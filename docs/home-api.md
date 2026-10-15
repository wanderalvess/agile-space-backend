# Home API — contrato (avisos, notificações, mensagens, ao vivo, histórico)

Contrato acordado entre `agile-space-backend` (feat/home-backend) e `agile-space-frontend` (feat/home-frontend).
Todas as rotas exigem o JWT atual (`JwtAuthenticationFilter`; userId/role vêm dos atributos do request, nunca do corpo).
Datas em ISO-8601 (`Instant`/UTC). JSON camelCase. Erros: 401 sem token, 403 sem acesso à squad, 404 inexistente.

Escopo de squads ("squads acessíveis"): as squads do usuário (ProjectMemberRole + squad da sessão) e, para
Agile Masters/liderança/ADMIN, as de `accessibleProjects` — reutilizar `SquadAccessService`, não reimplementar.

---

## 1. Avisos (announcements v2)

```ts
type AnnouncementType = 'INFO' | 'IMPORTANT' | 'MAINTENANCE';
type AudienceType = 'ALL' | 'SQUADS' | 'ROLES';
interface AnnouncementDto {
  id: string; title: string; content: string;
  type: AnnouncementType;
  audienceType: AudienceType;
  audienceSquadIds: string[];   // usado quando audienceType = SQUADS
  audienceRoles: string[];      // usado quando ROLES: UserRole (ADMIN/LEAD/MEMBER) ou roleKey de ProjectMemberRole (AGILE_MASTER, TRIBE_LEAD…)
  validFrom: string;            // default = createdAt
  validUntil: string | null;
  link: string | null;          // URL ou rota interna ("/room/abc")
  createdBy: string; createdByName: string | null; createdAt: string;
  dismissed: boolean;           // só nas rotas do usuário; false nas de admin
}
```

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/announcements/active` | `AnnouncementDto[]` — vigentes (validFrom ≤ agora < validUntil), do público do usuário, não dispensados. Ordem: MAINTENANCE, IMPORTANT, INFO; depois createdAt desc |
| GET | `/api/announcements/history?limit=20` | `AnnouncementDto[]` — do público do usuário com validFrom ≤ agora (inclui expirados e dispensados, com `dismissed`) createdAt desc |
| POST | `/api/announcements/{id}/dismiss` | 204 (idempotente) |
| GET | `/api/admin/announcements` | `AnnouncementDto[]` (ADMIN) — todos |
| POST | `/api/admin/announcements` | body `{title, content, type, audienceType, audienceSquadIds, audienceRoles, validFrom?, validUntil?, link?}` → `AnnouncementDto` (ADMIN). Gera notificações ANNOUNCEMENT para o público |
| PUT | `/api/admin/announcements/{id}` | mesmo body → `AnnouncementDto` (ADMIN) |
| DELETE | `/api/admin/announcements/{id}` | 204 (ADMIN) |
| GET | `/api/public/announcements` | mantém compatibilidade: só audienceType=ALL e vigentes |

Validação: title 1–120, content 1–2000, link ≤ 500 (http/https ou começando com "/"), validUntil > validFrom.

## 2. Notificações

```ts
type NotificationKind = 'INVITE' | 'REMINDER' | 'ACTION_ASSIGNED' | 'MESSAGE' | 'ANNOUNCEMENT';
interface NotificationDto {
  id: string; kind: NotificationKind;
  title: string | null;          // ex.: "Retro Sprint 41" (quando o "who" não é pessoa)
  text: string;                  // PT-BR curto, ex.: "convidou você para “Refinamento Sprint 43”."
  link: string | null;           // rota interna
  actorId: string | null; actorName: string | null; actorAvatarUrl: string | null;
  squadId: string | null;
  createdAt: string; readAt: string | null;
}
type NotificationCategory = 'FOR_YOU' | 'ANNOUNCEMENTS'; // FOR_YOU = todos os kinds exceto ANNOUNCEMENT e MESSAGE
interface UnreadCountDto { forYou: number; messages: number; announcements: number; total: number }
```

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/notifications?category=FOR_YOU&limit=30` | `NotificationDto[]` createdAt desc (só do próprio usuário) |
| GET | `/api/notifications/unread-count` | `UnreadCountDto` (`messages` = mensagens diretas não lidas) |
| POST | `/api/notifications/{id}/read` | 204 (404 se não for do usuário) |
| POST | `/api/notifications/read-all?category=FOR_YOU` | 204 (category opcional = todas) |

