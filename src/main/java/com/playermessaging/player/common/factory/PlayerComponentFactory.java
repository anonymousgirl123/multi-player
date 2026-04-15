package com.playermessaging.player.common.factory;

import com.playermessaging.player.common.policy.ConversationPolicy;
import com.playermessaging.player.common.role.PlayerRole;

/**
 * Abstract Factory for creating the role and policy objects that define a player's behaviour in a
 * conversation.
 *
 * <h3>Abstract Factory Pattern</h3>
 *
 * <p>This interface groups two <em>related</em> object creations — a {@link PlayerRole} and a
 * {@link ConversationPolicy} — behind a single factory abstraction. The two products are always
 * used together: the role consults the policy when deciding whether to stop. The factory ensures
 * both objects are created in a consistent, compatible way.
 *
 * <p>Why Abstract Factory instead of separate factories or direct instantiation?
 *
 * <ul>
 *   <li><strong>Cohesion:</strong> role + policy are a product family — they belong together.
 *       Grouping their creation in one factory makes that relationship explicit.
 *   <li><strong>Swappability:</strong> swap the factory to change the whole player's behaviour at
 *       the composition root without touching {@link
 *       com.playermessaging.player.sameprocess.Conversation} or {@link
 *       com.playermessaging.player.multiprocess.MultiProcessMain}.
 *   <li><strong>Testability:</strong> inject a stub factory in tests to exercise custom roles or
 *       policies without modifying production code.
 * </ul>
 *
 * <p>Concrete factories:
 *
 * <ul>
 *   <li>{@link InitiatorComponentFactory} — creates {@link InitiatorRole} + {@link
 *       FixedMessageCountPolicy}.
 *   <li>{@link ResponderComponentFactory} — creates {@link ResponderRole} + a no-op policy (the
 *       responder never stops on its own).
 * </ul>
 */
public interface PlayerComponentFactory {

    /**
     * Creates the {@link PlayerRole} for this player.
     *
     * @return a new role instance; never {@code null}.
     */
    PlayerRole createRole();

    /**
     * Creates the {@link ConversationPolicy} for this player.
     *
     * <p>For responder players this typically returns a policy that never signals stop, because the
     * responder exits passively on poison-pill.
     *
     * @return a new policy instance; never {@code null}.
     */
    ConversationPolicy createPolicy();
}
