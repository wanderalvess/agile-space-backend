-- Config da squad pro card "Próxima cerimônia" (/painel). Duas fontes possíveis, nunca
-- misturadas: 'google_calendar' (default/null — cada membro conecta a própria agenda,
-- ver useGoogleCalendarConnection no frontend) ou 'manual' (a squad cadastra o horário
-- recorrente uma vez, sem depender de OAuth do Google). `ceremonies` segue o mesmo
-- padrão de `phases`/`sprint_history`: array JSONB de objetos, sem tabela própria.
ALTER TABLE squads ADD COLUMN IF NOT EXISTS ceremony_mode character varying(20);
ALTER TABLE squads ADD COLUMN IF NOT EXISTS ceremonies jsonb;
