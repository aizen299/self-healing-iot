#!/usr/bin/env python3
"""Turn one raw experiment run into processed data and a summary.

    ./experiments/scripts/summarise.py experiments/results/raw/<run-id>

Reads what the run recorded and writes, under results/processed/:

    <run-id>.csv          one row per recovery, joined across both reporters
    <run-id>-summary.md   the same run described in prose and statistics

Everything here is derived from the run's own files. Nothing is assumed, no
value is filled in when a record is missing, and a field that was not
measured stays empty rather than becoming a zero — a zero in a latency column
is a claim, and an empty cell is the absence of one.

Run it again on the same raw directory and it produces the same output: the
processing is a pure function of what was recorded, which is what makes the
committed results checkable.
"""

import json
import statistics
import sys
from pathlib import Path

# device.recovery carries two record shapes with unrelated schemas, told apart
# by this discriminator (ADR-009, and RecoveryPublisher's class comment). A
# consumer that sniffed for fields instead would break the first time either
# format gained one.
OPERATOR_KIND = "recovery-action"


def read_jsonl(path):
    """Every parseable line, with the unparseable ones counted rather than hidden."""
    records, malformed = [], 0
    if not path.exists():
        return records, malformed
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError:
            malformed += 1
    return records, malformed


def percentile(values, fraction):
    """Nearest-rank percentile.

    Named rather than interpolated on purpose: at twenty samples an
    interpolated p90 invents a number that lies between two measurements, and
    this project reports measurements.
    """
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), int(-(-len(ordered) * fraction // 1))))
    return ordered[rank - 1]


def describe(values, unit="ms"):
    if not values:
        return {"n": 0}
    return {
        "n": len(values),
        "min": min(values),
        "median": statistics.median(values),
        "p90": percentile(values, 0.90),
        "max": max(values),
        "mean": round(statistics.mean(values), 1),
        "unit": unit,
    }


def join(operator_actions, gateway_recoveries):
    """Pair each replacement with the recovery the gateway confirmed after it.

    Keyed by device and then by time: the operator's record ends when the API
    server accepted the pod, the gateway's ends when heartbeats resumed, so
    the gateway event that belongs to a replacement is the first one for that
    device at or after the operator acted. Anything else would let a slow
    recovery be credited to the next iteration's failure.
    """
    by_device = {}
    for event in gateway_recoveries:
        by_device.setdefault(event["deviceId"], []).append(event)
    for events in by_device.values():
        events.sort(key=lambda e: e["at"])

    rows, unmatched = [], 0
    for action in sorted(operator_actions, key=lambda a: a.get("actedAt", 0)):
        device = action["deviceId"]
        acted = action.get("actedAt")
        match = None
        for event in by_device.get(device, []):
            if event.get("consumed"):
                continue
            if acted is None or event["at"] >= acted:
                match = event
                event["consumed"] = True
                break
        if match is None and action.get("outcome") == "REPLACED":
            unmatched += 1
        rows.append({
            "deviceId": device,
            "recoveryId": action.get("recoveryId"),
            "outcome": action.get("outcome"),
            "replacementPod": action.get("replacementPod") or action.get("existingPod"),
            "detectedAtMillis": action.get("detectedAt"),
            "actedAtMillis": acted,
            # A component of MTTR, never MTTR. It ends when the API server
            # accepts the pod; the gateway's number starts at the same instant
            # and already contains it, so the two must never be added.
            "detectionToReplacementMillis": action.get("detectionToReplacementMillis"),
            "recoveredAtMillis": match["at"] if match else None,
            # This is MTTR: detection to confirmed heartbeats.
            "mttrMillis": match.get("recoveryDurationMillis") if match else None,
            "missedHeartbeats": match.get("missedHeartbeats") if match else None,
        })
    return rows, unmatched


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    raw = Path(sys.argv[1]).resolve()
    if not raw.is_dir():
        print(f"no such run directory: {raw}", file=sys.stderr)
        return 2

    run_id = raw.name
    processed = raw.parent.parent / "processed"
    processed.mkdir(parents=True, exist_ok=True)

    metadata = {}
    metadata_path = raw / "metadata.json"
    if metadata_path.exists():
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

    recovery, malformed = read_jsonl(raw / "recovery.jsonl")
    iterations, _ = read_jsonl(raw / "iterations.jsonl")

    operator_actions = [r for r in recovery if r.get("kind") == OPERATOR_KIND]
    gateway_recoveries = [r for r in recovery
                          if r.get("kind") != OPERATOR_KIND
                          and r.get("event") == "DEVICE_RECOVERED"]

    rows, unmatched = join(operator_actions, gateway_recoveries)

    mttr = [r["mttrMillis"] for r in rows if r["mttrMillis"] is not None]
    operator_half = [r["detectionToReplacementMillis"] for r in rows
                     if r["detectionToReplacementMillis"] is not None]
    observed = [i["observedMillis"] for i in iterations if i.get("observedMillis") is not None]

    injected = len(iterations)
    recovered = sum(1 for i in iterations if i.get("recovered"))
    replaced = sum(1 for r in rows if r["outcome"] == "REPLACED")
    outcomes = {}
    for row in rows:
        outcomes[row["outcome"]] = outcomes.get(row["outcome"], 0) + 1

    columns = ["deviceId", "recoveryId", "outcome", "replacementPod",
               "detectedAtMillis", "actedAtMillis", "detectionToReplacementMillis",
               "recoveredAtMillis", "mttrMillis", "missedHeartbeats"]
    csv_path = processed / f"{run_id}.csv"
    with csv_path.open("w", encoding="utf-8") as f:
        f.write(",".join(columns) + "\n")
        for row in rows:
            f.write(",".join("" if row[c] is None else str(row[c]) for c in columns) + "\n")

    mttr_stats = describe(mttr)
    operator_stats = describe(operator_half)
    observed_stats = describe(observed)
    success = (recovered / injected * 100.0) if injected else None

    lines = []
    add = lines.append
    add(f"# {run_id}")
    add("")
    add("Generated by `experiments/scripts/summarise.py` from "
        f"`experiments/results/raw/{run_id}/`. Every number below came out of "
        "that run; none is an estimate.")
    add("")
    fleet = metadata.get("fleet", {})
    experiment = metadata.get("experiment", {})
    add("## Setup")
    add("")
    add("| Property | Value |")
    add("|---|---|")
    add(f"| Started (UTC) | {metadata.get('startedAtUtc', '—')} |")
    add(f"| Failure mode | `{experiment.get('failureMode', '—')}` |")
    add(f"| Iterations | {experiment.get('iterations', '—')} |")
    add(f"| Devices | {fleet.get('deviceCount', '—')} — "
        f"{', '.join(fleet.get('deviceIds', [])) or '—'} |")
    add(f"| Fleet model | {fleet.get('model', '—')} |")
    add(f"| Tick interval | {fleet.get('tickIntervalMs') or '—'} ms "
        "(publish interval and expected heartbeat interval — one value, ADR-006) |")
    add(f"| Device variant | {fleet.get('variant') or '—'} |")
    add(f"| Device heap | {fleet.get('heapLimit') or '—'} |")
    add(f"| Device sink | {fleet.get('sink') or '—'} |")
    for line in fleet.get("gatewayStartupConfig", []):
        if "missed heartbeats" in line or "recovery confirms" in line:
            add(f"| {line.split(':', 1)[0].strip()} | {line.split(':', 1)[1].strip()} |")
    add(f"| Machine | {metadata.get('machine', {}).get('os', '—')} |")
    add("")
    add("## Recovery success rate")
    add("")
    add(f"- Failures injected: **{injected}**")
    add(f"- Devices confirmed back online: **{recovered}**")
    if success is not None:
        add(f"- Recovery success rate: **{success:.1f}%**")
    add(f"- Operator outcomes: {outcomes or '—'}")
    if unmatched:
        add(f"- Replacements with no matching gateway confirmation: **{unmatched}** "
            "(counted, not dropped)")
    add("")
    add("## MTTR — detection to confirmed heartbeats")
    add("")
    add("The gateway's `recoveryDurationMillis`. **This is MTTR.**")
    add("")
    add(stat_table(mttr_stats))
    add("")
    add("## Operator half — detection to the API server accepting the pod")
    add("")
    add("A *component* of the number above, not a second measurement of the same "
        "thing and never to be added to it: it starts at the same instant and "
        "ends earlier.")
    add("")
    add(stat_table(operator_stats))
    add("")
    add("## Cross-check — the runner's external view")
    add("")
    add("Wall-clock time from `kubectl delete` returning to the gateway reporting "
        "`ONLINE`, polled at 4 Hz from outside the cluster. It should exceed MTTR: "
        "it includes the poll interval and the API round trip, and it starts "
        "before the gateway has detected anything. Recorded separately so a "
        "disagreement with the system's own numbers is visible rather than "
        "averaged away.")
    add("")
    add(stat_table(observed_stats))
    add("")
    if malformed:
        add(f"**{malformed} malformed record(s)** were skipped while reading "
            "`recovery.jsonl`.")
        add("")
    add("## Reading this")
    add("")
    add("This is a recorded result and may be cited as one. It is a measurement of "
        f"this fleet on this machine at {fleet.get('deviceCount', '?')} devices, "
        "one per pod — not a general claim about Kubernetes recovery, and not "
        "comparable to a run at a different device count or fleet model.")

    summary_path = processed / f"{run_id}-summary.md"
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"  rows                : {len(rows)}")
    print(f"  failures injected   : {injected}")
    print(f"  recovered           : {recovered}"
          + (f" ({success:.1f}%)" if success is not None else ""))
    print(f"  MTTR (gateway)      : {format_stats(mttr_stats)}")
    print(f"  operator half       : {format_stats(operator_stats)}")
    print(f"  runner observed     : {format_stats(observed_stats)}")
    print(f"  wrote               : {csv_path}")
    print(f"                        {summary_path}")
    return 0


def stat_table(stats):
    if not stats.get("n"):
        return "No samples were recorded for this measurement."
    return "\n".join([
        "| n | min | median | p90 | max | mean |",
        "|---|---|---|---|---|---|",
        f"| {stats['n']} | {stats['min']} ms | {stats['median']} ms | "
        f"{stats['p90']} ms | {stats['max']} ms | {stats['mean']} ms |",
    ])


def format_stats(stats):
    if not stats.get("n"):
        return "no samples"
    return (f"n={stats['n']} min={stats['min']} median={stats['median']} "
            f"p90={stats['p90']} max={stats['max']} mean={stats['mean']} ms")


if __name__ == "__main__":
    sys.exit(main())
