package com.playermessaging.player.multiprocess;

import com.playermessaging.player.common.channel.LoggingChannelDecorator;
import com.playermessaging.player.common.channel.MessageChannel;
import com.playermessaging.player.common.channel.MetricsChannelDecorator;
import com.playermessaging.player.common.config.ConfigLoader;
import com.playermessaging.player.common.factory.InitiatorComponentFactory;
import com.playermessaging.player.common.factory.PlayerComponentFactory;
import com.playermessaging.player.common.factory.ResponderComponentFactory;
import com.playermessaging.player.common.role.PlayerRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Application entry point for the <em>multi-process</em> scenario.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Parse the command-line argument that selects the player role ({@code initiator} or {@code
 *       responder}).
 *   <li>Establish the TCP connection between the two OS processes, with retry-with-backoff logic
 *       for the initiator side.
 *   <li>Construct the appropriate {@link PlayerRole} and inject it into {@link Player} alongside
 *       the {@link SocketChannel}.
 *   <li>Register a shutdown hook to release OS resources on SIGTERM.
 *   <li>Close the socket channel on exit to release OS resources.
 * </ul>
 *
 * <p>This class is the <em>composition root</em> for the multi-process scenario: all concrete types
 * ({@link InitiatorRole}, {@link ResponderRole}, {@link FixedMessageCountPolicy}, {@link
 * SocketChannel}) are assembled here. {@link Player} itself knows nothing about which role it holds
 * or what the stop condition is.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>
 *   # Start the responder first (it listens on the socket):
 *   java -jar player-multi-process.jar responder
 *
 *   # Then start the initiator in a separate terminal:
 *   java -jar player-multi-process.jar initiator
 * </pre>
 *
 * <p>Use {@code run-multi-process.sh} to launch both processes automatically.
 */
public final class MultiProcessMain {

    private static final Logger log = LoggerFactory.getLogger(MultiProcessMain.class);

    // All tuneable values are loaded from configuration.properties at startup.
    //
    // Why ConfigLoader instead of hardcoded constants?
    // It puts every knob in one file so an operator can change the port, message
    // count, or delay by editing configuration.properties — no source edits and
    // no recompile needed.  ConfigLoader also supports a file-system override:
    // drop a configuration.properties next to the jar to use different settings
    // without touching the jar itself.

    /** TCP port on which the responder listens and the initiator connects. */
    private static final int PORT = ConfigLoader.getInt("server.port");

    /** Hostname or IP address the initiator connects to. */
    private static final String HOST = ConfigLoader.getString("server.host");

    /** Number of messages the initiator exchanges before stopping. */
    private static final int MAX_MESSAGES = ConfigLoader.getInt("max.messages");

    /** First message sent by the initiator to open the conversation. */
    private static final String OPENING_MESSAGE = ConfigLoader.getString("opening.message");

    /**
     * Milliseconds the initiator waits before its first connection attempt, giving the responder's
     * server socket time to bind and start accepting.
     */
    private static final long CONNECT_DELAY_MS = ConfigLoader.getLong("connect.delay.ms");

    /**
     * Maximum number of connection retries before the initiator gives up.
     *
     * <p>Why retry at all? In the shell script the responder is started in the background and the
     * initiator immediately follows. On a loaded machine the responder may not have bound its port
     * yet when the initiator first tries to connect. A fixed {@code Thread.sleep} is fragile;
     * retries with backoff are more robust across environments.
     */
    private static final int RECONNECT_MAX_ATTEMPTS = ConfigLoader.getInt("reconnect.max.attempts");

    /**
     * Initial delay in milliseconds between retry attempts. Doubles after each failed attempt
     * (exponential backoff) to avoid hammering the network while still reconnecting quickly after
     * transient drops.
     */
    private static final long RECONNECT_DELAY_MS = ConfigLoader.getLong("reconnect.delay.ms");

