package com.playermessaging.player.common.broker;

import com.playermessaging.player.common.model.Message;

/**
 * Central message router that decouples senders from receivers.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Maintain a registry of named players (subscribers).
 *   <li>Accept a {@link Message} from any sender and deliver it to the {@link
 *       Message#getRecipient() recipient} player's inbox by name, without the sender needing a
 *       direct reference to the receiver.
 *   <li>Support {@link Message#BROADCAST} delivery: fan out a single message to every registered
 *       subscriber (useful for shutdown signals).
 *   <li>Provide a clean {@link #shutdown()} path that releases any internal resources and unblocks
 *       waiting receivers.
 * </ul>
 *
 * <p>This interface is the key enabler for N-player conversations: adding a third, fourth, or Nth
 * player only requires registering it with the broker; no existing player or channel wiring changes
 * are needed.
 *
 * <p>Implementations must be <strong>thread-safe</strong> because multiple player threads will call
 * {@link #publish(Message)} and {@link #receive(String)} concurrently.
 */
// Why an interface and not a concrete class?
// Today there is one implementation (InMemoryMessageBroker) and two players.
// The interface exists so that future implementations — e.g. a
// KafkaMessageBroker or a JmsMessageBroker — can be swapped in without
// touching Player or Conversation.  It also makes the broker trivially
// mockable in unit tests.  If you are certain the system will never grow
// beyond an in-memory queue, feel free to inline InMemoryMessageBroker here;
// but the interface costs nothing and buys a clear extension point.
public interface MessageBroker {

    /**
     * Registers a player so that messages addressed to {@code playerName} can be delivered to it
     * via {@link #receive(String)}.
     *
     * <p>Must be called before any message is published that targets this player.
     *
     * @param playerName unique name identifying the player; must not be {@code null}.
     * @throws IllegalArgumentException if {@code playerName} is already registered.
     */
    void register(String playerName);

    /**
     * Routes {@code message} to the inbox of the player identified by {@link
     * Message#getRecipient()}.
     *
     * <p>If the recipient is {@link Message#BROADCAST}, the message is delivered to <em>every</em>
     * registered player except the sender.
     *
     * @param message the message to route; must not be {@code null}.
     * @throws IllegalArgumentException if the recipient is not registered and the message is not a
     *     broadcast.
     */
    void publish(Message message);

    /**
     * Blocks until a message addressed to {@code playerName} is available, then removes it from
     * that player's inbox and returns it.
     *
     * @param playerName the name of the receiving player; must be registered.
     * @return the next message for {@code playerName}; never {@code null}.
     * @throws InterruptedException if the calling thread is interrupted while waiting.
     * @throws IllegalArgumentException if {@code playerName} is not registered.
     */
    Message receive(String playerName) throws InterruptedException;

    /**
     * Signals all waiting receivers to stop and releases internal resources. After this call,
     * further {@link #publish} or {@link #receive} calls result in undefined behaviour.
     */
    void shutdown();

    // -------------------------------------------------------------------------
    // Observer Pattern — Subject interface
    // -------------------------------------------------------------------------

    /**
     * Registers a {@link MessageEventListener} to receive broker-level events.
     *
     * <h3>Observer Pattern — Subject</h3>
     *
     * <p>Listeners are notified (in registration order) after every {@link #publish} and {@link
     * #receive} call. This allows cross-cutting concerns — logging, metrics, alerting — to be
     * attached without modifying the broker implementation or any player code.
     *
     * <p>Default no-op so existing {@link MessageBroker} implementations that do not need listener
     * support compile without change.
     *
     * @param listener the listener to add; must not be {@code null}.
     */
    default void addListener(MessageEventListener listener) {
        // Default: no-op. Implementations that support listeners may override.
    }
}
