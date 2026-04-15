package com.playermessaging.player.sameprocess;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

/**
 * Integration tests for {@link Conversation}.
 *
 * <p>These tests run an actual in-process conversation end-to-end, verifying that the wiring,
 * message loop, stop condition, and graceful shutdown all work correctly together — no mocking, no
 * stubs.
 *
 * <p>All tests use {@code @Timeout} to catch infinite loops or deadlocks.
 */
class ConversationTest {

    // ── 2-player default scenario ─────────────────────────────────────────────

    @Test
    @Timeout(10)
    void twoPlayerConversationCompletesWithoutError() throws InterruptedException {
        // Uses configuration.properties defaults: 10 messages, "Hello" opener
        new Conversation(List.of("PlayerA", "PlayerB")).start();
        // If we reach here the conversation finished gracefully
    }

    // ── N-player ring topology ────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void threePlayerRingCompletesWithoutError() throws InterruptedException {
        new Conversation(List.of("PlayerA", "PlayerB", "PlayerC")).start();
    }

    @Test
    @Timeout(10)
    void fourPlayerRingCompletesWithoutError() throws InterruptedException {
        new Conversation(List.of("A", "B", "C", "D")).start();
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void nullPlayerListThrows() {
        // Conversation treats null the same as "too few players" — both are
        // caught by the same guard and throw IllegalArgumentException.
        assertThrows(IllegalArgumentException.class, () -> new Conversation(null));
    }

    @Test
    void singlePlayerListThrows() {
        // A ring of one player is meaningless — expect validation failure
        assertThrows(IllegalArgumentException.class, () -> new Conversation(List.of("Lone")));
    }
}
