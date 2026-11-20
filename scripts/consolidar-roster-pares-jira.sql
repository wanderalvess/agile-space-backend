-- Consolidacao UNICA do roster (squad_members) da homologacao: funde os pares "chave interna do Jira Server (JIRAUSER<n>,
-- sem e-mail) + nome de usuario (com e-mail/vinculo)" da MESMA pessoa em uma linha so. Mesma regra do codigo
-- (PersonIdentity + RosterMergeService), para o resultado ser o mesmo que a aplicacao produziria sozinha.
--
-- COMO USAR (psql, em horario calmo):
--   1. Troque a squad em "param" abaixo se precisar (padrao DDWMISSI).
--   2. Rode o script inteiro. Ele termina em ROLLBACK (ensaio): leia os SELECTs "ANTES", "PARES", "DEPOIS".
--   3. Se estiver tudo certo, troque o ROLLBACK final por COMMIT e rode de novo.
--   O script e idempotente: depois de aplicado, nao encontra mais pares e nao muda nada.
--
-- REGRA DO PAR (so funde quando e inequivoco):
--   * mesma squad e mesmo nome normalizado (sem acento, minusculas, espacos colapsados);
--   * exatamente UMA linha com chave JIRAUSER<n> sem e-mail e exatamente UMA outra linha com o nome, que nao e JIRAUSER;
--   * a outra linha tem e-mail (a chave interna nunca tem). Homonimos (3+ linhas com o nome, dois JIRAUSER, dois
--     usuarios) ficam de fora e aparecem em "AMBIGUOS" para decisao humana.
-- O QUE FICA: a linha do nome de usuario (e-mail, claimed_by_uid, cargo). Da linha JIRAUSER ela herda so o que lhe falta;
--   horas MANUAL_OVERRIDE da linha JIRAUSER so passam se a mantida estiver no padrao (nao calibrada). squad_person_configs
--   da chave removida vao para a chave mantida (sem sobrescrever o que a mantida ja tem). Nada e apagado sem backup.
--
-- ANTES DE COMMIT: tire um dump (scripts/backup-db.sh). Este script tambem cria as tabelas bkp_roster_* dentro da transacao.

BEGIN;

CREATE TEMP TABLE param ON COMMIT DROP AS SELECT 'DDWMISSI'::text AS squad_id;

-- nome normalizado (sem acento) usado para casar
CREATE TEMP TABLE roster ON COMMIT DROP AS
SELECT m.*,
       lower(regexp_replace(translate(btrim(coalesce(m.display_name, '')),
             'ÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇáàâãäéèêëíìîïóòôõöúùûüç', 'AAAAAEEEEIIIIOOOOOUUUUCaaaaaeeeeiiiiooooouuuuc'),
             '\s+', ' ', 'g')) AS name_key,
       (m.jira_account_id ~* '^JIRAUSER[0-9]+$') AS is_key
FROM squad_members m, param p
WHERE m.squad_id = p.squad_id;

-- Candidatos por nome: so funde quando ha exatamente 1 chave interna sem e-mail e 1 usuario com e-mail.
CREATE TEMP TABLE par ON COMMIT DROP AS
SELECT d.db_id AS drop_db_id, d.jira_account_id AS drop_acc,
       k.db_id AS keep_db_id, k.jira_account_id AS keep_acc, d.squad_id, d.name_key
FROM roster d
JOIN roster k ON k.name_key = d.name_key AND k.db_id <> d.db_id
WHERE d.name_key <> ''
  AND d.is_key AND (d.email IS NULL OR btrim(d.email) = '')
  AND NOT k.is_key AND k.email IS NOT NULL AND btrim(k.email) <> ''
  AND (SELECT count(*) FROM roster r WHERE r.name_key = d.name_key) = 2
  AND NOT EXISTS (SELECT 1 FROM roster r WHERE r.name_key = d.name_key AND r.db_id NOT IN (d.db_id, k.db_id));

\echo '=== ANTES: roster da squad ==='
SELECT db_id, jira_account_id, display_name, email, role, capacity_hours_per_day AS horas, override_type, claimed_by_uid
FROM roster ORDER BY name_key, is_key DESC;

\echo '=== PARES que serao fundidos (drop = JIRAUSER, keep = usuario) ==='
SELECT drop_acc, keep_acc, name_key FROM par ORDER BY name_key;

\echo '=== AMBIGUOS (mesmo nome, nao fundidos automaticamente; decidir a mao) ==='
SELECT name_key, count(*) AS linhas, array_agg(jira_account_id ORDER BY jira_account_id) AS contas
FROM roster WHERE name_key <> '' GROUP BY name_key
HAVING count(*) > 1 AND name_key NOT IN (SELECT name_key FROM par);

\echo '=== REFERENCIAS a chave que sera removida (so leitura; a aplicacao resolve o apelido JIRAUSER por nome) ==='
SELECT 'users' AS tabela, u.id, u.jira_account_id FROM users u WHERE u.id IN (SELECT drop_acc FROM par) OR u.jira_account_id IN (SELECT drop_acc FROM par)
UNION ALL SELECT 'project_member_roles', pmr.id::text, pmr.jira_account_id FROM project_member_roles pmr, param p
 WHERE pmr.project_id = p.squad_id AND pmr.jira_account_id IN (SELECT drop_acc FROM par)
