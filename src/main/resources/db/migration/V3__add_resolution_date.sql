-- Data real de conclusão (Jira `resolutiondate`) — ao contrário de
-- updated_at_jira (que muda a QUALQUER edição, inclusive semanas depois de
-- fechada), resolutiondate só é setado uma vez, quando a issue resolve/fecha.
-- Usado por inferSlip (SquadPlansTimeline.tsx) pro sinal "concluiu atrasado"
-- sem falso-positivo de edição tardia não relacionada.
ALTER TABLE squad_issue_snapshots
    ADD COLUMN IF NOT EXISTS resolution_date VARCHAR(10);
