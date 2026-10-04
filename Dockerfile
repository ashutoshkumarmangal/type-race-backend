# Build stage: resolve dependencies and package the jar with Maven.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first so dependency resolution is cached until dependencies actually change.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# Runtime stage: JRE only, no Maven, no source.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Run unprivileged; Render injects PORT and the DB_* variables at runtime.
RUN groupadd --system typerush \
 && useradd --system --gid typerush --home /app typerush

COPY --from=build --chown=typerush:typerush /build/target/typerush-server-1.0.0.jar app.jar

USER typerush
EXPOSE 8081

# server.port resolves from $PORT first (see application.yml), so Render's port is honoured.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]