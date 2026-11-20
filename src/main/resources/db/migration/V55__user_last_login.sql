-- Último login por senha, para o painel de gestão de usuários. Nullable: contas antigas ficam sem valor até o próximo login.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;
