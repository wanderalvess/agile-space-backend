# Radar de clima e Retrospectiva — contrato novo (migração V79)

Complementa a API que o frontend da homologação já consome. Cobre o que o frontend deixou fora da tela por depender do
backend: quem já respondeu o Radar, encerramento real (`finishedAt`), apresentação sincronizada, status e "encerrar
retro", "escrevendo…" e o campo livre do check-in da Retro, e os números da sprint.

Tudo é **aditivo e retrocompatível**: campos novos são opcionais, nenhum campo, rota ou evento existente muda de
significado, e **evento novo só vai para quem pediu** (`?typing=1`, `?presentation=1`), no mesmo padrão do `?presence=1`
(ver `docs/presence-api.md`). Todas as rotas exigem o JWT. JSON camelCase. Erros no formato padrão
(`{ "error": "...", "message": "..." }`).

Migração **V79**: só `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` (colunas nulas). Linhas existentes ficam com tudo nulo;
nenhuma data é inventada.

---

## Parte 1. Radar de clima (`/api/health-checks`)

### 1.1 Campos novos no quadro (`HealthCheckBoard`)

Presentes em `GET /{id}`, `GET ?squadId=`, `POST` (criar), `POST /{id}/finish` e no payload de `BOARD_UPDATED`.

```ts
interface HealthCheckBoard {
  // ...campos de hoje, inalterados. `status` CONTINUA 'collecting' | 'finished'.
  finishedAt: string | null;            // NOVO. ISO-8601 (Instant, UTC). null enquanto aberto E nos radares encerrados antes da V79
  lifecycleStatus: 'OPEN' | 'FINISHED'; // NOVO. Derivado de `status` ('finished' => FINISHED). Só leitura; o corpo do POST ignora
  presentationStage: string | null;     // NOVO. Etapa da apresentação publicada pelo facilitador (ver 1.4). null = sem apresentação
  respondedCount?: number;              // NOVO. Pessoas (que ainda estão na sala) com pelo menos 1 voto
  completedCount?: number;              // NOVO. Pessoas que votaram em TODAS as dimensões
  participantsCount?: number;           // NOVO. Pessoas na sala (linhas de /participants)
}
```

Por que `lifecycleStatus` e não "`status` OPEN|FINISHED": `status` já existe no Radar com os valores `collecting|finished`
e o frontend em produção depende deles. O campo derivado fica ao lado, sem trocar nada.

`respondedCount <= participantsCount` e `completedCount <= respondedCount`, sempre. Quem saiu da sala (`DELETE
/participants/{userId}`) deixa de contar nos três números (os votos dele continuam no resultado, como hoje).
Em `GET ?squadId=` (lista) os três números vêm calculados em lote (2 consultas para a lista inteira).

**Privacidade**: os números são só contagens. Nenhuma resposta traz voto, comentário ou "quem votou o quê", nem antes nem
depois de encerrar. Os valores seguem anônimos exatamente como hoje (`GET /{id}/votes` não mudou).

### 1.2 Progresso leve — `GET /api/health-checks/{id}/progress`

Para o "N de M já responderam" e, para o facilitador, a lista de quem falta.

```json
{
  "boardId": "hc-7f3a",
  "dimensionsCount": 6,
  "participantsCount": 8,
  "respondedCount": 5,
  "completedCount": 3,
  "pendingParticipantIds": ["uid-caio", "uid-dani", "uid-edu"]
}
```

- `pendingParticipantIds` (userIds de quem está na sala e ainda **não votou em nenhuma dimensão**) vem **só para o
  facilitador** (criador do radar ou ADMIN). Para qualquer outra pessoa o campo **não existe** na resposta.
- A lista traz só ids: o apelido vem de `GET /{id}/participants`, que o cliente já carrega.
- Nunca traz o que alguém votou. O facilitador passa a saber **quem ainda não participou** (é o pedido), mas não o voto
  de ninguém. Os valores seguem anônimos; só a participação deixa de ser invisível para ele.
- `404` radar inexistente. `401` sem token. Cabeçalho `Cache-Control: no-store`.

### 1.3 Tempo real: contagem de respostas

Reaproveita o `BOARD_UPDATED` que o servidor já envia (a todas as sessões, sem opt-in, envelope de hoje):

- continua sendo enviado ao criar e ao encerrar (agora com os campos novos);
- **passa a ser enviado também** quando um voto muda `respondedCount` ou `completedCount` (a **primeira** resposta de
  alguém e quando alguém **completa** as dimensões). Votar de novo ou trocar um voto já dado **não** gera evento. Tráfego
  máximo por radar: `2 × participantes` eventos.
