# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml license-header-template.txt ./
COPY src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --gid 10001 thingsboard \
    && useradd --uid 10001 --gid thingsboard --no-create-home --shell /usr/sbin/nologin thingsboard

COPY --from=builder /build/target/oauth2-mapper.jar /app/oauth2-mapper.jar

USER 10001:10001
EXPOSE 10010
ENTRYPOINT ["java", "-jar", "/app/oauth2-mapper.jar"]
