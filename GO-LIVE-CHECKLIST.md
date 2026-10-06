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
