package com.playermessaging.player.common.channel;

import com.playermessaging.player.common.model.Message;

/**
 * Abstract base class for {@link MessageChannel} decorators.
 *
 * <h3>Decorator Pattern — Abstract Decorator</h3>
 *
 * <p>This class sits between the {@link MessageChannel} interface and the concrete decorators. It
 * holds a reference to the <em>wrapped</em> channel (the component) and delegates all three
 * interface methods to it. Concrete decorators extend this class and override only the methods they
 * need to enrich, inheriting the delegation for everything else.
 *
 * <p>Why an abstract class instead of delegating in each concrete decorator? <br>
 * Without this base class every concrete decorator would repeat the same three-line delegation. The
 * abstract class centralises that boilerplate and enforces the constructor contract (delegate must
 * not be null) in one place.
 *
 * <p>Composition chain example:
 *
 * <pre>
 *   MessageChannel channel =
 *       new LoggingChannelDecorator(
 *           new MetricsChannelDecorator(
 *               new SocketChannel(socket)));   // the concrete component
 * </pre>
 *
 * Each decorator wraps the next; {@code Player} only sees {@code MessageChannel}.
 */
public abstract class MessageChannelDecorator implements MessageChannel {

    /** The channel being decorated; never {@code null}. */
    protected final MessageChannel delegate;

    /**
     * @param delegate the channel to wrap; must not be {@code null}.
     */
    protected MessageChannelDecorator(MessageChannel delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.delegate = delegate;
    }

    /**
     * Delegates to the wrapped channel. Concrete decorators that need to add behaviour before or
     * after sending must call {@code super.send(message)} (or {@code delegate.send(message)}) at
     * the appropriate point.
     */
    @Override
    public void send(Message message) throws Exception {
        delegate.send(message);
    }

    /** Delegates to the wrapped channel. */
    @Override
    public Message receive() throws Exception {
        return delegate.receive();
    }

    /** Delegates to the wrapped channel. */
    @Override
    public void close() {
        delegate.close();
    }
}
