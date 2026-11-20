-- Fundo e tema do Modo Teatro da Review. O frontend já enviava os dois campos,
-- mas a sessão não tinha coluna para eles e a escolha se perdia ao recarregar.
-- O fundo aceita cor, gradiente CSS ou caminho de imagem. Idempotente.
ALTER TABLE public.showcase_sessions ADD COLUMN IF NOT EXISTS presentation_background text;
ALTER TABLE public.showcase_sessions ADD COLUMN IF NOT EXISTS presentation_theme varchar(32);
