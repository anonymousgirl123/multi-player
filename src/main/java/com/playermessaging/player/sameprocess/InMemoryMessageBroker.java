package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.broker.MessageBroker;
import com.playermessaging.player.common.broker.MessageEventListener;
import com.playermessaging.player.common.config.ConfigLoader;
import com.playermessaging.player.common.handler.LoggingHandler;
import com.playermessaging.player.common.handler.MessageHandler;
import com.playermessaging.player.common.handler.ValidationHandler;
import com.playermessaging.player.common.model.Message;
import com.playermessaging.player.common.model.MessageEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * In-memory {@link MessageBroker} implementation for the same-process scenario.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Maintain one bounded {@link LinkedBlockingQueue} per registered player as that player's
 *       private inbox.
 *   <li>Route an incoming {@link Message} to the correct inbox by looking up the recipient name in
 *       a {@link ConcurrentHashMap}.
 *   <li>Fan out {@link Message#BROADCAST} messages to every inbox except the sender's own inbox.
 *   <li>Apply backpressure: {@link #publish(Message)} blocks the sender if the recipient's inbox is
 *       full, preventing unbounded queue growth.
 *   <li>Remain thread-safe without explicit locking: all concurrency is delegated to {@link
 *       ConcurrentHashMap} and {@link LinkedBlockingQueue}.
 *   <li><strong>Observer Pattern — Subject:</strong> notify all registered {@link
 *       MessageEventListener}s after every {@link #publish} and {@link #receive} call.
 * </ul>
 *
 * <p>This implementation replaces the manually-wired pair of {@link InMemoryChannel}s used in the
 * original design. Instead of wiring A→B and B→A channels explicitly, {@link Conversation} now
 * creates a single broker, registers both players, and hands the broker to each player. Adding a
 * third player requires only one more {@code register} call.
 */
final class InMemoryMessageBroker implements MessageBroker {

    // Why ConcurrentHashMap<String, BlockingQueue> instead of two plain queues?
    //
    // The original design hardwired one InMemoryChannel per direction (A→B,
    // B→A).  That works perfectly for exactly 2 players but breaks the moment
    // a third player joins: you would need to add more channels and re-wire
    // every existing player.
    //
    // Here, each player owns a *named* inbox slot in this map.  Adding PlayerC
    // is a single broker.register("PlayerC") call — no existing code changes.
    // ConcurrentHashMap is chosen over a synchronised HashMap because register()
    // and publish() are called from different threads; CHM gives us fine-grained
    // locking for free without a monitor on the whole map.
    private final Map<String, BlockingQueue<Message>> inboxes = new ConcurrentHashMap<>();

    // Why a bounded queue capacity instead of unbounded LinkedBlockingQueue()?
    // An unbounded queue never blocks the producer.  Under sustained load a fast
    // producer paired with a slow consumer will keep growing the queue until the
    // JVM runs out of heap (OutOfMemoryError).  A bounded queue applies
    // backpressure: publish() blocks the sender when the inbox is full,
    // naturally throttling the producer to the consumer's pace without any
    // external rate-limiter.  The capacity comes from configuration.properties
    // so it can be tuned without recompiling.
    private final int queueCapacity;

    // ── Observer Pattern ─────────────────────────────────────────────────────
    // CopyOnWriteArrayList is used because:
    // 1. Listeners are added rarely (at startup) but iterated on every
    //    publish() and receive() call — a read-heavy workload.
    // 2. COWAL iteration is always consistent: it reads a stable snapshot
    //    of the list even if another thread is concurrently adding a listener,
    //    removing the need for explicit synchronisation in notifyListeners().
    private final List<MessageEventListener> listeners = new CopyOnWriteArrayList<>();

    // ── Chain of Responsibility ───────────────────────────────────────────────
    // The publish pipeline: ValidationHandler → LoggingHandler → DeliveryHandler.
    //
    // Why a chain here instead of inline if/else logic?
    // Each handler has exactly one responsibility (validate, log, deliver).
    // Adding a new concern — e.g. a RateLimitHandler or an EncryptionHandler —
    // means inserting a new link in the chain, not editing this class.
    // This keeps InMemoryMessageBroker closed for modification.
    //
    // The chain is built lazily in a factory method rather than at field
    // initialisation time because DeliveryHandler needs a reference to the
    // `inboxes` map, which is assigned before the constructor body runs but
    // after super() — safe to reference here.
    private final MessageHandler publishChain;

    /**
     * Creates a broker with the queue capacity read from {@code configuration.properties}. This is
     * the normal production constructor used by {@link Conversation}.
     */
    InMemoryMessageBroker() {
        this(ConfigLoader.getInt("inbox.queue.capacity"));
    }

    /**
     * Creates a broker with a custom queue capacity.
     *
     * <p>Why a second constructor? The benchmark needs to pass a large capacity (e.g. 64) to
     * prevent backpressure from dominating its throughput measurements. Injecting the capacity
     * directly avoids editing {@code configuration.properties} just for test/benchmark purposes.
     *
     * @param queueCapacity maximum messages buffered per player inbox; must be ≥ 1.
     */
    InMemoryMessageBroker(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, got: " + queueCapacity);
        }
        this.queueCapacity = queueCapacity;
        // Chain of Responsibility: build the publish pipeline.
        // ValidationHandler → LoggingHandler → DeliveryHandler (terminal)
        this.publishChain = new ValidationHandler(new LoggingHandler(new DeliveryHandler(inboxes)));
    }

    // -------------------------------------------------------------------------
    // MessageBroker implementation
    // -------------------------------------------------------------------------

    /**
     * Creates a new, bounded inbox queue for {@code playerName}.
     *
     * @throws IllegalArgumentException if {@code playerName} is already registered.
     */
    @Override
    public void register(String playerName) {
        // putIfAbsent is atomic on ConcurrentHashMap — no race condition
        // between the existence check and the insertion.
        if (inboxes.putIfAbsent(playerName, new LinkedBlockingQueue<>(queueCapacity)) != null) {
            throw new IllegalArgumentException("Player already registered: " + playerName);
        }
    }

    /**
     * Routes {@code message} through the publish pipeline (Validation → Logging → Delivery) and
     * then notifies all Observer listeners.
     *
     * <h3>Chain of Responsibility</h3>
     *
     * <p>The actual inbox placement is now handled by the {@code ValidationHandler → LoggingHandler
     * → DeliveryHandler} chain built in the constructor. This method is intentionally thin: it
     * delegates to the chain and fires the post-publish Observer event. Adding a new publish-time
     * concern (rate limiting, encryption) means inserting a new handler — this method stays
     * untouched.
     *
     * @throws IllegalArgumentException if validation fails or the recipient is not registered.
     * @throws RuntimeException if the calling thread is interrupted while waiting for space in a
     *     full inbox.
     */
    @Override
    public void publish(Message message) {
        // Chain of Responsibility: ValidationHandler → LoggingHandler → DeliveryHandler
        publishChain.handle(message);
        // Observer: notify all listeners that a message was published.
        notifyListeners(new MessageEvent(MessageEvent.Kind.PUBLISHED, message));
    }

    /**
     * Blocks until a message addressed to {@code playerName} arrives in its inbox, then removes and
     * returns it.
     *
     * <p>After removing the message, fires a {@link MessageEvent.Kind#DELIVERED} event to all
     * registered listeners.
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     * @throws IllegalArgumentException if {@code playerName} is not registered.
     */
    @Override
    public Message receive(String playerName) throws InterruptedException {
        BlockingQueue<Message> inbox = inboxes.get(playerName);
        if (inbox == null) {
            throw new IllegalArgumentException("Unknown player: " + playerName);
        }
        Message message = inbox.take();
        // Observer: notify all listeners that a message was delivered.
        notifyListeners(new MessageEvent(MessageEvent.Kind.DELIVERED, message));
        return message;
    }

    /**
     * Clears all inboxes. Threads blocked in {@link #receive} will remain blocked; callers are
     * responsible for interrupting them before or after calling {@code shutdown}.
     */
    @Override
    public void shutdown() {
        inboxes.clear();
    }

    // ── Observer Pattern — Subject ────────────────────────────────────────────

    /**
     * Registers a {@link MessageEventListener} to be notified after every {@link #publish} and
     * {@link #receive} call.
     *
     * <h3>Observer Pattern — Subject</h3>
     *
     * <p>Callers attach listeners once at startup (before the conversation begins), so the {@link
     * CopyOnWriteArrayList} write overhead is negligible. All subsequent read iterations in {@link
     * #notifyListeners} are lock-free.
     *
     * @param listener the observer to register; must not be {@code null}.
     */
    @Override
    public void addListener(MessageEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        listeners.add(listener);
    }

    /**
     * Iterates over all registered listeners and calls {@link MessageEventListener#onEvent} for
     * each one.
     *
     * <p>Exceptions thrown by individual listeners are caught and swallowed to prevent a
     * misbehaving listener from breaking the broker's event loop or disrupting other listeners.
     */
    private void notifyListeners(MessageEvent event) {
        for (MessageEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                // A bad listener must not disrupt the broker or other listeners.
                // In production code this would be logged at WARN level.
            }
        }
    }
}
