# Desenho de segurança — U8 Administração e feedback

Base: [requisitos de segurança](../nfr-requirements/security-requirements.md) e [respostas](nfr-design-questions.md) Q3 e Q4.

## Entrada do administrador

- **Rotas.** `AdminAuthController` atende `/api/v1/admin/auth/challenge|verify|refresh|logout`. Ele reutiliza o `ChallengeService`, o verificador Ed25519 nativo e o Base58 próprio da U1 (sem SDK Solana na produção), com um escopo de desafio `ADMIN` próprio. Um desafio do convidado não vale na entrada do administrador, e um do administrador não vale na do convidado.
- **Rotas públicas (R-02).** `challenge`, `verify` e `refresh` em `/api/v1/admin/auth/` funcionam sem token de acesso. A U8 acrescenta essas três rotas à lista de rotas públicas do `AuthenticationFilter` e à lista de exceções do `AdminRouteFilter`. As duas listas usam o caminho normalizado do `RequestPaths`, e um teste confere as duas. `logout` continua exigindo token ADMIN. O filtro que recusa token ADMIN fora de `/api/v1/admin/...` já existe (`AdminAuthorization`, U4) e não é recriado.
- **Carteira, sem revelar a lista (R-05).** O `challenge` responde 200 com um desafio para qualquer endereço válido, inclusive os que não estão na lista. Assim, a resposta não revela quais endereços são de administrador. A recusa acontece no `verify`, sempre com a mesma resposta `403 FORBIDDEN_ROLE`, para endereço fora da lista e para carteira já usada por um BETA_USER. O motivo exato (`NOT_ADMIN_WALLET` ou `WALLET_IS_GUEST`) vai só para o evento `LOGIN_REJECTED`. Isso refina a BR1.1 e a BR1.2 do desenho funcional sem mudar o resultado: nenhuma sessão é aberta para quem não está na lista.
- **Sessão.** Fica na tabela `user_session` com `role = ADMIN`:
  - token de acesso de 15 min e sessão de 8 h sem prorrogação;
  - cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/admin/auth/refresh` (R-07), com rotação a cada uso;
  - um cookie reutilizado revoga a sessão.
- **Carteira retirada da lista.** A conferência acontece no `verify` e em cada `refresh`.
- **Limite de tentativas (Q4).** O `AdminLoginLimiter` aplica dois limites:
  - **Por IP:** 5 pedidos de `challenge` e de `verify` por minuto.
  - **Por endereço:** 5 por minuto, contado por par (endereço, IP). Assim, alguém que só conhece o endereço do administrador não consegue travar a entrada dele a partir de outro IP (R-05).

  Acima de qualquer limite, a resposta é `429 RATE_LIMITED` com `Retry-After`, e um `LOGIN_REJECTED` é gravado com o motivo `RATE_LIMITED`. A expiração usa a mesma rotina de limpeza do limitador da U1.

## Separação de papéis (NFR5.2, NFR6.3)

- O `AdminRouteFilter` (U3) já recusa token não ADMIN em `/api/v1/admin/...`, menos as três rotas públicas de entrada. O `AdminAuthorization` (U4) já recusa token ADMIN fora de `/api/v1/admin/...`. Os dois usam o caminho normalizado do `RequestPaths` (sem `;`, `..` ou barras duplas). Um único `REQUEST_FORBIDDEN` é gravado por recusa.

## CORS do app do administrador (R-04)

- O app do administrador (`http://localhost:3001` no ambiente local, definido por `ADMIN_APP_ORIGIN`) chama a API em outra origem. A U8 cria uma `AdminCorsPolicy` só para `/api/v1/admin/**`:
  - aceita só a origem `ADMIN_APP_ORIGIN`;
  - métodos `GET`, `POST`, `PUT` e `OPTIONS`;
  - cabeçalhos `Authorization`, `Content-Type`, `Idempotency-Key` e `X-Correlation-Id`;
  - `allowCredentials = true` só em `/api/v1/admin/auth/refresh` e `/logout`, que usam o cookie.
