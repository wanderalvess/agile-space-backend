# Roteiros de teste ao vivo (API + WebSocket)

Scripts usados em 2026-10-09/10 contra um backend local com Postgres real. Servem de **linha de base**: rodando no HEAD atual devem repetir os resultados abaixo, e novos roteiros devem seguir o mesmo formato (`PASS`/`FAIL`, contagem no fim).

O passo a passo completo (subir banco, backend e frontend, e o que testar nas mudanças novas) está em
`agile-space-frontend/docs/fluxos/teste-ao-vivo.md`. Leia primeiro.

## Preparo (uma vez)

```bash
cd scripts/live-tests
echo 'Senha-Teste-123' > testpw.txt        # qualquer senha de 8+ caracteres; só vale no banco de teste
npm install ws                              # só para t_ws.js e t_takeover.js
```

Variáveis opcionais: `API_BASE` (padrão `http://localhost:8002/api`), `WS_BASE` (padrão `ws://localhost:8002`),
`TEST_DB` (padrão `portal_test`, nome do banco usado pelo `psql` via `docker exec agile-space-db`).
O contêiner do Postgres precisa se chamar `agile-space-db` (é o nome do `docker-compose.yml`).

## Várias contas de teste (carga, muitos usuários)

`criar_contas_teste.py` cria N contas `carga.NN@totvs.com.br` e grava e-mail, id e token em `contas-teste.json` (fora do git; tokens valem 24 h). A senha vem de `CARGA_SENHA` ou é perguntada na hora; nunca vai para a linha de comando nem para o arquivo. Rodar de novo só renova os tokens. Respeita o limite de 10 cadastros/logins por minuto por IP (30 contas ≈ 3 min). Servidor que não é local exige `--sim`.

```bash
python criar_contas_teste.py --qtd 30                                                 # backend local (API_BASE)
python criar_contas_teste.py --base https://app.espacoagil.com.br/api --qtd 30 --sim   # homologação
```

Para apagar depois: `delete from users where email like 'carga.%@totvs.com.br'` no banco (veja o cabeçalho do script).

Carga numa sala do Poker com essas contas: `node t_carga_poker.js` (precisa de `npm install ws`). A primeira conta cria a sala, revela e abre a rodada seguinte; as outras entram aos poucos, abrem o WebSocket, mandam heartbeat a cada 25 s como a tela e votam em toda rodada. No fim mostra p50/p95 por chamada, quanto o voto e a revelação levam para chegar aos outros e quantos WebSocket caíram; exclui a sala (use `--manter` para guardar). Opções: `--pessoas`, `--duracao` (s), `--rodada` (s), `--rampa` (s), `--squad`, `--contas`. De uma só máquina o servidor aceita 300 chamadas/min por IP; o script alonga a rodada para caber e avisa se não couber.

```bash
node t_carga_poker.js --pessoas 30 --duracao 300
```

## Ordem e resultado esperado (HEAD de 2026-10-10, antes das mudanças de 2026-10-13)

