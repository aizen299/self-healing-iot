# ADR-005: Gateway dependencies and the health API

## Status
Accepted — 2026-08-23

## Context
The gateway is the first component that consumes rather than produces. It
needs to turn a JSON payload back into a reading, reject anything
malformed, and expose fleet state over HTTP. Both raise the same question:
how much framework to take on.

The pressure here is different from the edge device's. The device is the
subject of the constrained-Java measurement, so its dependencies distort
the thing being studied. The gateway is not measured that way — but it
*is* measured in Phase 8 for CPU and memory under load, and it has to run
next to Kafka, a time-series database, Prometheus, and Grafana inside 8 GB
(ADR-003). Its footprint is a real constraint even though it is not a
Pillar A subject.

## Decision

### Jackson streaming (`jackson-core`), not databind

`jackson-core` 2.17.2 is a single 572 KB jar with no transitive
dependencies. `jackson-databind` would add two more and bring reflective
binding the project does not need: the payload is flat, fixed, and
specified in `docs/api/mqtt-topics.md`.

Field-by-field streaming also gives exact control over what counts as
malformed, which matters because rejecting bad input correctly is one of
the gateway's stated jobs. The parser distinguishes three failures that
databind would blur together: unparseable JSON, a missing known field, and
a known field of the wrong type. Unknown fields are skipped so a later
phase can add one without breaking existing consumers.

Writing a JSON parser by hand was considered and rejected. The format is
simple enough that it looks tempting, but handling malformed input is
precisely the part that is easy to get subtly wrong, and it is the part
this component exists to do well.

### The JDK's `HttpServer`, not a web framework

Three read-only endpoints do not justify Spring Boot or Javalin and their
dependency trees. `com.sun.net.httpserver` ships with the JDK, costs
nothing to add, and starts in milliseconds.

This is a deliberate guard against a specific failure mode for this
project: a gateway that pulls in a web framework becomes the largest
process in the stack, and the Phase 8 memory measurements would then be
measuring the framework.

### A format round-trip test across the module boundary

The device serializes with a hand-rolled fixed-point encoder; the gateway
parses with Jackson. Neither module's own tests would notice if the two
stopped agreeing, and the symptom would look like devices going silent
rather than like a format change.

`tests/integration` therefore runs both device variants through the
gateway's parser and asserts the parsed readings match. This is the
cross-boundary counterpart to `VariantPayloadEqualityTest`, which guards
the same contract within `edge-device`.

## Consequences
- Positive: the gateway adds two jars totalling under 1 MB, so it stays a
  small process in a stack that has to fit in 8 GB.
- Positive: malformed and invalid payloads are counted separately, so the
  health endpoint distinguishes a producer speaking the wrong format from
  a sensor reporting impossible values.
- Positive: the round-trip test makes wire-format drift a build failure
  rather than a silent outage.
- Negative: the JDK HTTP server has no routing, content negotiation, or
  middleware. Path handling is manual, and it would not be the right basis
  for a large API. Acceptable for a status endpoint; revisit if the
  gateway ever needs a real control API.
- Negative: adding a telemetry field means touching the parser by hand.
  With eight fields this is trivial, and the missing-field check means an
  omission fails loudly rather than defaulting to zero.
- Revisit if: the gateway needs to accept writes, serve many endpoints, or
  negotiate content types.
