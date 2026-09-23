# Production Deployment Guide

## Pre-Deployment Checklist

### Environment Variables (Required)

Before deploying to production, ensure these environment variables are set:

```bash
# Database
export DB_URL="jdbc:postgresql://prod-db-host:5432/espacoagil"
export DB_USERNAME="db_user"
export DB_PASSWORD="secure_password"

# Encryption (REQUIRED - NEVER use default)
export APP_ENCRYPTION_SECRET="$(openssl rand -base64 32)"  # Generate unique key

# Admin access (optional - leave empty to disable)
export APP_ADMIN_KEY="secure_admin_key_or_empty"

# CORS/WebSocket (REQUIRED in prod profile - no default, comma-separated)
export ALLOWED_ORIGINS="https://your-frontend-domain.com"

# Self-registration domain restriction (optional - prod profile already defaults to "totvs.com.br")
export ALLOWED_EMAIL_DOMAIN="totvs.com.br"

# Application Profile
export SPRING_PROFILES_ACTIVE="prod"

# Server Port
export PORT="8002"
```

See [.env.example](.env.example) for reference.

### Database Setup

1. **Create PostgreSQL database:**
   ```bash
   createdb espacoagil
   ```

2. **Run Flyway migrations:**
   - Migrations are executed automatically on startup
   - Migrations located in: `src/main/resources/db/migration/`
   - Current version: V1__create_api_keys_tables.sql

3. **Verify schema:**
   ```sql
   \dt -- List tables
   SELECT * FROM flyway_schema_history; -- Check migrations
   ```

### API Key Authentication

All requests to `/api/v1/**` and `/mcp/**` require valid API key:

```bash
# Create API key via admin endpoint
curl -X POST http://localhost:8002/api/admin/api-keys \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "integration-key",
    "ownerRole": "ADMIN",
    "scopes": ["KNOWLEDGE_READ", "SQUAD_READ", "POKER_READ"]
  }'

# Use API key in requests
curl http://localhost:8002/api/v1/knowledge/search \
  -H "X-Api-Key: ask_your_generated_key_here"
```

**API Key Scopes:**
- `KNOWLEDGE_READ` — Read knowledge base documents
- `KNOWLEDGE_WRITE` — Create/update knowledge documents
- `SQUAD_READ` — Read squad/project data
- `SQUAD_WRITE` — Modify squad data
- `PROMPTHUB_READ` — Read prompt collections and public prompts
- `PROMPTHUB_WRITE` — Create/update prompts and import agent skills (`SKILL.md`)
- `POKER_READ` — View poker sessions
- `POKER_WRITE` — Create/manage poker sessions

#### Uploading Skills via REST API (`POST /api/v1/prompt-hub/items`)
Agents and external automation scripts can publish or update skills using an API key with `PROMPTHUB_WRITE`:

```bash
curl -X POST http://localhost:8002/api/v1/prompt-hub/items \
  -H "X-Api-Key: ask_your_generated_key_here" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "---\nname: map-java-project\ndescription: Diagnóstico arquitetural de projetos Spring Boot\n---\n# Guia de Mapeamento Java...",
    "type": "skill",
    "visibility": "public"
  }'
```
- **Automatic Frontmatter Parsing:** Title and description are extracted from the YAML frontmatter block (`---`) if omitted.
- **Idempotent Upsert:** If a skill with the same title already exists for the author or workspace, its content, description, and tags are updated without duplicate entries.

#### Uploading Skills via MCP (`/mcp`)
The MCP Server exposes two dedicated tools for AI agents:
1. `importSkill(name?, content, description?, tags?, visibility?)`: Uploads or updates a single skill.
2. `batchImportSkills(skillsJson)`: Uploads multiple skills from a JSON array in a single tool call.

### Container Deployment

**Build image:**
```bash
docker build -t agile-space-backend:4.1.0 .
```

**Run container:**
```bash
docker run -d \
  -e DB_URL="jdbc:postgresql://postgres:5432/espacoagil" \
  -e DB_USERNAME="postgres" \
  -e DB_PASSWORD="secure_password" \
  -e APP_ENCRYPTION_SECRET="$(openssl rand -base64 32)" \
  -e SPRING_PROFILES_ACTIVE="prod" \
  -p 8002:8002 \
  --name agile-space-backend \
  agile-space-backend:4.1.0
```

**With docker-compose:**
```bash
SPRING_PROFILES_ACTIVE=prod docker-compose up -d
```

### Reverse Proxy / TLS

`docker-compose.yml` inclui um serviço `reverse-proxy` (Caddy) opcional, atrás do profile `proxy`,
que termina TLS via Let's Encrypt automaticamente e roteia `/api`, `/ws`, `/actuator`,
`/v3/api-docs` e `/swagger-ui` pro backend e o resto pro frontend (config em `Caddyfile`). `DOMAIN`
tem default `localhost` só pra não quebrar o `docker compose up` comum (sem `--profile proxy`) —
pra valer, exporte `DOMAIN` com o domínio público real, e garanta que o DNS (A/AAAA) já aponte
pro IP do servidor antes de subir, senão o Let's Encrypt falha o desafio HTTP-01.

