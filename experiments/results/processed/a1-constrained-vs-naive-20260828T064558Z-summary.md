# a1-constrained-vs-naive-20260828T064558Z

Pillar A. 5 repetitions of each variant, alternating, under `-Xmx64m`.

- **50 devices**, 100 ms tick, 60 s per run, seed 42, sink COUNTING
- **JVM**: openjdk version "21.0.12.1" 2026-08-18
- **Machine**: Apple M3, 8 cores, macOS-26.5.2-arm64-arm-64bit-Mach-O
- **Source**: `98f1e4ee28f2`

## Runs

10 runs recorded, all completed.

## Median (min–max) across repetitions

| Metric | constrained | naive | ratio |
|---|---|---|---|
| GC collections (count) | 0.0 (0.0–0.0) | 4.0 (4.0–4.0) | constrained recorded none |
| GC pause total (ms) | none recorded | 10.0 (8.4–18.0) | constrained recorded none |
| GC time (JVM counter) (ms) | 0.0 (0.0–0.0) | 9.0 (8.0–17.0) | constrained recorded none |
| Max resident set (MB) | 64.2 (63.9–65.1) | 102.5 (89.8–104.5) | naive 1.6x |
| CPU time (user+sys) (s) | 1.19 (1.08–1.47) | 3.11 (3.06–3.71) | naive 2.6x |
| User CPU (s) | 0.84 (0.76–1.09) | 2.23 (2.17–2.70) | naive 2.7x |
| Throughput (readings/s) | 500.0 (499.9–500.0) | 499.9 (499.9–499.9) | 1.00x |
| Readings published (count) | 30003 (30003–30003) | 30004 (30002–30005) | 1.00x |

## Reconciliation

- runs recorded: 10
- constrained: 5, naive: 5
- expected per variant: 5
- incomplete: 0

Every figure above is a median over the repetitions with the full range beside it, not a single run. The per-run values are in the CSV.
