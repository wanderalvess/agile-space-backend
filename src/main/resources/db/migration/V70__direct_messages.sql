-- Mensagens diretas entre duas pessoas que compartilham ao menos uma squad.
-- Uma conversa por par: user_a_id < user_b_id (ordem lexicográfica), com a última leitura de cada lado.
CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(36) PRIMARY KEY,
    user_a_id VARCHAR(255) NOT NULL,
    user_b_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_read_at_a TIMESTAMP,
    last_read_at_b TIMESTAMP,
    CONSTRAINT uq_conversations_pair UNIQUE (user_a_id, user_b_id),
    CONSTRAINT ck_conversations_order CHECK (user_a_id < user_b_id)
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_a ON conversations (user_a_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_user_b ON conversations (user_b_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS direct_messages (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_id VARCHAR(255) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_direct_messages_conversation ON direct_messages (conversation_id, created_at DESC);
