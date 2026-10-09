-- Biblioteca de IA: índices para as consultas de listagem (visibilidade/autor/dono) e para
-- as junções de tags, comentários e itens de coleção (sem eles cada abertura faz seq scan).
CREATE INDEX IF NOT EXISTS idx_prompt_hub_visibility_updated ON public.prompt_hub (visibility, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_prompt_hub_author ON public.prompt_hub (author_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_prompt_tags_prompt ON public.prompt_tags (prompt_id);
CREATE INDEX IF NOT EXISTS idx_prompt_comments_prompt ON public.prompt_comments (prompt_id, created_at);
CREATE INDEX IF NOT EXISTS idx_prompt_collections_owner ON public.prompt_collections (owner_id, visibility);
CREATE INDEX IF NOT EXISTS idx_prompt_collections_visibility ON public.prompt_collections (visibility);
CREATE INDEX IF NOT EXISTS idx_prompt_collection_items_prompt ON public.prompt_collection_items (prompt_id);
