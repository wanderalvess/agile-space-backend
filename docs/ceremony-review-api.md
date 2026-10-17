# Review (Showcase): trava PO/SME, participantes, apresentador, edição por card e JQL da squad — contrato (migração V81)

O frontend da Review já está na homologação e deixou fora da tela o que dependia do servidor. Este contrato acrescenta:
a **trava de decisão do PO/SME** no servidor, os **participantes do resumo**, o **seguir o apresentador**, a **edição por
card** (sem sobrescrever o trabalho dos outros) e as **consultas JQL compartilhadas com a squad**.

Tudo é **aditivo e retrocompatível**: o frontend que está na homologação continua funcionando sem mudar nada (a única
mudança visível a ele é que um não-PO/SME que tentava gravar uma decisão agora recebe 403, e o cliente atual já esconde esses
controles). Campos novos são opcionais, eventos novos só chegam a quem abriu o WebSocket com `?presence=1`
(ver `docs/presence-api.md`: o cliente antigo trata evento desconhecido como "recarrega a sala"). Todas as rotas exigem o JWT.
Datas ISO-8601, JSON camelCase. Erros no formato padrão do projeto `{ "error": "...", "message": "..." }`, com `code` (e dados
extras) nos erros listados abaixo.

> Observação sobre os caminhos: a Review mora em `/api/showcase-sessions` (não em `/api/showcase`). Todos os caminhos novos
> seguem esse prefixo. O WebSocket é `/ws/showcase/{sessionId}`.

## Resumo

| O quê | Como |
|---|---|
| Trava PO/SME | `403` com `code: "DECISION_RESTRICTED"` em `POST /api/showcase-sessions` (sessão inteira) e `PATCH .../cards/{cardId}` quando `decision`/`feedback` mudam e a pessoa não pode decidir |
| Participantes do resumo | campo `participants` na sessão; preenchido quando a Review vira `finished` |
| Seguir o apresentador | `PUT/DELETE/GET /api/showcase-sessions/{id}/presenter`, campo `presenter` na sessão, evento `PRESENTER_UPDATED` (opt-in) |
| Edição por card | `PATCH /api/showcase-sessions/{id}/cards/{cardId}` com `version`; `409` devolve o card atual; evento `CARD_UPDATED` (opt-in) |
| Mesclagem do `POST` antigo | cada card carrega `version`; o servidor mantém o card do servidor se o cliente tinha uma versão velha; lista `staleCardIds` |
| JQL da squad | `GET/POST /api/showcase-sessions/jql`, `DELETE /api/showcase-sessions/jql/{id}` |

## Tipos

```ts
// ShowcaseSession (resposta de GET /{id}, GET ?squadId=, POST e payload do SESSION_UPDATED) -> campos NOVOS no fim.
// Todos SÓ LEITURA: o que o cliente mandar neles é ignorado.
interface ShowcaseSession {
  /* ...campos de hoje, inalterados... */
  participants: { userId: string; displayName: string }[];   // NOVO. [] se a Review não foi finalizada ou é anterior à V81
  presenter?: Presenter | null;                               // NOVO. Quem está apresentando agora; ausente/null = ninguém
  staleCardIds?: string[];                                    // NOVO. SÓ na resposta do POST (ver "Mesclagem")
}

// ShowcaseTask (card) -> campos NOVOS
interface ShowcaseTask {
  /* ...campos de hoje, inalterados... */
  version: number;           // NOVO. Começa em 1 e sobe a cada mudança de conteúdo do card. Aceito de volta no POST e obrigatório no PATCH
  updatedAt?: string | null; // NOVO. Só leitura. Última mudança do card; null em card anterior à V81
}

interface Presenter {
  userId: string;
  displayName: string;
  cardId: string | null;     // card que o apresentador está mostrando; null = só abriu o Teatro
  step: string | null;       // etapa livre do cliente, ex.: "cover" | "card" | "summary" (até 40 chars: letras, números, "_" e "-")
  presentingAt: string;      // instante da última publicação
}

interface JqlQuery {
  id: string; squadId: string; name: string; jql: string;
  visibility: 'SQUAD' | 'PRIVATE';
  createdBy: string; createdByName: string | null; createdAt: string;
  mine: boolean;             // fui eu que criei
  canDelete: boolean;        // posso apagar (criador, liderança da squad em consulta SQUAD, ou ADMIN)
}
```

