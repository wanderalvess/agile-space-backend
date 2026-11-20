-- Base de Conhecimento: squad do documento (para a liderança poder editar) e histórico de versões.
-- Aditiva e idempotente: documentos existentes ficam sem squad (só autor e ADMIN editam) e ganham a
-- versão inicial sob demanda, na primeira edição. Nada é apagado nem reescrito.
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS squad_id character varying(255);

CREATE TABLE IF NOT EXISTS public.knowledge_document_versions (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_number integer NOT NULL,
    title character varying(255) NOT NULL,
    content text,
    tags text,
    category character varying(255),
    full_path character varying(255),
    changed_by character varying(255),
    changed_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT knowledge_document_versions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_knowledge_document_version UNIQUE (document_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_versions_doc ON public.knowledge_document_versions (document_id, version_number DESC);
