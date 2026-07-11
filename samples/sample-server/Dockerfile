# Multi-stage build for the sample server.
#
# Stage 1 — `builder`: invoke Gradle with the multi-module repo as the build context and
# produce a self-contained distribution under build/install/sample-server/.
#
# Stage 2 — `runtime`: copy the distribution into a slim JRE image. No JDK at runtime, no
# Gradle, no source. The result is ~150 MB, suitable for a public registry pull.
#
# Build context: this Dockerfile expects the build context root to be the repository root
# (so the multi-module `gradlew` can resolve sibling projects). The accompanying
# docker-compose.yml sets `context: ../..` to satisfy that.

# ---- Stage 1: build ----------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /workspace

# Copy the wrapper first so the layer caches before sources change.
COPY gradlew /workspace/gradlew
COPY gradle /workspace/gradle
COPY gradle.properties /workspace/gradle.properties

# Copy build scripts and version catalog. Layer ordering: scripts before sources so that
# editing a Kotlin source does not invalidate the dependency-resolution layer.
COPY build.gradle.kts settings.gradle.kts /workspace/
COPY build-logic /workspace/build-logic
COPY detekt.yml /workspace/

# Copy module sources. Listed explicitly so changes to unrelated modules do not bust this
# layer when building the sample server image alone.
COPY protocol /workspace/protocol
COPY protocol-fixtures /workspace/protocol-fixtures
COPY runtime /workspace/runtime
COPY server /workspace/server
COPY widgets-core /workspace/widgets-core
COPY widgets-forms /workspace/widgets-forms
COPY widgets-media /workspace/widgets-media
COPY widgets-media-coil /workspace/widgets-media-coil
COPY transport-http /workspace/transport-http
COPY transport-cache /workspace/transport-cache
COPY transport-live /workspace/transport-live
COPY tooling-cli /workspace/tooling-cli
COPY tooling-preview /workspace/tooling-preview
COPY tooling-snapshot /workspace/tooling-snapshot
COPY tooling-telemetry /workspace/tooling-telemetry
COPY tooling-telemetry-otel /workspace/tooling-telemetry-otel
COPY tooling-testing /workspace/tooling-testing
COPY samples /workspace/samples
COPY benchmarks /workspace/benchmarks
COPY protocol-snapshot.json /workspace/

RUN chmod +x /workspace/gradlew \
 && /workspace/gradlew :samples:sample-server:installDist --no-daemon -x test -x detekt

# ---- Stage 2: runtime --------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime

# Run as a non-root user.
RUN addgroup -S sdui && adduser -S -G sdui sdui

WORKDIR /app
COPY --from=builder /workspace/samples/sample-server/build/install/sample-server /app

USER sdui
EXPOSE 8080

# JVM tuning: container-aware heap sizing, exit on OOM so the orchestrator restarts us.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

ENTRYPOINT ["/app/bin/sample-server"]
