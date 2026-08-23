# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY pom.xml .
COPY common/pom.xml common/
COPY edge-device/pom.xml edge-device/
COPY gateway/pom.xml gateway/
COPY tests/integration/pom.xml tests/integration/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl common,edge-device -am dependency:go-offline

COPY common/src common/src
COPY edge-device/src edge-device/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl common,edge-device -am package -DskipTests

FROM eclipse-temurin:21-jre AS runtime

RUN groupadd --system fleet && useradd --system --gid fleet --home /app fleet
WORKDIR /app

COPY --from=build /src/edge-device/target/dependency/ ./dependency/
COPY --from=build /src/edge-device/target/edge-device-*.jar ./edge-device.jar

USER fleet

# No MaxRAMPercentage default here. The constrained variant's heap cap is the
# independent variable of Pillar A, so it is always set explicitly by whatever
# is running the experiment — a default would silently become part of the
# measurement.
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/edge-device.jar"]