UNION ALL SELECT 'squad_member_exclusions', e.db_id, e.jira_account_id FROM squad_member_exclusions e, param p
 WHERE e.squad_id = p.squad_id AND e.jira_account_id IN (SELECT drop_acc FROM par);

-- 1) BACKUP (dentro da transacao: so persiste se der COMMIT)
CREATE TABLE bkp_roster_squad_members_20261020 AS
SELECT m.* FROM squad_members m JOIN par ON m.db_id IN (par.drop_db_id, par.keep_db_id);
CREATE TABLE bkp_roster_squad_person_configs_20261020 AS
SELECT c.* FROM squad_person_configs c JOIN par ON c.squad_id = par.squad_id AND c.jira_account_id IN (par.drop_acc, par.keep_acc);

-- 2) Linha mantida absorve o que lhe falta da linha JIRAUSER
UPDATE squad_members k SET
    display_name = coalesce(nullif(btrim(k.display_name), ''), d.display_name),
    role = coalesce(nullif(btrim(k.role), ''), d.role),
    claimed_by_uid = coalesce(nullif(btrim(k.claimed_by_uid), ''), d.claimed_by_uid),
    system_calculated_capacity_hours_per_day = coalesce(k.system_calculated_capacity_hours_per_day, d.system_calculated_capacity_hours_per_day),
    -- horas: calibrado a mao (MANUAL_OVERRIDE) vence o calculado; so passa da JIRAUSER se a mantida nao esta calibrada
    capacity_hours_per_day = CASE WHEN coalesce(k.override_type, '') <> 'MANUAL_OVERRIDE' AND d.override_type = 'MANUAL_OVERRIDE'
                                  THEN d.capacity_hours_per_day ELSE coalesce(k.capacity_hours_per_day, d.capacity_hours_per_day) END,
    calibration_notes = CASE WHEN coalesce(k.override_type, '') <> 'MANUAL_OVERRIDE' AND d.override_type = 'MANUAL_OVERRIDE'
                             THEN d.calibration_notes ELSE coalesce(k.calibration_notes, d.calibration_notes) END,
    override_type = CASE WHEN coalesce(k.override_type, '') <> 'MANUAL_OVERRIDE' AND d.override_type = 'MANUAL_OVERRIDE'
                         THEN d.override_type ELSE coalesce(k.override_type, d.override_type) END,
    updated_at = to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
FROM par, squad_members d
WHERE k.db_id = par.keep_db_id AND d.db_id = par.drop_db_id;

-- 3) squad_person_configs: a chave removida vira a mantida
--    3a) quando a mantida ja tem config na mesma sprint: preenche so os campos vazios dela
UPDATE squad_person_configs kc SET
    papel = coalesce(kc.papel, dc.papel),
    dias_codificacao_teste = coalesce(kc.dias_codificacao_teste, dc.dias_codificacao_teste),
    dias_regressivo = coalesce(kc.dias_regressivo, dc.dias_regressivo),
    horas_produtivas = coalesce(kc.horas_produtivas, dc.horas_produtivas)
FROM par, squad_person_configs dc
WHERE dc.squad_id = par.squad_id AND dc.jira_account_id = par.drop_acc
  AND kc.squad_id = par.squad_id AND kc.jira_account_id = par.keep_acc AND kc.sprint_id = dc.sprint_id;
DELETE FROM squad_person_configs dc USING par
WHERE dc.squad_id = par.squad_id AND dc.jira_account_id = par.drop_acc
  AND EXISTS (SELECT 1 FROM squad_person_configs kc WHERE kc.squad_id = par.squad_id AND kc.jira_account_id = par.keep_acc AND kc.sprint_id = dc.sprint_id);
--    3b) as que sobraram (a mantida nao tinha na sprint): troca a chave (db_id = squad_sprint_conta)
UPDATE squad_person_configs dc SET
    jira_account_id = par.keep_acc,
    db_id = dc.squad_id || '_' || dc.sprint_id || '_' || par.keep_acc
FROM par
WHERE dc.squad_id = par.squad_id AND dc.jira_account_id = par.drop_acc;

-- 4) remove a linha JIRAUSER redundante (ja absorvida e com backup)
DELETE FROM squad_members m USING par WHERE m.db_id = par.drop_db_id;

\echo '=== DEPOIS: roster da squad ==='
SELECT m.db_id, m.jira_account_id, m.display_name, m.email, m.role, m.capacity_hours_per_day AS horas, m.override_type, m.claimed_by_uid
FROM squad_members m, param p WHERE m.squad_id = p.squad_id ORDER BY lower(m.display_name), m.jira_account_id;

\echo '=== DEPOIS: squad_person_configs sem chave orfa (deve vir vazio) ==='
SELECT c.* FROM squad_person_configs c, param p
WHERE c.squad_id = p.squad_id AND c.jira_account_id ~* '^JIRAUSER[0-9]+$'
  AND c.jira_account_id NOT IN (SELECT jira_account_id FROM squad_members WHERE squad_id = p.squad_id);

\echo '=== CONFERENCIA: e-mail duplicado na squad (deve vir vazio) ==='
SELECT lower(btrim(email)) AS email, count(*) FROM squad_members m, param p
WHERE m.squad_id = p.squad_id AND email IS NOT NULL AND btrim(email) <> '' GROUP BY 1 HAVING count(*) > 1;

-- ENSAIO por padrao. Depois de conferir, troque por COMMIT.
ROLLBACK;
-- COMMIT;
