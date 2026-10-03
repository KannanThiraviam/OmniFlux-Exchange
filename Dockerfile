# =============================================================================
# OmniFlux-Exchange
# =============================================================================
# Two stages so the runtime image carries no Maven, no source and no build cache.
# The runtime stage runs as non-root against a READ-ONLY root filesystem
# (see docker-compose.yml) — the zero-disk claim is enforced by the container,
# not asserted by the process.
# =============================================================================

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Dependency layer first: source edits do not re-download the world.
COPY pom.xml .
COPY mvnw ./mvnw
COPY .mvn ./.mvn
RUN chmod +x ./mvnw && ./mvnw -B -q dependency:go-offline

COPY src ./src
COPY config ./config
RUN ./mvnw -B -q clean package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted


FROM eclipse-temurin:25-jre-alpine AS runtime

ARG VERSION=0.1.0-SNAPSHOT
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="OmniFlux Exchange" \
      org.opencontainers.image.description="Bounded-memory asynchronous export service" \
      org.opencontainers.image.source="https://github.com/KannanThiraviam/OmniFlux-Exchange" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN addgroup -S omniflux && adduser -S -G omniflux omniflux
WORKDIR /app

COPY --from=build --chown=omniflux:omniflux /build/extracted/dependencies/ ./
COPY --from=build --chown=omniflux:omniflux /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=omniflux:omniflux /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=omniflux:omniflux /build/extracted/application/*.jar ./app.jar
USER omniflux

EXPOSE 8080

# The pinned Temurin Alpine runtime includes BusyBox wget (verified against
# this image), so the image health check does not need curl or a shell package.
HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=40s \
    CMD wget -q -O- http://localhost:8080/actuator/health >/dev/null 2>&1 || exit 1

# Keep image defaults aligned with the validated 512 MiB container budget.
ENV JAVA_TOOL_OPTIONS="-Xmx320m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError -XX:NativeMemoryTracking=summary"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
