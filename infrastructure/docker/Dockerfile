# syntax=docker/dockerfile:1
#
# One file, two runtime targets. The dependency-resolution stage lists every
# module pom, and keeping that list in a single place matters: Phase 6 adds a
# stream processor and Phase 9 a recovery operator, and a module missing from
# one of two near-identical Dockerfiles fails only for that image and only on
# a clean cache.
#
# Base images are pinned by digest, not by tag. ADR-002 pins the JVM because a
# different build can change the allocation and GC behaviour Pillar A measures,
# and a floating tag quietly undoes that: `eclipse-temurin:21-jre` moves with
# every Temurin release, so two Phase 8 runs weeks apart would use different
# JVMs while both claiming to be pinned.

# maven:3.9-eclipse-temurin-21
FROM maven@sha256:613124833fa6718ded9d655a2ebfab6425818c178f899116b93560b6f1c9ffe9 AS deps
WORKDIR /src

# Poms only, on their own layer. Dependencies change rarely and source changes
# constantly, so this keeps a code edit from re-resolving the world.
COPY pom.xml .
COPY common/pom.xml common/
COPY edge-device/pom.xml edge-device/
COPY gateway/pom.xml gateway/
COPY stream-processor/pom.xml stream-processor/
COPY tests/integration/pom.xml tests/integration/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# ---- build ------------------------------------------------------------
FROM deps AS build
COPY common/src common/src
COPY edge-device/src edge-device/src
COPY gateway/src gateway/src
COPY stream-processor/src stream-processor/src
# Tests are skipped here deliberately: the suite needs a broker, and an image
# build is not the place to discover that. CI runs them against a real one.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---- shared runtime ---------------------------------------------------
# eclipse-temurin:21-jre
FROM eclipse-temurin@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037 AS runtime-base

# curl is installed rather than assumed. It happens to ship in this base, but
# the healthcheck depends on it and a slimmer base — jlink or alpine, both
# worth doing before any registry push — would remove it and leave the
# container reporting unhealthy while the application was fine.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Unprivileged, and the working directory is writable by that user in both
# images. The device writes nothing today, but that is a fact about the
# current implementation rather than a property of the image.
RUN groupadd --system fleet && useradd --system --gid fleet --home /app fleet
WORKDIR /app
RUN mkdir -p /app/data && chown -R fleet:fleet /app
USER fleet

# ---- gateway ----------------------------------------------------------
FROM runtime-base AS gateway
COPY --from=build --chown=fleet:fleet /src/gateway/target/dependency/ ./dependency/
COPY --from=build --chown=fleet:fleet /src/gateway/target/gateway-*.jar ./gateway.jar

EXPOSE 8080

# GATEWAY_STORE_PATH is not set here. StoreConfig already defaults to
# ./data/fleet, which resolves to /app/data under this WORKDIR, so an ENV
# would be a second definition of the path the volume has to match.
ENV GATEWAY_HTTP_HOST=0.0.0.0 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Probes the gateway's own endpoint rather than the port: a listening socket
# only proves the JVM started, not that it is ingesting.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/health >/dev/null || exit 1"]

# "$@" so arguments appended to docker run or compose reach the application
# instead of being silently discarded.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/gateway.jar \"$@\"", "--"]

# ---- edge device ------------------------------------------------------
FROM runtime-base AS edge-device
COPY --from=build --chown=fleet:fleet /src/edge-device/target/dependency/ ./dependency/
COPY --from=build --chown=fleet:fleet /src/edge-device/target/edge-device-*.jar ./edge-device.jar

# No MaxRAMPercentage default. The heap cap is Pillar A's independent
# variable, so whatever runs the experiment states it explicitly — a default
# would quietly become part of the measurement.
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/edge-device.jar \"$@\"", "--"]

# ---- stream processor -------------------------------------------------
FROM runtime-base AS stream-processor
COPY --from=build --chown=fleet:fleet /src/stream-processor/target/dependency/ ./dependency/
COPY --from=build --chown=fleet:fleet /src/stream-processor/target/stream-processor-*.jar ./stream-processor.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/stream-processor.jar \"$@\"", "--"]
