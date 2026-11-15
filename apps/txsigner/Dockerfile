# syntax=docker/dockerfile:1
# Image of the TxSigner (ADR-010): only the Java 21 JRE and the JAR, non-root user, base images pinned by digest (same
# digests as apps/api). The kms-sim is NOT in this image: it is a separate test-only container (NFR1.6).

FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN sh ./mvnw -B -ntp -DskipTests -Djacoco.skip=true -Dspotless.check.skip=true package

FROM eclipse-temurin:21-jre-alpine@sha256:b48e02e0ea88d7bfb305335df94923bdef394905f4147dccd52e873cf17b1088
RUN addgroup -S txsigner && adduser -S -G txsigner -H -s /sbin/nologin txsigner
WORKDIR /app
COPY --from=build --chown=txsigner:txsigner /src/target/memetrade-txsigner.jar /app/app.jar
USER txsigner
EXPOSE 8443 8444
ENV JAVA_TOOL_OPTIONS="-Xmx384m -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=5s --retries=6 --start-period=60s \
  CMD wget -q -O - http://127.0.0.1:8444/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
