-- Convite real (token/link) para vincular um usuário a um squad + papel de negócio,
-- sem depender do e-mail bater exatamente com o que veio do Jira.

CREATE TABLE IF NOT EXISTS invites (
    id VARCHAR(64) PRIMARY KEY,
    token VARCHAR(64) NOT NULL,
    squad_id VARCHAR(100) NOT NULL,
    role_name VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    invited_by VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    accepted_by_user_id VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_invites_token ON invites (token);
CREATE INDEX IF NOT EXISTS idx_invites_squad_id ON invites (squad_id);
CREATE INDEX IF NOT EXISTS idx_invites_status ON invites (status);
