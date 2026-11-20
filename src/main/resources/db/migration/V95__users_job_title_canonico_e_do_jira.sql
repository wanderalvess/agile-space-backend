-- Cargo (users.job_title) canônico e preenchido a partir do Jira.
-- Decisão do produto: o cargo definido na administração é o cargo de ponta a ponta. Os nomes canônicos dos dois cargos
-- duplicados são "Desenvolvedor(a)" e "Analista de QA". Quem está sem cargo recebe o que o Jira trouxe (role do roster).
--
-- Mesma regra do CargoNames.java (comparação EXATA, sem acento/caixa/espaços nas pontas, nunca "contém"):
--   {developer, desenvolvedor, desenvolvedora, desenvolvedor(a), dev}                                -> Desenvolvedor(a)
--   {qa, analista de qa, qa engineer, quality assurance, analista de qualidade}                       -> Analista de QA
-- Liderança, design, UX, SME, Stakeholder e Observador NÃO são mapeados. NUNCA toca em users.role (nível de acesso) nem
-- em squad_members.role / project_member_roles.role_name (papel por squad). Idempotente: rodar de novo não muda nada.

CREATE FUNCTION pg_temp.cargo_canonico(raw text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN raw IS NULL OR btrim(raw) = '' THEN NULL
        WHEN k IN ('developer', 'desenvolvedor', 'desenvolvedora', 'desenvolvedor(a)', 'dev') THEN 'Desenvolvedor(a)'
        WHEN k IN ('qa', 'analista de qa', 'qa engineer', 'quality assurance', 'analista de qualidade') THEN 'Analista de QA'
        ELSE btrim(raw)
    END
    FROM (SELECT regexp_replace(
                     translate(lower(btrim(raw)), 'áàâãäéèêëíìîïóòôõöúùûüç', 'aaaaaeeeeiiiiooooouuuuc'),
                     '\s+', ' ', 'g') AS k) t
$$;

-- 1) Cargo em branco vira NULL (para o preenchimento abaixo tratar tudo igual).
UPDATE users SET job_title = NULL WHERE job_title IS NOT NULL AND btrim(job_title) = '';

-- 2) Normaliza só as duas famílias de grafia; qualquer outro cargo fica exatamente como está.
UPDATE users
   SET job_title = pg_temp.cargo_canonico(job_title)
 WHERE job_title IS NOT NULL
   AND pg_temp.cargo_canonico(job_title) IN ('Desenvolvedor(a)', 'Analista de QA')
   AND job_title <> pg_temp.cargo_canonico(job_title);

-- 3) Quem está sem cargo recebe a role do roster do Jira: primeiro as linhas ligadas à conta (claimed_by_uid) e, na
--    falta, as da mesma conta do Jira (users.jira_account_id = squad_members.jira_account_id, como o resto do sistema
--    liga a pessoa ao roster desde a Etapa 5 do vínculo). Dentro do mesmo critério vale a linha mais recente por
--    updated_at (ISO-8601 em texto); linhas sem role são ignoradas.
UPDATE users u
   SET job_title = pg_temp.cargo_canonico(c.role)
  FROM (
        SELECT DISTINCT ON (uid) uid, role
          FROM (
                SELECT sm.claimed_by_uid AS uid, sm.role, 0 AS prio, sm.updated_at, sm.db_id
                  FROM squad_members sm
                 WHERE sm.claimed_by_uid IS NOT NULL AND btrim(coalesce(sm.role, '')) <> ''
                UNION ALL
                SELECT u2.id AS uid, sm.role, 1 AS prio, sm.updated_at, sm.db_id
                  FROM squad_members sm
                  JOIN users u2 ON u2.jira_account_id = sm.jira_account_id
                 WHERE btrim(coalesce(u2.jira_account_id, '')) <> '' AND btrim(coalesce(sm.role, '')) <> ''
               ) cand
         ORDER BY uid, prio, updated_at DESC NULLS LAST, db_id
       ) c
 WHERE c.uid = u.id
   AND (u.job_title IS NULL OR btrim(u.job_title) = '');
