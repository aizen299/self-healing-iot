package io.fleet.common;

/**
 * A persisted health transition.
 *
 * <p>Mirrors the event published on {@code fleet/{deviceId}/events} so the
 * stored history and the live stream describe the same thing. Kept in
 * {@code common} rather than in the gateway because Phase 6's stream
 * processor and any later analysis read the same shape.
 *
 * @param deviceId               device the transition concerns
 * @param event                  what happened
 * @param fromHealth             previous health
 * @param toHealth               new health
 * @param atMillis               when the gateway made the call
 * @param missedHeartbeats       intervals of silence at that moment; 0 when the
 *                               broker's Last Will triggered it rather than a timeout
 * @param recoveryDurationMillis detection-to-confirmation time, or -1
 */
public record DeviceEventRecord(
        String deviceId,
        DeviceEventType event,
        DeviceHealth fromHealth,
        DeviceHealth toHealth,
        long atMillis,
        int missedHeartbeats,
        long recoveryDurationMillis) {
}
