-- Sprint Planner (E1b): colunas ADITIVAS no snapshot de issues do squad.
--
-- issue_type_id / status_id: ids do Jira (o snapshot só tinha os nomes).
-- own_original_estimate_sec / own_remaining_sec: estimativa PRÓPRIA da issue (timeoriginalestimate / timeestimate),
--   sem os aggregate*. estimate_sec/remaining_sec continuam como estão (agregado na pai), para não mudar métricas.
-- in_planner_backlog: a linha faz parte do backlog do Sprint Planner (Refinamento Concluído + sobra da sprint ativa e
--   subtarefas). O sync da sprint não apaga linha marcada: converte para sprint_id = 'PLANNER_BACKLOG'.
-- planner_synced_at: quando o sync do backlog gravou a linha pela última vez (ISO-8601).

ALTER TABLE squad_issue_snapshots
    ADD COLUMN issue_type_id varchar(32),
    ADD COLUMN status_id varchar(32),
    ADD COLUMN own_original_estimate_sec bigint,
    ADD COLUMN own_remaining_sec bigint,
    ADD COLUMN in_planner_backlog boolean NOT NULL DEFAULT false,
    ADD COLUMN planner_synced_at varchar(64);

CREATE INDEX ix_snap_squad_parent ON squad_issue_snapshots (squad_id, parent_key);
CREATE INDEX ix_snap_squad_planner_backlog ON squad_issue_snapshots (squad_id) WHERE in_planner_backlog;
