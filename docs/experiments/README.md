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
| Pillar C — fleet scalability | C | **Not measured, and not pending.** Descoped against the measurement host — see below |

Two of the three are results. The third is stated as absent rather than left
to be inferred, because a reader who finds a pillar missing should find out
here rather than by not finding a file.

## Why Pillar C was not measured

Scalability has two halves, and only one of them is affordable on the machine
this project is measured on.

The throughput half — messages per second against device count, on the shared
harness — would fit. Pillar A already ran 50 devices in one JVM under a 64 MB
cap using about 100 MB resident, and 10 → 25 → 50 is no heavier.

The half that matters more does not fit. Detection and recovery latency
against fleet size has to run one device per pod, because there is nothing for
the operator to replace in the shared harness — that is why Pillar B's figures
come from a different vehicle (ADR-013). A per-pod JVM costs roughly 64 MB of
baseline before it does any work, and the host has 8 GB with a Docker VM
already claiming about half of it. Phase 11 was scoped to three devices for
exactly this reason. A scalability curve drawn from three points, two of which
are the same order of magnitude, would describe the laptop rather than the
system.

Measuring only the affordable half and calling it Pillar C would be worse than
not measuring it: a scalability result that silently omits recovery latency is
the kind of artefact this project's reproducibility contract exists to keep
out. So it is declined, in the open, rather than left looking pending.

What would change the answer: a host with enough memory to run the fleet one
device per pod at 10, 25 and 50 — or a decision to report the throughput curve
under its own name, as something narrower than Pillar C.
