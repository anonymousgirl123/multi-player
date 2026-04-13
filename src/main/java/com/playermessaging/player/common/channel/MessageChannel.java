package com.playermessaging.player.common.channel;

import com.playermessaging.player.common.model.Message;

/**
 * Abstraction for a one-directional, asynchronous message channel.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Define the contract for sending and receiving {@link Message} objects without coupling
 *       callers to a specific transport mechanism.
 *   <li>Allow different implementations to provide in-memory (same-process) or network-based
 *       (multi-process) delivery transparently.
 * </ul>
 *
 * <p>Both {@link #send(Message)} and {@link #receive()} may block: {@code send} may block if the
 * underlying buffer is full, and {@code receive} blocks until a message is available.
 */
public interface MessageChannel {

    /**
     * Sends {@code message} through this channel.
     *
     * @param message the message to send; must not be {@code null}.
     * @throws Exception if the channel is closed or an I/O error occurs.
     */
    void send(Message message) throws Exception;

    /**
     * Blocks until a message is available and returns it.
     *
     * @return the next available message; never {@code null}.
     * @throws Exception if the channel is closed or an I/O error occurs.
     */
    Message receive() throws Exception;

    /**
     * Releases any resources held by this channel. Implementations that wrap I/O streams should
     * close them here.
     */
    void close();
}
