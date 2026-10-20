-- Sprint Planner (E1a): escopo (pais do Jira) e distribuição (subtarefas). Só o modelo; endpoints na E3/E4.

CREATE TABLE sprint_plan_items (
  id               varchar(255) PRIMARY KEY,
  plan_id          varchar(255) NOT NULL REFERENCES sprint_plans(id) ON DELETE CASCADE,
  parent_key       varchar(64) NOT NULL,
  title_snapshot   text,
  title_dev_hours  numeric(7,2),
  title_qa_hours   numeric(7,2),
  sort_order       integer NOT NULL,
  carry_over       boolean NOT NULL DEFAULT false,
  added_by         varchar(255) NOT NULL,
  added_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (plan_id, parent_key)
);
CREATE INDEX ix_sprint_plan_items_plan_order ON sprint_plan_items(plan_id, sort_order);

CREATE TABLE sprint_plan_assignments (
  id               varchar(255) PRIMARY KEY,
  plan_id          varchar(255) NOT NULL REFERENCES sprint_plans(id) ON DELETE CASCADE,
  item_id          varchar(255) NOT NULL REFERENCES sprint_plan_items(id) ON DELETE CASCADE,
  subtask_key      varchar(64) NOT NULL,
  jira_account_id  varchar(255),
  start_date       date,
  end_date         date,
  class            varchar(16) NOT NULL,
  hours            numeric(7,2),
  version          bigint NOT NULL DEFAULT 0,
  updated_by       varchar(255),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (plan_id, subtask_key),
  CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);
CREATE INDEX ix_sprint_plan_assignments_person ON sprint_plan_assignments(plan_id, jira_account_id);
