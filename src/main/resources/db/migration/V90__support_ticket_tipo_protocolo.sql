-- Suporte do usuário: tipo próprio, protocolo curto (SUP-NNNN) e marca de leitura do solicitante.
-- Só colunas NULL e uma sequence; chamados antigos continuam como estão (tipo/protocolo nulos: o backend deriva o
-- protocolo na leitura, sem gravar, e o front cai no tipo embutido no assunto).
ALTER TABLE support_tickets
    ADD COLUMN IF NOT EXISTS type VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS protocol VARCHAR(20) NULL,
    ADD COLUMN IF NOT EXISTS requester_last_read_at TIMESTAMP NULL;

CREATE SEQUENCE IF NOT EXISTS support_ticket_protocol_seq START WITH 1 INCREMENT BY 1;

-- Respostas que já existiam antes desta versão contam como lidas (senão todo chamado antigo apareceria como
-- "Nova resposta"). Não apaga nem altera nenhum dado de negócio.
UPDATE support_tickets
   SET requester_last_read_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
 WHERE requester_last_read_at IS NULL;
