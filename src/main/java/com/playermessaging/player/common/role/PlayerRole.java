package com.playermessaging.player.common.role;

/**
 * Strategy that encapsulates the behaviour of a player in a specific role within a conversation.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Define what a player <em>does</em> when it receives a message: does it reply
 *       unconditionally (responder), check a stop condition first (initiator), or simply observe
 *       (future use)?
 *   <li>Decide whether the conversation should be continued or ended after each received message by
 *       returning the appropriate {@link RoleAction}.
 *   <li>Keep all role-specific branching <em>out of</em> {@code Player} so that {@code Player}'s
 *       message loop contains zero {@code if/else} role checks.
 * </ul>
 *
 * <p>Why extract this into an interface instead of keeping a boolean flag? <br>
 * The original design used {@code boolean isInitiator} threaded through the core loop, driving two
 * completely different behaviours via {@code if/else}. That approach has two problems:
 *
 * <ol>
 *   <li>Adding a third role (e.g. an observer that only listens) requires another branch inside
 *       {@code Player}, violating the Open/Closed Principle.
 *   <li>The boolean is meaningless on its own — you have to read the whole loop body to understand
 *       what "initiator" actually implies.
 * </ol>
 *
 * With this interface each role is a self-contained, named object. {@code Player} simply calls
 * {@link #onMessageReceived} and acts on the returned {@link RoleAction} — it never needs to know
 * <em>which</em> role is active.
 *
 * <p>Implementations: {@link InitiatorRole}, {@link ResponderRole}.
 */
public interface PlayerRole {

    /**
     * Called by {@code Player} each time a non-poison-pill message arrives.
     *
     * @param receivedContent the textual content of the incoming message.
     * @param sentCount total messages sent by the player so far.
     * @param receivedCount total messages received by the player so far.
     * @return a {@link RoleAction} telling {@code Player} what to do next.
     */
    RoleAction onMessageReceived(String receivedContent, int sentCount, int receivedCount);

    /**
     * Called once before the message loop starts, giving the role a chance to send the very first
     * message of the conversation.
     *
     * <p>The {@link InitiatorRole} implementation returns an action that sends the opening message;
     * the {@link ResponderRole} returns {@link RoleAction#doNothing()} because the responder waits
     * for the first message to arrive.
     *
     * @return the action to perform before the first {@code receive()} call.
     */
    RoleAction onConversationStart();

    // -------------------------------------------------------------------------
    // Result type returned to Player
    // -------------------------------------------------------------------------

    /**
     * Immutable instruction returned from {@link PlayerRole} to {@code Player}.
     *
     * <p>Why a dedicated result type instead of returning a String directly? <br>
     * The role needs to communicate three distinct outcomes to {@code Player}: "send this reply",
     * "send a stop signal and exit", or "do nothing". A plain {@code String} return cannot
     * distinguish "send empty string" from "do nothing" without a sentinel value — which would be
     * fragile. A dedicated sealed type makes every outcome explicit and compile-time safe.
     */
    final class RoleAction {

        /** What {@code Player} should do with this action. */
        public enum Kind {
            SEND_REPLY,
            SEND_STOP_SIGNAL,
            DO_NOTHING
        }

        private final Kind kind;
        private final String replyContent; // only meaningful when kind == SEND_REPLY

        private RoleAction(Kind kind, String replyContent) {
            this.kind = kind;
            this.replyContent = replyContent;
        }

        /** Factory: player should send {@code content} to its peer. */
        public static RoleAction reply(String content) {
            return new RoleAction(Kind.SEND_REPLY, content);
        }

        /**
         * Factory: player should send the poison-pill stop signal to its peer and then exit its
         * message loop.
         */
        public static RoleAction stopConversation() {
            return new RoleAction(Kind.SEND_STOP_SIGNAL, null);
        }

        /** Factory: player should take no outbound action this turn. */
        public static RoleAction doNothing() {
            return new RoleAction(Kind.DO_NOTHING, null);
        }

        public Kind getKind() {
            return kind;
        }

        /**
         * @return the reply text; only valid when {@link #getKind()} is {@link Kind#SEND_REPLY}.
         */
        public String getReplyContent() {
            return replyContent;
        }
    }
}
