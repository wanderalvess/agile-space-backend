-- Unicidade de pessoa por squad: (squad_id, lower(trim(email))) em squad_members.
--
-- ADITIVA e segura: nunca apaga nem altera linha. A unicidade (squad_id, jira_account_id) já existia; o que faltava era
-- impedir duas linhas com o MESMO e-mail na mesma squad quando chegam por chaves diferentes (conta do Jira, uid,
-- "manual-...", e-mail, "import....") ou por dois pedidos ao mesmo tempo.
--
-- Estratégia:
--   1. A view squad_members_duplicate_report (somente leitura) lista, por squad e e-mail normalizado, as linhas repetidas.
--   2. Se NÃO há duplicatas, cria o índice único parcial (só onde há e-mail). Se HÁ duplicatas, a migração NÃO falha e NÃO
--      remove nada: só avisa (RAISE NOTICE) e deixa o índice para depois da limpeza manual. Depois de resolver o relatório,
--      rode o CREATE UNIQUE INDEX abaixo (o mesmo comando, copiado do bloco DO) em horário calmo.
--   O código da aplicação já normaliza e funde por conta > e-mail > nome antes de gravar, então não cria novas duplicatas
--   mesmo enquanto o índice não existir; com ele, a corrida entre dois pedidos vira 409 em vez de duas linhas.

CREATE OR REPLACE VIEW squad_members_duplicate_report AS
SELECT squad_id,
       lower(btrim(email))                                  AS email_key,
       count(*)                                             AS linhas,
       array_agg(db_id ORDER BY db_id)                      AS db_ids,
       array_agg(jira_account_id ORDER BY db_id)            AS contas,
       array_agg(display_name ORDER BY db_id)               AS nomes,
       array_agg(claimed_by_uid ORDER BY db_id)             AS vinculos
FROM squad_members
WHERE email IS NOT NULL AND btrim(email) <> ''
GROUP BY squad_id, lower(btrim(email))
HAVING count(*) > 1;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM squad_members_duplicate_report) THEN
        RAISE NOTICE 'V85: ha squad_members com o mesmo e-mail na mesma squad; indice unico NAO criado. Consulte squad_members_duplicate_report, limpe e crie: CREATE UNIQUE INDEX ux_squad_members_squad_email ON squad_members (squad_id, lower(btrim(email))) WHERE email IS NOT NULL AND btrim(email) <> '''';';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_squad_members_squad_email
            ON squad_members (squad_id, lower(btrim(email)))
            WHERE email IS NOT NULL AND btrim(email) <> '';
    END IF;
END
$$;
