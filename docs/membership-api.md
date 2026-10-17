# API de vínculos (Pessoa x Squad x Papel)

Etapa 1 do plano de vínculo (`docs/vinculo-auditoria-e-plano.md`). **Aditiva**: não muda quem pode o quê. A autorização por
squad só muda na Etapa 2, atrás da chave própria `app.membership.authz` (padrão `legacy` = comportamento de sempre; ver a seção
"Autorização por squad (Etapa 2)" no fim deste documento). A fonte da verdade do vínculo é `project_member_roles` (pmr).

## `GET /api/me/memberships`

Qualquer usuário autenticado vê **só os próprios** vínculos. O id vem do token (nunca de parâmetro; `?userId=` é ignorado).
Sem token ou token inválido: `401`. Conta que deixou de existir: `401`.

Cabeçalhos da resposta: `Cache-Control: no-store` e `X-Membership-Contract: 1` (versão do contrato, igual a `contractVersion`).

### Contrato (versão `1`)

```jsonc
{
  "contractVersion": "1",          // sobe só quando um campo é removido ou muda de sentido; acrescentar campo não sobe
  "activeProjectId": "DDWMISSI",   // squad ativa validada contra os vínculos; null se não há vínculo nenhum
  "legacyOnly": false,             // true: sem nenhuma linha pmr, mas com rastro do modelo antigo (ver abaixo)
  "transversalLeader": false,      // há liderança transversal (TRIBE_LEAD, AGILE_COACH, PEOPLE_LEAD) expandindo acesso
  "memberships": [
    {
      "projectId": "DDWMISSI",
      "projectName": "Missão Digital",
      "segmentName": "Distribuição",
      "tribeName": "Varejo",
      "roleName": "Product Owner",   // papel PRIMÁRIO na squad
      "roleKey": "PRODUCT_OWNER",
      "leadership": true,
      "direct": true,               // = scope == "SQUAD" (nome compatível com accessibleProjects.directAssignment)
      "scope": "SQUAD",             // SQUAD | TRIBE | SEGMENT
      "source": "MANUAL",           // origem do papel primário (coluna project_member_roles.source); null = anterior à V75
      "roles": [                    // TODOS os papéis da pessoa nesta squad; o primeiro é o primário
        { "roleName": "Product Owner", "roleKey": "PRODUCT_OWNER", "leadership": true,  "source": "MANUAL", "primary": true  },
        { "roleName": "Developer",     "roleKey": "DEVELOPER",     "leadership": false, "source": "JIRA",   "primary": false }
      ]
    },
    {
      "projectId": "DDWPRIME",
      "projectName": "Prime",
      "segmentName": "Distribuição",
      "tribeName": "Varejo",
      "roleName": "Liderança Transversal da Tribo",
      "roleKey": "TRIBE_LEADER_ACCESS",
      "leadership": true,
      "direct": false,
      "scope": "TRIBE",
      "source": null,
      "roles": [
        { "roleName": "Liderança Transversal da Tribo", "roleKey": "TRIBE_LEADER_ACCESS", "leadership": true, "source": null, "primary": true }
      ]
    }
  ]
}
```

### Semântica

