package io.fleet.common;

/**
 * Deterministic, allocation-free sensor simulation.
 *
 * <p>This class is the experimental control for Pillar A. Both edge-device
 * variants drive an identical {@code SensorModel} seeded identically, so the
 * two implementations process exactly the same sequence of readings. Only
 * the way they assemble and emit those readings differs. Without this, a
 * measured difference between the variants could be a difference in the data
 * rather than in the engineering discipline.
 *
 * <p>Values are exposed as primitive accessors after {@link #advance()}
 * rather than returned in an object, so generating a reading costs no
 * allocation. {@link java.util.Random} is avoided because its shared-state
 * CAS is a confounder on the constrained hot path.
 *
 * <p>Not thread-safe: one instance per device, driven by that device only.
 */
public final class SensorModel {

    /** Battery percentage consumed per reading. */
    public static final double BATTERY_DRAIN_PER_READING = 0.05;

    private static final double MIN_TEMPERATURE_C = 15.0;
    private static final double TEMPERATURE_RANGE_C = 20.0;
    private static final double MAX_VIBRATION = 5.0;
    private static final double COORDINATE_JITTER_DEGREES = 0.01;

    private final double baseLatitude;
    private final double baseLongitude;

    private long state;
    private double temperature;
    private double vibration;
    private double batteryLevel = 100.0;
    private double latitude;
    private double longitude;

    public SensorModel(long seed, double baseLatitude, double baseLongitude) {
        // xorshift degenerates to all-zero from a zero seed.
        this.state = (seed == 0L) ? 0x9E3779B97F4A7C15L : seed;
        this.baseLatitude = baseLatitude;
        this.baseLongitude = baseLongitude;
        this.latitude = baseLatitude;
        this.longitude = baseLongitude;
    }

    /** Advances every sensor by one reading. */
    public void advance() {
        temperature = MIN_TEMPERATURE_C + nextUnit() * TEMPERATURE_RANGE_C;
        vibration = nextUnit() * MAX_VIBRATION;
        batteryLevel = Math.max(0.0, batteryLevel - BATTERY_DRAIN_PER_READING);
        latitude = baseLatitude + (nextUnit() - 0.5) * COORDINATE_JITTER_DEGREES;
        longitude = baseLongitude + (nextUnit() - 0.5) * COORDINATE_JITTER_DEGREES;
    }

    public double temperature() {
        return temperature;
    }

    public double vibration() {
        return vibration;
    }

    public double batteryLevel() {
        return batteryLevel;
    }

    public double latitude() {
        return latitude;
    }

    public double longitude() {
        return longitude;
    }

    public DeviceStatus status() {
        return DeviceStatus.classify(batteryLevel, vibration);
    }

    /** xorshift64*, chosen for being deterministic, fast, and allocation-free. */
    private long nextBits() {
        long x = state;
        x ^= (x >>> 12);
        x ^= (x << 25);
        x ^= (x >>> 27);
        state = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    /** Uniform in [0, 1). */
    private double nextUnit() {
        return (nextBits() >>> 11) * 0x1.0p-53;
    }
}
