# API de vínculos (Pessoa x Squad x Papel)

Etapa 1 do plano de vínculo (`docs/vinculo-auditoria-e-plano.md`). **Aditiva**: não muda quem pode o quê. Autorização por
squad continua no `SquadAccessService` e no resolvedor antigo (Etapa 2). A fonte da verdade do vínculo é
`project_member_roles` (pmr).

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
