-- ETAPA 4 do vínculo: LEAD deixa de ser nível de conta (docs/vinculo-auditoria-e-plano.md; contrato em docs/membership-api.md).
-- ADITIVA e idempotente: NÃO rebaixa ninguém e NÃO cria vínculo. O rebaixamento é feito por um administrador em
-- POST /api/admin/membership/lead-demotion (dry-run por padrão), que grava/atualiza esta tabela; a reversão é
-- POST /api/admin/membership/lead-restore.

-- 1) Backup dos níveis LEAD: o que cada conta era antes do rebaixamento (a restauração lê daqui).
CREATE TABLE IF NOT EXISTS users_role_lead_backup (
    id VARCHAR(255) PRIMARY KEY,
    role VARCHAR(50) NOT NULL,
    migrated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Copia as contas que são LEAD agora e ainda não estão no backup (reexecutar não duplica nem sobrescreve).
INSERT INTO users_role_lead_backup (id, role, migrated_at)
SELECT u.id, u.role, NOW()
FROM users u
WHERE UPPER(u.role) = 'LEAD'
ON CONFLICT (id) DO NOTHING;

-- 2) Público de aviso por papel: LEAD vira a chave derivada LEADERSHIP (qualquer linha de liderança em
--    project_member_roles). A PK é (announcement_id, role): se o aviso já tem LEADERSHIP, só descarta o LEAD repetido.
DELETE FROM global_announcement_roles r
WHERE UPPER(r.role) = 'LEAD'
  AND EXISTS (SELECT 1 FROM global_announcement_roles x
              WHERE x.announcement_id = r.announcement_id AND x.role = 'LEADERSHIP');

UPDATE global_announcement_roles SET role = 'LEADERSHIP' WHERE UPPER(role) = 'LEAD';
