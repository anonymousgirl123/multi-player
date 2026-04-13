package com.playermessaging.player.common;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.model.Message;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * Unit tests for {@link Message}.
 *
 * <p>Covers: field storage, null-guard validation, timestamp default, explicit timestamp
 * constructor, BROADCAST sentinel, and toString format.
 */
class MessageTest {

    // ── Construction ─────────────────────────────────────────────────────────

    @Test
    void fieldsAreStoredCorrectly() {
        Instant before = Instant.now();
        Message msg = new Message("Alice", "Bob", "hello");
        Instant after = Instant.now();

        assertEquals("Alice", msg.getSender());
        assertEquals("Bob", msg.getRecipient());
        assertEquals("hello", msg.getContent());
        // sentAt should be between before and after (set to Instant.now() in ctor)
        assertFalse(msg.getSentAt().isBefore(before));
        assertFalse(msg.getSentAt().isAfter(after));
    }

    @Test
    void explicitTimestampIsPreserved() {
        Instant fixed = Instant.parse("2024-01-01T00:00:00Z");
        Message msg = new Message("A", "B", "content", fixed);
        assertEquals(fixed, msg.getSentAt());
    }

    @Test
    void emptyContentIsAllowed() {
        // Empty content is the poison-pill convention — must not throw.
        assertDoesNotThrow(() -> new Message("A", "B", ""));
    }

    // ── Null guards ───────────────────────────────────────────────────────────

    @Test
    void nullSenderThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Message(null, "B", "hi"));
    }

    @Test
    void nullRecipientThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Message("A", null, "hi"));
    }

    @Test
    void nullContentThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Message("A", "B", null));
    }

    @Test
    void nullSentAtThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Message("A", "B", "hi", null));
    }

    // ── BROADCAST sentinel ────────────────────────────────────────────────────

    @Test
    void broadcastConstantCanBeUsedAsRecipient() {
        Message msg = new Message("A", Message.BROADCAST, "shout");
        assertEquals(Message.BROADCAST, msg.getRecipient());
    }

    // ── toString ─────────────────────────────────────────────────────────────

    @Test
    void toStringContainsSenderRecipientAndContent() {
        Message msg = new Message("Alice", "Bob", "hello");
        String s = msg.toString();
        assertTrue(s.contains("Alice"));
        assertTrue(s.contains("Bob"));
        assertTrue(s.contains("hello"));
    }
}
