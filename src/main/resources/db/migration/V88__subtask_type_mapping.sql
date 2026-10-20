-- Sprint Planner (E1b): tipos de issue conhecidos por squad (cache do Jira) e mapeamento tipo de subtarefa -> classe.
--
-- ADITIVA: só cria tabelas novas. A semente (Codificação/Code Review = DEV; Execução de TI/Teste Automatizado/
-- Documento Técnico = QA) mora no SubtaskTypeMappingService e não aqui, porque os ids de issuetype variam por instância
-- do Jira (a instalação nova pode ter outros ids; o service casa por id e, na falta, pelo nome).
-- Datas como varchar ISO-8601, no mesmo padrão das demais tabelas do squad.

CREATE TABLE jira_issue_types_cache (
    squad_id   varchar(255) NOT NULL,
    type_id    varchar(32)  NOT NULL,
    name       varchar(255) NOT NULL,
    is_subtask boolean      NOT NULL DEFAULT false,
    fetched_at varchar(64)  NOT NULL,
    PRIMARY KEY (squad_id, type_id)
);

-- sprint_key = id da sprint do Jira que o mapeamento vale, ou 'GLOBAL'. Sem FK para sprint_plans (outra entrega):
-- o plano é referenciado por squad_id + sprint.
CREATE TABLE squad_subtask_type_mapping (
    id                    varchar(255) PRIMARY KEY,           -- {squadId}|{sprintKey}|{typeId}
    squad_id              varchar(255) NOT NULL,
    sprint_key            varchar(64)  NOT NULL,
    type_id               varchar(32)  NOT NULL,
    type_name             varchar(255) NOT NULL,
    class                 varchar(32)  NOT NULL,              -- DEV | QA | FORA_DA_CONTA | SEM_CLASSIFICACAO
    origin                varchar(16)  NOT NULL,              -- PADRAO | HERDADO | MANUAL
    qa_by_title_exception boolean      NOT NULL DEFAULT false, -- Code Review com "QA" no título conta como QA
    default_hours         numeric(5,2),                       -- Code Review = 1 h quando a subtarefa não tem estimativa
    updated_by            varchar(255),
    updated_at            varchar(64)  NOT NULL,
    CONSTRAINT ux_subtask_type_mapping UNIQUE (squad_id, sprint_key, type_id),
    CONSTRAINT ck_subtask_type_mapping_class CHECK (class IN ('DEV', 'QA', 'FORA_DA_CONTA', 'SEM_CLASSIFICACAO')),
    CONSTRAINT ck_subtask_type_mapping_origin CHECK (origin IN ('PADRAO', 'HERDADO', 'MANUAL'))
);
CREATE INDEX ix_subtask_type_mapping_squad ON squad_subtask_type_mapping (squad_id, updated_at);
