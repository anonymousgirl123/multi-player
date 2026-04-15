package com.playermessaging.player.sameprocess;

import static org.junit.jupiter.api.Assertions.*;

import com.playermessaging.player.common.model.Message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link InMemoryMessageBroker}.
 *
 * <p>Covers: registration, point-to-point routing, BROADCAST fan-out, backpressure (bounded queue),
 * concurrency, and error paths.
 */
class InMemoryMessageBrokerTest {

    private InMemoryMessageBroker broker;

    @BeforeEach
    void setUp() {
        // Use a generous capacity (64) so tests aren't blocked by backpressure
        // unless they specifically test that feature.
        broker = new InMemoryMessageBroker(64);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @Test
    void canRegisterAndReceiveForKnownPlayer() throws InterruptedException {
        broker.register("Alice");
        broker.register("Bob");

        broker.publish(new Message("Alice", "Bob", "hello"));
        Message received = broker.receive("Bob");

        assertEquals("hello", received.getContent());
        assertEquals("Alice", received.getSender());
        assertEquals("Bob", received.getRecipient());
    }

    @Test
    void duplicateRegistrationThrows() {
        broker.register("Alice");
        assertThrows(IllegalArgumentException.class, () -> broker.register("Alice"));
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test
    void messageGoesToCorrectRecipientOnly() throws InterruptedException {
        broker.register("A");
        broker.register("B");
        broker.register("C");

        broker.publish(new Message("A", "B", "for B only"));

        // B gets the message immediately
        Message received = broker.receive("B");
        assertEquals("for B only", received.getContent());

        // C's inbox is empty — a timed poll must return null
        // We verify by publishing a sentinel to C and checking it's the first thing there
        broker.publish(new Message("A", "C", "sentinel"));
        Message cMsg = broker.receive("C");
        assertEquals("sentinel", cMsg.getContent());
    }

    @Test
    void publishToUnknownRecipientThrows() {
        broker.register("A");
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.publish(new Message("A", "Nobody", "oops")));
    }

    @Test
    void receiveFromUnknownPlayerThrows() {
        assertThrows(IllegalArgumentException.class, () -> broker.receive("Ghost"));
    }

    // ── BROADCAST fan-out ─────────────────────────────────────────────────────

    @Test
    void broadcastDeliveredToAllExceptSender() throws InterruptedException {
        broker.register("A");
        broker.register("B");
        broker.register("C");

        broker.publish(new Message("A", Message.BROADCAST, "shout"));

        // B and C both receive it
        assertEquals("shout", broker.receive("B").getContent());
        assertEquals("shout", broker.receive("C").getContent());
    }

    @Test
    void broadcastNotDeliveredToSender() throws InterruptedException {
        broker.register("A");
        broker.register("B");

        broker.publish(new Message("A", Message.BROADCAST, "shout"));

        // B receives it
        assertEquals("shout", broker.receive("B").getContent());

        // A's inbox must be empty — publish a sentinel then check order
        broker.publish(new Message("B", "A", "only-sentinel"));
        assertEquals("only-sentinel", broker.receive("A").getContent());
    }

    // ── FIFO ordering ─────────────────────────────────────────────────────────

    @Test
    void messagesDeliveredInPublishOrder() throws InterruptedException {
        broker.register("A");
        broker.register("B");

        broker.publish(new Message("A", "B", "first"));
        broker.publish(new Message("A", "B", "second"));
        broker.publish(new Message("A", "B", "third"));

        assertEquals("first", broker.receive("B").getContent());
        assertEquals("second", broker.receive("B").getContent());
        assertEquals("third", broker.receive("B").getContent());
    }

    // ── Concurrency: producer / consumer on separate threads ─────────────────

    @Test
    @Timeout(5) // fail fast if the thread blocks forever
    void concurrentPublishAndReceiveDeliverAllMessages() throws Exception {
        broker.register("Producer");
        broker.register("Consumer");

        int count = 100;
        CountDownLatch done = new CountDownLatch(count);
        AtomicReference<String> error = new AtomicReference<>();

        Thread consumer =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    for (int i = 0; i < count; i++) {
                                        try {
                                            Message msg = broker.receive("Consumer");
                                            if (!("msg-" + i).equals(msg.getContent())) {
                                                error.set(
                                                        "Expected msg-"
                                                                + i
                                                                + " but got "
                                                                + msg.getContent());
                                            }
                                            done.countDown();
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }
                                });

        for (int i = 0; i < count; i++) {
            broker.publish(new Message("Producer", "Consumer", "msg-" + i));
        }

        assertTrue(done.await(4, TimeUnit.SECONDS), "Not all messages received in time");
        assertNull(error.get(), "Message content mismatch: " + error.get());
        consumer.join(1000);
    }

    // ── Backpressure (bounded queue) ──────────────────────────────────────────

    @Test
    @Timeout(3)
    void publishBlocksWhenQueueFull() throws Exception {
        // Use a very small capacity so we can trigger full easily
        InMemoryMessageBroker smallBroker = new InMemoryMessageBroker(2);
        smallBroker.register("A");
        smallBroker.register("B");

        // Fill the queue to capacity
        smallBroker.publish(new Message("A", "B", "1"));
        smallBroker.publish(new Message("A", "B", "2"));

        // A third publish should block — verify it unblocks when a consumer drains
        CountDownLatch publishStarted = new CountDownLatch(1);
        CountDownLatch publishFinished = new CountDownLatch(1);

        Thread publisher =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    publishStarted.countDown();
                                    smallBroker.publish(
                                            new Message("A", "B", "3")); // must block here
                                    publishFinished.countDown();
                                });

        assertTrue(publishStarted.await(1, TimeUnit.SECONDS));
        // Give the publisher a moment to block
        Thread.sleep(50);
        assertFalse(publishFinished.getCount() == 0, "publish() should still be blocked");

        // Draining one slot must unblock the publisher
        smallBroker.receive("B");
        assertTrue(publishFinished.await(2, TimeUnit.SECONDS), "publish() did not unblock");
        publisher.join(500);
    }

    // ── Construction guard ────────────────────────────────────────────────────

    @Test
    void zeroCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryMessageBroker(0));
    }

    @Test
    void negativeCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryMessageBroker(-1));
    }

    // ── shutdown ──────────────────────────────────────────────────────────────

    @Test
    void shutdownClearsInboxes() {
        broker.register("A");
        broker.register("B");
        broker.shutdown();
        // After shutdown, attempting to publish should throw (recipient unknown)
        assertThrows(
                IllegalArgumentException.class,
                () -> broker.publish(new Message("A", "B", "late")));
    }
}
