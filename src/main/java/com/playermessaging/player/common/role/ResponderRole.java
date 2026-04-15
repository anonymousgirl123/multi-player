package com.playermessaging.player.common.role;

/**
 * {@link PlayerRole} for the player that waits for the first message.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Do nothing at conversation start — the responder waits for the initiator to speak first.
 *   <li>On each received message, unconditionally echo it back with this player's running sent
 *       counter appended.
 *   <li>Never consult a {@link ConversationPolicy} — the responder exits only when it receives the
 *       poison-pill from the initiator, which is handled by {@code Player} before this method is
 *       even called.
 * </ul>
 *
 * <p>Why no policy here? <br>
 * The responder's exit is entirely passive: it keeps replying until the initiator decides to stop
 * and sends a poison-pill. Injecting a policy into the responder would create a second, independent
 * stop condition that could fire at a different time than the initiator's — breaking the symmetric
 * exchange guarantee. Keeping the responder policy-free makes this asymmetry explicit and
 * intentional.
 */
public final class ResponderRole implements PlayerRole {

    /**
     * The responder has nothing to send before the loop — it waits for the initiator's first
     * message.
     */
    @Override
    public RoleAction onConversationStart() {
        // Do nothing: the responder's loop begins with a blocking receive(),
        // waiting for the initiator's opening message.
        return RoleAction.doNothing();
    }

    /**
     * Always replies with the received content plus the next sent counter. The responder never
     * initiates a stop; that decision belongs to the initiator.
     *
     * <p>Note: {@code sentCount + 1} is used for the label because {@code Player} increments its
     * counter <em>after</em> this method returns.
     */
    @Override
    public RoleAction onMessageReceived(String receivedContent, int sentCount, int receivedCount) {
        return RoleAction.reply(receivedContent + " " + (sentCount + 1));
    }
}
