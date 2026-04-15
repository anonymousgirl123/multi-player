package com.playermessaging.player.sameprocess;

import com.playermessaging.player.common.config.ConfigLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Application entry point for the <em>same-process</em> scenario.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Parse an optional command-line argument specifying how many players should participate
 *       (default: 2).
 *   <li>Build the ordered list of player names and pass it to {@link Conversation}, which handles
 *       all wiring.
 *   <li>Keep {@code main} free of any business or wiring logic.
 * </ul>
 *
 * <p>All players run as separate threads inside this single JVM process. Use {@code
 * run-same-process.sh} to launch this class.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>
 *   java -jar player-same-process.jar                    # default: 2 players (PlayerA, PlayerB)
 *   java -jar player-same-process.jar 3                  # 3 players by count (PlayerA, PlayerB, PlayerC)
 *   java -jar player-same-process.jar Alice Bob Charlie  # 3 named players
 * </pre>
 */
public final class SameProcessMain {

    private static final Logger log = LoggerFactory.getLogger(SameProcessMain.class);

    private SameProcessMain() {
        /* utility class – no instances */
    }

    public static void main(String[] args) throws InterruptedException {
        // Resolve the player list from CLI args:
        //   - no args        → default count from configuration.properties
        //   - single number  → generate PlayerA, PlayerB, … up to that count
        //   - one or more names → use them directly (at least 2 required)
        List<String> names = resolvePlayerNames(args);

        log.info(
                "=== Same-Process Player Messaging | PID: {} | Players: {} ===",
                ProcessHandle.current().pid(),
                names.size());

        // Builder Pattern: ConversationBuilder reads defaults from
        // configuration.properties and allows any subset to be overridden
        // programmatically — without changing the Conversation class itself.
        new ConversationBuilder().players(names).build().start();

        log.info("=== Conversation finished. ===");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the ordered list of player names from command-line arguments.
     *
     * <p>Three modes:
     *
     * <ol>
     *   <li><strong>No args</strong> — reads {@code player.count} from {@code
     *       configuration.properties} and generates names (PlayerA, PlayerB, …).
     *   <li><strong>Single numeric arg</strong> (e.g. {@code 3}) — generates that many names.
     *   <li><strong>Two or more non-numeric args</strong> (e.g. {@code Alice Bob Charlie}) — uses
     *       the supplied names directly.
     * </ol>
     *
     * @param args command-line arguments from {@code main}.
     * @return ordered list of player names, always containing at least 2 entries.
     */
    private static List<String> resolvePlayerNames(String[] args) {
        // No arguments — use the configured default count.
        if (args.length == 0) {
            int configured = ConfigLoader.getInt("player.count");
            if (configured < 2) {
                log.warn("player.count in configuration.properties must be ≥ 2. Defaulting to 2.");
                configured = 2;
            }
            return buildPlayerNames(configured);
        }

        // Single argument: if it parses as an integer, treat it as a count.
        if (args.length == 1) {
            try {
                int n = Integer.parseInt(args[0]);
                if (n < 2) {
                    log.warn("Player count must be at least 2. Defaulting to 2.");
                    n = 2;
                }
                return buildPlayerNames(n);
            } catch (NumberFormatException e) {
                // Single non-numeric arg — treat as one name, but we need at least 2.
                log.warn(
                        "A single player name '{}' was provided — at least 2 are required."
                                + " Defaulting to 2 generated names.",
                        args[0]);
                return buildPlayerNames(2);
            }
        }

        // Two or more arguments — treat them all as player names.
        return new ArrayList<>(List.of(args));
    }

    /**
     * Generates {@code count} player names: PlayerA, PlayerB, PlayerC, … Uses letters A–Z for the
     * first 26 players, then Player27, Player28, … beyond that.
     */
    private static List<String> buildPlayerNames(int count) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Use letter suffixes up to 26 players, then fall back to numbers.
            String suffix = (i < 26) ? String.valueOf((char) ('A' + i)) : String.valueOf(i + 1);
            names.add("Player" + suffix);
        }
        return names;
    }
}
