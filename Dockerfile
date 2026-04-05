# Dockerfile for GraalVM native compilation of FileServer

# Build stage - Use official GraalVM Community Edition with Java 25
FROM ghcr.io/graalvm/graalvm-community:25.0.2 AS build

# Native-image is already included in GraalVM Community Edition

# Copy source code
WORKDIR /app
COPY . .

# Build with Gradle (skip tests for Docker build)
RUN ./gradlew :server:nativeCompile
RUN test -x /app/server/build/native/nativeCompile/fileserver

# Runtime stage - Use Debian slim for glibc compatibility
FROM debian:bookworm-slim

# Install required runtime dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    libc6 \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create non-root runtime user
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

# Copy native binary
WORKDIR /app
COPY --from=build /app/server/build/native/nativeCompile/fileserver ./fileserver

# Copy configuration
COPY server/src/main/resources/application.yaml .

# Ensure runtime user can read/write app data
RUN chown -R appuser:appuser /app

# Expose ports
EXPOSE 9000 9001

# Set environment variables for SQLite (default)
ENV DB_TYPE=sqlite
ENV DB_URL=jdbc:sqlite:fileserver.db

# Run as non-root user
USER appuser

# Container health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:9000/health || exit 1

# Run the application
CMD ["./fileserver"]
