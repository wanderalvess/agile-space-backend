-- Squads sem sync_owner_user_id continuam manuais mesmo com o sync agendado
-- ligado globalmente (app.squad.scheduled-sync.enabled) — opt-in por squad.
ALTER TABLE squads
    ADD COLUMN IF NOT EXISTS sync_owner_user_id character varying(255);
