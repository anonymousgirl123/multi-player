package com.playermessaging.player.common.factory;

import com.playermessaging.player.common.policy.ConversationPolicy;
import com.playermessaging.player.common.role.PlayerRole;
import com.playermessaging.player.common.role.ResponderRole;

/**
 * {@link PlayerComponentFactory} that produces the responder's role and policy.
 *
 * <h3>Abstract Factory — Concrete Factory (Responder)</h3>
 *
 * <p>Creates a {@link ResponderRole} (echoes messages, never initiates stop) paired with a
 * never-stop policy. The responder does not need a meaningful policy because it exits passively on
 * poison-pill, but the factory contract requires one to be returned.
 *
 * <p>The never-stop policy is expressed as an anonymous lambda for brevity: {@code (sent, received)
 * -> false}.
 */
public final class ResponderComponentFactory implements PlayerComponentFactory {

    /**
     * @return a new {@link ResponderRole}; stateless so a new instance every time is fine.
     */
    @Override
    public PlayerRole createRole() {
        return new ResponderRole();
    }

    /**
     * @return a policy that always returns {@code false} — the responder never stops on its own
     *     initiative.
     */
    @Override
    public ConversationPolicy createPolicy() {
        // Responder exits passively on poison-pill; this policy is never consulted.
        return (sentCount, receivedCount) -> false;
    }
}
