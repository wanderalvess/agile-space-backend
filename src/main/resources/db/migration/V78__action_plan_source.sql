-- Origem do plano de ação (Retro, Radar de clima, Brainstorming, Showcase ou manual) e da ação, mais o prazo da ação como
-- data de verdade. Só aditiva e idempotente: sem DELETE/DROP/UPDATE; planos e ações existentes ficam com tudo nulo
-- (source_type nulo = legado = MANUAL; o serviço normaliza na leitura). Contrato: docs/action-plan-origin-api.md.
ALTER TABLE action_plans ADD COLUMN IF NOT EXISTS source_type VARCHAR(20);
ALTER TABLE action_plans ADD COLUMN IF NOT EXISTS source_id VARCHAR(80);
ALTER TABLE action_plans ADD COLUMN IF NOT EXISTS source_title VARCHAR(255);

ALTER TABLE action_plan_tasks ADD COLUMN IF NOT EXISTS source_item_id VARCHAR(80);
ALTER TABLE action_plan_tasks ADD COLUMN IF NOT EXISTS source_label VARCHAR(255);
ALTER TABLE action_plan_tasks ADD COLUMN IF NOT EXISTS due_date DATE;

-- Um plano por origem em cada squad. Plano sem squad (squad_id nulo) cai na chave '' e disputa só com os outros sem squad.
-- Parcial: plano antigo/manual tem source_id nulo e fica fora do índice, então nada existente pode violá-lo.
CREATE UNIQUE INDEX IF NOT EXISTS uq_action_plans_source
    ON action_plans ((COALESCE(UPPER(squad_id), '')), source_type, source_id)
    WHERE source_id IS NOT NULL;

-- Busca "o plano deste radar" sem informar a squad.
CREATE INDEX IF NOT EXISTS idx_action_plans_source ON action_plans (source_type, source_id) WHERE source_id IS NOT NULL;
