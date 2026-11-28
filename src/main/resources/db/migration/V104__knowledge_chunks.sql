-- Trechos (chunks) da Base de Conhecimento para busca por seção. Aditiva e idempotente.
-- embedding fica em TEXT (JSON, mesmo formato de knowledge_kb.embedding): no volume atual (centenas de docs,
-- poucos milhares de trechos) a similaridade em memória basta; trocar por pgvector depois não muda o contrato.
-- tsv é gerada e indexada (GIN) para a parte textual da busca híbrida; o Hibernate não a mapeia.
CREATE TABLE IF NOT EXISTS public.knowledge_chunk (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    ordinal integer NOT NULL,
    heading_path character varying(1000),
    text text NOT NULL,
    text_hash character varying(64) NOT NULL,
    embedding text,
    created_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    tsv tsvector GENERATED ALWAYS AS (
        to_tsvector('portuguese'::regconfig, coalesce(heading_path, '') || ' ' || text)
    ) STORED,
    CONSTRAINT knowledge_chunk_pkey PRIMARY KEY (id),
    CONSTRAINT uq_knowledge_chunk_doc_ordinal UNIQUE (document_id, ordinal)
);

CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_tsv ON public.knowledge_chunk USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_pending ON public.knowledge_chunk (created_at) WHERE embedding IS NULL;
