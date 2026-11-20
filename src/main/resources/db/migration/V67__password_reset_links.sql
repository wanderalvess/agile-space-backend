-- Redefinição de senha por link de uso único: o administrador gera o link, a pessoa define a própria senha.
-- Só o hash (SHA-256) do token é guardado; o token em si é mostrado uma vez a quem gerou.
ALTER TABLE password_reset_requests ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
ALTER TABLE password_reset_requests ADD COLUMN IF NOT EXISTS token_expires_at TIMESTAMP;
ALTER TABLE password_reset_requests ADD COLUMN IF NOT EXISTS used_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_reset_token_hash ON password_reset_requests (token_hash);
-- Senhas temporárias legadas deixam de existir.
UPDATE password_reset_requests SET temp_password = NULL WHERE temp_password IS NOT NULL;
