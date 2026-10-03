ALTER TABLE retro_boards
    ADD COLUMN IF NOT EXISTS sprint_id VARCHAR(100);

ALTER TABLE action_plans
    ADD COLUMN IF NOT EXISTS sprint_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_retro_boards_sprint_id ON retro_boards (sprint_id);
CREATE INDEX IF NOT EXISTS idx_action_plans_sprint_id ON action_plans (sprint_id);
