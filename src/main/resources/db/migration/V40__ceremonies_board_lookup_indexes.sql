-- Brainstorming, Health Check e Plano de Ação: índices para as consultas por board/squad
-- (ideias, grupos, participantes, votos e tarefas eram lidos por varredura completa da tabela).
-- Só índices, idempotente; nenhum dado é alterado ou removido.
CREATE INDEX IF NOT EXISTS idx_brainstorming_ideas_board_id ON public.brainstorming_ideas (board_id);
CREATE INDEX IF NOT EXISTS idx_brainstorming_groups_board_id ON public.brainstorming_groups (board_id);
CREATE INDEX IF NOT EXISTS idx_brainstorming_boards_team_upper ON public.brainstorming_boards (upper(team));
CREATE INDEX IF NOT EXISTS idx_health_check_votes_board_id ON public.health_check_votes (board_id);
CREATE INDEX IF NOT EXISTS idx_health_check_boards_team_upper ON public.health_check_boards (upper(team));
CREATE INDEX IF NOT EXISTS idx_action_plan_tasks_board_id ON public.action_plan_tasks (board_id);
CREATE INDEX IF NOT EXISTS idx_action_plan_participants_board_id ON public.action_plan_participants (board_id);
