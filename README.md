# Agile Space Backend 🚀

O **Agile Space Backend** é o motor central (API REST e WebSockets) da plataforma Agile Space. Este repositório foi construído utilizando uma arquitetura moderna e escalável em **Java com Spring Boot**, focado em gerenciar cerimônias ágeis, métricas de squad, segurança de senhas (Vault) e colaboração em tempo real (WebSockets).

---

## 🛠️ Stack Tecnológica

- **Linguagem:** Java 17+
- **Framework Core:** Spring Boot 3.x
- **Documentação de API:** OpenAPI 3 / Swagger (Springdoc OpenAPI UI)
- **Banco de Dados:** PostgreSQL (Spring Data JPA)
- **Tempo Real:** Spring WebSockets (Stomp/SockJS)
- **Testes:** JUnit 5, Mockito
- **Build Tool:** Maven

---

## 🧩 Principais Módulos do Sistema

A arquitetura do sistema é modularizada para atender a diferentes fluxos ágeis:

- **Cerimônias Ágeis:** Serviços em tempo real para `Planning Poker`, `Retrospectivas` (RetroBoards e Cards), `Showcase Sessions` (Reviews) e `Brainstorming`.
- **Gestão de Squads e Métricas:** Integração com snapshots do Jira, métricas diárias, consolidação (rollups) de saúde do time, capacidade e cache de worklogs.
- **Health Check & Daily Flow:** Controle de humor e reportes diários do time, facilitando inspeções rápidas.
- **Vault (Cofre de Segredos):** Serviço de compartilhamento seguro de credenciais com autodestruição baseada em tempo (1h, 24h) ou visualização única (`once`).
- **Workspace do Usuário:** Quadros Kanban privados, notas adesivas e links rápidos para gestão pessoal (To-Do List pessoal).
- **Gestão de Conhecimento e Prompts de IA:** Repositório colaborativo de prompts (Scrum Master, Product Owner, etc.) com sistema de forks, comentários e ranqueamento de uso.

---

## ⚙️ Arquitetura

O projeto segue os princípios de Arquitetura em Camadas (Layered Architecture):

- **Domain (`com.agilespace.backend.domain`):** Modelos de dados e mapeamentos das tabelas do PostgreSQL (`@Entity`).
- **Repository (`com.agilespace.backend.repository`):** Interfaces de acesso a dados abstraídas pelo Spring Data JpaRepository.
- **Service (`com.agilespace.backend.service`):** Onde reside toda a regra de negócio e os limites transacionais (`@Transactional`).
- **Controller (`com.agilespace.backend.controller`):** Exposição dos endpoints REST para consumo pelo Frontend.
- **WebSocket (`com.agilespace.backend.websocket`):** Handlers responsáveis pelo broadcast de eventos (ex: virada de cartas no Planning Poker) usando arquitetura Pub/Sub.

---

## 🚀 Como Rodar Localmente

### Pré-requisitos
- **Java 17** (ou superior) instalado.
- **Maven** (O projeto já inclui o `mvnw` (Maven Wrapper), então não é obrigatório ter o Maven instalado globalmente).
- Instância do **PostgreSQL** rodando localmente (porta 5432) ou uma URI de um banco em nuvem.

### Passos de Execução

1. **Clone o repositório:**
   ```bash
   git clone https://github.com/wanderalvess/agile-space-backend.git
   cd agile-space-backend
   ```

2. **Configure as Variáveis de Ambiente:**
   Configure as propriedades do banco de dados no arquivo `src/main/resources/application.yml` ou exporte como variável de ambiente caso utilize um PostgreSQL externo.

3. **Inicie o Servidor:**
   Utilizando o Maven Wrapper:
   ```bash
   # Em ambiente Windows:
   ./mvnw.cmd spring-boot:run

   # Em ambiente Linux/Mac:
   ./mvnw spring-boot:run
   ```
   A aplicação subirá por padrão na porta `8002`.

---

## 📖 Documentação da API (Swagger / OpenAPI)

Com a aplicação em execução, a documentação interativa e os endpoints podem ser acessados em:

