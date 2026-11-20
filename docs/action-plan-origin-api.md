# Origem do Plano de ação — contrato (migração V78)

O Plano de ação (5W2H) nasce da Retrospectiva, do Radar de clima, do Brainstorming ou à mão. Até aqui o servidor não
guardava **de onde** o plano (ou a ação) veio nem impedia dois planos da mesma origem. A V78 e este contrato acrescentam:
a origem no plano e na ação, a trava de duplicidade no servidor, as contagens de ações na lista, o filtro por origem e o
prazo ("Quando") como data de verdade.

Tudo é **aditivo**: um cliente que não conhece os campos novos continua funcionando exatamente como antes. Todas as rotas
exigem o JWT. Datas ISO-8601, JSON camelCase. Erros no formato padrão do projeto (`{ "error": "...", "message": "..." }`).

## Tipos

```ts
type PlanSourceType = 'MANUAL' | 'RETRO' | 'HEALTH_CHECK' | 'BRAINSTORMING' | 'SHOWCASE';

// ActionPlanBoard (resposta de TODAS as rotas que devolvem plano) -> campos NOVOS no fim
interface ActionPlanBoard {
  id: string; creatorId: string; title: string; team: string;
  squadId?: string | null; sprintId?: string; createdAt: string;
  participantIds: string[]; isPublic: boolean;
  sourceType: PlanSourceType;     // NOVO. Plano antigo (anterior à V78) vem 'MANUAL'
  sourceId?: string | null;       // NOVO. Id do que originou o plano (id do radar, da retro, do board de brainstorming...). Null em MANUAL
  sourceTitle?: string | null;    // NOVO. Rótulo para exibir ("Radar de clima — Sprint 42"). Opcional
  tasksTotal?: number;            // NOVO, SÓ nas LISTAS (GET /api/action-plans...). Ausente em GET /{id} e no POST
  tasksDone?: number;             // NOVO, SÓ nas LISTAS. Ações com status 'done'
}

// ActionPlanTask (resposta de todas as rotas de ação) -> campos NOVOS no fim
interface ActionPlanTask {
  id: string; boardId: string;
  what: string; why?: string; where?: string; when?: string;   // 'when' segue sendo texto livre
  who?: string; how?: string; howMuch?: string;
  status: 'todo' | 'doing' | 'done' | 'blocked';
  authorId: string; order: number; createdAt: string; updatedAt: string;
  sourceItemId?: string | null;   // NOVO. Id do item de origem (cartão da retro, dimensão do radar, ideia do brainstorming)
  sourceLabel?: string | null;    // NOVO. Rótulo do item de origem ("Comunicação", "Automatizar o deploy"...)
  dueDate?: string | null;        // NOVO. 'yyyy-MM-dd' (sem hora nem fuso). Prazo de verdade; 'when' continua existindo
}
```

Campos sem valor vêm `null` (ou ausentes); trate `undefined`/`null` igual.

## 1. Criar plano com origem — `POST /api/action-plans`

Corpo (os campos antigos não mudam; os três novos são opcionais):

```json
{
  "title": "Plano — Radar de clima da Sprint 42",
  "team": "DDWMISSI",
  "squadId": "DDWMISSI",
  "sprintId": "sprint-42",
  "sourceType": "HEALTH_CHECK",
  "sourceId": "hc-7f3a...",
  "sourceTitle": "Radar de clima — Sprint 42"
}
```

Regras:

| Campo | Regra |
|---|---|
| `sourceType` | Opcional. Um de `MANUAL`, `RETRO`, `HEALTH_CHECK`, `BRAINSTORMING`, `SHOWCASE` (aceita minúsculas; o servidor grava em maiúsculas). Ausente = `MANUAL`. Outro valor = **400** |
| `sourceId` | **Obrigatório** quando `sourceType` não é `MANUAL` (1 a 80 caracteres, aparado). Com `MANUAL` (ou sem tipo) enviar `sourceId` = **400** |
| `sourceTitle` | Opcional, até 255. Ignorado em `MANUAL` |
| `title` | Como hoje (obrigatório, até 255) |