## 1. Trava PO/SME

**Quem pode registrar o veredito (`decision`) e o feedback (`feedback`) de um card** (mesma ideia do `canDecide` do cliente,
agora no servidor): ADMIN; liderança da squad da Review **por vínculo** (`MembershipService`: papel com `leadership`, como
Product Owner, Agile Master, Tech Lead, People Lead e a liderança transversal); ou quem tem **vínculo na squad com o papel
Product Owner ou SME**. A squad é a da Review (`squadName`, que guarda o id da squad). Review **sem squad** (`squadName`
vazio): só o ADMIN e o criador da Review.

Os campos de **conteúdo de preparação** (problema, solução, evidências, métricas, `preparationStatus`, links, etc.) continuam
liberados a qualquer pessoa autenticada, como hoje.

O que conta como "alterar a decisão":

- `decision` diferente do gravado (`null`, `""` e `"open"` são a mesma coisa: "sem decisão");
- `feedback` diferente do gravado (`null` e `""` são a mesma coisa);
- card **novo** já nascendo com decisão (`decision` ≠ open) ou com feedback.

Os campos de autoria (`decidedBy`, `decidedByName`, `decidedAt`, `approvedAt`) continuam sendo do servidor.

### Comportamento escolhido: rejeitar tudo com 403

- **Quem não pode decidir e envia a sessão inteira SEM mudar `decision`/`feedback` de nenhum card**: aceito (é o que o cliente
  atual faz toda vez que alguém edita um card de preparação).
- **Quem não pode decidir e muda `decision` ou `feedback` de qualquer card**: a requisição **inteira** é rejeitada com `403`
  (nada é gravado, nem o resto). Escolhido em vez de "aplicar o resto e ignorar a decisão" porque a gravação parcial
  silenciosa faria o cliente achar que a decisão valeu. O cliente atual esconde os controles, então só um cliente adulterado
  ou desatualizado cai nisso.

```json
{
  "error": "403 FORBIDDEN",
  "message": "Só o PO, o SME, a liderança da squad ou um administrador pode registrar a decisão ou o feedback de um card.",
  "code": "DECISION_RESTRICTED",
  "cardIds": ["card-1", "card-7"]
}
```

`cardIds` lista os cards cuja decisão/feedback a pessoa tentou mudar.

Interruptor de emergência: `app.showcase.decision-lock=off` (variável `APP_SHOWCASE_DECISION_LOCK=off`) desliga a trava sem
redeploy de código (volta ao comportamento anterior). Padrão: `enforce`.

## 2. Participantes do resumo

Quando a Review **passa a `finished`** (POST com `status: "finished"` vindo de outro status, ou criada já finalizada), o
servidor grava quem esteve presente e devolve em `participants`:

- quem estava **online na sala** (`PresenceRegistry`, o mesmo de `PRESENCE_UPDATED`) naquele instante, na ordem de entrada;
- mais **quem estava apresentando** (se havia) e **quem finalizou**;
- sem ninguém online no registro (nenhuma conexão, servidor reiniciado), a lista fica **vazia** (o frontend omite a linha).

```json
"participants": [
  { "userId": "uid-ana", "displayName": "Ana Souza" },
  { "userId": "uid-caio", "displayName": "Caio Lima" }
]
```

O nome é o do cadastro no momento da finalização (fica gravado: o resumo de uma Review antiga não muda se a pessoa mudar de
nome). Reabrir a Review (`finished` → outro status) **zera** a lista; finalizar de novo recalcula. Salvar de novo uma Review
que já está `finished` não recalcula. Reviews anteriores à V81 vêm com `participants: []`.

## 3. Seguir o apresentador

Estado **efêmero, em memória** (um por Review, por processo; reinício do servidor = ninguém apresentando). Não cria histórico
nem tabela.

### `PUT /api/showcase-sessions/{id}/presenter`

```json
{ "cardId": "card-3", "step": "card" }
```

Resposta `200` com o `Presenter`. Quem publica passa a ser o apresentador (o último a publicar vale). `cardId` e `step` são
opcionais; com `cardId` ele precisa ser um card da Review.

