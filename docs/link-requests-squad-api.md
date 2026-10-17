# Pedido de vínculo aprovado pela liderança da squad — contrato (Etapa 3 do vínculo, migração V76)

Complementa `docs/link-requests-api.md` (V74). Quem aprova deixa de ser só o ADMIN: **AM/SM/PO/People Lead da própria squad**
aprovam pedidos de contribuidor; pedidos de liderança são aprovados por **People Lead/Tribe Lead** (da squad ou da tribo)
ou ADMIN. As rotas por squad e as de admin usam **o mesmo serviço** (um fluxo só). Todas exigem o JWT; o solicitante e o
aprovador vêm sempre do token, nunca do corpo. Datas ISO-8601 UTC, JSON camelCase. Erros no formato padrão do projeto
(`ResponseStatusException`: `{ "error"|"message": ... }`).

O `{id}` das rotas por squad é a chave da squad (`projectKey`, ex.: `DDWMISSI`; aceita minúsculas e o alias histórico
`MISSI` = `DDWMISSI`, como as outras rotas de squad). Squad inexistente = 404.

## Tipos

```ts
type LinkRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
type LinkRequestKind = 'CONTRIBUTOR' | 'LEADERSHIP';

// POST /api/link-requests  (corpo)  ->  campos NOVOS: kind, requestedRoleName
interface LinkRequestCreate {
  projectKey: string;            // aceita minúsculas
  memberId?: string;             // linha do roster (project_member_roles.id) que a pessoa diz ser ela
  emailInJira: string;
  message?: string;              // até 500
  kind?: LinkRequestKind;        // opcional; ver "Como `kind` é decidido"
  requestedRoleName?: string;    // até 80; SÓ para contribuidor que NÃO aparece no roster (sem memberId)
}
// resposta: 201 { id: string, status: 'PENDING' }   (inalterada)

// GET /api/link-requests/mine  -> LinkRequestDto[]   (campos NOVOS no fim: kind, requestedRoleName)
interface LinkRequestDto {
  id: string; projectKey: string;
  memberId?: string; memberName?: string;
  emailInJira: string; message?: string;
  status: LinkRequestStatus;
  createdAt: string; decidedAt?: string; decisionNote?: string;
  kind: LinkRequestKind;                 // NOVO
  requestedRoleName?: string;            // NOVO (null quando o pedido aponta uma linha)
}

// Lista de admin E lista por squad (o MESMO DTO) -> campos NOVOS no fim: kind, requestedRoleName, canDecide
interface AdminLinkRequestDto {
  id: string;
  requester: { id: string; name: string; email: string };
  projectKey: string; memberId?: string; memberName?: string;
  emailInJira: string; message?: string;
  status: LinkRequestStatus;
  createdAt: string; decidedAt?: string;
  decidedBy?: string;                    // e-mail do aprovador (ou id, se o token não tinha e-mail)
  decisionNote?: string;
  kind: LinkRequestKind;                 // NOVO
  requestedRoleName?: string;            // NOVO
  canDecide?: boolean;                   // NOVO: só na lista por squad (null/ausente na de admin). Ver abaixo
}
```

Campos sem valor vêm `null` (ou ausentes); trate `undefined`/`null` igual.

`canDecide` (somente na lista por squad): "o chamador pode aprovar/recusar ESTE pedido", calculado pelo `kind` do pedido
e, se ele aponta uma linha de liderança, tratado como `LEADERSHIP`. Serve para o front desabilitar os botões de um
pedido de liderança que o AM vê mas não pode decidir. O servidor valida de novo em cada ação (a fonte da verdade é o
403 do approve/reject).

### Como `kind` é decidido (criação)

