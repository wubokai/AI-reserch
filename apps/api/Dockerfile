FROM maven:3.9.15-eclipse-temurin-26-alpine AS build

WORKDIR /repo/apps/api
COPY apps/api/pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY apps/api/src ./src
COPY packages/shared-schemas/llm /repo/packages/shared-schemas/llm
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/api-*.jar /repo/app.jar

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache font-noto-cjk

RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build --chown=spring:spring /repo/app.jar /app/app.jar

USER spring:spring
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Duser.timezone=UTC", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/app.jar"]