| Status | Quando |
|---|---|
| `200` | publicado |
| `400` | `step` inválido (mais de 40 caracteres ou fora de letras/números/`_`/`-`) |
| `401` | sem token |
| `403` | a pessoa não é ADMIN, criador da Review, liderança da squad (vínculo) nem o apresentador atual |
| `404` | Review inexistente ou `cardId` que não é desta Review |
| `409` | Review já finalizada (`finished`) |

### `DELETE /api/showcase-sessions/{id}/presenter`

Encerra a apresentação (`204`). Mesma permissão do PUT. Sem apresentador ativo também responde `204`.

### `GET /api/showcase-sessions/{id}/presenter`

Carga inicial sem esperar o socket. `200` com o `Presenter`, ou `204` se ninguém está apresentando. O mesmo dado vai no campo
`presenter` do `GET /api/showcase-sessions/{id}`. Cabeçalho `Cache-Control: no-store`.

### Validade e limpeza

- **Finalizar a Review** limpa o apresentador (e avisa a sala).
- Uma publicação com mais de 4 horas sem renovação some sozinha (`app.showcase.presenter-ttl-minutes`, padrão `240`): o
  apresentador que fechou a aba sem encerrar não deixa a sala "seguindo" para sempre. Enquanto apresenta, o cliente pode
  republicar a cada troca de card (o `PUT` renova o instante).

### Evento `PRESENTER_UPDATED` (opt-in `?presence=1`)

Mesmo envelope dos outros eventos da Review (chave da sala `sessionId`). **Só** as conexões abertas com `?presence=1`
recebem; quem não pediu não recebe nada novo.

```json
{
  "type": "PRESENTER_UPDATED",
  "sessionId": "5b6f...",
  "payload": {
    "presenter": {
      "userId": "uid-ana", "displayName": "Ana Souza",
      "cardId": "card-3", "step": "card", "presentingAt": "2026-10-17T14:30:12"
    }
  },
  "timestamp": 1760700000000
}
```

`payload.presenter = null` quando a apresentação acaba (DELETE, finalização ou expiração). O evento é **estado completo**
(substitua o que tinha). O estado inicial de quem entra vem do `GET`/campo `presenter`; o socket não manda snapshot de
apresentador ao conectar.

## 4. Edição por card: `PATCH /api/showcase-sessions/{id}/cards/{cardId}`

Atualização **parcial**: envie só os campos que mudaram, mais `version` (a que o cliente leu do card). Os outros cards, os
anexos e a sessão não são tocados, então duas pessoas editando cards diferentes (ou o mesmo card em momentos diferentes, com
releitura) não se sobrescrevem.

```json
{
  "version": 3,
  "title": "Novo título",
  "evidence": { "solution": "Texto novo da solução" },
  "metrics": [ { "field": "Economia (R$)", "value": 1200 } ]
}
```

Regras:

- `version` é **obrigatório** (`400` se faltar). É a versão do card que o cliente leu.
- Campos aceitos (os de conteúdo do card): `key`, `title`, `description`, `acceptanceCriteria`, `type`, `status`, `priority`,
  `points`, `cardKind`, `chartType`, `chartTitle`, `chartDisplay`, `assignee`, `url`, `decision`, `preparationStatus`,
  `feedback`, `project`, `versionSuporte`, `versionMaster`, `versionRelease`, `versionDevelop`, `evidence`, `metrics`.
- `evidence` é **mesclado campo a campo** (enviar só `{ "solution": "..." }` não apaga o `problem`); `metrics` é **substituído
  por inteiro** quando enviado (`"metrics": null` limpa a lista); os demais são trocados. Campo ausente = não mexe; campo
  `null` = limpa (exceto `evidence`, que precisa ser um objeto: `null` é ignorado).
- Ignorados em silêncio (como no POST): `id`, `sessionId`, `order`, `attachments`, `updatedAt`, `decidedBy`, `decidedByName`,
  `decidedAt`, `approvedAt` e qualquer campo desconhecido. A autoria da decisão é sempre do servidor.
- A trava PO/SME (seção 1) vale para `decision` e `feedback`: `403` com `code: "DECISION_RESTRICTED"`.
- Links (`url`, `evidence.screenshot`, `video`, `techDocUrl`, `tdnUrl`) passam pela mesma sanitização do POST.
- Um PATCH que não muda nada **não** sobe a `version`.
- Alterar o card renova o `updatedAt` da **sessão** (o cliente usa isso para saber se uma Review "Ao vivo" está abandonada).

