package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.handler.MessageHandler;
import com.playermessaging.player.common.model.Message;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Terminal {@link MessageHandler} in the publish pipeline — the handler that actually places the
 * message into the recipient's inbox queue.
 *
 * <h3>Chain of Responsibility — Terminal (Concrete) Handler</h3>
 *
 * <p>This is the last link in the chain assembled by {@link InMemoryMessageBroker}. It is
 * "terminal" in the sense that it never calls a successor; it performs the final action and
 * returns.
 *
 * <p>The chain typically looks like:
 *
 * <pre>
 *   ValidationHandler → LoggingHandler → DeliveryHandler
 * </pre>
 *
 * Each preceding handler performs its concern and passes the message forward. {@code
 * DeliveryHandler} enqueues the message (blocking on backpressure if the inbox is full) and the
 * chain completes.
 *
 * <p>Package-private because it is an implementation detail of {@link InMemoryMessageBroker} —
 * nothing outside this package needs to construct or reference it directly.
 */
final class DeliveryHandler implements MessageHandler {

    /**
     * Reference to the broker's live inbox map. Using the live map (not a snapshot) means newly
     * registered players are automatically visible to the handler without rebuilding the chain.
     */
    private final Map<String, BlockingQueue<Message>> inboxes;

    /**
     * @param inboxes the broker's inbox registry; must not be {@code null}.
     */
    DeliveryHandler(Map<String, BlockingQueue<Message>> inboxes) {
        if (inboxes == null) throw new IllegalArgumentException("inboxes must not be null");
        this.inboxes = inboxes;
    }

    /**
     * Places {@code message} in the recipient's inbox, applying backpressure if the queue is full.
     * Supports {@link Message#BROADCAST} fan-out.
     *
     * <p>This is a terminal handler — it does not forward to a successor.
     *
     * @throws IllegalArgumentException if the named recipient is not registered.
     */
    @Override
    public void handle(Message message) {
        if (Message.BROADCAST.equals(message.getRecipient())) {
            inboxes.forEach(
                    (name, inbox) -> {
                        if (!name.equals(message.getSender())) {
                            putInterruptibly(inbox, message);
                        }
                    });
        } else {
            BlockingQueue<Message> inbox = inboxes.get(message.getRecipient());
            if (inbox == null) {
                throw new IllegalArgumentException("Unknown recipient: " + message.getRecipient());
            }
            putInterruptibly(inbox, message);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------

    private static void putInterruptibly(BlockingQueue<Message> inbox, Message message) {
        try {
            inbox.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("DeliveryHandler interrupted while enqueuing message.", e);
        }
    }
}
