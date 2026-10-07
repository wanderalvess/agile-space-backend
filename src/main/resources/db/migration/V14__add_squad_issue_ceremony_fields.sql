-- Consolida os campos de cerimônia (Poker/Planner/Showcase) que antes viviam numa tabela
-- work_items separada, nunca alimentada pelo sync real do Jira. Passam a viver na mesma
-- linha de squad_issue_snapshots que o sync já popula com dado real de status/assignee/
-- estimate. ceremony_status é o estágio da cerimônia (backlog/committed/delivered/
-- rejected/carried_over) — não confundir com "status", que é o status bruto do Jira.
ALTER TABLE squad_issue_snapshots
    ADD COLUMN IF NOT EXISTS ceremony_status character varying(255),
    ADD COLUMN IF NOT EXISTS points_estimated double precision,
    ADD COLUMN IF NOT EXISTS decision_feedback text,
    ADD COLUMN IF NOT EXISTS decided_at character varying(255);
