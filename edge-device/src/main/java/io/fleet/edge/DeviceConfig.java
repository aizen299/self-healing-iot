package io.fleet.edge;

import io.fleet.common.ConfigurationException;
import io.fleet.common.Env;

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
 * @param sink                  where telemetry goes
 * @param deviceCount           devices in the fleet
 * @param deviceIdPrefix        prefix for generated device ids
 * @param deviceIndexOffset     first device index minus one; lets a process own a
 *                              slice of the fleet rather than always starting at 1
 * @param publishIntervalMillis delay between readings, per device
 * @param runDurationSeconds    how long the harness runs
 * @param failureMode           failure to inject, if any
 * @param failAfterReadings     readings before the failure triggers
 * @param floodMultiplier       readings per tick once MESSAGE_FLOOD triggers
 * @param interruptDurationMillis how long a NETWORK_INTERRUPTION lasts
 * @param seed                  base seed; each device derives its own from this
 * @param baseLatitude          fleet centre latitude
 * @param baseLongitude         fleet centre longitude
 */
public record DeviceConfig(
        Variant variant,
        SinkType sink,
        int deviceCount,
        String deviceIdPrefix,
        int deviceIndexOffset,
        long publishIntervalMillis,
        long runDurationSeconds,
        FailureMode failureMode,
        long failAfterReadings,
        int floodMultiplier,
        long interruptDurationMillis,
        long seed,
        double baseLatitude,
        double baseLongitude) {

    /** Fleet size scoped by ADR-003. */
    public static final int DEFAULT_DEVICE_COUNT = 50;

    /**
     * Bounded so a generated device id cannot overflow the constrained
     * variant's fixed 256-byte payload buffer. Without this the failure
     * surfaces as a runtime error on every tick, which the harness counts and
     * the run then reports as a completed run with zero readings — and it
     * would hit only the constrained variant, silently breaking the parity
     * the comparison depends on.
     */
    public static final int MAX_DEVICE_ID_PREFIX_LENGTH = 64;

    /**
     * Highest device index any process may own.
     *
     * <p>Bounded for the same reason as the prefix above, and against the same
     * failure. {@code deviceIndexOffset + deviceCount} is int arithmetic: at
     * offset 2147483000 with 1000 devices it overflows negative, the factory's
     * loop never runs, and the harness reports a completed run at zero
     * throughput — a silently empty fleet rather than a rejected
     * configuration. A million devices is four orders of magnitude past
     * ADR-003's scope and still nowhere near the overflow.
     */
    public static final int MAX_DEVICE_INDEX = 1_000_000;

    public DeviceConfig {
        if (variant == null) {
            throw new ConfigurationException("variant is required");
        }
        if (failureMode == null) {
            throw new ConfigurationException("failureMode is required");
        }
        if (sink == null) {
            throw new ConfigurationException("sink is required");
        }
        if (deviceIdPrefix == null || deviceIdPrefix.isBlank()) {
            throw new ConfigurationException("deviceIdPrefix must not be blank");
        }
        if (deviceIdPrefix.length() > MAX_DEVICE_ID_PREFIX_LENGTH) {
            throw new ConfigurationException(
                    "deviceIdPrefix must be at most " + MAX_DEVICE_ID_PREFIX_LENGTH
                            + " characters, got " + deviceIdPrefix.length());
        }
        if (deviceCount < 1) {
            throw new ConfigurationException("deviceCount must be >= 1, got " + deviceCount);
        }
        if (deviceIndexOffset < 0) {
            throw new ConfigurationException(
                    "deviceIndexOffset must be >= 0, got " + deviceIndexOffset);
        }
        // Widened to long deliberately: the sum is what overflows, so checking
        // it in int arithmetic would check the wrapped value.
        long highestIndex = (long) deviceIndexOffset + deviceCount;
        if (highestIndex > MAX_DEVICE_INDEX) {
            throw new ConfigurationException(
                    "deviceIndexOffset + deviceCount must be <= " + MAX_DEVICE_INDEX
                            + ", got " + highestIndex);
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
        if (failureMode == FailureMode.NETWORK_INTERRUPTION) {
            if (sink != SinkType.MQTT) {
                throw new ConfigurationException(
                        "NETWORK_INTERRUPTION requires FLEET_SINK=MQTT; there is no network to"
                                + " interrupt when telemetry goes to the " + sink + " sink");
            }
            if (interruptDurationMillis < 1) {
                throw new ConfigurationException(
                        "interruptDurationMillis must be >= 1 for NETWORK_INTERRUPTION, got "
                                + interruptDurationMillis);
            }
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
                Env.enumValue(env, "FLEET_VARIANT", "CONSTRAINED", Variant::parse),
                Env.enumValue(env, "FLEET_SINK", "COUNTING", SinkType::parse),
                Env.intValue(env, "FLEET_DEVICE_COUNT", DEFAULT_DEVICE_COUNT),
                Env.string(env, "FLEET_DEVICE_ID_PREFIX", "device"),
                Env.intValue(env, "FLEET_DEVICE_INDEX_OFFSET", 0),
                Env.longValue(env, "FLEET_PUBLISH_INTERVAL_MS", 1000L),
                Env.longValue(env, "FLEET_RUN_DURATION_SECONDS", 30L),
                Env.enumValue(env, "FLEET_FAILURE_MODE", "NONE", FailureMode::parse),
                Env.longValue(env, "FLEET_FAIL_AFTER", 0L),
                Env.intValue(env, "FLEET_FLOOD_MULTIPLIER", 10),
                Env.longValue(env, "FLEET_INTERRUPT_DURATION_MS", 5000L),
                Env.longValue(env, "FLEET_SEED", 42L),
                Env.doubleValue(env, "FLEET_BASE_LAT", 52.5200d),
                Env.doubleValue(env, "FLEET_BASE_LON", 13.4050d));
    }

    /**
     * First device index this process owns, one-based.
     *
     * <p>Indices, not names, because the id and the seed are both derived from
     * the index: a device pulled out into its own process keeps the id and the
     * data it had inside a shared harness, so the two deployments run the same
     * simulation rather than two that merely look alike.
     */
    public int firstIndex() {
        return deviceIndexOffset + 1;
    }

    /** Last device index this process owns, inclusive. */
    public int lastIndex() {
        return deviceIndexOffset + deviceCount;
    }

    /** Zero-padded device id, e.g. {@code device-007}. */
    public String deviceId(int index) {
        return String.format(Locale.ROOT, "%s-%03d", deviceIdPrefix, index);
    }

    /** Per-device seed, so devices differ from each other but a run repeats exactly. */
    public long seedFor(int index) {
        return seed * 31L + index;
    }
}
