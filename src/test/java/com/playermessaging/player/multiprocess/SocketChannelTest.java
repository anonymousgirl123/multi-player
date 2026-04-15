package com.playermessaging.player.multiprocess;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.model.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration tests for {@link SocketChannel}.
 *
 * <p>Each test spins up a real {@link ServerSocket}/{@link Socket} pair on the loopback interface
 * to exercise the actual binary framing, field serialisation, and EOF handling — no mocks, no
 * stubs.
 *
 * <p>All tests carry {@code @Timeout} to prevent infinite blocking if the protocol has a
 * regression.
 */
class SocketChannelTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface ChannelTask {
        void run(SocketChannel channel) throws Exception;
    }

    /**
     * Creates a loopback server/client socket pair, wraps each side in a {@link SocketChannel},
     * runs the supplied tasks on virtual threads, then waits for both to finish. Any exception
     * thrown inside a task is re-raised as an {@link AssertionError} so JUnit sees the test as
     * failed.
     */
    private static void withConnectedPair(ChannelTask serverTask, ChannelTask clientTask)
            throws Exception {

        AtomicReference<Throwable> serverError = new AtomicReference<>();
        AtomicReference<Throwable> clientError = new AtomicReference<>();
        CountDownLatch serverBound = new CountDownLatch(1);
        int[] portHolder = new int[1];

        Thread serverThread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (ServerSocket ss = new ServerSocket(0)) {
                                        portHolder[0] = ss.getLocalPort();
                                        serverBound.countDown();
                                        try (Socket sock = ss.accept()) {
                                            serverTask.run(new SocketChannel(sock));
                                        }
                                    } catch (Throwable t) {
                                        serverError.set(t);
                                        serverBound.countDown(); // unblock client if bind failed
                                    }
                                });

        Thread clientThread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        assertTrue(
                                                serverBound.await(3, TimeUnit.SECONDS),
                                                "Server did not bind in time");
                                        // Brief pause so accept() is listening before we connect.
                                        Thread.sleep(20);
                                        try (Socket sock = new Socket("localhost", portHolder[0])) {
                                            clientTask.run(new SocketChannel(sock));
                                        }
                                    } catch (Throwable t) {
                                        clientError.set(t);
                                    }
                                });

        serverThread.join(8_000);
        clientThread.join(8_000);

        if (serverError.get() != null) throw new AssertionError("Server error", serverError.get());
        if (clientError.get() != null) throw new AssertionError("Client error", clientError.get());
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void singleMessageRoundTripPreservesAllFields() throws Exception {
        Instant fixedTime = Instant.ofEpochMilli(1_700_000_000_000L);
        Message sent = new Message("Alice", "Bob", "hello world", fixedTime);
        AtomicReference<Message> received = new AtomicReference<>();

        withConnectedPair(server -> received.set(server.receive()), client -> client.send(sent));

        Message got = received.get();
        assertNotNull(got);
        assertEquals("Alice", got.getSender());
        assertEquals("Bob", got.getRecipient());
        assertEquals("hello world", got.getContent());
        assertEquals(fixedTime, got.getSentAt());
    }

    @Test
    @Timeout(10)
    void multipleMessagesDeliveredInOrder() throws Exception {
        int count = 10;
        String[] receivedContents = new String[count];

        withConnectedPair(
                server -> {
                    for (int i = 0; i < count; i++) {
                        receivedContents[i] = server.receive().getContent();
                    }
                },
                client -> {
                    for (int i = 0; i < count; i++) {
                        client.send(new Message("A", "B", "msg-" + i));
                    }
                });

        for (int i = 0; i < count; i++) {
            assertEquals("msg-" + i, receivedContents[i], "Wrong content at index " + i);
        }
    }

    @Test
    @Timeout(10)
    void poisonPillEmptyContentSurvivesRoundTrip() throws Exception {
        AtomicReference<Message> received = new AtomicReference<>();

        withConnectedPair(
                server -> received.set(server.receive()),
                client -> client.send(new Message("A", "B", "")));

        assertNotNull(received.get());
        assertTrue(
                received.get().getContent().isEmpty(),
                "Poison-pill content must be empty after round-trip");
    }

    @Test
    @Timeout(10)
    void contentWithPipeCharacterRoundTripsCorrectly() throws Exception {
        // '|' is the field separator inside the binary body — content may also
        // contain it; the length-prefix framing must handle this transparently.
        AtomicReference<Message> received = new AtomicReference<>();
        String withPipes = "hello|world|extra|pipes";

        withConnectedPair(
                server -> received.set(server.receive()),
                client -> client.send(new Message("A", "B", withPipes)));

        assertEquals(
                withPipes,
                received.get().getContent(),
                "Content containing '|' must survive round-trip intact");
    }

    @Test
    @Timeout(10)
    void unicodeContentRoundTripsCorrectly() throws Exception {
        AtomicReference<Message> received = new AtomicReference<>();
        String unicode = "こんにちは 🌍 Ünïcödé";

        withConnectedPair(
                server -> received.set(server.receive()),
                client -> client.send(new Message("A", "B", unicode)));

        assertEquals(
                unicode,
                received.get().getContent(),
                "Unicode content must survive UTF-8 encode/decode");
    }

    @Test
    @Timeout(10)
    void longMessageRoundTripsCorrectly() throws Exception {
        AtomicReference<Message> received = new AtomicReference<>();
        String big = "x".repeat(100_000); // 100 KB — well within the 1 MB guard

        withConnectedPair(
                server -> received.set(server.receive()),
                client -> client.send(new Message("A", "B", big)));

        assertEquals(big, received.get().getContent());
    }

    // ── EOF / connection close ────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void receiveThrowsIOExceptionWhenPeerClosesConnection() throws Exception {
        AtomicReference<Boolean> caughtIOException = new AtomicReference<>(false);

        withConnectedPair(
                server -> {
                    try {
                        server.receive(); // peer closes without sending — triggers EOF
                        fail("Expected an IOException but receive() returned normally");
                    } catch (IOException expected) {
                        caughtIOException.set(true);
                    }
                },
                client -> client.close() // close immediately — no data sent
                );

        assertTrue(caughtIOException.get(), "Server should have caught IOException on EOF");
    }

    // ── Bidirectional exchange ────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void bidirectionalExchangeWorks() throws Exception {
        AtomicReference<String> serverReceived = new AtomicReference<>();
        AtomicReference<String> clientReceived = new AtomicReference<>();

        withConnectedPair(
                server -> {
                    serverReceived.set(server.receive().getContent());
                    server.send(new Message("Server", "Client", "pong"));
                },
                client -> {
                    client.send(new Message("Client", "Server", "ping"));
                    clientReceived.set(client.receive().getContent());
                });

        assertEquals("ping", serverReceived.get(), "Server should receive 'ping'");
        assertEquals("pong", clientReceived.get(), "Client should receive 'pong'");
    }

    // ── Timestamp preservation across process boundary ────────────────────────

    @Test
    @Timeout(10)
    void sentAtTimestampPreservedWithMillisecondPrecision() throws Exception {
        // The wire format serialises sentAt as epoch-millis (not nanos),
        // so only millisecond precision is expected after round-trip.
        Instant original = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        AtomicReference<Message> received = new AtomicReference<>();

        withConnectedPair(
                server -> received.set(server.receive()),
                client -> client.send(new Message("A", "B", "ts-test", original)));

        assertEquals(
                original,
                received.get().getSentAt(),
                "sentAt should survive round-trip with millisecond precision");
    }
}
