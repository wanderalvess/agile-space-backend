-- ID do quadro Scrum do Jira por squad (antes o frontend enviava o valor mas o backend o descartava).
ALTER TABLE squads ADD COLUMN IF NOT EXISTS rapid_view_id VARCHAR(50);
