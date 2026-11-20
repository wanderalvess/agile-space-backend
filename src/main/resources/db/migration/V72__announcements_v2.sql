-- Avisos v2: tipo, público (todos / squads / papéis), vigência, link e dispensa por usuário.
-- Avisos antigos viram INFO para todos, vigentes desde a criação e sem fim.
ALTER TABLE global_announcements ADD COLUMN IF NOT EXISTS type VARCHAR(20);
ALTER TABLE global_announcements ADD COLUMN IF NOT EXISTS audience_type VARCHAR(10);
ALTER TABLE global_announcements ADD COLUMN IF NOT EXISTS valid_from TIMESTAMP;
ALTER TABLE global_announcements ADD COLUMN IF NOT EXISTS valid_until TIMESTAMP;
ALTER TABLE global_announcements ADD COLUMN IF NOT EXISTS link VARCHAR(500);

UPDATE global_announcements SET type = 'INFO' WHERE type IS NULL;
UPDATE global_announcements SET audience_type = 'ALL' WHERE audience_type IS NULL;
UPDATE global_announcements SET valid_from = created_at WHERE valid_from IS NULL;

ALTER TABLE global_announcements ALTER COLUMN type SET NOT NULL;
ALTER TABLE global_announcements ALTER COLUMN audience_type SET NOT NULL;
ALTER TABLE global_announcements ALTER COLUMN valid_from SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_announcements_valid_from ON global_announcements (valid_from);

-- Público SQUADS: ids das squads (mesmo espaço de User.squadId / projects.id).
CREATE TABLE IF NOT EXISTS global_announcement_squads (
    announcement_id VARCHAR(255) NOT NULL REFERENCES global_announcements (id) ON DELETE CASCADE,
    squad_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (announcement_id, squad_id)
);

-- Público ROLES: UserRole (ADMIN/LEAD/MEMBER) ou roleKey de project_member_roles (AGILE_MASTER, TRIBE_LEAD...).
CREATE TABLE IF NOT EXISTS global_announcement_roles (
    announcement_id VARCHAR(255) NOT NULL REFERENCES global_announcements (id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (announcement_id, role)
);

CREATE TABLE IF NOT EXISTS announcement_dismissals (
    announcement_id VARCHAR(255) NOT NULL REFERENCES global_announcements (id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    dismissed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (announcement_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_announcement_dismissals_user ON announcement_dismissals (user_id);
