package com.playermessaging.player.common.model;

/**
 * Immutable event fired by a {@link MessageBroker} whenever a message is published or delivered.
 *
 * <h3>Observer Pattern — Event Object</h3>
 *
 * <p>This class is the <em>notification payload</em> passed from the subject ({@link
 * MessageBroker}) to every registered {@link MessageEventListener}. Making it immutable ensures
 * that no listener can mutate the event and affect what subsequent listeners see.
 *
 * <p>Two event kinds are currently defined:
 *
 * <ul>
 *   <li>{@link Kind#PUBLISHED} — a message was accepted by the broker and placed in the recipient's
 *       inbox.
 *   <li>{@link Kind#DELIVERED} — a message was removed from an inbox and handed to a waiting
 *       receiver.
 * </ul>
 */
public final class MessageEvent {

    /** Distinguishes the lifecycle stage at which the event was fired. */
    public enum Kind {
        /** The message was placed in the recipient's inbox by {@code publish()}. */
        PUBLISHED,
        /** The message was removed from the inbox and returned by {@code receive()}. */
        DELIVERED
    }

    private final Kind kind;
    private final Message message;

    /**
     * Creates a new event.
     *
     * @param kind the event type; must not be {@code null}.
     * @param message the message associated with this event; must not be {@code null}.
     */
    public MessageEvent(Kind kind, Message message) {
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        if (message == null) throw new IllegalArgumentException("message must not be null");
        this.kind = kind;
        this.message = message;
    }

    /**
     * @return the lifecycle stage at which this event was fired.
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * @return the message associated with this event; never {@code null}.
     */
    public Message getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "MessageEvent[" + kind + ", " + message + "]";
    }
}
