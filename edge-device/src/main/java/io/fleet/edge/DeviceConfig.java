package io.fleet.edge;

import java.util.Locale;
import java.util.Map;

/**
 * Simulator configuration.
 *
 * <p>Every value comes from the environment — nothing is hardcoded at a call
 * site — so an experiment is fully described by its environment block and can
 * be replayed from the record in {@code experiments/configs/}.
 *
 * @param variant               implementation under test
 * @param deviceCount           devices in the fleet
 * @param deviceIdPrefix        prefix for generated device ids
 * @param publishIntervalMillis delay between readings, per device
 * @param runDurationSeconds    how long the harness runs
 * @param failureMode           failure to inject, if any
 * @param failAfterReadings     readings before the failure triggers
 * @param floodMultiplier       readings per tick once MESSAGE_FLOOD triggers
 * @param seed                  base seed; each device derives its own from this
 * @param baseLatitude          fleet centre latitude
 * @param baseLongitude         fleet centre longitude
 */
public record DeviceConfig(
        Variant variant,
        int deviceCount,
        String deviceIdPrefix,
        long publishIntervalMillis,
        long runDurationSeconds,
        FailureMode failureMode,
        long failAfterReadings,
        int floodMultiplier,
        long seed,
        double baseLatitude,
        double baseLongitude) {

    /** Fleet size scoped by ADR-003. */
    public static final int DEFAULT_DEVICE_COUNT = 50;

    public DeviceConfig {
        if (variant == null) {
            throw new ConfigurationException("variant is required");
        }
        if (failureMode == null) {
            throw new ConfigurationException("failureMode is required");
        }
        if (deviceIdPrefix == null || deviceIdPrefix.isBlank()) {
            throw new ConfigurationException("deviceIdPrefix must not be blank");
        }
        if (deviceCount < 1) {
            throw new ConfigurationException("deviceCount must be >= 1, got " + deviceCount);
        }
        if (publishIntervalMillis < 1) {
            throw new ConfigurationException(
                    "publishIntervalMillis must be >= 1, got " + publishIntervalMillis);
        }
        if (runDurationSeconds < 1) {
            throw new ConfigurationException(
                    "runDurationSeconds must be >= 1, got " + runDurationSeconds);
        }
        if (failAfterReadings < 0) {
            throw new ConfigurationException(
                    "failAfterReadings must be >= 0, got " + failAfterReadings);
        }
        if (failureMode != FailureMode.NONE && failAfterReadings < 1) {
            throw new ConfigurationException(
                    "failureMode " + failureMode + " requires failAfterReadings >= 1;"
                            + " a failure that never triggers is not a reproducible experiment");
        }
        if (failureMode == FailureMode.MESSAGE_FLOOD && floodMultiplier < 2) {
            throw new ConfigurationException(
                    "floodMultiplier must be >= 2 for MESSAGE_FLOOD, got " + floodMultiplier);
        }
        if (baseLatitude < -90.0 || baseLatitude > 90.0) {
            throw new ConfigurationException("baseLatitude out of range: " + baseLatitude);
        }
        if (baseLongitude < -180.0 || baseLongitude > 180.0) {
            throw new ConfigurationException("baseLongitude out of range: " + baseLongitude);
        }
    }

    /** Reads configuration from the process environment. */
    public static DeviceConfig fromEnv() {
        return from(System.getenv());
    }

    /** Reads configuration from an arbitrary map, so tests need no real environment. */
    public static DeviceConfig from(Map<String, String> env) {
        return new DeviceConfig(
                parseEnum("FLEET_VARIANT", env, "CONSTRAINED", Variant::parse),
                (int) parseLong("FLEET_DEVICE_COUNT", env, DEFAULT_DEVICE_COUNT),
                value("FLEET_DEVICE_ID_PREFIX", env, "device"),
                parseLong("FLEET_PUBLISH_INTERVAL_MS", env, 1000L),
                parseLong("FLEET_RUN_DURATION_SECONDS", env, 30L),
                parseEnum("FLEET_FAILURE_MODE", env, "NONE", FailureMode::parse),
                parseLong("FLEET_FAIL_AFTER", env, 0L),
                (int) parseLong("FLEET_FLOOD_MULTIPLIER", env, 10L),
                parseLong("FLEET_SEED", env, 42L),
                parseDouble("FLEET_BASE_LAT", env, 52.5200d),
                parseDouble("FLEET_BASE_LON", env, 13.4050d));
    }

    /** Zero-padded device id, e.g. {@code device-007}. */
    public String deviceId(int index) {
        return String.format(Locale.ROOT, "%s-%03d", deviceIdPrefix, index);
    }

    /** Per-device seed, so devices differ from each other but a run repeats exactly. */
    public long seedFor(int index) {
        return seed * 31L + index;
    }

    private static String value(String key, Map<String, String> env, String fallback) {
        String raw = env.get(key);
        return (raw == null || raw.isBlank()) ? fallback : raw.trim();
    }

    private static long parseLong(String key, Map<String, String> env, long fallback) {
        String raw = value(key, env, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be an integer, got '" + raw + "'", e);
        }
    }

    private static double parseDouble(String key, Map<String, String> env, double fallback) {
        String raw = value(key, env, null);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + " must be a number, got '" + raw + "'", e);
        }
    }

    private static <T> T parseEnum(
            String key, Map<String, String> env, String fallback, java.util.function.Function<String, T> parser) {
        String raw = value(key, env, fallback);
        try {
            return parser.apply(raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(key + " has unknown value '" + raw + "'", e);
        }
    }
}
