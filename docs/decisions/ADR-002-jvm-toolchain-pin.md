# ADR-002: Pin the JVM to HotSpot OpenJDK 21

## Status
Accepted — 2026-08-23

## Context
The development machine had four JDKs reachable at once, resolving
inconsistently:

| Entry point | Resolved to |
|---|---|
| `java` on `PATH` (via SDKMAN) | Oracle GraalVM 21.0.9 |
| `mvn` | Homebrew OpenJDK 26.0.2.1 |
| `/usr/libexec/java_home` | Oracle JDK 23.0.1 |
| `JAVA_HOME` | unset |

Six of the seven SDKMAN-managed JDKs were GraalVM builds.

Two problems follow. The mundane one is that Maven would compile against
JDK 26 while the launcher ran a 21 runtime — a class-file version break.

The serious one concerns the research. Pillar A of the thesis compares a
resource-disciplined edge-device implementation against a naive one under
an identical heap cap, measuring allocation behavior, GC activity, CPU,
throughput, and latency. GraalVM's JIT performs substantially more
aggressive escape analysis and scalar replacement than HotSpot's C2. On
allocation-heavy code — precisely the code path the experiment
discriminates on — Graal can optimize away the very allocations that
distinguish the naive variant from the constrained one. That would
produce a null result caused by the toolchain rather than by the
implementations, and it would not be visible in the output. A reader
could not tell the difference between "discipline does not matter" and
"the compiler hid the difference."

Separately, every container base image used from Phase 7 onward
(`eclipse-temurin`, `amazoncorretto`) ships HotSpot. Developing on
GraalVM locally and measuring on HotSpot in containers would mean the
Phase 1–6 measurements and the Phase 7+ measurements are not comparable,
breaking the reproducibility contract across exactly the phases where
experiments are recorded.

## Decision
Pin the project to **HotSpot OpenJDK 21.0.12.1** (LTS), the Homebrew
`openjdk@21` installation at:

```
/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

`JAVA_HOME` must be set to this path for all builds and all experiment
runs. The JVM is treated as part of the experimental setup, not as an
environment detail: every recorded run captures the full `java -version`
string per the reproducibility contract.

Eclipse Temurin 21 was the first choice, but SDKMAN's broker returns 404
for `21.0.12+1.1-tem` on `darwinarm64` (a stale arm64 catalog), and the
Homebrew cask `temurin@21` requires an interactive `sudo`. The Homebrew
`openjdk@21` build is version 21.0.12.1 — the same upstream build as the
Temurin package (`OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.pkg`) —
and is HotSpot with G1GC as the ergonomic default, matching
`eclipse-temurin:21`. The properties that motivated the choice are
therefore satisfied; only the vendor packaging differs.

Java 21 over 25/26: it is the LTS with the most battle-tested support in
the Java Operator SDK, Fabric8 Kubernetes client, and Kafka Streams — the
libraries this project depends on for its riskiest phases.

## Consequences
- Positive: Pillar A measures implementation discipline rather than JIT
  behavior, and the result is defensible under scrutiny.
- Positive: local and containerized runs share a runtime family and GC,
  so measurements remain comparable across Phase 7.
- Positive: compile and run targets agree, removing the JDK 26 / 21
  class-file mismatch.
- Negative: `JAVA_HOME` must be set explicitly, because the `PATH`
  default remains GraalVM via SDKMAN. This is a standing footgun for any
  contributor and for any experiment run from a fresh shell.
- Mitigation: once the Maven build exists (Phase 1), enforce the pin
  mechanically with `maven-enforcer-plugin` (`requireJavaVersion`) and
  `maven.compiler.release=21`, so a build on the wrong JVM fails loudly
  instead of silently producing incomparable measurements.
- Revisit if: the project deliberately adopts GraalVM native-image as a
  *studied variable* — a legitimate extension of the constrained-Java
  thesis, but one that would require its own ADR, its own experiment
  configuration, and re-baselining of all prior Pillar A results.
