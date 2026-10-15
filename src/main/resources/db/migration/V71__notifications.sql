-- Notificações por usuário (sino da home). kind: INVITE | REMINDER | ACTION_ASSIGNED | MESSAGE | ANNOUNCEMENT.
-- dedupe_key evita repetir a mesma notificação para a mesma pessoa (ex.: lembrete de voto, 1 por sala).
CREATE TABLE IF NOT EXISTS notifications (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    title VARCHAR(200),
    text VARCHAR(500) NOT NULL,
    link VARCHAR(500),
    actor_id VARCHAR(255),
    squad_id VARCHAR(100),
    dedupe_key VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_user_dedupe ON notifications (user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