- A `GuestCorsPolicy` não muda: ela não aceita a origem do administrador, e a política administrativa não aceita a origem do convidado. Um teste confere o preflight do `PUT` do kill switch, a recusa de outras origens e as credenciais só nas duas rotas.
- `ADMIN_APP_ORIGIN` entra no `.env.example` (sem segredo) e no guarda de configuração do processo de API, que recusa curinga.
- `AdminGuestRoutesIT` percorre o registro de rotas do convidado com token ADMIN e espera 403 em todas.
- ArchUnit: os pacotes `administration` e `feedback` não dependem de `ledger`, `paper`, `riskcontrol` (exceto a interface pública de contagem) nem de repositórios de outros módulos.

## App do administrador (NFR6.4, NFR6.5, Q3)

- **Serviço próprio.** `apps/admin` roda como serviço `admin` (porta 3001 em `127.0.0.1`), no mesmo site da API.
- **Cabeçalhos de segurança.** O middleware gera um nonce aleatório de 128 bits por requisição e define:
  - `Content-Security-Policy: default-src 'self'; script-src 'self' 'nonce-<n>' 'strict-dynamic'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' <origem da API>; frame-ancestors 'none'; base-uri 'none'; object-src 'none'; form-action 'self'`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: no-referrer`
  - `Permissions-Policy` restritiva
- **Renderização.** As páginas são renderizadas por requisição (sem exportação estática). Um teste confere os cabeçalhos e que a página hidrata com a política ativa.
- **Limitações aceitas (R-07):**
  - `style-src 'unsafe-inline'` fica porque o Next.js injeta estilos inline. Estilo não executa script, e `script-src` continua com nonce.
  - O cookie `Secure` em HTTP local funciona em `localhost` no Chrome e no Firefox, que tratam `localhost` como contexto seguro. Outros navegadores não são suportados no ambiente local do beta. Com hospedagem, tudo passa a HTTPS.
- **Convidado sem código administrativo.** `GuestBundleTest` (CI) procura `/api/v1/admin` e `openapi-admin` em `apps/web/.next` depois do build e falha se achar.

## Dados sensíveis (NFR6.7, NFR7.1, NFR7.2)

- **Feedback.** O texto só passa pelo DTO e pelo repositório. Os logs do módulo `feedback` registram só `screen`, `length` e `correlationId`. Um teste de varredura envia um feedback com um marcador único e procura o marcador nos logs capturados.
- **Kill switch.** O motivo vai só para o histórico e para o evento; nunca para log.
- **Carteira.** O `changedBy` da resposta do kill switch é mascarado (4 + 4).
- **Varredura geral.** A varredura de logs do `verify.sh` (já existente) cobre o serviço `admin`.

## Isolamento e integridade

| ID | Desenho |
|---|---|
| NFR4.1 | Gatilho `BEFORE UPDATE OR DELETE` em `kill_switch_change` que lança exceção. Sem `UPDATE` nem `DELETE` concedidos ao papel da aplicação. Teste com o papel da aplicação |
| NFR4.2 | Mecanismo comum de idempotência da identidade (project.md). Só respostas 201 são guardadas. 400 e 429 não são |
| NFR4.3 | `SELECT ... FOR UPDATE` na linha `app_user` da pessoa, dentro da transação do envio, antes da contagem |
| NFR5.1 | `tenantId` e `userId` do `Principal`. O DTO de leitura do administrador não tem os campos `userId` nem carteira (teste de serialização) |

## Acessibilidade (NFR11.1, R-05)

O tema escuro do app do administrador usa os tokens do `apps/web`, com contraste de texto ≥ 4,5:1. Um teste de componente confere o contraste das cores usadas em texto, e o estado é sempre dito em texto ou ícone.
