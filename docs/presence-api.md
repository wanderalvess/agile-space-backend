# API de presença nas salas

Quem está **online** em cada sala (Poker, Retro, Brainstorming, Radar de clima/Health Check, Showcase). **Aditiva e opt-in**: não
muda nenhum evento existente e o frontend atual (sem o parâmetro `presence=1`) continua recebendo exatamente o que recebia.
Sem migração de banco: a presença vive em memória, por processo.

## Resumo

| O quê | Como |
|---|---|
| Entrar no tempo real de presença | abrir o WebSocket da sala com `?presence=1` (além do `token`) |
| Evento | `PRESENCE_UPDATED` (mesmo tipo para o snapshot inicial e para as mudanças) |
| Fallback / carga inicial sem socket | `GET /api/presence/{module}/{roomId}` |
| Identidade de quem está online | o `userId` do token JWT do handshake (nunca um valor enviado pelo cliente) |

`module` ∈ `poker` · `retro` · `brainstorming` · `health-check` · `showcase` (o mesmo segmento que vem depois de `/ws/`).

## Por que opt-in (`?presence=1`)

O frontend em produção/homologação trata **qualquer tipo de evento desconhecido como "recarrega tudo"** (`default:` do
`switch` no Retro e no Radar, `else` no Showcase, `default` no Poker). Se o servidor mandasse `PRESENCE_UPDATED` a todas as
conexões, cada entrada/saída de alguém faria todos os clientes antigos refazerem os GETs da sala (tempestade de recargas, e
em sala movimentada um laço de recargas). Por isso o evento só é enviado às sessões que **pediram**: o frontend novo passa
`presence=1` e trata o tipo; o antigo não passa e não recebe nada novo.

Valores aceitos: `presence=1` ou `presence=true` (sem diferenciar caixa). Qualquer outra coisa, ou ausência, = não recebe.

Observação: **todas** as conexões, com ou sem opt-in, contam como presença (uma pessoa com o frontend antigo aparece online
para os outros). O opt-in controla só quem **recebe** o evento.

## WebSocket

URL: `wss://<host>/ws/{module}/{roomId}?token=<jwt>&presence=1` (o handshake, o token e as regras de acesso de cada sala não mudam).

### Evento `PRESENCE_UPDATED`

Mesmo envelope dos outros eventos de cada sala; só a chave do identificador da sala varia, como já varia hoje:

| Módulo | Chave da sala no envelope |
|---|---|
| poker | `roomId` |
| retro, brainstorming, health-check | `boardId` |
| showcase | `sessionId` |

```json
{
  "type": "PRESENCE_UPDATED",
  "boardId": "retro-123",
  "payload": {
    "online": ["uid-ana", "uid-bia", "uid-caio"],
    "total": 3,
    "version": 1042
  },
  "timestamp": 1760700000000
}
```

- `online`: userIds (o mesmo id dos participantes: `participant.id` / `userId` dos eventos `PARTICIPANT_*`), sem repetição, na
  ordem em que cada pessoa entrou na sala. Nenhum nome, e-mail ou outro dado pessoal.
- `total`: `online.length`.
- `version`: inteiro que **só cresce** dentro de um processo do servidor; vale para ordenar eventos da mesma sala. O cliente
  deve ignorar um evento com `version` **menor** que a última que aplicou naquela conexão (dois eventos disparados quase juntos
  podem chegar fora de ordem). Ao reconectar, zere a última versão vista (um reinício do servidor reinicia o contador).
- O evento é **estado completo**, não diferença: substitua a lista inteira. É idempotente.

### Quando é enviado

1. **Snapshot inicial**: logo depois de a conexão abrir com `presence=1`, a própria sessão recebe um `PRESENCE_UPDATED` com o
   estado atual (incluindo ela mesma). Vale também para a segunda aba de quem já estava online.
2. **Mudança**: quando a lista de userIds online da sala muda, todas as sessões da sala **com `presence=1`** recebem o evento.

Regras de contagem:

- Um usuário com várias abas/conexões na mesma sala é **uma** presença: ele fica online enquanto tiver pelo menos uma conexão.
  Abrir a segunda aba ou fechar uma das duas **não** gera broadcast (a lista não mudou); só a primeira entrada e a última saída geram.