| Ordem | Comando | Esperado |
|---|---|---|
| 1 | `python t_retro.py` | **61/61** na primeira execução num banco novo (2026-10-30). Cria as contas (admin, Ana AM, Bruno, Carla, Dani), põe Bruno e Carla no roster da ANATIME (o `join` só aceita quem consta no roster) e guarda os tokens em `tokens.json`. Re-execuções dão alguns "FAIL" explicados (ver abaixo). |
| 2 | `python t_misc.py` | **58/58** (Biblioteca de IA, Meu Espaço, perfil/feedback, Jolt logado, Radar de Saúde, Brainstorming) |
| 3 | `python t_poker_review.py` | **24/27** (depois de 2026-10-14; eram 22/27 antes de o servidor travar revelar e limpar); os 5 "FAIL" são expectativas do roteiro ou o limite documentado do Poker (participante revela/limpa votos) e a Review aceitar troca de decisão |
| 4 | `node t_ws.js <boardId>` | **9/9** (use o id de um quadro criado pelo `t_retro.py`) |
| 5 | `node t_takeover.js` | 4 linhas `PASS` (demora ~50 s: espera os 45 s de tolerância) |
| 6 | `python t_new.py` | **78/78** (2026-10-14): refresh/logout, link de redefinição de senha e troca da própria senha, chaves de API com validade, token do Jira escondido do admin, Base de Conhecimento (permissão e versões), anonimato da Retro e do Brainstorming, Poker (só o facilitador revela e limpa), homônimo sem acesso à squad. Troca os tokens de `dani.fora` e `carla.qa` em `tokens.json` (logout e reset invalidam os antigos), então rode depois dos outros. Espera a janela de 10 chamadas por minuto das rotas de autenticação (leva ~2 min). No Windows use `PYTHONIOENCODING=utf-8`. |
| 7 | `python t_v78.py` | **28/28** (2026-10-17): origem do plano de ação (V78: 409 para origem repetida, 403 para quem é de fora da squad, `tasksTotal`/`tasksDone`, prazo da ação) e presença em tempo real (`/api/presence`: 200/401/403/404). Usa `ana`, `bruno`, `dani` e `adm`. |
| 8 | `python t_ceremonias.py` | **36/36** (2026-10-17): encerrar e reabrir a Retro e o Brainstorming (participante comum x facilitador x ADMIN), limite de votos, passar a facilitação e a trava de decisão da Review (dev e QA levam 403; liderança e ADMIN decidem). Usa `ana`, `bruno`, `carla`, `dani` e `adm`. |
| 9 | `node t_review_duas_pessoas.js` | **30/30** (2026-10-17): Review com duas pessoas por WebSocket + REST: presença, apresentador, edição concorrente de card (409 por versão) e trava de decisão. |
| 10 | `node t_retro_duas_pessoas.js` | **54/54** (2026-10-17): Retro com duas pessoas: presença, cartão em tempo real, TYPING (anonimato, 1 evento/2 s, opt-in), nota do check-in (≤500), encerrar/reabrir (403/409), voto com limite por painel e assumir controle (409 com a facilitadora conectada e tolerância de 45 s; **leva ~1 min**). |
| 11 | `OFFLINE_SECONDS=3 node t_brainstorming_duas_pessoas.js` | **62/62** (2026-10-17): Brainstorming: encerrar/reabrir, limite de votos sob concorrência, assumir/passar a facilitação (precisa do backend de teste com `BRAINSTORMING_FACILITATOR_OFFLINE_SECONDS=3`; com o padrão 60 os passos de "offline" são pulados), mural anônimo sem vazar autoria, `restrictEdits` e a marca "editada". |
| 12 | `node t_radar_duas_pessoas.js` | **47/47** (2026-10-17): Radar: "N de M responderam" por BOARD_UPDATED sem vazar voto, `/progress` com `pendingParticipantIds` só para facilitador/ADMIN, encerrar idempotente e apresentação (`PRESENTATION_STAGE` só com `?presentation=1`). |
| 13 | `node t_poker_duas_pessoas.js` | **34/34** (2026-10-17): Poker síncrono com três contas: presença, entrada/saída, voto às cegas (`blindVotes`) sem vazar para quem não é facilitador, revelar/limpar só o facilitador. |
| 14 | `node t_vinculo_duas_pessoas.js` | **41/41** (2026-10-17): Vínculo: 403 para quem é de fora em todas as rotas de squad, pedido de contribuidor aprovado/recusado/cancelado pela liderança, `/api/me/memberships`, autoaprovação 403 e plano com squad alheia 403. **Altera e desfaz o banco de teste** (a Dani vira integrante da ANATIME e é removida no fim). |

Os roteiros 9 a 14 usam `lib_duas_pessoas.js` (chamadas, WebSocket e contagem PASS/FAIL).

"FAIL" esperados nas re-execuções de `t_retro.py`: o roteiro assume banco novo (equipe, papel e linhas de roster já existem;
quem já é membro não muda de papel pelo `join`).

Regras que mudaram de propósito e já estão nos roteiros (2026-10-30): quem recebe o link da Retro lê o quadro, os cards, o chat
geral e abre o WebSocket mesmo sendo de outra squad (2026-10-09); quem cria a equipe recebe o vínculo de liderança (`1003174`);
o `join` exige constar no roster (`72fdba6`); `/jolt/transform` exige login (2026-10).

**Última rodada completa:** 2026-10-30, `develop` em `e65c62d` (V96), banco novo, `APP_MEMBERSHIP_MODE=new` e
`APP_MEMBERSHIP_AUTHZ=new`: todos os 14 nos números da tabela (o 3 em 24/27, com as falhas explicadas acima).

## Cuidados

- `register` e `login` têm limite de **10 por minuto por IP**. Os scripts guardam os tokens em `tokens.json`
  (valem 24 h) para não logar de novo; se levar 429, espere 1 minuto ou reinicie o backend (zera o limitador).
- Tokens e senha de teste **nunca** são versionados (`.gitignore` desta pasta).
- Contas de teste só no banco de teste. Nunca rode contra produção.
