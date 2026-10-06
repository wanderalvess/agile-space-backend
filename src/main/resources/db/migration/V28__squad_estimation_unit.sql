-- Unidade de estimativa da squad (SP | HOURS | TSHIRT | COUNT). NULL = ainda não configurada:
-- os dashboards mostram "Não definida" em vez de assumir Story Points.
ALTER TABLE squads ADD COLUMN IF NOT EXISTS estimation_unit character varying(10);
