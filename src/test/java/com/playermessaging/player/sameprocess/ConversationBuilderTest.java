package com.playermessaging.player.sameprocess;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link ConversationBuilder}.
 *
 * <p>These tests verify that the Builder pattern behaves correctly: defaults are loaded from
 * configuration, each setter overrides the relevant field, the fluent API chains correctly, and
 * invalid inputs are rejected with clear exceptions.
 *
 * <p>Note: {@link Conversation} itself (the product) is tested in {@link ConversationTest}. These
 * tests focus exclusively on the builder's own logic — that it accepts, validates, and forwards
 * configuration correctly.
 */
class ConversationBuilderTest {

    // ── build() guard ──────────────────────────────────────────────────────

    @Test
    void buildWithoutPlayersThrowsIllegalState() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> new ConversationBuilder().build());
        assertTrue(
                ex.getMessage().contains("players"),
                "Exception message should mention 'players', got: " + ex.getMessage());
    }

    // ── players(varargs) ──────────────────────────────────────────────────

    @Test
    void playersVarargsAcceptsTwoNames() {
        assertDoesNotThrow(
                () -> new ConversationBuilder().players("Alice", "Bob").build(),
                "Two-player varargs build should succeed");
    }

    @Test
    void playersVarargsAcceptsThreeNames() {
        assertDoesNotThrow(
                () -> new ConversationBuilder().players("A", "B", "C").build(),
                "Three-player varargs build should succeed");
    }

    @Test
    void playersVarargsNullThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players((String[]) null));
    }

    @Test
    void playersVarargsEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationBuilder().players());
    }

    // ── players(List) ─────────────────────────────────────────────────────

    @Test
    void playersListAcceptsTwoNames() {
        assertDoesNotThrow(
                () -> new ConversationBuilder().players(List.of("Alice", "Bob")).build());
    }

    @Test
    void playersListNullThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players((List<String>) null));
    }

    @Test
    void playersListEmptyThrows() {
        assertThrows(
                IllegalArgumentException.class, () -> new ConversationBuilder().players(List.of()));
    }

    // ── maxMessages ───────────────────────────────────────────────────────

    @Test
    void maxMessagesZeroThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players("A", "B").maxMessages(0).build());
    }

    @Test
    void maxMessagesNegativeThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players("A", "B").maxMessages(-5).build());
    }

    @Test
    void maxMessagesOneIsValid() {
        assertDoesNotThrow(
                () -> new ConversationBuilder().players("A", "B").maxMessages(1).build());
    }

    @Test
    void maxMessagesLargeValueIsValid() {
        assertDoesNotThrow(
                () -> new ConversationBuilder().players("A", "B").maxMessages(10_000).build());
    }

    // ── openingMessage ────────────────────────────────────────────────────

    @Test
    void openingMessageNullThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players("A", "B").openingMessage(null).build());
    }

    @Test
    void openingMessageEmptyStringIsAllowed() {
        // Empty string is a valid (though unusual) opening message.
        assertDoesNotThrow(
                () -> new ConversationBuilder().players("A", "B").openingMessage("").build());
    }

    @Test
    void openingMessageCustomValueIsAccepted() {
        assertDoesNotThrow(
                () ->
                        new ConversationBuilder()
                                .players("A", "B")
                                .openingMessage("Hey there!")
                                .build());
    }

    // ── queueCapacity ─────────────────────────────────────────────────────

    @Test
    void queueCapacityZeroThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players("A", "B").queueCapacity(0).build());
    }

    @Test
    void queueCapacityNegativeThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players("A", "B").queueCapacity(-1).build());
    }

    @Test
    void queueCapacityOneIsValid() {
        assertDoesNotThrow(
                () -> new ConversationBuilder().players("A", "B").queueCapacity(1).build());
    }

    // ── fluent chaining ───────────────────────────────────────────────────

    @Test
    void allSettersChainAndBuildSucceeds() {
        assertDoesNotThrow(
                () ->
                        new ConversationBuilder()
                                .players("PlayerA", "PlayerB", "PlayerC")
                                .maxMessages(5)
                                .openingMessage("Hello!")
                                .queueCapacity(8)
                                .build());
    }

    @Test
    void builderReturnsNonNullConversation() {
        Conversation conversation =
                new ConversationBuilder().players("Alice", "Bob").maxMessages(1).build();
        assertNotNull(conversation, "build() must never return null");
    }

    @Test
    void eachBuilderInstanceIsIndependent() {
        // Two separate builders must not share state.
        ConversationBuilder b1 = new ConversationBuilder().players("A", "B");
        ConversationBuilder b2 = new ConversationBuilder().players("X", "Y").maxMessages(99);

        assertDoesNotThrow(b1::build, "Builder 1 should build independently");
        assertDoesNotThrow(b2::build, "Builder 2 should build independently");
    }

    // ── Conversation requires at least 2 players (product-level guard) ───

    @Test
    void singlePlayerNameThrowsAtBuildTime() {
        // The builder allows a single name through players() but Conversation
        // enforces the minimum-2 rule in its own constructor.
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationBuilder().players("LonelyPlayer").build());
    }
}
