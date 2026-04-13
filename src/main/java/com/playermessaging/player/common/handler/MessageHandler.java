package com.playermessaging.player.common.handler;

import com.playermessaging.player.common.model.Message;

/**
 * Handler node in a publish-pipeline chain.
 *
 * <h3>Chain of Responsibility Pattern — Handler</h3>
 *
 * <p>Each handler performs one focused concern on a {@link Message} being published (validation,
 * logging, delivery) and then calls {@link #handle(Message)} on the next handler in the chain.
 *
 * <p>A handler may short-circuit the chain by <em>not</em> calling {@link #handle(Message)} on its
 * successor — for example, a validation handler that rejects a malformed message.
 *
 * <p>The chain is assembled by the caller:
 *
 * <pre>
 *   MessageHandler chain =
 *       new ValidationHandler(
 *           new LoggingHandler(
 *               new DeliveryHandler(inboxes)));
 *
 *   chain.handle(message);
 * </pre>
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link ValidationHandler} — rejects messages with blank sender/recipient.
 *   <li>{@link LoggingHandler} — logs the message at DEBUG level.
 *   <li>{@link DeliveryHandler} — the terminal handler that places the message in the recipient's
 *       inbox.
 * </ul>
 */
public interface MessageHandler {

    /**
     * Processes {@code message}, optionally forwarding to the next handler.
     *
     * @param message the message being processed; never {@code null}.
     * @throws IllegalArgumentException if a handler rejects the message.
     * @throws RuntimeException if an unrecoverable error occurs.
     */
    void handle(Message message);
}