Resposta `200`: o **card inteiro** já com a `version` nova e o `updatedAt`.

### Conflito: `409`

Se a `version` enviada for menor que a do servidor (o card mudou desde que o cliente leu), nada é gravado e a resposta traz o
**card atual** para o cliente reaplicar a própria mudança por cima e tentar de novo:

```json
{
  "error": "409 CONFLICT",
  "message": "O card foi alterado por outra pessoa. Reaplique a sua mudança sobre a versão atual.",
  "code": "CARD_VERSION_CONFLICT",
  "card": { "id": "card-3", "version": 5, "title": "...", "...": "card completo" }
}
```

| Status | Quando |
|---|---|
| `200` | gravado (card completo na resposta) |
| `400` | `version` ausente ou não numérica; corpo que não é um objeto |
| `401` | sem token |
| `403` | tentou mudar `decision`/`feedback` sem poder (`DECISION_RESTRICTED`) |
| `404` | Review ou card inexistente (o card tem que ser desta Review) |
| `409` | `version` velha (`CARD_VERSION_CONFLICT`, com o `card` atual) |

Um conflito é por card, não por campo: se o card mudou em outro campo, o cliente recebe o card atual, reaplica o seu campo e
repete com a `version` nova (um laço curto, sem pedir nada à pessoa quando os campos não colidem).

### Eventos depois de um PATCH

- Conexões **com** `?presence=1`: `CARD_UPDATED`, leve (só o card):

  ```json
  {
    "type": "CARD_UPDATED",
    "sessionId": "5b6f...",
    "payload": { "cardId": "card-3", "version": 4, "card": { "...": "card completo" }, "sessionUpdatedAt": "2026-10-17T14:31:00" },
    "timestamp": 1760700000000
  }
  ```

  O cliente troca só aquele card (ignore se `payload.version` for menor ou igual ao que já tem).
- Conexões **sem** `?presence=1` (frontend antigo): `SESSION_UPDATED` com a sessão inteira, exatamente como depois de um POST.

Quem abriu o socket com `presence=1` deve tratar `CARD_UPDATED`, `PRESENTER_UPDATED`, `PRESENCE_UPDATED` **e** continuar
tratando `SESSION_UPDATED` (o POST da sessão inteira, por exemplo vindo de um cliente antigo, continua mandando esse evento a
todos).

## 5. O `POST /api/showcase-sessions` antigo (sessão inteira): retrocompatível, com mesclagem por card

O corpo e a resposta de hoje não mudam. O que muda por baixo, card a card, quando o cliente manda de volta o `version` que leu
(o cliente atual devolve o objeto do card inteiro, então já manda; um cliente que não manda `version` mantém o comportamento
antigo, "a última gravação vence"):

| Situação do card | O que o servidor faz |
|---|---|
| conteúdo igual ao gravado | mantém; `version` não sobe |
| `version` do cliente ≥ a do servidor e conteúdo diferente | aplica; `version` sobe |
| `version` do cliente **menor** que a do servidor e conteúdo diferente | **mantém o card do servidor** (a mudança de outra pessoa não é sobrescrita por uma cópia velha); o id vai em `staleCardIds` na resposta |
| card novo (id inexistente na Review) | cria com `version` 1 |
| card que não veio no corpo | continua sendo removido (como hoje: o POST é autoritativo sobre a lista) |

`staleCardIds` só existe na resposta do POST (e no `SESSION_UPDATED` desse mesmo salvamento, onde pode ser ignorado), e só quando
algo foi mantido. O cliente que usa o resultado do POST como verdade
(o atual faz) já mostra o card do servidor; um cliente novo pode avisar "o card X foi alterado por outra pessoa".

Não mesclam: ordem dos cards, lista de integrantes e campos da sessão (continuam "última gravação vence"). A remoção de card
pelo POST continua definitiva; quem precisa evitar apagar o card que outra pessoa acabou de criar deve usar o PATCH para
editar e só o POST para adicionar/remover.

## 6. JQL compartilhada com a squad

As consultas JQL do "Importar do Jira" da Review, hoje só no navegador. O servidor **só guarda o texto**; nunca executa JQL
(a consulta continua sendo feita pelo navegador, com a conta da pessoa).

