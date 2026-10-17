# Relatório de inconsistências do vínculo (Pessoa x Squad x Papel)

Etapa 0 do plano de vínculo (`docs/vinculo-auditoria-e-plano.md`, seção 3). **Somente leitura**: nada é gravado, nem
`audit_logs`. A consulta roda numa transação `readOnly` (o Postgres recusa escrita) e o resultado não é guardado.

## Endpoints

| Rota | Quem | Resposta |
|---|---|---|
| `GET /api/admin/membership/report` | ADMIN (`/api/admin/**` exige ADMIN no filtro JWT; o controller confere de novo) | JSON abaixo |
| `GET /api/admin/membership/report.csv` | ADMIN | o mesmo conteúdo em CSV, como download (`membership-report.csv`) |

Usuário comum ou LEAD recebe `403`, sem token `401`. Ambas respondem `Cache-Control: no-store`. A geração faz algumas dezenas de
consultas sobre `users`, `project_member_roles`, `squad_members` e `projects`; em bases com milhares de linhas leva segundos.
Rode fora de horário de pico se a base for grande.

## JSON

```jsonc
{
  "generatedAt": "2026-10-16T22:30:00Z",
  "sampleLimit": 20,                       // máximo de linhas de amostra por categoria
  "totals": {                              // contexto para dimensionar as categorias
    "users_total": 156, "users_active": 150, "projects": 40,
    "pmr_rows": 320, "pmr_with_user_id": 180, "pmr_without_user_id": 140, "pmr_leadership_rows": 95,
    "pmr_source_LEGADO": 320,              // source nulo = linha anterior à V75 (origem desconhecida, não se presume)
    "pmr_source_BACKFILL": 0, "pmr_source_MIGRATION": 0, // aparecem só as origens existentes
    "squad_members_rows": 500, "squad_members_claimed": 120, "squad_member_exclusions": 3
  },
  "categories": [
    {
      "code": "USERS_WITH_SQUAD_WITHOUT_LINK",   // chave estável, use-a em planilhas/telas
      "title": "Usuário com squad e sem vínculo nela",
      "description": "…o que significa e por que importa…",
      "count": 37,                                // total real, não limitado pela amostra
      "breakdown": { "sentinela": 12, "squad_id_nao_e_projeto": 15, "so_squad_members": 8, "sem_vinculo_nenhum": 2 },
      "sample": [                                 // até 20 linhas, ordenadas por squad e e-mail
        { "id": "uid-123", "email": "pessoa@empresa.com.br", "squad": "DDWMISSI", "detail": "so_squad_members" }
      ],
      "truncated": true                           // count > sample.size()
    }
  ]
}
```

A amostra traz só **id, e-mail, squad e um rótulo categórico** (`detail`). Não há nome, foto, token, senha ou conta do Jira.
O `id` depende da categoria: id do usuário, id da linha `project_member_roles`, `db_id` do roster ou a chave do projeto.
Datas e contagens vêm como números; campos ausentes vêm `null`.

## Categorias

