# syntax=docker/dockerfile:1
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: MIT-0

# ---- Build stage -------------------------------------------------------------
FROM public.ecr.aws/docker/library/maven:3.9-amazoncorretto-21-al2023 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
 && mkdir -p target/extracted \
 && (cd target/extracted && jar -xf ../*.jar)

# ---- Runtime stage -----------------------------------------------------------
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21-al2023-headless
# Pick up OS security fixes published after the base image was built.
RUN dnf -y upgrade --security --releasever=latest && dnf clean all && rm -rf /var/cache/dnf
WORKDIR /app
COPY --from=build /build/target/extracted/BOOT-INF/lib /app/lib
COPY --from=build /build/target/extracted/META-INF /app/META-INF
COPY --from=build /build/target/extracted/BOOT-INF/classes /app

# Unprivileged user; the root filesystem is read-only at runtime, only /tmp is writable.
USER 10001:10001
EXPOSE 8080
VOLUME /tmp
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dspring.jmx.enabled=false", \
            "-cp", "/app:/app/lib/*", "com.amazonaws.samples.AwsOutboxCDCSampleApplication"]
