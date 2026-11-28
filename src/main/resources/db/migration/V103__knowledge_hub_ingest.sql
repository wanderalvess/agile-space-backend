-- Base de Conhecimento como fonte do Hub: tipo, origem, id estável (upsert), versão de origem, hash do
-- conteúdo e visibilidade. Aditiva e idempotente: documentos existentes ficam com doc_type NULL (= "doc"
-- comum da UI) e visibility 'shared' (comportamento atual: KB compartilhada entre squads).
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS doc_type character varying(30);
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS source character varying(20);
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS source_url text;
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS external_id character varying(255);
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS doc_version character varying(100);
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS content_hash character varying(64);
ALTER TABLE public.knowledge_kb ADD COLUMN IF NOT EXISTS visibility character varying(20) NOT NULL DEFAULT 'shared';

-- Chave de upsert da ingestão: (doc_type, external_id). Parcial para não afetar os documentos antigos.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_kb_type_external
    ON public.knowledge_kb (doc_type, external_id) WHERE external_id IS NOT NULL;

-- Ligações entre documentos (ex.: doc de código <-> página do TDN que descreve o mesmo assunto).
CREATE TABLE IF NOT EXISTS public.knowledge_doc_relation (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    related_document_id uuid NOT NULL,
    kind character varying(30) NOT NULL DEFAULT 'related',
    CONSTRAINT knowledge_doc_relation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_knowledge_doc_relation UNIQUE (document_id, related_document_id, kind)
);
CREATE INDEX IF NOT EXISTS idx_knowledge_doc_relation_related ON public.knowledge_doc_relation (related_document_id);
