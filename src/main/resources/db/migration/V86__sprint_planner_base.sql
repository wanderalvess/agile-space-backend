-- Sprint Planner (E1a): plano por squad+sprint e o que é só dele (inclusão/foco por pessoa, tipos de reserva,
-- ausências e feriados). Papel/dias/horas por pessoa continuam em squad_person_configs (não duplicar).
-- Aditiva: só cria tabelas novas.

CREATE TABLE sprint_plans (
  id               varchar(255) PRIMARY KEY,
  squad_id         varchar(255) NOT NULL REFERENCES squads(id),
  jira_sprint_id   varchar(64),
  sprint_name      varchar(255) NOT NULL,
  start_date       date NOT NULL,
  end_date         date NOT NULL,
  status           varchar(16) NOT NULL DEFAULT 'RASCUNHO',
  goal             text,
  reserve_mode     varchar(16) NOT NULL DEFAULT 'HORAS',
  reserve_pct      numeric(5,2),
  focus_factor_pct numeric(5,2),
  created_by       varchar(255) NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          bigint NOT NULL DEFAULT 0,
  CHECK (end_date >= start_date)
);
-- Um plano por squad+sprint do Jira (decisão 6).
CREATE UNIQUE INDEX ux_sprint_plans_squad_sprint ON sprint_plans(squad_id, jira_sprint_id) WHERE jira_sprint_id IS NOT NULL;
CREATE INDEX ix_sprint_plans_squad_status ON sprint_plans(squad_id, status);

CREATE TABLE sprint_plan_person (
  plan_id          varchar(255) NOT NULL REFERENCES sprint_plans(id) ON DELETE CASCADE,
  jira_account_id  varchar(255) NOT NULL,
  included         boolean NOT NULL DEFAULT true,
  focus_factor_pct numeric(5,2),
  PRIMARY KEY (plan_id, jira_account_id)
);

CREATE TABLE sprint_plan_reserve_types (
  id            varchar(255) PRIMARY KEY,
  plan_id       varchar(255) NOT NULL REFERENCES sprint_plans(id) ON DELETE CASCADE,
  label         varchar(120) NOT NULL,
  hours_per_day numeric(5,2)
);
CREATE INDEX ix_sprint_plan_reserve_types_plan ON sprint_plan_reserve_types(plan_id);

CREATE TABLE sprint_plan_absences (
  id               varchar(255) PRIMARY KEY,
  plan_id          varchar(255) NOT NULL REFERENCES sprint_plans(id) ON DELETE CASCADE,
  jira_account_id  varchar(255) NOT NULL,
  start_date       date NOT NULL,
  end_date         date NOT NULL,
  hours_per_day    numeric(5,2),
  reason           varchar(255),
  created_by       varchar(255) NOT NULL,
  CHECK (end_date >= start_date)
);
CREATE INDEX ix_sprint_plan_absences_plan_person ON sprint_plan_absences(plan_id, jira_account_id);

CREATE TABLE sprint_plan_holidays (
  plan_id  varchar(255) NOT NULL REFERENCES sprint_plans(id) ON DELETE CASCADE,
  day      date NOT NULL,
  label    varchar(120),
  PRIMARY KEY (plan_id, day)
);
