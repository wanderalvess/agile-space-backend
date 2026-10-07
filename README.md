# Agile Space Backend 🚀

O **Agile Space Backend** é o motor central (API REST, WebSockets e Servidor MCP) da plataforma **Espaço Ágil**. Este repositório foi construído utilizando uma arquitetura escalável e resiliente em **Java 17+ com Spring Boot 3.5**, responsável por gerenciar cerimônias ágeis em tempo real, métricas e consolidação de squads, segurança de segredos (Vault), base de conhecimento com RAG vetorial e integrações para agentes de inteligência artificial.

---

## 🛠️ Stack Tecnológica

- **Linguagem & Runtime:** Java 17+
- **Framework Core:** Spring Boot 3.5.x
- **Banco de Dados & ORM:** PostgreSQL, Spring Data JPA / Hibernate (`ddl-auto: validate`)
- **Migrações de Banco:** Flyway (`classpath:db/migration`, baseline idempotente `V1_1__baseline_schema.sql` e scripts incrementais)
- **Segurança & Autenticação:**
  - Autenticação corporativa nativa JWT (HS256 com claims estruturadas e BCrypt)
  - API Keys com escopo granular (`X-Api-Key` com hash SHA-256) para rotas `/api/v1/**` e `/mcp/**`
  - Criptografia simétrica AES-256 (GCM) para tokens corporativos do Jira (`APP_ENCRYPTION_SECRET`)
  - Validação estrita de segredos no boot (`ProductionSecretsValidator`)
  - Controle de taxa e proteção contra abuso via Bucket4j
- **Tempo Real & WebSockets:** Spring WebSockets (Stomp/SockJS) com autenticação por token no handshake (`JwtHandshakeInterceptor`)
- **IA & Model Context Protocol (MCP):** Spring AI com servidor MCP WebMVC embutido (`/mcp/sse`), RAG vetorial autônomo com `pgvector`
- **Engine de Transformação:** Bazaarvoice JOLT nativo Java (`jolt-core`, `json-utils`)
- **Documentação de API:** OpenAPI 3 / Swagger (`springdoc-openapi-starter-webmvc-ui`)
- **Observabilidade:** Spring Boot Actuator (`/actuator/health`), suporte a rastreamento distribuído via OpenTelemetry (OTLP)
- **Testes:** JUnit 5, Mockito

---

## 🧩 Principais Módulos do Sistema

A arquitetura do sistema é modularizada por domínios de negócio ágil:

- **Cerimônias Ágeis em Tempo Real:**
  - `Scrum Poker`: Salas síncronas e assíncronas com votações em tempo real, consenso por papel e varredura visual de divergência.
  - `Retrospectivas Inteligentes`: Retro boards dinâmicos com colunas configuráveis, limite de votos por membro e fusão inteligente de cards preservando histórico integral (`originalTexts`).
  - `Sprint Showcase`: Sessões de Sprint Review com suporte ao Modo Teatro, renderização de evidências e proxy autenticado de mídias do Jira.
  - `Brainstorming Alpha`: Espaço colaborativo síncrono para dinâmicas de ideação.
- **Gestão de Squads e Métricas (Squad Pulse & Jiradash):** Integração com snapshots do Jira, métricas diárias, consolidação (rollups) de saúde do time, capacidade e cache compartilhado de consultas JQL.
- **Daily Flow & Health Check:** Controle de humor, reporte de impedimentos e diagnóstico anônimo periódico do clima da squad.
- **Plano de Ação (Action Plan):** Gestão tática de melhorias derivadas das retrospectivas com metodologia 5W2H, responsáveis e prazos.
- **Vault (Cofre de Segredos):** Serviço de compartilhamento seguro de credenciais com expiração temporal (1h, 24h) ou visualização única (`once`).
- **Workspace Pessoal:** Quadros Kanban privados, notas adesivas e links rápidos para gestão individual.
- **Gestão de Acessos & Convites:** Convites por link com token seguro (`InviteController`), separando administração do sistema (`role = ADMIN`) da liderança ágil de squad.
- **Prompt Hub & Acervo de IA:** Repositório colaborativo de prompts para papéis ágeis (SM, PO, Dev, QA) com versionamento, forks e upload automatizado de skills (`SKILL.md`).
- **RAG & Base de Conhecimento Vetorial (PostgreSQL + pgvector):** Ingestão de documentações técnicas e regras de negócio vetorizadas, permitindo consultas semânticas no Chat sem custo de IA externa.
- **Servidor MCP Embutido (`agile-space-mcp-server`):** Servidor Model Context Protocol exposto em `/mcp/sse` e `/mcp/message`, protegido por `X-Api-Key`, permitindo que agentes externos consultem documentações, squads e criem sessões de poker.
- **API Pública com Escopos Granulares (`/api/v1/**`):** Endpoints REST para integrações máquina-a-máquina autenticadas por chave de API.
- **Engine JOLT Server-Side:** Execução de transformações de estruturas JSON utilizando a especificação JOLT com o motor Java oficial Bazaarvoice.

