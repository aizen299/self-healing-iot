package io.fleet.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning one pod's manifest into another pod's.
 *
 * <p>Covered incidentally through the controller before, which left the two
 * things this class is actually careful about untested: dropping the fields
 * that belong to the source object, and not depending on JSON field order.
 */
class ReplacementFactoryTest {

    private final ReplacementFactory factory = new ReplacementFactory();

    @Test
    @DisplayName("the offset is rewritten even when value precedes name")
    void doesNotDependOnFieldOrder() throws Exception {
        // The reason rewriteOffsetEntry buffers each entry instead of
        // streaming it: JSON does not promise field order, so whether this is
        // the entry to rewrite is not known until the object has been read.
        // Every other fixture writes name first, so without this test a
        // "simplification" back to a streaming rewrite would pass the whole
        // suite while giving replacements another device's data — the id and
        // the sensor seed both derive from the index.
        String manifest = factory.build("""
                {"metadata":{"name":"edge-device-001","labels":{"device-id":"device-001"}},
                 "spec":{"containers":[{"name":"device",
                   "env":[{"value":"0","name":"FLEET_DEVICE_INDEX_OFFSET"}]}]}}
                """, "device-004", 3, "abc123", "edge-device-004");

        assertTrue(manifest.contains("\"FLEET_DEVICE_INDEX_OFFSET\""), manifest);
        assertTrue(manifest.contains("\"value\":\"3\""), manifest);
        assertFalse(manifest.contains("\"value\":\"0\""),
                "the source's offset must not survive: " + manifest);
    }

    @Test
    @DisplayName("a container with no offset at all is given one")
    void addsTheOffsetWhenTheSourceHasNone() throws Exception {
        // Cloning a sibling that never set an offset would otherwise produce a
        // replacement running device 1 under the failed device's name.
        String manifest = factory.build("""
                {"metadata":{"name":"edge-device-001","labels":{"device-id":"device-001"}},
                 "spec":{"containers":[{"name":"device",
                   "env":[{"name":"FLEET_SINK","value":"mqtt"}]}]}}
                """, "device-003", 2, "abc123", null);

        assertTrue(manifest.contains("\"FLEET_DEVICE_INDEX_OFFSET\""), manifest);
        assertTrue(manifest.contains("\"value\":\"2\""), manifest);
        assertTrue(manifest.contains("\"FLEET_SINK\""), "other env must survive: " + manifest);
    }

    @Test
    @DisplayName("an env entry read from a ConfigMap survives intact")
    void preservesValueFromEntries() throws Exception {
        String manifest = factory.build("""
                {"metadata":{"name":"edge-device-001","labels":{"device-id":"device-001"}},
                 "spec":{"containers":[{"name":"device","env":[
                   {"name":"FLEET_PUBLISH_INTERVAL_MS","valueFrom":{"configMapKeyRef":
                     {"name":"fleet-config","key":"TICK_INTERVAL_MS"}}}]}]}}
                """, "device-002", 1, "abc123", null);

        assertTrue(manifest.contains("\"configMapKeyRef\""), manifest);
        assertTrue(manifest.contains("\"TICK_INTERVAL_MS\""), manifest);
    }

    @Test
    @DisplayName("the source object's own identity and history are dropped")
    void stripsWhatBelongsToTheSourcePod() throws Exception {
        // Sending these back asks the API server to recreate one specific
        // historical object, which it refuses.
        String manifest = factory.build("""
                {"metadata":{"name":"edge-device-002","uid":"u-1","resourceVersion":"4711",
                   "creationTimestamp":"2026-08-24T10:00:00Z","generation":3,
                   "managedFields":[{"manager":"kubectl"}],
                   "labels":{"app":"edge-device","device-id":"device-002"}},
                 "spec":{"nodeName":"fleet-control-plane","restartPolicy":"Never",
                   "containers":[{"name":"device","env":[]}]},
                 "status":{"phase":"Failed","podIP":"10.244.0.9"}}
                """, "device-002", 1, "abc123", "edge-device-002");

        assertFalse(manifest.contains("\"uid\""), manifest);
        assertFalse(manifest.contains("\"resourceVersion\""), manifest);
        assertFalse(manifest.contains("\"creationTimestamp\""), manifest);
        assertFalse(manifest.contains("\"managedFields\""), manifest);
        assertFalse(manifest.contains("\"status\""), manifest);
        assertFalse(manifest.contains("\"nodeName\""),
                "copying nodeName pins the replacement to the source's node and bypasses"
                        + " scheduling: " + manifest);

        // What must survive: the spec is the whole point of cloning.
        assertTrue(manifest.contains("\"restartPolicy\":\"Never\""), manifest);
    }

    @Test
    @DisplayName("the replacement takes the failed device's identity, not the source's")
    void rewritesIdentityWhenCloningASibling() throws Exception {
        String manifest = factory.build("""
                {"metadata":{"name":"edge-device-001",
                   "labels":{"app":"edge-device","device-id":"device-001",
                             "fleet-id":"fleet-local","recovery-id":"older","replaces":"gone"}},
                 "spec":{"containers":[{"name":"device","env":[]}]}}
                """, "device-002", 1, "new-id", "edge-device-002");

        assertTrue(manifest.contains("\"device-id\":\"device-002\""), manifest);
        assertFalse(manifest.contains("\"device-id\":\"device-001\""),
                "a clone must not claim to be the sibling it was copied from: " + manifest);
        assertTrue(manifest.contains("\"recovery-id\":\"new-id\""), manifest);
        assertFalse(manifest.contains("\"older\""),
                "the source's own recovery labels must not carry over: " + manifest);
        assertTrue(manifest.contains("\"replaces\":\"edge-device-002\""), manifest);
        assertTrue(manifest.contains("\"fleet-id\":\"fleet-local\""),
                "labels the operator does not own must survive: " + manifest);
        assertTrue(manifest.contains(RecoveryId.replacementPodName("device-002", "new-id")),
                manifest);
    }

    @Test
    @DisplayName("garbage in is refused, not half-copied")
    void refusesAManifestItCannotRead() {
        assertThrows(io.fleet.recovery.k8s.KubernetesException.class,
                () -> factory.build("not json", "device-002", 1, "abc", null));
        assertThrows(io.fleet.recovery.k8s.KubernetesException.class,
                () -> factory.build("[]", "device-002", 1, "abc", null));
    }
}
