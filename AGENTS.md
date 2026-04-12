## Stack

- Gradle multi-module Kotlin repo: `:server` (Ktor/CIO app) and `:private-api-client` (OkHttp client library).
- Toolchain is pinned high: Kotlin `2.3.0`, Ktor `3.4.2`, Gradle wrapper `9.4.1`, JVM toolchain `25`. CI uses Temurin JDK 25.

## Entry Points

- Server entrypoint is `server/src/main/kotlin/Application.kt` via `startServers()`.
- One process starts two embedded CIO servers: public API defaults to `9000` and private API defaults to `9001`; configure them with `fileserver.publicPort` and `fileserver.privatePort` in `server/src/main/resources/application.yaml`.
- Public routes live in `server/src/main/kotlin/routes/PublicRoutes.kt`; private/authenticated routes live in `server/src/main/kotlin/routes/PrivateRoutes.kt`.

## Run And Verify

- CI parity is `./gradlew --no-daemon build`. There is no separate lint/typecheck setup in this repo.
- Focused module checks:
  - `./gradlew :server:test`
  - `./gradlew :private-api-client:test`
  - `./gradlew :server:run`
  - `./gradlew :server:nativeCompile`
- Manual HTTP checks are already scripted for IntelliJ HTTP client in `manual-tests/fileserver.http` with env values in `manual-tests/http-client.env.json`.
- `test_server.sh` starts `:server:run` with `PRIVATE_API_TOKEN` and does a quick curl smoke test; it is not part of CI.

## Runtime Quirks

- `PRIVATE_API_TOKEN` is mandatory at runtime; `:server:run` fails fast without it.
- `FILESERVER_PUBLIC_BASE_URL` and `FILESERVER_STORAGE_DIRECTORY` can be overridden by system properties/env vars; code trims trailing `/`.
- Database config is driven by `DB_*` system properties/env vars in `DatabaseFactory`; default is SQLite at `jdbc:sqlite:fileserver.db`.
- PostgreSQL requires both `DB_USER` and `DB_PASSWORD`; otherwise startup throws.
- Cleanup is in-process and disabled by default. In multi-instance deployments, keep it disabled except in one dedicated worker.

## Testing Quirks

- Server tests commonly set `DB_TYPE` / `DB_URL` as JVM system properties; property lookup takes precedence over env lookup.
- Most server tests use temporary dirs or SQLite in-memory/temp-file databases. Preserve that isolation when adding tests.
- Route tests use Ktor `testApplication`; client tests use `MockWebServer`.

## Build And Release

- Docker image build runs `./gradlew :server:nativeCompile` and skips tests in the Dockerfile; do not treat Docker build success as test coverage.
- GitHub release tag format must be `vX.Y.Z`; `release-client.yml` strips the leading `v` and publishes only `:private-api-client`.
- `release-image.yml` publishes GHCR tags for the release tag and `latest` only for non-prereleases.
