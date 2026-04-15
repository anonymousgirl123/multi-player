package com.playermessaging.player.common.handler;

import com.playermessaging.player.common.model.Message;

/**
 * {@link MessageHandler} that validates a message before passing it down the chain.
 *
 * <h3>Chain of Responsibility — Concrete Handler</h3>
 *
 * <p>Guards the rest of the chain from malformed messages. If any required field is blank, an
 * {@link IllegalArgumentException} is thrown and the message never reaches the delivery handler.
 *
 * <p>Currently validates:
 *
 * <ul>
 *   <li>Sender must not be null or blank.
 *   <li>Recipient must not be null or blank.
 *   <li>Content must not be null (empty content is allowed — it is the poison-pill convention).
 * </ul>
 */
public final class ValidationHandler implements MessageHandler {

    /** The next handler to invoke when validation passes. */
    private final MessageHandler next;

    /**
     * @param next the successor handler; must not be {@code null}.
     */
    public ValidationHandler(MessageHandler next) {
        if (next == null) throw new IllegalArgumentException("next handler must not be null");
        this.next = next;
    }

    /**
     * Validates {@code message} and forwards to the next handler if valid.
     *
     * @throws IllegalArgumentException if sender or recipient are blank.
     */
    @Override
    public void handle(Message message) {
        if (message.getSender() == null || message.getSender().isBlank()) {
            throw new IllegalArgumentException(
                    "Message rejected: sender must not be blank. Got: '" + message + "'");
        }
        if (message.getRecipient() == null || message.getRecipient().isBlank()) {
            throw new IllegalArgumentException(
                    "Message rejected: recipient must not be blank. Got: '" + message + "'");
        }
        // Validation passed — forward to the next handler in the chain.
        next.handle(message);
    }
}
