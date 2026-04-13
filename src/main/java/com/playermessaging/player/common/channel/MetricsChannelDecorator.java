package com.playermessaging.player.common.channel;

import com.playermessaging.player.common.model.Message;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link MessageChannel} decorator that counts messages sent and received through the channel.
 *
 * <h3>Decorator Pattern — Concrete Decorator</h3>
 *
 * <p>Wraps any {@link MessageChannel} and maintains atomic send/receive counters without modifying
 * the wrapped channel or the {@code Player} class.
 *
 * <p>Atomic counters are used because in a future bidirectional scenario the same channel object
 * might be used from two different threads. Atomics are also cheaper than {@code synchronized} for
 * single-writer cases.
 *
 * <p>Usage (can be stacked with other decorators):
 *
 * <pre>
 *   MetricsChannelDecorator metered = new MetricsChannelDecorator(
 *       new LoggingChannelDecorator(
 *           new SocketChannel(socket)));
 *
 *   // … run conversation …
 *
 *   System.out.println("sent="     + metered.getSentCount());
 *   System.out.println("received=" + metered.getReceivedCount());
 * </pre>
 */
public final class MetricsChannelDecorator extends MessageChannelDecorator {

    /** Total number of messages sent through this channel. */
    private final AtomicLong sentCount = new AtomicLong();

    /** Total number of messages received through this channel. */
    private final AtomicLong receivedCount = new AtomicLong();

    /**
     * @param delegate the channel to wrap; must not be {@code null}.
     */
    public MetricsChannelDecorator(MessageChannel delegate) {
        super(delegate);
    }

    /**
     * Increments the sent counter, then delegates to the wrapped channel. Counting before the send
     * means the counter reflects intent, not just successful delivery — consistent with {@link
     * PlayerMetrics#recordSend()}.
     */
    @Override
    public void send(Message message) throws Exception {
        sentCount.incrementAndGet();
        delegate.send(message);
    }

    /**
     * Delegates to the wrapped channel, then increments the received counter. Counting after the
     * receive ensures we only count successfully delivered messages.
     */
    @Override
    public Message receive() throws Exception {
        Message message = delegate.receive();
        receivedCount.incrementAndGet();
        return message;
    }

    /**
     * @return total messages sent through this channel.
     */
    public long getSentCount() {
        return sentCount.get();
    }

    /**
     * @return total messages received through this channel.
     */
    public long getReceivedCount() {
        return receivedCount.get();
    }
}
