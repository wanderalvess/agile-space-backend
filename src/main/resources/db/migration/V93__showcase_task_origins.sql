-- Origem dos textos que a importação do Jira põe nos campos de um card da Review: de onde veio (campo do Jira, descrição,
-- comentário de alguém, subtarefa, issue pai) e o texto original do Jira, para "Restaurar do Jira" depois de editar.
-- task_id sem FK de propósito: showcase_tasks é regravada por inteiro a cada save da sessão (como showcase_task_files).
-- Idempotente.
CREATE TABLE IF NOT EXISTS public.showcase_task_origins (
    id varchar(255) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES public.showcase_sessions (id) ON DELETE CASCADE,
    task_id varchar(255) NOT NULL,
    field_name varchar(30) NOT NULL,
    source_kind varchar(20) NOT NULL,
    source_label varchar(300) NOT NULL,
    original_text text NOT NULL,
    imported_by varchar(255),
    imported_at timestamp(6) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_showcase_task_origins_task_field
    ON public.showcase_task_origins (session_id, task_id, field_name);
