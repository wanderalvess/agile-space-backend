# Limite de requisições (RateLimitFilter)

Balde em memória (Bucket4j), janela de 1 minuto, instância única. Ordem de decisão:

1. `/api/auth/login|register|forgot-password|reset-password|change-password`: por IP, faixa apertada.
2. `/api/public/invites/**` (leitura pública do convite): por IP, balde próprio.
3. Demais `/api/**` com Bearer JWT **válido** (assinatura e validade conferidas): por ID do usuário (`sub`).
4. Demais `/api/**` sem token válido (inclusive token forjado): por IP.

WebSocket (`/ws/**`) e `OPTIONS` não passam pelo filtro. O IP vem de `X-Forwarded-For` só se o proxy estiver em `TRUSTED_PROXIES`.

| Propriedade | Variável | Padrão |
|---|---|---|
| `app.ratelimit.user-per-minute` | `RATELIMIT_USER_PER_MINUTE` | 300 |
| `app.ratelimit.per-user-enabled` | `RATELIMIT_PER_USER_ENABLED` | true (false = tudo por IP, como antes) |
| `app.ratelimit.general-per-ip-per-minute` | `RATELIMIT_GENERAL_PER_IP_PER_MINUTE` | 300 |
| `app.ratelimit.auth-per-ip-per-minute` | `RATELIMIT_AUTH_PER_IP_PER_MINUTE` | 10 |
| `app.ratelimit.public-invite-per-ip-per-minute` | `RATELIMIT_PUBLIC_INVITE_PER_IP_PER_MINUTE` | 1200 (folgado por causa de NAT) |

Resposta ao exceder: HTTP 429, cabeçalho `Retry-After` e `{"error": "Muitas requisições. Tente de novo em N s.", "message": "<mesmo texto>"}`. As duas chaves trazem o mesmo texto: `message` é a que o resto da API usa, e `error` fica por compatibilidade.

O balde é de recarga contínua (300/min = 5 por segundo), não uma janela fixa: passar um pouco do limite bloqueia por cerca de 1 s, e o login (10/min) por 5 a 6 s.

Riscos: contadores zeram ao reiniciar e não são compartilhados entre instâncias (um deploy horizontal exigiria limitador distribuído); os mapas de baldes (por IP e por usuário) não expiram entradas.
