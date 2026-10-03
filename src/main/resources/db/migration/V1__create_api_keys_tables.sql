-- Tabela de Chaves de API
-- Armazena chaves de acesso programático com scopes e restrições de squad
CREATE TABLE IF NOT EXISTS api_keys (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    key_hash VARCHAR(64) NOT NULL UNIQUE NOT NULL,
    owner_user_id VARCHAR(255),
    owner_role VARCHAR(50),
    squad_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    CONSTRAINT chk_key_hash_length CHECK (length(key_hash) = 64)
);

-- Índice pra lookup rápido de chaves por hash (usado em cada requisição)
CREATE INDEX IF NOT EXISTS idx_api_keys_hash_not_revoked
    ON api_keys(key_hash)
    WHERE revoked_at IS NULL;

-- Índice pra auditoria e limpeza de chaves antigas/revogadas
CREATE INDEX IF NOT EXISTS idx_api_keys_revoked_at
    ON api_keys(revoked_at);

CREATE INDEX IF NOT EXISTS idx_api_keys_owner_user_id
    ON api_keys(owner_user_id);

-- Tabela de Escopos
-- Define quais operações uma chave pode fazer (KNOWLEDGE_READ, POKER_WRITE, etc)
CREATE TABLE IF NOT EXISTS api_key_scopes (
    api_key_id UUID NOT NULL,
    scope VARCHAR(50) NOT NULL,
    PRIMARY KEY (api_key_id, scope),
    FOREIGN KEY (api_key_id) REFERENCES api_keys(id) ON DELETE CASCADE,
    CONSTRAINT chk_scope_valid CHECK (scope IN (
        'KNOWLEDGE_READ',
        'KNOWLEDGE_WRITE',
        'SQUAD_READ',
        'SQUAD_WRITE',
        'PROMPTHUB_READ',
        'PROMPTHUB_WRITE',
        'POKER_READ',
        'POKER_WRITE'
    ))
);
