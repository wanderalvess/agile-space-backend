-- A foto do Jira é guardada como imagem embutida (data URI), que não cabe em varchar(255).
ALTER TABLE project_member_roles ALTER COLUMN avatar_url TYPE TEXT;
