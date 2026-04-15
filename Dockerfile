FROM eclipse-temurin:21-jre-alpine

# Non-root user for security hardening
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# JAR is built by Maven in CI before this image is built
COPY target/notification-service-0.0.1-SNAPSHOT.jar app.jar

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
