-- Glossário do Gerador de spec Jolt: pares de nomes de campo (saída ← entrada) que a equipe confirmou e que geraram
-- uma spec válida. Na próxima rota, o mesmo par já vem ligado. Só nomes de campo, nenhum dado de registro.
CREATE TABLE IF NOT EXISTS public.jolt_glossary_terms (
    id          uuid PRIMARY KEY,
    term_from   varchar(120) NOT NULL,
    term_to     varchar(120) NOT NULL,
    uses        integer      NOT NULL DEFAULT 1,
    created_at  timestamp    NOT NULL DEFAULT now(),
    updated_at  timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT uq_jolt_glossary_terms UNIQUE (term_from, term_to)
);
