# API do Brainstorming: encerrar, facilitação, limite de votos, autoria e "editada"

Rodada de backend das cerimônias (2/3). **Tudo aditivo e retrocompatível**: o frontend que está na homologação continua funcionando
sem mudar uma linha (campos novos opcionais, nenhuma rota removida, nenhum evento novo). Migração **V80** só adiciona colunas nulas.

> Atenção ao prefixo real das rotas: é **`/api/brainstormings`** (plural, sem `/boards`), o mesmo que `src/app/brainstorming/api.ts`
> já usa. Os caminhos novos abaixo seguem esse prefixo.

## Resumo

| O quê | Como | Quem |
|---|---|---|
| Encerrar | `POST /api/brainstormings/{id}/finish` | facilitador atual, criador do mural ou ADMIN |
| Reabrir | `POST /api/brainstormings/{id}/reopen` | idem |
| Assumir a facilitação | `POST /api/brainstormings/{id}/facilitation/claim` | participante da sala (ver regra), criador ou ADMIN |
| Passar a facilitação | `POST /api/brainstormings/{id}/facilitation/transfer` `{ "userId": "..." }` | facilitador atual ou ADMIN |
| Limite de votos | campo `votesPerParticipant` no mural (criar e `PATCH`) | criar: quem cria; `PATCH`: facilitador/ADMIN |
| Quem editou / quando | `editedAt` (sempre) e `editedBy`/`editedByName` (só mural não anônimo) na ideia | leitura |
| Nome do autor | `authorName` na ideia (só mural não anônimo) | leitura |
| Nome do facilitador | `facilitatorId` e `facilitatorName` no mural; `facilitator` e `displayName` no participante | leitura |
| Quem pode editar o texto de ideia alheia | `settings.restrictEdits` (padrão `false` = como hoje) | facilitador liga |

Nenhum evento novo: toda mudança do mural (encerrar, reabrir, assumir, passar, limite, restrição de edição) sai no
`BOARD_UPDATED` que já existe, com o mural inteiro no `payload` (agora com os campos novos). Não há nada que dependa de `?presence=1`
para estes recursos; a **presença** (`PRESENCE_UPDATED`, `docs/presence-api.md`) continua opt-in e é só consultada pelo servidor
para decidir o "assumir".

## Mural: campos novos

`GET /api/brainstormings/{id}`, `GET /api/brainstormings?squadId=`, as respostas de `POST`/`PATCH` e o payload de `BOARD_UPDATED`:

```json
{
  "id": "b-123",
  "creatorId": "uid-ana",
  "title": "Como reduzir o tempo de deploy",
  "team": "DDWMISSI",
  "createdAt": "2026-10-17T13:00:00Z",
  "phase": "ideation",
  "settings": { "isAnonymous": false, "isRevealed": true, "restrictEdits": false },
  "timer": { "status": "stopped", "endTime": null, "initialDuration": 600, "remainingOnPause": 600 },
  "participantIds": ["uid-ana"],

  "status": "OPEN",
  "finishedAt": null,
  "facilitatorId": "uid-ana",
  "facilitatorName": "Ana",
  "votesPerParticipant": 5
}
```

| Campo | Tipo | Observações |
|---|---|---|
| `status` | `"OPEN"` \| `"FINISHED"` | sempre presente. Mural antigo (sem valor no banco) = `OPEN` |
| `finishedAt` | ISO-8601 \| `null` | preenchido ao encerrar, `null` ao reabrir |
| `facilitatorId` | string | **sempre presente**: o facilitador atual. Mural que nunca teve passagem de facilitação devolve o `creatorId` |
| `facilitatorName` | string \| `null` | apelido da pessoa na sala (o mesmo `nickname` da lista de participantes). `null` se ela não está na lista (ex.: ADMIN que assumiu sem ter entrado) |
| `votesPerParticipant` | inteiro 1..100 \| `null` | `null` = sem limite (o comportamento de sempre) |
| `settings.restrictEdits` | boolean | padrão ausente/`false`. Ver "Quem edita e quem apaga" |

`creatorId` **não muda nunca** (é o dono original). O facilitador é quem manda na sessão; o criador continua podendo encerrar,
reabrir e retomar a facilitação mesmo depois de passá-la.

**Compatibilidade no corpo de entrada:** `status`, `finishedAt`, `facilitatorId` e `facilitatorName` enviados em `POST`/`PATCH` são
**ignorados** (só mudam pelos endpoints próprios). Quem não manda os campos novos continua exatamente como antes.

### Criar e configurar o limite de votos

`POST /api/brainstormings` (criação) aceita `votesPerParticipant` e `settings.restrictEdits`:

```json
{ "title": "Retro de ideias", "team": "DDWMISSI", "votesPerParticipant": 3, "settings": { "isAnonymous": true, "restrictEdits": true } }
```

`PATCH /api/brainstormings/{id}` (facilitador/ADMIN) aceita `votesPerParticipant` com **inteiro 1..100** ou **`null` explícito** para
remover o limite. Campo ausente não mexe. Valor fora do intervalo, não inteiro ou `0` = `400` ("O limite de votos deve ser um número
inteiro entre 1 e 100."). `settings.restrictEdits` entra na mesclagem de `settings` como as outras chaves booleanas.

## Encerrar e reabrir

`POST /api/brainstormings/{id}/finish` e `POST /api/brainstormings/{id}/reopen`. Sem corpo. Resposta `200` com o mural.

```json
{ "id": "b-123", "status": "FINISHED", "finishedAt": "2026-10-17T14:02:11.482Z", "facilitatorId": "uid-ana", "...": "..." }
```

| Status | Quando |
|---|---|
| `200` | feito, ou já estava no estado pedido (**idempotente**: encerrar um encerrado devolve o mural como está, sem mudar `finishedAt` e sem novo evento; idem reabrir um aberto) |
| `401` | sem token |
| `403` | quem chama não é o facilitador atual, o criador nem ADMIN: "Apenas o facilitador pode encerrar o brainstorming." / "...reabrir..." |
| `404` | mural inexistente |

Depois de encerrado (`status = FINISHED`):

- **Leitura continua permitida** para todos: mural, ideias, grupos, participantes, WebSocket, exportação, criação do plano de ação.
- **Escrita é recusada com `409`** e a mensagem `"Este brainstorming foi encerrado. Peça ao facilitador para reabri-lo."`:
  criar/alterar ideia (`POST`/`PATCH …/ideas`), votar (`POST …/vote`), fundir (`POST …/merge/…`), apagar ideia, criar/alterar/apagar
  grupo. Mover, agrupar e priorizar são `PATCH …/ideas` e portanto também travam.
- `PATCH /api/brainstormings/{id}` num mural encerrado só aceita `title`; `phase`, `timer`, `settings` e `votesPerParticipant`
  respondem `409` (reabra primeiro). `POST /api/brainstormings` com o id de um mural existente segue a mesma regra.
- Continuam permitidos: entrar/sair da sala, apagar o mural (facilitador/ADMIN), reabrir, assumir/passar a facilitação.
- O cronômetro não é alterado pelo servidor ao encerrar.
- Murais antigos e novos nascem `OPEN`.

O encerramento aparece também no histórico e no "ao vivo" da Home: o status da sessão de Brainstorming em
`GET /api/rituals/BRAINSTORMING/history` e nas sessões ao vivo vira `FINISHED` assim que o mural é encerrado (antes só virava
`FINISHED` por inatividade de 3 h). Reabrir devolve a regra de atividade.

## Facilitação

### `POST /api/brainstormings/{id}/facilitation/claim` (assumir)

Sem corpo. Quem chama vira o facilitador. Resposta `200` com o mural (`facilitatorId` = quem chamou, `facilitatorName` preenchido).

Regras (a presença vem do `PresenceRegistry`, ou seja, da conexão WebSocket da sala, com ou sem `presence=1`):

1. Quem chama já é o facilitador: `200`, nada muda (idempotente).
2. Criador do mural ou ADMIN: assume **sempre**, sem checar presença.
3. Os demais precisam ser **participantes da sala** (já terem entrado, `POST …/participants`) e o facilitador atual precisa estar
   **offline há mais de N segundos**. N = `app.brainstorming.facilitator-offline-seconds` (padrão **60**).

| Status | Quando |
|---|---|
| `200` | assumiu (ou já era) |
| `401` | sem token |
| `403` | não é participante da sala: "Entre na sala para assumir a facilitação." |
| `404` | mural inexistente |
| `409` | facilitador online: `"O facilitador está online."` |
| `409` | facilitador offline há menos de N s: `"O facilitador saiu há pouco. Tente de novo em instantes."` |

"Offline" = nenhuma conexão WebSocket aberta dele nesta sala **neste processo do backend**. Depois de um reinício do servidor, o
relógio de "offline" começa no reinício (ninguém consegue assumir nos primeiros N s só porque o servidor reiniciou).

### `POST /api/brainstormings/{id}/facilitation/transfer` (passar)

```json
{ "userId": "uid-bia" }
```

Só o **facilitador atual ou ADMIN**. O destino precisa ser participante da sala. Resposta `200` com o mural.

| Status | Quando |
|---|---|
| `200` | passou (ou o destino já era o facilitador: idempotente) |
| `400` | `userId` ausente ou vazio |
| `403` | quem chama não é o facilitador atual nem ADMIN: "Apenas o facilitador pode passar a facilitação." |
| `404` | mural inexistente, ou `userId` não é participante da sala: "Essa pessoa não está nesta sala." |

Efeitos de trocar o facilitador (assumir ou passar): o novo facilitador ganha os poderes de facilitador (fase, timer, configurações,
apagar ideia alheia, **ver o texto de ideias ocultas**) e o anterior os perde (se for o criador, ainda pode encerrar/reabrir e
retomar). O evento `BOARD_UPDATED` leva o novo `facilitatorId`/`facilitatorName`. **O frontend atual calcula "sou facilitador"
só por `creatorId`**; para a troca valer na tela ele precisa passar a comparar com `facilitatorId`.

Auditoria: log `INFO` com `boardId`, id de quem agiu e id do destino (nunca nome nem e-mail).

## Limite de votos

- `votesPerParticipant = null` (padrão): sem limite, igual a hoje.
- Com limite, `POST …/ideas/{ideaId}/vote` que **adicionaria** um voto e faria a pessoa passar do limite responde
  **`409`** `"Você já usou seus 3 votos. Remova um voto para votar em outra ideia."` (o módulo usa 409 para conflito de estado e 400 só
  para dado malformado; o limite é conflito de estado).
- A conta é "ideias do mural em que o `userId` do token consta em `votes`". **Remover** o voto (o mesmo `POST …/vote` quando já votou)
  sempre funciona e devolve a cota. Fundir ideias une votos sem repetir, então quem votou nas duas recupera um voto.
- A conta é feita com lock no mural: dois votos simultâneos da mesma pessoa em ideias diferentes nunca passam do limite.
- **Reduzir o limite abaixo do que alguém já usou é permitido** (decisão: não se desfazem votos já dados): ninguém perde voto, quem
  está acima do novo limite só não consegue votar de novo até remover votos. Aumentar libera na hora. A tela pode mostrar
  "x de N votos" calculando `votes.includes(meuId)` nas ideias; o servidor é quem recusa.
- Ideias ocultas contam (o voto é real no servidor, mesmo que a saída venha com `votes: []` para quem não pode ler).

## Ideia: campos novos

`GET …/ideas`, respostas de `POST`/`PATCH …/ideas`, `…/vote`, `…/merge` e o payload de `IDEA_SAVED`:

```json
{
  "id": "i-1",
  "boardId": "b-123",
  "content": "Automatizar o smoke test",
  "authorId": "uid-ana",
  "authorName": "Ana",
  "createdAt": "2026-10-17T13:05:00Z",
  "votes": ["uid-bia"],
  "position": { "x": 100, "y": 100 },
  "editedAt": "2026-10-17T13:20:00.123Z",
  "editedBy": "uid-caio",
  "editedByName": "Caio"
}
```

Todos opcionais e omitidos quando nulos:

| Campo | Aparece quando |
|---|---|
| `editedAt` | o **texto** da ideia foi alterado depois de criada (mudar posição, grupo, ligação, qualificadores ou cor **não** conta). Fusão conta para a ideia de destino |
| `editedBy` | idem **e** o mural não é anônimo. É o `userId` do **token** de quem editou |
| `editedByName` | idem `editedBy`, e o editor consta na lista de participantes |
| `authorName` | o mural **não** é anônimo (`settings.isAnonymous` falso), a ideia não está oculta para quem lê, e o autor consta na lista de participantes |

**Sem vazamento (regra de redação, por destinatário, REST e WebSocket):**

| Situação | `authorId` / `authorName` / `editedBy` / `editedByName` | `editedAt` |
|---|---|---|
| Mural anônimo (`isAnonymous: true`) | nunca saem (para ninguém; `authorId` só para o próprio autor, como hoje; `editedBy` só se quem lê foi quem editou) | sai |
| Ideias ocultas (`isRevealed: false`), quem lê não é autor nem facilitador | nunca saem (ideia redigida) | não sai |
| Demais casos | saem | sai |

Os nomes vêm do `nickname` da lista de participantes (o mesmo da tela). Quem foi removido da sala (`DELETE …/participants/{id}`)
deixa de ter nome: `authorName`/`editedByName` somem e o frontend cai no nome que já mostra hoje.

## Participante: campos novos

`GET …/participants` e eventos `PARTICIPANT_JOINED`:

```json
{ "id": "uid-ana", "boardId": "b-123", "nickname": "Ana", "role": "Dev", "isCreator": true, "lastActive": "…",
  "displayName": "Ana", "facilitator": true }
```

- `displayName`: o nome para exibir (hoje igual a `nickname`; é o campo que a tela deve preferir daqui para a frente).
- `facilitator`: `true` para o facilitador atual do mural (`isCreator` continua significando "criou o mural"). ADMIN que não é o
  facilitador não é marcado.
- Nos eventos `PARTICIPANT_JOINED` o campo `facilitator` acompanha o estado no momento da entrada; para o estado atual use o
  `facilitatorId` do mural (`BOARD_UPDATED`).

## Quem edita e quem apaga ideia (regra atual e a nova)

**Regra de hoje (e o padrão que continua valendo):** qualquer pessoa autenticada edita o texto, a posição, o grupo, a ligação e os
qualificadores de qualquer ideia do mural (a tela mostra o lápis "Editar a ideia" para todos e a Teia, os Grupos e a Matriz dependem de
mover ideias alheias). Apagar: autor, facilitador ou ADMIN (o servidor já impõe isso hoje).

**Nova, opt-in: `settings.restrictEdits = true`.** Com ela ligada, **mudar o texto** de uma ideia (`PATCH` com `content` diferente do
atual, ou `POST` de ideia existente com texto diferente) só é permitido ao **autor, ao facilitador atual ou a ADMIN**; os demais
recebem `403` `"Só o autor da ideia ou o facilitador podem editar o texto."`. Continuam livres para qualquer participante, com a
restrição ligada: mover (`position`), agrupar (`groupId`), ligar (`parentId`), priorizar (`qualifiers`), votar e **fundir** (juntar
ideias é um fluxo legítimo que mexe na ideia alheia; o servidor mantém permitido). Apagar não muda.

Por que opt-in e não padrão: o frontend na homologação mostra o botão de editar para todos; impor a restrição para todos os murais
quebraria essa tela com `403`. Quando o frontend novo esconder o lápis de quem não pode, ele pode criar os murais já com
`restrictEdits: true` (ou oferecer o interruptor ao facilitador). Quem edita o próprio texto ou é facilitador nunca é afetado.

Em qualquer modo o servidor registra `editedAt`/`editedBy` com o `userId` do **token** (nunca do corpo).

## Retrocompatibilidade (o que não mudou)

- Nenhuma rota, verbo, status ou corpo existente mudou. Corpo antigo de `POST`/`PATCH` funciona igual.
- Respostas antigas só ganham campos novos (clientes que ignoram campos desconhecidos não notam).
- Murais anteriores à V80: `status = OPEN`, `facilitatorId = creatorId`, sem limite de votos, `restrictEdits` ausente (= livre), ideias
  sem `editedAt`.
- Sem `votesPerParticipant` o voto é o de sempre (liga/desliga, sem limite, sem 409 de cota).
- Eventos: nenhum tipo novo. `BOARD_UPDATED`/`IDEA_SAVED`/`PARTICIPANT_JOINED` ganham os campos acima.
- Único comportamento antigo que muda: num mural **encerrado** as escritas respondem `409` (só acontece depois de alguém usar
  `…/finish`, que o frontend atual não chama).

## Migração V80 (`V80__brainstorming_ceremony.sql`)

Só aditiva e idempotente (`ADD COLUMN IF NOT EXISTS`, colunas nulas, sem `UPDATE`/`DROP`/`DELETE`):

| Tabela | Coluna | Tipo |
|---|---|---|
| `brainstorming_boards` | `status` | `VARCHAR(20)` (nulo = `OPEN`) |
| `brainstorming_boards` | `finished_at` | `VARCHAR(40)` |
| `brainstorming_boards` | `facilitator_id` | `VARCHAR(255)` (nulo = o criador) |
| `brainstorming_boards` | `votes_per_participant` | `INTEGER` |
| `brainstorming_ideas` | `edited_at` | `VARCHAR(40)` |
| `brainstorming_ideas` | `edited_by` | `VARCHAR(255)` |

Reverter: o backend anterior ignora as colunas novas (JPA não as conhece); o `DROP COLUMN` não é necessário para voltar de versão.

## Exemplo de cliente

```ts
// Encerrar / reabrir / assumir / passar
await authFetch(`${API}/brainstormings/${id}/finish`, { method: 'POST' });
await authFetch(`${API}/brainstormings/${id}/reopen`, { method: 'POST' });
await authFetch(`${API}/brainstormings/${id}/facilitation/claim`, { method: 'POST' });
await authFetch(`${API}/brainstormings/${id}/facilitation/transfer`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId }),
});

// Sou facilitador? (cai no criador para servidor/mural antigos)
const facilitatorId = board.facilitatorId ?? board.creatorId;
const isFacilitator = me.id === facilitatorId;
const finished = board.status === 'FINISHED';
```

Erros trazem `{ "error": "409 CONFLICT", "message": "…em português…" }` (o `@ExceptionHandler` do controller); mostre `message`.
