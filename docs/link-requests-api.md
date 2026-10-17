# Pedido de vínculo — contrato (backend `feat/link-requests`, migração V74)

> **Etapa 3 (V76):** a aprovação deixou de ser só do admin. AM/SM/PO/People Lead da squad aprovam contribuidores e
> People/Tribe Lead aprovam liderança, pelas rotas `/api/squads/{id}/link-requests*`; `kind` e `requestedRoleName` entram
> nos DTOs; contribuidor sem linha no roster ganha uma linha ao ser aprovado. Contrato e regras novas:
> [`link-requests-squad-api.md`](./link-requests-squad-api.md). O que está abaixo continua valendo (rotas de admin, erros,
> efeito da aprovação), com as exceções descritas lá (notificação de pedido novo e quem decide).

Para quem lidera uma squad no Jira com **e-mail diferente** do e-mail da conta no portal (ou cuja linha do roster não é
reivindicável pelo "sou eu" do onboarding: e-mail diferente, linha de liderança). A pessoa pede o vínculo e um **admin**
aprova ou recusa. Todas as rotas exigem o JWT atual; o solicitante e o admin vêm sempre do token, nunca do corpo.
`/api/admin/**` exige `role=ADMIN` (`JwtAuthenticationFilter`). Datas ISO-8601 (`Instant`/UTC), JSON camelCase.
Erros: `{ "error"|"message": ... }` no formato padrão do projeto (`ResponseStatusException`).

```ts
type LinkRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

// POST /api/link-requests  (corpo)
interface LinkRequestCreate {
  projectKey: string;        // chave do projeto/squad (ex.: "DDWMISSI"); aceita minúsculas
  memberId?: string;         // id da linha do roster (project_member_roles.id) que a pessoa diz ser ela
  emailInJira: string;       // e-mail válido que ela usa no Jira
  message?: string;          // até 500 caracteres
}

// GET /api/link-requests/mine
interface LinkRequestDto {
  id: string; projectKey: string;
  memberId?: string; memberName?: string;     // memberName = displayName da linha (some se a linha foi reimportada)
  emailInJira: string; message?: string;
  status: LinkRequestStatus;
  createdAt: string; decidedAt?: string; decisionNote?: string;
}

// GET /api/admin/link-requests
interface AdminLinkRequestDto {
  id: string;
  requester: { id: string; name: string; email: string };
  projectKey: string; memberId?: string; memberName?: string;
  emailInJira: string; message?: string;
  status: LinkRequestStatus;
  createdAt: string; decidedAt?: string;
  decidedBy?: string;        // e-mail do admin (ou id, se o token não tinha e-mail)
  decisionNote?: string;
}
```

Campos sem valor vêm `null` (ou ausentes, conforme a serialização padrão do projeto); trate `undefined`/`null` igual.

| Método | Rota | Quem | Resposta |
|---|---|---|---|
| POST | `/api/link-requests` | qualquer sessão | `201 { id, status: 'PENDING' }` |
| GET | `/api/link-requests/mine` | qualquer sessão | `LinkRequestDto[]`, só do usuário logado, mais recentes primeiro |
| GET | `/api/admin/link-requests?status=` | ADMIN | `AdminLinkRequestDto[]`, mais recentes primeiro; sem `status` = todos; status inválido = 400 |
| POST | `/api/admin/link-requests/{id}/approve` | ADMIN | corpo opcional `{ memberId?: string }`; `200 AdminLinkRequestDto` (APPROVED) |
| POST | `/api/admin/link-requests/{id}/reject` | ADMIN | corpo opcional `{ reason?: string }` (até 500); `200 AdminLinkRequestDto` (REJECTED, `decisionNote = reason`) |

## Validações e erros

`POST /api/link-requests`:

| Caso | Status |
|---|---|
| `projectKey` ausente / e-mail ausente ou inválido / mensagem > 500 | 400 |
| projeto inexistente / `memberId` inexistente | 404 |
| `memberId` de outra squad | 422 |
| já existe pedido **PENDING** do usuário para essa squad | 409 `"Você já tem um pedido pendente para esta squad"` |
| `memberId` já vinculado a **outra** conta | 409 `"Essa pessoa já está vinculada a outra conta…"` |
| `memberId` já vinculado à própria conta (já é membro com essa linha) | 409 `"Você já está vinculado(a) a essa pessoa nesta squad."` |
| 10 ou mais pedidos pendentes na conta (somando squads) | 429 |

