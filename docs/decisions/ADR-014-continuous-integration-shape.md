# ADR-014: What CI is allowed to decide, and where it runs

## Status
Accepted — 2026-08-27

## Context
Phase 12 adds the pipeline that every change passes through. Three questions
had to be answered before writing it, and only the first is the one people
usually mean by "which CI".

## Decision

### GitHub Actions, not the Jenkins already running on this machine

There is a Jenkins on this laptop. It has been there longer than this project
— it is why the fleet publishes on 18080 instead of 8080 — so "use what is
already installed" was a real option rather than a hypothetical one. It is
declined for three reasons, and the third is the one that settles it.

The workflow this project already uses lives on GitHub: every phase ends in a
pull request, a review, and a merge. A gate that cannot mark a pull request
red is not gating that workflow. Making a laptop Jenkins reachable by GitHub
would mean exposing it, which is a larger security decision than a semester
project's CI should be making.

But the deciding reason is methodological. **Jenkins competes for the same
8 GB the experiments measure in.** Pillar A's whole subject is heap and GC
behaviour under a constrained cap, and Phase 11's recovery runs time a loop on
this host. A CI build that started while a run was in progress would consume
memory and CPU inside the measurement, and — worse — it would do so
invisibly, producing a number that is real, plausible, and wrong. Every other
decision in this project treats the measurement host as part of the
experimental apparatus. Putting the build on someone else's machine follows
from that, and it is the same reasoning that keeps the JVM pinned.

### The gate is "the suite ran complete", not "the build passed"

`mvn verify` exits 0 when tests are skipped. Measured on the day this was
written, eleven of the suite's 260 tests skipped themselves when no MQTT
broker was reachable: the seven in `MqttTelemetrySinkTest`, and the four
integration tests covering MQTT → gateway and heartbeat-timeout detection.
Those counts are recorded here, dated, and nowhere else — a figure repeated
across README and CLAUDE.md goes stale the next time a test is added, and a
stale figure in four places is worse than one figure in one. That skip is correct behaviour
locally — `mvn test` should stay green on a machine with no broker — and it
is exactly wrong in CI, where a broker that failed to start would produce a
green run covering none of Phase 2 and none of Phase 4's integration path.

So CI starts a real broker, and then asserts that the suite ran complete:
every module with tests reported, no report belongs to a class that no longer
exists, nothing skipped, nothing failed. The numbers above are measured, not
estimated — the suite skipped 11 with no broker and 0 with one, which is what
makes zero an honest threshold rather than an aspiration.

Two details of that check earn their place. It reads the **reason** for each
skip out of the report rather than inferring one: JUnit writes the assumption
message into the `<skipped>` element, so the gate can quote the test instead
of guessing that any skip means the broker is down — which would misdirect
whoever reads it the first time a skip has some other cause. And it rejects a
report whose test class has no source file, because surefire never deletes
reports: without that, a build that skipped `clean` folds a renamed class's
last run into this one's totals.

The check has its own fixtures, run in CI as `--self-test`. It is the one
component here whose failure mode is a green build, so a regression in it is
invisible by construction unless something tests it.

The broker is started with `docker compose up --wait broker` rather than as a
workflow service container. The broker's configuration is a file in this
repository — anonymous access on, persistence off so retained presence cannot
leak into the next run (ADR-004) — and a service container starts before the
checkout that would supply it. Reusing the compose service means CI runs the
same image, at the same pinned digest, with the same config as a developer.

This is the same principle as the Phase 11 preflight, applied to a different
apparatus: **a harness that cannot do its job must fail, not shrug.**

### CI never produces a number

There is no benchmark job, no timing assertion, and no performance regression
gate. GitHub's runners are shared machines of unstated and varying hardware,
so any figure produced there would fail the reproducibility contract at the
first line — the one that requires machine specs. A performance trend graph
built from CI runs would look exactly like a result and be worth nothing, and
this project's recurring failure mode is precisely the artefact that gets
mistaken for a result.

The smoke test therefore asserts behaviour and never latency: that the gateway
accepted telemetry, and that the store reports a complete history. Both are
yes/no questions about whether the system works.

The recovery experiment is not run in CI either. It needs a cluster, and while
a runner could host `kind`, the MTTR it measured there would be a property of
a shared runner. Results come from `experiments/`, on a recorded host, and
from nowhere else (ADR-013).

### Everything the pipeline depends on is pinned

