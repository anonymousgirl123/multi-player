package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.broker.LoggingEventListener;
import com.playermessaging.player.common.broker.MessageBroker;
import com.playermessaging.player.common.broker.MetricsEventListener;
import com.playermessaging.player.common.config.ConfigLoader;
import com.playermessaging.player.common.factory.InitiatorComponentFactory;
import com.playermessaging.player.common.factory.PlayerComponentFactory;
import com.playermessaging.player.common.factory.ResponderComponentFactory;
import com.playermessaging.player.common.role.PlayerRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates an N-player conversation inside a single JVM process.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Accept an ordered list of player names and wire them into a <em>ring topology</em>: each
 *       player sends to the next one in the list, and the last player sends back to the first.
 *   <li>Create a single shared {@link InMemoryMessageBroker} and register all players with it.
 *   <li>Assign {@link InitiatorRole} to the first player and {@link ResponderRole} to every other
 *       player.
 *   <li>Submit each player as a task to a virtual-thread {@link ExecutorService} and await
 *       completion via {@link Future#get()}.
 *   <li>Shut down the executor and broker on completion.
 * </ul>
 *
 * <p><strong>Ring topology explained:</strong> <br>
 * With players {@code [A, B, C]}, the message path is A → B → C → A. Each reply travels one step
 * forward around the ring until it returns to the initiator, who then decides whether to continue
 * or stop. When the initiator fires a poison-pill it goes to B, B forwards it to C, C forwards it
 * back to A — every player in the ring shuts down in order.
 *
 * <p>Why a ring and not a star (all-to-initiator)? <br>
 * A ring keeps the message-counter semantics from the 2-player design intact: each player appends
 * its own sent-counter to the message as it passes through. A star would require a separate
 * aggregation step and would break the simple append protocol. The ring is also the most natural
 * generalisation of the original A ↔ B ping-pong.
 *
 * <p>Why is this class now data-driven instead of hardcoding two players? <br>
 * The original design hard-coded {@code PLAYER_A} / {@code PLAYER_B} constants and manually created
 * two objects. Every new player required editing this class. The list-driven approach means adding
 * a third player is a one-word change in {@link SameProcessMain}: add {@code "PlayerC"} to the
 * list. {@code Conversation} itself never changes.
 */
final class Conversation {

    private static final Logger log = LoggerFactory.getLogger(Conversation.class);

    /**
     * Ordered list of player names that participate in the conversation. The first name is the
     * initiator; all others are responders. The list must contain at least two names.
     */
    private final List<String> playerNames;

    /**
     * Number of messages the initiator sends before ending the conversation. Sourced from
     * configuration or overridden via {@link ConversationBuilder}.
     */
    private final int maxMessages;

    /**
     * First message sent by the initiator to start the conversation. Sourced from configuration or
     * overridden via {@link ConversationBuilder}.
     */
    private final String openingMessage;

    /**
     * Maximum messages buffered in each player's inbox (backpressure). Sourced from configuration
     * or overridden via {@link ConversationBuilder}.
     */
    private final int queueCapacity;

    /**
     * Creates a conversation with the default 2-player setup (PlayerA, PlayerB). All configuration
     * values are read from {@code configuration.properties}. Preserved for backward-compatibility
     * with {@link SameProcessMain}.
     */
    Conversation() {
        this(
                Arrays.asList("PlayerA", "PlayerB"),
                ConfigLoader.getInt("max.messages"),
                ConfigLoader.getString("opening.message"),
                ConfigLoader.getInt("inbox.queue.capacity"));
    }

    /**
     * Creates a conversation for the given ordered list of player names. Uses configuration-file
     * values for maxMessages, openingMessage, and queueCapacity — identical to the old single-arg
     * constructor.
     *
     * <p>The first name in the list will be assigned the initiator role; all others receive the
     * responder role. Messages flow in a ring.
     *
     * @param playerNames at least two unique player names; must not be {@code null}.
     * @throws IllegalArgumentException if fewer than two names are provided.
     */
    Conversation(List<String> playerNames) {
        this(
                playerNames,
                ConfigLoader.getInt("max.messages"),
                ConfigLoader.getString("opening.message"),
                ConfigLoader.getInt("inbox.queue.capacity"));
    }

    /**
     * Full constructor used by {@link ConversationBuilder}.
     *
     * <h3>Builder Pattern — Product Constructor</h3>
     *
     * <p>Receives all configuration from the builder; all fields are validated once here so neither
     * the builder nor the caller can bypass the guards.
     *
     * @param playerNames at least two unique player names; must not be {@code null}.
     * @param maxMessages messages to exchange before stopping; must be &gt; 0.
     * @param openingMessage first message sent by the initiator; must not be {@code null}.
     * @param queueCapacity inbox buffer size per player; must be ≥ 1.
     * @throws IllegalArgumentException on any invalid argument.
     */
    Conversation(
            List<String> playerNames, int maxMessages, String openingMessage, int queueCapacity) {
        if (playerNames == null || playerNames.size() < 2) {
            throw new IllegalArgumentException(
                    "A conversation requires at least 2 players, got: "
                            + (playerNames == null ? "null" : playerNames.size()));
        }
        if (maxMessages <= 0) throw new IllegalArgumentException("maxMessages must be > 0");
        if (openingMessage == null)
            throw new IllegalArgumentException("openingMessage must not be null");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        // Defensive copy: caller changes to the list must not affect wiring.
        this.playerNames = new ArrayList<>(playerNames);
        this.maxMessages = maxMessages;
        this.openingMessage = openingMessage;
        this.queueCapacity = queueCapacity;
    }

    /**
     * Runs the full conversation and blocks until all players have finished.
     *
     * <p>Each player is submitted as a {@link Runnable} to a {@link
     * Executors#newVirtualThreadPerTaskExecutor()} — a Java 21 executor that spins up one virtual
     * thread per submitted task.
     *
     * <p>Why {@code ExecutorService} instead of raw {@code Thread} management? <br>
     * An executor decouples <em>what to run</em> (the player tasks) from <em>how to run it</em>
     * (the threading strategy). Swapping to a fixed platform-thread pool — or a custom scheduler —
     * is a one-line change in this method; {@link Player} and every role class remain untouched.
     * Executors also manage task lifecycle, exception capture via {@link Future}, and orderly
     * shutdown, removing the need for the manual start/join loops we previously maintained.
     *
     * <p>Why {@code newVirtualThreadPerTaskExecutor()}? <br>
     * Virtual threads (JEP 444, GA in Java 21) cost ~1 KB of heap each versus ~1 MB for a platform
     * (OS) thread. Players spend the vast majority of their time <em>blocked</em> on {@link
     * InMemoryMessageBroker#receive} — the ideal use-case for virtual threads, which are
     * transparently unmounted from their carrier thread whenever they block on I/O or a lock,
     * freeing the carrier for other tasks.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting for player
     *     tasks to complete.
     */
    void start() throws InterruptedException {
        // Builder Pattern: queueCapacity was set at construction time (via builder
        // or defaults), so the broker respects whatever the builder configured.
        MessageBroker broker = new InMemoryMessageBroker(queueCapacity);
        // Observer Pattern: attach listeners to the broker.
        broker.addListener(new LoggingEventListener());
        MetricsEventListener brokerMetrics = new MetricsEventListener();
        broker.addListener(brokerMetrics);

        // Register every player with the broker so messages can be routed by name.
        for (String name : playerNames) {
            broker.register(name);
        }

        // Build player tasks — ring wiring: player[i] sends to player[(i+1) % size].
        // This works identically for 2 players (A→B, B→A) and for N players.
        List<Runnable> tasks = new ArrayList<>();
        List<String> taskNames = new ArrayList<>();
        int size = playerNames.size();

        for (int i = 0; i < size; i++) {
            String name = playerNames.get(i);
            // The modulo wraps the last player back to the first, closing the ring.
            String peerName = playerNames.get((i + 1) % size);

            // Abstract Factory Pattern: choose the factory based on player index.
            // The first player is the initiator; all others are responders.
            // Swapping roles or policies for a player means injecting a different
            // factory — Conversation never needs to know which concrete types are used.
            PlayerComponentFactory factory =
                    (i == 0)
                            ? new InitiatorComponentFactory(openingMessage, maxMessages)
                            : new ResponderComponentFactory();
            PlayerRole role = factory.createRole();

            Player player = new Player(name, peerName, broker, role);
            taskNames.add(name);
            tasks.add(
                    () -> {
                        try {
                            player.start();
                        } catch (InterruptedException e) {
                            // The virtual thread was interrupted (e.g. executor shutdownNow()).
                            // Restore the flag so the executor's bookkeeping sees it.
                            Thread.currentThread().interrupt();
                            log.warn("[{}] interrupted – exiting.", name);
                        } catch (Exception e) {
                            log.error("[{}] error: {}", name, e.getMessage(), e);
                        }
                    });
        }

        // Use a virtual-thread-per-task executor (Java 21, JEP 444).
        //
        // Why not Executors.newFixedThreadPool(size)?
        // A fixed pool caps concurrency at `size` OS threads and forces them to
        // block on the OS when waiting for messages — expensive and limited by
        // the OS thread ceiling.  A virtual-thread executor has no such ceiling:
        // blocked virtual threads are transparently parked, and the underlying
        // carrier threads are reused for other tasks.  For a messaging system
        // where players spend most time blocked, this is the optimal choice.
        //
        // try-with-resources calls executor.close() which is equivalent to
        // shutdown() + awaitTermination(Long.MAX_VALUE) — it blocks until all
        // submitted tasks complete, then releases the executor.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // Register a shutdown hook so SIGTERM / Ctrl-C triggers a clean stop.
            // executor.shutdownNow() interrupts all virtual threads, causing their
            // blocked receive() calls to throw InterruptedException, which each
            // player catches and exits cleanly.
            Thread shutdownHook =
                    new Thread(
                            () -> {
                                log.warn(
                                        "[Conversation] Shutdown hook triggered – stopping"
                                                + " executor.");
                                executor.shutdownNow();
                                broker.shutdown();
                            },
                            "conversation-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(shutdownHook);

            // Submit all player tasks.  The executor assigns one virtual thread
            // per task automatically.
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(executor.submit(task));
            }

            // Await each task in submission order.  Because we submitted all tasks
            // before waiting on any of them, all players are running concurrently
            // before the first get() call — no player can be missed.
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    log.error(
                            "[{}] task failed: {}",
                            taskNames.get(i),
                            e.getCause().getMessage(),
                            e.getCause());
                }
            }

            // All tasks done — deregister the hook so it is not fired during
            // normal JVM exit (would try to interrupt already-finished tasks).
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } // executor.close() blocks here until all tasks finish (already done above)

        // Observer Pattern: print aggregate broker metrics after all players finish.
        log.info("{}", brokerMetrics.summarise());
        broker.shutdown();
    }
}
