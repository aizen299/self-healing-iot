package io.fleet.recovery;

import io.fleet.common.ConfigurationException;

/**
 * Recovers a device's place in the fleet from its id.
 *
 * <p>The inverse of {@code DeviceConfig.deviceId(int)}, which formats
 * {@code prefix-%03d} from a one-based index. A replacement has to be told
 * which device it is, and the only thing a failure event carries is the id.
 *
 * <p>The two must stay in step: the id and the sensor seed both derive from
 * the index (ADR-010), so a replacement given the wrong offset would publish
 * under the right name while generating a different device's data — a fleet
 * that looks recovered and is quietly running the wrong experiment.
 */
public final class DeviceIndex {

    private DeviceIndex() {
    }

    /**
     * The {@code FLEET_DEVICE_INDEX_OFFSET} a single-device process needs in
     * order to be {@code deviceId}.
     *
     * @throws ConfigurationException if the id does not have the shape this
     *         fleet's ids have — better than defaulting to zero, which would
     *         silently make every unparseable device into device 1
     */
    public static int offsetFor(String deviceId, String prefix) {
        String expected = prefix + "-";
        if (deviceId == null || !deviceId.startsWith(expected)) {
            throw new ConfigurationException("device id \"" + deviceId
                    + "\" does not start with \"" + expected + "\"; the operator cannot tell"
                    + " which slice of the fleet to give its replacement");
        }
        String digits = deviceId.substring(expected.length());
        int index;
        try {
            index = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new ConfigurationException("device id \"" + deviceId + "\" does not end in a"
                    + " number, so it has no index in the fleet", e);
        }
        if (index < 1) {
            throw new ConfigurationException("device indices are one-based; \"" + deviceId
                    + "\" claims index " + index);
        }
        return index - 1;
    }
}
