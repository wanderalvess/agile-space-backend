-- Meta atual da sprint no Jira (para a Revisão comparar com a meta do plano). Aditiva, nula por padrão.
ALTER TABLE sprint_plans ADD COLUMN jira_goal TEXT;