- Fechamento ou erro da conexão remove a sessão. Uma varredura periódica (30 s) remove sessões que ficaram abertas só no
  mapa (zumbis) e gera o evento correspondente.
- Uma queda de rede curta (reconexão em poucos segundos) aparece como saída e entrada: o cliente deve tolerar o "pisca" se
  quiser suavizar (ex.: esperar ~5 s antes de mostrar alguém como offline). O servidor não aplica tolerância.
- Sala vazia é removida da memória.

### Exemplo: cliente

```ts
const url = wsBase + '?token=' + encodeURIComponent(token) + '&presence=1';
let lastVersion = 0; // zere a cada nova conexão
onMessage = (m) => {
  if (m.type === 'PRESENCE_UPDATED') {
    if (m.payload.version < lastVersion) return;
    lastVersion = m.payload.version;
    setOnline(new Set(m.payload.online));
  }
};
```

## REST: `GET /api/presence/{module}/{roomId}`

Carga inicial sem esperar o socket, ou fallback quando o WebSocket não conecta. Autenticado (`Authorization: Bearer <jwt>`).

```json
{
  "module": "retro",
  "roomId": "retro-123",
  "online": ["uid-ana", "uid-bia"],
  "total": 2
}
```

Sala que existe mas está sem ninguém conectado: `200` com `"online": []` e `"total": 0`. Cabeçalho `Cache-Control: no-store`.

| Status | Quando |
|---|---|
| `200` | módulo válido, sala existe e a pessoa pode entrar nela |
| `401` | sem token, token inválido ou expirado |
| `403` | a sala existe mas a pessoa não tem acesso a ela (só Retro; ver abaixo) |
| `404` | `module` desconhecido, ou a sala/quadro/sessão não existe |

### Regra de acesso: a mesma de cada módulo

Reaproveita a regra que cada módulo já usa para abrir a sala (REST e handshake do WebSocket), para a presença não vazar
nada que a sala não vazaria:

| Módulo | Regra | Existência |
|---|---|---|
| `poker` | qualquer pessoa autenticada (o link da sala dá acesso, igual a `GET /api/poker/{id}`) | a sala existe |
| `brainstorming` | qualquer pessoa autenticada (igual ao handshake) | o board existe |
| `health-check` | qualquer pessoa autenticada (igual ao handshake) | o board existe |
| `showcase` | qualquer pessoa autenticada (igual a `GET /api/showcase-sessions/{id}`) | a sessão existe |
| `retro` | criador, participante ou ADMIN do quadro (igual ao handshake do WebSocket da Retro) | o board existe |

Para a Retro, quem não é participante recebe `403` (não `404`), como acontece hoje no handshake.

## Limitações e evolução

- **Instância única.** O registro é em memória, por processo (como as próprias listas de sessões WebSocket de hoje). Com mais
  de uma instância atrás do balanceador cada uma veria só as conexões dela e a lista ficaria incompleta. O caminho, sem
  implementação aqui: publicar entradas/saídas num Redis (hash `presence:{module}:{roomId}` com TTL por sessão renovado a cada
  varredura + canal pub/sub para o broadcast entre instâncias) e fazer o `PresenceRegistry` ler/escrever nele atrás da mesma interface.
- **Reinício do backend** derruba todas as conexões; a presença se refaz sozinha conforme os clientes reconectam (o frontend já
  reconecta com backoff), e `version` recomeça (o cliente zera a versão ao reconectar).
- **Conexões "meio mortas"** (cabo puxado, sem FIN) só somem quando o servidor detecta o fechamento; a varredura cobre
  sessões que o servidor já considera fechadas, mas não faz ping ativo.
- A presença é "tem conexão aberta com a sala", não "está com a aba em foco".

## Observabilidade

- Métrica Micrometer `presence.sessions{module}`: conexões WebSocket abertas por módulo (gauge). Só aparece num backend de
  métricas (OTLP/SigNoz) ou se `metrics` for exposto no actuator.
- Log em nível `DEBUG`: módulo, sala, total de usuários online e de sessões. Nunca userId, nome ou e-mail.

## Reverter

Sem migração. Reverter = implantar a versão anterior (o frontend que passa `presence=1` simplesmente deixa de receber o evento
e o REST responde `404`; o frontend deve esconder o indicador nesse caso).
