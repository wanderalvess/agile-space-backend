-- Pedido de vínculo: quem não consegue se reconhecer no roster (e-mail do Jira diferente do login, linha de
-- liderança) pede ao admin que ligue a conta a uma linha de project_member_roles. Somente criação.
-- status: PENDING | APPROVED | REJECTED. member_id é a linha do roster (project_member_roles.id), opcional no pedido
-- e definida na aprovação; sem FK porque o roster é reimportado do Jira (linhas podem ser recriadas).
CREATE TABLE IF NOT EXISTS link_requests (
    id VARCHAR(36) PRIMARY KEY,
    requester_user_id VARCHAR(255) NOT NULL REFERENCES users (id),
    project_key VARCHAR(50) NOT NULL,
    member_id VARCHAR(255),
    email_in_jira VARCHAR(255) NOT NULL,
    message VARCHAR(500),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    decided_by VARCHAR(255),
    decision_note VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_link_requests_status_created ON link_requests (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_link_requests_requester_project_status ON link_requests (requester_user_id, project_key, status);
-- No máximo um pedido pendente por (usuário, projeto), também sob corrida entre duas requisições.
CREATE UNIQUE INDEX IF NOT EXISTS uq_link_requests_pending ON link_requests (requester_user_id, project_key) WHERE status = 'PENDING';
