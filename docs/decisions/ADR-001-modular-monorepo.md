# ADR-001: Modular monorepo structure

## Status
Accepted — 2026-08-21

## Context
The project spans several independently runnable Java modules
(edge-device, gateway, stream-processor, recovery-operator, common),
infrastructure config (Docker, Kubernetes, monitoring), reproducible
experiments, and documentation. We need a repository layout that keeps
each module independently buildable and testable while making the
system's data flow and shared schema obvious to a reader (and to a
grader) without jumping between repositories.

## Decision
Use a single modular monorepo (this repository) with one top-level
directory per module, a shared `common/` module for DTOs and topic/schema
constants, and dedicated top-level directories for infrastructure,
experiments, tests, and docs, as specified in the project skill
(`java-iot-skill.md`, section 17).

## Consequences
- Positive: one clone gets the whole system; cross-module changes (e.g.
  a telemetry schema change touching `common/`, `edge-device/`, and
  `gateway/`) are a single commit and PR; CI can build/test everything
  from one checkout; easier to present and grade as one coherent project.
- Positive: each module still has its own README and, once code exists,
  its own build file, so modules remain independently buildable.
- Negative: a single `.github/workflows/` CI pipeline must be careful to
  scope build/test steps per module so unrelated modules don't fail a
  build over an unrelated module's issue.
- Revisit if: any module grows large enough to need its own release
  cadence or its own team — not expected within the scope of a semester
  project.