---

## ⚙️ Arquitetura

O projeto adota uma Arquitetura em Camadas (Layered Architecture) estrita e desacoplada:

- **Domain (`com.agilespace.backend.domain`):** Modelos de dados e mapeamentos das tabelas do PostgreSQL (`@Entity`), colunas JSONB gerenciadas via `@JdbcTypeCode(SqlTypes.JSON)`.
- **Repository (`com.agilespace.backend.repository`):** Interfaces de acesso a dados abstraídas pelo Spring Data JpaRepository.
- **Service (`com.agilespace.backend.service`):** Regras de negócio, transações (`@Transactional`) e orquestração de eventos.
- **Controller (`com.agilespace.backend.controller`):** Exposição dos endpoints REST, segregados entre rotas de sessão de usuário (`/api/**` via JWT) e rotas de integração de serviços (`/api/v1/**` via `X-Api-Key`).
- **Security (`com.agilespace.backend.security`):** Filtros [`JwtAuthenticationFilter`](src/main/java/com/agilespace/backend/security/JwtAuthenticationFilter.java), [`ApiKeyAuthenticationFilter`](src/main/java/com/agilespace/backend/security/ApiKeyAuthenticationFilter.java), gerador/validador de tokens [`JwtTokenUtil`](src/main/java/com/agilespace/backend/security/JwtTokenUtil.java) e interceptor de WebSockets [`JwtHandshakeInterceptor`](src/main/java/com/agilespace/backend/security/JwtHandshakeInterceptor.java).
- **MCP (`com.agilespace.backend.mcp`):** Ferramentas expostas via Model Context Protocol para consumo por agentes de IA.
- **WebSocket (`com.agilespace.backend.websocket`):** Handlers responsáveis pelo broadcast Pub/Sub de eventos em tempo real (`/ws/poker/*`, `/ws/retro/*`, `/ws/showcase/*`, `/ws/health-check/*`, `/ws/brainstorming/*`).

---

## 🚀 Como Rodar Localmente

### Pré-requisitos
- **Java 17** (ou superior) instalado.
- **Maven Wrapper** (já incluído no projeto via `./mvnw` / `./mvnw.cmd`).
- Instância do **PostgreSQL 14+** em execução (porta 5432) com uma base chamada `espacoagil`.

### Passos de Execução

1. **Clone o repositório:**
   ```bash
   git clone https://github.com/wanderalvess/agile-space-backend.git
   cd agile-space-backend
   ```

2. **Configuração de Variáveis de Ambiente:**
   Para desenvolvimento local, o arquivo `src/main/resources/application.yml` já fornece defaults funcionais para PostgreSQL (`postgres/postgres`), JWT e chaves de criptografia. Caso queira sobrescrever, defina as variáveis de ambiente:
   ```bash
   export DB_URL="jdbc:postgresql://localhost:5432/espacoagil"
   export DB_USERNAME="postgres"
   export DB_PASSWORD="postgres"
   ```

3. **Inicie o Servidor:**
   ```bash
   # Em ambiente Windows (PowerShell / CMD):
   ./mvnw.cmd spring-boot:run

   # Em ambiente Linux / macOS:
   ./mvnw spring-boot:run
   ```
   A aplicação executará por padrão na porta `8002` (`http://localhost:8002/api`).

   > 💡 **Migrações Automáticas:** Na primeira subida, o **Flyway** aplicará automaticamente o script baseline (`V1_1__baseline_schema.sql`) e todas as migrações subsequentes. O Hibernate valida o schema (`ddl-auto: validate`).

---

## 🐳 Rodando com Docker

A stack completa (**Backend + Frontend + PostgreSQL dedicado**) sobe com um único comando, isolada de outros containers locais:

