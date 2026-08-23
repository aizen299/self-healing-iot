# syntax=docker/dockerfile:1

# ---- build ------------------------------------------------------------
# Temurin 21, the same HotSpot the project is pinned to by ADR-002 — so the
# enforcer's JVM rules pass inside the image exactly as they do on the host.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Poms first, resolved on their own layer. Dependencies change rarely and
# source changes constantly, so this keeps a code edit from re-downloading
# the world on every rebuild.
COPY pom.xml .
COPY common/pom.xml common/
COPY edge-device/pom.xml edge-device/
COPY gateway/pom.xml gateway/
COPY tests/integration/pom.xml tests/integration/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl common,gateway -am dependency:go-offline

COPY common/src common/src
COPY gateway/src gateway/src
# Tests are skipped here on purpose: the suite needs a broker, and the image
# build is not the place to discover that. CI runs them against a real one.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl common,gateway -am package -DskipTests

# ---- runtime ----------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Unprivileged: nothing here needs root, and the gateway is reachable from
# the network.
RUN groupadd --system fleet && useradd --system --gid fleet --home /app fleet
WORKDIR /app

COPY --from=build /src/gateway/target/dependency/ ./dependency/
COPY --from=build /src/gateway/target/gateway-*.jar ./gateway.jar

# The store writes here; declared so a compose volume can own it.
RUN mkdir -p /app/data && chown -R fleet:fleet /app
USER fleet

EXPOSE 8080

ENV GATEWAY_HTTP_HOST=0.0.0.0 \
    GATEWAY_STORE_PATH=/app/data/fleet \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Uses the gateway's own health endpoint rather than a port probe: a
# listening socket only proves the JVM started, not that it is ingesting.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD ["sh", "-c", "curl -fsS http://127.0.0.1:8080/health >/dev/null || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/gateway.jar"]