```bash
export DOMAIN="agilespace.totvs.com.br"
export ALLOWED_ORIGINS="https://${DOMAIN}"
export SPRING_PROFILES_ACTIVE=prod
export APP_ENCRYPTION_SECRET="$(openssl rand -base64 32)"
export DB_URL="jdbc:postgresql://db:5432/espacoagil"
export DB_PASSWORD="secure_password"

# NEXT_PUBLIC_* do frontend: precisam do domínio público real ANTES do build (ver Dockerfile
# do frontend) — setar depois via "environment:" não muda o bundle já compilado.
export NEXT_PUBLIC_API_URL="https://${DOMAIN}/api"
export NEXT_PUBLIC_SPRING_API_URL="https://${DOMAIN}/api"
export NEXT_PUBLIC_WS_URL="wss://${DOMAIN}"
export NEXT_PUBLIC_GOOGLE_CLIENT_ID="..."  # opcional — sem ela, o card de agenda do painel some, resto do app funciona

docker compose --profile proxy up -d --build
```

Sem domínio real ainda (ex.: testando num servidor por IP), não suba o profile `proxy` — acesse
o frontend/backend direto nas portas 9002/8002 até o DNS estar pronto.

**Importante:** sempre que qualquer `NEXT_PUBLIC_*` mudar (domínio, client ID do Google, etc.),
o frontend precisa ser **rebuildado** (`--build`), não só reiniciado — o valor fica congelado no
bundle JS desde o momento do `docker build`.

### Health Check

Verify application is healthy:

```bash
curl http://localhost:8002/actuator/health
# Response: {"status":"UP"}
```

### Security Hardening

- ✅ HTTPS/TLS enforced (configure in load balancer or reverse proxy)
- ✅ API keys hashed with SHA-256 (stored securely)
- ✅ Encryption key never logged (use external secret manager)
- ✅ Swagger UI disabled in production profile
- ✅ Error responses sanitized (no stack traces)
- ✅ Schema validation enforced (ddl-auto: validate)

### Backup & Disaster Recovery

1. **Database backups:**
   ```bash
   pg_dump espacoagil | gzip > backup_$(date +%Y%m%d).sql.gz
   ```

2. **API keys audit:**
   ```sql
   SELECT id, name, owner_user_id, created_at, last_used_at, revoked_at 
   FROM api_keys 
   ORDER BY created_at DESC;
   ```

3. **Revoke compromised keys:**
   ```sql
   UPDATE api_keys SET revoked_at = NOW() WHERE id = '{key_id}';
   ```

### Monitoring & Logging

- Health endpoint: `GET /actuator/health`
- API key usage tracked: `last_used_at` updated on each request
- Check logs for failed authentication attempts (401 responses)

### Observability (OpenTelemetry)

Both `agile-space-backend` and `agile-space-frontend` ship with OpenTelemetry instrumentation
that is present but **inert by default** — vendor-neutral (OTLP), so it works with SigNoz or
any other OTLP-compatible backend without code changes, just an endpoint.

**Backend** — a Java agent is baked into the image (`Dockerfile`), auto-instrumenting Spring MVC,
JDBC/Hibernate and outgoing HTTP calls, plus JVM metrics. Enable with:
```bash
export OTEL_JAVAAGENT_ENABLED=true
export OTEL_EXPORTER_OTLP_ENDPOINT="http://your-signoz-collector:4317"
```

**Frontend** — `src/instrumentation.ts` registers `@vercel/otel` only when an endpoint is set:
```bash
export OTEL_EXPORTER_OTLP_ENDPOINT="http://your-signoz-collector:4317"
```

Both default to disabled/no-op — safe to leave unset until the observability backend (e.g.
SigNoz) is actually deployed somewhere. `docker-compose.yml` already wires these through.

## Deployment Commands

### Local Development
```bash
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run
```

### Production Deployment
```bash
export SPRING_PROFILES_ACTIVE=prod
export APP_ENCRYPTION_SECRET="$(openssl rand -base64 32)"
export DB_URL="jdbc:postgresql://prod-host:5432/espacoagil"
export DB_USERNAME="prod_user"
export DB_PASSWORD="prod_password"

mvn clean package -DskipTests
java -jar target/backend-4.1.0.jar
```

## Troubleshooting

### APP_ENCRYPTION_SECRET not set
```
Error: Could not resolve placeholder 'APP_ENCRYPTION_SECRET'
```
**Fix:** Set `APP_ENCRYPTION_SECRET` environment variable before startup.

### Flyway migration failed
```
Error: Unable to execute statement
```
**Fix:** Check database connectivity and permissions. Ensure database exists.

### API key validation fails
```
401: Chave de API inválida ou revogada.
```
**Fix:** Verify API key header format and value. Check if key was revoked.

### HTTPS connection issues
Configure reverse proxy (nginx/haproxy) with valid TLS certificate.

---

## 🔮 Backlog Arquitetural: RAG Vetorial (PostgreSQL + pgvector)

Para a nova versão rodando junto ao **WinThor Dev Manager** na mesma VM:
1. **Extensão pgvector:**
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
2. **Tabela de Chunks Vetorizados (`kb_chunks`):**
   - Criação da tabela com coluna `embedding VECTOR(384)` e índice `HNSW` (`vector_cosine_ops`).
3. **Endpoint de Ingestão (`POST /api/v1/knowledge/sync`):**
   - Recebe em lote os chunks e vetores gerados pelo WinThor Dev Manager (sem custo de IA externa).
   - Autenticado via header `X-Api-Key` com escopo `KNOWLEDGE_WRITE`.
4. **Consulta do Chat:**
   - Realizada diretamente via SQL (`ORDER BY embedding <=> query_vector LIMIT 5`) de forma ultra veloz e gratuita.

---

**Version:** 4.1.0  
**Last Updated:** 2026-09-10

