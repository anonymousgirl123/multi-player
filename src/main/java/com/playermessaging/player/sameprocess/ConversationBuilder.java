package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.config.ConfigLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for {@link Conversation}.
 *
 * <h3>Builder Pattern</h3>
 *
 * <p>Constructing a {@link Conversation} via this builder makes each configuration choice explicit
 * and readable at the call site. Compare:
 *
 * <pre>
 * // Without Builder — grows unwieldy as parameters are added
 * new Conversation(List.of("A", "B", "C"), 20, "Hey", 32);
 *
 * // With Builder — intent is clear
 * new ConversationBuilder()
 *     .players("PlayerA", "PlayerB", "PlayerC")
 *     .maxMessages(20)
 *     .openingMessage("Hey")
 *     .queueCapacity(32)
 *     .build();
 * </pre>
 *
 * <p>Why a separate builder class instead of a static factory method on {@link Conversation}? <br>
 * A factory method can only return an instance — it cannot accumulate optional parameters without
 * overloads. The Builder pattern handles any number of optional configuration knobs without
 * combinatorial overload explosion.
 *
 * <p>All parameters have sensible defaults read from {@code configuration.properties}, so the
 * builder is usable with just a {@link #players} call:
 *
 * <pre>
 *   new ConversationBuilder().players("A", "B").build();
 * </pre>
 *
 * <p><strong>Not thread-safe:</strong> builders are single-use objects, used by one thread before
 * {@link #build()} is called.
 */
public final class ConversationBuilder {

    // Defaults read from configuration.properties — same as Conversation's
    // static fields, centralised here so the builder is self-contained.
    private List<String> playerNames = null;
    private int maxMessages = ConfigLoader.getInt("max.messages");
    private String openingMessage = ConfigLoader.getString("opening.message");
    private int queueCapacity = ConfigLoader.getInt("inbox.queue.capacity");

    /**
     * Sets the ordered list of player names (first = initiator, rest = responders). At least two
     * names are required.
     *
     * @param names one or more player names.
     * @return this builder (fluent API).
     */
    public ConversationBuilder players(String... names) {
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException("At least one player name must be provided.");
        }
        this.playerNames = new ArrayList<>(Arrays.asList(names));
        return this;
    }

    /**
     * Sets the ordered list of player names from a {@link List}.
     *
     * @param names list of player names; must not be null or empty.
     * @return this builder (fluent API).
     */
    public ConversationBuilder players(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("Player list must not be null or empty.");
        }
        this.playerNames = new ArrayList<>(names);
        return this;
    }

    /**
     * Overrides the number of messages to exchange before stopping. Defaults to the value of {@code
     * max.messages} in {@code configuration.properties}.
     *
     * @param maxMessages must be &gt; 0.
     * @return this builder (fluent API).
     */
    public ConversationBuilder maxMessages(int maxMessages) {
        if (maxMessages <= 0) throw new IllegalArgumentException("maxMessages must be > 0");
        this.maxMessages = maxMessages;
        return this;
    }

    /**
     * Overrides the first message sent by the initiator. Defaults to the value of {@code
     * opening.message} in {@code configuration.properties}.
     *
     * @param openingMessage must not be null.
     * @return this builder (fluent API).
     */
    public ConversationBuilder openingMessage(String openingMessage) {
        if (openingMessage == null)
            throw new IllegalArgumentException("openingMessage must not be null");
        this.openingMessage = openingMessage;
        return this;
    }

    /**
     * Overrides the per-player inbox queue capacity. Defaults to the value of {@code
     * inbox.queue.capacity} in {@code configuration.properties}.
     *
     * @param queueCapacity must be ≥ 1.
     * @return this builder (fluent API).
     */
    public ConversationBuilder queueCapacity(int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        this.queueCapacity = queueCapacity;
        return this;
    }

    /**
     * Constructs and returns the configured {@link Conversation}.
     *
     * @return a new {@link Conversation}; never {@code null}.
     * @throws IllegalStateException if {@link #players} was never called.
     * @throws IllegalArgumentException if fewer than two player names were provided.
     */
    public Conversation build() {
        if (playerNames == null) {
            throw new IllegalStateException(
                    "ConversationBuilder.players(...) must be called before build().");
        }
        return new Conversation(playerNames, maxMessages, openingMessage, queueCapacity);
    }
}
