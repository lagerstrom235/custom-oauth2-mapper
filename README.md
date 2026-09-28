# ThingsBoard Custom OAuth 2.0 mapper

This project provides a sample of custom mapper of OAuth 2.0 external user info into ThingsBoard internal user object.

You can use this application as a base implementation for the Custom Mapper endpoint.
As well, you can copy any snippet of this implementation into your existent microservices.

You can combine this Custom Mapper with [ThingsBoard REST API Client](https://thingsboard.io/docs/reference/rest-client/) to get any additional information from the ThingsBoard platform as Tenant Id or Customer Id.   

## Prerequisites

Docker with BuildKit support is the only required build tool. Java 17 and Maven 3.9 run inside the builder container; no host Java, Maven, or Gradle installation is needed. Docker is the supported distribution workflow.

## Build

From the repository root:

```bash
docker build -t thingsboard/oauth2-mapper .
```

The multi-stage build runs `mvn -B -ntp verify`, including license checks and any tests, then copies the executable Spring Boot JAR into an Eclipse Temurin 17 JRE image. Maven downloads are cached across builds using a BuildKit cache mount.

## Running

Create a `.env` file in the repository root:

```dotenv
CUSTOM_MAPPER_AUTH_ENABLED=true
CUSTOM_MAPPER_AUTH_USERNAME=admin
CUSTOM_MAPPER_AUTH_PASSWORD=password
```

These are the Docker defaults. Set the username and password to your desired credentials. Supply the password as plain text without a `{noop}` prefix: the application hashes it with BCrypt itself. `.env` files are excluded from the Docker build context.

```bash
docker run --rm --env-file .env -p 10010:10010 thingsboard/oauth2-mapper
```

The application runs as a non-root user and logs to the console. Java runs directly as the container's main process and receives Docker shutdown signals. Use `docker stop <container-id>` to stop it.

To configure JVM settings, add `JAVA_TOOL_OPTIONS` to `.env`, for example:

```dotenv
JAVA_TOOL_OPTIONS=-Xms128m -Xmx512m
```

`SERVER_PORT` defaults to `10010`; if you override it, adjust the container port in `-p` accordingly.

## Validation on the Docker host

Build from a clean checkout on a host with Docker alone, then start the container using the commands above. Check startup and console logs with `docker logs <container-id>`, and confirm the non-root identity with `docker exec <container-id> id` (UID/GID `10001`).

Send this sample request, replacing the credentials if you changed `.env`:

```bash
curl -i -u admin:password \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","givenName":"Alice","familyName":"Smith"}' \
  http://localhost:10010/oauth2/mapper
```

Expect HTTP 200 with mapped fields including `email: alice@example.com`, `firstName: Alice`, `lastName: Smith`, and `tenantName: Tenant example.com`. Repeat the same request without `-u admin:password`, then with `-u admin:incorrect`; both should return HTTP 401 while authentication is enabled.

Stop the container with `docker stop <container-id>` and confirm that it exits cleanly. After a source change, rebuild with `docker build --progress=plain -t thingsboard/oauth2-mapper .`; Maven should reuse previously downloaded artifacts from the cache (provided the same builder is used and its cache has not been pruned).