- `VOTE_SAVED` não mudou (só vai às sessões de quem votou).

O frontend atual trata `BOARD_UPDATED` substituindo o quadro, então não quebra (só ganha campos).

Entrada e saída de pessoas continuam avisadas por `PARTICIPANT_JOINED` / `PARTICIPANT_LEFT`: o cliente deriva
`participantsCount` da lista que já sincroniza e usa `respondedCount`/`completedCount` do quadro. O facilitador, ao ver
`respondedCount` mudar, pode refazer `GET /{id}/progress` para atualizar a lista de pendentes (uma chamada leve).

### 1.4 Encerramento real

`POST /api/health-checks/{id}/finish` (já existe) agora também grava `finishedAt` (instante do encerramento) e limpa
`presentationStage`. Idempotente: encerrar de novo devolve o radar como está, **sem** mudar `finishedAt`. O caminho
legado (`POST /api/health-checks` com `status: "finished"` num radar existente) passa pelo mesmo código.

Radares encerrados antes da V79: `finishedAt = null`, `lifecycleStatus = "FINISHED"` (vem de `status`). A tela deve
tratar `finishedAt` nulo como "data desconhecida" e cair em `createdAt`/omitir.

### 1.5 Apresentação sincronizada

O facilitador publica a etapa em que está; quem abriu a apresentação segue.

| Rota | Quem | Efeito |
|---|---|---|
| `PUT /api/health-checks/{id}/presentation` | criador do radar ou ADMIN | grava `presentationStage` |
| `DELETE /api/health-checks/{id}/presentation` | criador do radar ou ADMIN | limpa (fim da apresentação) |

Corpo do `PUT`: `{ "stage": "radar" }`. `stage`: 1 a 40 caracteres de `A-Z a-z 0-9 _ - .` (o servidor **não** fixa a lista de
etapas; o frontend define: ex. `score`, `radar`, `frequency`, `comments`, `plan`). Resposta: o quadro (200). Idempotente.

| Status | Quando |
|---|---|
| `200` | gravou / limpou |
| `400` | `stage` ausente, vazio ou fora do formato |
| `403` | quem chama não é o criador nem ADMIN |
| `404` | radar inexistente |
| `409` | o radar ainda não foi encerrado (a apresentação mostra o resultado, que só existe depois) |

Quem não é facilitador **não** consegue mudar a etapa (nem por `POST /api/health-checks`: o corpo continua sendo ignorado
em radar existente, salvo para encerrar).

**Evento opt-in `PRESENTATION_STAGE`** — só para quem abriu o WebSocket com `?presentation=1`:

```
wss://<host>/ws/health-check/{boardId}?token=<jwt>&presentation=1
```

```json
{
  "type": "PRESENTATION_STAGE",
  "boardId": "hc-7f3a",
  "payload": { "stage": "radar" },
  "timestamp": 1760700000000
}
```

- `payload.stage = null` quando o facilitador encerra a apresentação.
- É **estado completo** (idempotente), enviado a cada `PUT`/`DELETE` e ao encerrar o radar com etapa publicada. Aceita
  combinar com `presence=1` (`?token=…&presence=1&presentation=1`).
- **Sem snapshot ao conectar**: quem entra (ou reconecta) lê `presentationStage` no `GET /{id}` / `BOARD_UPDATED`, que o
  cliente já recarrega na abertura.
- Sem `presentation=1` a sessão **não recebe** o evento (o frontend antigo trata tipo desconhecido como "recarrega tudo").
  O `presentationStage` aparece nos GET e no `BOARD_UPDATED` para todos, mas **mudar de etapa não dispara
  `BOARD_UPDATED`**.

---

## Parte 2. Retrospectiva (`/api/retros`)

### 2.1 Campos novos

```ts
interface RetroBoard {
  // ...campos de hoje, inalterados
  finishedAt: string | null;            // NOVO. Mesmo formato de `createdAt` (LocalDateTime ISO, sem fuso). null = aberta
  lifecycleStatus: 'OPEN' | 'FINISHED'; // NOVO. Só leitura. Retro antiga (anterior à V79) = 'OPEN'
}

interface RetroParticipant {
  // ...campos de hoje, inalterados
  checkinNote?: string | null;          // NOVO. Texto livre do check-in, até 500 caracteres
}
```

