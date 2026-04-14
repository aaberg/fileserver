# fileserver

Minimal file server with two endpoints:

- Private API (`:9001`) for upload/download/delete and public URL generation (token protected)
- Public API (`:9000`) for downloading files via expiring public IDs

Typical deployment is behind a reverse proxy/load balancer (Traefik, Nginx, cloud LB) with persistent volume storage.

## Quick Start (Docker)

```bash
docker run --rm \
  -e PRIVATE_API_TOKEN=dev-token \
  -e FILESERVER_PUBLIC_BASE_URL=https://files.example.com \
  -v fileserver_data:/data/files \
  -p 9000:9000 -p 9001:9001 \
  ghcr.io/aaberg/fileserver:latest
```

Notes:

- Set `FILESERVER_PUBLIC_BASE_URL` to the external URL clients should use.
- Mount a persistent volume at `/data/files` (default storage location in the container image).
- Keep private API (`:9001`) protected by network policy/proxy rules.

## Quick Start (Docker Compose)

```yaml
services:
  fileserver:
    image: ghcr.io/aaberg/fileserver:latest
    container_name: fileserver
    environment:
      PRIVATE_API_TOKEN: ${PRIVATE_API_TOKEN}
      FILESERVER_PUBLIC_BASE_URL: https://files.example.com
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - fileserver_data:/data/files
    restart: unless-stopped

volumes:
  fileserver_data:
```

## API Usage (curl)

Upload a file through the private API:

```bash
curl -X PUT \
  -H "Authorization: Bearer dev-token" \
  --data-binary "hello" \
  http://localhost:9001/file/example.txt
```

Generate a public URL (returns JSON with `publicUrl`):

```bash
curl -X POST \
  -H "Authorization: Bearer dev-token" \
  -H "Content-Type: application/json" \
  -d '{"duration": 10}' \
  http://localhost:9001/file/example.txt/public-url
```

Download the file from the returned public URL:

```bash
curl -L "<publicUrl>"
```

Delete the private file:

```bash
curl -X DELETE \
  -H "Authorization: Bearer dev-token" \
  http://localhost:9001/file/example.txt
```

Temporary file flow:

```bash
# Upload temporary file
curl -X POST \
  -H "Authorization: Bearer dev-token" \
  --data-binary "draft-content" \
  http://localhost:9001/temp-file

# Download temporary file directly (private API)
curl -X GET \
  -H "Authorization: Bearer dev-token" \
  http://localhost:9001/temp-file/<tempFileId>

# Create public URL for temp file
curl -X POST \
  -H "Authorization: Bearer dev-token" \
  -H "Content-Type: application/json" \
  -d '{"duration": 5}' \
  http://localhost:9001/temp-file/<tempFileId>/public-url

# Promote temp file to permanent id
curl -X POST \
  -H "Authorization: Bearer dev-token" \
  http://localhost:9001/temp-file/<tempFileId>/promote/final-file.txt
```

## Health Endpoints

- Public health: `GET http://localhost:9000/health`
- Private health: `GET http://localhost:9001/health`

Both return:

```json
{"status":"ok"}
```

## Runtime Configuration

Environment variables / overrides:

- `PRIVATE_API_TOKEN` (required): bearer token required for `http://<host>:9001/file/*`.
- `FILESERVER_PUBLIC_BASE_URL` (optional): external base URL for generated public links (for example `https://files.example.com`). Falls back to `fileserver.publicBaseUrl` in `server/src/main/resources/application.yaml`.
- `FILESERVER_STORAGE_DIRECTORY` (optional): storage path for uploaded files. In the Docker image this defaults to `/data/files`. Outside Docker, it falls back to `fileserver.storageDirectory` in `server/src/main/resources/application.yaml`.
- `FILESERVER_CLEANUP_ENABLED` (optional): enables periodic cleanup of expired public URLs in the app process. Default is `false`.
- `FILESERVER_CLEANUP_INTERVAL_SECONDS` (optional): cleanup interval in seconds when cleanup is enabled. Default is `300`.
- `FILESERVER_TEMP_TTL_SECONDS` (optional): default temporary file lifetime in seconds. Default is `3600`.
- `FILESERVER_TEMP_MAX_UPLOAD_BYTES` (optional): max upload bytes for temporary file uploads. Default is `fileserver.maxUploadBytes`.
- `DB_TYPE`: `sqlite` (default) or `postgres`.
- `DB_URL`: JDBC URL. Defaults to SQLite local file DB.
- `DB_USER` and `DB_PASSWORD`: required for PostgreSQL.

App config defaults (`server/src/main/resources/application.yaml`):

- `fileserver.maxUploadBytes` default: `10485760` (10 MiB)
- `fileserver.temp.ttlSeconds` default: `3600`
- `fileserver.temp.maxUploadBytes` default: `10485760`
- `fileserver.cleanup.enabled` default: `false`
- `fileserver.cleanup.intervalSeconds` default: `300`
- `fileserver.timeouts.shutdownGracePeriodMillis` default: `5000`
- `fileserver.timeouts.shutdownTimeoutMillis` default: `15000`

## Kotlin Client Library

The repository includes a framework-agnostic Kotlin client module:

- Module: `private-api-client`
- Transport: OkHttp

```kotlin
val client = FileserverClient(
    baseUrl = "http://localhost:9001",
    bearerToken = "dev-token"
)

val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

client.uploadFile("example.txt", "hello".toByteArray())
client.uploadFile("picture.png", imageBytes, contentType = "image/png")
val publicUrl = client.createPublicUrl("example.txt", durationMinutes = 60)
```

## Development

Useful tasks:

- `./gradlew test` - run all tests
- `./gradlew build` - build all modules
- `./gradlew :server:test` - run server tests only
- `./gradlew :private-api-client:test` - run client tests only
- `./gradlew :server:nativeCompile` - build GraalVM native server binary
- `./gradlew :server:run` - run server locally

Manual endpoint verification resources:

- Requests: `manual-tests/fileserver.http`
- IntelliJ environment: `manual-tests/http-client.env.json`

## Production Notes

- Run behind HTTPS termination at your proxy/LB.
- Ensure `FILESERVER_PUBLIC_BASE_URL` matches the externally reachable URL.
- Use persistent volumes for file storage.
- Restrict access to the private API port (`9001`) to trusted callers only.
- In multi-instance deployments, keep in-process cleanup disabled and run cleanup from a single dedicated worker/CronJob.
