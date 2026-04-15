package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.model.Message;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmark establishing a throughput baseline for {@link InMemoryMessageBroker}.
 *
 * <p><strong>Why benchmark at all?</strong><br>
 * "Measure, don't guess" is the first rule of performance work. This benchmark establishes a
 * baseline ops/s figure <em>before</em> any optimisation so we can prove (or disprove) that a
 * change actually helped. Without a baseline, claimed improvements are just anecdotes.
 *
 * <p><strong>Why JMH and not a hand-written loop?</strong><br>
 * The JVM's JIT compiler is extremely aggressive: it will constant-fold, dead-code-eliminate, and
 * inline across call boundaries in ways that make a naïve {@code System.nanoTime()} loop wildly
 * misleading. JMH handles:
 *
 * <ul>
 *   <li>Warm-up iterations — lets the JIT fully compile the hot path before timing begins.
 *   <li>Dead-code prevention — forces results through a {@link Blackhole} so the JIT cannot
 *       eliminate the call to {@link InMemoryMessageBroker#receive} on the grounds that its return
 *       value is unused.
 *   <li>Statistical reporting — runs multiple forks and iterations, reports mean ±
 *       standard-deviation, and flags high variance automatically.
 *   <li>Forked JVM — each fork starts in a clean JVM state so prior warm-up state does not
 *       contaminate subsequent measurements.
 * </ul>
 *
 * <p><strong>Benchmark scenarios:</strong>
 *
 * <ul>
 *   <li>{@link #publishThenReceive} — sequential publish + receive on the same thread. Establishes
 *       the absolute minimum latency of the happy path (no contention, no blocking) and the pure
 *       overhead of the bounded {@link java.util.concurrent.BlockingQueue}.
 *   <li>{@link #concurrentProducerConsumer} — one producer thread continuously publishes while the
 *       benchmark thread consumes. Measures throughput under realistic two-thread contention,
 *       matching the 2-player {@link com.playermessaging.player.sameprocess.Conversation} model.
 * </ul>
 *
 * <p><strong>Running the benchmark:</strong>
 *
 * <pre>
 *   mvn clean package -DskipTests
 *   java -jar target/player-benchmark.jar BrokerThroughputBenchmark
 *
 *   # or with custom parameters:
 *   java -jar target/player-benchmark.jar BrokerThroughputBenchmark \
 *       -wi 3 -i 5 -f 2 -tu us
 * </pre>
 */
@BenchmarkMode(Mode.Throughput) // measure operations per second
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread) // each JMH thread gets its own state instance
@Warmup(iterations = 3, time = 1) // 3 × 1-second warm-up rounds
@Measurement(iterations = 5, time = 2) // 5 × 2-second measurement rounds
@Fork(2) // run in 2 independent JVM forks for reliability
public class BrokerThroughputBenchmark {

    // -------------------------------------------------------------------------
    // Shared state
    // -------------------------------------------------------------------------

    private InMemoryMessageBroker broker;
    private Message testMessage;

    // Pre-allocated player names to avoid String allocation inside the hot path.
    private static final String SENDER = "PlayerA";
    private static final String RECIPIENT = "PlayerB";

    // -------------------------------------------------------------------------
    // JMH lifecycle
    // -------------------------------------------------------------------------

    /**
     * Set up a fresh broker and register both players before each benchmark iteration.
     *
     * <p>Why {@link Level#Invocation} and not {@link Level#Trial}? We want each single
     * publish+receive round to start with an empty queue so the queue never fills up mid-iteration
     * and triggers backpressure from the bounded {@link java.util.concurrent.LinkedBlockingQueue}.
     * A trial-level setup would share a single broker across thousands of iterations, letting the
     * queue fill and artificially throttle throughput.
     *
     * <p>Caveat: {@link Level#Invocation} setup runs before <em>every</em> benchmark call, adding
     * setup overhead. For this benchmark that overhead is intentional — we are measuring the
     * broker's per-operation cost, not the steady-state rate of an already-warmed broker.
     */
    @Setup(Level.Invocation)
    public void setUp() {
        // Use a large capacity so publish() never blocks due to a full queue
        // during the benchmark.  We are measuring pure throughput, not
        // backpressure behaviour (which is a separate concern).
        broker = new InMemoryMessageBroker(64);
        broker.register(SENDER);
        broker.register(RECIPIENT);
        testMessage = new Message(SENDER, RECIPIENT, "benchmark-payload");
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
        broker.shutdown();
    }

    // -------------------------------------------------------------------------
    // Benchmark methods
    // -------------------------------------------------------------------------

    /**
     * Measures the throughput of one complete publish-then-receive round-trip on a single thread.
     *
     * <p>This is the most optimistic scenario (no lock contention between threads) and gives an
     * upper-bound estimate of how fast the broker can move messages when the consumer is always
     * ready.
     *
     * <p>The {@link Blackhole} prevents the JIT from treating the received message as dead code and
     * eliminating the {@code receive()} call entirely.
     */
    @Benchmark
    public void publishThenReceive(Blackhole bh) throws InterruptedException {
        broker.publish(testMessage);
        bh.consume(broker.receive(RECIPIENT));
    }

    /**
     * Measures throughput under realistic two-thread producer-consumer contention — closer to the
     * actual 2-player conversation model.
     *
     * <p>A background producer thread publishes messages as fast as it can; the benchmark thread
     * consumes them. The measured ops/s reflects the throughput of {@code receive()} under
     * contention on the internal {@link java.util.concurrent.LinkedBlockingQueue}.
     *
     * <p>Why a separate thread for production? In the real system, PlayerA sends and PlayerB
     * receives on separate threads — the queue is always accessed from two different threads
     * simultaneously. A single-threaded benchmark would never exercise the CAS contention on the
     * queue's head/tail pointers.
     */
    @Benchmark
    public void concurrentProducerConsumer(Blackhole bh) throws InterruptedException {
        // Submit a producer that sends 1000 messages then stops.
        // The benchmark thread consumes all 1000.
        //
        // Why 1000?  Large enough to amortise thread-start overhead across
        // many operations, small enough that the queue (capacity 64) provides
        // real backpressure and forces interleaving between producer and consumer.
        final int COUNT = 1000;

        Thread producer =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    for (int i = 0; i < COUNT; i++) {
                                        try {
                                            broker.publish(testMessage);
                                        } catch (Exception ignored) {
                                            break;
                                        }
                                    }
                                });

        for (int i = 0; i < COUNT; i++) {
            bh.consume(broker.receive(RECIPIENT));
        }

        producer.join();
    }

    // -------------------------------------------------------------------------
    // Standalone entry point (alternative to fat-jar)
    // -------------------------------------------------------------------------

    /**
     * Allows running the benchmark directly with {@code java -cp ... BrokerThroughputBenchmark}.
     * The recommended way is via the fat-jar ({@code player-benchmark.jar}).
     */
    public static void main(String[] args) throws Exception {
        Options opt =
                new OptionsBuilder()
                        .include(BrokerThroughputBenchmark.class.getSimpleName())
                        .warmupIterations(3)
                        .measurementIterations(5)
                        .forks(1)
                        .build();
        new Runner(opt).run();
    }
}
