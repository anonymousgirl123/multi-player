package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.broker.MessageBroker;
import com.playermessaging.player.common.model.Message;
import com.playermessaging.player.common.model.PlayerMetrics;
import com.playermessaging.player.common.role.PlayerRole;
import com.playermessaging.player.common.role.PlayerRole.RoleAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conversational participant that exchanges messages through a shared {@link MessageBroker}.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Run the message loop: receive from the broker, delegate each incoming message to the
 *       injected {@link PlayerRole}, and act on the returned {@link RoleAction}.
 *   <li>Delegate message counting and latency recording to a {@link PlayerMetrics} instance, and
 *       expose the counters to the role so it can make informed decisions without holding its own
 *       state.
 *   <li>Print a metrics summary via SLF4J at the end of the conversation.
 *   <li>Handle the poison-pill shutdown signal transparently, regardless of which role is active.
 * </ul>
 *
 * <p>This class is intentionally <strong>role-agnostic</strong>: it contains zero role-specific
 * logic. It does not know whether it is the initiator or the responder — that knowledge lives
 * entirely in the injected {@link PlayerRole}. Swapping the role object changes the player's
 * behaviour without touching this class at all.
 *
 * <p>Why remove {@code initiate()} / {@code listen()} in favour of a single {@link #start()}
 * method? <br>
 * Previously the two entry points encoded role-specific behaviour: {@code initiate} sent the first
 * message; {@code listen} did not. With role objects that distinction moves into {@link
 * PlayerRole#onConversationStart()}, so a single {@code start()} method is sufficient. {@code
 * Player} asks the role what to do at the start of the conversation, just as it asks what to do on
 * every subsequent message.
 *
 * <p>Thread-safety: each {@code Player} instance is driven by exactly one thread. Its mutable state
 * ({@link PlayerMetrics}) is never accessed from another thread.
 */
final class Player {

    // One logger per class — cheap shared constant, thread-safe by SLF4J contract.
    private static final Logger log = LoggerFactory.getLogger(Player.class);

    /** This player's unique identity — used as sender label and inbox address. */
    private final String name;

    /** The name of the single peer this player exchanges messages with. */
    private final String peerName;

    /** Shared broker that routes all messages. */
    private final MessageBroker broker;

    // Why inject a PlayerRole instead of a boolean isInitiator?
    // A boolean forces Player to contain two diverging code paths that grow
    // with every new role added (observer, moderator, etc.).  A role object
    // encapsulates the full behaviour for one role in one place.  Player's
    // loop stays identical regardless of how many roles exist.
    // See PlayerRole, InitiatorRole, and ResponderRole for the concrete behaviours.
    private final PlayerRole role;

    /**
     * Collects per-message statistics (sent count, received count, latency).
     *
     * <p>Why {@link PlayerMetrics} instead of plain int counters? Metrics combines counting
     * <em>and</em> latency tracking in one cohesive object, keeps this class focused on the
     * messaging loop, and makes it easy to extend reporting (histograms, percentiles) without
     * touching Player again.
     */
    private final PlayerMetrics metrics = new PlayerMetrics();

    /**
     * Creates a new player.
     *
     * @param name unique display name; must be registered with {@code broker}.
     * @param peerName the name of the peer this player will talk to.
     * @param broker the shared message broker; must not be {@code null}.
     * @param role the role strategy that drives this player's behaviour; must not be {@code null}.
     */
    Player(String name, String peerName, MessageBroker broker, PlayerRole role) {
        this.name = name;
        this.peerName = peerName;
        this.broker = broker;
        this.role = role;
    }

    /**
     * Starts the player's message loop.
     *
     * <p>First, the role is asked what to do at conversation start (send an opening message, or do
     * nothing). Then the loop blocks on {@link MessageBroker#receive}, delegates each message to
     * the role, and acts on the returned {@link RoleAction} until a stop or poison-pill signal is
     * received. A metrics summary is logged on every exit path.
     *
     * <p><strong>Interruption contract:</strong> if the thread is interrupted while blocked in
     * {@link MessageBroker#receive}, the interrupt flag is restored before returning so that
     * callers higher up the stack (e.g. a thread pool or shutdown coordinator) can observe and act
     * on it.
     *
     * @throws Exception if the broker raises a non-interruption error.
     */
    void start() throws Exception {
        // Let the role decide whether to send an opening message.
        // InitiatorRole returns reply("Hello"); ResponderRole returns doNothing().
        // Player does not need to know which one is active.
        handleAction(role.onConversationStart());

        // Core message loop — fully role-agnostic.
        while (true) {
            Message incoming;
            try {
                incoming = broker.receive(name);
            } catch (InterruptedException e) {
                // Why restore the flag instead of swallowing the exception?
                // InterruptedException clears the thread's interrupt flag as a side
                // effect.  If we simply return without re-setting it, any caller
                // that checks Thread.currentThread().isInterrupted() — e.g. a
                // thread-pool executor or a join() in Conversation — will miss the
                // signal and may hang or behave incorrectly.
                // Restoring the flag preserves the cooperative cancellation contract
                // defined in Java Concurrency in Practice (§7.1.2).
                Thread.currentThread().interrupt();
                log("interrupted while waiting for message – shutting down.");
                logMetrics();
                return;
            }

            // Poison-pill: the initiator has signalled the end of the conversation.
            // This check lives in Player (not in the role) because it is a
            // transport-level shutdown mechanism, not a role-specific decision.
            //
            // Why forward before exiting (ring propagation)?
            // In a 2-player setup the initiator sends directly to its only peer,
            // so one pill is enough.  In an N-player ring the pill must travel
            // the whole ring to reach every player: A fires it to B, B forwards
            // it to C, C to D, …, until the last player forwards it back to A
            // (which has already exited — its inbox is simply never drained).
            // Without forwarding, only the first responder would shut down and
            // all others would block forever on broker.receive().
            if (incoming.getContent().isEmpty()) {
                log("received stop signal – forwarding and shutting down.");
                broker.publish(new Message(name, peerName, "")); // cascade the pill
                logMetrics();
                return;
            }

            metrics.recordReceive(incoming);
            log(
                    "received (#"
                            + metrics.getReceivedCount()
                            + "): \""
                            + incoming.getContent()
                            + "\"");

            // Delegate entirely to the role — no if/else on role type here.
            RoleAction action =
                    role.onMessageReceived(
                            incoming.getContent(),
                            metrics.getSentCount(),
                            metrics.getReceivedCount());

            if (handleAction(action)) {
                logMetrics();
                return; // role signalled end of conversation
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Executes {@code action} and returns {@code true} if the conversation should end (i.e. a stop
     * signal was sent).
     *
     * <p>Centralising action dispatch here means the loop body stays clean and every action kind is
     * handled in exactly one place.
     */
    private boolean handleAction(RoleAction action) throws Exception {
        switch (action.getKind()) {
            case SEND_REPLY:
                send(action.getReplyContent());
                return false;

            case SEND_STOP_SIGNAL:
                log("stop condition reached – sending stop signal and finishing.");
                // Empty content is the poison-pill convention understood by all players.
                broker.publish(new Message(name, peerName, ""));
                return true;

            case DO_NOTHING:
            default:
                return false;
        }
    }

    /**
     * Builds a {@link Message} addressed to {@link #peerName}, records the send in metrics, logs
     * the action, and publishes it via the broker.
     */
    private void send(String content) throws Exception {
        metrics.recordSend();
        Message message = new Message(name, peerName, content);
        log("sending  (#" + metrics.getSentCount() + "): \"" + content + "\"");
        broker.publish(message);
    }

    /**
     * Logs the metrics summary at conversation end.
     *
     * <p>Using {@link Logger#info} means the summary respects the configured log level — suppress
     * it by setting {@code log.level=WARN} in {@code configuration.properties}.
     */
    private void logMetrics() {
        log.info("{}", metrics.summarise(name));
    }

    /**
     * Emits an INFO-level log line prefixed with this player's name.
     *
     * <p>Using SLF4J's parameterised logging ({@code {}}) avoids string concatenation when the INFO
     * level is disabled — the arguments are only formatted if the message is actually written.
     */
    private void log(String event) {
        log.info("[{}] {}", name, event);
    }
}
