package io.fleet.recovery;

import io.fleet.common.ConfigurationException;
import io.fleet.common.DeviceEventRecord;
import io.fleet.recovery.k8s.KubernetesApi;
import io.fleet.recovery.k8s.KubernetesException;
import io.fleet.recovery.k8s.PodRef;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Decides what to do about a failure, and does it.
 *
 * <p>Separated from the Kafka consumer that feeds it so the decision can be
 * driven directly in tests — the polling loop is plumbing, this is the part
 * with judgement in it.
 *
 * <p>Recovery closes the loop the whole project is built around:
 * {@code device → telemetry → monitoring → failure detected → recovery event
 * → controller → replacement workload → healthy fleet restored}. This class
 * is the fourth arrow.
 */
public final class RecoveryController {

    private final KubernetesApi kubernetes;
    private final ReplacementFactory replacements;
    private final OperatorConfig config;
    private final Clock clock;

    /**
     * Every recovery this operator has performed, keyed by recovery id.
     *
     * <p>Explicit state, as the design requires — but deliberately *not* what
     * makes recovery idempotent. That guarantee is the deterministic
     * replacement name and the API server's refusal to create it twice, which
     * survives this process dying; a ledger alone would forget. This exists so
     * the operator can report what it did, and to save an API round trip on a
     * duplicate it happens to still remember.
     *
     * <p>By recovery id rather than by device, so a device recovered more than
     * once keeps both. Keyed by device it silently dropped all but the latest,
     * which is exactly the case repeated-failure experiments produce most of.
     */
    private final Map<String, Recovery> ledger = new ConcurrentHashMap<>();

    /**
     * The last recovery per device that actually created a replacement.
     *
     * <p>Only replacements, which is the whole point: keyed on the last
     * *decision* it is overwritten by the NOT_NEEDED that the guard below
     * produces, so the third failure of a startup storm finds no replacement
     * recorded and creates another pod. The guard has to remember what is
     * running, not what was last concluded.
     */
    private final Map<String, Recovery> lastReplacement = new ConcurrentHashMap<>();
    private final LongAdder replacementDurationTotalMillis = new LongAdder();
    private final LongAdder replacementDurationSamples = new LongAdder();

    private final LongAdder replaced = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder notNeeded = new LongAdder();
    private final LongAdder failed = new LongAdder();

