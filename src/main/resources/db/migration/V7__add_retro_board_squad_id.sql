-- Liga o board de retro à squad de verdade (Squad.id), sem FK — mesmo padrão
-- de sprint_id. `team` continua só o nome de exibição digitado na criação.
-- Nullable: boards antigos ficam sem squad_id até serem recriados/migrados.

ALTER TABLE retro_boards
    ADD COLUMN IF NOT EXISTS squad_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_retro_boards_squad_id ON retro_boards (squad_id);
