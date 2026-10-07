-- Título (summary do Jira) da própria issue. Sem ele o frontend caía para a chave da issue e o Cronograma
-- mostrava "DDWMISSI-123 DDWMISSI-123". Linhas já sincronizadas ficam sem título até a próxima sincronização.
ALTER TABLE squad_issue_snapshots ADD COLUMN IF NOT EXISTS title text;