The runner image is `ubuntu-24.04`, not `ubuntu-latest`. Actions are pinned to
commit SHAs, not tags. ShellCheck runs from an image pinned by digest. This is
the Dockerfile's argument for pinning base images — a moving tag silently
changes what you are running — applied to the thing that decides whether a
change is allowed in. A gate that can tighten or loosen on its own is not a
gate, and a build that turns red on a morning when nothing was committed costs
more trust than the pinning costs effort.

Only three actions are used, all first-party: checkout, setup-java, and
upload-artifact. Registry login is `docker login --password-stdin` and the
image build is `docker build`, because both are one line and neither needs a
third party in the path of a credential.

### Publishing is tag-gated, and there is no continuous deployment

The "CD" half is deliberately small, because this project has no environment
to deploy to. Its deployment target is a `kind` cluster on a laptop, and
`deploy.sh` side-loads images into it directly — there is no registry in that
path at all. A pipeline that redeployed on every merge would have nowhere to
redeploy to, and building one would be the same mistake as adding a chaos
framework to inject a fault that `kubectl delete` already injects.

What a registry does buy is a cluster that is not this laptop, and Phase 13's
optional GitOps. So the images are published, on a `v*` tag only. Merging to
main publishes nothing. Each image gets two tags — the version and the commit
sha — and **no `:latest`**: the Dockerfile pins its own bases by digest so a
moving tag cannot change the JVM under a measurement, and publishing a moving
tag of our own would hand that same problem to whoever pulls these.

Only the sha tag is genuinely immutable, since it names the commit that built
it; a force-moved git tag would republish over the version tag. The job prints
each pushed digest for that reason — the digest is the thing to pin against —
and it refuses to publish at all unless the tag matches the version in
`pom.xml` and that version is not a `-SNAPSHOT`. Otherwise `git tag v2.0.0`
would put a version number on an image that appears nowhere in the source
which built it.

### The shell scripts are checked at the strictest severity

ShellCheck runs at `--severity=style`, which reports everything it knows how
to report. The scripts in this repository are not glue: `deploy.sh` builds the
cluster and `run-recovery-experiment.sh` is the measuring instrument. Phase 11
discarded two runs over bugs in that instrument, one of them a pair of
backticks inside an unquoted heredoc — SC2006, a warning shellcheck has
emitted for years. A shell bug does not announce itself the way a failing test
does; it produces a file with the wrong contents.

Adopting the gate required one fix: a dead `GATEWAY_POD` assignment left in
the experiment runner after config capture moved to `kubectl logs
deployment/...`.

## Verified

The gate was demonstrated on the pull request that introduced it, by pointing
`MQTT_BROKER_URL` at a port nothing was listening on and pushing. The broker
container still started and reported healthy, `mvn verify` still printed
`BUILD SUCCESS`, and the run failed anyway:

```
FAIL: the suite ran 260 tests but did not run complete:
  - 11 of 260 tests were skipped: io.fleet.edge.mqtt.MqttTelemetrySinkTest
    (7/7), io.fleet.integration.HeartbeatFailureDetectionTest (2/2),
    io.fleet.integration.MqttToGatewayTest (2/2)
```

That is the whole argument for this job in one run: a healthy broker, a
successful build, and no coverage of the MQTT path. The change was reverted
and the pipeline went green again.

## Consequences

- **The pipeline is five jobs**: build-and-test (with a broker), scripts
  (ShellCheck, actionlint, and the gate's own fixtures), manifests
  (kubeconform plus a parse of the Grafana dashboard, which nothing else in
  the pipeline reads), images-and-smoke-test, and a publish job that runs on
  tags only. The first four run on every pull request.
- **A new module must be added to the Dockerfile as well as to `pom.xml`.**
  The image job builds the recovery-operator target explicitly, because
  compose builds only three of the four runtime targets and the operator is
  otherwise built only by `deploy.sh`. This is the one place CI catches a
  module that builds under Maven and fails inside Docker.
- **A test that legitimately needs to skip in CI cannot simply skip.** The
  completeness check has no allow-list, by design: adding one is a visible
  edit to this repository with a reviewer attached, which is the point.
- **The smoke test cannot run locally while the kind cluster is up.** Both
  publish the gateway on host 18080. Locally that is a port-bind failure; the
  danger it hints at is worse, since a health check that reached the *cluster's*
  gateway would pass for the wrong reason. On a clean runner the question does
  not arise, which is another argument for CI not being this machine.
- **Pinned actions need updating deliberately.** They will go stale, and that
  is the trade accepted above. The tag each SHA stood for is in a comment
  beside it so the update is a readable diff rather than an archaeology
  exercise.