    public RecoveryController(KubernetesApi kubernetes, ReplacementFactory replacements,
            OperatorConfig config, Clock clock) {
        this.kubernetes = kubernetes;
        this.replacements = replacements;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Handles one failure event.
     *
     * <p>Never throws. A single unrecoverable failure — a device whose name
     * cannot be parsed, a cluster that refuses — must not stop the operator
     * consuming the next event, or one bad message would end recovery for the
     * whole fleet.
     */
    public Recovery onFailure(DeviceEventRecord event) {
        String deviceId = event.deviceId();
        String recoveryId = RecoveryId.of(deviceId, event.atMillis());
        String podName = RecoveryId.replacementPodName(deviceId, recoveryId);

        // Cheap duplicate check, on this exact recovery. The authoritative one
        // is the create below.
        Recovery known = ledger.get(recoveryId);
        if (known != null && known.outcome() != RecoveryOutcome.FAILED) {
            duplicates.increment();
            // Said out loud. Returning silently makes a duplicate that was
            // correctly declined look exactly like an event that never
            // arrived, which is indistinguishable from the operator being
            // broken — and the difference matters most when someone is
            // checking whether recovery is idempotent.
            System.out.println("ignoring a repeat of recovery " + recoveryId + " for "
                    + deviceId + "; already handled as " + known.pod());
            return record(new Recovery(deviceId, recoveryId, known.pod(),
                    event.atMillis(), now(), RecoveryOutcome.ALREADY_RECOVERED));
        }

        try {
            return act(event, deviceId, recoveryId, podName);
        } catch (KubernetesException e) {
            failed.increment();
            System.err.println("recovery of " + deviceId + " (" + recoveryId + ") failed: "
                    + e.getMessage());
            return record(new Recovery(deviceId, recoveryId, podName,
                    event.atMillis(), now(), RecoveryOutcome.FAILED));
        } catch (ConfigurationException e) {
            // Separated from the catch below because this one is explicable:
            // the operator knows exactly what is wrong and saying "unexpected
            // error" about a device id it cannot parse sends whoever reads the
            // log looking for a bug instead of a misconfiguration.
            failed.increment();
            System.err.println("cannot recover " + deviceId + ": " + e.getMessage());
            return record(new Recovery(deviceId, recoveryId, podName,
                    event.atMillis(), now(), RecoveryOutcome.FAILED));
        } catch (RuntimeException e) {
            failed.increment();
            System.err.println("recovery of " + deviceId + " (" + recoveryId
                    + ") hit an unexpected error: " + e);
            return record(new Recovery(deviceId, recoveryId, podName,
                    event.atMillis(), now(), RecoveryOutcome.FAILED));
        }
    }

    private Recovery act(DeviceEventRecord event, String deviceId, String recoveryId,
            String podName) throws KubernetesException {

        List<PodRef> fleet = kubernetes.listPods("app", config.deviceAppLabel());
        List<PodRef> forThisDevice = fleet.stream()
                .filter(pod -> deviceId.equals(pod.label("device-id")))
                .toList();

        // Already replaced for this exact failure, by an earlier delivery or by
        // an earlier life of this process.
        if (forThisDevice.stream().anyMatch(pod -> recoveryId.equals(pod.label("recovery-id")))) {
            duplicates.increment();
            return record(new Recovery(deviceId, recoveryId, podName,
                    event.atMillis(), now(), RecoveryOutcome.ALREADY_RECOVERED));
        }

        // Already answered by a replacement this operator made *after* the
        // failure was detected.
        //
        // This is the storm guard, and it is deliberately separate from the
        // liveness check below. A replacement waits for the broker and then
        // boots a JVM, and the gateway keeps declaring the device offline
        // throughout — genuinely distinct failure events with distinct
        // recovery ids, which the deterministic-name guard cannot catch. Any
        // failure detected before the current replacement was created is a
        // report of the outage that replacement already answers.
        //
        // Checked before replaceLiveDevices is consulted, because that flag
        // exists to say "a Running pod does not block replacement" — not
        // "ignore a recovery already in progress". Conflating the two turns
        // the flag into a churn loop where every event kills the pod that was
        // about to end the outage.
        Recovery inFlight = lastReplacement.get(deviceId);
        if (inFlight != null && inFlight.actedAtMillis() >= event.atMillis()
                && forThisDevice.stream()
                        .anyMatch(pod -> pod.name().equals(inFlight.pod()) && pod.isAlive())) {
            notNeeded.increment();
            System.out.println("no recovery needed for " + deviceId + ": " + inFlight.pod()
                    + " already replaces it and is " + inFlight.recoveryId());
            return record(new Recovery(deviceId, recoveryId, inFlight.pod(),
                    event.atMillis(), now(), RecoveryOutcome.NOT_NEEDED));
        }

        // A failure event says what the gateway saw when it fired, not what is
        // true now. A device that came back on its own between the event being
        // written and this operator reading it is healthy, and replacing it
        // would kill a working device to cure a fault that has passed.
        Optional<PodRef> alive = forThisDevice.stream().filter(PodRef::isAlive).findFirst();
        if (alive.isPresent() && !config.replaceLiveDevices()) {
            notNeeded.increment();
            System.out.println("no recovery needed for " + deviceId + ": " + alive.get().name()
                    + " is " + alive.get().phase());
            return record(new Recovery(deviceId, recoveryId, alive.get().name(),
                    event.atMillis(), now(), RecoveryOutcome.NOT_NEEDED));
        }

        String source = sourceManifest(forThisDevice, fleet, deviceId);
        String replaced0 = forThisDevice.isEmpty() ? null : forThisDevice.get(0).name();

        String manifest = replacements.build(source, deviceId,
                DeviceIndex.offsetFor(deviceId, config.deviceIdPrefix()), recoveryId, replaced0);

        // Create first, delete after.
        //
        // The other order is destructive: a create that fails once the stale
        // pods are gone leaves nothing to clone on the retry, and when the
        // whole fleet is down — which is when recovery matters most — there is
        // no sibling either, so the device becomes permanently unrecoverable
        // by the operator's own hand. This way a failed create leaves the
        // cluster exactly as it found it.
        //
        // Nothing collides: the replacement's name is derived from the
        // recovery id, so it never clashes with the pod it replaces.
        boolean created = kubernetes.createPod(manifest);
        if (created) {
            replaced.increment();
            System.out.println("recovered " + deviceId + " as " + podName
                    + " (recovery " + recoveryId + ")");
        } else {
            duplicates.increment();
            System.out.println("recovery " + recoveryId + " for " + deviceId
                    + " already existed; nothing created");
        }

        // Only now. The stopped pod still holds its name and would otherwise
        // keep showing up as this device in `kubectl get pods`.
        for (PodRef stale : forThisDevice) {
            kubernetes.deletePod(stale.name(), true);
        }
        return record(new Recovery(deviceId, recoveryId, podName, event.atMillis(), now(),
                created ? RecoveryOutcome.REPLACED : RecoveryOutcome.ALREADY_RECOVERED));
    }

    /**
     * A pod to clone.
     *
     * <p>The failed device's own pod first: a crashed pod with {@code
     * restartPolicy: Never} still exists in Failed phase, and it is the exact
     * shape the replacement should have. If it was force-deleted there is
     * nothing left of it, so any healthy sibling does — every device pod in
     * the fleet is the same but for its offset and label.
     *
     * <p>No API call: the list that found these pods already carried their
     * manifests.
     */
    private String sourceManifest(List<PodRef> forThisDevice, List<PodRef> fleet,
            String deviceId) throws KubernetesException {
        for (PodRef own : forThisDevice) {
            if (own.manifest() != null) {
                return own.manifest();
            }
        }
        for (PodRef sibling : fleet) {
            if (!deviceId.equals(sibling.label("device-id")) && sibling.isAlive()
                    && sibling.manifest() != null) {
                return sibling.manifest();
            }
        }
        // Reported rather than guessed at. Inventing a pod spec here would
        // produce a replacement that silently differs from the fleet, and a
        // fleet with nothing left to clone is a situation an operator needs to
        // be told about rather than have papered over.
        throw new KubernetesException("nothing to clone for " + deviceId
                + ": its own pod is gone and no sibling device pod is running");
    }

    private Recovery record(Recovery recovery) {
        ledger.put(recovery.recoveryId(), recovery);
        if (recovery.outcome() == RecoveryOutcome.REPLACED) {
            lastReplacement.put(recovery.deviceId(), recovery);
        }
        // Only the ones that measured something. An outcome that replaced
        // nothing spans a failure this recovery did not answer, and a negative
        // result means the gateway's clock and this one disagree — the same
        // test the publisher applies before writing the field at all.
        if (recovery.hasMeaningfulDuration()) {
            replacementDurationTotalMillis.add(recovery.durationMillis());
            replacementDurationSamples.increment();
        }
        return recovery;
    }

    private long now() {
        return clock.millis();
    }

    /** Every recovery this operator has performed, keyed by recovery id. */
    public Map<String, Recovery> ledger() {
        return Map.copyOf(ledger);
    }

    public long replacedCount() {
        return replaced.sum();
    }

    /** Redelivered or already-handled failures that created nothing. */
    public long duplicateCount() {
        return duplicates.sum();
    }

    public long notNeededCount() {
        return notNeeded.sum();
    }

    public long failedCount() {
        return failed.sum();
    }

    /**
     * Total detection-to-replacement time, over the recoveries that measured
     * one.
     *
     * <p><b>Not MTTR and never to be added to it.</b> This ends when the API
     * server accepts the pod; the gateway's recoveryDurationMillis starts at
     * the same instant, ends when heartbeats are confirmed, and already
     * contains this. MTTR is the gateway's number.
     */
    public long replacementDurationTotalMillis() {
        return replacementDurationTotalMillis.sum();
    }

    /** How many replacements contributed a duration, which is not all of them. */
    public long replacementDurationSampleCount() {
        return replacementDurationSamples.sum();
    }
}
