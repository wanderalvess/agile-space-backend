-- Etapa 3 do vínculo (docs/link-requests-squad-api.md): aprovação do pedido pela liderança da squad.
-- Só aditivo e idempotente; nenhum DELETE/DROP.
--
-- kind: CONTRIBUTOR | LEADERSHIP. Decide QUEM pode aprovar (contribuidor: AM/SM/PO/People Lead da squad; liderança:
--   People Lead/Tribe Lead ou ADMIN). Linhas anteriores: LEADERSHIP quando member_id aponta para uma linha
--   project_member_roles com is_leadership = true (na V74 só o admin aprovava, e a liderança era a regra implícita);
--   CONTRIBUTOR nos demais casos (inclusive pedido sem linha, ou linha reimportada que não existe mais). A aprovação
--   NÃO confia só nesta coluna: o tipo efetivo também considera a linha que o aprovador escolhe.
-- requested_role_name: papel de contribuidor pedido por quem não aparece no roster (allowlist do joinProject, validada
--   no serviço). NULL quando o pedido aponta uma linha.
ALTER TABLE link_requests ADD COLUMN IF NOT EXISTS requested_role_name VARCHAR(80);
ALTER TABLE link_requests ADD COLUMN IF NOT EXISTS kind VARCHAR(12);

UPDATE link_requests lr
SET kind = CASE
        WHEN EXISTS (SELECT 1 FROM project_member_roles pmr WHERE pmr.id = lr.member_id AND pmr.is_leadership IS TRUE)
            THEN 'LEADERSHIP'
        ELSE 'CONTRIBUTOR'
    END
WHERE lr.kind IS NULL;

-- Um binário anterior à V76 (rollback de deploy) continua inserindo sem a coluna: cai em CONTRIBUTOR, e o serviço
-- recalcula o tipo efetivo pela linha na hora de aprovar.
ALTER TABLE link_requests ALTER COLUMN kind SET DEFAULT 'CONTRIBUTOR';

-- Lista por squad: WHERE project_key IN (...) AND status = ... ORDER BY created_at DESC.
CREATE INDEX IF NOT EXISTS idx_link_requests_project_status_created ON link_requests (project_key, status, created_at DESC);
