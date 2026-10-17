-- Review (Showcase): versão por card (edição parcial sem sobrescrever), participantes do resumo e consultas JQL por squad.
-- Só aditiva e idempotente: sem DELETE/DROP/UPDATE de dado existente; cards antigos ficam na versão 1 e sem data de edição,
-- Reviews antigas ficam sem participantes. Contrato: docs/ceremony-review-api.md.

-- Controle otimista por card. Não é @Version do Hibernate: showcase_tasks é regravada por inteiro a cada POST da sessão e a
-- versão é calculada pelo serviço (sobe só quando o conteúdo muda).
ALTER TABLE showcase_tasks ADD COLUMN IF NOT EXISTS card_version bigint NOT NULL DEFAULT 1;
ALTER TABLE showcase_tasks ADD COLUMN IF NOT EXISTS card_updated_at timestamp(6);

-- Quem esteve na Review quando ela foi finalizada (nome gravado no momento, para o resumo não mudar depois).
CREATE TABLE IF NOT EXISTS showcase_participants (
    id varchar(36) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES showcase_sessions (id) ON DELETE CASCADE,
    user_id varchar(255) NOT NULL,
    display_name varchar(255),
    sort_order integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_showcase_participants_session ON showcase_participants (session_id, sort_order);

-- Consultas JQL salvas por squad. O servidor só guarda o texto; a consulta ao Jira continua no navegador.
-- squad_id guardado em MAIÚSCULAS; visibility: SQUAD (a squad inteira vê) ou PRIVATE (só quem criou).
CREATE TABLE IF NOT EXISTS showcase_jql_queries (
    id varchar(36) PRIMARY KEY,
    squad_id varchar(255) NOT NULL,
    name varchar(120) NOT NULL,
    jql text NOT NULL,
    visibility varchar(10) NOT NULL DEFAULT 'PRIVATE',
    created_by varchar(255) NOT NULL,
    created_at timestamp(6) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_showcase_jql_squad ON showcase_jql_queries (squad_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_showcase_jql_owner_name ON showcase_jql_queries (squad_id, created_by, (LOWER(name)));
