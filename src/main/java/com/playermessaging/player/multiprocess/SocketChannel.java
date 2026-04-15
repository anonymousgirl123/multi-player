package com.playermessaging.player.multiprocess;

import com.playermessaging.player.common.channel.MessageChannel;
import com.playermessaging.player.common.model.Message;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * TCP-socket-backed {@link MessageChannel} using a <strong>length-prefixed binary protocol</strong>
 * for the multi-process scenario.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Wrap a connected {@link Socket} with binary stream I/O ({@link DataInputStream} / {@link
 *       DataOutputStream}).
 *   <li>Serialize a {@link Message} into a length-prefixed binary frame before writing it to the
 *       socket output stream.
 *   <li>Deserialize a frame back into a {@link Message} when reading from the socket input stream.
 *   <li>Block the caller on {@link #receive()} until a complete frame arrives.
 *   <li>Release the socket and its streams cleanly on {@link #close()}.
 * </ul>
 *
 * <h3>Wire Format</h3>
 *
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │ 4 bytes (int, big-endian) │        N bytes (UTF-8 body)              │
 *   │      frame length N       │  sender|recipient|sentAtEpochMillis|content  │
 *   └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The {@code sentAtEpochMillis} field is the result of {@link java.time.Instant#toEpochMilli()}
 * serialised as a decimal string. The receiver converts it back with {@link
 * java.time.Instant#ofEpochMilli(long)} and passes it to the {@link Message#Message(String, String,
 * String, java.time.Instant)} constructor, preserving the original creation timestamp across
 * process boundaries.
 *
 * <p>Why length-prefixed binary instead of the original newline-delimited text?
 *
 * <ol>
 *   <li><strong>No delimiter conflicts.</strong> The old text protocol used {@code '|'} to separate
 *       fields and {@code '\n'} as a frame terminator. If {@code content} ever contained a newline
 *       (e.g. a multi-line chat message, serialised JSON, or binary data encoded as text), the
 *       receiver would split on the wrong boundary and mis-parse the frame. A length-prefix is
 *       immune: the receiver reads exactly the declared number of bytes regardless of their
 *       content.
 *   <li><strong>No string scanning.</strong> {@link java.io.BufferedReader#readLine} had to scan
 *       byte-by-byte until it found {@code '\n'}. {@link DataInputStream#readInt} reads exactly 4
 *       bytes in one call; {@link DataInputStream#readFully} then fills the body buffer in a single
 *       syscall. Fewer syscalls → lower latency per message.
 *   <li><strong>Explicit framing.</strong> The receiver always knows up front how many bytes to
 *       read. This makes it trivial to pre-allocate the exact buffer size and avoids copying data
 *       through intermediate line buffers.
 *   <li><strong>Binary-safe body.</strong> The body is still UTF-8 text (for human readability in
 *       logs), but the framing is binary, so future evolution to a fully binary body (e.g.
 *       protobuf) is a non-breaking change — only the serialiser/deserialiser changes, not the
 *       framing.
 * </ol>
 *
 * <p>The body keeps the {@code '|'} field separator between sender, recipient, and content (last
 * field wins for ambiguity) so that the wire format remains human-readable when decoded as a
 * string.
 */
final class SocketChannel implements MessageChannel {

    /** Separator between fields inside the body. */
    private static final String FIELD_SEPARATOR = "|";

    /** Charset used to encode/decode the body string. */
    private static final java.nio.charset.Charset CHARSET = StandardCharsets.UTF_8;

    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;

    /**
     * Wraps {@code socket} in binary-stream I/O.
     *
     * @param socket an already-connected TCP socket; must not be {@code null}.
     * @throws IOException if the socket's streams cannot be opened.
     */
    SocketChannel(Socket socket) throws IOException {
        this.socket = socket;
        // DataOutputStream/DataInputStream for readInt()/writeInt() and readFully().
        // BufferedOutputStream wraps the raw stream to batch small writes into
        // a single syscall — critical for low-latency messaging where each message
        // would otherwise cause two separate write() calls (length + body).
        this.out = new DataOutputStream(new java.io.BufferedOutputStream(socket.getOutputStream()));
        this.in = new DataInputStream(socket.getInputStream());
    }

    /**
     * Serializes {@code message} into a length-prefixed binary frame and writes it to the socket
     * output stream.
     *
     * <p>Frame layout: {@code [int: bodyLength][bodyLength bytes: UTF-8 body]} where {@code body =
     * "sender|recipient|sentAtEpochMillis|content"}.
     *
     * <p>Why include {@code sentAt} in the body rather than as a separate header? Keeping all
     * fields in the single body string means the deserialiser needs only one split pass and the
     * wire format remains a single, human-readable UTF-8 string (useful for debugging with {@code
     * tcpdump -A}). The epoch-millis representation is compact, lossless, and easily parseable.
     *
     * <p>Why flush after every write? {@link java.io.BufferedOutputStream} accumulates bytes until
     * the buffer fills. Without an explicit flush the receiver would block indefinitely waiting for
     * data that is still sitting in the sender's buffer. Flushing after each message ensures the
     * frame is transmitted immediately.
     */
    @Override
    public void send(Message message) throws IOException {
        String body =
                message.getSender()
                        + FIELD_SEPARATOR
                        + message.getRecipient()
                        + FIELD_SEPARATOR
                        + message.getSentAt().toEpochMilli()
                        + FIELD_SEPARATOR
                        + message.getContent();
        byte[] bodyBytes = body.getBytes(CHARSET);

        // Write the 4-byte length prefix followed by the body.
        out.writeInt(bodyBytes.length);
        out.write(bodyBytes);
        // Flush so the frame leaves the kernel send buffer immediately.
        out.flush();
    }

    /**
     * Reads a length-prefixed binary frame from the socket and deserializes it into a {@link
     * Message}.
     *
     * <p>Steps:
     *
     * <ol>
     *   <li>Read a 4-byte big-endian integer — the body length.
     *   <li>Allocate a byte array of that size and fill it with {@link DataInputStream#readFully}
     *       (blocks until all bytes arrive).
     *   <li>Decode the body as UTF-8 and split on {@code '|'} into four fields: {@code
     *       sender|recipient|sentAtEpochMillis|content}.
     *   <li>Validate that sender and recipient fields are non-empty.
     *   <li>Parse {@code sentAtEpochMillis} back into an {@link Instant} and reconstruct the {@link
     *       Message} with its original timestamp.
     * </ol>
     *
     * <p>Why {@link DataInputStream#readFully} instead of a loop? A raw {@link
     * java.io.InputStream#read} call may return fewer bytes than requested (short read) due to TCP
     * segmentation. {@code readFully} retries until the buffer is completely filled, removing the
     * need for a manual read loop.
     *
     * @throws IOException if the connection closes or the frame is malformed.
     */
    @Override
    public Message receive() throws IOException {
        // Step 1: Read the 4-byte frame length.
        int bodyLength;
        try {
            bodyLength = in.readInt();
        } catch (EOFException e) {
            throw new IOException("Connection closed by peer (EOF reading frame length).", e);
        }

        // Guard against corrupt or malicious length values.
        // Why cap at 1 MB?  A legitimate message in this demo is at most a few
        // hundred bytes.  A wildly large length indicates a protocol violation
        // (corrupt stream, version mismatch) and we should not allocate gigabytes
        // of heap trying to read it.
        if (bodyLength < 0 || bodyLength > 1_048_576) {
            throw new IOException(
                    "Invalid frame length: "
                            + bodyLength
                            + " (expected 0 – 1 048 576). Protocol violation or corrupt stream.");
        }

        // Step 2: Read exactly bodyLength bytes.
        byte[] bodyBytes = new byte[bodyLength];
        try {
            in.readFully(bodyBytes); // blocks until all bytes arrive — no short-read risk
        } catch (EOFException e) {
            throw new IOException(
                    "Connection closed mid-frame after reading " + bodyLength + "-byte body.", e);
        }

        // Step 3: Decode and split into the four expected fields.
        // Wire format: "sender|recipient|sentAtEpochMillis|content"
        // We split only on the first three separators; content may contain '|'.
        String body = new String(bodyBytes, CHARSET);
        int first = body.indexOf(FIELD_SEPARATOR);
        int second = first >= 0 ? body.indexOf(FIELD_SEPARATOR, first + 1) : -1;
        int third = second >= 0 ? body.indexOf(FIELD_SEPARATOR, second + 1) : -1;

        // Step 4: Validate — malformed body if any of the three separators are missing.
        if (first < 0 || second < 0 || third < 0) {
            throw new IOException(
                    "Malformed frame body (expected 'sender|recipient|sentAtMs|content'): '"
                            + body
                            + "'");
        }
        String sender = body.substring(0, first);
        String recipient = body.substring(first + 1, second);
        String sentAtMillisStr = body.substring(second + 1, third);
        String content = body.substring(third + 1);

        if (sender.isEmpty()) {
            throw new IOException("Malformed frame – sender field is empty: '" + body + "'");
        }
        if (recipient.isEmpty()) {
            throw new IOException("Malformed frame – recipient field is empty: '" + body + "'");
        }

        // Step 5: Reconstruct the original sentAt timestamp.
        // Using the explicit-timestamp constructor preserves the sender's clock
        // across process boundaries — the receiver can measure true delivery latency.
        Instant sentAt;
        try {
            sentAt = Instant.ofEpochMilli(Long.parseLong(sentAtMillisStr));
        } catch (NumberFormatException e) {
            throw new IOException(
                    "Malformed frame – sentAt field is not a valid long millis value: '"
                            + sentAtMillisStr
                            + "'",
                    e);
        }

        return new Message(sender, recipient, content, sentAt);
    }

    /**
     * Closes the underlying socket and its associated streams. Errors during close are silently
     * swallowed to keep shutdown clean.
     */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
