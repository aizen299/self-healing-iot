#!/usr/bin/env bash
#
# Builds the images and proves the stack actually works, then tears it down.
#
# Everything else in this project fails loudly when it breaks; containerisation
# was the one part verified only by hand. The Dockerfile hardcodes a list of
# module poms and source directories, so adding a module — the stream processor
# in Phase 6, the recovery operator in Phase 9 — can build fine under Maven and
# fail inside Docker on a clean cache. This is what catches that, and what
# Phase 12's CI should run.
set -euo pipefail

cd "$(dirname "$0")/../.."

PORT=18080
DEVICES=3
compose() { docker compose "$@"; }

cleanup() {
  echo "--- tearing down"
  compose down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "--- building images"
compose build

echo "--- starting broker and gateway"
compose up -d broker gateway

echo "--- waiting for the gateway to report healthy"
for _ in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null \
  || { echo "FAIL: gateway never became reachable on ${PORT}"; compose logs gateway; exit 1; }

echo "--- starting a short-lived fleet of ${DEVICES}"
compose run --rm -d \
  -e FLEET_DEVICE_COUNT="${DEVICES}" \
  -e FLEET_RUN_DURATION_SECONDS=40 \
  -e FLEET_DEVICE_ID_PREFIX=smoke \
  fleet >/dev/null

echo "--- waiting for telemetry to reach the gateway"
accepted=0
for _ in $(seq 1 30); do
  accepted=$(curl -fsS "http://127.0.0.1:${PORT}/health" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["telemetryAccepted"])')
  [ "${accepted}" -gt 0 ] && break
  sleep 2
done

if [ "${accepted}" -le 0 ]; then
  echo "FAIL: gateway accepted no telemetry"
  compose logs --tail=40 gateway
  exit 1
fi

# The store is the other half: ingestion without persistence is a silent gap,
# and a window that is not complete cannot support a result (ADR-007).
integrity=$(curl -fsS "http://127.0.0.1:${PORT}/stats" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["integrity"]["complete"])')

if [ "${integrity}" != "True" ]; then
  echo "FAIL: store reported an incomplete history"
  exit 1
fi

echo "PASS: ${accepted} readings accepted, history complete"
