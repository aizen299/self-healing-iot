package io.fleet.recovery;

import io.fleet.common.DeviceEventRecord;
import io.fleet.common.DeviceEventType;
import io.fleet.common.DeviceHealth;
import io.fleet.recovery.k8s.KubernetesException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the operator does about a failure.
 *
 * <p>Driven against a fake cluster rather than a real one: the decision is
 * the part with judgement in it, and a real API server would test Kubernetes.
 * The fake does enforce pod-name uniqueness, because that is the mechanism
 * idempotency actually rests on.
 */
class RecoveryControllerTest {

    private static final long DETECTED_AT = 1_787_500_000_000L;

    private FakeKubernetesApi cluster;
    private RecoveryController controller;

    @BeforeEach
    void setUp() {
        cluster = new FakeKubernetesApi();
        controller = new RecoveryController(cluster, new ReplacementFactory(),
                OperatorConfig.from(Map.of()),
                Clock.fixed(Instant.ofEpochMilli(DETECTED_AT + 900), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a failed device is replaced by a pod carrying its identity")
    void replacesAFailedDevice() {
        cluster.addPod("edge-device-002", "Failed", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));

        Recovery recovery = controller.onFailure(failureOf("device-002"));

        assertEquals(RecoveryOutcome.REPLACED, recovery.outcome());
        assertEquals(1, controller.replacedCount());

        // The stopped pod is removed: it still held the name and would keep
        // showing up as this device in the fleet.
        assertTrue(cluster.deleted.contains("edge-device-002"));

        var replacement = cluster.pod(recovery.replacementPod());
        assertEquals("device-002", replacement.label("device-id"));
        assertEquals(recovery.recoveryId(), replacement.label("recovery-id"),
                "a replacement must be traceable to the failure that caused it");
        assertEquals("edge-device", replacement.label("app"));
        assertEquals("fleet-local", replacement.label("fleet-id"),
                "labels the operator does not own must survive the clone");
    }

    @Test
    @DisplayName("the same failure event twice creates one replacement")
    void isIdempotentAcrossRedelivery() {
        cluster.addPod("edge-device-002", "Failed", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));

        DeviceEventRecord event = failureOf("device-002");
        Recovery first = controller.onFailure(event);
        Recovery second = controller.onFailure(event);

        assertEquals(RecoveryOutcome.REPLACED, first.outcome());
        assertEquals(RecoveryOutcome.ALREADY_RECOVERED, second.outcome(),
                "Kafka redelivers at least once; a second delivery must create nothing");
        assertEquals(first.replacementPod(), second.replacementPod());
        assertEquals(1, controller.replacedCount());
        assertEquals(1, controller.duplicateCount());
        assertEquals(1, cluster.podCount(), "the fleet must not have grown a second device-002");
    }

    @Test
    @DisplayName("idempotency survives the operator forgetting everything")
    void isIdempotentAcrossAnOperatorRestart() {
        cluster.addPod("edge-device-002", "Failed", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));
        DeviceEventRecord event = failureOf("device-002");

        Recovery first = controller.onFailure(event);

        // A brand-new controller against the same cluster: the in-memory
        // ledger is gone, exactly as it would be after a crash. Kafka will
        // redeliver an uncommitted offset, so this is the ordinary case, not
        // an exotic one.
        RecoveryController restarted = new RecoveryController(cluster,
                new ReplacementFactory(), OperatorConfig.from(Map.of()),
                Clock.fixed(Instant.ofEpochMilli(DETECTED_AT + 5_000), ZoneOffset.UTC));
        Recovery afterRestart = restarted.onFailure(event);

        assertEquals(RecoveryOutcome.ALREADY_RECOVERED, afterRestart.outcome());
        assertEquals(first.replacementPod(), afterRestart.replacementPod());
        assertEquals(0, restarted.replacedCount(),
                "the guarantee must come from the cluster, not from what the operator remembers");
        assertEquals(1, cluster.podCount());
    }

