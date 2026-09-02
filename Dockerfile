FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Cache dependencies in their own layer.
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S bot && adduser -S bot -G bot \
    && mkdir -p /data && chown bot:bot /data
COPY --from=build /build/target/tgbot.jar /app/tgbot.jar
USER bot
VOLUME /data
ENV DB_PATH=/data/bot.db
ENTRYPOINT ["java", "-jar", "/app/tgbot.jar"]
