# Experiment methodology and results (docs)

Narrative writeups of each experiment (hypothesis, setup, result,
conclusion) go here. Raw and processed data live in
`experiments/results/`; scripts and configs live in `experiments/scripts/`
and `experiments/configs/`. See `experiments/README.md` for the
reproducibility contract.

Evaluation is a cross-cutting workstream rather than a single phase — Pillar A
becomes measurable as soon as both edge-device variants exist in Phase 1,
Pillar B after Phases 4 and 9, Pillar C once the pipeline carries load. See
the root `README.md` for the pillar-to-phase mapping.

| Writeup | Pillar | Status |
|---|---|---|
| [`pillar-a-constrained-vs-naive.md`](pillar-a-constrained-vs-naive.md) | A — constrained vs. naive | **Recorded**: 5 repetitions of each variant under one 64 MB cap. Identical work; zero GC collections against four, 2.6× less CPU |
| [`pillar-b-recovery.md`](pillar-b-recovery.md) | B — detection and recovery | **Recorded**, Phase 11: 20 samples, MTTR and recovery success rate |
| [`pillar-c-scalability.md`](pillar-c-scalability.md) | C — fleet scalability | **Recorded**: 10 → 25 → 50 devices. Zero telemetry lost, detection flat at ~4128 ms, per-device cost falling. Recovery-vs-size still out of reach — see below |

All three pillars are recorded. What each one does **not** cover is stated in
its own writeup rather than left to be inferred.

## What Pillar C leaves out, and an earlier mistake about it

Scalability has two halves. Throughput, resource cost and **detection** latency
against device count are measured. **Recovery** latency against device count is
not.

The distinction is the whole point, and this index previously got it wrong: it
declined Pillar C outright on the grounds that latency-versus-size needed one
device per pod. That is true of recovery and false of detection. Detection
happens inside the gateway — it is the gateway noticing a device has stopped
heartbeating — and needs no pods at all, which is why the recorded run measures
it at 50 devices without difficulty.

Recovery genuinely does need one device per pod, because there is nothing for
the operator to replace in the shared harness; that is why Pillar B's figures
come from a different vehicle (ADR-013). A per-pod JVM costs roughly 64 MB of
baseline before doing any work, and the host has 8 GB with a Docker VM already
claiming about half. Phase 11 was scoped to three devices for exactly that
reason, and a curve drawn from three points would describe the laptop.

So the omission is one half, named in the writeup and carried as a structured
`scope.notMeasured` field in the run's own metadata. What would change it: a
host with enough memory to run the fleet one device per pod at 10, 25 and 50.