`finishedAt` e `lifecycleStatus` **nunca** são lidos do corpo de `POST/PATCH /api/retros` (nem na criação): só mudam por
`finish`/`reopen`. `votingStatus` (`disabled|active|finished`) é outra coisa e não mudou: é a fase de votação **dentro** da
sessão; `lifecycleStatus` é a sessão inteira.

### 2.2 Encerrar e reabrir

| Rota | Quem | Efeito |
|---|---|---|
| `POST /api/retros/{id}/finish` | facilitador (criador) ou ADMIN | `lifecycleStatus = FINISHED`, `finishedAt = agora` |
| `POST /api/retros/{id}/reopen` | facilitador (criador) ou ADMIN | `lifecycleStatus = OPEN`, `finishedAt = null` |

Sem corpo. Resposta: o quadro (200). **Idempotentes**: encerrar uma retro já encerrada não muda `finishedAt`; reabrir
uma aberta não faz nada. Ambas publicam `BOARD_UPDATED` (o quadro inteiro) a todas as sessões. `403` para quem não é
facilitador/ADMIN (incluindo participantes comuns), `404` quadro inexistente.

**Regra do quadro encerrado: somente leitura para participantes.** Facilitador e ADMIN continuam podendo tudo
(inclusive reabrir). Para um participante comum, em retro `FINISHED`, **recebem `409`**
(`"Esta retrospectiva foi encerrada. Só o facilitador pode alterá-la."`):

- criar, editar, mover, marcar como feito, reagir e apagar card (`POST/DELETE /cards...`)
- votar (`POST /cards/{id}/vote`)
- fundir cards (`POST /cards/{t}/merge/{s}`) e importar ações (`POST /cards/import`)

**Não** são bloqueados: leitura de tudo, WebSocket, o chat, entrar na sala (`POST /participants`, inclusive registrar o
check-in) e sair. Retro nunca encerrada (qualquer retro anterior à V79) se comporta exatamente como antes.

### 2.3 Check-in com texto livre

`POST /api/retros/{id}/participants` ganha `checkinNote` (opcional, **≤ 500 caracteres**, `400` se passar). Segue o
mesmo merge por campo do `healthCheckAnswer`: campo ausente/`null` **não apaga**; `""` (vazio ou só espaços) **limpa**.
O participante só altera o próprio (o id sempre vem do JWT). Visibilidade **igual à da resposta do check-in**: sai em
`GET /participants` e `PARTICIPANT_JOINED` para quem está na sala; não é anônimo.

### 2.4 "Escrevendo…" — evento efêmero `TYPING`

Só em memória, nunca persistido. Opt-in nos dois sentidos: **só sessões abertas com `?typing=1` enviam e recebem**.

```
wss://<host>/ws/retro/{boardId}?token=<jwt>&typing=1
```

**Cliente → servidor** (mensagem de texto do próprio WebSocket, até 512 caracteres):

```json
{ "type": "TYPING", "scope": "card", "columnKey": "good" }
{ "type": "TYPING", "scope": "chat", "channelId": "geral" }
```

- `scope`: `"card"` (escrevendo um card; `columnKey` opcional, ≤ 50) ou `"chat"` (`channelId` obrigatório, ≤ 100; os
  mesmos canais do chat: `geral`, `role-<Categoria>`, `dm_<uidA>_<uidB>`). Ausente = `"card"`.
- **O `userId` nunca vem do corpo**: o servidor usa o do JWT do handshake. Campos extras (inclusive `userId`) são ignorados.
- Reenviar a cada ~3 s enquanto a pessoa digita; o cliente não precisa avisar que parou (o evento expira).
- **Limite de taxa**: 1 evento a cada 2 s **por sessão**; o excedente é descartado em silêncio (sem erro, sem fechar a
  conexão). Mensagens que não são `TYPING`, JSON inválido, `scope` desconhecido ou grande demais são ignorados.

**Servidor → clientes** (envelope igual ao dos outros eventos):

```json
{
  "type": "TYPING",
  "boardId": "retro-123",
  "payload": {
    "userId": "uid-ana",
    "anonymous": false,
    "scope": "card",
    "columnKey": "good",
    "expiresInMs": 5000,
    "expiresAt": 1760700005000
  },
  "timestamp": 1760700000000
}
```

- Quem recebe: sessões **da mesma sala com `typing=1`**, exceto as do próprio remetente.
- `expiresInMs = 5000` (e `expiresAt` = `timestamp + 5000`): o cliente mostra "escrevendo…" por até 5 s e some sozinho se
  não chegar outro. Não existe evento de "parou".
