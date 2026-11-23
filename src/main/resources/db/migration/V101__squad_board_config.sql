-- Configuração do quadro Scrum da squad (filtros rápidos, raias, estratégia de raias e limites de WIP das colunas).
-- Antes ficava só no localStorage de quem editou, mas a tela dizia "atualizados no quadro": ninguém mais via a mudança.
-- Uma linha por squad. jsonb porque o formato é o da própria tela (muda junto com ela), sem migração a cada campo.
-- Aditiva: NULL em uma coluna = a squad não personalizou aquela parte e vale o que o Jira trouxer.
CREATE TABLE IF NOT EXISTS squad_board_config (
    squad_id          VARCHAR(100) PRIMARY KEY,
    quick_filters     JSONB,
    swimlanes         JSONB,
    swimlane_strategy VARCHAR(20),
    columns           JSONB,
    updated_by        VARCHAR(100),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