Gatilhos mínimos (backend):
- INVITE — convite criado para usuário existente (fluxo `Invite`) e convites a sessão, se existirem.
- REMINDER — poker assíncrono aberto: membros da squad (exceto o criador) recebem "falta seu voto", 1 por sala.
- ACTION_ASSIGNED — ação de retro (RetroCard.assignee) atribuída/alterada para um usuário (e ActionPlanTask, se houver dono).
- ANNOUNCEMENT — aviso novo, para cada usuário do público.
Não notificar o próprio autor da ação.

## 3. Mensagens diretas (entre usuários que compartilham ao menos uma squad)

```ts
interface UserRefDto { id: string; name: string; avatarUrl: string | null }
interface ConversationDto { id: string; otherUser: UserRefDto; lastMessage: MessageDto | null; unreadCount: number; updatedAt: string }
interface MessageDto { id: string; conversationId: string; senderId: string; body: string; createdAt: string }
```

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/conversations` | `ConversationDto[]` updatedAt desc |
| GET | `/api/conversations/contacts` | `UserRefDto[]` — usuários que compartilham squad com o usuário |
| POST | `/api/conversations` | body `{userId}` → `ConversationDto` (get-or-create; 403 sem squad em comum; 400 consigo mesmo) |
| GET | `/api/conversations/{id}/messages?limit=50&before=<ISO>` | `MessageDto[]` createdAt asc (403 se não participa) |
| POST | `/api/conversations/{id}/messages` | body `{body}` (1–2000) → `MessageDto` |
| POST | `/api/conversations/{id}/read` | 204 |

## 4. Canal do usuário (tempo real)

WebSocket `/ws/user?token=<jwt>` (mesma autenticação de handshake das salas). Servidor envia apenas sinais;
cliente refaz o GET correspondente (padrão REFRESH do projeto):

```json
{ "type": "NOTIFICATION" | "MESSAGE" | "ANNOUNCEMENT" | "UNREAD", "conversationId": "…opcional" }
```

Fallback: se o socket cair/não abrir, o cliente faz polling de `/api/notifications/unread-count` a cada 60 s.

## 5. Status ao vivo uniforme

```ts
type Tool = 'POKER' | 'RETRO' | 'SHOWCASE' | 'HEALTH_CHECK' | 'BRAINSTORMING';
type SessionStatus = 'LIVE' | 'ASYNC_OPEN' | 'PLANNING' | 'FINISHED';
interface LiveSessionDto {
  tool: Tool; id: string; title: string;
  squadId: string; squadName: string | null;
  participantsCount: number; status: SessionStatus;
  link: string;          // /room/{id}, /retro/{id}, /showcase/{id}, /health-check/{id}, /brainstorming/{id}
  startedAt: string | null; updatedAt: string | null;
}
```

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/live-sessions?squadId=` | `LiveSessionDto[]` com status ≠ FINISHED. Sem `squadId` = todas as squads acessíveis. Ordem: LIVE, ASYNC_OPEN, PLANNING |
| GET | `/api/action-plans?squadId=` | listagem por squad (nova; `sprintId` continua funcionando) |

