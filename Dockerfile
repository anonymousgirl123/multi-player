# ─────────────────────────────────────────────────────────────────────────────
# Multi-stage Dockerfile for player-messaging
#
# Stage 1  builder  – compile the source and produce the fat jars via Maven
# Stage 2  runtime  – copy only the two runnable jars into a slim JRE image
#
# Why multi-stage?
# The builder stage pulls in the full JDK and the Maven cache (~hundreds of MB).
# The runtime stage needs only a JRE and the two jars (~a few MB).  Multi-stage
# keeps the final image small, which speeds up pulls and reduces the attack
# surface (no compiler, no source code, no Maven in production).
#
# Usage (same-process scenario — single container):
#   docker build -t player-messaging .
#   docker run --rm player-messaging same-process
#   docker run --rm player-messaging same-process 3   # 3-player ring
#
# Usage (multi-process scenario — two containers via docker-compose):
#   docker compose up
#   # or manually:
#   docker run --rm -p 9090:9090 player-messaging responder &
#   docker run --rm --network host player-messaging initiator
#
# See docker-compose.yml for the automated two-container wiring.
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: builder ─────────────────────────────────────────────────────────
#
# eclipse-temurin is the de-facto standard OpenJDK distribution on Docker Hub.
# We pin to 21-jdk-alpine (smallest footprint that still carries a full JDK).
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy the Maven wrapper scripts and the wrapper properties first.
# Docker layer caching: this layer only changes when the wrapper or its
# properties change — not on every source edit.
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn/

# Copy the POM next.  Again, this layer changes less frequently than source
# code, so dependency downloads are cached across source-only changes.
COPY pom.xml ./

# Download all dependencies into the local repository inside the image.
# --no-transfer-progress suppresses the noisy progress bars in CI logs.
# -B = batch mode (no colour codes, no interactive prompts).
RUN ./mvnw dependency:go-offline -B --no-transfer-progress

# Now copy the full source tree.  This layer changes on every source edit.
COPY src src/

# Build all fat jars, skipping tests (tests are run separately in CI).
# -DskipTests does NOT skip compilation of test sources; use -Dmaven.test.skip
# if you want to skip test compilation too (not recommended — keeps compile-time
# checks active).
RUN ./mvnw package -B --no-transfer-progress -DskipTests


# ── Stage 2: runtime ─────────────────────────────────────────────────────────
#
# eclipse-temurin:21-jre-alpine is the smallest image that can run Java 21
# bytecode.  It contains the JRE but not the JDK compiler tools.
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as a non-root user.
# Why?  If the container process is compromised, an attacker ends up as a
# low-privilege user inside the container rather than as root.  This limits
# the blast radius of any container-escape exploit.
RUN addgroup -S player && adduser -S player -G player
USER player

WORKDIR /app

# Copy only the two runnable fat jars from the builder stage.
# The benchmark jar and the non-shaded library jar are intentionally excluded.
COPY --from=builder /build/target/player-same-process.jar  player-same-process.jar
COPY --from=builder /build/target/player-multi-process.jar player-multi-process.jar

# Copy the configuration file so operators can override it with a volume mount:
#   docker run -v ./my-config.properties:/app/configuration.properties player-messaging same-process
# The ConfigLoader checks the working directory first, then falls back to the
# classpath bundle already embedded in the jar.
COPY src/main/resources/configuration.properties configuration.properties

# Expose the TCP port used by the multi-process responder.
# EXPOSE is documentation only; it does not actually publish the port.
# Use -p 9090:9090 or the ports: directive in docker-compose.yml to bind it.
EXPOSE 9090

# The ENTRYPOINT wraps the JVM call; CMD supplies the default argument.
#
# Why split ENTRYPOINT + CMD instead of using CMD alone?
# This pattern lets operators override the scenario (same-process / responder /
# initiator) cleanly at `docker run` time without having to repeat the java
# invocation:
#
#   docker run player-messaging same-process        # default
#   docker run player-messaging responder           # multi-process responder
#   docker run player-messaging initiator           # multi-process initiator
#
# The entrypoint script selects the correct jar based on the first argument.
COPY --chown=player:player docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["same-process"]
