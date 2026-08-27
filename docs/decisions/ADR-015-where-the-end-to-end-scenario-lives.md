# ADR-015: Where the end-to-end scenario lives

## Status
Accepted — 2026-08-27

## Context
`CLAUDE.md` has listed a third test tier since the project started:

> **E2E** (`tests/e2e`): the project's core reproducible demo — start device
> → send telemetry → verify gateway receives it → kill device → verify
> heartbeat-timeout detection → verify failure event → verify recovery
> controller reacts → verify replacement telemetry resumes → record recovery
> duration.

`tests/e2e` is empty, and its README said it would stay that way "until Phase
9". Phase 9 landed, then 10, 11 and 12. By Phase 12 the scenario was being run
twice a phase — just not from `tests/e2e` — and the directory's README was
still describing a plan.

This ADR exists because retiring a stated requirement in a README is exactly
the move this project forbids: "change architecture without recording why (add
an ADR)".

## Decision

**The scenario stays; the JUnit suite does not get written.** It runs in two
places, at two altitudes, and both are already wired into the workflow:

- `infrastructure/docker/smoke-test.sh` runs the first half against the
  containerised stack — start the fleet, prove telemetry reached the gateway,
  prove the store recorded it — and asserts behaviour only. CI runs it on
  every pull request (ADR-014).
- `experiments/scripts/run-recovery-experiment.sh` runs the whole loop,
  including the kill and the recovery, against a real cluster. It **records**
  what happened rather than asserting on it, and that recording is where
  Phase 11's MTTR came from (ADR-013).

A JUnit copy would need a cluster to mean anything, because the replacement
half of the loop is the recovery operator creating a pod and the device pods
are bare Pods that do not restart themselves (ADR-010). Given a cluster, the
suite would have to end in an assertion, and the only assertion available is a
threshold — "recovery completed within N milliseconds". No recorded run
justifies any particular N. Picking one would put a number into the codebase
that no experiment produced, which is the single thing the reproducibility
contract forbids, and CI would then be the place that decides whether a
latency is acceptable on hardware the contract says cannot be used for
latency (ADR-014).

The recovery experiment already answers that question better: it records the
duration, and the writeup argues about what it means.

## Consequences

- **`CLAUDE.md`'s testing requirements now name the two vehicles** instead of
  a suite that does not exist, and point here. The tier is not abandoned —
  it moved.
- **`tests/e2e` stays in the tree with a README** rather than being deleted.
  The directory is where someone looks for the scenario, and finding a
  pointer there is worth more than finding nothing.
- **A yes/no property is still fair game for a suite here.** The case that
  would justify writing one: asserting that a redelivered failure event never
  produces a second replacement (ADR-011's idempotence guarantee). That is a
  boolean about correctness, not a threshold about speed, and it is the kind
  of thing that should fail a build. It needs a cluster, so it is not written
  today — but if `tests/e2e` ever fills up, it should fill up with that sort
  of test and not with timings.
- **The smoke test and the experiment runner are now load-bearing as tests.**
  They are shell, so Phase 12 puts them under ShellCheck at its strictest
  setting; a bug in either is a bug in the test tier.
