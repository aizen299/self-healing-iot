# tests/e2e

End-to-end scenario: start device -> send telemetry -> verify gateway
receives it -> kill device -> verify heartbeat-timeout detection ->
verify failure event -> verify recovery controller reacts -> verify
replacement telemetry resumes -> record recovery duration.

**Status: deliberately empty as a JUnit suite — see
[ADR-015](../../docs/decisions/ADR-015-where-the-end-to-end-scenario-lives.md),
which records the decision and what would reverse it.**

The scenario itself exists and runs — twice over, at two different
altitudes:

- `infrastructure/docker/smoke-test.sh` runs the first half against the
  containerised stack, asserting behaviour only: telemetry accepted, history
  complete. CI runs it on every pull request.
- `experiments/scripts/run-recovery-experiment.sh` runs the whole loop,
  including the kill and the recovery, against a real Kubernetes cluster —
  and records what happened rather than asserting on it. That is the
  vehicle Phase 11's MTTR came from; see
  `docs/experiments/pillar-b-recovery.md`.

A JUnit e2e suite would need a cluster to be meaningful, because the
replacement half of the loop is the recovery operator creating a pod
(ADR-010). It would then be a slower, less honest copy of the experiment
runner: an assertion of "recovery happened within N ms" hard-codes a
threshold no recorded run justifies, which is exactly what the
reproducibility contract forbids. The runner records the duration instead,
and the threshold question is answered by the writeup.

What would justify a suite here: a scenario that has to *fail* the build
rather than be measured — for instance, asserting that a redelivered
failure event never produces a second replacement (ADR-011's idempotence
guarantee), which is a yes/no property and does not need a number. If this
directory ever fills up, it should fill up with that and not with timings.
