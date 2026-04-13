package com.playermessaging.player.common.model;

import java.time.Instant;

/**
 * Immutable value object representing a single message exchanged between players.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Carry the textual content of a message.
 *   <li>Record the name of the <em>sender</em> for traceability.
 *   <li>Record the name of the <em>recipient</em> so that a broker or router can deliver the
 *       message to the correct destination without inspecting the content.
 *   <li>Record the wall-clock {@link Instant} at which the message was <em>created</em> (i.e. just
 *       before it was published). This timestamp is the anchor point for end-to-end latency: the
 *       receiver subtracts {@code sentAt} from its own current clock to obtain the one-way delivery
 *       time.
 *   <li>Provide a human-readable string representation for logging.
 * </ul>
 *
 * <p>Being immutable, a {@code Message} is inherently thread-safe and can be shared across threads
 * (or serialised across processes) without defensive copying.
 *
 * <p>The {@link #BROADCAST} constant can be used as the recipient value when a message is intended
 * for all registered players (e.g. a poison-pill shutdown signal).
 *
 * <h3>Why {@code Instant} and not {@code System.currentTimeMillis()}?</h3>
 *
 * {@link Instant} is the standard Java-time representation of a point on the UTC timeline. It
 * survives serialisation cleanly (ISO-8601 string or epoch-millis long), integrates with all {@code
 * java.time} arithmetic, and makes intent explicit. A raw {@code long} millisecond value is
 * ambiguous without comments; {@code Instant} is self-documenting.
 */
public final class Message {

    /**
     * Sentinel recipient value meaning "deliver to every registered player". A {@link
     * MessageBroker} implementation should fan this message out to all known subscribers.
     */
    public static final String BROADCAST = "*";

    private final String sender;

    // Why a recipient field?
    // In the original 2-player design the channel wiring implicitly encoded
    // the destination: if you wrote to channel A→B the message arrived at B.
    // Once we introduced MessageBroker — which routes by name so N players
    // can coexist without cross-wiring N² channels — the broker must know
    // *who* the message is for without parsing the content.  The recipient
    // field carries that address explicitly, keeping routing logic out of
    // the payload.
    private final String recipient;
    private final String content;

    /**
     * Wall-clock timestamp captured at construction time (just before publish).
     *
     * <p>Why set here and not in the broker/channel? The producer's clock is the canonical
     * reference for "when was this message created". Capturing it inside the constructor means the
     * timestamp is set <em>by the thread that creates the message</em>, closest to the moment of
     * intent. A broker-side timestamp would include queue-wait time and would not reflect the true
     * send instant.
     */
    private final Instant sentAt;

    /**
     * Creates a new addressed message with {@code sentAt} set to {@link Instant#now()}.
     *
     * @param sender the name of the player who created this message; must not be {@code null}.
     * @param recipient the name of the intended receiving player, or {@link #BROADCAST}; must not
     *     be {@code null}.
     * @param content the textual payload; must not be {@code null}.
     */
    public Message(String sender, String recipient, String content) {
        this(sender, recipient, content, Instant.now());
    }

    /**
     * Creates a new addressed message with an explicit {@code sentAt} timestamp.
     *
     * <p>This overload exists for the {@link com.playermessaging.player.multiprocess.SocketChannel}
     * deserialiser, which reconstructs {@code Message} objects from the wire and must preserve the
     * original timestamp rather than capturing a new one.
     *
     * @param sender the name of the player who created this message; must not be {@code null}.
     * @param recipient the name of the intended receiving player, or {@link #BROADCAST}; must not
     *     be {@code null}.
     * @param content the textual payload; must not be {@code null}.
     * @param sentAt the send timestamp; must not be {@code null}.
     */
    public Message(String sender, String recipient, String content, Instant sentAt) {
        if (sender == null) throw new IllegalArgumentException("sender must not be null");
        if (recipient == null) throw new IllegalArgumentException("recipient must not be null");
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (sentAt == null) throw new IllegalArgumentException("sentAt must not be null");
        this.sender = sender;
        this.recipient = recipient;
        this.content = content;
        this.sentAt = sentAt;
    }

    /**
     * @return the name of the player who created this message.
     */
    public String getSender() {
        return sender;
    }

    /**
     * @return the name of the intended receiving player, or {@link #BROADCAST} if the message is
     *     addressed to all players.
     */
    public String getRecipient() {
        return recipient;
    }

    /**
     * @return the textual payload of this message.
     */
    public String getContent() {
        return content;
    }

    /**
     * Returns the wall-clock instant at which this message was created.
     *
     * <p>The receiver can compare this with {@link Instant#now()} to compute the one-way delivery
     * latency:
     *
     * <pre>
     *     Duration latency = Duration.between(message.getSentAt(), Instant.now());
     * </pre>
     *
     * @return the creation timestamp; never {@code null}.
     */
    public Instant getSentAt() {
        return sentAt;
    }

    @Override
    public String toString() {
        return "[" + sender + " → " + recipient + " @ " + sentAt + "] " + content;
    }
}
