FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
RUN apk add --no-cache curl && addgroup -S gym && adduser -S -G gym gym
ARG BUILD_REVISION=unknown
ENV BUILD_REVISION=${BUILD_REVISION}
LABEL org.opencontainers.image.revision=${BUILD_REVISION}
WORKDIR /app
COPY --chown=gym:gym build/libs/app.jar app.jar
USER gym
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 CMD curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
