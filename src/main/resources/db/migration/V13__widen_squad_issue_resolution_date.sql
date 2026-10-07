-- resolution_date nasceu VARCHAR(10) (V3) supondo "YYYY-MM-DD", mas o
-- backend sempre gravou o timestamp ISO completo do Jira (fields.resolutiondate,
-- ex: "2026-09-01T14:23:45.000-0300") sem truncar - mesmo formato que due_date/
-- target_start/target_end/updated_at_jira/synced_at já usam nesta tabela.
-- Sync real de qualquer squad com issue resolvida falhava com
-- "value too long for type character varying(10)" no insert em lote.
ALTER TABLE squad_issue_snapshots
    ALTER COLUMN resolution_date TYPE VARCHAR(255);
