# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Runtime stage ---
# noble (Ubuntu) em vez de alpine: 17-jre-alpine nao tem manifest arm64 (falha em VM ARM, ex. Oracle A1).
FROM eclipse-temurin:17-jre-noble
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring spring

# Agente Java do OpenTelemetry: sempre presente na imagem, mas inerte por padrão. Ativado só
# via OTEL_JAVAAGENT_ENABLED=true (ver docker-compose.yml/DEPLOYMENT.md) — sem isso, o agente
# não instrumenta nada e não tenta exportar pra lugar nenhum. Compatível com qualquer backend
# OTLP (SigNoz, Jaeger, etc.), não é vendor-specific.
# Versão fixa + checksum: o jar roda como -javaagent, então build não pode depender de "latest".
# Para atualizar: troque a versão e o sha256 (digest do asset na página da release).
ARG OTEL_AGENT_VERSION=2.32.0
ARG OTEL_AGENT_SHA256=f787eb6c7f3d18e69a431e108a15278d25ee37f83d68b678f621e063f3988f82
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/otel-agent.jar
RUN echo "${OTEL_AGENT_SHA256}  /app/otel-agent.jar" | sha256sum -c - \
    && chown spring:spring /app/otel-agent.jar

# Anexos dos cards da Review: pasta pertencente ao usuário da aplicação. O volume nomeado do
# compose herda este dono na primeira criação.
RUN mkdir -p /data/uploads && chown -R spring:spring /data
ENV UPLOADS_DIR=/data/uploads

# Fuso fixo em UTC: createdAt/updatedAt/lastLoginAt são LocalDateTime gravados com now() e a API os envia com "Z"
# (JacksonUtcConfig). Se a JVM rodasse em outro fuso, o navegador mostraria a hora errada. Fixar aqui (variável do
# sistema e propriedade da JVM) tira a dependência do fuso padrão da máquina.
ENV TZ=UTC

USER spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8002
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-javaagent:/app/otel-agent.jar", "-jar", "app.jar"]
