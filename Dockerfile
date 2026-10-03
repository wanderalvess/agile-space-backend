# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring

# Agente Java do OpenTelemetry: sempre presente na imagem, mas inerte por padrão. Ativado só
# via OTEL_JAVAAGENT_ENABLED=true (ver docker-compose.yml/DEPLOYMENT.md) — sem isso, o agente
# não instrumenta nada e não tenta exportar pra lugar nenhum. Compatível com qualquer backend
# OTLP (SigNoz, Jaeger, etc.), não é vendor-specific.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar /app/otel-agent.jar
RUN chown spring:spring /app/otel-agent.jar

USER spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8002
ENTRYPOINT ["java", "-javaagent:/app/otel-agent.jar", "-jar", "app.jar"]