- **Swagger UI (Interface Interativa):** [http://localhost:8002/swagger-ui.html](http://localhost:8002/swagger-ui.html)
- **OpenAPI Spec (JSON):** [http://localhost:8002/v3/api-docs](http://localhost:8002/v3/api-docs)

---

## 🧪 Como Rodar os Testes

O projeto possui **100% de cobertura das lógicas de negócio** das camadas de `Controller` e `Service`.

Para executar a suíte completa de testes unitários e de integração:

```bash
./mvnw.cmd clean test
```

Os testes estão fortemente focados em:
- Garantia de persistência correta no PostgreSQL.
- Emissão de eventos em tempo real (`verify(webSocketHandler.broadcastEvent(...))`).
- Tratamento de IDs compostos (dbIds) e lógicas de mesclagem (merges).
- Expiração temporal rigorosa e autodestruição para o módulo Vault.

---

## 🤝 Contribuição

1. Crie uma branch para a sua feature (`git checkout -b feature/minha-feature`)
2. Faça os commits (`git commit -m 'feat: minha nova feature'`)
3. Faça o push para a branch (`git push origin feature/minha-feature`)
4. Abra um Pull Request.

**Agile Space Backend** - Feito para potencializar o dia a dia de times ágeis.

---

## 🚢 Guia de Deploy em Produção

> Este guia consolida **todas as ações obrigatórias** antes e depois de subir o Agile Space em um servidor de produção. Siga a ordem dos passos para evitar falhas de inicialização ou brechas de segurança.

---

### 1. Variáveis de Ambiente (OBRIGATÓRIAS)

O servidor **não inicia** sem as variáveis abaixo corretamente configuradas.
Configure-as no seu ambiente (systemd, Docker Compose, painel do provedor cloud, etc.)
— **NUNCA coloque valores reais no `application.yml` ou em arquivos versionados no Git**.

#### 1.1 `APP_ENCRYPTION_SECRET` — Chave AES-256 para tokens Jira

Todos os tokens Jira dos usuários são cifrados com essa chave antes de serem salvos no banco.
Se a chave for trocada, os tokens já salvos **não poderão mais ser descriptografados** — os usuários precisarão re-cadastrar seus tokens.

**Como gerar (escolha um dos métodos):**

```powershell
# PowerShell (Windows)
[Convert]::ToBase64String((1..48 | % {[byte](Get-Random -Max 256)}))
```

```bash
# Linux / macOS
openssl rand -base64 48
```

Exemplo de saída: `k3Hv9Xz2mQpL7rNsYwAoBdFtUcGjEiKl8eDxVyCZn4hP1R0sW5=`

**Configure no ambiente:**
```bash
# Linux (systemd ou .env)
export APP_ENCRYPTION_SECRET="k3Hv9Xz2mQpL7rNsYwAoBdFtUcGjEiKl8eDxVyCZn4hP1R0sW5="

# Docker Compose
environment:
  APP_ENCRYPTION_SECRET: "k3Hv9Xz2mQpL7rNsYwAoBdFtUcGjEiKl8eDxVyCZn4hP1R0sW5="
```

> ⚠️ **Salve essa chave em local seguro** (ex: cofre de senhas corporativo, AWS Secrets Manager, Azure Key Vault). Sem ela, todos os tokens do banco ficam inacessíveis.

---

#### 1.2 `APP_ADMIN_KEY` — Chave para o endpoint `GET /api/users`

Protege a listagem de todos os usuários. Sem essa variável, o endpoint retorna `403 Forbidden`.
Use apenas para ferramentas internas de suporte.

```bash
# Gerar:
openssl rand -hex 32
# PowerShell:
-join ((1..32) | % { '{0:x2}' -f (Get-Random -Max 256) })
```

```bash
export APP_ADMIN_KEY="a1b2c3d4e5f6..."
```

---

#### 1.3 Variáveis de Banco de Dados

```bash
export DB_URL="jdbc:postgresql://seu-servidor-db:5432/espacoagil"
export DB_USERNAME="seu_usuario_db"
export DB_PASSWORD="senha_forte_do_banco"
```

---

#### 1.4 `PORT` (Opcional)

```bash
export PORT=8002   # padrão — altere se necessário
```

---

### 2. Certificado SSL do Jira Corporativo (TOTVS)

Atualmente o backend ignora toda validação SSL para se comunicar com o Jira da TOTVS (`jiraproducao.totvs.com.br`).
Isso é um risco de segurança (MITM). **O fix correto é importar o certificado corporativo na JVM:**

```bash
# 1. Exporte o certificado do servidor Jira (execute no servidor de produção)
openssl s_client -connect jiraproducao.totvs.com.br:443 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -outform PEM > totvs-jira.crt

# 2. Importe no truststore da JVM que vai rodar o backend
#    Substitua JAVA_HOME pelo caminho correto (ex: /usr/lib/jvm/java-17-openjdk)
keytool -importcert \
  -alias totvs-jira \
  -file totvs-jira.crt \
  -keystore $JAVA_HOME/lib/security/cacerts \
  -storepass changeit \
  -noprompt

# 3. Após importar, habilite a validação SSL de volta no JiraService.java
#    (remova o TrustManager que aceita tudo e o HostnameVerifier permissivo)
```

> Enquanto o certificado não for importado, o trust-all permanece funcional mas é tecnicamente inseguro em redes não controladas.

---

### 3. Banco de Dados

#### 3.1 Verificar `ddl-auto`

Em produção, **nunca use `ddl-auto: create` ou `create-drop`**. O projeto já usa `update`, que é seguro para produção.
Verifique antes de subir:

```yaml
# application.yml — deve estar assim:
jpa:
  hibernate:
    ddl-auto: update   # ✅ seguro — só adiciona colunas, não apaga dados
```

#### 3.2 Migração de tokens existentes

Se você já tinha tokens salvos **antes** da chave `APP_ENCRYPTION_SECRET` ser definida, eles estavam cifrados com a chave padrão pública (`AgileSpaceSecureMasterKey2026Default#AES256GCMKey`).

O `EncryptionUtil.decrypt()` tem fallback de retrocompatibilidade — se a descriptografia falhar com a nova chave, ele retorna o valor original.
Isso significa que os tokens antigos **não serão descriptografados corretamente** com a nova chave.

**Opções:**
- **A (recomendada):** Pedir que todos os usuários re-cadastrem seu token Jira após o deploy. É o caminho mais limpo.
- **B (script de migração):** Decriptografar todos os registros com a chave antiga e re-encriptografar com a nova. Execute antes de trocar a variável de ambiente.

---

### 4. Frontend — Variáveis de Ambiente

No servidor/painel onde o Next.js está hospedado, configure:

```bash
# URL do backend Spring Boot em produção
NEXT_PUBLIC_API_URL=https://api.seudominio.com.br/api

# Exemplo com porta direta (se não usar reverse proxy):
NEXT_PUBLIC_API_URL=http://100.200.300.400:8002/api
```

> O prefixo `NEXT_PUBLIC_` é obrigatório para que a variável seja acessível no lado do cliente (browser).

---

### 5. CORS em Produção

Atualmente `WebCorsConfig.java` usa `allowedOriginPatterns("*")` — aceita qualquer origem.
Quando o domínio de produção estiver definido, restrinja:

```java
// WebCorsConfig.java — substitua * pelo domínio real:
registry.addMapping("/**")
    .allowedOriginPatterns(
        "https://agile.seudominio.com.br",
        "https://www.agile.seudominio.com.br"
    )
    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
    .allowedHeaders("*")
    .allowCredentials(true);
```

---

### 6. Checklist Final — Verificação Pós-Deploy

Execute este checklist após subir o servidor para confirmar que tudo está funcionando:

| # | Verificação | Como testar |
|---|---|---|
| ✅ | Backend subiu sem erros | `curl http://servidor:8002/actuator/health` ou ver logs de inicialização |
| ✅ | `APP_ENCRYPTION_SECRET` está definida | Tentar salvar um token Jira — deve persistir e carregar sem erro |
| ✅ | Token Jira funciona | Clicar em "Testar Conexão" na tela de Conexão Jira do frontend |
| ✅ | Sync do squad funciona | Clicar em "Sincronizar" no Squad Pulse — dados devem aparecer |
| ✅ | Acesso cruzado bloqueado | `curl GET /api/users/{outroUserId}/jira-config` sem o header `X-Caller-Id` → deve retornar `403` |
| ✅ | `GET /users` bloqueado | `curl GET /api/users` sem `X-Admin-Key` → deve retornar `403` |
| ✅ | Slot legado `localStorage` limpo | Abrir DevTools → Application → Local Storage → não deve existir a chave `agileSpace_jira_config` |
| ✅ | Frontend conecta no backend certo | Confirmar que `NEXT_PUBLIC_API_URL` aponta para o servidor de produção |

---

### 7. Recomendações Futuras (Pós-Launch)

| Prioridade | Item |
|---|---|
| 🔴 Alta | Implementar autenticação real (JWT / OAuth2) no backend — atualmente os endpoints são abertos na rede interna |
| 🔴 Alta | Importar certificado SSL da TOTVS na JVM e remover o trust-all do `JiraService.java` |
| 🟠 Média | Migrar rate limit para Redis/Upstash se o frontend tiver múltiplas instâncias (ex: Vercel) |
| 🟠 Média | Configurar backup automático do banco PostgreSQL |
| 🟡 Baixa | Restringir CORS para o domínio de produção exato em `WebCorsConfig.java` |
| 🟡 Baixa | Habilitar `show-sql: false` e configurar log level `WARN` em produção para reduzir verbosidade |

