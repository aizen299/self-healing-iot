package io.fleet.edge.harness;

import io.fleet.common.SinkException;
import io.fleet.edge.DeviceConfig;
import io.fleet.edge.EdgeDevice;
import io.fleet.edge.sink.CountingSinkFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The fleet a process builds for itself.
 *
 * <p>Kubernetes runs one device per pod, so which devices a process owns stops
 * being "all of them" and becomes a property of its configuration.
 */
class DeviceFactoryTest {

    @Test
    @DisplayName("without an offset the fleet is the whole one-based range")
    void buildsTheWholeFleetByDefault() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "3"));

        assertEquals(List.of("device-001", "device-002", "device-003"), idsOf(config));
    }

    @Test
    @DisplayName("an offset builds that slice, not a renamed copy of the first")
    void buildsOnlyTheSliceItOwns() throws SinkException {
        DeviceConfig config = DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "2",
                "FLEET_DEVICE_INDEX_OFFSET", "3"));

        assertEquals(List.of("device-004", "device-005"), idsOf(config));
    }

    @Test
    @DisplayName("slices tile the fleet without overlapping")
    void slicesPartitionTheFleet() throws SinkException {
        // What the per-device pods do: three processes, one device each, and
        // between them exactly the fleet a single three-device harness builds.
        List<String> whole = idsOf(DeviceConfig.from(Map.of("FLEET_DEVICE_COUNT", "3")));

        List<String> tiled = List.of(
                idsOf(sliceAt(0)).get(0), idsOf(sliceAt(1)).get(0), idsOf(sliceAt(2)).get(0));

        assertEquals(whole, tiled);
    }

    private static DeviceConfig sliceAt(int offset) {
        return DeviceConfig.from(Map.of(
                "FLEET_DEVICE_COUNT", "1",
                "FLEET_DEVICE_INDEX_OFFSET", Integer.toString(offset)));
    }

    private static List<String> idsOf(DeviceConfig config) throws SinkException {
        List<EdgeDevice> devices = DeviceFactory.createFleet(config, new CountingSinkFactory());
        return devices.stream().map(EdgeDevice::deviceId).toList();
    }
}
