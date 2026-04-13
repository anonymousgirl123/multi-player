package com.playermessaging.player.common.broker;

import com.playermessaging.player.common.model.MessageEvent;

/**
 * Observer interface for broker-level messaging events.
 *
 * <h3>Observer Pattern — Observer</h3>
 *
 * <p>Implementors register with a {@link MessageBroker} (the <em>subject</em>) and are notified via
 * {@link #onEvent(MessageEvent)} every time a message is published or delivered.
 *
 * <p>Built-in implementations:
 *
 * <ul>
 *   <li>{@link com.playermessaging.player.common.LoggingEventListener} — logs each event at DEBUG
 *       level via SLF4J.
 *   <li>{@link com.playermessaging.player.common.MetricsEventListener} — maintains aggregate
 *       counters (total published, total delivered) across all players.
 * </ul>
 *
 * <p>Any number of listeners may be attached to a broker; they are called in registration order.
 * Listener implementations must be thread-safe because {@code onEvent} is called from the thread
 * that invokes {@link MessageBroker#publish} or {@link MessageBroker#receive}, which may be
 * different threads in a concurrent scenario.
 */
public interface MessageEventListener {

    /**
     * Called by the broker after a message is published or delivered.
     *
     * <p>Implementations must not throw unchecked exceptions; doing so would disrupt the broker's
     * notification loop and prevent subsequent listeners from being notified.
     *
     * @param event the event; never {@code null}.
     */
    void onEvent(MessageEvent event);
}
