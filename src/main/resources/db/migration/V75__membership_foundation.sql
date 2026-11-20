-- Fundação do modelo de vínculo (Pessoa x Squad x Papel) em project_member_roles ("pmr"). ETAPA 0: só aditivo,
-- idempotente e sem apagar nada. Documento do plano: docs/vinculo-auditoria-e-plano.md.
--
-- 1) Índices que o resolvedor de acesso (UserProjectResolverService.resolveUserAccess) usa a cada requisição:
--    findByUserId (user_id), findByEmailIgnoreCase (o Spring Data gera upper(email)) e a @Query de e-mail/conta do
--    Jira (lower(email)). Antes só havia índice em project_id, email (case-sensitive) e jira_account_id.
CREATE INDEX IF NOT EXISTS idx_pmr_user_id ON project_member_roles (user_id);
CREATE INDEX IF NOT EXISTS idx_pmr_email_lower ON project_member_roles (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_pmr_email_upper ON project_member_roles (UPPER(email));

-- 2) De onde veio a linha: JIRA, MANUAL, INVITE, CLAIM, APPROVED, BACKFILL ou MIGRATION. NULL = legado (linha criada
--    antes desta coluna; não dá para saber, então não se chuta). A reimportação do Jira só remove linha NULL/JIRA
--    sem user_id; as demais são preservadas (ver JiraProfieldsService.planTeamImport).
ALTER TABLE project_member_roles ADD COLUMN IF NOT EXISTS source VARCHAR(20);

-- 3) Backfill CONSERVADOR de user_id: linha sem conta cujo e-mail (sem caixa/espaços) casa com EXATAMENTE uma conta
--    em todo o cadastro (HAVING COUNT(*) = 1) e essa conta está ativa. Nunca toca linha que já tem user_id nem linha
--    com origem registrada; não casa por jira_account_id (e pula a linha cujo jira_account_id pertence a outra conta).
--    Marca source = 'BACKFILL' para a reversão: UPDATE project_member_roles SET user_id = NULL, source = NULL
--    WHERE source = 'BACKFILL'. Idempotente: depois de rodar, a linha já tem user_id e sai do filtro.
UPDATE project_member_roles pmr
SET user_id = m.user_id,
    source = 'BACKFILL',
    updated_at = NOW()
FROM (
    SELECT LOWER(BTRIM(email)) AS email_norm, MIN(id) AS user_id
    FROM users
    WHERE email IS NOT NULL AND BTRIM(email) <> ''
    GROUP BY LOWER(BTRIM(email))
    HAVING COUNT(*) = 1 AND BOOL_AND(is_active IS TRUE)
) m
WHERE pmr.user_id IS NULL
  AND pmr.source IS NULL
  AND pmr.email IS NOT NULL
  AND LOWER(BTRIM(pmr.email)) = m.email_norm
  AND NOT EXISTS (
      SELECT 1 FROM users other
      WHERE pmr.jira_account_id IS NOT NULL AND pmr.jira_account_id <> ''
        AND other.jira_account_id = pmr.jira_account_id
        AND other.id <> m.user_id
  );

-- 4) Mesma regra da V69, reaplicada: roster (squad_members) reivindicado por uma conta e sem vínculo no projeto vira
--    pmr SEM liderança, só para squads que existem em projects. source = 'MIGRATION'. NOT EXISTS => idempotente.
INSERT INTO project_member_roles (
    id, project_id, role_name, role_key, jira_account_id, display_name, email, user_id, is_leadership, source,
    created_at, updated_at
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
    'MIGRATION',
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
