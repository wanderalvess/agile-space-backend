-- Biblioteca de IA: favoritos no servidor, histórico de versões e eventos de uso (gráfico por semana).
-- Só CREATE TABLE/INDEX. ON DELETE CASCADE: excluir o item leva junto favoritos, versões e eventos.

CREATE TABLE IF NOT EXISTS public.prompt_favorites (
    user_id    varchar(255) NOT NULL,
    prompt_id  uuid         NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT prompt_favorites_pkey PRIMARY KEY (user_id, prompt_id),
    CONSTRAINT fk_prompt_favorites_prompt FOREIGN KEY (prompt_id) REFERENCES public.prompt_hub (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_prompt_favorites_prompt ON public.prompt_favorites (prompt_id);

CREATE TABLE IF NOT EXISTS public.prompt_versions (
    id              uuid         NOT NULL,
    prompt_id       uuid         NOT NULL,
    version_number  integer      NOT NULL,
    title           varchar(255) NOT NULL,
    description     text,
    content         text,
    type            varchar(50),
    visibility      varchar(50),
    status          varchar(50),
    tags            text,
    changed_by      varchar(255),
    changed_by_name varchar(255),
    changed_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT prompt_versions_pkey PRIMARY KEY (id),
    CONSTRAINT fk_prompt_versions_prompt FOREIGN KEY (prompt_id) REFERENCES public.prompt_hub (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_prompt_versions_prompt_number ON public.prompt_versions (prompt_id, version_number);

CREATE TABLE IF NOT EXISTS public.prompt_usage_events (
    id          uuid        NOT NULL,
    prompt_id   uuid        NOT NULL,
    user_id     varchar(255),
    kind        varchar(10) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT prompt_usage_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_prompt_usage_events_prompt FOREIGN KEY (prompt_id) REFERENCES public.prompt_hub (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_prompt_usage_events_prompt_time ON public.prompt_usage_events (prompt_id, occurred_at);
