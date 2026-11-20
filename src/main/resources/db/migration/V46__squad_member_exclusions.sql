-- Pessoas removidas à mão do time. O sync do Jira semeia o roster com quem aparece como responsável de issue;
-- sem esta lista, quem a liderança removeu voltava na sincronização seguinte. Só cria tabela e índice (idempotente).
CREATE TABLE IF NOT EXISTS squad_member_exclusions (
    db_id            VARCHAR(255) PRIMARY KEY,
    squad_id         VARCHAR(255) NOT NULL,
    jira_account_id  VARCHAR(255) NOT NULL,
    email            VARCHAR(255),
    removed_by       VARCHAR(255),
    removed_at       VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_squad_member_exclusions_squad
    ON squad_member_exclusions (squad_id);
