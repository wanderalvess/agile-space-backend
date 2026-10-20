-- SOMENTE LEITURA. Linhas de project_member_roles que a importacao do Jira gravou e que provavelmente NAO sao pessoas:
--   (1) sem e-mail e com o nome (ou a conta) igual ao nome/chave da squad ("Projeto Mississauga - Winthor");
--   (2) caixa compartilhada/servico pelo prefixo do e-mail ou usuario (engenharia.vd).
-- Nada e apagado aqui: a decisao e do usuario. Linhas com user_id (alguem ja entrou por elas) devem ser revistas antes.
SELECT pmr.id, pmr.project_id, pmr.role_name, pmr.jira_account_id, pmr.display_name, pmr.email, pmr.user_id, pmr.source, pmr.is_leadership,
       CASE WHEN (pmr.email IS NULL OR btrim(pmr.email) = '')
                 AND (lower(btrim(pmr.display_name)) = lower(btrim(pc.name)) OR lower(btrim(pmr.display_name)) = lower(pmr.project_id)
                      OR lower(btrim(pmr.jira_account_id)) = lower(btrim(pc.name)))
            THEN 'nome da squad como pessoa' ELSE 'caixa compartilhada/servico' END AS motivo
FROM project_member_roles pmr
LEFT JOIN project_configs pc ON pc.id = pmr.project_id
WHERE pmr.source = 'JIRA'
  AND ( ((pmr.email IS NULL OR btrim(pmr.email) = '')
         AND (lower(btrim(pmr.display_name)) = lower(btrim(pc.name)) OR lower(btrim(pmr.display_name)) = lower(pmr.project_id)
              OR lower(btrim(pmr.jira_account_id)) = lower(btrim(pc.name))))
     OR split_part(lower(coalesce(nullif(pmr.email, ''), pmr.jira_account_id)), '@', 1)
          ~ '^(engenharia|suporte|support|atendimento|noreply|no-reply|naoresponda|contato|financeiro|automacao|integracao)[._-]' )
ORDER BY pmr.project_id, pmr.display_name;
