-- Plano de ação ganha squad: listagem por squad na home (GET /api/action-plans?squadId=).
-- Backfill só quando é derivável sem chute: "team" igual ao id de uma squad/projeto cadastrado,
-- ou a sprint do plano pertence a retros de uma única squad. O resto fica NULL.
ALTER TABLE action_plans ADD COLUMN IF NOT EXISTS squad_id VARCHAR(100);

UPDATE action_plans ap SET squad_id = s.id
FROM squads s
WHERE ap.squad_id IS NULL AND ap.team IS NOT NULL AND UPPER(TRIM(ap.team)) = UPPER(s.id);

UPDATE action_plans ap SET squad_id = p.id
FROM projects p
WHERE ap.squad_id IS NULL AND ap.team IS NOT NULL AND UPPER(TRIM(ap.team)) = UPPER(p.id);

UPDATE action_plans ap SET squad_id = r.squad_id
FROM (
    SELECT sprint_id, MIN(squad_id) AS squad_id
    FROM retro_boards
    WHERE sprint_id IS NOT NULL AND squad_id IS NOT NULL
    GROUP BY sprint_id
    HAVING COUNT(DISTINCT UPPER(squad_id)) = 1
) r
WHERE ap.squad_id IS NULL AND ap.sprint_id IS NOT NULL AND ap.sprint_id = r.sprint_id;

CREATE INDEX IF NOT EXISTS idx_action_plans_squad ON action_plans (squad_id, created_at DESC);
