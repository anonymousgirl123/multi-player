package com.playermessaging.player.common.channel;

import com.playermessaging.player.common.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MessageChannel} decorator that logs every send and receive at DEBUG level.
 *
 * <h3>Decorator Pattern — Concrete Decorator</h3>
 *
 * <p>Wraps any {@link MessageChannel} and adds channel-level logging without modifying the wrapped
 * channel or the {@code Player} class.
 *
 * <p>Usage:
 *
 * <pre>
 *   MessageChannel channel =
 *       new LoggingChannelDecorator(new SocketChannel(socket));
 * </pre>
 *
 * <p>Log format:
 *
 * <pre>
 *   [Channel SEND]    [PlayerA → PlayerB @ ...] Hello
 *   [Channel RECEIVE] [PlayerA → PlayerB @ ...] Hello 1
 * </pre>
 *
 * <p>Why DEBUG and not INFO? <br>
 * Channel-level logging is high-frequency (one line per message per direction) and is most useful
 * when diagnosing a specific issue rather than in normal operations. Keeping it at DEBUG means it
 * is invisible at the default INFO level and can be enabled on demand via {@code
 * -Dlog.level=DEBUG}.
 */
public final class LoggingChannelDecorator extends MessageChannelDecorator {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannelDecorator.class);

    /**
     * @param delegate the channel to wrap; must not be {@code null}.
     */
    public LoggingChannelDecorator(MessageChannel delegate) {
        super(delegate);
    }

    /** Logs the outgoing message, then delegates to the wrapped channel. */
    @Override
    public void send(Message message) throws Exception {
        log.debug("[Channel SEND]    {}", message);
        delegate.send(message);
    }

    /**
     * Delegates to the wrapped channel, then logs the received message. Logging after the receive
     * ensures the message exists before it is logged.
     */
    @Override
    public Message receive() throws Exception {
        Message message = delegate.receive();
        log.debug("[Channel RECEIVE] {}", message);
        return message;
    }
}
