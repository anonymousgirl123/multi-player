package com.playermessaging.player.common;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.model.Message;
import com.playermessaging.player.common.model.PlayerMetrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * Unit tests for {@link PlayerMetrics}.
 *
 * <p>Covers: sent/received counters, latency recording, summarise output format, and edge case of
 * zero messages received.
 */
class PlayerMetricsTest {

    private PlayerMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new PlayerMetrics();
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialSentCountIsZero() {
        assertEquals(0, metrics.getSentCount());
    }

    @Test
    void initialReceivedCountIsZero() {
        assertEquals(0, metrics.getReceivedCount());
    }

    // ── recordSend ────────────────────────────────────────────────────────────

    @Test
    void recordSendIncrementsSentCount() {
        metrics.recordSend();
        assertEquals(1, metrics.getSentCount());
    }

    @Test
    void recordSendMultipleTimesAccumulates() {
        metrics.recordSend();
        metrics.recordSend();
        metrics.recordSend();
        assertEquals(3, metrics.getSentCount());
    }

    @Test
    void recordSendDoesNotAffectReceivedCount() {
        metrics.recordSend();
        assertEquals(0, metrics.getReceivedCount());
    }

    // ── recordReceive ─────────────────────────────────────────────────────────

    @Test
    void recordReceiveIncrementsReceivedCount() {
        metrics.recordReceive(msgSentNow());
        assertEquals(1, metrics.getReceivedCount());
    }

    @Test
    void recordReceiveMultipleTimesAccumulates() {
        metrics.recordReceive(msgSentNow());
        metrics.recordReceive(msgSentNow());
        assertEquals(2, metrics.getReceivedCount());
    }

    @Test
    void recordReceiveDoesNotAffectSentCount() {
        metrics.recordReceive(msgSentNow());
        assertEquals(0, metrics.getSentCount());
    }

    @Test
    void latencyIsNonNegativeForMessageSentInPast() {
        // A message sent 10 ms in the past should have latency ≥ 0
        Instant past = Instant.now().minusMillis(10);
        metrics.recordReceive(new Message("A", "B", "hi", past));
        // We can only verify it doesn't blow up and summarise runs
        String summary = metrics.summarise("A");
        assertFalse(summary.contains("n/a")); // latency was recorded
    }

    @Test
    void latencyIsClampedToZeroForFutureTimestamp() {
        // A message with a timestamp in the future simulates clock skew.
        // The latency must be clamped to 0 ns, not negative.
        Instant future = Instant.now().plusSeconds(60);
        metrics.recordReceive(new Message("A", "B", "hi", future));
        // Should not throw and summary should show "0 ns"
        String summary = metrics.summarise("A");
        assertTrue(summary.contains("0 ns"));
    }

    // ── summarise ────────────────────────────────────────────────────────────

    @Test
    void summariseContainsPlayerName() {
        String summary = metrics.summarise("PlayerA");
        assertTrue(summary.contains("PlayerA"));
    }

    @Test
    void summariseShowsNaWhenNoMessagesReceived() {
        metrics.recordSend();
        String summary = metrics.summarise("PlayerA");
        assertTrue(summary.contains("n/a"));
    }

    @Test
    void summariseShowsSentAndReceivedCounts() {
        metrics.recordSend();
        metrics.recordSend();
        metrics.recordReceive(msgSentNow());
        String summary = metrics.summarise("PlayerA");
        // Counts should appear somewhere in the summary
        assertTrue(summary.contains("2")); // sent
        assertTrue(summary.contains("1")); // received
    }

    @Test
    void summariseContainsLatencyAfterReceive() {
        metrics.recordReceive(new Message("A", "B", "hi", Instant.now().minusMillis(5)));
        String summary = metrics.summarise("PlayerA");
        // Should include min/avg/max latency keywords
        assertTrue(summary.contains("min="));
        assertTrue(summary.contains("avg="));
        assertTrue(summary.contains("max="));
    }

    @Test
    void summariseIsNeverNull() {
        assertNotNull(metrics.summarise("X"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates a test message with sentAt = now (zero or near-zero latency). */
    private static Message msgSentNow() {
        return new Message("Sender", "Receiver", "test-content");
    }
}
