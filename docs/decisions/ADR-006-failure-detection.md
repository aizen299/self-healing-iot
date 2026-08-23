# ADR-006: Two detection paths, and what counts as proof of life

## Status
Accepted — 2026-08-23

## Context
Phase 4 has to answer one question: when is a device broken? Everything
downstream — the failure event, the recovery in Phase 9, the MTTR figure
that is Pillar B — inherits whatever this decides. A wrong call here is a
wrong recovery, and a missed call is a fleet that quietly shrinks.

Phase 2 already gave the system a failure signal: the broker publishes a
device's Last Will when its connection drops ungracefully. That signal is
excellent — immediate, produced by infrastructure rather than by the
failing device itself, and impossible for a dead process to suppress.

It is also incomplete, and the gap is the reason this phase exists.

## Decision

### Two paths, because neither covers the other

| Failure | Connection | Last Will | Heartbeat timeout |
|---|---|---|---|
| Power loss, kill, network cut | drops | **fires** | would also fire, later |
| Wedged process, blocked loop, starved thread | **stays up** | never fires | **fires** |

A device whose liveness path has wedged is still connected, so the broker
has nothing to report. If detection relied only on the will, that device
would be considered healthy forever.

Both paths are kept and **both drive health**. The will gives immediate
detection of the common case — a device declared failed on receipt, with
`missedHeartbeats: 0` in the event as the signature. The timeout gives
coverage of the case the will structurally cannot see. `HEARTBEAT_STOP`
exists precisely to exercise the second in isolation.

### Which required splitting OFFLINE from SHUTDOWN

Phase 2 had a device publish retained `OFFLINE` on clean shutdown *and*
register `OFFLINE` as its will, which made the two indistinguishable to a
subscriber. That was harmless while presence was only recorded; the moment
it drives failure detection it is not, because every orderly fleet stop
would look like a fleet-wide failure — and from Phase 9, would provision
replacements for devices deliberately stopped.

So a device now publishes `SHUTDOWN` before a clean DISCONNECT, and only
the broker publishes `OFFLINE`. The gateway treats them oppositely:
`OFFLINE` declares a failure, `SHUTDOWN` returns the device to `UNKNOWN`
and clears its heartbeat history so a later sweep cannot resurrect it as a
failure either.

The ghost rule survives both: an `OFFLINE` arriving for a device in
`UNKNOWN` leaves it there. A retained will from an earlier run proves a
device existed once, not that it just died.

### Telemetry does not count as proof of life

A device under `HEARTBEAT_STOP` keeps publishing readings at full rate.
Treating any traffic as liveness would make the fault undetectable — and it
is the fault most likely to occur in a real system, since a partially
wedged process is far more common than a cleanly dead one.

Only a heartbeat proves the device is executing the path that says "I am
alive". This is verified: the integration test asserts a device is declared
OFFLINE while its telemetry counter keeps climbing.

### Heartbeats stay QoS 0, deliberately

The gateway subscribes at QoS 1 but devices publish heartbeats at QoS 0,
and MQTT delivers at the lower of the two — so liveness is best-effort and
raising the subscription QoS cannot change that. This is not an oversight
to correct: heartbeats are the highest-volume message in the fleet, and
`SUSPECTED` exists exactly so that best-effort delivery is safe. Raising
the publisher to QoS 1 would buy reliability the detector is designed not
to need, at a cost paid on every tick by every device.

### Receipt time, never the device's clock

The heartbeat carries the device's timestamp for diagnosis, but timeouts
are measured against the gateway's receipt time. A wedged device may be
wrong about the time, and a device whose clock jumps forward must not be
able to buy itself a reprieve.

### SUSPECTED, because one miss is not evidence

Heartbeats travel at QoS 0 (ADR-004), so a lost message is expected traffic
rather than a symptom. A detector that condemned on a single miss would
spend the run recovering healthy devices, and in Phase 9 that means
provisioning replacements for devices that never failed.

The default is suspect at 2 misses, fail at 4. The floor is enforced in
code, not just chosen as a default: a policy configured with
`suspectAfterMisses = 1` is rejected at startup.

`SUSPECTED` is deliberately **not** published as an event. It is the
detector hedging, and announcing it would invite consumers to react to what
is explicitly not yet a failure.

### RECOVERING is probation, not "being recovered"

The name is borrowed from the design's state machine, but the meaning here
is narrower: a failed device that has started heartbeating again, and has
not yet proved it will keep doing so. It returns to `ONLINE` only after
`recoveryConfirmations` consecutive heartbeats.

This matters more in Phase 9 than now. A replacement workload enters the
fleet by connecting and heartbeating, so it passes through exactly this
path — meaning one rule covers both a device that healed itself and one
that was replaced, and a flapping device cannot be repeatedly declared
recovered.

A device that was merely `SUSPECTED` skips probation entirely: it was never
broken, so making it wait would delay a device that is already healthy.

### A device never heard from is never declared failed

Silence from `UNKNOWN` is not a failure. This is what keeps the
retained-presence ghosts of ADR-004 out of the failure path: a retained
`OFFLINE` proves a device existed once, and declaring it down would have
Phase 9 provisioning a replacement for something that does not exist. The
demonstration run shows eight such ghosts sitting in `UNKNOWN` while three
real devices are correctly declared `OFFLINE`.

## Consequences
- Positive: the two detection paths together cover both clean death and
  partial wedging, and the test suite distinguishes them — including a test
  that a fired will condemns a known device while leaving a ghost alone.
- Positive: detection is a pure function of (state, misses, confirmations),
  so every transition is tested directly rather than by waiting on real
  time. The full failure-and-recovery cycle is covered in milliseconds.
- Positive: `offlineSinceMillis` is carried on the transition, so recovery
  duration is measurable at the moment it becomes meaningful. Pillar B has
  its raw material before Phase 9 needs it.
- Negative: the gateway's expected heartbeat interval must match the
  fleet's publish interval, because heartbeats ride the telemetry tick.
  Nothing enforces that across the two processes, and a mismatch shows up
  as either false failures or slow detection. Documented in both READMEs;
  a shared configuration source would be the real fix.
- Negative: the reported duration is detection-to-confirmation, not
  fault-to-recovery. The gateway cannot know when a device actually broke.
  Any MTTR figure derived from this must say which interval it is.
- Revisit if: heartbeats need their own cadence, independent of telemetry.
  That would need a second schedule per device, and the concurrency it
  introduces would put a lock on the constrained variant's hot path — where
  it would show up in the Pillar A measurements.
