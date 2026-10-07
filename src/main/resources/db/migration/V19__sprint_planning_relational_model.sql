-- Migra sprint_plannings de JSONB opaco (tasks/members/settings/imported_poker_room_ids)
-- pra colunas e tabelas relacionais. Sem dado real em produção ainda (times usando o
-- legado) — troca direta, sem script de preservação de dado.

ALTER TABLE sprint_plannings
    DROP COLUMN IF EXISTS tasks,
    DROP COLUMN IF EXISTS members,
    DROP COLUMN IF EXISTS settings,
    DROP COLUMN IF EXISTS imported_poker_room_ids;

ALTER TABLE sprint_plannings
    ADD COLUMN sprint_start_date varchar(255),
    ADD COLUMN working_days integer,
    ADD COLUMN focus_factor integer,
    ADD COLUMN dev_count integer,
    ADD COLUMN dev_absences integer,
    ADD COLUMN qa_count integer,
    ADD COLUMN qa_absences integer,
    ADD COLUMN is_detailed_mode boolean,
    ADD COLUMN default_dev_hours_per_day double precision,
    ADD COLUMN default_qa_hours_per_day double precision,
    ADD COLUMN ready_for_poker boolean;

CREATE TABLE sprint_planning_tasks (
    id varchar(255) PRIMARY KEY,
    planning_id varchar(255) NOT NULL REFERENCES sprint_plannings (id) ON DELETE CASCADE,
    name text NOT NULL,
    link text,
    description text,
    status varchar(255),
    assignee_id varchar(255),
    role varchar(255),
    hours double precision,
    start_date varchar(255),
    end_date varchar(255),
    task_order integer
);
CREATE INDEX idx_sprint_planning_tasks_planning_id ON sprint_planning_tasks (planning_id);

CREATE TABLE sprint_planning_subtasks (
    id varchar(255) PRIMARY KEY,
    task_id varchar(255) NOT NULL REFERENCES sprint_planning_tasks (id) ON DELETE CASCADE,
    name text NOT NULL,
    status varchar(255),
    link text,
    assignee_id varchar(255),
    role varchar(255),
    hours double precision,
    start_date varchar(255),
    end_date varchar(255),
    subtask_order integer
);
CREATE INDEX idx_sprint_planning_subtasks_task_id ON sprint_planning_subtasks (task_id);

CREATE TABLE sprint_planning_members (
    id varchar(255) PRIMARY KEY,
    planning_id varchar(255) NOT NULL REFERENCES sprint_plannings (id) ON DELETE CASCADE,
    name varchar(255) NOT NULL,
    role varchar(255),
    focus_factor integer,
    days_off integer,
    hours_per_day double precision,
    member_order integer
);
CREATE INDEX idx_sprint_planning_members_planning_id ON sprint_planning_members (planning_id);

CREATE TABLE sprint_planning_imported_poker_rooms (
    planning_id varchar(255) NOT NULL REFERENCES sprint_plannings (id) ON DELETE CASCADE,
    poker_room_id varchar(255),
    room_order integer NOT NULL,
    PRIMARY KEY (planning_id, room_order)
);
