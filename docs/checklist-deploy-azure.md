# Checklist de deploy no Azure — Portal Tech V&D

Estado de referência (2026-10-23): a `develop` do Azure espelha a homologação (backend `f9da9cc8` = GitHub `4aa7104`; frontend `ca460925` = GitHub `ca07dd8e`). A `main` do Azure (backend `5efa9764`, frontend `c43951d1`) ainda não recebeu essas mudanças. A checagem de merge entre `develop` e `main` não mostrou conflito nos dois repositórios.

## Antes do deploy

1. Abrir os PRs `develop` → `main` nos dois repositórios. Usar "Merge sem fast-forward" ou "Rebase e FF". Nunca squash.
2. Fazer dump do banco.
3. Comparar o `.env` da homologação (sem segredos) com o do Azure. Ninguém comparou o `.env.example` do repositório do Azure arquivo a arquivo.

## Banco (Flyway)

- Aplica a V83 (fora de ordem) e as V84 a V93. Todas são aditivas.
- A V90 faz um UPDATE de preenchimento só na coluna nova `requester_last_read_at`. Nada apaga dado.
- `out-of-order=true` está no `application.yml`.
- Versão final esperada: 93. Depois da partida, conferir 0 ERROR no log do backend.

## Proxy (Caddy)

- O Caddyfile novo tem `handle /mcp/*` (com `flush_interval -1`) antes de `/api/*`.
- Depois do `git pull`, **reiniciar** o serviço do proxy. Recarregar não basta, porque o bind mount perde o inode. Na VM Oracle o serviço se chama `reverse-proxy`: `docker compose --profile proxy restart reverse-proxy`.
- Conferir: `curl -i https://<domínio>/mcp/sse` sem chave deve dar 401 do Spring ("Chave de API ausente").

## Memória e imagem

- `FRONTEND_MEMORY_LIMIT=2048M`; backend com 1024M.
- A imagem do frontend é `node:20-slim` (precisa de glibc para o onnxruntime). O build baixa da internet o modelo de embedding (cerca de 465 MB em `/app/.cache/transformers-cache`).

## Variáveis

- `TRUSTED_PROXIES` (por exemplo `172.16.0.0/12` para o proxy no Docker).
- `APP_MEMBERSHIP_MODE=new` e `APP_MEMBERSHIP_AUTHZ=new`.
- Rate limit por usuário: padrões 300/min e 10/min por IP na autenticação (`app.ratelimit.*`, ver `docs/rate-limit.md`). Atrás de NAT, considerar `RATELIMIT_PUBLIC_INVITE_PER_IP_PER_MINUTE=1200`.
- `/api/jolt/*` exige login. O MCP em `/mcp/sse` exige o cabeçalho `X-Api-Key`.

## Disco

- Cada rebuild consome 1 a 2 GB de cache. `docker builder prune -af` libera esse espaço sem mexer nas imagens de rollback.
- Guardar a imagem de rollback anterior antes de subir.

## Conferência depois do deploy

- 4 containers saudáveis, 0 ERROR no backend, Flyway em 93.
- 200 em `/login`, `/squad`, `/support`, `/manual`, `/devtools`, `/prompt-hub`, `/jolt`, `/changelog`, `/governance`, `/knowledge/kb`, `/room`, `/retro`.
- 401 em `/api/home` e em `POST /api/jolt/transform` sem token.
- `GET /api/public/prompt-hub/collections?page=0&size=200` sem token deve dar 200.

## Rollback

Voltar o git dos dois clones aos hashes anteriores, restaurar o dump se as migrações já tiverem rodado e refazer o build.
