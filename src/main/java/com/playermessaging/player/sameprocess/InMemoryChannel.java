package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.channel.MessageChannel;
import com.playermessaging.player.common.model.Message;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * In-memory {@link MessageChannel} backed by a {@link LinkedBlockingQueue}.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Provide a thread-safe, non-network message pipe between two {@link Player} instances that
 *       live inside the <em>same</em> JVM process.
 *   <li>Block the calling thread on {@link #receive()} until a message arrives, enabling a simple
 *       producer-consumer pattern without busy-waiting.
 * </ul>
 *
 * <p>One {@code InMemoryChannel} instance represents one direction of communication. A full duplex
 * link between Player A and Player B therefore requires two instances: one for A→B and one for B→A.
 */
final class InMemoryChannel implements MessageChannel {

    /**
     * Unbounded queue that acts as the message buffer. {@link LinkedBlockingQueue} is thread-safe
     * by design.
     */
    private final BlockingQueue<Message> queue = new LinkedBlockingQueue<>();

    /**
     * Places {@code message} at the tail of the queue. Because the queue is unbounded this never
     * blocks in practice.
     */
    @Override
    public void send(Message message) {
        queue.add(message);
    }

    /**
     * Removes and returns the head of the queue, blocking until one is available.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting.
     */
    @Override
    public Message receive() throws InterruptedException {
        return queue.take();
    }

    /** No-op: an in-memory queue holds no external resources. */
    @Override
    public void close() {
        // nothing to release for an in-memory queue
    }
}
