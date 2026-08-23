package io.fleet.common;

/**
 * A device's liveness signal, published on {@code fleet/{deviceId}/heartbeat}.
 *
 * <p>Deliberately tiny. A heartbeat carries no sensor data because its only
 * job is to prove the device is still executing, and because it is sent at
 * least as often as telemetry — anything carried here is paid for on every
 * tick by every device in the fleet.
 *
 * <p>The timestamp is the device's own clock and is recorded for diagnosis
 * only. Timeout detection uses the gateway's receipt time: a wedged device
 * may be wrong about the time, and a device whose clock jumps must not be
 * able to make itself look alive.
 *
 * @param deviceId  the device asserting liveness
 * @param timestamp device clock, epoch milliseconds
 */
public record Heartbeat(String deviceId, long timestamp) {
}
