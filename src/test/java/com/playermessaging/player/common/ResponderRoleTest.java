package com.playermessaging.player.common;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.role.PlayerRole;
import com.playermessaging.player.common.role.ResponderRole;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResponderRole}.
 *
 * <p>Covers: conversation-start does nothing, reply content format (received + counter), and that
 * the responder never signals a stop.
 */
class ResponderRoleTest {

    private final PlayerRole role = new ResponderRole();

    // ── onConversationStart ───────────────────────────────────────────────────

    @Test
    void conversationStartReturnsDoNothing() {
        PlayerRole.RoleAction action = role.onConversationStart();
        assertEquals(PlayerRole.RoleAction.Kind.DO_NOTHING, action.getKind());
    }

    // ── onMessageReceived ─────────────────────────────────────────────────────

    @Test
    void firstReplyAppendsCounterOne() {
        // sentCount=0 → label = 0+1 = 1
        PlayerRole.RoleAction action = role.onMessageReceived("Hello", 0, 0);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
        assertEquals("Hello 1", action.getReplyContent());
    }

    @Test
    void secondReplyAppendsCounterTwo() {
        // sentCount=1 → label = 1+1 = 2
        PlayerRole.RoleAction action = role.onMessageReceived("Hello 1 2", 1, 1);
        assertEquals("Hello 1 2 2", action.getReplyContent());
    }

    @Test
    void responderNeverSignalsStop() {
        // Even at high counters the responder always replies — it has no policy.
        for (int i = 0; i < 20; i++) {
            PlayerRole.RoleAction action = role.onMessageReceived("msg", i, i);
            assertNotEquals(
                    PlayerRole.RoleAction.Kind.SEND_STOP_SIGNAL,
                    action.getKind(),
                    "Responder should never stop (sentCount=" + i + ")");
        }
    }

    @Test
    void replyAlwaysKindIsSendReply() {
        PlayerRole.RoleAction action = role.onMessageReceived("content", 5, 5);
        assertEquals(PlayerRole.RoleAction.Kind.SEND_REPLY, action.getKind());
    }

    // ── Conversation sequence ─────────────────────────────────────────────────

    @Test
    void replySequenceMatchesExpectedPattern() {
        // Simulate the first 5 rounds of the responder's view
        String[] receivedContents = {
            "Hello", "Hello 1 2", "Hello 1 2 2 3", "Hello 1 2 2 3 3 4", "Hello 1 2 2 3 3 4 4 5"
        };
        String[] expectedReplies = {
            "Hello 1",
            "Hello 1 2 2",
            "Hello 1 2 2 3 3",
            "Hello 1 2 2 3 3 4 4",
            "Hello 1 2 2 3 3 4 4 5 5"
        };

        for (int i = 0; i < receivedContents.length; i++) {
            PlayerRole.RoleAction action = role.onMessageReceived(receivedContents[i], i, i + 1);
            assertEquals(
                    expectedReplies[i], action.getReplyContent(), "Mismatch at round " + (i + 1));
        }
    }
}
