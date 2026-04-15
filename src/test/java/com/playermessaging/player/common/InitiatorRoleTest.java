package com.playermessaging.player.common;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.policy.ConversationPolicy;
import com.playermessaging.player.common.policy.FixedMessageCountPolicy;
import com.playermessaging.player.common.role.InitiatorRole;
import com.playermessaging.player.common.role.PlayerRole;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InitiatorRole}.
 *
 * <p>Covers: opening-message action, reply content format (received + counter), stop-signal
 * delegation to the policy, and null-guard construction.
 */
class InitiatorRoleTest {

    // Shared policy: stop after 3 sent AND 3 received.
    private final ConversationPolicy policy = new FixedMessageCountPolicy(3);
    private final PlayerRole role = new InitiatorRole("Hello", policy);

    // ── onConversationStart ───────────────────────────────────────────────────

    @Test
    void conversationStartReturnsSendReplyAction() {
        PlayerRole.RoleAction action = role.onConversationStart();
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
    }

    @Test
    void conversationStartSendsOpeningMessage() {
        PlayerRole.RoleAction action = role.onConversationStart();
        assertEquals("Hello", action.getReplyContent());
    }

    // ── onMessageReceived — reply path ────────────────────────────────────────

    @Test
    void replyContentConcatenatesReceivedMessageWithNextCounter() {
        // sentCount=0, receivedCount=0 → policy allows, reply = "Hi 1"
        PlayerRole.RoleAction action = role.onMessageReceived("Hi", 0, 0);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
        assertEquals("Hi 1", action.getReplyContent());
    }

    @Test
    void replyAppendsSentCountPlusOne() {
        // sentCount=2 → label = 3
        PlayerRole.RoleAction action = role.onMessageReceived("Hi 1 2", 2, 2);
        // policy: 2 < 3 so no stop; reply = "Hi 1 2 3"
        assertEquals("Hi 1 2 3", action.getReplyContent());
    }

    @Test
    void replyKindIsSendReply() {
        PlayerRole.RoleAction action = role.onMessageReceived("msg", 0, 0);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
    }

    // ── onMessageReceived — stop path ─────────────────────────────────────────

    @Test
    void returnsStopSignalWhenPolicySaysSo() {
        // sentCount=3 AND receivedCount=3 → policy fires
        PlayerRole.RoleAction action = role.onMessageReceived("anything", 3, 3);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_STOP_SIGNAL, action.getKind());
    }

    @Test
    void doesNotStopWhenOnlySentReachesLimit() {
        // sentCount=3 but receivedCount=2 → policy does NOT fire
        PlayerRole.RoleAction action = role.onMessageReceived("msg", 3, 2);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
    }

    @Test
    void doesNotStopWhenOnlyReceivedReachesLimit() {
        PlayerRole.RoleAction action = role.onMessageReceived("msg", 2, 3);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
    }

    // ── Conversation sequence: verify exact content at each step ─────────────

    @Test
    void replySequenceMatchesExpectedPattern() {
        // Simulate the initiator receiving replies 0..2 (before stop at 3)
        String[] received = {"Hi", "Hi 1", "Hi 1 2"};
        String[] expected = {"Hi 1", "Hi 1 2", "Hi 1 2 3"};

        for (int i = 0; i < 3; i++) {
            PlayerRole.RoleAction action = role.onMessageReceived(received[i], i, i);
            assertEquals(
                    PlayerRole.RoleAction.Kind.SEND_REPLY,
                    action.getKind(),
                    "Step " + i + " should reply");
            assertEquals(
                    expected[i], action.getReplyContent(), "Reply content mismatch at step " + i);
        }
    }

    // ── Construction guards ───────────────────────────────────────────────────

    @Test
    void nullOpeningMessageThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InitiatorRole(null, policy));
    }

    @Test
    void nullPolicyThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InitiatorRole("Hello", null));
    }
}