`sourceId` é **só um rótulo**: o servidor não confere se o radar/retro existe e **não concede acesso a nada** por ele. O
acesso segue sendo o da squad (ver "Autorização").

Resposta **201** = o plano criado, com `sourceType`, `sourceId` e `sourceTitle` preenchidos:

```json
{
  "id": "0c0b6a7e-...", "creatorId": "u1", "title": "Plano — Radar de clima da Sprint 42",
  "team": "DDWMISSI", "squadId": "DDWMISSI", "sprintId": "sprint-42",
  "createdAt": "2026-10-17T14:02:11.123", "isPublic": true, "participantIds": ["u1"],
  "sourceType": "HEALTH_CHECK", "sourceId": "hc-7f3a...", "sourceTitle": "Radar de clima — Sprint 42"
}
```

### Trava de duplicidade — 409

Só pode existir **um plano por origem em cada squad**: a chave é (`squadId`, `sourceType`, `sourceId`). Planos `MANUAL`
nunca colidem. Se já existe plano da mesma origem na mesma squad:

```
HTTP 409
{
  "error": "409 CONFLICT",
  "message": "Já existe um plano de ação criado a partir desta origem.",
  "existingPlanId": "5d1e...-..."
}
```

- `existingPlanId` é o id do plano existente. Se o plano existente for **privado** e quem chama não puder vê-lo (não é
  criador, participante nem ADMIN), a resposta continua **409**, mas **sem** `existingPlanId` (não vaza o id).
- O servidor **não** devolve o plano existente como se fosse sucesso: quem decide é o frontend. O caminho esperado é
  "já existe, abrir o plano" (`GET /api/action-plans/{existingPlanId}`).
- A trava vale também sob concorrência (índice único parcial no banco): duas criações simultâneas da mesma origem
  resultam em um 201 e um 409.
- Squads diferentes **podem** ter planos da mesma origem (o índice é por squad). Plano **sem squad** (`team` é texto
  livre como "Squad Geral", `squadId` nulo) também tem trava: ela vale entre os planos sem squad.
- Apagar o plano libera a origem (pode criar outro).

## 2. Criar ação com origem e prazo — `POST /api/action-plans/{id}/tasks`

```json
{
  "what": "Combinar um canal único para impedimentos",
  "why": "Impedimentos ficaram sem dono",
  "who": "Ana",
  "status": "todo",
  "sourceItemId": "comunicacao",
  "sourceLabel": "Comunicação",
  "dueDate": "2026-11-05"
}
```

| Campo | Regra |
|---|---|
| `sourceItemId` | Opcional, até 80 caracteres |
| `sourceLabel` | Opcional, até 255 |
| `dueDate` | Opcional, `yyyy-MM-dd`. Valor que não seja uma data ISO válida (ex.: `"05/11/2026"`, `"2026-02-30"`) = **400** `"Corpo da requisição ausente ou inválido."` |
| `when` | Segue como texto livre, independente de `dueDate` (o servidor não preenche um a partir do outro) |

Resposta **201** = a ação, com `sourceItemId`, `sourceLabel` e `dueDate`. Não há unicidade por item: o frontend é quem
evita criar a mesma ação duas vezes (a trava forte é a do plano).

### Editar ação — `PUT /api/action-plans/tasks/{taskId}` (edição parcial, como hoje)

- `dueDate` enviado muda o prazo. Para **limpar** o prazo envie `"clearDueDate": true` (campo ausente/`null` = não mexer).
- `sourceItemId` e `sourceLabel` **não mudam** depois da criação (a origem é imutável); se vierem no corpo, são ignorados.
- A origem do plano também é imutável: o `PUT /api/action-plans/{id}` continua só alterando `title` e `isPublic`.

## 3. Listas com contagens e filtro por origem — `GET /api/action-plans`

```
GET /api/action-plans?squadId=DDWMISSI                                  # planos da squad (como hoje) + contagens
GET /api/action-plans?squadId=DDWMISSI&sourceType=RETRO                 # só os de origem retro
GET /api/action-plans?squadId=DDWMISSI&sourceType=HEALTH_CHECK&sourceId=hc-7f3a...   # "o plano deste radar"
GET /api/action-plans?sourceType=HEALTH_CHECK&sourceId=hc-7f3a...       # sem squadId: ver abaixo
GET /api/action-plans?sprintId=sprint-42                                # como hoje (só públicos) + contagens
```

