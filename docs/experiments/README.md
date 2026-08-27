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
| [`pillar-b-recovery.md`](pillar-b-recovery.md) | B — detection and recovery | **Recorded**, Phase 11: 20 samples, MTTR and recovery success rate |
| Pillar A — constrained vs. naive | A | Not yet run. Both variants have existed since Phase 1; the comparison needs a harness run under an identical heap cap |
| Pillar C — fleet scalability | C | Not yet run. Needs the shared harness at 10 → 25 → 50 devices |

Only the first row is a result. The other two are stated as absent rather than
left to be inferred, because a reader who finds two of three pillars missing
should find out here rather than by not finding a file.
