# Pillar A ablation: which half of the variant does the work?

**Run `a2-encoding-vs-threading-20260831T121336Z`.** Raw data in
[`experiments/results/raw/`](../../experiments/results/raw/a2-encoding-vs-threading-20260831T121336Z/),
processed output in [`experiments/results/processed/`](../../experiments/results/processed/).

## Why this run exists

[Pillar A](pillar-a-constrained-vs-naive.md) compared `constrained` against
`naive` and found the constrained variant did identical work for 2.6× less CPU
without triggering a single garbage collection. But the two variants differ in
**two** ways at once:

1. **Encoding** — a `PayloadBuffer` reused for the life of the run, with
   digits written into it, versus `String.format`, boxing, a new `String` and
   a fresh `byte[]` per reading.
2. **Threading** — one shared thread for the fleet, versus one thread per
   device.

No figure in that experiment can be attributed to either factor alone. That is
the same confound this project identifies in its closest prior art, and
publishing the criticism while carrying the flaw would not survive review.

`FLEET_THREADS` was added to override the variant's thread policy in either
direction. It defaults to "let the variant decide", so Pillar A and every
deployment manifest are unaffected.

## Design

Four arms, five repetitions each, alternating order, all other parameters held
at Pillar A's values: `-Xmx64m`, 50 devices, 100 ms tick, 60 s, seed 42,
`COUNTING` sink, OpenJDK 21.0.12.1 HotSpot on an Apple M3. 1203 s elapsed,
clean tree at `c88bc4d79e79`.

`constrained/1` and `naive/50` are Pillar A's two arms; the other two corners
are what it never measured.

## Results — median of five

| Metric | constrained / 1 | constrained / 50 | naive / 1 | naive / 50 |
|---|---|---|---|---|
| GC collections | **0** | **0** | 3 | 4 |
| GC time (ms) | 0 | 0 | 7 | 9 |
| Max resident set (MB) | 64.7 | 72.5 | 98.8 | 103.5 |
| CPU (user+sys, s) | 1.38 | 2.39 | 2.34 | 3.43 |
| Throughput (readings/s) | 499.9 | 499.9 | 500.0 | 499.9 |
| Readings published | 30003 | 30004 | 30005 | 30004 |

Throughput and readings are identical across all four arms, which is again the
control: every arm did the same work at the same rate.

## Decomposition

| Metric | encoding (threads fixed) | threading (encoding fixed) | Pillar A combined |
|---|---|---|---|
| GC collections | 0 vs 3 · 0 vs 4 | none · 1.33× | 0 vs 4 |
| Max resident set | **1.53× · 1.43×** | 1.12× · 1.05× | 1.60× |
| CPU | 1.70× · 1.44× | **1.73× · 1.47×** | 2.49× |

Three findings, and two of them correct the earlier writeup.

**Garbage collection is entirely the encoding.** The constrained encoding
triggers zero collections whether it runs on one thread or fifty. The naive
encoding collects three times on one thread and four on fifty. Thread count
moves the count by one; the encoding moves it from four to none. This is the
cleanest result in the project and it is attributable to a single factor.

**Memory is dominated by the encoding, not by thread stacks — the opposite of
what Pillar A claimed.** Holding threads fixed, the encoding accounts for
1.53×; holding the encoding fixed, fifty threads against one account for only
1.12×. The earlier writeup asserted the resident-set difference was "dominated
by fifty thread stacks against one". That was plausible reasoning and it was
wrong; fifty JVM thread stacks are largely reserved rather than resident, while
the naive encoding's per-reading garbage is genuinely occupied.

**CPU splits roughly evenly, which weakens the headline.** Encoding costs
1.70× at one thread; threading costs 1.73× at the constrained encoding. They
compound to the 2.49× Pillar A reported. So **about half of that figure is
scheduling and contention, not allocation discipline.** Pillar A's 2.6× CPU
claim should not be read as a claim about coding style alone.

## What this means for the thesis

The narrow claim gets stronger and the broad one gets weaker, which is the
usual shape of an honest ablation.

Stronger: *allocation discipline alone* eliminates garbage collection under a
fixed heap ceiling, and accounts for most of the resident-set difference, with
thread count held constant. That is a clean, single-factor result.

Weaker: "resource-conscious Java costs 2.6× less CPU" conflates two decisions.
The defensible version is that discipline and threading contribute comparably,
and that a project adopting only one of them should expect roughly half the
benefit.

## What this run does not show

**Nothing about why threading costs what it does.** Fifty threads on eight
cores could be scheduler overhead, context switching, cache pressure or all
three. The ablation separates the factors; it does not explain the mechanism
of either.

**Nothing about other thread counts.** Only 1 and 50 were run. The relationship
between thread count and CPU is not established as linear or otherwise.

**The same limits as Pillar A** otherwise: one machine, one collector, one cap,
a rate-limited workload with no saturation point, and no per-message latency.

## Reproducing it

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

```bash
./experiments/scripts/run-pillar-a-ablation.sh
```

```bash
python3 experiments/scripts/summarise-pillar-a-ablation.py experiments/results/raw/a2-encoding-vs-threading-20260831T121336Z
```

Re-derivation is byte-identical, as with every other processor here.
