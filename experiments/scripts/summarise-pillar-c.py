#!/usr/bin/env python3
"""Derive Pillar C's processed results from one raw run directory.

    python3 experiments/scripts/summarise-pillar-c.py \
        experiments/results/raw/c1-fleet-scalability-<stamp>

Writes `<run-id>.csv` and `<run-id>-summary.md` under results/processed. It
holds no state, contacts nothing, and fills in no value that was not recorded.
Running it again on the same raw directory produces byte-identical output.

The table is one row per device count, because that is the independent
variable. Per-device costs are derived and labelled as derived — the recorded
quantity is the whole-fleet figure, and dividing it is an interpretation.
"""
import csv
import json
import statistics
import sys
from pathlib import Path

METRICS = [
    ("fleet.throughputPerSecond", "Fleet throughput", "{:.1f}", "readings/s"),
    ("fleet.process_cpuSeconds", "Fleet CPU", "{:.2f}", "s"),
    ("fleet.process_maxResidentBytes", "Fleet resident", "{:.1f}", "MB"),
    ("fleet.gcCollections", "Fleet GC collections", "{:.1f}", "count"),
    ("gateway.cpuSeconds", "Gateway CPU", "{:.2f}", "s"),
    ("gateway.maxResidentBytes", "Gateway resident", "{:.1f}", "MB"),
    ("ingest.telemetryAccepted", "Telemetry accepted", "{:.0f}", "count"),
    ("ingest.failuresDetected", "Failures detected", "{:.0f}", "count"),
    ("detection.medianMillis", "Detection latency", "{:.0f}", "ms"),
]
SCALE = {
    "fleet.process_maxResidentBytes": 1024 * 1024,
    "gateway.maxResidentBytes": 1024 * 1024,
}


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
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def median_of(runs, path):
    present = [v for v in (value_of(r, path) for r in runs) if v is not None]
    return statistics.median(present) if present else None


def write_csv(out, runs):
    columns = ["runTag", "deviceCount", "repetition", "exitCode"] + [m[0] for m in METRICS]
    with open(out, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for run in sorted(runs, key=lambda r: (r["deviceCount"], r["repetition"])):
            row = [run["runTag"], run["deviceCount"], run["repetition"], run["exitCode"]]
            for path, *_ in METRICS:
                value = value_of(run, path)
                row.append("" if value is None else value)
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

    counts = params["deviceCounts"]
    by_count = {c: [r for r in runs if r["deviceCount"] == c] for c in counts}
    incomplete = [r["runTag"] for r in runs
                  if r.get("incomplete") or r["exitCode"] != 0]

    lines = [
        f"# {run_id}",
        "",
        f"Pillar C. {params['repetitions']} repetitions at each of "
        f"{', '.join(str(c) for c in counts)} devices, "
        f"{params['publishIntervalMillis']} ms tick, "
        f"{params['runDurationSeconds']} s per run, {params['variant']} variant.",
        "",
        f"- **Fleet** `-Xmx{params['fleetHeapCap']}`, "
        f"**gateway** `-Xmx{params['gatewayHeapCap']}`, over MQTT to "
        f"{metadata['mqtt']['broker']}",
        f"- **Fault**: {params['failureMode']} after "
        f"{params['failAfterReadings']} readings, every device; OFFLINE after "
        f"{params['offlineAfterMisses']} missed heartbeats",
        f"- **JVM**: {metadata['toolchain']['java'].splitlines()[0]}",
        f"- **Machine**: {metadata['machine']['cpuModel']}, "
        f"{metadata['machine']['cpuCount']} cores",
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
        "## Median at each fleet size",
        "",
        "| Metric | " + " | ".join(f"{c} devices" for c in counts) + " |",
        "|---" * (len(counts) + 1) + "|",
    ]

    for path, label, fmt, unit in METRICS:
        cells = []
        for c in counts:
            value = median_of(by_count[c], path)
            cells.append("not recorded" if value is None else fmt.format(value))
        lines.append(f"| {label} ({unit}) | " + " | ".join(cells) + " |")

    # Derived, and said to be derived. The recorded quantity is the whole
    # fleet's cost; per-device is that divided by the count, which is an
    # interpretation and is labelled as one.
    lines += ["", "## Derived: cost per device", "",
              "Whole-fleet figures divided by the device count. Derived from the "
              "rows above, not separately measured.", "",
              "| Metric | " + " | ".join(f"{c} devices" for c in counts) + " |",
              "|---" * (len(counts) + 1) + "|"]
    for path, label, _fmt, unit in [
            ("fleet.process_cpuSeconds", "Fleet CPU per device", "", "ms"),
            ("gateway.cpuSeconds", "Gateway CPU per device", "", "ms"),
            ("gateway.maxResidentBytes", "Gateway resident per device", "", "MB")]:
        cells = []
        for c in counts:
            value = median_of(by_count[c], path)
            if value is None:
                cells.append("not recorded")
            elif unit == "ms":
                cells.append(f"{value * 1000 / c:.1f}")
            else:
                cells.append(f"{value / c:.2f}")
        lines.append(f"| {label} ({unit}) | " + " | ".join(cells) + " |")

    lines += [
        "",
        "## Reconciliation",
        "",
        f"- runs recorded: {len(runs)}",
        "- per device count: "
        + ", ".join(f"{c}={len(by_count[c])}" for c in counts),
        f"- expected per device count: {params['repetitions']}",
        f"- incomplete: {len(incomplete)}",
        "",
        "Detection samples per run (devices the gateway had declared OFFLINE "
        "when sampled, against the fleet size):",
        "",
    ]
    for c in counts:
        detail = []
        for run in sorted(by_count[c], key=lambda r: r["repetition"]):
            got = dotted(run, "detection.samples")
            missed = dotted(run, "detection.notOffline")
            detail.append(f"rep{run['repetition']}={got}/{c}"
                          + (f" ({missed} not offline)" if missed else ""))
        lines.append(f"- {c} devices: " + ", ".join(detail))

    lines += [
        "",
        "## Scope",
        "",
        "Measured: " + "; ".join(metadata["scope"]["measured"]) + ".",
        "",
        "**Not measured**: recovery latency against device count. "
        + metadata["scope"]["notMeasured"]["recoveryLatencyAgainstDeviceCount"],
        "",
    ]

    (processed / f"{run_id}-summary.md").write_text("\n".join(lines) + "\n")
    print(f"  experiments/results/processed/{run_id}.csv")
    print(f"  experiments/results/processed/{run_id}-summary.md")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
