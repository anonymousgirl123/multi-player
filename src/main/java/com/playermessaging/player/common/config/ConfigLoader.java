package com.playermessaging.player.common.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads application configuration from {@code configuration.properties} and promotes every key to a
 * {@link System} property so that downstream frameworks (notably Logback) can read them via {@code
 * ${key:-default}} substitution before the first {@code Logger} is created.
 *
 * <p><strong>Why a dedicated loader and not {@code System.getProperty()}?</strong><br>
 * System properties require every value to be passed on the command line ({@code
 * -Dmax.messages=10}). A properties file keeps all defaults in one readable place and lets
 * operators tune behaviour by editing a text file — no recompile, no long command lines.
 *
 * <p><strong>Why also promote to System properties?</strong><br>
 * Logback resolves {@code ${log.level:-INFO}} in {@code logback.xml} by reading {@link
 * System#getProperty(String)}. Promoting our properties lets a single {@code
 * configuration.properties} control both application logic and logging configuration without
 * duplicating values. An explicit {@code -Dlog.level=DEBUG} on the command line still wins because
 * {@link System#setProperty} only sets a property when no existing system-property value is present
 * (see code below).
 *
 * <p><strong>Load order (first match wins):</strong>
 *
 * <ol>
 *   <li>A {@code configuration.properties} file in the <em>current working directory</em> — useful
 *       for overriding defaults without touching the jar.
 *   <li>The {@code configuration.properties} bundled inside the fat-jar on the classpath — the safe
 *       default shipped with the application.
 * </ol>
 *
 * This two-step strategy means the jar "just works" out of the box but can be reconfigured by
 * dropping an override file next to it.
 *
 * <p>This class is a utility (all static methods, no instances). It is eagerly initialised at
 * class-load time; any I/O error loading the classpath copy is treated as a fatal misconfiguration
 * and throws {@link ExceptionInInitializerError}.
 */
public final class ConfigLoader {

    /** File name looked up on both the file system and the classpath. */
    private static final String CONFIG_FILE = "configuration.properties";

    /**
     * The resolved properties — loaded once at startup and shared by all callers.
     *
     * <p>Why load once? Properties are immutable after startup; reloading on every access would add
     * unnecessary I/O overhead and complicate thread-safety.
     */
    private static final Properties PROPS = load();

    /** Utility class — no instances. */
    private ConfigLoader() {}

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the value for {@code key} as a {@code String}.
     *
     * <p><strong>Lookup order (first match wins):</strong>
     *
     * <ol>
     *   <li>JVM system property ({@code -Dkey=value} on the command line, or set by the container
     *       runtime via {@code JAVA_OPTS}). This gives operators a way to override any value
     *       without rebuilding the image.
     *   <li>Value from {@code configuration.properties} (file-system override or classpath bundle,
     *       resolved at class-load time).
     * </ol>
     *
     * @param key the property key (e.g. {@code "opening.message"}).
     * @return the configured value; never {@code null}.
     * @throws IllegalStateException if the key is absent from both system properties and the
     *     properties file.
     */
    public static String getString(String key) {
        // Check JVM system properties first so that -Dkey=value overrides the file.
        // promoteToSystemProperties() already pushed file values into System, but
        // only when no system property existed yet — so explicit -D flags win.
        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isEmpty()) {
            return sysProp.trim();
        }
        String value = PROPS.getProperty(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Missing required configuration key: '" + key + "' in " + CONFIG_FILE);
        }
        return value.trim();
    }

    /**
     * Returns the value for {@code key} as an {@code int}.
     *
     * @param key the property key (e.g. {@code "max.messages"}).
     * @return the configured integer value.
     * @throws IllegalStateException if the key is absent or not a valid integer.
     */
    public static int getInt(String key) {
        String raw = getString(key);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Configuration key '" + key + "' must be an integer, got: '" + raw + "'");
        }
    }

    /**
     * Returns the value for {@code key} as a {@code long}.
     *
     * @param key the property key (e.g. {@code "connect.delay.ms"}).
     * @return the configured long value.
     * @throws IllegalStateException if the key is absent or not a valid long.
     */
    public static long getLong(String key) {
        String raw = getString(key);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "Configuration key '" + key + "' must be a long integer, got: '" + raw + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Internal loader
    // -------------------------------------------------------------------------

    /**
     * Loads {@code configuration.properties}, trying the file system first, then falling back to
     * the classpath.
     *
     * <p>After loading, every key–value pair is promoted to a {@link System} property (unless a
     * system property with that key already exists — this preserves command-line overrides like
     * {@code -Dlog.level=DEBUG}). Logback reads {@code ${log.level:-INFO}} from system properties
     * before the first {@code Logger} is created, so the promotion must happen here, at class-load
     * time.
     *
     * <p>Why try the file system first? Operators should be able to override defaults by placing a
     * {@code configuration.properties} file next to the fat-jar without rebuilding. If no override
     * file is present, the bundled defaults are used.
     */
    private static Properties load() {
        Properties props = new Properties();

        // 1. Try file-system override (working directory).
        File override = new File(CONFIG_FILE);
        if (override.exists()) {
            try (InputStream fs = new FileInputStream(override)) {
                props.load(fs);
                System.out.println(
                        "[ConfigLoader] Loaded configuration from file system: "
                                + override.getAbsolutePath());
                promoteToSystemProperties(props);
                return props;
            } catch (IOException e) {
                System.err.println(
                        "[ConfigLoader] Warning: found '"
                                + CONFIG_FILE
                                + "' on file system but could not read it: "
                                + e.getMessage()
                                + ". Falling back to classpath copy.");
                props.clear();
            }
        }

        // 2. Fall back to classpath (bundled inside the jar).
        try (InputStream cp =
                ConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {

            if (cp == null) {
                throw new IllegalStateException(
                        "'"
                                + CONFIG_FILE
                                + "' not found on classpath. "
                                + "Ensure it is present in src/main/resources/.");
            }
            props.load(cp);
            System.out.println("[ConfigLoader] Loaded configuration from classpath.");
            promoteToSystemProperties(props);
            return props;

        } catch (IOException e) {
            throw new ExceptionInInitializerError(
                    "Failed to load '" + CONFIG_FILE + "' from classpath: " + e.getMessage());
        }
    }

    /**
     * Copies each key–value pair from {@code props} into {@link System#setProperty} only if no
     * system property with that key already exists.
     *
     * <p>Why "only if absent"? A JVM argument like {@code -Dlog.level=DEBUG} is already present in
     * system properties before this class is loaded. Overwriting it would silently ignore the
     * operator's explicit override — the opposite of what they intend. Checking first gives
     * command-line flags priority over the file.
     */
    private static void promoteToSystemProperties(Properties props) {
        props.forEach(
                (key, value) -> {
                    String k = key.toString();
                    if (System.getProperty(k) == null) {
                        System.setProperty(k, value.toString().trim());
                    }
                });
    }
}
