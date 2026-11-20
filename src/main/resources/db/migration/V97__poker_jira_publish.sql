-- Publicação do resultado do Poker no Jira (renomear a pai, criar sub-tarefas, comentar).
--
-- squad_subtask_templates: quais sub-tarefas cada squad/projeto cria por tipo de issue pai. Os nomes dos tipos
-- de sub-tarefa são os do Jira daquele projeto, configurados na tela (não existe nome fixo no código).
-- jira_publish_jobs/steps: fila com estado. Cada passo é uma única ação no Jira, executada na ordem; passo
-- concluído nunca repete, então a retentativa continua do primeiro passo que falhou (sem duplicar).
CREATE TABLE IF NOT EXISTS squad_subtask_templates (
    id                   VARCHAR(36)  PRIMARY KEY,
    squad_id             VARCHAR(100) NOT NULL,
    parent_issue_type    VARCHAR(100),                 -- NULL = vale para qualquer tipo de issue pai
    jira_issue_type_name VARCHAR(100) NOT NULL,        -- nome exato do tipo de sub-tarefa no Jira do projeto
    role                 VARCHAR(10)  NOT NULL,        -- DEV | QA | NONE (sem horas)
    title_template       TEXT         NOT NULL,
    comment_template     TEXT,
    position             INTEGER      NOT NULL DEFAULT 0,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_squad_subtask_templates_squad ON squad_subtask_templates (squad_id, position);

CREATE TABLE IF NOT EXISTS jira_publish_jobs (
    id             VARCHAR(36)  PRIMARY KEY,
    squad_id       VARCHAR(100) NOT NULL,
    room_id        VARCHAR(100),
    issue_id       VARCHAR(100),                       -- id da tarefa na fila do Poker
    parent_key     VARCHAR(60)  NOT NULL,
    parent_summary TEXT,
    requested_by   VARCHAR(100) NOT NULL,
    status         VARCHAR(20)  NOT NULL,              -- PENDING | RUNNING | DONE | ERROR
    error_message  TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_jira_publish_jobs_room ON jira_publish_jobs (room_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_jira_publish_jobs_squad ON jira_publish_jobs (squad_id, created_at DESC);

CREATE TABLE IF NOT EXISTS jira_publish_steps (
    id            VARCHAR(36)  PRIMARY KEY,
    job_id        VARCHAR(36)  NOT NULL REFERENCES jira_publish_jobs (id) ON DELETE CASCADE,
    position      INTEGER      NOT NULL,
    kind          VARCHAR(30)  NOT NULL,               -- RENAME_PARENT | CREATE_SUBTASK | ADD_COMMENT
    target_key    VARCHAR(60),                         -- issue alvo (pai, ou sub criada em outro passo)
    depends_on    VARCHAR(36),                         -- passo que cria a sub onde este comenta
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL,               -- PENDING | DONE | ERROR | SKIPPED
    result_key    VARCHAR(60),
    error_message TEXT,
    attempts      INTEGER      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_jira_publish_steps_job ON jira_publish_steps (job_id, position);
