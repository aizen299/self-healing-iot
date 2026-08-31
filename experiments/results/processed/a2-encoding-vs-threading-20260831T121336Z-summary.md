# a2-encoding-vs-threading-20260831T121336Z

Pillar A ablation. 5 repetitions of each of four arms, under `-Xmx64m`, 50 devices, 100 ms tick, 60 s, seed 42.

- **JVM**: openjdk version "21.0.12.1" 2026-08-18
- **Machine**: Apple M3, 8 cores
- **Source**: `c88bc4d79e79`

20 runs recorded, all completed.

`constrained-t1` and `naive-t50` are a1's two arms; the other two are the
corners a1 never measured.

## The 2x2 — median across repetitions

| Metric | constrained / 1 thread | constrained / 50 | naive / 1 | naive / 50 |
|---|---|---|---|---|
| GC collections (count) | 0.0 | 0.0 | 3.0 | 4.0 |
| GC time (ms) | 0.0 | 0.0 | 7.0 | 9.0 |
| Max resident set (MB) | 64.7 | 72.5 | 98.8 | 103.5 |
| CPU time (user+sys) (s) | 1.38 | 2.39 | 2.34 | 3.43 |
| Throughput (readings/s) | 499.9 | 499.9 | 500.0 | 499.9 |
| Readings published (count) | 30003 | 30004 | 30005 | 30004 |

## Decomposition

Each factor with the other held fixed. The a1 column is the combined
effect the earlier experiment measured without being able to split it.

| Metric | encoding @1 thread | encoding @50 threads | threads @constrained | threads @naive | a1 combined |
|---|---|---|---|---|---|
| GC collections (count) | 0 vs 3 | 0 vs 4 | n/a (0) | 1.33x | 0 vs 4 |
| GC time (ms) | 0 vs 7 | 0 vs 9 | n/a (0) | 1.29x | 0 vs 9 |
| Max resident set (MB) | 1.53x | 1.43x | 1.12x | 1.05x | 1.60x |
| CPU time (user+sys) (s) | 1.70x | 1.44x | 1.73x | 1.47x | 2.49x |
| Throughput (readings/s) | 1.00x | 1.00x | 1.00x | 1.00x | 1.00x |
| Readings published (count) | 1.00x | 1.00x | 1.00x | 1.00x | 1.00x |

Read left to right: how much worse the naive encoding is at a fixed thread
count, then how much worse more threads are at a fixed encoding, then the
combined figure a1 reported. Where a factor's columns are close to 1.00x it
did not contribute; where they are not, it did.

## Reconciliation

- runs recorded: 20
- per arm: constrained-t1=5, constrained-t50=5, naive-t1=5, naive-t50=5
- expected per arm: 5
- incomplete: 0

