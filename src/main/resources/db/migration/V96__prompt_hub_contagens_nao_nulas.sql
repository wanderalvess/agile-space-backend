-- As contagens de uso e de clones nunca devem ser nulas: a ordenação "mais usados / mais clonados" do Prompt Hub
-- depende disso (no Postgres, DESC põe nulos primeiro e o Hibernate não aceita "nulls last" em Specification).
UPDATE public.prompt_hub SET use_count = 0 WHERE use_count IS NULL;
UPDATE public.prompt_hub SET fork_count = 0 WHERE fork_count IS NULL;

ALTER TABLE public.prompt_hub ALTER COLUMN use_count SET DEFAULT 0;
ALTER TABLE public.prompt_hub ALTER COLUMN use_count SET NOT NULL;
ALTER TABLE public.prompt_hub ALTER COLUMN fork_count SET DEFAULT 0;
ALTER TABLE public.prompt_hub ALTER COLUMN fork_count SET NOT NULL;
