-- Convites aceitos antes da correção do InviteService gravavam só squad_members. A sessão resolve a squad ativa
-- por project_member_roles, então essas pessoas voltavam ao onboarding a cada login e sumiam do roster e das
-- contagens do time. Cria o vínculo que faltou, sem liderança (mesma regra do /projects/{key}/join).
--
-- Só entram linhas "reivindicadas" por uma conta (claimed_by_uid) de squads que existem como projeto e cuja
-- conta ainda não tem vínculo no projeto (nem por user_id, nem por e-mail). Idempotente: rodar de novo não duplica.
INSERT INTO project_member_roles (
    id, project_id, role_name, role_key, jira_account_id, display_name, email, user_id, is_leadership, created_at, updated_at
)
SELECT
    gen_random_uuid()::text,
    sm.squad_id,
    COALESCE(NULLIF(TRIM(sm.role), ''), 'Developer'),
    COALESCE(
        NULLIF(REGEXP_REPLACE(UPPER(REPLACE(TRIM(COALESCE(sm.role, '')), ' ', '_')), '[^A-Z_]', '', 'g'), ''),
        'DEVELOPER'
    ),
    u.jira_account_id,
    COALESCE(NULLIF(TRIM(sm.display_name), ''), u.name),
    COALESCE(NULLIF(TRIM(sm.email), ''), u.email),
    sm.claimed_by_uid,
    FALSE,
    NOW(),
    NOW()
FROM squad_members sm
JOIN projects p ON p.id = sm.squad_id
JOIN users u ON u.id = sm.claimed_by_uid
WHERE sm.claimed_by_uid IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM project_member_roles pmr
      WHERE pmr.project_id = sm.squad_id
        AND (pmr.user_id = sm.claimed_by_uid
             OR (pmr.email IS NOT NULL AND u.email IS NOT NULL AND LOWER(pmr.email) = LOWER(u.email)))
  );

-- Quem não tinha projeto padrão passa a ter o da squad que já tinha marcado.
UPDATE users u
SET default_project_id = u.squad_id
WHERE (u.default_project_id IS NULL OR u.default_project_id = '')
  AND u.squad_id IS NOT NULL AND u.squad_id <> ''
  AND EXISTS (SELECT 1 FROM project_member_roles pmr WHERE pmr.project_id = u.squad_id AND pmr.user_id = u.id);