### Pré-requisitos
- Docker Engine e Docker Compose instalados.
- Repositório [`agile-space-frontend`](https://github.com/wanderalvess/agile-space-frontend) clonado **no mesmo diretório pai** que este repositório:
  ```
  meus-projetos/
  ├── agile-space-backend/   (este repositório, contém o docker-compose.yml)
  └── agile-space-frontend/  (repositório frontend Next.js)
  ```

### Subir a Stack Completa
```bash
docker compose up -d --build
```

Isso inicializa:
- **`agile-space-db`** — PostgreSQL 17 dedicado, banco `espacoagil`, volume nomeado `agile-space-db-data`.
- **`agile-space-backend`** — API Spring Boot na porta `8002`. As migrações do Flyway rodam automaticamente durante a inicialização.
- **`agile-space-frontend`** — Next.js 16 standalone na porta `9002`.

Os três serviços possuem `healthcheck` ativo no Compose (`db` via `pg_isready`, `backend` via `/actuator/health`, `frontend` via `/`), garantindo que o frontend só inicie após a API Spring Boot estar 100% operacional.

### Verificar Conectividade
```bash
docker compose ps                        # Todos devem exibir status "healthy"
curl http://localhost:8002/actuator/health  # {"status":"UP"}
curl http://localhost:8002/v3/api-docs      # Documentação OpenAPI
curl http://localhost:9002                  # Frontend Next.js
```

### Recompilar após Alterações de Código
```bash
docker compose up -d --build backend    # Recompilar apenas o backend
docker compose up -d --build frontend   # Recompilar apenas o frontend
```

### Parar / Limpar
```bash
docker compose down          # Para os containers, preserva os dados do PostgreSQL
docker compose down -v       # Para e remove os containers e o volume de dados do banco
```

---

## 🤖 Servidor MCP Embutido (Model Context Protocol)

O backend possui um servidor MCP nativo via Spring AI (`spring-ai-starter-mcp-server-webmvc`), projetado para integração com agentes de IA (como Claude Desktop, Cursor, Antigravity, etc.):

- **Nome do Servidor:** `agile-space-mcp-server`
- **Transporte SSE:** `GET /mcp/sse` e `POST /mcp/message`
- **Autenticação:** Requer o cabeçalho `X-Api-Key` com uma chave de API válida emitida pelo administrador.
- **Ferramentas Disponíveis no MCP:**
  - `importSkill`: Importação ou atualização de skills individuais (`SKILL.md`).
  - `batchImportSkills`: Importação em lote de múltiplas skills a partir de um array JSON.
  - Leitura e busca semântica na Base de Conhecimento.
  - Consulta a métricas e status de squads.
  - Criação e consulta de sessões de Scrum Poker.

---

## 🔑 Autenticação por API Key (`/api/v1/**` e `/mcp/**`)

Para chamadas de máquina ou integrações de terceiros, utilize as API Keys com escopos:

### Gerar API Key (via Admin autenticado com JWT)
```bash
curl -X POST http://localhost:8002/api/admin/api-keys \
  -H "Authorization: Bearer {JWT_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "minha-chave-integracao",
    "ownerRole": "ADMIN",
    "scopes": ["KNOWLEDGE_READ", "SQUAD_READ", "PROMPTHUB_WRITE", "POKER_READ"]
  }'
```

### Escopos Suportados
- `KNOWLEDGE_READ` / `KNOWLEDGE_WRITE`: Leitura e escrita de documentos na Base de Conhecimento.
- `SQUAD_READ` / `SQUAD_WRITE`: Leitura e modificação de dados de squads e projetos.
- `PROMPTHUB_READ` / `PROMPTHUB_WRITE`: Consulta e publicação de prompts e skills no Prompt Hub.
- `POKER_READ` / `POKER_WRITE`: Consulta e gerenciamento de salas de Scrum Poker.

---

## 📖 Documentação da API (Swagger / OpenAPI)

Em ambiente de desenvolvimento, a documentação interativa e as especificações de contrato ficam disponíveis em:

- **Swagger UI:** [http://localhost:8002/swagger-ui.html](http://localhost:8002/swagger-ui.html)
- **OpenAPI Spec (JSON):** [http://localhost:8002/v3/api-docs](http://localhost:8002/v3/api-docs)

*(Em profile de produção `prod`, o Swagger UI e os endpoints de docs são desabilitados por padrão por motivos de segurança).*

---

## 🩺 Observabilidade & Monitoramento

- **Health check:** `GET /actuator/health` (Spring Boot Actuator) — retorna `{"status":"UP"}`, verificando a conectividade com o banco PostgreSQL. Não exige autenticação.
- **OpenTelemetry (OTLP):** A imagem Docker inclui agente Java OTLP integrado, permitindo exportar traces e métricas automaticamente para SigNoz, Jaeger ou qualquer coletor compatível definindo `OTEL_EXPORTER_OTLP_ENDPOINT`.

---

## 🧪 Como Rodar os Testes

Para executar a suíte completa de testes unitários e de integração:

```bash
# Windows:
./mvnw.cmd clean test

# Linux / macOS:
./mvnw clean test
```

Os testes cobrem:
- Validação de integridade e transações no PostgreSQL.
- Verificação de emissão de eventos em WebSockets (`webSocketHandler.broadcastEvent(...)`).
- Mecanismo de handshake e autenticação via `JwtHandshakeInterceptor`.
- Autenticação e autorização via `JwtAuthenticationFilter` e `ApiKeyAuthenticationFilter`.
- Expiração temporal e autodestruição do módulo Vault.
- Regras de negócio de retrospectivas (fusão de cards, controle de votos).

---

## 🚢 Guia de Deploy em Produção

> Consulte também o documento detalhado [`DEPLOYMENT.md`](./DEPLOYMENT.md) para procedimentos avançados de infraestrutura, TLS e backup.

### 1. Variáveis de Ambiente Obrigatórias (Profile `prod`)

Quando o backend executa com `SPRING_PROFILES_ACTIVE=prod`, a classe [`ProductionSecretsValidator`](src/main/java/com/agilespace/backend/config/ProductionSecretsValidator.java) **bloqueia a inicialização** caso as variáveis abaixo estejam ausentes ou utilizando valores padrão de desenvolvimento:

| Variável | Descrição | Como Gerar |
| :--- | :--- | :--- |
| `APP_JWT_SECRET` | Chave HMAC (mínimo 48 bytes) usada para assinar os tokens JWT de sessão. | `openssl rand -base64 48` |
| `APP_ENCRYPTION_SECRET` | Chave AES-256 (32 bytes) para cifrar tokens Jira e segredos salvos no banco. | `openssl rand -base64 32` |
| `ALLOWED_EMAIL_DOMAIN` | Restrição de domínio para auto-cadastro no `POST /api/auth/register` (ex: `totvs.com.br`). | Definir o domínio corporativo permitido |
| `ALLOWED_ORIGINS` | Lista separada por vírgula dos domínios autorizados no CORS e no WebSocket (sem wildcard em produção). | `https://agilespace.seudominio.com.br` |
| `DB_URL` | URL de conexão JDBC com o PostgreSQL. | `jdbc:postgresql://host:5432/espacoagil` |
| `DB_USERNAME` | Usuário do banco PostgreSQL. | `db_user` |
| `DB_PASSWORD` | Senha do banco PostgreSQL. | Senha forte |
| `APP_ADMIN_KEY` | Chave de proteção para o endpoint administrativo `GET /api/users`. | `openssl rand -hex 32` |
| `PORT` | Porta de escuta da aplicação (padrão: `8002`). | `8002` |

### 2. Promoção do Primeiro Usuário Administrador de Sistema

Por design de segurança, nenhum seed ou script cria usuários `ADMIN` de sistema automaticamente:
1. Registre seu usuário normalmente pela interface (`/login` -> Cadastrar).
2. Conecte ao banco de dados e promova manualmente o usuário:
   ```sql
   UPDATE users SET role = 'ADMIN' WHERE email = 'seu-email@totvs.com.br';
   ```
3. O perfil com `role = 'ADMIN'` terá acesso liberado ao painel administrativo (`/api/admin/**`).

### 3. Banco de Dados e Migrações

- **Nunca utilize `ddl-auto: create` ou `update` em produção**. O backend utiliza estritamente `hibernate.ddl-auto: validate`.
- Todas as criações de tabelas, índices e alterações de esquema são executadas de forma versionada e controlada pelo **Flyway**.
- O script `scripts/backup-db.sh` encapsula rotinas de `pg_dump` com política de retenção para agendamento via Cron.

### 4. Configuração de CORS e WebSockets

Não é necessário alterar código-fonte para configurar CORS. O [`WebCorsConfig`](src/main/java/com/agilespace/backend/config/WebCorsConfig.java) e o [`WebSocketConfig`](src/main/java/com/agilespace/backend/config/WebSocketConfig.java) utilizam a variável de ambiente `ALLOWED_ORIGINS`:
```bash
export ALLOWED_ORIGINS="https://agilespace.totvs.com.br"
```

### 5. Certificado SSL Corporativo (Jira TOTVS)

Para habilitar a validação estrita de SSL na comunicação com o Jira corporativo da TOTVS (`jiraproducao.totvs.com.br`), importe a CA/certificado corporativo na truststore da JVM:
```bash
openssl s_client -connect jiraproducao.totvs.com.br:443 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > totvs-jira.crt

keytool -importcert \
  -alias totvs-jira \
  -file totvs-jira.crt \
  -keystore $JAVA_HOME/lib/security/cacerts \
  -storepass changeit \
  -noprompt
```

---

## 🤝 Contribuição e Padrões de Commit

1. Crie uma branch para a sua feature (`git checkout -b feature/minha-feature`)
2. Faça os commits seguindo a convenção do projeto (`type(scope): summary`):
   - Exemplo: `feat(mcp): adiciona ferramenta batchImportSkills`
   - Exemplo: `fix(security): valida expiracao do token no handshake do websocket`
3. Faça o push para a branch (`git push origin feature/minha-feature`)
4. Abra um Pull Request.

---

**Agile Space Backend** — Potencializando a colaboração e a engenharia de software de alta performance.
