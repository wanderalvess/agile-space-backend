-- Suporte ao workflow do Jira Plans (aba /squad "Plans"): ordem das fases,
-- confiabilidade das datas e mapeamento tipo-de-issue -> fase, usados pra
-- detectar atraso automaticamente e desenhar a fita de cronograma.

-- Posição da subtarefa no array fields.subtasks da issue pai no Jira (ordem
-- de rank que alguém arrastou lá) — usado como desempate de ordem de fase
-- quando duas fases têm a mesma data planejada.
ALTER TABLE squad_issue_snapshots
    ADD COLUMN IF NOT EXISTS order_index INTEGER;

-- true quando target_start/target_end vieram de fato de campo de data real do
-- Jira; false/NULL quando caíram no fallback (dueDate, ou created/updated) —
-- ver squadIssueToPlansTask no frontend. Sem essa flag, cascata de atraso
-- calcula em cima de "data inventada" sem avisar ninguém.
ALTER TABLE squad_issue_snapshots
    ADD COLUMN IF NOT EXISTS dates_are_inferred BOOLEAN;

-- Mapeamento configurável por squad de issuetype -> fase de workflow
-- (kind/label/color/ordem), pra classificar subtarefas na timeline sem
-- depender de heurística de texto. Formato: array de objetos
-- [{kind, label, color, issueTypes: [...]}], ordem do array = ordem da fase.
ALTER TABLE squads
    ADD COLUMN IF NOT EXISTS phases JSONB;
