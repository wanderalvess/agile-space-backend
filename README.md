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
