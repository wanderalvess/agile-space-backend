-- Necessário pra taxa de escape de bugs (Fase 8): bug "escapou" quando foi criado dentro
-- da janela da sprint e não foi resolvido antes dela fechar. Sem isto, squad_issue_snapshots
-- só tinha updated_at_jira (muda a cada edição), não created (imutável).
ALTER TABLE squad_issue_snapshots
    ADD COLUMN IF NOT EXISTS created_at_jira character varying(255);
