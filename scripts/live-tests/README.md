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

## Ordem e resultado esperado (HEAD de 2026-10-10, antes das mudanças de 2026-10-13)

| Ordem | Comando | Esperado |
|---|---|---|
| 1 | `python t_retro.py` | **59/59** na primeira execução num banco novo. Cria as contas (admin, Ana AM, Bruno, Carla, Dani) e guarda os tokens em `tokens.json`. Re-execuções dão ~5 "FAIL" explicados (ver abaixo). |
| 2 | `python t_misc.py` | **57/57** (Biblioteca de IA, Meu Espaço, perfil/feedback, Jolt, Radar de Saúde, Brainstorming) |
| 3 | `python t_poker_review.py` | **22/27**; os 5 "FAIL" são expectativas do roteiro ou o limite documentado do Poker (participante revela/limpa votos) e a Review aceitar troca de decisão |
| 4 | `node t_ws.js <boardId>` | **9/9** (use o id de um quadro criado pelo `t_retro.py`) |
| 5 | `node t_takeover.js` | 4 linhas `PASS` (demora ~50 s: espera os 45 s de tolerância) |

"FAIL" esperados nas re-execuções de `t_retro.py`: o roteiro assume banco novo (equipe e papel já existem; quem já é
membro não muda de papel pelo `join`) e uma regra mudou de propósito em 2026-10-09 (quem recebe o link da Retro entra mesmo
sendo de outra squad, então "intruso NÃO lê" agora dá 200).

## Cuidados

- `register` e `login` têm limite de **10 por minuto por IP**. Os scripts guardam os tokens em `tokens.json`
  (valem 24 h) para não logar de novo; se levar 429, espere 1 minuto ou reinicie o backend (zera o limitador).
- Tokens e senha de teste **nunca** são versionados (`.gitignore` desta pasta).
- Contas de teste só no banco de teste. Nunca rode contra produção.
