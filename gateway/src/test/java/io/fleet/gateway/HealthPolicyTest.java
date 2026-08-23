package io.fleet.gateway;

import io.fleet.common.ConfigurationException;
import io.fleet.common.DeviceHealth;
import io.fleet.common.Presence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detection rules, driven directly rather than through elapsed time.
 *
 * <p>This is the logic the whole self-healing story rests on: everything
 * downstream reacts to the OFFLINE call made here, so a wrong transition is a
 * wrong recovery.
 */
class HealthPolicyTest {

    /** Suspect at 2 misses, fail at 4, trust again after 2 heartbeats. */
    private final HealthPolicy policy = new HealthPolicy(1_000L, 2, 4, 2);

    @Test
    @DisplayName("a single missed heartbeat never condemns a device")
    void oneMissIsToleratedByConstruction() {
        assertEquals(DeviceHealth.ONLINE, policy.afterSilence(DeviceHealth.ONLINE, 0));
        assertEquals(DeviceHealth.ONLINE, policy.afterSilence(DeviceHealth.ONLINE, 1));

        // The rule is enforced at construction, not just by the default value:
        // heartbeats travel at QoS 0, so one loss is expected traffic.
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> new HealthPolicy(1_000L, 1, 4, 2));
        assertTrue(error.getMessage().contains("single lost heartbeat"), error.getMessage());
    }

    @Test
    void walksOnlineToSuspectedToOffline() {
        assertEquals(DeviceHealth.SUSPECTED, policy.afterSilence(DeviceHealth.ONLINE, 2));
        assertEquals(DeviceHealth.SUSPECTED, policy.afterSilence(DeviceHealth.SUSPECTED, 3));
        assertEquals(DeviceHealth.OFFLINE, policy.afterSilence(DeviceHealth.SUSPECTED, 4));
    }

    @Test
    @DisplayName("a device that was only late returns straight to service")
    void suspicionIsClearedWithoutProbation() {
        // It was never actually broken, so making it serve probation would
        // delay a device that is already healthy.
        assertEquals(DeviceHealth.ONLINE, policy.afterHeartbeat(DeviceHealth.SUSPECTED, 1));
    }

    @Test
    void aFailedDeviceMustServeProbationBeforeItIsTrusted() {
        assertEquals(DeviceHealth.RECOVERING, policy.afterHeartbeat(DeviceHealth.OFFLINE, 1));
        assertEquals(DeviceHealth.RECOVERING, policy.afterHeartbeat(DeviceHealth.RECOVERING, 1));
        assertEquals(DeviceHealth.ONLINE, policy.afterHeartbeat(DeviceHealth.RECOVERING, 2));
    }

    @Test
    @DisplayName("recoveryConfirmations actually changes how long probation lasts")
    void confirmationCountIsHonoured() {
        // With 1 the value used to behave identically to 2, so configuring it
        // did nothing and reported no error.
        HealthPolicy immediate = new HealthPolicy(1_000L, 2, 4, 1);
        assertEquals(DeviceHealth.ONLINE, immediate.afterHeartbeat(DeviceHealth.OFFLINE, 1));

        HealthPolicy patient = new HealthPolicy(1_000L, 2, 4, 3);
        assertEquals(DeviceHealth.RECOVERING, patient.afterHeartbeat(DeviceHealth.OFFLINE, 1));
        assertEquals(DeviceHealth.RECOVERING, patient.afterHeartbeat(DeviceHealth.RECOVERING, 2));
        assertEquals(DeviceHealth.ONLINE, patient.afterHeartbeat(DeviceHealth.RECOVERING, 3));
    }

    @Test
    @DisplayName("the Last Will condemns a known device but never a ghost")
    void presenceDrivesTheFastPath() {
        assertEquals(DeviceHealth.OFFLINE,
                policy.afterPresence(DeviceHealth.ONLINE, Presence.OFFLINE));
        assertEquals(DeviceHealth.OFFLINE,
                policy.afterPresence(DeviceHealth.SUSPECTED, Presence.OFFLINE));
        assertEquals(DeviceHealth.UNKNOWN,
                policy.afterPresence(DeviceHealth.UNKNOWN, Presence.OFFLINE));

        // Connecting proves nothing about liveness; only a heartbeat does.
        assertEquals(DeviceHealth.SUSPECTED,
                policy.afterPresence(DeviceHealth.SUSPECTED, Presence.ONLINE));

        // Leaving on purpose retires the device rather than failing it.
        assertEquals(DeviceHealth.UNKNOWN,
                policy.afterPresence(DeviceHealth.ONLINE, Presence.SHUTDOWN));
    }

    @Test
    void missCountIsClampedRatherThanOverflowing() {
        HealthPolicy fine = new HealthPolicy(1L, 2, 4, 2);
        // An unchecked cast here goes negative and fails every threshold, so
        // the longest-silent device would be the one never declared failed.
        assertTrue(fine.missedHeartbeats(1L, Long.MAX_VALUE) > 0);
    }

    @Test
    void aRecoveringDeviceThatGoesQuietAgainFailsAgain() {
        assertEquals(DeviceHealth.RECOVERING, policy.afterSilence(DeviceHealth.RECOVERING, 3));
        assertEquals(DeviceHealth.OFFLINE, policy.afterSilence(DeviceHealth.RECOVERING, 4));
    }

    @Test
    @DisplayName("silence from a device never heard from is not a failure")
    void unknownDevicesAreNeverDeclaredFailed() {
        // Otherwise a retained-presence ghost (ADR-004) would be declared down
        // and, from Phase 9, recovered — provisioning a replacement for a
        // device that does not exist.
        assertEquals(DeviceHealth.UNKNOWN, policy.afterSilence(DeviceHealth.UNKNOWN, 0));
        assertEquals(DeviceHealth.UNKNOWN, policy.afterSilence(DeviceHealth.UNKNOWN, 1_000));
    }

    @Test
    void theFirstHeartbeatBringsADeviceIntoService() {
        assertEquals(DeviceHealth.ONLINE, policy.afterHeartbeat(DeviceHealth.UNKNOWN, 1));
    }

    @Test
    void offlineStaysOfflineWhileSilent() {
        assertEquals(DeviceHealth.OFFLINE, policy.afterSilence(DeviceHealth.OFFLINE, 99));
    }

    @Test
    void missesAreCountedFromReceiptTime() {
        assertEquals(0, policy.missedHeartbeats(10_000L, 10_500L));
        assertEquals(1, policy.missedHeartbeats(10_000L, 11_000L));
        assertEquals(4, policy.missedHeartbeats(10_000L, 14_999L));

        // A device that has never reported has missed nothing; it is UNKNOWN,
        // not late.
        assertEquals(0, policy.missedHeartbeats(0L, 999_999L));

        // A clock that jumps backwards must not manufacture misses.
        assertEquals(0, policy.missedHeartbeats(10_000L, 9_000L));
    }

    @Test
    void rejectsThresholdsThatWouldMakeSuspicionUnreachable() {
        ConfigurationException error = assertThrows(ConfigurationException.class,
                () -> new HealthPolicy(1_000L, 4, 4, 2));
        assertTrue(error.getMessage().contains("must exceed"), error.getMessage());
    }

    @Test
    void rejectsNonsenseTimings() {
        assertThrows(ConfigurationException.class, () -> new HealthPolicy(0L, 2, 4, 2));
        assertThrows(ConfigurationException.class, () -> new HealthPolicy(1_000L, 2, 4, 0));
    }
}