Um único pendente por (usuário, projeto) também é garantido pelo banco (índice único parcial). Depois de decidido, o
usuário pode abrir um novo pedido para a mesma squad. Além disso vale o limite geral por IP do `RateLimitFilter`
(300 req/min em `/api/**`); não foi criada faixa nova.

`approve` / `reject`:

| Caso | Status |
|---|---|
| pedido inexistente | 404 |
| pedido já decidido (APPROVED/REJECTED) | 409 `"Esse pedido já foi decidido."` |
| approve sem `memberId` (nem no pedido nem no corpo) | 422 `"Informe a linha do roster"` |
| approve com linha inexistente / conta do solicitante inexistente | 404 |
| approve com linha de outra squad | 422 |
| approve com linha já vinculada a **outra** conta | 409 (pedido continua PENDING) |
| reject com `reason` > 500 | 400 |

## Efeito da aprovação (transação única)

`memberId` do corpo do approve **sobrescreve** o do pedido (e passa a ser o gravado no pedido). O vínculo reaproveita a
gravação do `OnboardingService.claim` (`OnboardingService.linkApproved`), **sem** a checagem de identidade (o admin a
confirma) e **permitindo linha de liderança** (a trava de "liderança nunca é autodeclarada" continua valendo só no
self-service `POST /api/onboarding/claim/{memberId}`):

1. grava `userId` do solicitante na linha do roster (`project_member_roles`); `isLeadership` é o da própria linha;
2. se a conta ainda não tem `jiraAccountId`, herda o da linha;
3. liga `squad_members.claimedByUid` das vagas do roster da squad que apontam para a mesma pessoa (conta do Jira, e-mail
   ou nome da linha) e ainda não tinham dono;
4. marca o pedido `APPROVED` (`decidedAt`, `decidedBy`);
5. grava auditoria e dispara a notificação (abaixo).

Mesma conta aprovada duas vezes na mesma linha é idempotente; outra conta na linha é 409 e nada muda.
Aprovar **não** troca o projeto ativo da sessão do solicitante: ele passa a ver a squad em `/painel` na próxima carga.

## Auditoria (`audit_logs`)

Ações `LINK_REQUEST_CREATED` (operador = e-mail do solicitante), `LINK_REQUEST_APPROVED` e `LINK_REQUEST_REJECTED`
(operador = e-mail do admin), com `details` em texto (`pedido=… usuario=… projeto=… linha=… papel=…`).

## Notificações (sino, `GET /api/notifications`)

Novo `kind` **`LINK_REQUEST`** (entra na categoria `FOR_YOU` e em `read-all`), com `dedupeKey` por pedido:

| Gatilho | Para | Texto | `link` |
|---|---|---|---|
| `LINK_REQUEST_CREATED` | todos os ADMINs ativos | "<nome> pediu para ser vinculado(a) à squad <KEY>." | `/admin` |
| `LINK_REQUEST_DECIDED` (aprovado) | o solicitante | "Seu pedido de vínculo à squad <KEY> foi aprovado." | `/painel` |
| `LINK_REQUEST_DECIDED` (recusado) | o solicitante | "…foi recusado. Motivo: <reason>" | `/onboarding` |

O `title` é "Pedido de vínculo"; `actorId` = quem pediu / quem decidiu (a pessoa nunca é notificada da própria ação).
As notificações saem depois do commit e uma falha ao gerá-las só vira log.

## Banco (V74)

Tabela `link_requests` (`id`, `requester_user_id` FK `users`, `project_key`, `member_id` sem FK, `email_in_jira`,
`message`, `status`, `created_at`, `decided_at`, `decided_by`, `decision_note`), índices por `(status, created_at)` e
`(requester_user_id, project_key, status)` e índice único parcial `(requester_user_id, project_key) WHERE status = 'PENDING'`.
Somente criação; `member_id` não tem FK porque o roster é reimportado do Jira.
