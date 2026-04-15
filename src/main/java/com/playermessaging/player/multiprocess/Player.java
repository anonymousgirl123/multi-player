package com.playermessaging.player.multiprocess;

import com.playermessaging.player.common.channel.MessageChannel;
import com.playermessaging.player.common.model.Message;
import com.playermessaging.player.common.model.PlayerMetrics;
import com.playermessaging.player.common.role.PlayerRole;
import com.playermessaging.player.common.role.PlayerRole.RoleAction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A conversational participant designed to run in its own OS process.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Run the message loop over a TCP {@link SocketChannel}: receive a message, delegate to the
 *       injected {@link PlayerRole}, and act on the returned {@link RoleAction}.
 *   <li>Delegate message counting and latency recording to a {@link PlayerMetrics} instance, and
 *       expose the counters to the role so it can make informed decisions without holding its own
 *       state.
 *   <li>Print a metrics summary via SLF4J at the end of the conversation.
 *   <li>Handle the poison-pill shutdown signal transparently.
 * </ul>
 *
 * <p>This class is intentionally <strong>role-agnostic</strong> — identical in structure to its
 * same-process counterpart. The only difference is the transport: {@link SocketChannel} instead of
 * {@link com.playermessaging.player.sameprocess.InMemoryMessageBroker}. Both variants share the
 * same {@link PlayerRole} interface and implementations, which ensures consistent behaviour
 * regardless of the underlying transport.
 *
 * <p>Why a single {@link #start()} instead of {@code initiate()} / {@code listen()}? <br>
 * The role object carries that distinction: {@link com.playermessaging.player.common.InitiatorRole}
 * sends the opening message at start; {@link com.playermessaging.player.common.ResponderRole} does
 * nothing. {@code Player} asks, acts, and loops — it never inspects which role it holds.
 */
final class Player {

    // One logger per class — cheap shared constant, thread-safe by SLF4J contract.
    private static final Logger log = LoggerFactory.getLogger(Player.class);

    /** Human-readable identifier used in log output and message attribution. */
    private final String name;

    /** The name of the peer this player is talking to (used to address outgoing messages). */
    private final String peerName;

    /**
     * Bidirectional channel backed by a TCP socket. Reads and writes both flow through the same
     * object.
     */
    private final MessageChannel channel;

    // Why inject a PlayerRole instead of a boolean isInitiator?
    // See sameprocess.Player for the full rationale.  Short version: a role object
    // removes all if/else branching from the loop and makes adding new roles
    // (observer, moderator, etc.) a matter of adding a new class — not editing
    // this one.
    private final PlayerRole role;

    /**
     * Collects per-message statistics (sent count, received count, latency).
     *
     * <p>In the multi-process scenario latency includes TCP transmission time in addition to
     * queue-wait and scheduling overhead, making the numbers meaningfully larger than in the
     * same-process scenario.
     */
    private final PlayerMetrics metrics = new PlayerMetrics();

    /**
     * Creates a new player.
     *
     * @param name display name; must not be {@code null}.
     * @param peerName the name of the peer this player addresses messages to.
     * @param channel the duplex channel connecting this player to its peer.
     * @param role the role strategy that drives this player's behaviour.
     */
    Player(String name, String peerName, MessageChannel channel, PlayerRole role) {
        this.name = name;
        this.peerName = peerName;
        this.channel = channel;
        this.role = role;
    }

    /**
     * Starts the player's message loop.
     *
     * <p>Asks the role what to do at conversation start, then loops: receive, delegate to role, act
     * on the returned {@link RoleAction}. A metrics summary is logged on every exit path.
     *
     * @throws Exception if the channel raises an I/O error.
     */
    void start() throws Exception {
        // Let the role decide whether to send an opening message.
        handleAction(role.onConversationStart());

        // Core loop — fully role-agnostic.
        while (true) {
            Message incoming = channel.receive();

            // Poison-pill: the initiator has signalled the end of the conversation.
            //
            // Why forward before exiting (ring propagation)?
            // In an N-player ring the pill must travel the whole ring to reach
            // every player: A fires it to B, B forwards it to C, C to D, …,
            // until the last player forwards it back to A (which has already
            // exited — harmless).  Without forwarding, only the first responder
            // would shut down; all others would block forever on channel.receive().
            if (incoming.getContent().isEmpty()) {
                log("received stop signal – forwarding and shutting down.");
                channel.send(new Message(name, peerName, "")); // cascade the pill
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

    /** Executes {@code action} and returns {@code true} if the conversation should end. */
    private boolean handleAction(RoleAction action) throws Exception {
        switch (action.getKind()) {
            case SEND_REPLY:
                send(action.getReplyContent());
                return false;

            case SEND_STOP_SIGNAL:
                log("stop condition reached – sending stop signal and finishing.");
                channel.send(new Message(name, peerName, "")); // poison pill
                return true;

            case DO_NOTHING:
            default:
                return false;
        }
    }

    /** Builds, records in metrics, logs, and dispatches a single outgoing message. */
    private void send(String content) throws Exception {
        metrics.recordSend();
        Message message = new Message(name, peerName, content);
        log("sending  (#" + metrics.getSentCount() + "): \"" + content + "\"");
        channel.send(message);
    }

    /** Logs the metrics summary at conversation end. */
    private void logMetrics() {
        log.info("{}", metrics.summarise(name));
    }

    /**
     * Emits an INFO-level log line prefixed with this player's name.
     *
     * <p>The PID is omitted here because the Logback pattern already stamps the thread name, which
     * uniquely identifies the virtual thread in the same-process scenario. In the multi-process
     * scenario the PID appears in the startup banner printed by {@link MultiProcessMain}.
     */
    private void log(String event) {
        log.info("[{}] {}", name, event);
    }
}
