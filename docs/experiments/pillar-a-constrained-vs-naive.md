# Pillar A: constrained vs. naive Java under one heap cap

**Run `a1-constrained-vs-naive-20260828T064558Z`.** Raw data in
[`experiments/results/raw/`](../../experiments/results/raw/a1-constrained-vs-naive-20260828T064558Z/),
processed output in
[`experiments/results/processed/`](../../experiments/results/processed/).
This is a recorded result and may be cited as one.

This is the project's stated academic contribution: applying J2ME-style
resource-conscious discipline to modern Java under a constrained heap, and
measuring what it is worth against a baseline written the ordinary way.

## What was measured

Both variants have existed since Phase 1 and are not two different programs.
Given the same seed they simulate the same sensor values in the same order and
emit **byte-identical payloads**, which is what makes "the same work" a
property of the code rather than an assumption in the writeup. What differs is
only how they get there.

**Naive** does what most Java does. Per reading it allocates, at minimum: a
`Telemetry` record, a topic `String`, a varargs `Object[]` holding six boxed
primitives, a `Formatter` and its `StringBuilder`, the result `String`, and a
UTF-8 byte array. It runs one thread per device. Every line of it is normal
practice — that is the point. The question is what an ordinary style costs
under a cap, not whether obviously wasteful code is wasteful.

**Constrained** allocates a `PayloadBuffer` once per device and reuses it for
the life of the run: static byte constants for the JSON keys, integers and
fixed-point decimals written digit by digit into the existing array, topics
cached, and one shared thread for the whole fleet.

| Parameter | Value |
|---|---|
| Heap cap | `-Xmx64m`, identical for both |
| Devices | 50, in one JVM (the shared harness) |
| Tick | 100 ms |
| Run duration | 60 s, ×5 repetitions per variant, alternating |
| Sink | `COUNTING` |
| Seed | 42 |
| Failure mode | `NONE` |
| JVM | OpenJDK 21.0.12.1, HotSpot, G1 |
| Machine | Apple M3, 8 cores, 8 GB, macOS 26.5.2 |
| Elapsed | 603 s |

The repetitions alternate rather than running five of one and then five of the
other. Machine state drifts over ten minutes — thermal, page cache, whatever
else a laptop decides to do — and giving that drift entirely to one side would
turn it into a result.

## Results

Median across five repetitions, with the full range beside it.

| Metric | constrained | naive | ratio |
|---|---|---|---|
| GC collections | 0 (0–0) | 4 (4–4) | constrained recorded none |
| GC pause total | none recorded | 10.0 ms (8.4–18.0) | constrained recorded none |
| GC time (JVM counter) | 0 ms (0–0) | 9 ms (8–17) | constrained recorded none |
| Max resident set | 64.2 MB (63.9–65.1) | 102.5 MB (89.8–104.5) | **naive 1.6×** |
| CPU time (user+sys) | 1.19 s (1.08–1.47) | 3.11 s (3.06–3.71) | **naive 2.6×** |
| User CPU | 0.84 s (0.76–1.09) | 2.23 s (2.17–2.70) | **naive 2.7×** |
| Throughput | 500.0 readings/s | 499.9 readings/s | 1.00× |
| Readings published | 30,003 | 30,004 | 1.00× |

## What the numbers say

**The constrained variant never collected.** Not "collected less" — across
five 60-second runs producing 30,003 readings each, the G1 log records zero
collections and the JVM's own counter agrees. The naive variant collected four
times in every single run, with a peak heap of 38 MB before collection. The
discipline does not reduce garbage here; under this workload it eliminates it.

**The throughput row is what makes the rest mean anything.** Both variants
published the same number of readings at the same rate. This is not a fast
implementation beating a slow one — it is the *same work, delivered on the same
schedule*, for **2.6× less CPU** and **1.6× less resident memory**. Reading
`1.00×` as "no difference" would be exactly backwards: it is the control that
licenses every other row.

**The memory figure is not mostly heap.** Both ran under the same 64 MB cap
and neither approached it, so the difference is in what the process holds
outside the heap.

> **Correction (2026-08-31).** This section previously attributed that
> difference to "fifty thread stacks against one". That was reasoning, not
> measurement, and the ablation in
> [`pillar-a-ablation-encoding-vs-threading.md`](pillar-a-ablation-encoding-vs-threading.md)
> shows it is wrong: holding the thread count fixed, the encoding accounts for
> 1.53x of the resident set, while holding the encoding fixed, the thread count
> accounts for only 1.12x. Memory is dominated by allocation discipline, not by
> thread stacks. The same ablation shows the CPU difference is roughly an even
> split between the two factors rather than mostly discipline, and that the
> zero-collection result is attributable to the encoding alone.

**The spread is narrow enough to trust the medians.** Constrained CPU ranges
1.08–1.47 s and naive 3.06–3.71 s: the distributions do not come close to
overlapping. The same is true of resident set. Nothing here rests on a single
run.

## What this run does not show

**Nothing about peak throughput.** The workload is rate-limited at 500
readings/s and neither variant was saturated. Both met the target with CPU to
spare, so this measures *cost at a fixed workload* and says nothing about what
either could sustain flat out. A saturation experiment is a different run and
has not been done.

**Nothing about a cap either variant is straining against.** 64 MB is
comfortable for both. An experiment that picked a cap low enough to kill the
naive variant would be a statement about the cap, and the interesting claim is
the one measured here: what each costs to do identical work inside the same
allowance.

**The tick is ten times the production rate.** The fleet's real workload is
one reading per device per second; this compresses an hour of it into a
minute. The figures compared are per-run costs at a stated rate, and the rate
is in the config so anyone who thinks it matters can re-run at 1000 ms.

**One machine, one JVM, one cap, one workload.** An Apple M3 under macOS with
G1. Nothing here generalises to another collector, another architecture, or a
container with a different memory limit without re-running — which is what the
committed runner is for.

**Two variables move together here.** `constrained` and `naive` differ in both
payload encoding and thread count, so no figure in this run can be attributed
to either on its own. That is what the companion ablation exists to resolve;
until it is read alongside this one, every ratio above is a combined effect.

**The readings differ by one.** 30,003 against 30,004, which is where the
60-second boundary falls relative to a 100 ms tick, not a difference in work.
Per-reading payloads are byte-identical.

## Reproducing it

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

```bash
./experiments/scripts/run-pillar-a-experiment.sh
```

The runner refuses to start without `JAVA_HOME`, because the `java` on this
machine's PATH is a GraalVM and ADR-002 excludes it — Graal's escape analysis
can erase exactly the allocation differences this experiment exists to measure.
That guard fired for real while this run was being taken.

Processing is a pure function of the recording:

```bash
python3 experiments/scripts/summarise-pillar-a.py experiments/results/raw/a1-constrained-vs-naive-20260828T064558Z
```

Running it again on the same raw directory produces byte-identical output,
which is what makes this result checkable rather than merely present. A metric
that was not recorded is reported as missing rather than counted as zero — the
"none recorded" cells above are an absence, not a claim of zero.

Three runs were discarded before this one. The first was killed mid-repetition
and left a truncated recording. The second was complete but its
`metadata.json` had no completion time, so the elapsed duration the
reproducibility contract asks for appeared nowhere — the same omission that
cost a Phase 11 run, and back-filling it is precisely what ADR-013 forbids.
The third recorded `uncommittedFiles: 1`, because the fix for the second was
still uncommitted when it ran; someone checking out that commit would not have
got the apparatus that produced it. This run was taken against a clean tree at
`98f1e4ee28f2`.
