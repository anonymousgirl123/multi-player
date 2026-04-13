package com.playermessaging.player.common.broker;

import com.playermessaging.player.common.model.MessageEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link MessageEventListener} that maintains aggregate message counters across all players in a
 * conversation.
 *
 * <h3>Observer Pattern — Concrete Observer</h3>
 *
 * <p>While {@link PlayerMetrics} tracks per-player statistics, this listener provides a broker-wide
 * view: total messages published and total messages delivered regardless of which player sent or
 * received them.
 *
 * <p>Atomic counters are used because {@link #onEvent} is called from multiple player threads
 * concurrently.
 *
 * <p>Example usage:
 *
 * <pre>
 *   MetricsEventListener brokerMetrics = new MetricsEventListener();
 *   broker.addListener(brokerMetrics);
 *   // … run conversation …
 *   System.out.println(brokerMetrics.summarise());
 * </pre>
 */
public final class MetricsEventListener implements MessageEventListener {

    /** Total number of {@link MessageEvent.Kind#PUBLISHED} events received. */
    private final AtomicLong totalPublished = new AtomicLong();

    /** Total number of {@link MessageEvent.Kind#DELIVERED} events received. */
    private final AtomicLong totalDelivered = new AtomicLong();

    @Override
    public void onEvent(MessageEvent event) {
        switch (event.getKind()) {
            case PUBLISHED:
                totalPublished.incrementAndGet();
                break;
            case DELIVERED:
                totalDelivered.incrementAndGet();
                break;
        }
    }

    /**
     * @return total messages published to the broker across all players.
     */
    public long getTotalPublished() {
        return totalPublished.get();
    }

    /**
     * @return total messages delivered from the broker across all players.
     */
    public long getTotalDelivered() {
        return totalDelivered.get();
    }

    /**
     * Returns a one-line human-readable summary.
     *
     * <p>Example: {@code [BrokerMetrics] published=10 delivered=10}
     */
    public String summarise() {
        return String.format(
                "[BrokerMetrics] published=%d  delivered=%d",
                totalPublished.get(), totalDelivered.get());
    }
}
