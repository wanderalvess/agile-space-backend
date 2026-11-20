-- Sessões (JWT) emitidas antes deste instante (epoch, segundos) deixam de valer.
-- Preenchido no logout e na redefinição de senha; NULL = nenhuma sessão invalidada.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tokens_valid_after BIGINT;
