# ---------------------------------------------------------------------------
# Stage 1 – build
# Caches Maven dependencies in a separate layer so they are not re-downloaded
# on every code change.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

# Pull dependencies first — this layer is cached as long as pom.xml is unchanged.
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ---------------------------------------------------------------------------
# Stage 2 – runtime
# Uses only the JRE (smaller image) and runs as a non-root user.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
