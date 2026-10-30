-- Ids dos campos de data e hora planejadas no Jira ("Data e Hora de Início"/"Data e Hora de Fim"),
-- por squad. Nulo = autodetectar pelo nome. Só ADD COLUMN: sem backfill, default nem drop.
-- target_start/target_end dos snapshots já são varchar(255), cabem um ISO-8601 com offset.
ALTER TABLE squads ADD COLUMN jira_start_field VARCHAR(100) NULL;
ALTER TABLE squads ADD COLUMN jira_end_field VARCHAR(100) NULL;
