-- Garante a tabela do chat da sala de poker. A V1_1 (baseline) a cria, mas bancos
-- em que a baseline foi aplicada antes dessa tabela existir ficaram sem ela, e
-- GET /api/poker/{roomId}/chat respondia erro. Idempotente: no-op onde já existe.
CREATE TABLE IF NOT EXISTS public.poker_chat_messages (
    id character varying(255) NOT NULL PRIMARY KEY,
    room_id character varying(255) NOT NULL,
    channel_id character varying(255) NOT NULL,
    sender_id character varying(255) NOT NULL,
    sender_name character varying(255) NOT NULL,
    sender_category character varying(255),
    text text NOT NULL,
    kind character varying(20) NOT NULL,
    created_at character varying(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_poker_chat_room_channel ON public.poker_chat_messages (room_id, channel_id);
