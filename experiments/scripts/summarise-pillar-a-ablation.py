#!/usr/bin/env python3
"""Split a1's result into the part that is encoding and the part that is threads.

    python3 experiments/scripts/summarise-pillar-a-ablation.py \
        experiments/results/raw/a2-encoding-vs-threading-<stamp>

a1 compared `constrained` against `naive`, and those differ in two ways at
once: payload encoding and thread count. This reads the 2x2 and reports each
factor's effect with the other held fixed, which is the only way the earlier
number decomposes.

Same contract as every other processor here: no state, no network, nothing
filled in that was not recorded, and byte-identical output on a second run.
"""
import csv
import json
import statistics
import sys
from pathlib import Path

ARMS = ("constrained-t1", "constrained-t50", "naive-t1", "naive-t50")

METRICS = [
    ("gc.collections", "GC collections", "{:.1f}", "count"),
    ("internal.gcTimeMillis", "GC time", "{:.1f}", "ms"),
    ("external.maxResidentBytes", "Max resident set", "{:.1f}", "MB"),
    ("external.cpuSeconds", "CPU time (user+sys)", "{:.2f}", "s"),
    ("internal.throughputPerSecond", "Throughput", "{:.1f}", "readings/s"),
    ("internal.readingsPublished", "Readings published", "{:.0f}", "count"),
]
SCALE = {"external.maxResidentBytes": 1024 * 1024}


def dotted(record, path):
    cursor = record
    for part in path.split("."):
        if not isinstance(cursor, dict) or part not in cursor:
            return None
        cursor = cursor[part]
    return cursor


def value_of(record, path):
    raw = dotted(record, path)
    if raw is None:
        return None
    return raw / SCALE[path] if path in SCALE else raw


def read_jsonl(path):
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def median_of(runs, path):
    present = [v for v in (value_of(r, path) for r in runs) if v is not None]
    return statistics.median(present) if present else None


def ratio(high, low):
    """How many times larger `high` is than `low`, or a stated absence."""
    if high is None or low is None:
        return "—"
    if low == 0:
        return "n/a (0)" if high == 0 else "0 vs " + f"{high:g}"
    return f"{high / low:.2f}x"


def write_csv(out, runs):
    columns = ["runTag", "variant", "repetition", "exitCode"] + [m[0] for m in METRICS]
    with open(out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for run in sorted(runs, key=lambda r: (r["variant"], r["repetition"])):
            row = [run["runTag"], run["variant"], run["repetition"], run["exitCode"]]
            for path, *_ in METRICS:
                v = value_of(run, path)
                row.append("" if v is None else v)
            writer.writerow(row)


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    raw = Path(argv[1]).resolve()
    runs = read_jsonl(raw / "runs.jsonl")
    metadata = json.loads((raw / "metadata.json").read_text())
    run_id = metadata["runId"]
    params = metadata["parameters"]

    processed = raw.parents[1] / "processed"
    processed.mkdir(parents=True, exist_ok=True)
    write_csv(processed / f"{run_id}.csv", runs)

    by_arm = {a: [r for r in runs if r["variant"] == a] for a in ARMS}
    incomplete = [r["runTag"] for r in runs
                  if r.get("incomplete") or r["exitCode"] != 0]

    lines = [
        f"# {run_id}",
        "",
        f"Pillar A ablation. {params['repetitionsPerArm']} repetitions of each of "
        f"four arms, under `-Xmx{params['heapCap']}`, "
        f"{params['deviceCount']} devices, {params['publishIntervalMillis']} ms tick, "
        f"{params['runDurationSeconds']} s, seed {params['seed']}.",
        "",
        f"- **JVM**: {metadata['toolchain']['java'].splitlines()[0]}",
        f"- **Machine**: {metadata['machine']['cpuModel']}, "
        f"{metadata['machine']['cpuCount']} cores",
        f"- **Source**: `{metadata['source']['commit'][:12]}`"
        + ("" if metadata["source"]["uncommittedFiles"] == 0
           else f" with {metadata['source']['uncommittedFiles']} uncommitted files"),
        "",
        f"{len(runs)} runs recorded, "
        + ("all completed." if not incomplete
           else f"**{len(incomplete)} did not complete**: {', '.join(incomplete)}."),
        "",
        "`constrained-t1` and `naive-t50` are a1's two arms; the other two are the",
        "corners a1 never measured.",
        "",
        "## The 2x2 — median across repetitions",
        "",
        "| Metric | constrained / 1 thread | constrained / 50 | naive / 1 | naive / 50 |",
        "|---|---|---|---|---|",
    ]
    for path, label, fmt, unit in METRICS:
        cells = []
        for a in ARMS:
            v = median_of(by_arm[a], path)
            cells.append("not recorded" if v is None else fmt.format(v))
        lines.append(f"| {label} ({unit}) | " + " | ".join(cells) + " |")

    lines += [
        "",
        "## Decomposition",
        "",
        "Each factor with the other held fixed. The a1 column is the combined",
        "effect the earlier experiment measured without being able to split it.",
        "",
        "| Metric | encoding @1 thread | encoding @50 threads | threads @constrained | threads @naive | a1 combined |",
        "|---|---|---|---|---|---|",
    ]
    for path, label, _fmt, unit in METRICS:
        c1 = median_of(by_arm["constrained-t1"], path)
        c50 = median_of(by_arm["constrained-t50"], path)
        n1 = median_of(by_arm["naive-t1"], path)
        n50 = median_of(by_arm["naive-t50"], path)
        lines.append(
            f"| {label} ({unit}) | {ratio(n1, c1)} | {ratio(n50, c50)} | "
            f"{ratio(c50, c1)} | {ratio(n50, n1)} | {ratio(n50, c1)} |")

    lines += [
        "",
        "Read left to right: how much worse the naive encoding is at a fixed thread",
        "count, then how much worse more threads are at a fixed encoding, then the",
        "combined figure a1 reported. Where a factor's columns are close to 1.00x it",
        "did not contribute; where they are not, it did.",
        "",
        "## Reconciliation",
        "",
        f"- runs recorded: {len(runs)}",
        "- per arm: " + ", ".join(f"{a}={len(by_arm[a])}" for a in ARMS),
        f"- expected per arm: {params['repetitionsPerArm']}",
        f"- incomplete: {len(incomplete)}",
        "",
    ]

    (processed / f"{run_id}-summary.md").write_text("\n".join(lines) + "\n")
    print(f"  experiments/results/processed/{run_id}.csv")
    print(f"  experiments/results/processed/{run_id}-summary.md")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
