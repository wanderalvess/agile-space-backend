-- Validade das chaves de API. Nulo = sem expiração (só o ADMIN cria assim).
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

-- As chaves que já existem ganham 90 dias a partir do deploy, para não quebrar integrações no primeiro dia.
-- Renovar (POST /api/api-keys/{id}/renew) reavalia o papel do dono. Só toca chaves ativas e ainda sem validade.
UPDATE api_keys SET expires_at = CURRENT_TIMESTAMP + INTERVAL '90 days'
WHERE expires_at IS NULL AND revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_api_keys_expires_at ON api_keys(expires_at);
