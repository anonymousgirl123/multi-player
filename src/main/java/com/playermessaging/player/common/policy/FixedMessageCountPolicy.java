package com.playermessaging.player.common.policy;

/**
 * {@link ConversationPolicy} that ends the conversation once the initiator has both <em>sent</em>
 * and <em>received</em> a fixed number of messages.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Be the single source of truth for the {@code maxMessages} constant, replacing the
 *       hard-coded field that previously lived inside {@code Player}.
 *   <li>Implement the stop condition: halt when {@code sentCount >= maxMessages}
 *       <strong>and</strong> {@code receivedCount >= maxMessages}, guaranteeing a symmetric
 *       exchange before shutdown.
 * </ul>
 *
 * <p>Why a separate class and not a lambda? <br>
 * A named class makes the policy self-documenting and discoverable. Developers can search the
 * codebase for {@code FixedMessageCountPolicy} and immediately understand what stop rule is active.
 * A lambda like {@code (s, r) -> s >= 10 && r >= 10} would leave the magic number 10 floating in
 * {@code Conversation} with no name attached to it. The class also makes it easy to add new
 * policies later (e.g. {@code TimeLimitPolicy}, {@code KeywordPolicy}) without changing any
 * existing code — just create a new implementation and inject it.
 */
public final class FixedMessageCountPolicy implements ConversationPolicy {

    /** The number of messages the initiator must send and receive before stopping. */
    private final int maxMessages;

    /**
     * Creates a policy that stops after {@code maxMessages} sent and received.
     *
     * @param maxMessages must be greater than zero.
     */
    public FixedMessageCountPolicy(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be > 0, got: " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    /**
     * Returns {@code true} once the initiator has sent <em>and</em> received at least {@link
     * #maxMessages} messages, ensuring both sides of every exchange have completed before the
     * conversation is closed.
     */
    @Override
    public boolean shouldStop(int sentCount, int receivedCount) {
        return sentCount >= maxMessages && receivedCount >= maxMessages;
    }
}
