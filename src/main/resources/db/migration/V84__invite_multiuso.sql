-- Convite de equipe (link multiuso): aditiva, nada é apagado. O convite individual continua igual
-- (multi_use = false, uses_count = 0). V83 fica reservada a outra frente.

ALTER TABLE invites ADD COLUMN IF NOT EXISTS multi_use BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE invites ADD COLUMN IF NOT EXISTS max_uses INTEGER;
ALTER TABLE invites ADD COLUMN IF NOT EXISTS uses_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE invites ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;

-- Quem entrou por cada convite de equipe: impede o uso duplo da mesma conta e alimenta a lista de quem criou.
CREATE TABLE IF NOT EXISTS invite_redemptions (
    id VARCHAR(64) PRIMARY KEY,
    invite_id VARCHAR(64) NOT NULL REFERENCES invites (id),
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_invite_redemptions_invite_user UNIQUE (invite_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_invite_redemptions_invite_id ON invite_redemptions (invite_id);
