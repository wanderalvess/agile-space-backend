# Checklist de Go-Live — Agile Space

Ambiente de homologação: `https://app.espacoagil.com.br` (Oracle Cloud A1 ARM, 2 OCPU / 12 GB, uma VM com db + backend + frontend + Caddy via `docker compose --profile proxy`).

Legenda: ✅ verificado · ⬜ pendente · ⚠️ decisão/risco aberto · 🔒 exige conta de teste (manual)

---

## 1. Smoke test sem login — executado em 2026-10-06

Rodado de fora, contra o domínio público, sem criar conta nem enviar credenciais reais.

| # | Verificação | Resultado |
|---|---|---|
| ✅ | `http://` redireciona para `https://` | 308 |
| ✅ | Certificado válido (Let's Encrypt), expira 2027-01-04 | renovação automática pelo Caddy |
| ✅ | Headers: HSTS, CSP, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy | presentes |
| ✅ | `/actuator/health` público e sem detalhes | `{"status":"UP"}` |
| ✅ | `/actuator/info`, `/env`, `/metrics`, `/beans` não expostos | 404 |
| ✅ | Swagger UI e `/v3/api-docs` desligados em prod | 404 |
| ✅ | Rotas protegidas sem token (`/api/users`, `/api/admin/*`, `/api/squads`, `/api/workspaces`, `/api/auth/me`) | 401 |
| ✅ | `/api/v1/**` sem `X-Api-Key` | 401 |
| ✅ | WebSocket `/ws/poker/*` e `/ws/retro/*` sem token | 401 |
| ✅ | CORS: origem estranha bloqueada, origem do app permitida | 403 / 200 |
| ✅ | Rate limit de auth (10 req/min por IP) | 429 a partir da 11ª tentativa |
| ✅ | Rate limit não é burlável com `X-Forwarded-For` falso | continua 429 (o Caddy sobrescreve o cabeçalho) |
| ✅ | Erro 404 de API não vaza stack trace | corpo JSON curto |
| ✅ | Banco, backend e frontend só em `127.0.0.1`; só 22/80/443 públicas | confirmado com `ss` |
| ✅ | SSH só por chave, `fail2ban` ativo, atualizações automáticas ligadas | confirmado |
| ✅ | 26 migrations do Flyway aplicadas sem erro | v25 |

Observações do smoke:
- `X-Powered-By: Next.js` e `Via: Caddy` aparecem nos headers. Não é falha, só informação (dá para remover com `poweredByHeader: false` no `next.config.ts`).
- `/mcp/sse` responde 404 pelo domínio público: o Caddy só envia `/api`, `/ws`, `/actuator` e docs ao backend. Se agentes de IA precisarem do servidor MCP em produção, falta uma rota `handle /mcp/*` no `Caddyfile`.
- `/painel` responde 200 sem login: a proteção dessa página é no cliente. Os **dados** estão protegidos (401 na API).

---

## 2. Smoke test autenticado — executado em 2026-10-06

Feito dentro do navegador, com a sessão do usuário ADMIN que já estava logado (nenhuma conta ou senha foi criada por mim).

| # | Verificação | Resultado |
|---|---|---|
| ✅ | Sessão ADMIN reconhecida em `/api/auth/me` | `role = ADMIN` |
| ✅ | Rotas de admin e leitura com token (`/api/admin/api-keys`, `/api/users`, `/api/squads`) | 200 |
| ✅ | WebSocket de poker abre com token válido | conectou |
| ✅ | WebSocket com token inválido é recusado | recusado |
| ✅ | **Tempo real (Brainstorming)**: criado quadro de teste, duas conexões WS no mesmo quadro, uma ideia criada por REST | as **duas** conexões receberam o evento `IDEA_SAVED` |
| ✅ | Limpeza do quadro de teste | apagado (204) |

Limites do que isso prova: foi o mesmo usuário nas duas conexões e o canal foi o de Brainstorming. O mecanismo de broadcast, o proxy e a autenticação do WebSocket estão provados. Poker e Retro usam handlers equivalentes, mas não foram exercitados com ação real.

---

## 2b. Teste funcional pela interface — executado em 2026-10-06

Feito no navegador, com a sessão ADMIN, criando só dados de teste ("SMOKE TEST ... (apagar)").

| # | Fluxo | Resultado |
|---|---|---|
| ✅ | **Poker**: criar sala, adicionar tarefa manual, iniciar refinamento, votar, revelar | funcionou; estimativa calculada (5 h) |
| ✅ | Validação de formulário (título obrigatório na sala) | bloqueia e avisa |
| ✅ | **Retro**: criar quadro, escrever card, revelar cards | funcionou |
| ✅ | Persistência: recarregar a página da retro | card e estado mantidos |
| ✅ | **Chat da retro**: enviar mensagem | enviada e exibida |
| ✅ | Carga sem erro de API: `/painel`, `/admin`, `/retro`, `/room`, `/showcase`, `/health-check`, `/brainstorming`, `/action-plan`, `/workspace`, `/prompt-hub`, `/squad`, `/squad/roster`, `/squad/dashboards`, `/jiradash`, `/governance`, `/changelog`, `/sprint-planner` | todas renderizam |

### Bugs e pontos encontrados

1. 🟡 **Chamada inútil com 400 no chat de conhecimento** (`/knowledge`). A landing gera um slug local de 10 caracteres (`crypto.randomUUID().slice(0, 10)`, [knowledge/chat/page.tsx:11](../agile-space-frontend/src/app/knowledge/chat/page.tsx)) e a página chama `GET /api/knowledge/conversations/{id}`, que exige UUID, então sempre dá `400`. **Não quebra o chat**: o erro cai num `catch`, a sessão abre vazia e a conversa é criada no backend (com UUID real) no primeiro envio. Corrigido no frontend (commitado): não busca quando o id não é UUID. **Deploy feito na VM em 2026-10-06 (frontend `1cdf8b7`) e verificado no navegador: `/knowledge` abre sem chamar `/conversations/{id}` e sem erro 4xx.** O envio de mensagem com IA segue não testado (sem chave de IA na VM). Não verificado em produção; o chat de IA também não foi exercitado (sem chave de IA na VM).
2. 🟠 **Jira acessível da VM?** A sincronização exige domínio Jira e token (PAT). Se o Jira da TOTVS for interno ou atrás de VPN, esta VM na internet pública **não alcança**. Não testado (sem credenciais). Confirmar antes de produção.
3. 🟡 **404 recorrentes no console**: `/api/users/{id}/jira-config` e `/tdn-config` devolvem 404 quando o usuário não configurou. Funciona, mas polui o console e os logs. Melhor devolver 200 vazio ou 204.
4. 🟡 **Requisições com id vazio** ao abrir o painel: `GET /api/squads//rollup`, `/issues`, `/members` (404) disparam antes de o squad ser resolvido. Inofensivo, mas indica carga antes da hora.
5. 🟡 **`GET /api/squads/DDWMISSI` e `/rollup` retornam 404** no squad novo sem Jira conectado. Esperado sem sincronização, mas as telas de squad ficam vazias; confirmar com o Jira conectado.
6. 🟡 **Poker e Retro não têm endpoint para apagar sala/quadro** (só participantes, votos, cards). Salas de teste só saem com a limpeza do banco. Para produção, avaliar se é intencional.
7. 🟡 **Telas lentas na primeira pintura**: `/room`, `/retro` e o modal "Nova sessão" demoram alguns segundos animando o fade-in antes de ficarem clicáveis (observado no navegador embutido, 2 OCPU). Vale medir em máquina comum.

### Fluxo básico: primeiro login, primeira equipe, convidar pessoas

| # | Passo | Resultado |
|---|---|---|
| ✅ | Login ADMIN na produção | funciona (sessão, `/api/auth/me`, token) |
| ✅ | **Criar equipe** (`POST /api/projects`) | cria o projeto, o criador vira **Agile Master (liderança)** e a equipe ativa dele passa a ser a nova |
| ✅ | Chave de equipe duplicada / nome vazio | 409 / 400 |
| ✅ | **Gerar convite por link** (tela do roster) | link `https://app.espacoagil.com.br/invite/<token>`, expira em 7 dias |
| ✅ | Convite em equipe recém-criada | 201 |
| ✅ | **Abrir convite logado e aceitar** | mostra "Squad / Papel", aceita, vai para `/squad` e a pessoa aparece no roster com o papel |
| ✅ | Convite é de uso único | segundo aceite: 400 |
| ✅ | Aceitar sem login / token inválido | 401 / 401 |
| ✅ | Cadastro é 100% local (sem chamada ao Jira) | confirmado no código (`AuthService.register`) |
| ✅ | Pós-login volta ao `/invite/<token>`; `/invite/` está liberada do onboarding obrigatório | 11 testes unitários passam (`auth-routing.test.ts`), incluindo "cadastro com returnUrl" |

**Não executado (precisa de pessoa/conta nova):** a tela `/onboarding` (só aparece para quem não tem equipe) e o cadastro real de alguém convidado. O que se viu: a regra de e-mail `@totvs.com.br` vale para quem for convidado a se cadastrar.

Achados do fluxo:
- ℹ️ `GET /api/squads/{id}/invites` ("listPending") devolve também convites já aceitos (`status: ACCEPTED`), mas o roster **já filtra `PENDING`** no frontend (`squad/roster/page.tsx`). Sem impacto na tela; só o nome do método engana. Não corrigido de propósito: mudar o contrato da API não traz ganho.
- 🟡 Convite aceito vira integrante com o `jiraAccountId` igual ao id do usuário (sem Jira). Esperado, mas quando o Jira for conectado pode duplicar a pessoa. Validar no sync real.
- ℹ️ `GET /api/squads/{id}` retorna 404 até haver sincronização: o "squad" do roster e o "projeto" criado no onboarding são entidades diferentes.

Dados de teste deixados: equipe `SMOKETEST` ("Smoke Test (apagar)") com 1 convite pendente, e o ADMIN agora é integrante Developer da equipe `DDWMISSI` (convite aceito).

Não coberto: Showcase (criação), Brainstorming pela interface, sprint-planner (criação), Jolt, Prompt Hub (criar item), Workspace (criar quadro), importação do Jira, chat de IA (sem chave de IA configurada na VM).

---

## 3. Testes manuais — 🔒 ainda pendentes (precisam de conta de teste ou de outra pessoa)

- ⬜ Cadastro com e-mail `@totvs.com.br` funciona e entra.
- ⬜ Cadastro com `@gmail.com` novo é recusado (esperado 403 "Cadastro restrito...").
- ⬜ Logout e login de novo; token expirado volta ao login.
- ⬜ `/admin` barra usuário comum (só o lado ADMIN foi visto).
- ⬜ Poker e Retro com **dois usuários diferentes**: voto de um aparece para o outro sem recarregar; cards, votos e chat da Retro.
- ⬜ Conexão Jira: salvar token, "Testar Conexão", sync do squad, dados aparecem.
- ⬜ Acesso cruzado: usuário de um squad não lê dados de outro squad.
- ⬜ API Key: criar via admin, usar em `/api/v1/**` com `X-Api-Key`.
- ⬜ Upload/evidência de Showcase (proxy de anexos do Jira).
- ⬜ Reiniciar o stack (`docker compose restart`) e confirmar que sessões e dados persistem.
- ⬜ Teste de carga leve (algumas dezenas de usuários na sala) observando CPU/RAM da VM.

---

## 4. Bloqueadores de produção

- ⬜ **Login real.** SSO da TOTVS pendente (protocolo, discovery URL, client id; o secret vai direto no `.env` da VM). Hoje o login é e-mail e senha.
- ⬜ **Verificação de e-mail no cadastro.** Hoje basta digitar um endereço `@totvs.com.br`: o sistema checa só o texto, não confirma que a pessoa é dona dele. Resolver com SSO, convite ou confirmação por e-mail.
- ⬜ **Backup com restauração testada.** `scripts/backup-db.sh` no cron, cópia para fora da VM (ex.: Object Storage), e um teste de restore em banco descartável. Backup nunca restaurado não conta.
- ⬜ **Banco limpo e segredos novos antes do primeiro dado real.** Os segredos atuais foram usados na homologação. Atenção: `APP_ENCRYPTION_SECRET` cifra os tokens do Jira; trocar depois de existir dado real os deixa ilegíveis. Limpeza completa: `docker compose down -v` (**apaga tudo, irreversível**).
- ⚠️ **Onde hospedar.** A VM está na conta pessoal Oracle e no domínio pessoal. Para dados da TOTVS, decidir com o time se fica aqui ou em conta/infra da empresa (LGPD, segurança da informação, propriedade).
- ⚠️ **`ALLOWED_EMAIL_DOMAIN`** em produção deve ser `totvs.com.br` (hoje na homologação também; confirmar no `.env`).
- ⬜ **Restringir SSH** ao IP do time ou VPN (Security List) e colocar passphrase na chave privada.

## 5. Operação

- ⬜ Monitor externo de disponibilidade (ex.: UptimeRobot) no `https://app.espacoagil.com.br/actuator/health`. O check diário agendado no Claude só roda com o app aberto.
- ⬜ Telemetria: código pronto e desligado. Falta destino OTLP e repassar `OTEL_EXPORTER_OTLP_HEADERS` no compose (hoje não é repassado).
- ⬜ Runbook de deploy e rollback. Hoje é manual: `git pull`, `docker compose --profile proxy up -d --build` (build leva vários minutos). Considerar tags de imagem ou GitHub Actions.
- ⬜ Janela para reboot (atualizações de kernel) e política de atualização.
- ⬜ Ponto único de falha: uma VM, um banco. Definir tempo fora e perda de dados aceitáveis.
- ⬜ Cutover do domínio: o principal (`espacoagil.com.br`) ainda aponta para o legado. Definir data, ordem e plano de volta.
- ⬜ Orçamento: budget "testes" já alerta em 1% e 10% do gasto. Conferir que a VM aparece com custo zero em Cost Analysis (Always Free: máx. 4 OCPU / 24 GB / 200 GB de disco).
- ⬜ Limpar cache de build do Docker quando o disco apertar (`docker builder prune`; hoje ~7,7 GB recuperáveis, disco em 29%).
- ⬜ Opcional: proxy do Cloudflare (WAF/DDoS). Exige ajustar a emissão do certificado no Caddy.
- ⬜ Opcional: `poweredByHeader: false` no frontend.

## 6. Ordem sugerida

1. Decidir hospedagem (item ⚠️ acima), porque pode mudar o resto.
2. Fechar a seção 3 (testes manuais).
3. Backup com restauração testada.
4. SSO da TOTVS (ou convite/confirmação de e-mail).
5. Banco limpo, segredos novos, `ALLOWED_EMAIL_DOMAIN=totvs.com.br`.
6. Restringir SSH, monitor externo, runbook de deploy.
7. Cutover do domínio.
