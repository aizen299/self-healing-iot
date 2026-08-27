#!/usr/bin/env python3
"""Derive Pillar A's processed results from one raw run directory.

    python3 experiments/scripts/summarise-pillar-a.py \
        experiments/results/raw/a1-constrained-vs-naive-<stamp>

Writes `results/processed/<run-id>.csv` and `<run-id>-summary.md`. It holds no
state, contacts nothing, and fills in no value that was not recorded: a metric
missing from a run is reported as missing rather than counted as zero, because
a zero in a resource column is a claim and an absence is not. Running it again
on the same raw directory produces byte-identical output, which is what makes
a committed result checkable rather than merely present.
"""
import csv
import json
import statistics
import sys
from pathlib import Path

VARIANTS = ("constrained", "naive")

# The metrics compared, and where each comes from. The two views are never
# merged into one figure: the JVM's account of itself and the operating
# system's account of the JVM answer different questions, and a disagreement
# between them is worth being able to see (ADR-013).
METRICS = [
    ("gc.collections", "GC collections", "{:.1f}", "count", "lower"),
    ("gc.pauseMillisTotal", "GC pause total", "{:.1f}", "ms", "lower"),
    ("internal.gcTimeMillis", "GC time (JVM counter)", "{:.1f}", "ms", "lower"),
    ("external.maxResidentBytes", "Max resident set", "{:.1f}", "MB", "lower"),
    ("external.cpuSeconds", "CPU time (user+sys)", "{:.2f}", "s", "lower"),
    ("external.userCpuSeconds", "User CPU", "{:.2f}", "s", "lower"),
    ("internal.throughputPerSecond", "Throughput", "{:.1f}", "readings/s", "higher"),
    ("internal.readingsPublished", "Readings published", "{:.0f}", "count", "higher"),
]

SCALE = {"external.maxResidentBytes": 1024 * 1024}


def dotted(record, path):
    """Read `a.b` from nested dicts, or None if any step is absent."""
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
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def summarise(values):
    """min / median / max / mean, or None when nothing was recorded."""
    present = [v for v in values if v is not None]
    if not present:
        return None
    return {
        "n": len(present),
        "min": min(present),
        "median": statistics.median(present),
        "max": max(present),
        "mean": statistics.fmean(present),
    }


def write_csv(out, runs):
    columns = ["runTag", "variant", "repetition", "exitCode"] + [m[0] for m in METRICS]
    with open(out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for run in sorted(runs, key=lambda r: (r["variant"], r["repetition"])):
            row = [run["runTag"], run["variant"], run["repetition"], run["exitCode"]]
            for path, *_ in METRICS:
                value = value_of(run, path)
                row.append("" if value is None else value)
            writer.writerow(row)


def comparison_line(label, unit, fmt, stats, better):
    """One metric, both variants, and the ratio between them."""
    left, right = stats.get("constrained"), stats.get("naive")
    if left is None or right is None:
        missing = " and ".join(v for v in VARIANTS if stats.get(v) is None)
        return f"| {label} ({unit}) | — | — | not recorded for {missing} |"

    def cell(s):
        return f"{fmt.format(s['median'])} ({fmt.format(s['min'])}–{fmt.format(s['max'])})"

    ratio = "—"
    a, b = left["median"], right["median"]
    if better == "lower" and a > 0:
        ratio = f"naive {b / a:.1f}x" if b > a else f"constrained {a / b:.1f}x" if b > 0 else "—"
    elif better == "lower" and a == 0:
        # A ratio against zero is not a number, and rounding it to one would be
        # the most flattering possible lie. Say what happened instead.
        ratio = "constrained recorded none" if b > 0 else "neither"
    elif better == "higher" and b > 0:
        ratio = f"{a / b:.2f}x" if a != b else "1.00x"
    return f"| {label} ({unit}) | {cell(left)} | {cell(right)} | {ratio} |"


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    raw = Path(argv[1]).resolve()
    runs = read_jsonl(raw / "runs.jsonl")
    metadata = json.loads((raw / "metadata.json").read_text())
    run_id = metadata["runId"]

    processed = raw.parents[1] / "processed"
    processed.mkdir(parents=True, exist_ok=True)
    write_csv(processed / f"{run_id}.csv", runs)

    params = metadata["parameters"]
    by_variant = {v: [r for r in runs if r["variant"] == v] for v in VARIANTS}
    incomplete = [r["runTag"] for r in runs if r.get("incomplete") or r["exitCode"] != 0]

    lines = [
        f"# {run_id}",
        "",
        f"Pillar A. {params['repetitionsPerVariant']} repetitions of each variant, "
        f"alternating, under `-Xmx{params['heapCap']}`.",
        "",
        f"- **{params['deviceCount']} devices**, {params['publishIntervalMillis']} ms tick, "
        f"{params['runDurationSeconds']} s per run, seed {params['seed']}, "
        f"sink {params['sink']}",
        f"- **JVM**: {metadata['toolchain']['java'].splitlines()[0]}",
        f"- **Machine**: {metadata['machine']['cpuModel']}, "
        f"{metadata['machine']['cpuCount']} cores, {metadata['machine']['os']}",
        f"- **Source**: `{metadata['source']['commit'][:12]}`"
        + ("" if metadata["source"]["uncommittedFiles"] == 0
           else f" with {metadata['source']['uncommittedFiles']} uncommitted files"),
        "",
        "## Runs",
        "",
        f"{len(runs)} runs recorded, "
        + ("all completed." if not incomplete
           else f"**{len(incomplete)} did not complete**: {', '.join(incomplete)}."),
        "",
        "## Median (min–max) across repetitions",
        "",
        "| Metric | constrained | naive | ratio |",
        "|---|---|---|---|",
    ]

    for path, label, fmt, unit, better in METRICS:
        stats = {v: summarise([value_of(r, path) for r in by_variant[v]])
                 for v in VARIANTS}
        lines.append(comparison_line(label, unit, fmt, stats, better))

    counts = {v: len(by_variant[v]) for v in VARIANTS}
    lines += [
        "",
        "## Reconciliation",
        "",
        f"- runs recorded: {len(runs)}",
        f"- constrained: {counts['constrained']}, naive: {counts['naive']}",
        f"- expected per variant: {params['repetitionsPerVariant']}",
        f"- incomplete: {len(incomplete)}",
        "",
        "Every figure above is a median over the repetitions with the full "
        "range beside it, not a single run. The per-run values are in the CSV.",
        "",
    ]

    (processed / f"{run_id}-summary.md").write_text("\n".join(lines))
    print(f"  {processed.relative_to(Path.cwd())}/{run_id}.csv")
    print(f"  {processed.relative_to(Path.cwd())}/{run_id}-summary.md")
    if counts["constrained"] != counts["naive"]:
        print("  warning: the two variants have different run counts", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