| code | O que acusa | Rótulos (`detail` / `breakdown`) |
|---|---|---|
| `USERS_WITH_SQUAD_WITHOUT_LINK` | Conta ativa com `squad_id`/`default_project_id` sem linha pmr nessa squad (alias DDWMISSI/MISSI considerado) | `sentinela` (Sem Time), `squad_id_nao_e_projeto`, `so_squad_members`, `sem_vinculo_nenhum` |
| `ROSTER_WITHOUT_LINK` | `squad_members` sem pmr correspondente (conta, e-mail ou Jira) e não removido à mão | `projeto_existe`, `squad_so_roster` |
| `EXCLUDED_BUT_STILL_LINKED` | Em `squad_member_exclusions` mas ainda com pmr ou `users.squad_id` na squad | `pmr`, `squad_id`, `pmr_e_squad_id` |
| `SAME_SQUAD_MULTIPLE_ROWS` | Mesma conta com 2+ linhas pmr na mesma squad (o resolvedor mostra uma: liderança vence) | `lideranca_e_contribuidor`, `varias_liderancas`, `varios_contribuidores` |
| `USER_IN_MULTIPLE_SQUADS` | Conta com vínculo em 2+ squads | `N squads` |
| `ROSTER_DUPLICATE_CLAIMS` | Mesma `claimed_by_uid` em 2+ linhas do roster da mesma squad | `N linhas` |
| `SQUADS_WITHOUT_LEADERSHIP` | Projeto sem nenhuma linha pmr de liderança | `sem_nenhuma_pessoa`, `so_contribuidores` |
| `SQUADS_WITHOUT_APPROVER` | Sem AM/SM/PO/PL de liderança, ou nenhum deles com conta | `sem_aprovador`, `aprovador_sem_conta` |
| `PMR_USER_MISSING` | `pmr.user_id` aponta para conta inexistente | `conta_inexistente` |
| `ROSTER_CLAIM_USER_MISSING` | `squad_members.claimed_by_uid` aponta para conta inexistente | `conta_inexistente` |
| `CLAIM_DIVERGENT` | Mesma pessoa/squad no roster e no pmr com claims diferentes ou só de um lado | `roster_sem_claim_pmr_com`, `pmr_sem_claim_roster_com`, `contas_diferentes` |
| `JIRA_ACCOUNT_DUPLICATED` | Mesma `users.jira_account_id` em 2+ contas (uma linha por conta) | `jira_account_duplicada` |
| `PHANTOM_ACCOUNTS` | Sem senha e e-mail `@empresa.com` ou id = conta do Jira (criadas pelo sync) | `ativa`, `inativa` |
| `ROSTER_EMAIL_WITHOUT_ACCOUNT` | E-mail de pmr/roster que não casa com nenhuma conta | `pmr`, `squad_members` |
| `PMR_UNRESOLVABLE` | pmr sem `user_id`, sem e-mail e sem conta do Jira | a `role_key` da linha |
| `LEAD_USERS` | Contas ativas com `role = LEAD` | `squads_lideranca=N` (0 = perderia todo o acesso) |
| `TRANSVERSAL_LEADERS` | Linhas TRIBE_LEAD/AGILE_COACH/PEOPLE_LEAD com liderança, que expandem para a tribo/segmento | `squads_alcancadas=N` |
| `SQUAD_KEY_CASE_VARIANTS` | `users.squad_id` gravado com caixa/espaço diferentes para a mesma squad | `N variantes` |
| `USER_SQUAD_DEFAULT_DIVERGENT` | `users.squad_id` ≠ `default_project_id` | `default=<chave>` |
| `PMR_ORPHAN_PROJECT` | pmr de `project_id` que não existe em `projects` (não há FK) | `N linhas` |
| `ROSTER_ONLY_SQUAD` | `squad_members` de squad sem linha em `projects` | `N pessoas` |
| `PROJECT_WITHOUT_TRIBE` | `projects.tribe_name` vazio | `sem_tribo` |

Observações:
- `squad_id` que não é projeto (consulta 3.7b do plano) está dentro de `USERS_WITH_SQUAD_WITHOUT_LINK` (rótulo `squad_id_nao_e_projeto`).
- Uma linha pmr que casa com duas contas (por `user_id` e por e-mail) conta para as duas, exatamente como o resolvedor de acesso.
- `count` é exato; `breakdown` mostra até 50 rótulos.

## CSV

Separador `;` (abre direto no Excel em pt-BR), UTF-8 com BOM, quebra `\r\n`. Cabeçalho:

```
tipo;categoria;titulo;total;rotulo_qtd;id;email;squad;detalhe
```

| `tipo` | Preenche |
|---|---|
| `TOTAL` | `categoria` = nome do total, `total` = valor |
| `CATEGORIA` | `categoria` = code, `titulo`, `total` = count |
| `ROTULO` | `categoria` = code, `rotulo_qtd` = contagem, `detalhe` = rótulo |
| `AMOSTRA` | `categoria` = code, `id`, `email`, `squad`, `detalhe` |

Células que começam com `=`, `+`, `-` ou `@` ganham um apóstrofo à frente (proteção contra injeção de fórmula no Excel).

## Como usar na homologação

1. Gere o relatório **antes** de aplicar a V75 (se possível) e depois; compare `pmr_without_user_id` e `pmr_source_BACKFILL`.
2. Trate por grupo: `USERS_WITH_SQUAD_WITHOUT_LINK` e `ROSTER_WITHOUT_LINK` decidem quem perderia acesso na etapa 2;
   `LEAD_USERS` com `squads_lideranca=0` bloqueia a etapa 4; `SQUADS_WITHOUT_APPROVER` bloqueia a etapa 3.
3. A migração V75 não corrige nada disso além do backfill conservador de `user_id` por e-mail único.
