package com.playermessaging.player.common.handler;

import com.playermessaging.player.common.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MessageHandler} that logs a message at TRACE level before passing it down the chain.
 *
 * <h3>Chain of Responsibility — Concrete Handler</h3>
 *
 * <p>Adds a zero-overhead audit trail at TRACE level: when the log level is higher than TRACE,
 * SLF4J skips string formatting entirely so there is no performance cost in normal operation.
 *
 * <p>Log format:
 *
 * <pre>
 *   [PublishChain] [PlayerA → PlayerB @ 2024-...] Hello
 * </pre>
 */
public final class LoggingHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingHandler.class);

    /** The next handler to invoke after logging. */
    private final MessageHandler next;

    /**
     * @param next the successor handler; must not be {@code null}.
     */
    public LoggingHandler(MessageHandler next) {
        if (next == null) throw new IllegalArgumentException("next handler must not be null");
        this.next = next;
    }

    /** Logs the message at TRACE level, then forwards to the next handler. */
    @Override
    public void handle(Message message) {
        log.trace("[PublishChain] {}", message);
        next.handle(message);
    }
}
