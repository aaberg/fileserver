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
  -e FILESERVER_STORAGE_DIRECTORY=/data/files \
  -v fileserver_data:/data/files \
  -p 9000:9000 -p 9001:9001 \
  ghcr.io/<owner>/<repo>:latest
```

Notes:

- Set `FILESERVER_PUBLIC_BASE_URL` to the external URL clients should use.
- Mount a persistent volume and point `FILESERVER_STORAGE_DIRECTORY` at that mount.
- Keep private API (`:9001`) protected by network policy/proxy rules.

## Quick Start (Docker Compose)

```yaml
services:
  fileserver:
    image: ghcr.io/<owner>/<repo>:latest
    container_name: fileserver
    environment:
      PRIVATE_API_TOKEN: ${PRIVATE_API_TOKEN}
      FILESERVER_PUBLIC_BASE_URL: https://files.example.com
      FILESERVER_STORAGE_DIRECTORY: /data/files
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
- `FILESERVER_STORAGE_DIRECTORY` (optional): storage path for uploaded files (for example `/data/files`). Falls back to `fileserver.storageDirectory` in `server/src/main/resources/application.yaml`.
- `DB_TYPE`: `sqlite` (default) or `postgres`.
- `DB_URL`: JDBC URL. Defaults to SQLite local file DB.
- `DB_USER` and `DB_PASSWORD`: required for PostgreSQL.

App config defaults (`server/src/main/resources/application.yaml`):

- `fileserver.maxUploadBytes` default: `10485760` (10 MiB)
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

client.uploadFile("example.txt", "hello".toByteArray())
val publicUrl = client.createPublicUrl("example.txt", durationSeconds = 60)
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
