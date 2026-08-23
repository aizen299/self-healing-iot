package io.fleet.edge.harness;

import io.fleet.common.SensorModel;
import io.fleet.common.SinkException;
import io.fleet.common.TelemetrySink;
import io.fleet.common.TelemetrySinkFactory;
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
    public static List<EdgeDevice> createFleet(DeviceConfig config, TelemetrySinkFactory sinks)
            throws SinkException {
        List<EdgeDevice> devices = new ArrayList<>(config.deviceCount());
        for (int index = 1; index <= config.deviceCount(); index++) {
            devices.add(createDevice(config, sinks, index));
        }
        return devices;
    }

    public static EdgeDevice createDevice(
            DeviceConfig config, TelemetrySinkFactory sinks, int index) throws SinkException {
        String deviceId = config.deviceId(index);
        TelemetrySink sink = sinks.create(deviceId);
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
