# ThingsBoard Custom OAuth 2.0 mapper

This project provides a sample of custom mapper of OAuth 2.0 external user info into ThingsBoard internal user object.

You can use this application as a base implementation for the Custom Mapper endpoint.
As well, you can copy any snippet of this implementation into your existent microservices.

You can combine this Custom Mapper with [ThingsBoard REST API Client](https://thingsboard.io/docs/reference/rest-client/) to get any additional information from the ThingsBoard platform as Tenant Id or Customer Id.   

## Prerequisites

Docker with BuildKit support is the only required build tool. Java 25 (LTS) and Maven 3.9 run inside the builder container; no host Java, Maven, or Gradle installation is needed. Docker is the supported distribution workflow.

## Build

From the repository root:

```bash
docker build -t thingsboard/oauth2-mapper .
```

The multi-stage build runs `mvn -B -ntp verify`, including license checks and any tests, then copies the executable Spring Boot JAR into an Eclipse Temurin 25 JRE image. Maven downloads are cached across builds using a BuildKit cache mount.

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

## Logging

Logging uses SLF4J with Logback and writes to the console at INFO level by default.
In any Java class, import `lombok.extern.slf4j.Slf4j` and annotate the class with
`@Slf4j` to use `log.info("Message: {}", value)`, `log.debug(...)`, or `log.warn(...)`.
The controller logs successful mappings at DEBUG and mapping validation failures at WARN,
without logging user attributes or credentials.

To enable DEBUG logs for the mapper, add this to `.env` and recreate the container:

```dotenv
LOGGING_LEVEL_SH_LAGERSTROM_MAPPER=DEBUG
```

View output with `docker logs -f <container-id>`. Console formatting is configured in
`src/main/resources/logback.xml`. Avoid logging OAuth tokens, credentials, or user payloads.

## YAML mapping configuration

Copy [mapper.example.yml](mapper.example.yml) to `mapper.yml` and edit the rules you need. Mount the file and load it as additional Spring configuration:

```bash
docker run --rm --env-file .env -p 10010:10010 \
  --mount type=bind,source="$(pwd)/mapper.yml",target=/config/mapper.yml,readonly \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/mapper.yml \
  thingsboard/oauth2-mapper
```

The file must be readable by container UID `10001`. Restart the container after changing it; no image rebuild is needed. A missing configured file prevents startup.

Each rule uses exactly one of `attribute` (a JSON Pointer into the incoming user object, such as `/organization/name`) or `value` (a fixed string). An optional `prefix` is prepended to names, email, or groups. For keys containing `/` or `~`, escape them as `~1` or `~0` in the pointer.

Supported rules are `email`, `first-name`, `last-name`, `tenant-name`, `tenant-id`, `customer-name`, and `customer-id`. Set `professional-edition: true` to additionally use `parent-customer-name`, `parent-customer-id`, and `user-groups`. Groups are a list of rules; each attribute can contain a string or an array of strings, which is expanded into the group list.

Configure a name **or** an ID for each tenant/customer/parent customer. IDs must be UUIDs and cannot have prefixes. Invalid configuration fails startup, including PE-only options when Professional Edition is disabled. Unknown mapping settings are rejected to catch typos.

Without configuration, the mapper reads `/email`, `/givenName`, and `/familyName`, sets the tenant name to `Tenant ` plus the mapped email domain, and leaves customer and PE fields unset. The default tenant name is used only when neither tenant rule is configured. Every configured attribute is required: missing, blank, or incorrectly typed values, invalid request UUIDs, and malformed JSON return HTTP 400. Authentication remains controlled by `CUSTOM_MAPPER_AUTH_*`.

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

## License and provenance

This sh.lagerstrom fork is licensed under the [Apache License, Version 2.0](LICENSE).
It derives from the ThingsBoard Custom OAuth 2.0 mapper; the original ThingsBoard
copyright and license notices are retained. Changes in this fork include
configuration-based user mapping, the `sh.lagerstrom.mapper` namespace, and build
and runtime updates. Modified source files carry modification notices.

The Maven JAR includes the license under `META-INF/LICENSE` (inside `BOOT-INF/classes`
in the executable Spring Boot JAR), and the Docker image includes `/app/LICENSE`.
