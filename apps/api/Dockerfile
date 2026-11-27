# syntax=docker/dockerfile:1
# Multi-stage image of the API: the runtime stage has only the Java 21 JRE and the JAR, runs as a non-root user,
# and every base image is pinned by digest (infrastructure-specification.md, Q1). Dependabot updates the digests.

FROM eclipse-temurin:25-jdk-alpine@sha256:22edbb1021420ae6e248519d1af07577742314d67d4c60fcbb2e285b7e47fa78 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN sh ./mvnw -B -ntp -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true package

FROM eclipse-temurin:25-jre-alpine@sha256:69c2a26e9894016e274c880fa542956d005b3755f84db765145185e87405bd89
# 261113-trading-real-bot U5 (BR1.4, security-design.md "CI"): the commit of the image; the gates of the REAL read the
# CI of exactly this commit. Without the argument it stays empty (local build): the gates block CI_UNREACHABLE.
ARG BUILD_SHA=
ENV MEMETRADE_BUILD_SHA=$BUILD_SHA
RUN addgroup -S memetrade && adduser -S -G memetrade -H -s /sbin/nologin memetrade
WORKDIR /app
COPY --from=build --chown=memetrade:memetrade /src/target/memetrade-api.jar /app/app.jar
USER memetrade
EXPOSE 8080 8081
# Heap capped at 512 MB inside the 1 GB container limit.
ENV JAVA_TOOL_OPTIONS="-Xmx512m -XX:+ExitOnOutOfMemoryError"
# HealthProbe is packaged in the JAR: the slim image has no curl or wget.
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=60s \
  CMD ["java", "-Dloader.main=com.memetrade.ops.HealthProbe", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
