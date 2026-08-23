package io.fleet.common;

import java.util.Map;
import java.util.function.Function;

/**
 * Reads configuration values from an environment map, failing loudly on
 * anything it cannot interpret.
 *
 * <p>Shared so every module reports a bad value the same way — naming the
 * variable and showing what it received. The alternative, a copy of these
 * helpers per module, is how one module ends up silently defaulting where
 * another throws.
 *
 * <p>Every method takes the map rather than reading {@code System.getenv()}
 * directly, so configuration is testable without a real environment.
 */
public final class Env {

    /** Trimmed value, or {@code fallback} when unset or blank. */
    public static String string(Map<String, String> env, String key, String fallback) {
        String raw = env.get(key);
        return (raw == null || raw.isBlank()) ? fallback : raw.trim();
    }

    public static long longValue(Map<String, String> env, String key, long fallback) {
        String raw = string(env, key, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer, got '" + raw + "'", e);
        }
    }

    /**
     * Range-checks before narrowing. A bare {@code (int)} cast would turn a
     * too-large value into a different, plausible one and let it pass every
     * check that follows.
     */
    public static int intValue(Map<String, String> env, String key, int fallback) {
        long parsed = longValue(env, key, fallback);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new ConfigurationException(key + " must fit in a 32-bit integer, got " + parsed);
        }
        return (int) parsed;
    }

    public static double doubleValue(Map<String, String> env, String key, double fallback) {
        String raw = string(env, key, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be a number, got '" + raw + "'", e);
        }
    }

    /**
     * Strict on purpose: {@code Boolean.parseBoolean} maps anything
     * unrecognised to false, which silently disables a feature the operator
     * meant to enable.
     */
    public static boolean booleanValue(Map<String, String> env, String key, boolean fallback) {
        String raw = string(env, key, null);
        if (raw == null) {
            return fallback;
        }
        if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(raw);
        }
        throw new ConfigurationException(key + " must be true or false, got '" + raw + "'");
    }

    public static <T> T enumValue(
            Map<String, String> env, String key, String fallback, Function<String, T> parser) {
        String raw = string(env, key, fallback);
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(key + " has unknown value '" + raw + "'", e);
        }
    }

    private Env() {
    }
}
