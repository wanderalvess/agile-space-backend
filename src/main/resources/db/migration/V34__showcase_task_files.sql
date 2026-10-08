-- Arquivos anexados aos cards da Review (evidências que não estão no Jira): PNG, JPEG ou PDF.
-- Só os metadados ficam aqui; o conteúdo fica no armazenamento de arquivos do backend
-- (storage_key é um nome gerado pelo servidor, nunca o nome enviado pelo usuário).
-- task_id sem FK de propósito: showcase_tasks é regravada por inteiro a cada save da sessão.
-- Idempotente.
CREATE TABLE IF NOT EXISTS public.showcase_task_files (
    id varchar(255) PRIMARY KEY,
    session_id varchar(255) NOT NULL REFERENCES public.showcase_sessions (id) ON DELETE CASCADE,
    task_id varchar(255) NOT NULL,
    original_name varchar(255) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_key varchar(255) NOT NULL,
    uploaded_by varchar(255),
    created_at timestamp(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_showcase_task_files_task
    ON public.showcase_task_files (session_id, task_id);
