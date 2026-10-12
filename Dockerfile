# syntax=docker/dockerfile:1
# Modified in the sh.lagerstrom fork: runtime/build updates and license distribution.

FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

COPY pom.xml license-header-template.txt LICENSE ./
COPY src ./src
RUN mvn -B -ntp verify -Dlicense.skip=true

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --gid 10001 thingsboard \
    && useradd --uid 10001 --gid thingsboard --no-create-home --shell /usr/sbin/nologin thingsboard

COPY --from=builder /build/target/oauth2-mapper.jar /app/oauth2-mapper.jar

COPY LICENSE /app/LICENSE

USER 10001:10001
EXPOSE 10010
ENTRYPOINT ["java", "-jar", "/app/oauth2-mapper.jar"]
