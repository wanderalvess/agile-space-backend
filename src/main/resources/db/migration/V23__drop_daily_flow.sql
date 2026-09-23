-- Daily Flow (rota /daily-flow, aba "Daily & Timesheet" do squad e widgets de
-- timesheet ligados a ela) sai do sistema novo por decisão de produto — continua
-- existindo no legado; se algum time reportar uso real, a feature volta.
-- Sem dado real em produção ainda (times usando o legado), então é drop direto.

DROP TABLE IF EXISTS daily_reports;
DROP TABLE IF EXISTS daily_checkins;
DROP TABLE IF EXISTS user_worklogs;
