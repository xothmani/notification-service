# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom.xml first — Docker caches this layer until pom.xml changes
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Resolve all dependencies offline so subsequent builds are fast
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and produce the fat JAR (tests run in CI, not here)
COPY src ./src
RUN ./mvnw package -DskipTests -B

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user for security hardening
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/target/notification-service-0.0.1-SNAPSHOT.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

# Port declared in application.properties
EXPOSE 8085

# JVM flags tuned for containerised microservices:
#   UseContainerSupport  — honour cgroup CPU/memory limits (on by default in 11+, explicit for clarity)
#   MaxRAMPercentage=75  — allocate up to 75 % of container RAM to the heap
#   UseZGC               — low-pause GC well-suited for latency-sensitive services (Java 21 GA)
#   security.egd         — avoid /dev/random blocking on entropy-starved containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseZGC \
               -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
