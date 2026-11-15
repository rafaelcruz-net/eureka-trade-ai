# Documentação de APIs — eureka-trader-ai

Inventário dono das rotas REST e dos contratos internos. Componentes em
[component-inventory.md](component-inventory.md); tabelas em [architecture.md](architecture.md#inventário-de-dados).

## Convenções comuns

- **Base**: `/api/v1/`; o processo `api` escuta em 8080 e o actuator em 8081 (`health`, `prometheus`, `info`).
- **Contratos**: OpenAPI 3.1 gerado pelo springdoc e versionado em `apps/api/openapi/openapi-guest.yaml` (grupo
  `guest`: `/api/v1/**` menos `/api/v1/admin/**`) e `openapi-admin.yaml` (grupo `admin`: `/api/v1/admin/**` e
  `/api/v1/legal/**`). Os testes `GuestOpenApiContractTest` e `AdminOpenApiContractTest` comparam o gerado com o
  versionado; os frontends geram `schema.d.ts` e conferem com `check:api`.
- **Autenticação**: `Authorization: Bearer <token de acesso>` (HMAC próprio, 15 min); renovação por cookie HttpOnly
  SameSite=Strict (`/auth/refresh`); administrador por login e cookie próprios (`/api/v1/admin/auth/*`). O papel
  `ADMIN` só passa pela regra de rotas administrativas (`AdminRouteFilter`); um token de convidado em rota
  administrativa é `403 FORBIDDEN_ROLE` com um `REQUEST_FORBIDDEN`.
- **Escopo de dados** (`tenancy/route-registry.yml`): `CLIENT` (dados de uma conta, tenant do token, teste negativo
  obrigatório), `SESSION`, `GLOBAL` (sem dado de cliente) e `ADMIN`.
- **Idempotência**: toda rota que muda estado exige `Idempotency-Key` (UUID), tratada pelo `IdempotencyKeyFilter` e
  pelo `IdempotencyGuard`; mesma chave e corpo devolve a mesma resposta; corpo diferente devolve `409
  IDEMPOTENCY_CONFLICT`.
- **Erros**: RFC 7807 (`ProblemDetail`) com `type` estável por categoria (`https://memetrade.app/problems/<categoria>`),
  `code` legível por máquina e `correlationId`; catálogo em `shared/error/ErrorCode.java` (39 códigos), entre eles
  `REAL_MODE_UNAVAILABLE` (403), `PAUSED_BY_SYSTEM` (409), `DAILY_LOSS_LIMIT_REACHED` (409), `ACTIVE_BOT_LIMIT` (409),
  `VIRTUAL_BALANCE_ABOVE_CAP` (422), `SOURCE_UNAVAILABLE` (503), `TERMS_REACCEPTANCE_REQUIRED` (403), `LOCK_TIMEOUT`
  (503).
- **Corpo**: campos desconhecidos recusados (`fail-on-unknown-properties`); decimais trafegam como texto
  (`DecimalString`), nunca número JSON; valores de mercado como união discriminada por `status` (`KNOWN`/`UNKNOWN`).
- **Modo**: nenhuma rota declara `mode`; `ModeGuardFilter` recusa `mode` diferente de `PAPER` com `403
  REAL_MODE_UNAVAILABLE`.
- **Reaceite dos termos**: sem aceite da versão vigente, o convidado só alcança `/api/v1/legal/`,
  `/api/v1/terms/acceptances`, `/api/v1/account`, `/api/v1/auth/logout`, `/api/v1/auth/refresh` e `/api/v1/privacy/`;
  o resto devolve `403 TERMS_REACCEPTANCE_REQUIRED`.
- **Limites de taxa**: login 10/min por IP, consulta de convite 10/min, scanner 60/min por pessoa e rota, "verificar
  agora" 6/min, documentos legais 60/min, feedback 10/h, pedidos de titular 10/h; até 5 canais ao vivo por pessoa.

## Rotas do convidado (`openapi-guest.yaml`, 32 operações)

| Método | Caminho | Escopo | Módulo | Finalidade |
|---|---|---|---|---|
| POST | `/api/v1/auth/challenge` | GLOBAL | identity | Desafio de login (nonce, 5 min); idempotente até expirar |
| POST | `/api/v1/auth/verify` | GLOBAL | identity | Confere a assinatura, cria conta por convite, abre sessão |
| POST | `/api/v1/auth/refresh` | SESSION | identity | Renova o token pelo cookie, com rotação |
| POST | `/api/v1/auth/logout` | SESSION | identity | Encerra a sessão |
| GET | `/api/v1/invites/{code}` | GLOBAL | identity | Estado do convite e textos vigentes |
| GET | `/api/v1/account` | CLIENT | identity | Conta: carteira mascarada, sessão, termos aceitos, REAL `UNAVAILABLE` |
| POST | `/api/v1/terms/acceptances` | CLIENT | identity | Aceite da versão vigente (reaceite) |
| GET | `/api/v1/legal/current` | GLOBAL | identity | Documentos legais vigentes, sem login |
| GET | `/api/v1/legal/versions` | GLOBAL | identity | Histórico de versões |
| GET | `/api/v1/legal/versions/{version}` | GLOBAL | identity | Uma versão |
| GET | `/api/v1/scanner/tokens` | GLOBAL | scanner | Lista com filtros, ordem e cursor |
| GET | `/api/v1/tokens/{chain}/{mint}/assessment` | GLOBAL | scanner | Avaliação de risco de um token com fatores |
| GET | `/api/v1/strategy-templates` | GLOBAL | bots | Modelos de estratégia v1 |
| GET | `/api/v1/bots` | CLIENT | bots | Bots da pessoa |
| POST | `/api/v1/bots` | CLIENT | bots | Cria rascunho |
| GET | `/api/v1/bots/{botId}` | CLIENT | bots | Bot com rascunho e limites |
| PUT | `/api/v1/bots/{botId}/draft` | CLIENT | bots | Salva um passo do assistente |
| POST | `/api/v1/bots/{botId}/commands/{command}` | CLIENT | bots | `start`, `pause`, `resume`, `stop` (start financia a conta PAPER; stop fecha as posições) |
| POST | `/api/v1/bots/{botId}/limits` | CLIENT | bots | Nova versão de limites |
| GET | `/api/v1/bots/{botId}/decisions` | CLIENT | bots | Decisões explicadas, por cursor |
| GET | `/api/v1/bots/{botId}/rejections` | CLIENT | riskcontrol | Ordens recusadas com motivos |
| GET | `/api/v1/bots/{botId}/trades` | CLIENT | ledger | Ordens e preenchimentos do bot |
| GET | `/api/v1/bots/{botId}/day-usage` | CLIENT | ledger | Uso dos limites no dia UTC |
| GET | `/api/v1/ledger` | CLIENT | ledger | Lançamentos da pessoa, por cursor |
| GET | `/api/v1/pnl` | CLIENT | pnl | P&L de um período (realizado, não realizado ou `UNKNOWN`) |
| GET | `/api/v1/reconciliation` | CLIENT | pnl | Última reconciliação |
| POST | `/api/v1/reconciliation/run` | CLIENT | pnl | "Verificar agora" |
| GET | `/api/v1/stream/dashboard` | CLIENT | bots | Canal ao vivo (SSE lido com `fetch` e `Authorization`) |
| POST | `/api/v1/feedback` | CLIENT | feedback | Comentário de uma tela |
| GET | `/api/v1/privacy/requests` | CLIENT | privacy | Pedidos de titular da pessoa |
| POST | `/api/v1/privacy/requests` | CLIENT | privacy | Novo pedido |
| GET | `/api/v1/privacy/requests/{requestId}` | CLIENT | privacy | Um pedido |

## Rotas do administrador (`openapi-admin.yaml`, 24 operações)

| Método | Caminho | Módulo | Finalidade |
|---|---|---|---|
| POST | `/api/v1/admin/auth/challenge`, `/verify`, `/refresh`, `/logout` | identity | Entrada do administrador (carteira da lista) |
| GET, POST | `/api/v1/admin/invites` | identity | Lista e emite convites (única rota que recebe tenant do cliente) |
| POST | `/api/v1/admin/invites/{inviteId}/revoke` | identity | Revoga convite pendente |
| GET, PUT | `/api/v1/admin/kill-switch` | administration | Lê e muda o kill switch (estado e motivo) |
| GET | `/api/v1/admin/system-status` | administration | Contagens e estado das fontes, sem identificadores |
| GET | `/api/v1/admin/tenants` | administration | Tenants ativos |
| GET | `/api/v1/admin/audit-events` | audit | Trilha de auditoria por cursor (gera `AUDIT_QUERIED`) |
| GET | `/api/v1/admin/feedback` | feedback | Comentários sem pessoa nem carteira |
| GET, POST | `/api/v1/admin/privacy/requests` | privacy | Lista e registra pedido recebido por e-mail |
| GET | `/api/v1/admin/privacy/requests/{requestId}` | privacy | Detalhe com resumo de acesso |
| POST | `/api/v1/admin/privacy/requests/{requestId}/transitions` | privacy | Muda o estado do pedido |
| POST | `/api/v1/admin/privacy/requests/{requestId}/verification` e `/verification/confirm` | privacy | Verificação de identidade por assinatura |
| POST | `/api/v1/admin/privacy/requests/{requestId}/reply-sent` | privacy | Registra a resposta enviada |
| POST | `/api/v1/admin/privacy/requests/{requestId}/anonymization` | privacy | Anonimiza a conta (confirmação `ANONIMIZAR`) |
| GET | `/api/v1/legal/current`, `/versions`, `/versions/{version}` | identity | Documentos legais (compartilhados com o convidado) |

Não há rota REST para os pesos do indicador de risco: `RiskWeightAdministration` existe só como interface Java.
Também não há rota de carteira, depósito, saque, saldo on-chain, cotação ou execução REAL.

## Contratos internos (interfaces Java públicas entre módulos)

O projeto trata as interfaces dos pacotes `*.api` como contratos de esquema compartilhado, ao lado do OpenAPI. As
regras ArchUnit impedem um módulo de importar o que não está em `api` de outro (exceção documentada: `bots` usa
`decision.domain` e `decision.infra`).

| Contrato | Interface | Dono → consumidores | Regras relevantes |
|---|---|---|---|
| C10 | `identity.api.AuthContext`, `Principal(tenantId, userId, role)` | identity → todos | Tenant e pessoa só do contexto; no worker `CycleAuthContext` (ThreadLocal) liga o dono do bot |
| Idempotência | `identity.api.IdempotencyGuard`, `IdempotentOperation` (enum fechado de 28 operações), `IdempotencyScope` | identity → bots, ledger, pnl, administration, feedback, privacy | Escopo pré-login por carteira ou autenticado por tenant/pessoa; retenção `DEFAULT` (24 h), `UNTIL_RESULT_EXPIRES` ou `NOT_STORED` |
| C11 | `marketdata.api.MarketDataQuery` | marketdata → bots, decision, riskcontrol, ledger, pnl | `referencePrice`, `liquidity`, `decimals`, `window`, `sourceStatuses`; ausência é `Unknown`; `stale` acima de 5 min; falha técnica vira `Unknown(SOURCE_UNAVAILABLE)` |
| C12 | `riskassessment.api.RiskAssessmentQuery` | riskassessment → scanner, bots/decision | Indicador indisponível é `NO_TRADE`, nunca risco baixo |
| C13 | `pnl.api.PortfolioRiskView.snapshot(botId, chain, mint, utcDay)` | pnl → riskcontrol, bots | Exposição pelo custo, perda do dia pelos fills, patrimônio vazio quando o preço não é confiável, saldo virtual |
| C14 | `riskcontrol.api.OrderGate.evaluate(CandidateOrder, BotLimitsVersionRef)` | riskcontrol → bots | Exige transação aberta; `Approved` é o único caminho para a execução; `Rejected` com motivos em ordem fixa e gatilhos `DAILY_LOSS`/`DRAWDOWN`; falha técnica lança |
| C15 | `ledger.api.PaperExecution` (`fund`, `execute`, `closePositions`) | ledger → bots | `Propagation.MANDATORY`; recusa é `NotFilled` com motivo; `Duplicate`/`Conflict` por chave; nunca constrói, assina ou envia transação |
| — | `ledger.api.LedgerReadModel` | ledger → pnl, bots | Toda leitura filtra tenant, pessoa e PAPER; `lockAccountForMarking` trava a conta |
| C16 | `bots.api.BotAdministration` | bots → administration, privacy | `applySystemPause`, `liftSystemPause`, contagens, resumo do painel, `systemPaused()` |
| C18 | `marketdata.api.SourceStatusMarks`, `ledger.api.LedgerChangeMarks`, `bots.api.BotChangeMarks` | → canal ao vivo | Marcas que só crescem, lidas a cada 2 s |
| C19 | `marketdata.api.MarketSourceAdapter` | implementações → marketdata | `fetch(FetchRequest)` devolve tokens e observações; campo não informado é `Unknown(NOT_PROVIDED)`; só simuladas no beta |
| C21 | `riskcontrol.api.RejectionReason`, `RejectionCounts` | riskcontrol → web, administration | Ordem dos motivos fixa; contagem sem identificadores |
| J1 a J7 | `identity.api.PersonDataPort`, `AccountAnonymizationPort`, `bots.api.BotOwnerDataPort`, `feedback.api.FeedbackOwnerDataPort`, `ledger.api.PaperOrderCountPort`, `audit.api.AuditEventPublisher`, `shared.retention` | → privacy | Resumo de acesso e anonimização sem ler tabelas de outro módulo |
| Auditoria | `audit.api.AuditEventPublisher`, `AuditActions`, `AuditEventCommand` | audit → todos | Grava na saída na transação de quem chama; `AuditEventValidator` recusa chaves `nonce`, `signature`, `token`, `privatekey`, `seedphrase`, `mnemonic` e valores com JWT, Bearer ou sequência Base58 de 80 a 90 caracteres |

### Eventos de domínio (síncronos, na transação de quem publica)

| Evento | Publicado por | Ouvido por | Efeito |
|---|---|---|---|
| `MarketCollectionCompleted(sourceId, status, completedAt)` | `MarketCollectorJob` | `RiskRecalculator`, `EquityMarkingJob`, `PendingCloseJob` | Recalcula risco, marca patrimônio, conclui fechamentos pendentes |
| `PaperAccountFunded` | `FundingService` | `EquityPeakUpdater` | Cria o pico inicial |
| `FillRecorded` | `FillRecorder` | `EquityPeakUpdater` | Recalcula o pico; falha do ouvinte desfaz o preenchimento |

### Ações de auditoria

`AuditActions` lista 38 ações, entre elas `LOGIN_SUCCEEDED`, `LOGIN_REJECTED`, `REQUEST_FORBIDDEN`,
`TENANT_MISMATCH`, `ACCOUNT_CREATED`, `PAPER_ACCOUNT_FUNDED`, `PAPER_ORDER_FILLED`, `PAPER_ORDER_NOT_FILLED`,
`POSITION_CLOSE_PENDING`, `POSITION_CLOSE_COMPLETED`, `LEDGER_ENTRY_COMPENSATED`, `ORDER_REJECTED`, `BOT_STARTED`,
`BOT_PAUSED`, `BOT_RESUMED`, `BOT_STOPPED`, `BOT_LIMITS_CHANGED`, `BOT_SYSTEM_PAUSE_LIFTED`, `TERMS_ACCEPTED`,
`DATA_REQUEST_*` e `ACCOUNT_ANONYMIZED`; `KILL_SWITCH_ON` e `KILL_SWITCH_OFF` são publicados pelo módulo
`administration`. Não existem ações de execução REAL, depósito, saque ou reconciliação on-chain.

## APIs externas

Nenhuma chamada externa existe em produção. As integrações previstas pelo PRD (§12: descoberta, preços, Solana
RPC/WebSocket, roteador de swap, pagamentos) não têm adaptador; a única porta pronta é `MarketSourceAdapter`. No
navegador, o app usa o Wallet Standard só para `connect` e `solana:signMessage`.
