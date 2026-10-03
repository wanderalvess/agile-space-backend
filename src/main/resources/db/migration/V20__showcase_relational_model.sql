-- Migra showcase_sessions de JSONB opaco (tasks/members) pra colunas e tabelas
-- relacionais, mesmo racional da V19 pra sprint_plannings: sem dado real em
-- produção ainda (times usando o legado), troca direta sem preservação de dado.

ALTER TABLE showcase_sessions
    DROP COLUMN IF EXISTS tasks,
    DROP COLUMN IF EXISTS members;

CREATE TABLE showcase_tasks (
    id varchar(255) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES showcase_sessions (id) ON DELETE CASCADE,
    task_key varchar(255),
    title text,
    description text,
    acceptance_criteria text,
    task_type varchar(255),
    status varchar(255),
    priority varchar(255),
    points double precision,
    card_kind varchar(255),
    chart_type varchar(255),
    chart_title varchar(255),
    chart_display varchar(255),
    assignee varchar(255),
    url text,
    decision varchar(255),
    preparation_status varchar(255),
    feedback text,
    project varchar(255),
    version_suporte varchar(255),
    version_master varchar(255),
    version_release varchar(255),
    version_develop varchar(255),
    approved_at varchar(255),
    decided_by varchar(255),
    decided_by_name varchar(255),
    decided_at varchar(255),
    problem text,
    solution text,
    evidence_dev varchar(255),
    evidence_qa varchar(255),
    screenshot text,
    video text,
    evidence_preference varchar(255),
    time_spent double precision,
    time_estimate double precision,
    planned_dev varchar(255),
    planned_qa varchar(255),
    planned_tu varchar(255),
    task_order integer
);
CREATE INDEX idx_showcase_tasks_session_id ON showcase_tasks (session_id);

CREATE TABLE showcase_task_metrics (
    task_id varchar(255) NOT NULL REFERENCES showcase_tasks (id) ON DELETE CASCADE,
    metric_field varchar(255),
    metric_value double precision,
    metric_order integer NOT NULL,
    PRIMARY KEY (task_id, metric_order)
);

CREATE TABLE showcase_members (
    id varchar(255) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES showcase_sessions (id) ON DELETE CASCADE,
    name varchar(255) NOT NULL,
    role varchar(255),
    avatar text,
    member_order integer
);
CREATE INDEX idx_showcase_members_session_id ON showcase_members (session_id);
