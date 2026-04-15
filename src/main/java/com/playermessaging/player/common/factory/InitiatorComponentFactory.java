package com.playermessaging.player.common.factory;

import com.playermessaging.player.common.policy.ConversationPolicy;
import com.playermessaging.player.common.policy.FixedMessageCountPolicy;
import com.playermessaging.player.common.role.InitiatorRole;
import com.playermessaging.player.common.role.PlayerRole;

/**
 * {@link PlayerComponentFactory} that produces the initiator's role and policy.
 *
 * <h3>Abstract Factory — Concrete Factory (Initiator)</h3>
 *
 * <p>Creates an {@link InitiatorRole} (sends the opening message, drives the conversation) paired
 * with a {@link FixedMessageCountPolicy} (stops after {@code maxMessages} exchanges).
 *
 * <p>Changing the stop rule — say, switching to a time-limit policy — only requires creating a new
 * concrete factory; every caller of {@link PlayerComponentFactory} is unaffected.
 */
public final class InitiatorComponentFactory implements PlayerComponentFactory {

    private final String openingMessage;
    private final int maxMessages;

    /**
     * @param openingMessage the first message sent at conversation start; must not be {@code null}.
     * @param maxMessages maximum number of messages to exchange before stopping; must be &gt; 0.
     */
    public InitiatorComponentFactory(String openingMessage, int maxMessages) {
        if (openingMessage == null)
            throw new IllegalArgumentException("openingMessage must not be null");
        if (maxMessages <= 0) throw new IllegalArgumentException("maxMessages must be > 0");
        this.openingMessage = openingMessage;
        this.maxMessages = maxMessages;
    }

    /**
     * @return a new {@link InitiatorRole} wired with the configured opening message and the policy
     *     produced by {@link #createPolicy()}.
     */
    @Override
    public PlayerRole createRole() {
        return new InitiatorRole(openingMessage, createPolicy());
    }

    /**
     * @return a new {@link FixedMessageCountPolicy} for {@code maxMessages}.
     */
    @Override
    public ConversationPolicy createPolicy() {
        return new FixedMessageCountPolicy(maxMessages);
    }
}
