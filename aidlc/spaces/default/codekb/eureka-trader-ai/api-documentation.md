# Documentação das APIs — eureka-trader-ai

## Superfícies

| Superfície | Fonte da verdade | Consumidor |
|---|---|---|
| REST do convidado `/api/v1/**` (sem `/admin`) | `apps/api/openapi/openapi-guest.yaml` (OpenAPI 3.1 gerado pelo springdoc, grupo `guest`) | `apps/web` (tipos gerados em `src/lib/api/schema.d.ts`) |
| REST administrativa `/api/v1/admin/**` | `apps/api/openapi/openapi-admin.yaml` (grupo `admin`) | `apps/admin` |
| Interfaces Java públicas entre módulos (`com.memetrade.*.api`) | Código | Outros módulos do backend |
| Registro de escopo de dados | `apps/api/src/main/resources/tenancy/route-registry.yml` | Testes `RouteRegistryTest`, `TenantFromClientScanTest` |
| Gestão (porta 8081) | Actuator: `/actuator/health` (liveness, readiness), `/actuator/prometheus`, `/actuator/info` | Docker healthcheck, operação |

Anexos humanos por Unit: `docs/api/*.md` (C2 scanner, C8 auditoria administrativa, U3 convites e sessões, U5 bots,
U6 barreira, U7 ledger e P&L, U8 administração e feedback).

## Convenções comuns

- **Autenticação**: `Authorization: Bearer <token de acesso>` (HMAC, 15 min, só na memória do app). Renovação por
  cookie HttpOnly, Secure, SameSite=Strict: `mt_refresh` (convidado) e `mt_admin_refresh` (administrador). Pessoa e
  tenant vêm sempre do token.
- **Idempotência**: escritas sensíveis exigem `Idempotency-Key` (UUID); mesma chave e mesmo conteúdo devolvem a
  resposta guardada; mesma chave com outro conteúdo devolve `409 IDEMPOTENCY_CONFLICT`.
- **Erros**: RFC 7807 (`application/problem+json`) com `type` estável por categoria, `code` legível por máquina,
  `correlationId`, `violations[]` em validação; nunca stack trace, SQL ou segredo. Códigos em
  `shared/error/ErrorCode.java` (por exemplo `VALIDATION_FAILED`, `UNAUTHENTICATED`, `FORBIDDEN_ROLE`,
  `REAL_MODE_UNAVAILABLE`, `NOT_FOUND`, `IDEMPOTENCY_CONFLICT`, `RATE_LIMITED`, `INVITE_NOT_FOUND`, `INVITE_INVALID`,
  `TERMS_NOT_ACCEPTED`, `WALLET_ALREADY_LINKED`, `INVALID_TRANSITION`, `ACTIVE_BOT_LIMIT`, `PAUSED_BY_SYSTEM`).
- **JSON**: campos não declarados são recusados (`fail-on-unknown-properties`); instantes ISO-8601 em UTC; valores
  decimais como texto; enums em maiúsculas.
- **Paginação**: cursor opaco de tupla, sem `OFFSET`.
- **Escopos** (registro de rotas): `CLIENT` (dados de um tenant, exige teste negativo entre tenants), `SESSION`
  (só a própria sessão), `GLOBAL` (sem dado de cliente), `ADMIN` (só `Role.ADMIN`, exige teste de 403 para
  convidado).

## Inventário de endpoints do convidado (25)

| Método e caminho | Escopo | Uso |
|---|---|---|
| `POST /api/v1/auth/challenge` | GLOBAL | Desafio de login para uma carteira (idempotente por carteira + operação) |
| `POST /api/v1/auth/verify` | GLOBAL | Verifica a assinatura; com `inviteCode` e `acceptedTermsVersion` cria a conta |
| `POST /api/v1/auth/refresh` | SESSION | Renova o token pelo cookie, com rotação e detecção de reuso |
| `POST /api/v1/auth/logout` | SESSION | Encerra a sessão |
| `GET /api/v1/invites/{code}` | GLOBAL | Consulta pública do convite: estado, validade, versão e textos vigentes dos termos e do aviso de risco; limite de 10 por IP e minuto |
| `GET /api/v1/account` | CLIENT | Tela Conta: carteira mascarada, provedor, fim da sessão, termos aceitos (versão, horário, textos), `realMode: UNAVAILABLE`; `no-store` |
| `GET /api/v1/scanner/tokens` | GLOBAL | Lista do scanner com filtros e `unknown` explícito; limite por pessoa e rota |
| `GET /api/v1/tokens/{chain}/{mint}/assessment` | GLOBAL | Avaliação de risco de um token |
| `GET /api/v1/strategy-templates` | GLOBAL | Modelos de estratégia |
| `GET /api/v1/bots` | CLIENT | Bots da pessoa |
| `POST /api/v1/bots` | CLIENT | Cria rascunho de bot |
| `GET /api/v1/bots/{botId}` | CLIENT | Detalhe do bot |
| `PUT /api/v1/bots/{botId}/draft` | CLIENT | Atualiza o rascunho |
| `POST /api/v1/bots/{botId}/commands/{command}` | CLIENT | Iniciar, pausar, retomar, parar |
| `POST /api/v1/bots/{botId}/limits` | CLIENT | Nova versão dos limites |
| `GET /api/v1/bots/{botId}/decisions` | CLIENT | Decisões explicadas |
| `GET /api/v1/bots/{botId}/rejections` | CLIENT | Ordens recusadas pela barreira |
| `GET /api/v1/bots/{botId}/trades` | CLIENT | Operações PAPER do bot |
| `GET /api/v1/bots/{botId}/day-usage` | CLIENT | Uso dos limites no dia UTC |
| `GET /api/v1/ledger` | CLIENT | Lançamentos do ledger PAPER |
| `GET /api/v1/pnl` | CLIENT | P&L por período |
| `GET /api/v1/reconciliation` | CLIENT | Última reconciliação |
| `POST /api/v1/reconciliation/run` | CLIENT | Reconciliação sob demanda (6 por pessoa e minuto) |
| `POST /api/v1/feedback` | CLIENT | Comentário (1 a 2000 caracteres, tela S1 a S9, 10 por hora); tenant no corpo é recusado |
| `GET /api/v1/stream/dashboard` | CLIENT | Canal ao vivo `text/event-stream` (até 5 por pessoa; o app lê com `fetch`, não `EventSource`) |

