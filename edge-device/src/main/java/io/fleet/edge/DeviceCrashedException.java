package io.fleet.edge;

/**
 * Signals a simulated abrupt device death.
 *
 * <p>Unchecked on purpose. A real device that loses power does not give its
 * caller a chance to handle anything, and modelling that as a checked
 * exception would invite callers to write recovery code that has no
 * real-world counterpart. The harness catches it at the fleet boundary and
 * records the device as dead.
 */
public final class DeviceCrashedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String deviceId;

    public DeviceCrashedException(String deviceId, long afterReadings) {
        super("device " + deviceId + " crashed after " + afterReadings + " readings");
        this.deviceId = deviceId;
    }

    public String deviceId() {
        return deviceId;
    }
}