### `GET /api/showcase-sessions/jql?squadId=DDWMISSI`

Quem lê a squad (a mesma regra de `GET /api/showcase-sessions?squadId=`: membro, liderança ou ADMIN; senão `403`) recebe:
as consultas `SQUAD` da squad **mais as suas próprias** `PRIVATE`. Consulta privada de outra pessoa nunca aparece, nem para a
liderança. Ordem: mais recentes primeiro. Até 200. `squadId` é obrigatório (`400`).

```json
[
  { "id": "9c1e...", "squadId": "DDWMISSI", "name": "Review da sprint atual", "jql": "sprint in openSprints() AND status = Concluído",
    "visibility": "SQUAD", "createdBy": "uid-ana", "createdByName": "Ana Souza", "createdAt": "2026-10-17T14:00:00",
    "mine": true, "canDelete": true }
]
```

### `POST /api/showcase-sessions/jql`

```json
{ "squadId": "DDWMISSI", "name": "Review da sprint atual", "jql": "sprint in openSprints() AND status = Concluído", "visibility": "SQUAD" }
```

- `name`: obrigatório, até 120 caracteres; `jql`: obrigatório, até 4000; `visibility`: `SQUAD` ou `PRIVATE` (**padrão
  `PRIVATE`** quando ausente: o mais restritivo).
- Precisa de acesso de leitura à squad (`403` senão).
- `409` se a mesma pessoa já tem uma consulta com esse nome na squad (sem diferenciar maiúsculas); `409` também ao passar de
  30 consultas da mesma pessoa na squad.
- Resposta `200` com o `JqlQuery`. O `id` e o criador são do servidor.

### `DELETE /api/showcase-sessions/jql/{id}`

`204`. Pode apagar: o **criador**; para consulta `SQUAD`, também a **liderança da squad** (vínculo) e o **ADMIN**. Consulta
`PRIVATE` de outra pessoa responde `404` (não revela que existe), exceto para o ADMIN. `404` se não existe. `403` para quem lê a
squad mas não pode apagar uma consulta `SQUAD`.

## Retrocompatibilidade

- Frontend atual da homologação: o `GET`, o `POST` e o `SESSION_UPDATED` continuam iguais; os campos novos (`participants`,
  `presenter`, `version`, `updatedAt`) são ignorados por quem não os conhece. O cliente antigo ao reenviar o card manda
  `version` de volta sem saber, e passa a ter a mesclagem de graça.
- Eventos novos (`PRESENTER_UPDATED`, `CARD_UPDATED`) só vão às conexões com `?presence=1`; quem não pediu continua recebendo só
  `SESSION_UPDATED`/`REFRESH_SESSION`/anexos como hoje.
- A única mudança de comportamento para o cliente antigo é o `403` de quem não pode decidir e tenta mudar `decision`/`feedback`.

## Migração V81 (aditiva)

Tudo com `IF NOT EXISTS` (roda 2x sem erro). Nenhuma coluna ou tabela existente é alterada ou removida.

- `showcase_tasks.card_version bigint NOT NULL DEFAULT 1` e `showcase_tasks.card_updated_at timestamp` (nula);
- `showcase_participants(id, session_id → showcase_sessions ON DELETE CASCADE, user_id, display_name, position)`;
- `showcase_jql_queries(id, squad_id, name, jql, visibility, created_by, created_at)` com índice por squad e unicidade
  `(squad_id, created_by, lower(name))`.

## Reverter

Implantar o jar anterior: as colunas/tabelas novas ficam sem uso (o Hibernate em `validate` não reclama de coluna a mais). O
estado do apresentador some (é só memória). Para desfazer a V81 por completo (opcional, só com dump em mãos):
`DROP TABLE showcase_jql_queries, showcase_participants; ALTER TABLE showcase_tasks DROP COLUMN card_version, DROP COLUMN card_updated_at;`.

## Limitações

- **Instância única** para o apresentador (memória do processo) e para a presença usada nos participantes, como em
  `docs/presence-api.md`.
- O PATCH detecta conflito **por card**, não por campo.
- Não há `POST`/`DELETE` por card: criar e remover cards continua sendo pelo `POST` da sessão inteira.
- O `PATCH` não altera a ordem dos cards, os integrantes nem as configurações da sessão (continuam no `POST`).
