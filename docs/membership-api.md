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
- **`legacyOnly`**: `true` só quando `memberships` está vazio **e** a conta tem rastro do modelo antigo: `default_project_id`
  preenchido (fora de "Sem Time"/"Sem Squad"; a coluna `users.squad_id` deixou de ser lida na Etapa 5), ou presença no roster (`squad_members` reivindicado pela conta ou
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
`switchActiveProject` grava **só** `default_project_id` (a preferência; o dual-write com `squad_id` acabou na Etapa 5) nos três modos
e só aceita squad que esteja nos vínculos (`403` caso contrário). No `new`, grava o id canônico do vínculo (o legado gravava o texto como veio, ex. `ddwmissi`).

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
`APP_MEMBERSHIP_AUTHZ=legacy` e reiniciar. **Sem migração de banco**, sem dump necessário. Desde a Etapa 5 só `default_project_id`
é gravado pelos fluxos de entrada; nos modos `legacy`/`shadow` a antiga "squad da sessão" (`users.squad_id`) é lida como
`default_project_id` (eram gravados juntos, então coincidem; a categoria `USER_SQUAD_DEFAULT_DIVERGENT` do relatório lista as exceções).

## A regra nova (modo `new`)

- **ADMIN** (token ou cadastro): acesso total. **LEAD** só com `app.membership.lead-level=legacy` (Etapa 4: com `off` não concede nada).
- **Vínculo** em `project_member_roles` (`MembershipService`, com cache), inclusive a expansão por tribo/segmento da liderança
  transversal (`TRIBE_LEAD`, `AGILE_COACH`, `PEOPLE_LEAD` com `is_leadership=true`).
- **Deixam de dar acesso**: `users.squad_id`/`default_project_id`, o roster `squad_members` e o cargo (`jobTitle`) como autoridade.
- **Gestão** (`canManageSquad`): ADMIN (LEAD só em `lead-level=legacy`), ou vínculo com `is_leadership` na squad (inclusive transversal).
- **Liderança estrita** (`isSquadLeadership`): ADMIN, ou vínculo com `is_leadership` (o LEAD sem vínculo de liderança continua não
  contando, igual ao legado).
- **Squad sem NENHUMA liderança cadastrada** (bootstrap de ambiente novo): só quem **tem vínculo** na squad gerencia (antes: qualquer
  membro do roster/`users.squad_id`). Cada squad nessa situação gera, uma vez por processo, o aviso
  `authz_squad_without_leadership squad=<chave>`; ela também aparece no relatório (`SQUADS_WITHOUT_LEADERSHIP`). A saída é cadastrar
  a liderança (importação do Jira, convite de admin).

## Pontos de autorização migrados

| Ponto | Regra legada | Regra nova (`new`) | `check` no log |
|---|---|---|---|
| `SquadAccessService.matchesSquad` (leitura de qualquer tela por squad, WebSocket) | ADMIN/LEAD*, `users.squad_id`/`default_project_id`, resolvedor pmr, roster | ADMIN/LEAD*, vínculo (com tribo/segmento) | `matchesSquad` |
| `SquadAccessService.canManageSquad` | `matchesSquad` + (cargo ou liderança pmr ou squad sem liderança) | vínculo + (liderança ou squad sem liderança) | `canManageSquad` |
| `SquadAccessService.isSquadLeadership` | `matchesSquad` + (cargo ou liderança pmr) | vínculo com liderança | `isSquadLeadership` |
| `InviteController.requireInviteAccess` (criar/listar/revogar convite) | ADMIN/LEAD*; `users.squad_id` + cargo; liderança em linha pmr própria | ADMIN/LEAD*; liderança em linha **própria** (escopo SQUAD; a expansão por tribo não convida) | `invite` |
| `InviteService.isSquadMemberOrAdmin` (quem vê e-mail/autor de convite endereçado) | ADMIN/LEAD*; `users.squad_id`; qualquer linha pmr por conta/e-mail | ADMIN/LEAD*; vínculo | `inviteView` |

\* LEAD só vale com `app.membership.lead-level=legacy` (Etapa 4); com `off` é tratado como MEMBER.
| `SquadAudienceService.contextFor` (squads, papéis e liderança da home/avisos) | `users.squad_id`, resolvedor, roster; cargo conta como liderança; só o papel primário por squad | só vínculos; todos os papéis da pessoa; sem cargo | `audience`, `audienceLeadership`, `audienceRoles` |
| `SquadAudienceService.memberUserIds` (destinatários de notificação/aviso por squad) | `default_project_id`, linhas pmr, roster | só linhas pmr (sem `findActiveByDefaultProjectIds`) | `audienceMembers` |
| `JoltProjectController` / `JoltProjectService` (compartilhar/ler projeto Jolt com a squad) | UMA squad: `users.squad_id` | N squads: as dos vínculos (nova consulta por lista) | `jolt` |
| `KnowledgeService.squadOfUser` (squad gravada no documento novo; define quem edita) | `users.squad_id` | squad ativa validada contra os vínculos | `knowledge` |
| `PromptHubApiV1Controller` / `McpPromptHubTools` (squad de autoria, fallback da chave) | `users.squad_id` | squad ativa validada | `prompthub` |
| `ApiKeyController.create` / `ApiKeyLifecycle.renew` (chave SQUAD_READ travada na squad) | `users.squad_id` | squad ativa validada; **sem vínculo, a chave perde `SQUAD_READ`** em vez de ficar sem restrição | `apikey` |

**Sem mudança necessária**: `SquadAudienceService.userIdsWithRoles` já lê só `UserRole` + `project_member_roles`;
`UserService.requireProjectAccess` já usa o resolvedor de vínculos. **A base de conhecimento continua compartilhada de propósito**:
só mudou de onde vem a squad gravada no documento (que decide quem edita), nunca quem lê.

**Bootstrap de squad nova em ambiente limpo** (`SquadBootstrapService`, chamado por `SquadController` quando a pessoa não passa
na leitura/escrita/gestão): é o primeiro uso de uma squad que ainda não existe (sem linha em `squads`, sem roster) por quem não tem
squad ativa preferida (`default_project_id` vazio; a coluna `users.squad_id` foi aposentada na Etapa 5). Chave própria **`app.membership.bootstrap`** (env `APP_MEMBERSHIP_BOOTSTRAP`, também no `docker-compose.yml`),
**padrão `off`**; só `on` liga (vazio ou valor desconhecido = `off`, com aviso no log).

| `authz` | `bootstrap=off` (padrão) | `bootstrap=on` |
|---|---|---|
| `legacy` / `shadow` | como sempre: grava `default_project_id` (não grava mais `users.squad_id`); não cria vínculo | idem (a chave é ignorada) |
| `new` | **não concede nada**: squad vazia é 403 para quem não é ADMIN; nada é gravado | cria o vínculo (abaixo) |

**Por que `off` por padrão:** com `on`, qualquer conta sem vínculo pode inventar uma chave de squad que não existe e virar Agile
Master dela; como `canManageTeams` vale para quem é AM/PL em qualquer pmr, essa conta passa a criar e importar equipes no sistema
inteiro. É uma auto-promoção, então só se liga conscientemente (por exemplo, numa instalação de demonstração).

Com `on` em `authz=new`:

- cria o projeto (`projects`, nome = chave) se faltar e a linha de `project_member_roles` da pessoa: `source=MANUAL`,
  `is_leadership=true`, papel do fundador de `createManualProject` (Agile Master; People Lead se a pessoa já é). Grava
  `default_project_id` (`users.squad_id` não é mais gravada);
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

Compara, para **todos os usuários ativos x squads** (todas as de `projects` mais as que aparecem para a pessoa em `default_project_id`,
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

# LEAD deixa de ser nível de conta (Etapa 4)

Os níveis de conta (`users.role`) passam a ser **ADMIN | MEMBER**. Quem lidera uma squad tem um **vínculo de liderança**
(`project_member_roles.is_leadership`, por exemplo Agile Master, People Lead, Tech Lead). O nível `LEAD` não concede mais nada.

**Regra de ouro**: o sistema nunca infere liderança nem cria vínculo sozinho. A migração V77 é só aditiva (backup e troca de rótulo de
aviso); quem decide quem vira MEMBER, e com qual vínculo de liderança, é um administrador, pelo endpoint abaixo.

## Chave `app.membership.lead-level` (env `APP_MEMBERSHIP_LEAD_LEVEL`, também no `docker-compose.yml`)

| Valor | O que o nível `LEAD` da conta faz |
|---|---|
| `off` (**padrão do `application.yml`**, instalação nova) | **Nada.** `LEAD` é tratado como `MEMBER` em toda decisão de acesso (autorização por squad, convite, `by-user`, facilitador do Poker, chaves de API, público de avisos, relatório). O poder vem só do vínculo. |
| `legacy` | Comportamento de antes: LEAD acessa e gerencia todas as squads, convida, é facilitador de qualquer sala de Poker e pode emitir chaves com todos os escopos. Serve para **reverter sem deploy**. |

Valor desconhecido (`of`, `Legacy2`...) cai em **`legacy`**: errar a digitação nunca REMOVE acesso (um aviso sai no log de
inicialização). Valor vazio vale o padrão (`off`). Para desligar de verdade é preciso escrever `off`. Reverter:
`APP_MEMBERSHIP_LEAD_LEVEL=legacy` e reiniciar (o rebaixamento de contas, se já feito, se desfaz com
`POST /api/admin/membership/lead-restore`). A chave é independente de `app.membership.authz`: vale em `legacy`, `shadow` e `new`.

## `POST /api/admin/membership/lead-demotion` (ADMIN)

Rebaixa de `LEAD` para `MEMBER` **somente** quem já tem vínculo de liderança. **Dry-run por padrão**: sem `apply=true` não grava nada.
`401` sem token, `403` para quem não é ADMIN, `400` parâmetro inválido (`forceDemote` sem `userId`, `limit` fora de 1..200).

Query: `apply` (padrão `false`), `limit` (padrão `20`, máximo `200`; tamanho do lote), `userId` (restringe a uma conta; o `limit` não se
aplica), `forceDemote` (padrão `false`; exige `userId`; permite rebaixar conscientemente uma conta `NO_LEADERSHIP_LINK`). Com `userId`
inexistente: `404`; conta que não é LEAD: `409`.

Classificação de cada conta com `users.role = LEAD` (ativa ou não):

| `classification` | Significado | `apply=true` |
|---|---|---|
| `HAS_LEADERSHIP_LINK` | tem pelo menos uma linha `project_member_roles` com `is_leadership` (casada por conta, e-mail ou conta do Jira) | rebaixa (até o limite do lote) |
| `NO_LEADERSHIP_LINK` | não tem liderança em nenhuma squad: rebaixar tira todo o poder dela | **não rebaixa**; só reporta. O admin cria o vínculo ou usa `userId` + `forceDemote=true` |

```jsonc
// POST /api/admin/membership/lead-demotion          (dry-run)
// POST /api/admin/membership/lead-demotion?apply=true&limit=10
// POST /api/admin/membership/lead-demotion?apply=true&userId=uid-7&forceDemote=true
{
  "mode": "DRY_RUN",                 // ou "APPLIED"
  "leadLevel": "off",                // app.membership.lead-level em vigor: "off" | "legacy"
  "limit": 20,
  "summary": {
    "leadUsers": 3,                  // contas LEAD consideradas (todas, ou só a do userId)
    "hasLeadershipLink": 1,
    "noLeadershipLink": 2,
    "wouldDemote": 1,                // dry-run: o que o apply rebaixaria agora
    "demoted": 0,                    // apply: o que foi rebaixado
    "keptNoLeadershipLink": 2,
    "overLimit": 0                   // elegíveis que ficaram para o próximo lote
  },
  "users": [
    {
      "userId": "uid-1",
      "email": "ana@totvs.com.br",
      "name": "Ana",
      "active": true,
      "classification": "HAS_LEADERSHIP_LINK",
      "leadershipSquads": ["DDWMISSI", "DDWPRIME"],   // squads onde há linha de liderança (MAIÚSCULAS, sem repetição)
      "action": "WOULD_DEMOTE",      // ver tabela abaixo
      "activeApiKeys": 2,            // chaves de API ativas (não revogadas e não vencidas) do usuário
      "apiKeysNote": "2 chave(s) de API ativa(s) continuam válidas com os escopos atuais até serem renovadas ou rotacionadas; a renovação corta os escopos de liderança. Nenhuma chave foi alterada."
    },
    { "userId": "uid-2", "email": "bia@totvs.com.br", "name": "Bia", "active": true, "classification": "NO_LEADERSHIP_LINK",
      "leadershipSquads": [], "action": "KEPT_NO_LEADERSHIP_LINK", "activeApiKeys": 0, "apiKeysNote": null }
  ],
  "warnings": [
    "app.membership.lead-level=legacy: rebaixar agora REMOVE o acesso global destas contas."   // só quando leadLevel=legacy
  ]
}
```

| `action` | Quando |
|---|---|
| `WOULD_DEMOTE` / `WOULD_DEMOTE_FORCED` | dry-run: seria rebaixada (a forçada é `NO_LEADERSHIP_LINK` com `userId`+`forceDemote=true`) |
| `DEMOTED` / `DEMOTED_FORCED` | `apply=true`: rebaixada para `MEMBER` |
| `KEPT_NO_LEADERSHIP_LINK` | `NO_LEADERSHIP_LINK` sem `forceDemote`: nada feito |
| `OVER_LIMIT` | elegível, mas fora do lote atual (rode de novo) |

O que o `apply` faz por conta: grava/atualiza `users_role_lead_backup(id, role='LEAD', migrated_at)`, troca `users.role` para `MEMBER`,
audita (`audit_logs`: `LEAD_DEMOTED` ou `LEAD_DEMOTED_FORCED`, com quem fez e a conta) e derruba o cache de sessão (`UserSessionGuard`: o
token vivo da pessoa passa a valer como MEMBER na hora neste servidor e em até ~30 s nos demais). O lote inteiro é uma transação. As
**chaves de API não são alteradas**: seguem válidas até renovar/rotacionar; a renovação reavalia o papel e corta os escopos de liderança.

## `POST /api/admin/membership/lead-restore` (ADMIN)

Desfaz o rebaixamento a partir de `users_role_lead_backup`. Query: `userId` (opcional; sem ele, todos do backup). Só restaura quem hoje
é `MEMBER` (nunca sobrescreve um `ADMIN` promovido depois). Audita `LEAD_RESTORED` e derruba o cache de sessão. Idempotente.

```jsonc
// POST /api/admin/membership/lead-restore            (todos)
// POST /api/admin/membership/lead-restore?userId=uid-1
{
  "restored": 1,
  "skipped": 1,
  "users": [
    { "userId": "uid-1", "email": "ana@totvs.com.br", "result": "RESTORED", "reason": null },
    { "userId": "uid-9", "email": "caio@totvs.com.br", "result": "SKIPPED", "reason": "CURRENT_ROLE_NOT_MEMBER" }
  ]
}
```

`reason` quando `SKIPPED`: `ALREADY_LEAD`, `CURRENT_ROLE_NOT_MEMBER`, `USER_NOT_FOUND`, `NOT_IN_BACKUP` (só com `userId`).
Observação: com `lead-level=off` a conta restaurada é `LEAD` mas continua sem poder algum.

## Relatório (`GET /api/admin/membership/report`)

- `LEAD_USERS` (mantida): contas **ativas** com `role = LEAD`; rótulo `squads_lideranca=N`.
- `LEAD_NO_LEADERSHIP_LINK` (nova): subconjunto de `LEAD_USERS` com **nenhuma** linha de liderança (`squads_lideranca=0`); rótulo
  `sem_lideranca_por_vinculo`. Quem aparece aqui perderia todo o poder ao sair de LEAD: crie o vínculo (ou aceite) antes do `apply`.

## Migração V77 (aditiva)

Cria `users_role_lead_backup(id, role, migrated_at)` e copia nela as contas com `UPPER(role) = 'LEAD'` que ainda não estão lá; troca
`global_announcement_roles.role = 'LEAD'` por `'LEADERSHIP'`. **Não rebaixa ninguém e não cria vínculo.** Reexecutar é inofensivo.
Público de aviso `LEADERSHIP` = quem tem alguma linha de liderança em `project_member_roles`; `LEAD` gravado por um cliente antigo é
normalizado para `LEADERSHIP` ao salvar o aviso.

## Pontos que o nível LEAD concedia (antes x agora)

| Ponto (arquivo: método) | Antes (LEAD) | Agora (`lead-level=off`) |
|---|---|---|
| `SquadAccessService.matchesSquad` (leitura por squad, WebSocket) | todas as squads | só vínculo (`legacy` authz: também o legado sem LEAD) |
| `SquadAccessService.canManageSquad` | gestão de qualquer squad | só liderança por vínculo na squad |
| `SquadAccessService.isSquadLeadership` | (nunca contou) | igual |
| `InviteController.requireInviteAccess` | criar/listar/revogar convite em qualquer squad | só liderança em linha própria da squad |
| `InviteService.isSquadMemberOrAdmin` | ver e-mail/autor de convite endereçado | só quem tem vínculo (ou o destinatário) |
| `SquadController.getSquadMembersForUser` (`/by-user`) | consultar a squad de qualquer pessoa | só a própria (ou ADMIN) |
| `PokerService` (`isRoomFacilitator`, `requireRoomParticipant`, `requireSelfOrFacilitator`) | facilitar, entrar e votar como outro em qualquer sala | facilitador = criador, ADMIN, participante marcado ou **liderança por vínculo na squad da sala** |
| `PokerService.deleteRoom` / `deleteChatMessage` | excluir qualquer sala / moderar | só o criador e o ADMIN (mensagem: autor, criador, ADMIN) |
| `ApiKeyController.create` / `ApiKeyLifecycle` (`reevaluateOwner`) | todos os escopos e squad livre | só `KNOWLEDGE_READ`/`SQUAD_READ`, travada na squad; **na renovação a chave perde os demais escopos** e, sem squad para travar, perde `SQUAD_READ` |
| `SquadAudienceService` (`contextFor`, `userIdsWithRoles`) | `leadership=true`, chave `LEAD` | só vínculo; chave derivada `LEADERSHIP`; `LEAD` não casa ninguém |
| `AuthzDiffService` / relatório | LEAD "privilegiado", fora da comparação | LEAD entra na comparação como MEMBER |
| `SquadLeadership` | cargo/nome "lead" = liderança | removido |
| `UserService.saveUser` | atribuía LEAD | rejeita (400); LEAD reenviado sem mudança é tolerado |
| `AuthService` (token e sessão) | `role` como gravado | `LEAD` legado sai como `MEMBER` |
| `UserRole` | `ADMIN, LEAD, MEMBER` | `ADMIN, MEMBER` (`parse("LEAD")` = `MEMBER`) |

Chaves de API **já emitidas** a um LEAD não são alteradas: o filtro de chave usa os escopos gravados e a squad da chave, não o papel
atual do dono. Elas valem até vencer ou ser renovada/rotacionada (a renovação reavalia). Revogue à mão as que não devem sobreviver.

Decisão de produto registrada: com `off`, **liderança de uma squad por vínculo** (`is_leadership`, direta ou por expansão de tribo)
passa a ser facilitadora automática das salas de Poker da própria squad, inclusive para quem nunca foi LEAD. Em `legacy` isto não existe.

## Mudança de leitura/escrita do nível de conta

- `UserRole`: apenas `ADMIN` e `MEMBER`. A leitura de um `LEAD` legado vira `MEMBER` (`UserRole.parse`).
- Criar/editar usuário pelo admin: `role = LEAD` é **rejeitado** (400, "use ADMIN ou MEMBER") com `lead-level=off`; em `legacy` continua aceito.
- Token e `AuthResponse.role`: com `off`, um `LEAD` legado sai como `MEMBER`. A listagem de usuários do admin (`/api/users`) continua mostrando o valor gravado.

## Roteiro recomendado (ambiente que já tem LEAD, como a homologação)

1. Suba a versão com `APP_MEMBERSHIP_LEAD_LEVEL=legacy` **explícito** (o padrão do compose e do `application.yml` é `off`; sem a variável o
   LEAD perderia o acesso global no deploy). A V77 é aditiva e roda sozinha.
2. `GET /api/admin/membership/report`: `LEAD_USERS` e `LEAD_NO_LEADERSHIP_LINK`. Para cada LEAD sem liderança, **decida** o vínculo
   (convite, importação do Jira, adição pela liderança da squad) ou aceite a perda. O sistema não cria vínculo sozinho.
3. `POST /api/admin/membership/lead-demotion` (dry-run): confira `summary` e `warnings`; reúna-se com os poucos LEAD.
4. `POST .../lead-demotion?apply=true&limit=5`, repetindo por lotes; acompanhe se alguém reclama de acesso. Com `legacy` o rebaixamento já
   tira o acesso global da conta (o aviso `warnings[]` diz isso).
5. Quando `LEAD_USERS` for 0 (ou só restar quem foi aceito), vire `APP_MEMBERSHIP_LEAD_LEVEL=off` e reinicie.
6. Reverter a qualquer momento: `APP_MEMBERSHIP_LEAD_LEVEL=legacy` e reiniciar devolve o acesso a quem ainda é LEAD;
   `POST /api/admin/membership/lead-restore` devolve o nível LEAD a quem foi rebaixado (revise antes: restaurar com `legacy` devolve o
   acesso global). Rever as chaves de API de ex-LEAD (continuam válidas até renovar).

---

# Aposentadoria de `users.squad_id` (Etapa 5, passo 1)

O vínculo (`project_member_roles`) é a única fonte de verdade de "quem está em qual squad". `users.default_project_id` fica como
**preferência de squad ativa** (validada contra os vínculos). O backend **para de ler e de gravar** `users.squad_id` e o campo sai
da entidade `User`; a **coluna continua no banco** (o `ddl-auto=validate` tolera coluna a mais; nenhuma migração nesta etapa).

## Contrato de compatibilidade com o frontend (nada quebra)

| Superfície | Antes | Agora |
|---|---|---|
| `POST /api/users` (`saveUser`, `updateProfile`) com `squadId` no corpo | `squadId` mudava a squad (exigia vínculo; 403 sem ele) | **Ignorado**: sem erro, sem 400, sem 403, sem efeito. O resto do perfil (nome, avatar, horas diárias, jira id, segmento, tribo) grava igual. |
| Como trocar de squad | `saveUser` ou `POST /api/auth/switch-project` | **Só** `POST /api/auth/switch-project` (valida o vínculo e grava `users.default_project_id`, a preferência). |
| `GET /api/users/{id}` e resposta de `POST /api/users` | `squadId` = coluna `users.squad_id` | `squadId` continua no JSON, **somente leitura**, derivado da **squad ativa validada** do `MembershipService` (a preferência `default_project_id` se estiver nos vínculos; senão a primeira squad por precedência; `null` sem nenhum vínculo). |
| `GET /api/users` (lista, ADMIN) | `squadId` = coluna `users.squad_id` | `squadId` somente leitura = `users.default_project_id` **sem validar** (evita 3 consultas por pessoa; é só rótulo de exibição). Pessoa sem preferência vem sem `squadId` (a tela cai em `team`/"Sem squad"). |
| `AuthResponse` (`login|me|refresh|register|switch-project|change-password`) | não tem campo `squadId`: a squad ativa é `activeProjectId` | **Inalterado.** `activeProjectId/Name/Role`, `accessibleProjects`, `legacyOnly` e o claim `defaultProjectId` do JWT seguem exatamente como antes. |
| `GET /api/me/memberships` | — | Inalterado. |

Regras para o frontend:

- Pode **parar de enviar** `squadId` em `saveUser`/`updateProfile`; um cliente antigo que continue enviando não quebra (o campo é descartado).
- Para saber a squad ativa, usar `AuthResponse.activeProjectId` ou `GET /api/me/memberships` (`activeProjectId`), como já faz. O `squadId` do
  `User` é só legado de exibição e será removido do JSON quando o frontend não o ler mais.
- O JSON de `User` nunca devolveu `team`; o `team` do `UserProfile` do frontend é derivado no cliente.

## O que mudou no código (inventário verificado por busca e por compilação)

Verificação: o campo saiu da entidade, então **o compilador** prova que não resta nenhum `getSquadId()/setSquadId()/squadId(...)` de `User`
(os de `SquadMember`, `WorkItem`, `Invite`, cerimônias etc. são de outras entidades e seguem). Mais a varredura de SQL/JPQL: a única
consulta com `users.squad_id` fora do diagnóstico era `UserRepository.findActiveBySquadIds`. O teste `UsersSquadIdColumnRetiredTest` trava
a regressão (reflexão na entidade + varredura de `src/main/java` sem comentários).

| # (tabela 1.A) | Arquivo: método | L/E | Antes | Agora |
|---|---|---|---|---|
| 4(c), 5 | `SquadAccessService.sessionSquadMatches` (legado de `matchesSquad`/`canManageSquad`/`isSquadLeadership`) | L | `squad_id` OU `default_project_id` = squad | só `default_project_id` |
| 9 | `SquadController.bootstrapNewSquad` | L/E | "sem squad" = `squad_id` vazio; gravava `squad_id` (+ `default_project_id` se vazio) | "sem squad" = `default_project_id` vazio; grava só `default_project_id` |
| 13 | `SquadService.saveMember` | E | sobrescrevia `users.squad_id` (last-write-wins entre squads) | só sincroniza o nome da conta; nada de squad |
| 14 | `SquadService.deleteMember` | L/E | zerava `squad_id` da conta removida | só apaga a linha do roster (a conta não é tocada) |
| 15 | `SquadTeamService.removeMember` | E | zerava `squad_id` e `default_project_id` | zera só `default_project_id` (se era essa squad) |
| 16 | `AuthService.login` (e o fallback do roster) | L/E | gravava `default_project_id` e `squad_id`; copiava um para o outro | grava só `default_project_id` (1ª squad dos vínculos, ou a do roster no fallback) |
| 17 | `AuthService.register` (conta nova e conta-fantasma) | E | gravava `default_project_id` e `squad_id` | só `default_project_id` |
| 18 | `AuthService.switchActiveProject` (também `ProjectController`, `OnboardingController`, `InviteController`) | E | dual-write | só `default_project_id` |
| 27 | `JiraAdminService.confirmSync` (importar do Jira, `syncUsers`) | E | gravava `squad_id=projectKey` (criar/atualizar) e zerava na saída | não grava nem zera nada de squad na conta |
| 28 | `InviteService.linkProjectMembership` (aceite de convite) | E | `default_project_id` se vazio + `squad_id` sempre | só `default_project_id` se vazio |
| 29 | `InviteService.isSquadMemberOrAdmin` / `legacyIsSquadMemberOrAdmin` | L | `squad_id` ou `default_project_id` | só `default_project_id` |
| 30 | `InviteController.legacyInviteAllowed` | L | idem | idem |
| 32 | `UserService.saveUser` | L/E | `squadId` do corpo trocava a squad (exigia vínculo) | **ignorado** (sem erro); a troca é só `/auth/switch-project` |
| 34 | `UserRepository.findActiveBySquadIds` | L | `squad_id` OU `default_project_id` | renomeada `findActiveByDefaultProjectIds` (só `default_project_id`); só o caminho legado de `SquadAudienceService` usa |
| 35, 36 | `SquadAudienceService.legacyContext` / `legacyMemberUserIds` | L | `squad_id` entrava na "squad corrente" e nos membros | só `default_project_id`; o caminho `new` já não lia |
| 44 | `KnowledgeService.squadOfUser` (legado) | L | `squad_id` | `default_project_id` (em `new`/`shadow` já era `activeSquadFor`) |
| 45 | `JoltProjectController` (`Caller.squadId`, legado) | L | `squad_id` | `default_project_id` |
| 46, 47 | `SquadAccessService.activeSquadFor` / `legacyShareSet`; `ApiKeyLifecycle.reevaluateOwner` (sem o serviço) | L | `squad_id` | `default_project_id` (em `new` segue a squad ativa validada) |
| — (não estava na tabela) | `MembershipService.hasLegacyTraces` | L | `squad_id` ou `default_project_id` | só `default_project_id` (+ roster) |
| — (não estava na tabela) | `AuthzDiffService.evaluateUser` | L | `squad_id` entrava no universo de squads | só `default_project_id`, roster e vínculos |
| — | `MembershipReportService` (SQL nativo) | L | `users.squad_id` | **mantido de propósito**: diagnóstico de legado (`LEGACY-SQUAD-ID-DIAGNOSTIC`), sai com o DROP COLUMN |

Correções à tabela 1.A do plano: (a) os itens 9, 13-18, 27, 28 e 32 estão certos; o item 15 grava `squad_id` só no `removeMember` (a
adição/troca de papel nunca escreveu); o item 27 tem **dois** pontos de escrita (criar e atualizar) mais o de zerar; (b) faltavam
`MembershipService.hasLegacyTraces`, `AuthzDiffService` e o caminho `ApiKeyLifecycle`/`activeSquadFor` (todos leituras); (c) o item 46
(`PromptHubApiV1Controller`, `McpPromptHubTools`) não lê a coluna diretamente: passa por `SquadAccessService.activeSquadFor`; (d)
`PokerService`, `HomeService`, `LiveSessionService`, `NotificationTriggers` e `ConversationService` **nunca** leram `User.squadId`: usam o
`SquadAudienceService.contextFor/memberUserIds` (indiretos, já cobertos acima); (e) `SquadSyncService` e `SquadCapacityService` só usam
`squadId` de `SquadMember`/`WorkItem`, não de `User`.

Não há consumidor da coluna em `scripts/` (os `live-tests` só mandam `squadId` em payloads de cerimônia), `deploy/`, `docker-compose.yml`
nem jobs de `scheduler/` (`SquadSyncScheduler` não lê `User`).

## `squad_members.claimed_by_uid`: o que foi aposentado e o que ficou

Aposentado como fonte de vínculo/acesso (a coluna **fica**; a capacidade segue ligada por `squad_members.jira_account_id`):

- `UserService.getSquadsForUser` (`GET /api/users/{uid}/squads`): agora vem dos **vínculos diretos** do `MembershipService` (a expansão por
  tribo/segmento não é "vínculo da pessoa"). Mesmo formato (`squadId`, `role`, `displayName`...): usa a linha do roster da pessoa pelo
  `jira_account_id` quando existe; senão uma linha só de resposta.
- `SquadTeamService.rowsOf`/`linkedUser`: a conta da pessoa do roster é achada por `jira_account_id` e depois por e-mail (antes: por
  `claimed_by_uid`). A checagem de duplicidade em `addMember` deixou de olhar `claimed_by_uid` (jira/e-mail já cobriam).
- `OnboardingService.applyLink` ("Sou eu" e pedido aprovado): **deixa de gravar** `claimed_by_uid` no roster; grava `project_member_roles.user_id`
  e herda o `jira_account_id` na conta, que é o que liga a capacidade.

**Ficou (remoção arriscada ou grande demais; recomendação abaixo):**

| Uso restante | Onde | Por que ficou | Recomendação |
|---|---|---|---|
| ~~Escrita do "Sou eu" do roster~~ (resolvido) | `POST /api/squads/{id}/members/{jira}` só com `claimedByUid` | `SquadService.claimMember` foi apagado; a rota responde **`410 Gone`** (`"Esta forma de vincular a conta foi desativada. Use o \"Sou eu\" do onboarding ou peça o vínculo."`) e não grava nada. O "Sou eu" válido é `POST /api/onboarding/claim/{id}`; o pedido é `POST /api/link-requests`. Salvar linha pela liderança (payload sem ser só `claimedByUid`) segue igual | feito |
| Gravação em login/registro/convite/import | `AuthService.login` (fallback do roster), `register`; `InviteService.acceptInvite`; `SquadTeamService.addMember`; `JiraAdminService.confirmSync` | marcam a linha do roster com a conta; sem leitor em `new` | parar de gravar junto do DROP da coluna |
| Leitura no caminho **legado** (só `authz`≠`new`) | `SquadAccessService.rosterMatches`, `SquadAudienceService` (`legacyContext`, links do roster), `AuthzDiffService.rosterRows`, `JiraAccountIdGuard` (guarda de posse do id do Jira) | são o próprio modo legado/sombra que ainda existe | cai quando o modo legado for removido; `JiraAccountIdGuard` é segurança (impede sequestro do id), mantenha |
| Diagnóstico | `MembershipReportService` (várias categorias), `MembershipService.hasLegacyTraces` | só leitura | idem |

Em `app.membership.authz=new` nenhuma decisão de acesso lê `claimed_by_uid`.

## Consumidores externos da coluna (varridos)

- `agile-space-backend/scripts`, `deploy`, `docker-compose.yml`, `Caddyfile`, `scheduler/`: nenhum uso de `users.squad_id`.
- `agile-space-legado` (somente leitura): usa Firestore e, nas telas de Jolt e snapshots, a **API** deste backend; não conecta ao Postgres nem lê `users.squad_id`.
- `agile-space-frontend-wt/integration/src`: só recebe `squadId` pela API (contrato acima). O que depende do campo do `User`: `components/admin/user-admin.ts`
  (`squadLabelOf`), `UserExplorer.tsx` (resumo da squad ativa), `UserContext.tsx`/`useSquadStore.ts` (**enviam** `squadId` em `saveUser`; ignorado agora).
- **Não confirmado:** relatórios/BI/consultas manuais direto no Postgres da homologação e do futuro Azure. Rodar `grep -rn "squad_id"` nos repositórios de BI e no
  histórico de consultas antes do passo 2.

## Passo 2 (destrutivo, NÃO feito): backup e DROP da coluna

Só depois de N semanas em produção/homologação sem leituras (a trava de arquitetura garante o código; o resto é externo) e **aprovação explícita**.
Script proposto (próxima migração livre, **não criada**):

```sql
-- V8x__retire_users_squad_id.sql  (exemplo; renumerar para a próxima livre)
CREATE TABLE users_squad_legacy_backup (
    id                 VARCHAR(255) PRIMARY KEY,
    squad_id           VARCHAR(255),
    default_project_id VARCHAR(255),
    backed_up_at       TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO users_squad_legacy_backup (id, squad_id, default_project_id)
SELECT id, squad_id, default_project_id FROM users;

-- confere antes de dropar: a Flyway aborta a migração (e nada é dropado) se o backup não cobrir todos os usuários
DO $$
BEGIN
  IF (SELECT COUNT(*) FROM users_squad_legacy_backup) <> (SELECT COUNT(*) FROM users) THEN
    RAISE EXCEPTION 'backup de users.squad_id incompleto';
  END IF;
END $$;

ALTER TABLE users DROP COLUMN squad_id;
```

No mesmo PR do passo 2: remover as categorias de `MembershipReportService` que usam `users.squad_id` (`USERS_WITH_SQUAD_WITHOUT_LINK` na parte
`squad_id`, `SQUAD_KEY_CASE_VARIANTS`, `USER_SQUAD_DEFAULT_DIVERGENT`, `EXCLUDED_BUT_STILL_LINKED` na parte `squad_id`, `squad_id AS squad` de `LEAD_USERS`
e dos duplicados), ajustar `docs/membership-report.md`, e tirar `MembershipReportService.java` da lista do `UsersSquadIdColumnRetiredTest`.
Os índices da coluna, se existirem, caem com ela (conferir `\d users` antes). Reverter: `ALTER TABLE users ADD COLUMN squad_id VARCHAR(255);` e
`UPDATE users u SET squad_id = b.squad_id FROM users_squad_legacy_backup b WHERE b.id = u.id;`.

## Diagnóstico de legado: a coluna virou um retrato congelado

`MembershipReportService` continua lendo `users.squad_id` por SQL nativo, mas desde esta etapa ninguém a atualiza: ela descreve o que havia no
deploy. Efeitos a ter em mente ao interpretar `docs/membership-report.md`: `USERS_WITH_SQUAD_WITHOUT_LINK`, `SQUAD_KEY_CASE_VARIANTS` e
`USER_SQUAD_DEFAULT_DIVERGENT` não crescem mais (contas novas têm a coluna `NULL`); `EXCLUDED_BUT_STILL_LINKED` pode listar com o rótulo `squad_id`
quem foi removido do time depois do deploy (a remoção não limpa mais a coluna): ignore esse rótulo e confie em `pmr`.

## Riscos de deploy e reversão

- **Nenhuma migração**: a coluna não é tocada; o `ddl-auto=validate` tolera coluna a mais (provado pelo `UsersSquadIdRetiredIntegrationTest` contra o schema real).
- Quem lia `squad_id` por fora (BI, consultas manuais, outro serviço) continua lendo, mas o valor deixa de se mover; contas novas vêm com `NULL`.
- Em `authz`≠`new` (rollback do modo), a "squad da sessão" passa a ser só `default_project_id`. Quem tinha `squad_id` ≠ `default_project_id` (lista em
  `USER_SQUAD_DEFAULT_DIVERGENT`) perde, **nesses modos**, o acesso que vinha só do `squad_id`; em `new` não muda nada.
- Frontend antigo que ainda envia `squadId` em `saveUser` não quebra (ignorado). Quem dependia disso para TROCAR de squad passa a precisar de `/auth/switch-project`.
- `GET /api/users` (lista) devolve `squadId` = `default_project_id` sem validar; `GET /api/users/{id}` e a resposta do `POST` devolvem a squad ativa validada.
- Reverter: reinserir `@Column(name = "squad_id") private String squadId;` em `User`, desfazer os commits `refactor(vinculo): users.squad_id deixa de ser lida...`
  e `test(vinculo)...` (a coluna nunca foi tocada, os dados antigos continuam lá).
