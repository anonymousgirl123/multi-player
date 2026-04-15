package com.playermessaging.player.common.role;

import com.playermessaging.player.common.policy.ConversationPolicy;

/**
 * {@link PlayerRole} for the player that starts the conversation.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Send the very first message of the conversation via {@link #onConversationStart()} before
 *       the message loop begins.
 *   <li>On each subsequent received reply, consult the injected {@link ConversationPolicy} to
 *       decide whether to keep replying or to signal the end of the conversation.
 *   <li>Return {@link PlayerRole.RoleAction#stopConversation()} when the policy says the
 *       conversation is over, triggering the poison-pill mechanism inside {@code Player}.
 * </ul>
 *
 * <p>Why does this class hold the policy and not Player? <br>
 * The stop decision is role-specific: only the initiator decides when to stop; the responder never
 * does. Keeping the policy here means {@code Player} never needs to know which role is active or
 * what the stop condition is. The separation is clean: {@code Player} owns the loop mechanics;
 * {@code InitiatorRole} owns the lifecycle decision.
 */
public final class InitiatorRole implements PlayerRole {

    /** Text of the very first message sent to open the conversation. */
    private final String openingMessage;

    /** Decides when the initiator should stop. */
    private final ConversationPolicy policy;

    /**
     * Creates the initiator role.
     *
     * @param openingMessage the text sent before the loop begins; must not be {@code null}.
     * @param policy the stop-condition strategy; must not be {@code null}.
     */
    public InitiatorRole(String openingMessage, ConversationPolicy policy) {
        if (openingMessage == null)
            throw new IllegalArgumentException("openingMessage must not be null");
        if (policy == null) throw new IllegalArgumentException("policy must not be null");
        this.openingMessage = openingMessage;
        this.policy = policy;
    }

    /**
     * Signals {@code Player} to send the opening message before the loop starts. This is what makes
     * this player the "initiator" — it speaks first.
     */
    @Override
    public RoleAction onConversationStart() {
        return RoleAction.reply(openingMessage);
    }

    /**
     * After each reply is received:
     *
     * <ul>
     *   <li>If the policy says stop → return {@link RoleAction#stopConversation()} so {@code
     *       Player} sends the poison-pill and exits.
     *   <li>Otherwise → build the next reply by appending the next sent counter to the received
     *       content and return {@link RoleAction#reply(String)}.
     * </ul>
     *
     * <p>Note: {@code sentCount + 1} is used for the label because {@code Player} will increment
     * its own counter <em>after</em> this method returns.
     */
    @Override
    public RoleAction onMessageReceived(String receivedContent, int sentCount, int receivedCount) {
        if (policy.shouldStop(sentCount, receivedCount)) {
            return RoleAction.stopConversation();
        }
        return RoleAction.reply(receivedContent + " " + (sentCount + 1));
    }
}
