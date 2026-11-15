# syntax=docker/dockerfile:1
# kms-sim: TEST-ONLY simulator of the AWS KMS JSON protocol (infra/kms-sim/KmsSim.java). Used only by
# infra/docker-compose.test.yml; never in a production image. Same pinned JDK image as the build stage of apps/api.
FROM eclipse-temurin:21-jdk-alpine@sha256:fd52084c35bbac78890d95d57707d31ffc9d7a3324199e89c06be2a9dc71bfa0
RUN addgroup -S kmssim && adduser -S -G kmssim -H -s /sbin/nologin kmssim
WORKDIR /app
COPY KmsSim.java /app/KmsSim.java
USER kmssim
EXPOSE 4599
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
  CMD wget -q -O - http://127.0.0.1:4599/health || exit 1
ENTRYPOINT ["java", "-Xmx96m", "/app/KmsSim.java"]
