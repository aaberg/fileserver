# fileserver

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor GitHub page](https://github.com/ktorio/ktor)
- The [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). You'll need to [request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up) to join.

## Features

Here's a list of features included in this project:

| Name                                               | Description                                                 |
| ----------------------------------------------------|------------------------------------------------------------- |
| [Routing](https://start.ktor.io/p/routing-default) | Allows to define structured routes and associated handlers. |

## Building & Running

To build or run the project, use one of the following tasks:

| Task                                    | Description                                                          |
| -----------------------------------------|---------------------------------------------------------------------- |
| `./gradlew test`                        | Run the tests                                                        |
| `./gradlew build`                       | Build everything                                                     |
| `./gradlew buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `./gradlew buildImage`                  | Build the docker image to use with the fat JAR                       |
| `./gradlew publishImageToLocalRegistry` | Publish the docker image locally                                     |
| `./gradlew run`                         | Run the server                                                       |
| `./gradlew runDocker`                   | Run using the local docker image                                     |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Private API Authentication

Private endpoints under `/file/*` are served by the private server on port `9001` and require a bearer token.
Public routes on port `9000` do not use this `Authorization` header requirement.

- Set `PRIVATE_API_TOKEN` before starting the server.
- Send `Authorization: Bearer <token>` only on requests to the private server (`http://localhost:9001/file/*`).

Example:

```bash
PRIVATE_API_TOKEN=my-secret-token ./gradlew run
```

```bash
curl -X PUT \
  -H "Authorization: Bearer my-secret-token" \
  --data-binary "hello" \
  http://localhost:9001/file/example.txt
```

## Health Endpoints

Both servers expose an open health check endpoint:

- Public server: `GET http://localhost:9000/health`
- Private server: `GET http://localhost:9001/health`

Each endpoint returns:

```json
{"status":"ok"}
```

The root endpoint (`GET /`) is not used for health checks.

## Runtime Configuration

- `PRIVATE_API_TOKEN` (required): bearer token used for all `/file/*` private API calls.
- `DB_TYPE`: `sqlite` (default) or `postgres`.
- `DB_URL`: JDBC URL. Defaults to `jdbc:sqlite:fileserver.db` for SQLite.
- `DB_USER` and `DB_PASSWORD`: required when `DB_TYPE=postgres`.
- `fileserver.maxUploadBytes`: max upload size in bytes (default `10485760`).
- `fileserver.timeouts.shutdownGracePeriodMillis`: graceful stop window in ms (default `5000`).
- `fileserver.timeouts.shutdownTimeoutMillis`: hard stop timeout in ms (default `15000`).

## Manual Verification (IntelliJ HTTP Client)

This repository includes a ready-to-use IntelliJ HTTP client file:

- Requests file: `manual-tests/fileserver.http`
- Environment file: `manual-tests/http-client.env.json`

Quick start:

1. Start the server locally:

```bash
PRIVATE_API_TOKEN=dev-token ./gradlew run
```

2. In IntelliJ, open `manual-tests/fileserver.http`.
3. Select the `local` environment from `manual-tests/http-client.env.json`.
4. Run requests top-to-bottom to verify:
   - public/private health endpoints
   - authenticated file upload/download
   - public URL creation and access
   - expected 401 and 400 negative cases

## Private API Client Library

The repository includes a framework-agnostic Kotlin client module for the private API:

- Module: `private-api-client`
- Transport: OkHttp

Example usage:

```kotlin
val client = FileserverClient(
    baseUrl = "http://localhost:9001",
    bearerToken = "dev-token"
)

client.uploadFile("example.txt", "hello".toByteArray())
val publicUrl = client.createPublicUrl("example.txt", durationSeconds = 60)
```
