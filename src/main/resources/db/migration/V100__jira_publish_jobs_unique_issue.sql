-- Um envio por tarefa da sala: dois cliques (ou duas abas) quase juntos passavam pela checagem do serviço
-- e criavam dois jobs para a mesma issue. Descartar um envio APAGA a linha, então a regra vale para toda a tabela.
-- Aditiva e defensiva: se já existir duplicata (dado antigo), não derruba o deploy; avisa no log e deixa o índice
-- de fora até alguém consolidar as linhas. A checagem do serviço continua valendo de qualquer forma.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM jira_publish_jobs
                WHERE room_id IS NOT NULL AND issue_id IS NOT NULL
                GROUP BY room_id, issue_id HAVING COUNT(*) > 1) THEN
        RAISE WARNING 'jira_publish_jobs tem envios duplicados para a mesma (sala, tarefa); índice único NÃO criado.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_jira_publish_jobs_room_issue
            ON jira_publish_jobs (room_id, issue_id);
    END IF;
END $$;
