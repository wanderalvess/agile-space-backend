-- Templates de sessão do Scrum Poker guardados por squad (antes ficavam só no localStorage de cada navegador).
-- settings e backlog seguem o formato que o frontend já usa na sala (poker_rooms.settings e a fila de tarefas),
-- por isso jsonb: o conjunto de opções da sala muda junto com a tela, sem migração a cada opção nova.
CREATE TABLE IF NOT EXISTS squad_poker_templates (
    id              VARCHAR(36)  PRIMARY KEY,
    squad_id        VARCHAR(100) NOT NULL,
    name            VARCHAR(60)  NOT NULL,
    deck_type       VARCHAR(40)  NOT NULL,
    settings        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    backlog         JSONB        NOT NULL DEFAULT '[]'::jsonb,  -- [{title, jiraLink?, type?}]
    created_by      VARCHAR(100) NOT NULL,
    created_by_name VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_squad_poker_templates_squad ON squad_poker_templates (squad_id, created_at DESC);