Regras de status (fonte única: `SessionStatusResolver` no backend; documentar ajustes aqui):
- POKER: `sessionEndedAt` ≠ null → FINISHED; modo assíncrono → ASYNC_OPEN; `sessionStartedAt` ≠ null → LIVE; senão PLANNING.
- SHOWCASE: planning → PLANNING; active → LIVE; finished → FINISHED.
- HEALTH_CHECK: collecting → ASYNC_OPEN (pesquisa anônima, coleta ao longo do tempo); finished → FINISHED.
- RETRO / BRAINSTORMING: encerrada/fase final → FINISHED; senão LIVE se houve atividade recente, senão FINISHED (regra definida pelo backend — ver seção 8).
- Proteção contra sessões abandonadas: LIVE sem atividade há mais de 12 h é reportada como FINISHED.
- Sala criada e ainda não iniciada (PLANNING) conta como "ao vivo agora" (a home mostra "Aguardando"), mas some da lista depois de 24 h sem nenhum sinal (criação, início ou heartbeat de participante).

## 6. Histórico por ritual (drawer)

```ts
interface RitualSessionDto {
  tool: Tool; id: string; title: string; squadId: string;
  date: string;                 // início ou criação
  participantsCount: number;
  status: SessionStatus;
  summary: string | null;       // PT-BR: "60h em 6 tarefas", "18 cards · 3 ações abertas", "9 respostas", "24 ideias", "7 aprovadas, 1 ajuste"
  durationMinutes: number | null;
  link: string;
}
```

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/rituals/{tool}/history?squadId=&limit=10` | `RitualSessionDto[]`, date desc. `tool` em minúsculas: `poker`, `retro`, `showcase`, `health-check`, `brainstorming` |

## 7. Agregado da home

```ts
interface RitualFactsDto { tool: Tool; facts: string[]; openCount: number; last: RitualSessionDto | null }
interface SquadSummaryDto {
  squadId: string; name: string;
  sprintName: string | null; sprintDaysLeft: number | null; sprintProgressPct: number | null;
  liveCount: number;
  nextCeremony: { name: string; startsAt: string } | null;   // null quando a agenda só existe no cliente (useNextCeremony)
}
interface HomeDto {
  announcements: AnnouncementDto[];      // = /api/announcements/active
  unread: UnreadCountDto;
  multiSquad: boolean;                   // true para Agile Master/liderança com > 1 squad acessível
  squads: SquadSummaryDto[];             // multiSquad: todas as acessíveis; senão só a squad atual
  liveSessions: LiveSessionDto[];        // multiSquad: todas; senão a squad atual
  rituals: RitualFactsDto[];             // da squad atual (ou `squadId`)
}
```

| Método | Rota | Resposta |
|---|---|---|
| GET | `/api/home?squadId=` | `HomeDto`. `squadId` opcional (default = squad da sessão) |

## 8. Notas de implementação (backend)

**Status (fonte única: `SessionStatusResolver`)**
- POKER: como na seção 5. A regra de 12 h usa o último sinal de vida (início da sessão ou último heartbeat de participante); sala assíncrona não sofre a regra (fica ASYNC_OPEN até encerrar).
- SHOWCASE: `planning`/desconhecido → PLANNING; `active` → LIVE (FINISHED se sem escrita há mais de 12 h, via `updatedAt`); `finished` → FINISHED.
- HEALTH_CHECK: `collecting` → ASYNC_OPEN; `finished` → FINISHED.
- RETRO e BRAINSTORMING não têm estado de encerramento no modelo. Regra: LIVE se houve atividade nas últimas **3 h**, senão FINISHED. Atividade = RetroBoard.updatedAt (carimbado a cada escrita) / maior entre criação, última ideia e último `lastActive` de participante (Brainstorming). A busca de candidatas ao vivo olha os últimos 24 h (boards) ou 30 dias (poker/review/health).
- `GET /api/live-sessions` nunca devolve FINISHED. Cada ferramenta traz no máximo 50 sessões.

**Histórico e resumos** (`GET /api/rituals/{tool}/history`, sem `squadId` = squad da sessão; 403 sem acesso; 404 ferramenta desconhecida; `limit` máx. 50)
- poker: horas por papel somadas (`devPoints`+`qaPoints` das rodadas não puladas/canceladas): baralho `hours` → "60h em 6 tarefas"; `fibonacci` → "21 pontos em 4 tarefas"; camisetas ou sem número → "3 tarefas". `durationMinutes` = fim − início da sessão.
- retro: "18 cards · 3 ações abertas" (cards fora de colunas de tema `action`; ações = cards de coluna `action` com `isDone` ≠ true).
- showcase: decisões das tarefas ("7 aprovadas, 1 ajuste"); sem decisão, "N itens".
- health-check: "9 respostas" (pessoas que votaram). brainstorming: "24 ideias".
- `date` = início (poker) ou criação. `durationMinutes` só no poker (único com início e fim).

**Home**
- Squads acessíveis (`SquadAudienceService`): squad da sessão + `accessibleProjects` (project_member_roles, liderança transversal) + roster por vaga reivindicada/e-mail. `multiSquad` = liderança (ADMIN/LEAD, cargo de liderança ou papel de liderança) com mais de uma squad e sem `squadId` explícito. `squadId` explícito sem acesso → 403.
- Sprint: `Squad.activeSprintId` + `squad_metrics_rollup` dessa sprint (sprintName, `workdaysRemaining` → `sprintDaysLeft`, `doneIssues/totalIssues` → `sprintProgressPct`). Sem sync/rollup: nulls.
- `nextCeremony`: só squads em `ceremonyMode = manual` (`squads.ceremonies`, próxima ocorrência em America/Sao_Paulo). Modo Google Calendar → `null` (agenda só existe no cliente).
- `rituals[].facts`: "Última: <resumo>" e "N abertas agora"; `openCount` = sessões ao vivo da ferramenta na squad.
- Consultas em lote: sessões ao vivo = 1–3 consultas por ferramenta para todas as squads (máx. 30 squads); fatos só da squad atual.

**Outras decisões**
- `action_plans.squad_id` (novo, nullable): preenchido na criação quando `team`/`squadId` é id de squad/projeto cadastrado e, na migration, por backfill derivável (team = id de squad/projeto, ou sprint ligada a retros de uma só squad). `GET /api/action-plans?squadId=` exige acesso à squad e esconde planos privados de quem não é criador/participante/ADMIN. Planos antigos sem squad derivável ficam fora da listagem por squad.
- Notificações (rodam depois do commit, em transação própria; falha só vira log; autor nunca notificado): INVITE em `InviteService.createInvite` (e-mail de conta existente); REMINDER em `PokerService.saveOrUpdateRoom` (sala criada/alterada para `async`, dedupe `POKER_ASYNC:<sala>`); ACTION_ASSIGNED em `RetroService.saveOrUpdateCard` e `ActionPlanService.createTask/updateTask`. `RetroCard.assignee` e `ActionPlanTask.who` são **texto livre**, não userId: resolvidos por apelido de participante do board ou por nome/e-mail/usuário entre membros da squad; ambíguo ou desconhecido = não notifica. ANNOUNCEMENT em `AnnouncementService.create/update` (só aviso já vigente; uma por pessoa).
- Datas dos DTOs novos: `LocalDateTime` do servidor (UTC na JVM) convertido para `Instant`.
- Avisos: `createdBy` guarda o e-mail do token; `createdByName` é resolvido por e-mail/id. `DELETE` de aviso inexistente agora responde 404.
- `/ws/user` usa o mesmo handshake JWT (`?token=`) e as mesmas origens permitidas das salas; não há lista de caminhos a liberar (o filtro JWT só cobre `/api/**`, o rate limit também).

**Migrations**: `V70__direct_messages.sql`, `V71__notifications.sql`, `V72__announcements_v2.sql`, `V73__action_plans_squad_id.sql`.