| Corpo | `kind` gravado | Observação |
|---|---|---|
| `memberId` de linha **não** liderança, sem `kind` | `CONTRIBUTOR` | como hoje |
| `memberId` de linha de **liderança**, sem `kind` | `LEADERSHIP` | era a regra implícita da V74 (só admin aprovava) |
| `memberId` + `kind` que **contradiz** a linha (ex.: `CONTRIBUTOR` em linha de liderança) | 422 | a linha manda |
| sem `memberId`, com `requestedRoleName` | `CONTRIBUTOR` | `kind: LEADERSHIP` + `requestedRoleName` = 422 |
| sem `memberId` e sem `requestedRoleName` | o `kind` enviado, senão `CONTRIBUTOR` | quem aprova escolhe a linha no approve |

`requestedRoleName` e `memberId` juntos = 422 (é uma coisa ou outra).

### Papéis que podem ser pedidos como contribuidor (`requestedRoleName`)

Allowlist **fixa no código** (a mesma do `joinProject`, `SELF_SERVICE_JOIN_ROLE_NAMES`), sem caixa:
`Developer`, `Desenvolvedor(a)`, `QA`, `Analista de QA`, `Designer`, `UX`, `SME`, `Stakeholder / Observador`.
Qualquer outro nome (inclusive Agile Master, Product Owner, People Lead, Tribe Lead, Agile Coach…) = **422**
`"Papel de liderança ou desconhecido não pode ser pedido como contribuidor…"`. Para liderança use `memberId` da linha de
liderança do roster.

## Rotas

| Método | Rota | Quem | Resposta |
|---|---|---|---|
| POST | `/api/link-requests` | qualquer sessão | `201 { id, status: 'PENDING' }` |
| GET | `/api/link-requests/mine` | qualquer sessão | `LinkRequestDto[]` |
| GET | `/api/squads/{id}/link-requests/can-approve` | qualquer sessão | `200 { canApprove, canApproveLeadership }` |
| GET | `/api/squads/{id}/link-requests?status=&limit=&offset=` | quem `canApprove` na squad | `200 AdminLinkRequestDto[]` |
| POST | `/api/squads/{id}/link-requests/{reqId}/approve` | quem pode decidir o pedido | corpo opcional `{ memberId?: string }`; `200 AdminLinkRequestDto` (APPROVED) |
| POST | `/api/squads/{id}/link-requests/{reqId}/reject` | quem pode decidir o pedido | corpo opcional `{ reason?: string }` (até 500); `200 AdminLinkRequestDto` (REJECTED) |
| GET | `/api/admin/link-requests?status=` | ADMIN | como hoje (+ `kind`, `requestedRoleName`) |
| POST | `/api/admin/link-requests/{id}/approve` | ADMIN | como hoje |
| POST | `/api/admin/link-requests/{id}/reject` | ADMIN | como hoje |

### `GET /api/squads/{id}/link-requests/can-approve`

Só calcula para o **próprio chamador** (nunca para outra pessoa). Sempre 200 para sessão válida (inclusive `false/false`).

```json
{ "canApprove": true, "canApproveLeadership": false }
```

- `canApprove`: pode decidir pedidos de **contribuidor** desta squad (AM/SM/PO/People Lead da squad, People/Tribe Lead
  da tribo, ou ADMIN). Use para mostrar a aba "Pedidos".
- `canApproveLeadership`: pode decidir pedidos de **liderança** (People Lead/Tribe Lead da squad ou da tribo, ou ADMIN).

### `GET /api/squads/{id}/link-requests`

- `status`: `PENDING` (padrão) | `APPROVED` | `REJECTED` | `ALL` (sem caixa). Outro valor = 400.
- Ordenado por `createdAt` decrescente. `size` (1–200, padrão 100) e `page` (≥ 0, padrão 0). Mais resultados = pedir
  `page + 1` enquanto vier uma página cheia. Fora desses limites = 400.
- Só devolve pedidos da squad `{id}` (e do alias `MISSI`/`DDWMISSI`).