- **`scope`**
  - `SQUAD`: a pessoa tem linha própria em pmr nessa squad (por `user_id`, e-mail ou conta do Jira). `direct=true`.
  - `TRIBE`: sem linha própria; a squad é da mesma tribo (`projects.tribe_name`, sem diferenciar caixa) de uma squad onde a pessoa
    tem liderança transversal real (`is_leadership=true` com `TRIBE_LEAD`, `AGILE_COACH` ou `PEOPLE_LEAD`). `direct=false`.
  - `SEGMENT`: idem, mas só pelo `segment_name`. Se casa por tribo e por segmento, vale `TRIBE`.
  - Os itens `TRIBE`/`SEGMENT` são **derivados**: têm um único papel sintético (`TRIBE_LEADER_ACCESS`, "Liderança Transversal da
    Tribo", `source=null`), igual ao que `accessibleProjects` já devolve hoje.
  - Linha com `is_leadership=false` e nome de papel de liderança (convite/backfill) **não** expande.
- **`primary` / papel primário**: quando a pessoa tem mais de uma linha na mesma squad, o papel de topo (`roleName`, `roleKey`,
  `leadership`, `source`) é o da linha que vence por precedência: liderança antes de contribuidor; entre lideranças
  `TRIBE_LEAD`, `AGILE_COACH`, `PEOPLE_LEAD`, `PRODUCT_OWNER`, `AGILE_MASTER`, `SCRUM_MASTER`, depois os outros; empate por nome do papel
  e id da linha. `roles[]` traz todas na mesma ordem, com `primary=true` só na primeira. Isso corrige a perda de papéis do
  resolvedor antigo quando alguém é, por exemplo, PO e Developer na mesma squad.
- **Ordem de `memberships`** (determinística, não depende do banco): vínculos próprios primeiro (liderança antes de contribuidor,
  maior alcance, nome da squad, id), depois os derivados (`TRIBE` antes de `SEGMENT`, nome, id). A primeira é o fallback de
  `activeProjectId`.
- **`activeProjectId`**: `users.default_project_id` é só uma **preferência**. Vale se está nos vínculos (comparação sem caixa; devolve
  o id canônico). Se não está, é a primeira squad por precedência. Sem vínculo nenhum, `null`. Nada é gravado na leitura.
- **`legacyOnly`**: `true` só quando `memberships` está vazio **e** a conta tem rastro do modelo antigo: `users.squad_id` ou
  `default_project_id` preenchido (fora de "Sem Time"/"Sem Squad"), ou presença no roster (`squad_members` reivindicado pela conta ou
  casando por e-mail/conta do Jira). Conta nova, sem rastro nenhum, é `false` (vai para o onboarding). Hoje essas pessoas só
  entram nas telas pelo acesso do roster; quando a leitura passar só para os vínculos (Etapa 2), é este o grupo que perde acesso.
- **`source`**: `JIRA`, `MANUAL`, `INVITE`, `CLAIM`, `APPROVED`, `BACKFILL`, `MIGRATION`, ou `null` (linha anterior à V75; a origem
  não é presumida).

## Relação com a sessão (`AuthResponse`)

`/api/auth/login|me|refresh|register|switch-project|change-password` continuam devolvendo o mesmo `AuthResponse`. Campos
`accessibleProjects`, `activeProject*`, `transversalLeader` e o `defaultProjectId` do JWT vêm do cálculo escolhido por
`app.membership.mode`. Único acréscimo: `legacyOnly` (opcional; só aparece, com `true`, no modo `new`). O JWT **não** leva lista
de squads.

Fora do `new`, `activeProjectId` de `/api/me/memberships` (validado) pode diferir do `activeProjectId` da sessão (o do legado,
`users.default_project_id` sem validar). É exatamente a divergência `ACTIVE_PROJECT_DIFFERENT` descrita abaixo.

## Modos (`app.membership.mode`, env `APP_MEMBERSHIP_MODE`)

| Modo | Sessão (`accessibleProjects`, `activeProject*`) | Modelo novo |
|---|---|---|
| `legacy` | como antes (resolvedor antigo) | não é executado na sessão |
| `shadow` (**padrão**) | como antes | roda em paralelo; divergências vão para log e métrica |
| `new` | vem do `MembershipService` (squad ativa validada) | é a resposta |

Valor desconhecido (erro de digitação) cai em `legacy`, nunca no modelo novo. Reverter: `APP_MEMBERSHIP_MODE=legacy` e reiniciar.
`switchActiveProject` continua gravando `default_project_id` **e** `squad_id` (dual-write) nos três modos e só aceita squad que esteja
nos vínculos (`403` caso contrário). No `new`, grava o id canônico do vínculo (o legado gravava o texto como veio, ex. `ddwmissi`).

### Modo sombra: log e métrica

A cada resposta de sessão em `shadow`, o legado e o novo são comparados e **cada divergência** vira uma linha:

```
membership_divergence user=<id da conta> type=<TIPO> squad=<chave da squad> detail=<rótulo>
```

Nível `WARN` para divergência de acesso (`ONLY_LEGACY`, `ONLY_NEW`, `ROLE_DIFFERENT`, `TRANSVERSAL_DIFFERENT`) e `INFO` para
`ACTIVE_PROJECT_DIFFERENT`. Nunca há e-mail nem nome. Também soma o contador Micrometer `membership.divergence` (tag `type`). O
actuator só expõe `health` por padrão (`management.endpoints.web.exposure.include`), então o contador só aparece num backend de
métricas (OTLP/SigNoz) ou se `metrics` for exposto. Erro no cálculo sombra gera `membership_shadow_error ...` e **nunca** afeta a sessão.

| `type` | Significa | `detail` |
|---|---|---|
| `ONLY_LEGACY` | squad que o legado devolve e o novo não | `direto` / `transversal` |
| `ONLY_NEW` | squad que o novo devolve e o legado não | `direto` / `transversal` |
| `ROLE_DIFFERENT` | mesma squad, papel/liderança/forma de acesso diferentes | `roleKey`, `leadership`, `direct` (combinados com `+`) |
| `TRANSVERSAL_DIFFERENT` | a liderança transversal não bate | `so_no_novo` / `so_no_legado` |
| `ACTIVE_PROJECT_DIFFERENT` | squad ativa da sessão diferente | `preferencia_fora_dos_vinculos`, `sem_preferencia`, `sem_vinculos`, `outro` |

## `GET /api/admin/membership/shadow-diff` (ADMIN)

Recalcula, para **todos os usuários ativos**, legado x novo, direto do banco (não usa nem popula o cache). Somente leitura
(transação `readOnly`; o Postgres recusaria escrita). `401` sem token, `403` para quem não é ADMIN. A chamada é registrada pelo
`SecurityAuditInterceptor` como qualquer rota `/api/admin/**` (é o único registro gravado). Custo: algumas consultas por usuário;
em bases grandes, rode fora do pico.

```jsonc
{
  "generatedAt": "2026-10-17T12:00:00Z",
  "mode": "shadow",                  // modo em vigor
  "sampleLimit": 20,
  "usersChecked": 150,
  "usersWithDivergence": 12,
  "usersFailed": 0,                  // usuários cujo cálculo lançou exceção (também vão para o log)
  "totals": {                        // sempre todas as chaves; conta divergências (um usuário pode ter várias)
    "ONLY_LEGACY": 0, "ONLY_NEW": 0, "ROLE_DIFFERENT": 0, "TRANSVERSAL_DIFFERENT": 0, "ACTIVE_PROJECT_DIFFERENT": 12
  },
  "sample": [                        // até 20, ordenadas por id de usuário; só ids e rótulos
    { "userId": "uid-123", "squad": "DDWMISSI", "type": "ACTIVE_PROJECT_DIFFERENT", "detail": "preferencia_fora_dos_vinculos" }
  ],
  "truncated": true                  // há mais divergências do que a amostra
}
```

### Como interpretar (critério para virar a chave)

Pronto para `new` quando `ONLY_LEGACY`, `ONLY_NEW`, `ROLE_DIFFERENT` e `TRANSVERSAL_DIFFERENT` estão em **0** (ou cada caso
restante tem decisão documentada). Hoje o código dos dois cálculos compartilha as linhas e a precedência, então o esperado é 0 nesses
quatro; qualquer valor acima de 0 indica dado novo (pmr gravado entre as duas leituras) ou divergência real a investigar pelo `userId`.

`ACTIVE_PROJECT_DIFFERENT` **não é bug**: é a correção intencional da squad ativa. `preferencia_fora_dos_vinculos` são as contas
cuja preferência aponta para uma squad onde não têm linha em pmr (típico de quem entrou só pelo roster); no `new` a sessão passa a
mostrar a primeira squad dos vínculos, ou `null` se não há nenhum (e `legacyOnly=true` quando há rastro antigo). Cruze com o
relatório `USERS_WITH_SQUAD_WITHOUT_LINK` (`docs/membership-report.md`): quem aparece lá e está ativo aparece aqui. Decida por grupo
(criar o vínculo, ou aceitar que a pessoa passe pelo onboarding) antes de virar a chave.

## Cache de vínculos

Por usuário, em memória, TTL `app.membership.cache-ttl-seconds` (padrão 60; `0` desliga; env
`APP_MEMBERSHIP_CACHE_TTL_SECONDS`), até 5.000 entradas. Guarda só o que vem de pmr e `projects`; a preferência
(`users.default_project_id`) é lida do usuário a cada chamada, então trocar de squad não depende de evicção. A entrada leva a
impressão (id, e-mail, conta do Jira): mudou, é ignorada.

**Evicção**: um listener JPA (`MembershipCacheEvictionListener`) em `ProjectMemberRole` e `ProjectConfig` limpa o cache em
**qualquer** insert/update/delete dessas entidades, por qualquer caminho: importação do Jira (Profields), `createManualProject`,
`joinProject`, claim ("sou eu"), aprovação de pedido de vínculo, aceite de convite, `SquadTeamService` (adicionar, trocar papel,
remover), `AuthService.register` (liga `user_id` às linhas) e edição de projeto. Limpa na hora e de novo no fim da transação
(commit ou rollback); enquanto a transação tem gravação pendente ela não lê nem popula o cache. `switchActiveProject` também chama a
evicção do usuário. Fora do JPA, a evicção **não** é automática: SQL direto, migrações Flyway, o `deleteByProjectId` JPQL (hoje sem
chamador) e edição manual no banco só aparecem após o TTL (ou reinício). Com mais de uma instância da API a evicção vale só no
processo que gravou; as demais convergem pelo TTL.

---

# Autorização por squad (Etapa 2)

**ALTO RISCO.** Quem hoje acessa uma squad só pelo roster (`squad_members`, ex.: responsável de issue), por um `users.squad_id`
solto ou pelo cargo (`jobTitle`) perde o acesso quando a regra nova entra. Por isso a mudança nasce atrás de uma chave
**separada** de `app.membership.mode` (que só controla a sessão), com modo sombra e uma lista de exceções para tratar ANTES de
virar. O padrão não muda nada.

## Chave `app.membership.authz` (env `APP_MEMBERSHIP_AUTHZ`, também no `docker-compose.yml`)

| Valor | Decisão de acesso | Regra nova |
|---|---|---|
| `legacy` (**padrão**) | exatamente a de antes | nem é calculada (custo zero) |
| `shadow` | a do legado | calculada em paralelo; cada divergência vira log e métrica (abaixo) |
| `new` | a nova | é a decisão |

Valor vazio ou desconhecido (`nwe`, `true`...) cai em **`legacy`**, nunca em `new` (um aviso sai no log de inicialização). Reverter:
`APP_MEMBERSHIP_AUTHZ=legacy` e reiniciar. **Sem migração de banco**, sem dump necessário; `users.squad_id`/`default_project_id`
continuam sendo gravados pelos sincronizadores (dual-write) em todos os modos.

## A regra nova (modo `new`)

- **ADMIN** (token ou cadastro) e **LEAD** (mantido até a Etapa 4): acesso total, como hoje.
- **Vínculo** em `project_member_roles` (`MembershipService`, com cache), inclusive a expansão por tribo/segmento da liderança
  transversal (`TRIBE_LEAD`, `AGILE_COACH`, `PEOPLE_LEAD` com `is_leadership=true`).
- **Deixam de dar acesso**: `users.squad_id`/`default_project_id`, o roster `squad_members` e o cargo (`jobTitle`) como autoridade.
- **Gestão** (`canManageSquad`): ADMIN/LEAD, ou vínculo com `is_leadership` na squad (inclusive transversal).
- **Liderança estrita** (`isSquadLeadership`): ADMIN, ou vínculo com `is_leadership` (o LEAD sem vínculo de liderança continua não
  contando, igual ao legado).
- **Squad sem NENHUMA liderança cadastrada** (bootstrap de ambiente novo): só quem **tem vínculo** na squad gerencia (antes: qualquer
  membro do roster/`users.squad_id`). Cada squad nessa situação gera, uma vez por processo, o aviso
  `authz_squad_without_leadership squad=<chave>`; ela também aparece no relatório (`SQUADS_WITHOUT_LEADERSHIP`). A saída é cadastrar
  a liderança (importação do Jira, convite de admin).

## Pontos de autorização migrados

| Ponto | Regra legada | Regra nova (`new`) | `check` no log |
|---|---|---|---|
| `SquadAccessService.matchesSquad` (leitura de qualquer tela por squad, WebSocket) | ADMIN/LEAD, `users.squad_id`/`default_project_id`, resolvedor pmr, roster | ADMIN/LEAD, vínculo (com tribo/segmento) | `matchesSquad` |
| `SquadAccessService.canManageSquad` | `matchesSquad` + (cargo ou liderança pmr ou squad sem liderança) | vínculo + (liderança ou squad sem liderança) | `canManageSquad` |
| `SquadAccessService.isSquadLeadership` | `matchesSquad` + (cargo ou liderança pmr) | vínculo com liderança | `isSquadLeadership` |
| `InviteController.requireInviteAccess` (criar/listar/revogar convite) | ADMIN/LEAD; `users.squad_id` + cargo; liderança em linha pmr própria | ADMIN/LEAD; liderança em linha **própria** (escopo SQUAD; a expansão por tribo não convida) | `invite` |
| `InviteService.isSquadMemberOrAdmin` (quem vê e-mail/autor de convite endereçado) | ADMIN/LEAD; `users.squad_id`; qualquer linha pmr por conta/e-mail | ADMIN/LEAD; vínculo | `inviteView` |
| `SquadAudienceService.contextFor` (squads, papéis e liderança da home/avisos) | `users.squad_id`, resolvedor, roster; cargo conta como liderança; só o papel primário por squad | só vínculos; todos os papéis da pessoa; sem cargo | `audience`, `audienceLeadership`, `audienceRoles` |
| `SquadAudienceService.memberUserIds` (destinatários de notificação/aviso por squad) | `users.squad_id`, linhas pmr, roster | só linhas pmr (sem `findActiveBySquadIds`) | `audienceMembers` |
| `JoltProjectController` / `JoltProjectService` (compartilhar/ler projeto Jolt com a squad) | UMA squad: `users.squad_id` | N squads: as dos vínculos (nova consulta por lista) | `jolt` |
| `KnowledgeService.squadOfUser` (squad gravada no documento novo; define quem edita) | `users.squad_id` | squad ativa validada contra os vínculos | `knowledge` |
| `PromptHubApiV1Controller` / `McpPromptHubTools` (squad de autoria, fallback da chave) | `users.squad_id` | squad ativa validada | `prompthub` |
| `ApiKeyController.create` / `ApiKeyLifecycle.renew` (chave SQUAD_READ travada na squad) | `users.squad_id` | squad ativa validada; **sem vínculo, a chave perde `SQUAD_READ`** em vez de ficar sem restrição | `apikey` |

**Sem mudança necessária**: `SquadAudienceService.userIdsWithRoles` já lê só `UserRole` + `project_member_roles`;
`UserService.requireProjectAccess` já usa o resolvedor de vínculos. **A base de conhecimento continua compartilhada de propósito**:
só mudou de onde vem a squad gravada no documento (que decide quem edita), nunca quem lê.

**Bootstrap de squad nova em ambiente limpo** (`SquadBootstrapService`, chamado por `SquadController` quando a pessoa não passa
na leitura/escrita/gestão): é o primeiro uso de uma squad que ainda não existe (sem linha em `squads`, sem roster) por quem não tem
`users.squad_id`. Chave própria **`app.membership.bootstrap`** (env `APP_MEMBERSHIP_BOOTSTRAP`, também no `docker-compose.yml`),
**padrão `off`**; só `on` liga (vazio ou valor desconhecido = `off`, com aviso no log).

| `authz` | `bootstrap=off` (padrão) | `bootstrap=on` |
|---|---|---|
| `legacy` / `shadow` | como sempre: grava `users.squad_id` (e `default_project_id`, se vazio); não cria vínculo | idem (a chave é ignorada) |
| `new` | **não concede nada**: squad vazia é 403 para quem não é ADMIN; nada é gravado | cria o vínculo (abaixo) |

**Por que `off` por padrão:** com `on`, qualquer conta sem vínculo pode inventar uma chave de squad que não existe e virar Agile
Master dela; como `canManageTeams` vale para quem é AM/PL em qualquer pmr, essa conta passa a criar e importar equipes no sistema
inteiro. É uma auto-promoção, então só se liga conscientemente (por exemplo, numa instalação de demonstração).

Com `on` em `authz=new`:

- cria o projeto (`projects`, nome = chave) se faltar e a linha de `project_member_roles` da pessoa: `source=MANUAL`,
  `is_leadership=true`, papel do fundador de `createManualProject` (Agile Master; People Lead se a pessoa já é). `squad_id` e
  `default_project_id` continuam gravados (dual-write);
- só no primeiro uso: exige que a squad **não tenha nenhum vínculo nem projeto cadastrado** (sem caixa) e que a pessoa **não tenha
  vínculo em lugar nenhum**. Squad importada do Jira (só pmr, sem linha em `squads`) nunca é concedida a ninguém por aqui;
- o cache de vínculos é invalidado pelo listener JPA (`MembershipCacheEvictionListener`) na própria gravação; há teste;
- auditoria: log INFO `authz_bootstrap user=<id da conta> squad=<chave>` (sem e-mail nem nome), **só quando concede**;
- ADMIN não passa por aqui (já tem acesso) e não ganha vínculo.

Limites conhecidos: a serialização do primeiro uso é por processo (`synchronized`); com várias instâncias do backend, duas pessoas
chegando no mesmo instante a uma squad vazia podem virar as duas liderança. Em `legacy`/`shadow` (comportamento antigo, mantido) toda
pessoa sem squad entra numa squad ainda sem `squads`/roster, ou seja, várias podem entrar na mesma até alguém salvar a configuração.

## Modo sombra: `authz_divergence`

A cada decisão em `shadow` em que legado e novo discordam:

```
authz_divergence user=<id da conta> check=<ponto> squad=<chave> legacy=<bool> new=<bool> reason=<rótulo>
```

- **WARN** quando o novo NEGA o que o legado permite (`legacy=true new=false`) = **perda de acesso**. **INFO** no sentido contrário.
- Contador Micrometer `authz.divergence{check, direction}` com `direction` = `LOSS` (legado permite, novo nega) ou `GAIN`. O
  actuator só expõe `health` por padrão: o contador só aparece num backend de métricas (OTLP/SigNoz) ou se `metrics` for exposto.
- Sem e-mail, nome ou qualquer PII: só o id da conta, a chave da squad (saneada: `[A-Za-z0-9._:-]`, 64 caracteres) e rótulos.
- O mesmo log (conta, ponto, squad, sentido) repete no máximo a cada `app.membership.authz-log-dedupe-seconds` (padrão 60; `0` loga
  todas) para não inundar o log em pontos que rodam a cada requisição. O contador conta todas.
- Erro no cálculo sombra gera `authz_shadow_error user=.. check=.. squad=.. erro=<classe>` (nunca a mensagem) e **nunca** muda a decisão.
- Em `new`, erro no cálculo propaga (um 500 visível é melhor que falhar aberto num controle de acesso).

| `reason` | Significa |
|---|---|
| `ONLY_ROSTER` | o legado dava acesso só porque a pessoa está no roster (`squad_members`) |
| `ONLY_SQUAD_ID` | só porque `users.squad_id`/`default_project_id` aponta para a squad |
| `ROSTER_AND_SQUAD_ID` | pelos dois, sem vínculo |
| `ONLY_JOB_TITLE` | gestão/liderança só pelo cargo (`jobTitle`) |
| `NO_LEADERSHIP_FALLBACK` | gestão só porque a squad não tem liderança cadastrada e a pessoa não tem vínculo |
| `NEW_ONLY_MEMBERSHIP` | (sentido GAIN) o novo permite e o legado nega: dado novo entre as duas leituras ou resolvedor divergente |
| `RESOLVER_ONLY` | os dois cálculos de vínculo discordam entre si: investigar o `user` |
| `NO_MEMBERSHIP`, `ACTIVE_SQUAD_DIFFERENT`, `ROLE_KEYS_LOST`, `ROLE_KEYS_ADDED`, `UNKNOWN` | pontos que comparam squad ativa/papéis (não há permitir/negar) |

## `GET /api/admin/membership/authz-diff` (ADMIN)

Compara, para **todos os usuários ativos x squads** (todas as de `projects` mais as que aparecem para a pessoa em `users.squad_id`,
roster ou vínculos), a decisão legada e a nova de `matchesSquad`, `canManageSquad` e `isSquadLeadership`. Somente leitura (transação
`readOnly`), sem cache. `401` sem token, `403` para quem não é ADMIN; a chamada é auditada como qualquer `/api/admin/**`. ADMIN/LEAD
ficam de fora (decisão idêntica). Custo: algumas consultas por usuário; rode fora do pico em bases grandes.

```jsonc
{
  "generatedAt": "2026-10-17T12:00:00Z",
  "mode": "legacy",                 // app.membership.authz em vigor (a comparação é a mesma em qualquer modo)
  "sampleLimit": 20,
  "usersChecked": 150, "usersSkippedPrivileged": 3, "usersFailed": 0,
  "pairsChecked": 6300, "usersWithDivergence": 12,
  "totals": { "LOSS": 31, "GAIN": 0 },                       // conta divergências (pessoa x squad x ponto)
  "byCheck": { "matchesSquad":      { "LOSS": 18, "GAIN": 0 },
               "canManageSquad":    { "LOSS": 9,  "GAIN": 0 },
               "isSquadLeadership": { "LOSS": 4,  "GAIN": 0 } },
  "byReason": { "LOSS:ONLY_ROSTER": 12, "LOSS:ONLY_SQUAD_ID": 6, "LOSS:ONLY_JOB_TITLE": 9, "LOSS:NO_LEADERSHIP_FALLBACK": 4 },
  "sample": [ { "userId": "uid-1", "squad": "DDWMISSI", "check": "matchesSquad", "direction": "LOSS", "reason": "ONLY_ROSTER" } ],
  "truncated": true                 // há mais divergências do que a amostra (as contagens são exatas)
}
```

## Quando virar a chave (critério objetivo para a homologação)

1. `APP_MEMBERSHIP_AUTHZ=shadow` por **pelo menos um ciclo de sprint** com uso real; acompanhar `authz_divergence` (WARN).
2. `GET /api/admin/membership/authz-diff` e a categoria `WOULD_LOSE_ACCESS` de `/api/admin/membership/report`
   (`docs/membership-report.md`): **`totals.LOSS = 0`**, ou cada pessoa restante tem decisão registrada (criar o vínculo ou aceitar a
   perda). `RESOLVER_ONLY` e `GAIN` precisam estar em 0 ou explicados (indicam bug ou dado mudando).
3. Nenhum `authz_squad_without_leadership` de squad que ainda precise de configuração (cadastrar a liderança antes).
4. Nenhum chamado de "perdi acesso" na janela do `shadow`.
5. Virar `new` em horário de baixo uso, com o plano de reversão à mão (`APP_MEMBERSHIP_AUTHZ=legacy` + reinício; nada a restaurar no banco).

## Instalação limpa com `mode=new` e `authz=new` (sem passar pelo modo sombra)

Sistema novo, sem dado nenhum, com `app.membership.bootstrap=off` (padrão). O login é e-mail corporativo + senha
(`POST /api/auth/register|login`); não há login Google no backend (o Google só é usado para o Calendar).

**O caminho oficial:**

1. O **primeiro ADMIN** não nasce no código: é promovido por SQL (`UPDATE users SET role = 'ADMIN' ...`, README) depois de se cadastrar.
2. Ele cria a equipe por `POST /api/projects` (ou importa do Jira). **O criador da equipe entra como liderança**: `createManualProject`
   grava o vínculo `source=MANUAL`, `is_leadership=true`, como AM/PL se já é, senão Agile Master. Isso não amplia privilégio de ninguém
   (só quem já passou por `canManageTeams` chega ali) e a squad nasce com liderança cadastrada, então não dispara
   `authz_squad_without_leadership`.
3. Os **demais entram por convite** (`POST /api/squads/{id}/invites` + `/api/invites/{token}/accept`), **pedido de vínculo**
   (Etapa 3, `/link-requests`) ou **importação** do Jira (que cria o roster/pmr; depois "Sou eu"). Membro comum não cria equipe
   (403) nem inventa squad (403 com o bootstrap desligado).

Decisões de acesso em cada passo (teste: `PrimeiroUsuarioInstalacaoLimpaTest`):

| Passo | Chamada | Quem decide | Depende de roster / `users.squad_id` / cargo? |
|---|---|---|---|
| Cadastro e sessão | `POST /api/auth/register`, `GET /api/auth/me`, `GET /api/me/memberships` | `MembershipService` (pmr); conta sem vínculo e sem rastro vai para o onboarding | não (rastros só marcam `legacyOnly`) |
| Onboarding | `GET /api/onboarding/suggestions\|search\|roster`, `POST /claim/{id}` | só autenticação; o "Sou eu" liga a conta a uma linha existente | lê o roster por desenho (reconhecer a pessoa), não decide acesso |
| Criar a equipe | `POST /api/projects` | `JiraProfieldsService.canManageTeams`: ADMIN, AM/PL em pmr, **ou `jobTitle` AM/PL atribuído por admin** | **cargo (`jobTitle`) ainda vale aqui**; membro comum recebe 403 |
| Entrar por "join" | `POST /api/projects/{k}/join` | ADMIN, ou constar no roster/Jira do projeto | **roster** (por desenho: é o reconhecimento da pessoa) |
| Telas da squad | `/api/squads/**`, poker, retro, showcase, WebSocket... | `SquadAccessService.matchesSquad/canManageSquad/isSquadLeadership` | não (só pmr, ADMIN e LEAD) |
| Equipe | `/api/squads/{id}/team/**` | `SquadTeamService`: ADMIN ou AM/PL em pmr | não |
| Convite | `POST /api/squads/{id}/invites` | ADMIN/LEAD ou liderança em linha própria de pmr | não |
| Home, avisos, notificações | `GET /api/home` | `SquadAudienceService` (só vínculos) | não |

**Onboarding de membro comum na UI** (leitura de `agile-space-frontend-wt/integration`, `src/components/onboarding`): os caminhos que
uma pessoa sem vínculo tem são (1) "Sou eu" (`onboardingService.suggestions/search/roster` + `claimRosterMember`, exige linha no
roster), (2) entrar numa squad com papel (`joinProject`, exige constar no roster/Jira ou ser ADMIN), (3) criar squad (`createProject`,
só AM/PL/ADMIN), (4) colar link/código de convite (`/invite/{token}`), (5) pedir vínculo (`RequestLinkStep` -> `link-requests`),
(6) importar do Jira (`ImportPreview`/`projectService`, só AM/PL/quem o Jira lista como tal) e (7) exploração pública. **Nenhum deles
chama o bootstrap**: todas as escritas em `/api/squads/{id}` da UI (`src/app/squad/api.ts`, painéis, sync) ficam atrás de quem já
está numa squad. Portanto o bootstrap pode ficar `off` sem quebrar o onboarding.
