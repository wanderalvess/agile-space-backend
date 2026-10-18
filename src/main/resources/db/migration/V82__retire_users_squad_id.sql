-- ETAPA 5 do vínculo, PASSO 2 (DESTRUTIVA): apaga a coluna users.squad_id (docs/membership-api.md, "Etapa 5").
-- O passo 1 já tirou o campo da entidade User: ninguém lê nem grava a coluna. Antes de apagar, TODO usuário é copiado para
-- users_squad_legacy_backup (id, squad_id, default_project_id). Se o backup não cobrir exatamente o que está em users
-- (linha a menos ou valor diferente), a migração ABORTA com EXCEPTION e NADA é apagado (o Flyway reverte o script inteiro).
-- Esta migração NUNCA apaga a tabela de backup; mantenha-a por pelo menos 30 dias (limpeza só manual).
-- Segura quando a coluna já não existe (base restaurada/reexecução): então nada é copiado nem apagado.
-- Dependências da coluna conferidas no schema real (\d users): nenhum índice, FK, constraint, view ou trigger usa squad_id;
-- por isso o DROP é SEM CASCADE (se algo dependesse dela, o DROP falharia em vez de levar o objeto junto).

CREATE TABLE IF NOT EXISTS users_squad_legacy_backup (
    id                 VARCHAR(255) PRIMARY KEY,
    squad_id           VARCHAR(255),
    default_project_id VARCHAR(255),
    backed_up_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

DO $$
DECLARE
    missing BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'squad_id') THEN

        INSERT INTO users_squad_legacy_backup (id, squad_id, default_project_id, backed_up_at)
        SELECT u.id, u.squad_id, u.default_project_id, NOW() FROM users u
        ON CONFLICT (id) DO UPDATE
            SET squad_id = EXCLUDED.squad_id,
                default_project_id = EXCLUDED.default_project_id,
                backed_up_at = EXCLUDED.backed_up_at;

        SELECT COUNT(*) INTO missing
        FROM users u
        WHERE NOT EXISTS (
            SELECT 1 FROM users_squad_legacy_backup b
            WHERE b.id = u.id
              AND b.squad_id IS NOT DISTINCT FROM u.squad_id
              AND b.default_project_id IS NOT DISTINCT FROM u.default_project_id);

        IF missing > 0 THEN
            RAISE EXCEPTION 'backup de users.squad_id incompleto (% usuarios sem copia fiel): nada foi apagado', missing;
        END IF;

        ALTER TABLE users DROP COLUMN squad_id;
    END IF;
END
$$;
