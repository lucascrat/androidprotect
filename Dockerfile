# Stage 1: Build the Ktor application Fat JAR
FROM gradle:8.7-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN ./gradlew :server:build --no-daemon

# Stage 2: Tiny runtime environment
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the Fat JAR from build stage
COPY --from=build /home/gradle/src/server/build/libs/server.jar /app/server.jar

# Copy static frontend assets so they are served correctly at relative paths
COPY --from=build /home/gradle/src/server/src/main/resources/static /app/server/src/main/resources/static

# Persistent Volume for photos and audio recordings uploads
RUN mkdir -p /app/uploads
VOLUME ["/app/uploads"]

# Port exposed by Ktor Netty
EXPOSE 8080

# Command to execute
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
