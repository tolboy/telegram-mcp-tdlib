# ═══════════════════════════════════════════════════════════════════════════════
# Multi-stage Dockerfile for Telegram MCP Server
# ═══════════════════════════════════════════════════════════════════════════════

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# Cache Gradle wrapper
COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew gradlew
RUN chmod +x gradlew && ./gradlew --version

# Cache dependencies
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN ./gradlew dependencies --no-daemon || true

# Build the application
ARG APP_VERSION
COPY src/ src/
RUN if [ -n "$APP_VERSION" ]; then \
            ./gradlew bootJar --no-daemon -x test -PreleaseVersion="$APP_VERSION"; \
        else \
            ./gradlew bootJar --no-daemon -x test; \
        fi

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre AS runtime

LABEL io.modelcontextprotocol.server.name="io.github.tolboy/telegram-mcp-tdlib"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system mcp \
    && useradd --system --gid mcp mcp

WORKDIR /app

COPY --from=build /app/build/libs/telegram-mcp-server.jar app.jar
COPY docker/healthcheck.sh /usr/local/bin/telegram-mcp-healthcheck

RUN chmod 0555 /usr/local/bin/telegram-mcp-healthcheck \
    && mkdir -p /data/tdlib-data /data/downloads \
    && chown -R mcp:mcp /app /data
USER mcp

ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom --enable-native-access=ALL-UNNAMED" \
    SERVER_ADDRESS=0.0.0.0 \
    TELEGRAM_MCP_DATA_DIR=/data/tdlib-data \
    TDLIB_DATA_DIR=/data/tdlib-data \
    TDLIB_DOWNLOADS_DIR=/data/downloads
VOLUME ["/data/tdlib-data", "/data/downloads"]

# Actuator health check for the HTTP transport; a no-op under STDIO, which has
# no listener to probe and would otherwise report unhealthy for its whole life.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD ["/usr/local/bin/telegram-mcp-healthcheck"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

# Registry/local-client variant: same signed image contents, stdio by default.
FROM runtime AS stdio-runtime
ENV MCP_TRANSPORT=stdio
HEALTHCHECK NONE

# Keep the default build target backwards-compatible with Streamable HTTP.
FROM runtime AS final
