# ==============================================================================
# Multi-Stage Dockerfile for Movie Service (Java 21 / Spring Boot)
# ==============================================================================

# Stage 1: Build the Application
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Cache Maven dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and package the runnable JAR
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal Runtime Environment
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user for enterprise container security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser:appgroup

# Copy built JAR from builder stage
COPY --from=builder /build/target/movie-service-*.jar /app/movie-service.jar

# Service HTTP port
EXPOSE 8082

# Container-aware JVM memory configuration
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=5s --retries=3 --start-period=30s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/movie-service.jar"]