## Inventário de endpoints administrativos (13)

| Método e caminho | Uso |
|---|---|
| `POST /api/v1/admin/auth/challenge` | Desafio para qualquer endereço válido (a resposta não revela se é admin) |
| `POST /api/v1/admin/auth/verify` | Entrada do administrador (carteira na lista `ADMIN_WALLET_ADDRESSES`); cria a conta ADMIN na primeira vez |
| `POST /api/v1/admin/auth/refresh` | Renova pelo cookie `mt_admin_refresh` (sessão de 8 h sem extensão) |
| `POST /api/v1/admin/auth/logout` | Encerra a sessão administrativa |
| `GET /api/v1/admin/invites` | Lista convites por cursor |
| `POST /api/v1/admin/invites` | Emite convite (única rota que recebe tenant do cliente, `tenantFromClient: true`) |
| `POST /api/v1/admin/invites/{inviteId}/revoke` | Revoga convite PENDING |
| `GET /api/v1/admin/audit-events` | Consulta a trilha (filtros e cursor); cada consulta gera `AUDIT_QUERIED`; `details` pode conter o endereço completo |
| `GET /api/v1/admin/kill-switch` | Estado do kill switch |
| `PUT /api/v1/admin/kill-switch` | Liga/desliga com motivo (pausa todos os bots) |
| `GET /api/v1/admin/system-status` | Estado do sistema e contagens |
| `GET /api/v1/admin/tenants` | Tenants ativos (para emitir convite) |
| `GET /api/v1/admin/feedback` | Comentários do mais novo ao mais antigo, com tenant e tela, sem pessoa nem carteira |

Não existe nenhuma rota de titular de dados (exportação, correção, exclusão, anonimização), de política de
privacidade, de nova versão de termos nem de reaceite.

## Contratos internos principais (pacotes `api`)

| Interface | Módulo dono | Usada por |
|---|---|---|
| `AuditEventPublisher`, `AuditEventCommand`, `AuditActions`, `AuditBacklog` | audit | identity, ledger, riskcontrol, bots, pnl, administration, feedback |
| `IdempotencyGuard`, `IdempotencyScope`, `IdempotentOperation`, `Principal`, `Role`, `AuthContext`, `AdminAccess`, `IdentityAdministration`, `IdentityCounts` | identity | todos os módulos com escrita autenticada; administration, feedback |
| `MarketDataQuery`, `MarketListingQuery`, `MarketRiskInputs`, `MarketSourceAdapter`, `MarketValue` | marketdata | scanner, riskassessment, bots, ledger, riskcontrol |
| `RiskAssessmentQuery`, `RiskWeightAdministration` | riskassessment | scanner, bots |
| `OrderGate`, `CandidateOrder`, `GateResult`, `RejectionCounts` | riskcontrol | bots worker |
| `PaperExecution`, `LedgerReadModel`, `LedgerJobs`, `LedgerChangeMarks` | ledger | bots, pnl |
| `PortfolioRiskView` | pnl | riskcontrol |
| `BotAdministration`, `BotChangeMarks` | bots | administration, dashboard |

## Eventos de auditoria (contrato C9)

Nomes em `audit/api/AuditActions.java` mais `FEEDBACK_PURGED` (em `FeedbackPurgeJob`) e eventos de U8. Eventos com
endereço completo em `details`: `ACCOUNT_CREATED`, `LOGIN_SUCCEEDED`, `LOGIN_REJECTED` (convidado e administrador).
`details` recusa as chaves `nonce`, `signature`, `token`, `accesstoken`, `refreshtoken`, `authorization`, `password`,
`secret`, `privatekey`, `seedphrase`, `mnemonic` e valores com JWT, Bearer ou assinatura Base58 de 64 bytes.
