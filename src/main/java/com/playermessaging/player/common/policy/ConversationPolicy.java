package com.playermessaging.player.common.policy;

/**
 * Strategy that decides when a player acting as the <em>initiator</em> should stop sending messages
 * and signal the end of the conversation.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Encapsulate the stop condition so that {@link Player} contains <em>no</em> hard-coded
 *       decision about when to halt.
 *   <li>Allow different termination rules to be injected without touching {@code Player} — e.g.
 *       stop after N messages, after a time limit, or after a keyword is received.
 * </ul>
 *
 * <p>Why pull this out of Player? <br>
 * In the original design {@code Player} contained {@code if (sentCount >= maxMessages)} directly in
 * its loop. That couples <em>conversation mechanics</em> (how messages are exchanged) with
 * <em>lifecycle policy</em> (when to stop). Any change to the stop rule — say, "stop after 10
 * <em>received</em> messages instead of 10 <em>sent</em>" — would require modifying {@code Player},
 * which should otherwise remain stable. By extracting the decision into this interface, {@code
 * Player} becomes open for extension (new policies) but closed for modification (its loop is
 * untouched). This is the Open/Closed Principle applied at method level.
 *
 * <p>Only the <strong>initiator</strong> consults the policy; the responder exits passively when it
 * receives a poison-pill from the initiator.
 */
public interface ConversationPolicy {

    /**
     * Returns {@code true} when the initiator should stop sending messages, dispatch a shutdown
     * signal to its peer, and exit.
     *
     * <p>This method is called by the initiator's message loop <em>after</em> a reply has been
     * received and <em>before</em> the next message would be sent, giving the policy access to both
     * counters at the decision point.
     *
     * @param sentCount total messages sent by the initiator so far.
     * @param receivedCount total replies received by the initiator so far.
     * @return {@code true} if the conversation should end now.
     */
    boolean shouldStop(int sentCount, int receivedCount);
}
