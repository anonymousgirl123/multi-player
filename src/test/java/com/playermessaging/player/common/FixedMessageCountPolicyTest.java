package com.playermessaging.player.common;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.policy.ConversationPolicy;
import com.playermessaging.player.common.policy.FixedMessageCountPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link FixedMessageCountPolicy}.
 *
 * <p>Covers: stop condition logic (both counters must reach the limit), boundary values, and
 * invalid construction.
 */
class FixedMessageCountPolicyTest {

    private final ConversationPolicy policy = new FixedMessageCountPolicy(3);

    // ── shouldStop returns false before limit ─────────────────────────────────

    @Test
    void doesNotStopWhenBothCountersAreZero() {
        assertFalse(policy.shouldStop(0, 0));
    }

    @Test
    void doesNotStopWhenOnlySentReachesLimit() {
        // sent = 3, received = 2 → NOT stopped (received hasn't caught up)
        assertFalse(policy.shouldStop(3, 2));
    }

    @Test
    void doesNotStopWhenOnlyReceivedReachesLimit() {
        // sent = 2, received = 3 → NOT stopped (sent hasn't caught up)
        assertFalse(policy.shouldStop(2, 3));
    }

    // ── shouldStop returns true at and beyond limit ───────────────────────────

    @Test
    void stopsWhenBothCountersExactlyReachLimit() {
        assertTrue(policy.shouldStop(3, 3));
    }

    @Test
    void stopsWhenBothCountersExceedLimit() {
        assertTrue(policy.shouldStop(5, 5));
    }

    @Test
    void stopsWhenBothCountersExceedLimitAsymmetrically() {
        // Both still above max even if unequal
        assertTrue(policy.shouldStop(4, 3));
    }

    // ── Parameterised boundary sweep ─────────────────────────────────────────

    @ParameterizedTest(name = "sent={0}, received={1} → shouldStop={2}")
    @CsvSource({
        "0, 0, false",
        "1, 0, false",
        "0, 1, false",
        "3, 2, false",
        "2, 3, false",
        "3, 3, true",
        "4, 3, true",
        "3, 4, true",
        "10, 10, true"
    })
    void boundaryMatrix(int sent, int received, boolean expected) {
        ConversationPolicy p = new FixedMessageCountPolicy(3);
        assertEquals(expected, p.shouldStop(sent, received));
    }

    // ── Construction guard ────────────────────────────────────────────────────

    @Test
    void zeroMaxMessagesThrows() {
        assertThrows(IllegalArgumentException.class, () -> new FixedMessageCountPolicy(0));
    }

    @Test
    void negativeMaxMessagesThrows() {
        assertThrows(IllegalArgumentException.class, () -> new FixedMessageCountPolicy(-1));
    }

    @Test
    void oneMaxMessageIsAllowed() {
        ConversationPolicy p = new FixedMessageCountPolicy(1);
        assertFalse(p.shouldStop(0, 0));
        assertTrue(p.shouldStop(1, 1));
    }
}
