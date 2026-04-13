package com.playermessaging.player.common.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects per-player messaging statistics for a single conversation.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Count how many messages this player <em>sent</em> and <em>received</em>.
 *   <li>Record the one-way delivery latency of every received message so that min, max, and average
 *       latency can be reported at the end of the conversation.
 *   <li>Produce a human-readable summary via {@link #summarise(String)}.
 * </ul>
 *
 * <p><strong>Latency definition:</strong> One-way latency is the elapsed time between the moment
 * the sender constructed the {@link Message} (captured in {@link Message#getSentAt()}) and the
 * moment the receiver called {@link #recordReceive(Message)}. In the same-process scenario this
 * measures queue-wait time plus scheduling overhead. In the multi-process scenario it also includes
 * TCP round-trip time.
 *
 * <p>Thread-safety: this class is <em>not</em> thread-safe. Each {@link Player} holds its own
 * {@code PlayerMetrics} instance and updates it from a single thread (the player's own virtual
 * thread), so no synchronisation is needed.
 *
 * <h3>Why not use a pre-built metrics library (Micrometer, Dropwizard Metrics)?</h3>
 *
 * For an interview exercise, a minimal custom class demonstrates understanding of what metrics mean
 * and how to compute them without hiding the logic behind an opaque library. In production code a
 * library would be preferred for its histogram reservoirs, percentile support, and export adapters
 * (Prometheus, JMX, …).
 */
public final class PlayerMetrics {

    /** Number of messages this player successfully published. */
    private int sentCount = 0;

    /** Number of messages this player received (excluding the poison-pill). */
    private int receivedCount = 0;

    /**
     * Per-message delivery latencies in nanoseconds.
     *
     * <p>Why nanoseconds? Same-process delivery via a {@link java.util.concurrent.BlockingQueue}
     * can complete in microseconds; millisecond granularity would round most latency samples to
     * zero. {@link Duration#toNanos()} gives the finest resolution available from {@link
     * Instant#now()}.
     */
    private final List<Long> latenciesNs = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Increments the sent-message counter.
     *
     * <p>Call this after every successful {@code broker.publish()} or {@code channel.send()} — i.e.
     * after the message has been handed off to the transport layer.
     */
    public void recordSend() {
        sentCount++;
    }

    /**
     * Increments the received-message counter and records the delivery latency derived from {@link
     * Message#getSentAt()}.
     *
     * <p>Latency = {@link Instant#now()} at time of call minus {@link Message#getSentAt()}. Because
     * {@code getSentAt()} is set at construction time (in the sender's thread), the measured
     * interval is the time the message spent in transit — queue wait + scheduling + any network
     * hop.
     *
     * <p>Do <em>not</em> call this for poison-pill messages; their purpose is signalling, not
     * measurement.
     *
     * @param message the received message; must not be {@code null}.
     */
    public void recordReceive(Message message) {
        receivedCount++;
        Duration latency = Duration.between(message.getSentAt(), Instant.now());
        // Guard against negative values caused by clock skew (possible across
        // processes on different machines).  A negative latency is meaningless;
        // clamping to zero keeps the statistics interpretable.
        latenciesNs.add(Math.max(0L, latency.toNanos()));
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    /**
     * Returns a multi-line human-readable summary of the metrics collected.
     *
     * <p>Example output:
     *
     * <pre>
     *   [PlayerA] ── Conversation Metrics ──────────────────
     *   [PlayerA]   Messages sent    :  10
     *   [PlayerA]   Messages received:  10
     *   [PlayerA]   Latency (one-way): min=2 µs  avg=14 µs  max=103 µs
     *   [PlayerA] ──────────────────────────────────────────
     * </pre>
     *
     * @param playerName the name to prefix every line with; must not be {@code null}.
     * @return the formatted summary string; never {@code null}.
     */
    public String summarise(String playerName) {
        String prefix = "[" + playerName + "]";
        String bar = "─".repeat(40);
        StringBuilder sb = new StringBuilder();

        sb.append(prefix).append(" ").append(bar).append(System.lineSeparator());
        sb.append(String.format("%s   Messages sent    : %3d%n", prefix, sentCount));
        sb.append(String.format("%s   Messages received: %3d%n", prefix, receivedCount));

        if (latenciesNs.isEmpty()) {
            sb.append(prefix)
                    .append("   Latency (one-way): n/a (no messages received)")
                    .append(System.lineSeparator());
        } else {
            long minNs = latenciesNs.stream().mapToLong(Long::longValue).min().orElse(0);
            long maxNs = latenciesNs.stream().mapToLong(Long::longValue).max().orElse(0);
            long avgNs = (long) latenciesNs.stream().mapToLong(Long::longValue).average().orElse(0);

            sb.append(
                    String.format(
                            "%s   Latency (one-way): min=%s  avg=%s  max=%s%n",
                            prefix, formatNs(minNs), formatNs(avgNs), formatNs(maxNs)));
        }

        sb.append(prefix).append(" ").append(bar);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Accessors (useful for tests / Task 24 summary printing from Player)
    // -------------------------------------------------------------------------

    /**
     * @return the number of messages this player successfully sent.
     */
    public int getSentCount() {
        return sentCount;
    }

    /**
     * @return the number of messages this player received.
     */
    public int getReceivedCount() {
        return receivedCount;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Formats a nanosecond count in the most readable unit (ns, µs, or ms).
     *
     * <p>Examples: 450 → "450 ns", 12500 → "12 µs", 3000000 → "3 ms".
     */
    private static String formatNs(long nanos) {
        if (nanos < 1_000L) {
            return nanos + " ns";
        } else if (nanos < 1_000_000L) {
            return (nanos / 1_000L) + " µs";
        } else {
            return (nanos / 1_000_000L) + " ms";
        }
    }
}