    @Test
    @DisplayName("a replacement that fails in turn gets its own recovery")
    void distinctFailuresAreNotConfusedForDuplicates() {
        cluster.addPod("edge-device-002", "Failed", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));

        Recovery first = controller.onFailure(failureOf("device-002", DETECTED_AT));

        // The replacement dies too. That is a new failure and needs a new
        // recovery — deduplicating on device id alone would leave the fleet
        // permanently one device short.
        String replacementPod = first.replacementPod();
        cluster.addPod(replacementPod, "Failed",
                Map.of("app", "edge-device", "device-id", "device-002",
                        "fleet-id", "fleet-local", "recovery-id", first.recoveryId()),
                podManifest(replacementPod, "device-002", "1"));

        Recovery second = controller.onFailure(failureOf("device-002", DETECTED_AT + 60_000));

        assertNotEquals(first.recoveryId(), second.recoveryId());
        assertEquals(RecoveryOutcome.REPLACED, second.outcome());
        assertEquals(2, controller.replacedCount());
    }

    @Test
    @DisplayName("a second failure while the replacement is still starting creates nothing")
    void doesNotStormWhileAReplacementIsComingUp() {
        // The window that makes this necessary is real and not small: a
        // replacement waits for the broker, then boots a JVM, and until it
        // heartbeats the gateway keeps seeing silence. Those are genuinely
        // distinct failure events with distinct recovery ids, so the
        // deterministic-name guard does not catch them — without the liveness
        // check, every one would add another pod to a device that already has
        // one on the way up.
        cluster.addPod("edge-device-002", "Failed", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));

        Recovery first = controller.onFailure(failureOf("device-002", DETECTED_AT));
        assertEquals(RecoveryOutcome.REPLACED, first.outcome());

        Recovery duringStartup =
                controller.onFailure(failureOf("device-002", DETECTED_AT + 3_000));
        Recovery stillStarting =
                controller.onFailure(failureOf("device-002", DETECTED_AT + 6_000));

        assertEquals(RecoveryOutcome.NOT_NEEDED, duringStartup.outcome());
        assertEquals(RecoveryOutcome.NOT_NEEDED, stillStarting.outcome());
        assertEquals(1, controller.replacedCount());
        assertEquals(1, cluster.podCount(),
                "device-002 must end up with exactly one pod: " + cluster.podNames());
    }

    @Test
    @DisplayName("a device that came back on its own is left alone")
    void doesNotReplaceALiveDevice() {
        cluster.addPod("edge-device-002", "Running", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));

        Recovery recovery = controller.onFailure(failureOf("device-002"));

        assertEquals(RecoveryOutcome.NOT_NEEDED, recovery.outcome());
        assertEquals(0, controller.replacedCount());
        assertTrue(cluster.deleted.isEmpty(),
                "a failure event describes what was true when it fired; killing a device that"
                        + " has since recovered would turn a stale event into a real outage");
    }

    @Test
    @DisplayName("a force-deleted device is rebuilt from a sibling")
    void clonesASiblingWhenTheFailedPodIsGone() {
        // Nothing left of device-002 — force deletion leaves no object to read.
        cluster.addPod("edge-device-001", "Running", deviceLabels("device-001"),
                podManifest("edge-device-001", "device-001", "0"));

        Recovery recovery = controller.onFailure(failureOf("device-002"));

        assertEquals(RecoveryOutcome.REPLACED, recovery.outcome());
        var replacement = cluster.pod(recovery.replacementPod());
        assertEquals("device-002", replacement.label("device-id"),
                "cloning a sibling must not make the replacement claim to be that sibling");
    }

    @Test
    @DisplayName("the replacement is given its own slice of the fleet")
    void replacementRunsTheRightDeviceIndex() throws Exception {
        cluster.addPod("edge-device-001", "Running", deviceLabels("device-001"),
                podManifest("edge-device-001", "device-001", "0"));

        Recovery recovery = controller.onFailure(failureOf("device-003"));

        // device-003 is the third device, so offset 2. Getting this wrong
        // gives a replacement the right name and another device's data,
        // because the id and the sensor seed both derive from the index.
        String manifest = cluster.readPod(recovery.replacementPod());
        assertTrue(manifest.contains("\"FLEET_DEVICE_INDEX_OFFSET\""), manifest);
        assertTrue(manifest.contains("\"value\":\"2\""), manifest);
        assertFalse(manifest.contains("\"value\":\"0\""),
                "the cloned sibling's offset must not survive: " + manifest);
    }

    @Test
    @DisplayName("a cluster that refuses is recorded, not thrown")
    void aRefusedRecoveryIsCountedAndReported() {
        cluster.addPod("edge-device-002", "Failed", deviceLabels("device-002"),
                podManifest("edge-device-002", "device-002", "1"));
        cluster.nextFailure = new KubernetesException("forbidden: pods is forbidden");

        Recovery recovery = controller.onFailure(failureOf("device-002"));

        assertEquals(RecoveryOutcome.FAILED, recovery.outcome());
        assertEquals(1, controller.failedCount());
        // The next event must still be handled: one refusal cannot end
        // recovery for the whole fleet.
        Recovery next = controller.onFailure(failureOf("device-002"));
        assertEquals(RecoveryOutcome.REPLACED, next.outcome());
    }

    @Test
    @DisplayName("a device with nothing to clone reports rather than inventing a pod")
    void refusesToGuessAPodSpec() {
        Recovery recovery = controller.onFailure(failureOf("device-002"));

        assertEquals(RecoveryOutcome.FAILED, recovery.outcome());
        assertEquals(0, cluster.podCount(),
                "inventing a spec would produce a replacement that silently differs"
                        + " from the fleet");
    }

    @Test
    @DisplayName("an unparseable device id fails that recovery, not the operator")
    void anUnknownDeviceIdIsNotFatal() {
        cluster.addPod("edge-device-001", "Running", deviceLabels("device-001"),
                podManifest("edge-device-001", "device-001", "0"));

        Recovery recovery = controller.onFailure(failureOf("sensor-xyz"));

        assertEquals(RecoveryOutcome.FAILED, recovery.outcome());
        assertEquals(1, controller.failedCount());
    }

    private static Map<String, String> deviceLabels(String deviceId) {
        return Map.of("app", "edge-device", "device-id", deviceId, "fleet-id", "fleet-local");
    }

    private static DeviceEventRecord failureOf(String deviceId) {
        return failureOf(deviceId, DETECTED_AT);
    }

    private static DeviceEventRecord failureOf(String deviceId, long at) {
        return new DeviceEventRecord(deviceId, DeviceEventType.DEVICE_OFFLINE,
                DeviceHealth.SUSPECTED, DeviceHealth.OFFLINE, at, 4, -1L);
    }

    /** A pod manifest shaped like the ones base/40-devices.yaml produces. */
    private static String podManifest(String name, String deviceId, String offset) {
        return """
                {"apiVersion":"v1","kind":"Pod",
                 "metadata":{"name":"%s","namespace":"fleet","uid":"abc-123",
                   "resourceVersion":"4711","creationTimestamp":"2026-08-24T10:00:00Z",
                   "labels":{"app":"edge-device","device-id":"%s","fleet-id":"fleet-local"}},
                 "spec":{"restartPolicy":"Never","nodeName":"fleet-control-plane",
                   "terminationGracePeriodSeconds":30,
                   "containers":[{"name":"device","image":"fleet/edge-device:0.1.0",
                     "env":[{"name":"FLEET_SINK","value":"mqtt"},
                            {"name":"FLEET_DEVICE_INDEX_OFFSET","value":"%s"},
                            {"name":"FLEET_PUBLISH_INTERVAL_MS",
                             "valueFrom":{"configMapKeyRef":{"name":"fleet-config",
                                          "key":"TICK_INTERVAL_MS"}}}],
                     "resources":{"limits":{"memory":"192Mi"}}}]},
                 "status":{"phase":"Failed","podIP":"10.244.0.9"}}
                """.formatted(name, deviceId, offset);
    }
}
