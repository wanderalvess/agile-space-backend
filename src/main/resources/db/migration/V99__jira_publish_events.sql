-- Registro (log) de cada tentativa da fila de publicação no Jira: quem fez, o quê, deu certo ou não e a mensagem.
-- Aditiva: não altera as tabelas de jobs e passos. Apagar um job (descartar envio) apaga o log junto.
CREATE TABLE IF NOT EXISTS jira_publish_events (
    id            VARCHAR(36)  PRIMARY KEY,
    job_id        VARCHAR(36)  NOT NULL REFERENCES jira_publish_jobs (id) ON DELETE CASCADE,
    step_position INTEGER,                              -- nulo nos eventos do envio como um todo
    kind          VARCHAR(30),                          -- RENAME_PARENT | CREATE_SUBTASK | ADD_COMMENT
    action        VARCHAR(20)  NOT NULL,                -- SUBMITTED | RETRY | STEP_DONE | STEP_ERROR | JOB_DONE | VERIFIED
    ok            BOOLEAN,
    message       TEXT,
    result_key    VARCHAR(60),                          -- chave/id criado no Jira, quando houver
    user_id       VARCHAR(100),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_jira_publish_events_job ON jira_publish_events (job_id, created_at);