    private MultiProcessMain() {
        /* utility class – no instances */
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            log.error("Usage: java -jar player-multi-process.jar <initiator|responder>");
            System.exit(1);
        }

        String roleName = args[0].toLowerCase();
        switch (roleName) {
            case "responder":
                runAsResponder();
                break;
            case "initiator":
                runAsInitiator();
                break;
            default:
                log.error("Unknown role: '{}'. Use 'initiator' or 'responder'.", roleName);
                System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Role-specific startup
    // -------------------------------------------------------------------------

    /**
     * Binds a {@link ServerSocket}, waits for the initiator to connect, then starts the player with
     * a {@link ResponderRole}.
     *
     * <p>The responder exits passively via poison-pill; it holds no policy and no opening message —
     * that asymmetry is now visible here in the composition root, not buried inside {@code Player}.
     */
    private static void runAsResponder() throws Exception {
        long pid = ProcessHandle.current().pid();
        log.info("[Responder | PID {}] Listening on port {} …", pid, PORT);

        // try-with-resources closes both the server socket and the client socket
        // for the normal (conversation-finished) exit path.
        try (ServerSocket serverSocket = new ServerSocket(PORT);
                Socket clientSocket = serverSocket.accept()) {

            log.info("[Responder | PID {}] Initiator connected.", pid);

            // Abstract Factory Pattern: factory selects the role + policy family.
            PlayerComponentFactory factory = new ResponderComponentFactory();
            PlayerRole role = factory.createRole();

            // Decorator Pattern: wrap SocketChannel with logging + metrics
            // decorators so that every send/receive is logged at DEBUG level and
            // counted — without touching SocketChannel or Player.
            MetricsChannelDecorator metricsChannel =
                    new MetricsChannelDecorator(
                            new LoggingChannelDecorator(new SocketChannel(clientSocket)));
            MessageChannel channel = metricsChannel;

            // Register a shutdown hook to close the socket on SIGTERM / SIGINT.
            // Why a shutdown hook?
            // try-with-resources only runs when the try block exits normally or via
            // a handled exception.  If the JVM receives SIGTERM (e.g. `kill <pid>`
            // or Ctrl-C) the try block is abruptly interrupted and the socket may
            // not be closed, leaving the OS port bound and the peer's read() hanging.
            // The shutdown hook runs in a dedicated thread during JVM shutdown,
            // ensuring the socket is always closed regardless of how the JVM exits.
            Thread hook =
                    new Thread(
                            () -> {
                                log.warn(
                                        "[Responder | PID {}] Shutdown hook – closing channel.",
                                        pid);
                                channel.close();
                            },
                            "responder-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(hook);

            Player responder = new Player("PlayerB", "PlayerA", channel, role);
            responder.start();
            channel.close();

            log.info(
                    "[Responder | PID {}] Channel stats — sent={} received={}",
                    pid,
                    metricsChannel.getSentCount(),
                    metricsChannel.getReceivedCount());

            // Conversation finished normally — deregister the hook so it does not
            // try to close an already-closed socket during JVM exit.
            Runtime.getRuntime().removeShutdownHook(hook);
        }

        log.info("[Responder | PID {}] Conversation finished.", pid);
    }

    /**
     * Connects to the responder with retry-with-backoff, then starts the player with an {@link
     * InitiatorRole}.
     *
     * <p>Why retry instead of a fixed sleep? A fixed {@code Thread.sleep(CONNECT_DELAY_MS)} before
     * the first connect guesses how long the responder needs to start. That guess is wrong on a
     * loaded machine (too short → connection refused) or wastes time on a fast one (too long →
     * unnecessary wait). Retry-with-backoff adapts: it connects as soon as the responder is ready,
     * regardless of startup speed, and backs off gracefully if the network is temporarily
     * unavailable.
     *
     * <p>InitiatorRole sends the opening message and applies the stop policy; both are injected
     * here at the composition root.
     */
    private static void runAsInitiator() throws Exception {
        long pid = ProcessHandle.current().pid();
        log.info("[Initiator | PID {}] Connecting to responder at {}:{} …", pid, HOST, PORT);

        // Brief initial delay to give the responder process time to bind its port.
        Thread.sleep(CONNECT_DELAY_MS);

        Socket socket = connectWithRetry();

        try (socket) {
            log.info("[Initiator | PID {}] Connected.", pid);

            // Abstract Factory Pattern: factory produces the initiator's role + policy.
            PlayerComponentFactory factory =
                    new InitiatorComponentFactory(OPENING_MESSAGE, MAX_MESSAGES);
            PlayerRole role = factory.createRole();

            // Decorator Pattern: same wrapping as the responder side.
            MetricsChannelDecorator metricsChannel =
                    new MetricsChannelDecorator(
                            new LoggingChannelDecorator(new SocketChannel(socket)));
            MessageChannel channel = metricsChannel;

            // Shutdown hook so SIGTERM / Ctrl-C closes the TCP socket cleanly.
            // See runAsResponder() for the full rationale.
            Thread hook =
                    new Thread(
                            () -> {
                                log.warn(
                                        "[Initiator | PID {}] Shutdown hook – closing channel.",
                                        pid);
                                channel.close();
                            },
                            "initiator-shutdown-hook");
            Runtime.getRuntime().addShutdownHook(hook);

            Player initiator = new Player("PlayerA", "PlayerB", channel, role);
            initiator.start();
            channel.close();

            log.info(
                    "[Initiator | PID {}] Channel stats — sent={} received={}",
                    pid,
                    metricsChannel.getSentCount(),
                    metricsChannel.getReceivedCount());

            // Deregister hook — conversation finished normally.
            Runtime.getRuntime().removeShutdownHook(hook);
        }

        log.info("[Initiator | PID {}] Conversation finished.", pid);
    }

    // -------------------------------------------------------------------------
    // Connection helpers
    // -------------------------------------------------------------------------

    /**
     * Attempts to connect to {@link #HOST}:{@link #PORT} up to {@link #RECONNECT_MAX_ATTEMPTS}
     * times, with exponential backoff between attempts.
     *
     * <p><strong>Backoff strategy:</strong> after the first failure wait {@code RECONNECT_DELAY_MS}
     * ms, then double the delay on each subsequent failure. Doubling prevents hammering a
     * temporarily unreachable peer while still reconnecting quickly after a short outage.
     *
     * @return a connected {@link Socket}.
     * @throws IOException if all attempts are exhausted.
     */
    private static Socket connectWithRetry() throws IOException, InterruptedException {
        long delayMs = RECONNECT_DELAY_MS;

        for (int attempt = 1; attempt <= RECONNECT_MAX_ATTEMPTS + 1; attempt++) {
            try {
                return new Socket(HOST, PORT);
            } catch (IOException e) {
                if (attempt > RECONNECT_MAX_ATTEMPTS) {
                    // All attempts exhausted — propagate the last error.
                    throw new IOException(
                            String.format(
                                    "Failed to connect to %s:%d after %d attempt(s): %s",
                                    HOST, PORT, RECONNECT_MAX_ATTEMPTS, e.getMessage()),
                            e);
                }
                log.warn(
                        "[Initiator] Connection attempt {}/{} failed ({}). Retrying in {} ms …",
                        attempt,
                        RECONNECT_MAX_ATTEMPTS,
                        e.getMessage(),
                        delayMs);
                Thread.sleep(delayMs);

                // Exponential backoff: double the delay up to a 30-second ceiling.
                // Why cap at 30 s?  Beyond that the user experience degrades; if
                // the responder hasn't started in 30 s there's probably a bigger problem.
                delayMs = Math.min(delayMs * 2, 30_000);
            }
        }
        // Unreachable — loop always throws or returns before here.
        throw new IllegalStateException("connectWithRetry: unexpected exit from retry loop");
    }
}