```json
[
  {
    "id": "7d1c…",
    "requester": { "id": "u-123", "name": "Ana Paula", "email": "ana@totvs.com.br" },
    "projectKey": "DDWMISSI",
    "memberId": null,
    "memberName": null,
    "emailInJira": "ana.nogueira@totvs.com.br",
    "message": "Entrei no time esta semana",
    "status": "PENDING",
    "createdAt": "2026-10-17T12:00:00Z",
    "decidedAt": null,
    "decidedBy": null,
    "decisionNote": null,
    "kind": "CONTRIBUTOR",
    "requestedRoleName": "Developer",
    "canDecide": true
  }
]
```

### `POST …/approve`

Corpo opcional `{ "memberId": "…" }` (linha do roster que o aprovador confirma; **sobrescreve** a do pedido). Efeito, em
transação única:

1. Pedido **com linha** (do pedido ou do corpo): liga a conta à linha existente (`OnboardingService.linkApproved`, como na V74).
2. Pedido **sem linha, com `requestedRoleName`** (contribuidor que não aparece no roster): **cria** a linha em
   `project_member_roles`: `user_id` = solicitante, `source = 'APPROVED'`, papel = `requestedRoleName` (allowlist),
   `is_leadership = false`, nome/e-mail do solicitante. Se o solicitante já tem linha igual na squad, reaproveita (idempotente).
3. Sem linha e sem `requestedRoleName` = 422 `"Informe a linha do roster"` (o aprovador deve mandar `memberId`).
4. Dual-write (até a Etapa 5): `users.default_project_id` e `users.squad_id` do solicitante são preenchidos **se estiverem vazios
   ou "Sem Time"**; nunca troca a squad ativa de quem já tem uma.
5. Marca `APPROVED`, audita e notifica o solicitante. O cache de vínculos é invalidado: o próximo
   `GET /api/me/memberships` do solicitante já traz a squad.

**O `kind` que vale na autorização é o EFETIVO**: `LEADERSHIP` se o pedido é `LEADERSHIP` **ou** se a linha escolhida
(do pedido ou do corpo) é de liderança. Um AM não consegue "promover" um contribuidor apontando uma linha de liderança: dá 403.

### `POST …/reject`

Corpo opcional `{ "reason": "…" }` (até 500). Mesmo critério de poder do approve. `decisionNote = reason`.

## Quem pode decidir

| Papel do chamador | Contribuidor | Liderança |
|---|---|---|
| ADMIN | sim | sim |
| pmr `isLeadership` AGILE_MASTER / SCRUM_MASTER / PRODUCT_OWNER **na squad do pedido** | sim | não |
| pmr `isLeadership` PEOPLE_LEAD ou TRIBE_LEAD **na squad** ou em outra squad **da mesma tribo** (tribo não vazia) | sim | sim |
| Developer/QA/etc., AM de **outra** tribo/squad, Agile Coach, quem só tem jobTitle ou papel autodeclarado | não | não |

Nota: AGILE_COACH **não** aprova (fora da allowlist fixa), embora tenha acesso transversal à tribo.
O poder vem **só** de linha `project_member_roles` com `is_leadership = true` e `role_key` na allowlist fixa do código
(`LinkRequestApprovalPolicy`); jobTitle, papel autodeclarado e `users.role = LEAD` não contam. O solicitante **nunca**
decide o próprio pedido (nem aprovar nem recusar, nem o ADMIN): 403.

## Erros

`POST /api/link-requests` (além dos da V74):

| Caso | Status |
|---|---|
| `kind` desconhecido / `requestedRoleName` > 80 | 400 |
| `kind` contradiz a linha / `memberId` + `requestedRoleName` / `LEADERSHIP` + `requestedRoleName` | 422 |
| `requestedRoleName` fora da allowlist (inclui qualquer papel de liderança) | 422 |
| solicitante já tem linha com esse papel na squad | 409 |

Rotas por squad:

| Caso | Status |
|---|---|
| flag `app.link-requests.squad-approval=off` | **404** (as rotas por squad "não existem") |
| squad inexistente | 404 |
| chamador sem poder de aprovar nesta squad (lista, approve, reject) | 403 |
| pedido inexistente **ou de outra squad** (rota errada) | 404 `"Pedido não encontrado"` |
| pedido já decidido | 409 `"Esse pedido já foi decidido."` |
| pedido do próprio chamador | 403 `"Você não pode decidir o próprio pedido…"` |
| pedido (efetivamente) de liderança e chamador sem poder de liderança | 403 |
| approve sem linha e sem papel pedido | 422 `"Informe a linha do roster"` |
| linha de outra squad | 422 |
| linha já ligada a outra conta (pedido continua PENDING) | 409 |
| linha inexistente / conta do solicitante inexistente | 404 |
| `status` inválido, `page`/`size` fora do limite na lista / `reason` > 500 | 400 |

`/api/admin/link-requests*` continua exigindo `role=ADMIN` e funciona com a flag ligada ou desligada.

## Flag

`app.link-requests.squad-approval` = `on` (padrão) | `off`; env `APP_LINK_REQUESTS_SQUAD_APPROVAL` (também no
`docker-compose.yml`). Valor desconhecido cai em `off` (comportamento anterior) com um aviso no log.

- `off`: as rotas `/api/squads/{id}/link-requests*` respondem **404**; só ADMIN aprova (pela rota de admin, como na V74);
  as notificações de pedido novo vão só para ADMINs, com o link antigo.
- Reverter = `APP_LINK_REQUESTS_SQUAD_APPROVAL=off` e reiniciar. A V76 é só aditiva; não há o que desfazer no banco.

## Notificações (sino)

Tipo `LINK_REQUEST`, `dedupeKey` por pedido.

| Gatilho | Para | `link` |
|---|---|---|
| Pedido criado (`CONTRIBUTOR`) | aprovadores da squad: pmr `isLeadership` AM/SM/PO/PL na squad + PL/TL de squads da mesma tribo | `/squad/roster?tab=pedidos&squadId=<KEY>` |
| Pedido criado (`LEADERSHIP`) | só PL/TL da squad e da tribo | `/squad/roster?tab=pedidos&squadId=<KEY>` |
| Pedido criado, **sem nenhum aprovador** com conta (ou flag `off`) | ADMINs ativos (fallback) | `/admin?tab=aprovacoes` |
| Decidido (aprovado) | o solicitante | `/painel` |
| Decidido (recusado) | o solicitante | `/onboarding` |

O solicitante nunca é notificado do próprio pedido novo; ADMIN **só** recebe quando não há aprovador.

## Auditoria (`audit_logs`)

Além de `LINK_REQUEST_CREATED/APPROVED/REJECTED` (V74, `performedBy` = e-mail), cada decisão grava
`LINK_REQUEST_APPROVED_BY` / `LINK_REQUEST_REJECTED_BY` com **só ids**: `performedBy` = id do aprovador e
`details` = `pedido=<id> squad=<KEY> solicitante=<id> tipo=<CONTRIBUTOR|LEADERSHIP> via=<SQUAD|ADMIN> [linha=<id>]`.
Tentativa negada vira log `WARN` com ids (sem e-mail/nome).

## Banco (V76)

`link_requests` ganha `requested_role_name VARCHAR(80)` e `kind VARCHAR(12)` (`CONTRIBUTOR|LEADERSHIP`) e o índice
`(project_key, status, created_at DESC)`. As linhas anteriores recebem `kind = LEADERSHIP` se o `member_id` aponta
para uma linha `is_leadership = true`, senão `CONTRIBUTOR`. Só `ADD COLUMN IF NOT EXISTS`, um `UPDATE` de preenchimento
(idempotente, só onde `kind IS NULL`) e `CREATE INDEX IF NOT EXISTS`; nenhum DELETE/DROP.