Cada item da lista traz tudo do plano **mais** `tasksTotal` e `tasksDone` (calculados numa consulta agregada, sem N+1):

```json
[
  {
    "id": "5d1e...", "title": "Plano — Radar de clima da Sprint 42", "squadId": "DDWMISSI", "isPublic": true,
    "sourceType": "HEALTH_CHECK", "sourceId": "hc-7f3a...", "sourceTitle": "Radar de clima — Sprint 42",
    "tasksTotal": 5, "tasksDone": 2
  }
]
```

Plano sem ações vem com `tasksTotal: 0`, `tasksDone: 0`.

Filtros:

| Parâmetro | Regra |
|---|---|
| `sourceType` | Um dos tipos acima (aceita minúsculas). Inválido = 400. `MANUAL` casa também com plano antigo (anterior à V78) |
| `sourceId` | Exige `sourceType` (senão 400) |
| sem `squadId` | Só aceito com `sourceType` **e** `sourceId` (senão a lista é a vazia de sempre). Devolve os planos dessa origem que o chamador pode ver e cuja squad ele pode ler (planos sem squad entram) |
| com `squadId` | Filtra dentro da squad, com a mesma checagem de acesso à squad de hoje |
| `sprintId` | Os filtros de origem **não** se combinam com `sprintId`; ele segue como hoje |

A lista por squad continua limitada aos 100 planos mais novos. Quem só quer saber "este radar já tem plano?" deve usar o
filtro com `sourceId` (não depende do limite).

## 4. Autorização (inalterada no que já existia; novo na criação)

- Plano **público** (padrão): qualquer autenticado lê e edita; **privado**: criador, participantes e ADMIN. Igual a hoje.
- **Criar plano numa squad** passa a exigir acesso de leitura à squad (a mesma regra de `GET ?squadId=`, via
  `SquadAccessService`, modo `authz` em vigor). Antes a criação não conferia: qualquer autenticado criava plano em squad
  alheia mandando o `squadId`. A squad do plano é a que o servidor resolve (`squadId` do corpo, senão `team`, desde que
  seja o id de uma squad cadastrada); `team` de texto livre ("Squad Geral") não é squad e não pede nada. Sem acesso =
  **403**, e a checagem vem **antes** da trava de duplicidade (quem não é da squad nunca vê 409).
- `sourceType`/`sourceId` nunca concedem acesso.

## 5. Retrocompatibilidade

- Corpo antigo (`{ "title", "team", "squadId", "sprintId", ... }`) cria um plano `MANUAL`: resposta igual à de antes mais
  `"sourceType": "MANUAL"`, `sourceId`/`sourceTitle` nulos.
- Ação antiga (`what/why/where/when/who/how/howMuch/status/order`) segue igual; `sourceItemId`, `sourceLabel` e
  `dueDate` vêm nulos.
- Planos criados antes da V78 vêm com `sourceType: "MANUAL"` (no banco ficam nulos; o servidor normaliza na resposta).
- Campos desconhecidos no corpo continuam ignorados. `tasksTotal`/`tasksDone` enviados no corpo são ignorados.

## 6. Migração V78 (resumo)

Só aditiva e idempotente (`IF NOT EXISTS`), sem `DELETE`/`DROP`/`UPDATE`:

- `action_plans`: `source_type VARCHAR(20)`, `source_id VARCHAR(80)`, `source_title VARCHAR(255)`.
- `action_plan_tasks`: `source_item_id VARCHAR(80)`, `source_label VARCHAR(255)`, `due_date DATE`.
- Índice **único parcial** `uq_action_plans_source` em (`COALESCE(UPPER(squad_id), '')`, `source_type`, `source_id`)
  `WHERE source_id IS NOT NULL` (planos antigos têm `source_id` nulo e ficam fora do índice) e um índice comum
  `idx_action_plans_source` em (`source_type`, `source_id`) para o filtro sem squad.
- Reverter: ver o relatório da entrega (dropar os dois índices e as seis colunas; nenhum dado antigo é tocado).
