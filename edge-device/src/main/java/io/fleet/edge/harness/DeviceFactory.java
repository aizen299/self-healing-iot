package io.fleet.edge.harness;

import io.fleet.common.SensorModel;
import io.fleet.common.TelemetrySink;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.EdgeDevice;
import io.fleet.edge.FailureInjector;
import io.fleet.edge.constrained.ConstrainedEdgeDevice;
import io.fleet.edge.naive.NaiveEdgeDevice;

import java.util.ArrayList;
import java.util.List;

/** Builds a fleet of the configured variant. */
public final class DeviceFactory {

    /**
     * Each device gets its own {@link SensorModel} seeded from
     * {@link DeviceConfig#seedFor(int)}, so devices differ from one another
     * while the fleet as a whole replays identically between runs — and, more
     * importantly, identically between the two variants.
     */
    public static List<EdgeDevice> createFleet(DeviceConfig config, TelemetrySink sink) {
        List<EdgeDevice> devices = new ArrayList<>(config.deviceCount());
        for (int index = 1; index <= config.deviceCount(); index++) {
            devices.add(createDevice(config, sink, index));
        }
        return devices;
    }

    public static EdgeDevice createDevice(DeviceConfig config, TelemetrySink sink, int index) {
        String deviceId = config.deviceId(index);
        SensorModel sensor = new SensorModel(
                config.seedFor(index), config.baseLatitude(), config.baseLongitude());
        FailureInjector failures = FailureInjector.from(config);

        return switch (config.variant()) {
            case CONSTRAINED -> new ConstrainedEdgeDevice(deviceId, sensor, sink, failures);
            case NAIVE -> new NaiveEdgeDevice(deviceId, sensor, sink, failures);
        };
    }

    private DeviceFactory() {
    }
}
