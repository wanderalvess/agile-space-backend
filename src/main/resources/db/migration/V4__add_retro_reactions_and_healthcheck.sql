-- Check-in inicial configurável pelo facilitador (retro_boards) e reações
-- rápidas por card (retro_cards), além da resposta de cada participante ao
-- check-in. Tudo opcional/nullable: retros existentes continuam funcionando
-- sem esses campos.

ALTER TABLE retro_boards
    ADD COLUMN IF NOT EXISTS health_check_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS health_check_question TEXT;

ALTER TABLE retro_cards
    ADD COLUMN IF NOT EXISTS reactions JSONB;

ALTER TABLE retro_participants
    ADD COLUMN IF NOT EXISTS health_check_answer VARCHAR(20);