- **Anonimato dos cards**: enquanto `isAuthorsRevealed = false`, o evento de `scope: "card"` sai com
  `userId: null` e `anonymous: true` ("alguém está escrevendo"), para o "escrevendo…" não revelar a autoria dos cards que
  depois aparecem. Com os autores revelados vem o `userId`.
- **Chat**: respeita o canal. `geral` vai à sala; `role-X` só ao criador e aos participantes da função; `dm_a_b` só às duas
  pessoas. Quem não tem acesso ao canal não gera nem recebe o evento.
- Sem `typing=1` a sessão não recebe nada novo (tipo desconhecido = "recarrega tudo" no frontend antigo).
- Instância única, em memória: com várias instâncias o evento só alcança quem está na mesma instância.

### 2.5 Números da sprint — `GET /api/work-items/{squadId}/sprint/{sprintId}/stats`

Acesso e rota inalterados. Os quatro campos de hoje continuam (`velocityReal`, `previsto`, `entregue`, `carryOvers`).
**Quatro campos novos**, todos calculados dos mesmos itens (`squad_issue_snapshots` da sprint), nunca estimados:

| Campo | O que é | Fonte |
|---|---|---|
| `totalItens` | quantos itens a sprint tem | contagem das linhas |
| `concluidosJira` | itens com categoria de status "done" no Jira | `statusCategory = done` |
| `bugs` | itens que são bug | `isBug` |
| `carryOverPontos` | pontos dos itens que "ficaram para a próxima" | soma de `pointsEstimated` dos `carried_over` |

Observações honestas: `entregue`, `previsto` e `carryOvers` vêm do **estágio da cerimônia** (Poker/Planner/Showcase:
`delivered`, `committed`, `carried_over`), não do status do Jira; sem cerimônia registrada eles são 0 mesmo com a sprint
andando. Os campos `totalItens`/`concluidosJira`/`bugs` vêm do Jira sincronizado e funcionam sem a cerimônia. O servidor
**não** guarda velocity histórica, horas por sprint nem escape de bugs nesta rota; se a tela previa algum outro
número, ele não existe nesta API.

---

## Retrocompatibilidade (resumo)

| Cliente | O que acontece |
|---|---|
| Frontend atual (sem opt-in) | Recebe os mesmos eventos de sempre. `BOARD_UPDATED` do Radar ganha campos e passa a vir um pouco mais (primeira/última resposta de cada pessoa); o frontend só substitui o quadro. Nenhum evento novo, nenhuma exigência nova |
| `POST /api/health-checks`, `POST /api/retros`, `PATCH` com corpo antigo | Iguais. Campos novos no corpo (inclusive devolvidos de um GET) são ignorados |
| Retro e Radar existentes | `finishedAt` nulo, `OPEN`/derivado de `status`, `presentationStage` nulo, `checkinNote` nulo |
| Frontend novo contra backend antigo | Campos ausentes, rotas novas `404`/`405`, sem `TYPING`/`PRESENTATION_STAGE`: a tela deve esconder os recursos novos quando o campo não existir |

## Reverter

Implantar a versão anterior do backend. A V79 só adiciona colunas nulas: não precisa ser desfeita (o código antigo não as
conhece) e o Hibernate em `validate` aceita colunas extras no banco. Se quiser desfazer o banco mesmo assim, só depois de
voltar o código: `ALTER TABLE health_check_boards DROP COLUMN finished_at, DROP COLUMN presentation_stage;`
`ALTER TABLE retro_boards DROP COLUMN finished_at, DROP COLUMN lifecycle;`
`ALTER TABLE retro_participants DROP COLUMN checkin_note;`

## Notas de operação (achadas no QA com duas pessoas)

- **Assumir o controle da Retro:** responde 409 enquanto a facilitação está conectada e também por 45 s depois que ela sai da sala (`ABSENCE_GRACE_MS` em `RetroWebSocketHandler`). Só passado esse prazo outra pessoa assume.
- **`finishedAt` da Retro e dos demais quadros:** é gravado com precisão de microssegundos, igual ao que o banco devolve, para a mesma string valer na primeira chamada e nas chamadas idempotentes seguintes.
- **Poker com votos às cegas (`blindVotes`):** o `VOTE_SAVED` em tempo real chega mascarado para quem não votou, inclusive para a facilitação; a tela da facilitação lê o valor por `GET /votes`.
