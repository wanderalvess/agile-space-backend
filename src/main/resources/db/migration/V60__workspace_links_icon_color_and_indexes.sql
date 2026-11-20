-- Meu Espaço: atalhos passam a guardar ícone e cor escolhidos na tela (antes eram descartados),
-- a URL do atalho deixa de estourar em 255 caracteres, e as listagens por usuário ganham índice.
-- Base de Conhecimento: índices de listagem e de conversas.
-- Aditiva e idempotente: não apaga nem reescreve dados.
ALTER TABLE public.user_quick_links ADD COLUMN IF NOT EXISTS icon_type character varying(32);
ALTER TABLE public.user_quick_links ADD COLUMN IF NOT EXISTS color character varying(160);
ALTER TABLE public.user_quick_links ALTER COLUMN url TYPE character varying(2048);
ALTER TABLE public.user_kanban_cards ALTER COLUMN origin_link TYPE character varying(2048);

CREATE INDEX IF NOT EXISTS idx_user_kanban_cards_user ON public.user_kanban_cards (user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_sticky_notes_user ON public.user_sticky_notes (user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_quick_links_user ON public.user_quick_links (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_snippets_user ON public.user_snippets (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_knowledge_kb_status_updated ON public.knowledge_kb (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_conversations_user ON public.knowledge_conversations (user_id, updated_at DESC);
