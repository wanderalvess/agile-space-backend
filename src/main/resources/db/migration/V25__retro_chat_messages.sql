-- Chat do time na retrospectiva (mesmo modelo do chat do poker).
-- Canais: 'geral', 'role-{categoria}', 'dm_{uidA}_{uidB}'. Idempotente.
CREATE TABLE IF NOT EXISTS public.retro_chat_messages (
    id character varying(255) NOT NULL PRIMARY KEY,
    board_id character varying(255) NOT NULL,
    channel_id character varying(255) NOT NULL,
    sender_id character varying(255) NOT NULL,
    sender_name character varying(255) NOT NULL,
    sender_category character varying(255),
    text text NOT NULL,
    kind character varying(20) NOT NULL,
    created_at character varying(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_retro_chat_board_channel ON public.retro_chat_messages (board_id, channel_id);
