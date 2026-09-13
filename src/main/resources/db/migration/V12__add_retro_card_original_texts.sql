-- Histórico de ideias fundidas por card (linha do tempo no frontend, em vez
-- de concatenar tudo dentro de `content`). Mesmo padrão de retro_card_votes.

CREATE TABLE IF NOT EXISTS retro_card_original_texts (
    card_id VARCHAR(255) NOT NULL,
    position INTEGER NOT NULL,
    text TEXT
);
