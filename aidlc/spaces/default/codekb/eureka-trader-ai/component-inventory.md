# Inventário de componentes — eureka-trader-ai

Cada título abaixo é o nome canônico do componente (usado também no bloco Scope of Analysis). Dependências em
[dependencies.md](dependencies.md); rotas e contratos em [api-documentation.md](api-documentation.md); tabelas em
[architecture.md](architecture.md#inventário-de-dados). "Profundidade" registra quanto esta varredura leu de cada um.

Atualização de 2026-11-21: a varredura focada do intent `261121-paper-real-unico` releu a fundo `bots`, `ledger`,
`pnl`, `realexecution`, `realexits`, `web-bots`, `web-real`, `web-dashboard` e `web-pnl-route`. As demais entradas
descrevem o estado de 2026-11-13. Existem hoje componentes que este inventário ainda não descreve: o módulo `botwallet`
(carteira do bot, depósito, saque, liberação, reconciliação com a rede), o subpacote `riskcontrol/real` (barreira REAL
`RealRiskGate`) e o app `apps/txsigner` (assinatura isolada com o KMS).

## Backend (`apps/api`)

### shared
- **Local**: `apps/api/src/main/java/com/memetrade/shared/`
- **Responsabilidade**: relógio injetável (`ClockConfig`); erros RFC 7807 (`ApiException`, `ErrorCode`,
  `ProblemDetailsHandler`); filtros comuns (`CorrelationIdFilter`, `SecurityHeadersFilter`, `ModeGuardFilter`);
  cursor de tupla (`KeysetCursor`); limitador por pessoa e rota; máscara de carteira (`WalletAddress`) e de logs
  (`LogMasking`, `Base58AddressValueMasker`); guarda de configuração na subida (`RuntimeSettingsGuard`,
  `LoadtestProfileGuard`); seleção de processo (`ProcessRole`, `ProcessRoleCondition`); declaração de retenção
  (`shared/retention`); saúde e rodadas de jobs.
- **Relevância para o Trading REAL**: `ModeGuardFilter` e `REAL_MODE_UNAVAILABLE` são o bloqueio HTTP do modo REAL.
- **Profundidade**: profunda nos filtros, erros e processo; superficial no restante.

### identity
- **Local**: `apps/api/src/main/java/com/memetrade/identity/`
- **Responsabilidade**: documentos legais e termos versionados (`TermsCatalog`, `CurrentTerms`, `LegalCatalogReader`),
  reaceite (`TermsReacceptanceFilter`), convites, criação de conta por convite, login por assinatura Ed25519 nativa
  (convidado e administrador), sessões com rotação, tela Conta, idempotência comum (`IdempotencyService`,
  `IdempotencyGuard`), limites de taxa, autenticação e autorização administrativa, expurgos de desafios e sessões,
  portas de dados pessoais e de anonimização.
- **Relevância para o Trading REAL**: a carteira de login (`wallet`, uma por conta, endereço único) é a candidata a
  "carteira principal" de saque; a idempotência é obrigatória em toda Unit nova; a mensagem de login fixa
  `solana:mainnet`.
- **Profundidade**: profunda na interface pública e na assinatura; superficial no domínio, infra e web.

### audit
- **Local**: `apps/api/src/main/java/com/memetrade/audit/`
- **Responsabilidade**: publicação validada na saída (`JdbcAuditEventPublisher`, `AuditEventValidator`), relay para a
  trilha (`AuditOutboxRelay`, 1 s), carteira trocada por HMAC (`WalletHasher`), consulta administrativa, expurgo de 12
  meses pelo pool de retenção, limpeza da saída, recuperação na subida e saúde dos jobs.
- **Relevância para o Trading REAL**: o validador recusa a chave `signature` e sequências Base58 de 80 a 90
  caracteres (assinatura de transação) e trata palavras Base58 de 32 bytes como carteira.
- **Profundidade**: profunda no validador e nas ações; superficial no restante.

### marketdata
- **Local**: `apps/api/src/main/java/com/memetrade/marketdata/`
- **Responsabilidade**: porta de fonte (`MarketSourceAdapter`, C19) com duas fontes simuladas determinísticas
  (`infra/simulated/SimulatedMarketSource`, `sim-a` e `sim-b`, mints sintéticos); coleta por fonte com limite de 5 s e
  até 3 tentativas fora da transação (`SourceCaller`, Resilience4j); validação; resolução do valor exibido (o KNOWN mais
  recente de fonte não FAILED) com divergência por tolerância; histórico de 5 min; leitura C11 (`MarketDataQuery`), da
  lista do scanner e das entradas do risco; limpeza horária; marcas de estado das fontes.
- **Relevância para o Trading REAL**: ponto de entrada de um adaptador de preço real; hoje `KNOWN_SOURCES` aceita só
  `sim-a` e `sim-b` e ArchUnit proíbe `java.net` no módulo.
- **Profundidade**: profunda.

### scanner
- **Local**: `apps/api/src/main/java/com/memetrade/scanner/`
- **Responsabilidade**: lista do scanner com filtros, ordenação e cursor de foto, detalhe da avaliação de um token,
  limitador por pessoa e rota; responde `503 SOURCE_UNAVAILABLE` quando todas as fontes falham.
- **Profundidade**: superficial.

### riskassessment
- **Local**: `apps/api/src/main/java/com/memetrade/riskassessment/`
- **Responsabilidade**: indicador de risco 0 a 100 por fatores com curvas e pesos versionados (`RiskCalculator`,
  `WeightSet`), recálculo após cada coleta (`RiskRecalculator`), leitura C12, administração de versões de pesos
  (`RiskWeightAdministration`, sem rota REST).
- **Profundidade**: superficial.

### riskcontrol
- **Local**: `apps/api/src/main/java/com/memetrade/riskcontrol/`
- **Responsabilidade**: barreira obrigatória de ordens (`OrderGate`, C14; `OrderGateService`, `LimitChecks`,
  `SlippageEstimate`) que falha fechada; motivos em ordem fixa (`ORDER_SIZE`, `TOKEN_EXPOSURE`, `TOTAL_EXPOSURE`,
  `SLIPPAGE`, `MIN_LIQUIDITY`, `STALE_DATA`, `INTERVAL`, `DUPLICATE`, `INSUFFICIENT_PAPER_BALANCE`, `DAILY_LOSS`,
  `DRAWDOWN`); gatilhos de pausa; registro de avaliações e recusas; consulta de recusas; contagem para o administrador;
  expurgo diário das avaliações aprovadas antigas pelo papel `risk_retention`.
- **Relevância para o Trading REAL**: todas as verificações do PRD §9 por ordem já existem, mas leem o ledger PAPER e
  estimam o slippage pela política simulada.
- **Profundidade**: profunda.

### bots
- **Local**: `apps/api/src/main/java/com/memetrade/bots/`
- **Responsabilidade**: bots PAPER (assistente, validação, máquina de estados `DRAFT` → `VALIDATED` → `ACTIVE` ↔
  `PAUSED` → `CLOSING_PENDING` → `STOPPED`), limites versionados imutáveis, motivos de pausa (`PERSON`, `DAILY_LOSS`,
  `DRAWDOWN`, `SYSTEM`, `ADMIN_RELEASED`, `WAITING_ACTIVE_SLOT`), comandos idempotentes, canal ao vivo
  (`DashboardStreamController`, `DashboardMarkPoller`), pausa do sistema (C16) e, no processo worker, o ciclo de 5 min
  (`SlotDispatcher`, `CycleRunner`, `CycleAuthContext`), meia-noite UTC, verificação de fechamento e expurgo de
  decisões.
- **Relevância para o Trading REAL**: o ciclo é o orquestrador de decisão, barreira e execução, hoje numa única
  transação de banco.
- **REAL (2026-11-21)**: `bot.market` (`BotMarket`: `PAPER` ou `REAL`, fixo desde a criação); criação REAL exige
  carteira do bot ATIVA; bot REAL não financia conta PAPER nem fecha posições ao parar; no worker, `RealCycle` lê o saldo
  e as posições da carteira do bot inteira (M1 e M2) e `RealDecisionStep` passa pela barreira REAL e grava a decisão no
  M6 na transação do ciclo; a venda usa a quantidade inteira do mint na carteira; `RealBotOwnership` (api) responde ao
  M6 se o bot é REAL, ATIVO e da pessoa; `BotSummary` traz `mode` fixo `PAPER` e `market`.
- **Relevância para o intent `261121-paper-real-unico`**: a lista de bots já devolve os dois modos; o ciclo REAL é o
  ponto onde "vender só a própria quantidade" muda.
- **Profundidade**: profunda (2026-11-21) no ramo REAL do ciclo, nos comandos, no modelo e nas rotas; superficial no
  canal ao vivo, nos repositórios e na carga.

### decision
- **Local**: `apps/api/src/main/java/com/memetrade/decision/`
- **Responsabilidade**: motor de decisão puro e determinístico, sem IA (`DecisionEngine`, `MomentumVolumeStrategy`,
  `RangeBreakoutStrategy`, `ExitRules`, `OrderSizer`, `TokenSelectionResolver`), texto da decisão em pt-BR,
  persistência da decisão explicada (`JdbcDecisionRepository`, `DecisionJson`).
- **Profundidade**: superficial (lido por classe).

### ledger
- **Local**: `apps/api/src/main/java/com/memetrade/ledger/`
- **Responsabilidade**: execução PAPER (C15: `PaperExecutionImpl`, `FundingService`, `ClosingService`), cálculo puro do
  preenchimento (`FillCalculator`, política `SimulationPolicy` v1, `Scales`), gravação conjunta do preenchimento
  (`FillRecorder`, `LedgerEntries`, `PositionAccounting`), fechamento pendente (`PendingCloseJob`), compensação,
  leitura pública (`LedgerReadModel`), rotas de leitura, marcas de mudança, contagem de ordens para a privacidade.
- **Relevância para o Trading REAL**: modelo de ledger append-only e chave única com `mode` reaproveitáveis; todas as
  tabelas aceitam só `PAPER`. (Estado de 2026-11-13.)
- **REAL (2026-11-21)**: ledger REAL próprio (M2: `RealLedger`, `JdbcRealLedger`, tabela `real_ledger_entry` com
  `CHECK (mode = 'REAL')`), por carteira do bot e sem `bot_id`; convenção de sinal (`RealEntryRules`), saldo e reserva
  (`RealBalances`, `RealBalance`), posições por custo médio da carteira (`RealPositions`: só a perna em SOL, parte
  vendida para baixo), fluxos, saldos de token para a reconciliação e leitura em fluxo para a exportação fiscal. As
  tabelas PAPER continuam só PAPER.
- **Relevância para o intent `261121-paper-real-unico`**: o P&L PAPER por bot se apoia na conta, nas posições e no
  realizado por fill de cada bot; o REAL não tem nada por bot no ledger.
- **Profundidade**: profunda (2026-11-21) nas interfaces PAPER e REAL, no domínio e na gravação REAL; superficial nos
  repositórios PAPER e na carga.

### pnl
- **Local**: `apps/api/src/main/java/com/memetrade/pnl/`
- **Responsabilidade**: P&L de um período (`PnlQueryService`, `PnlCalculator`, `ExitValuation`), visão de risco C13
  (`PortfolioRiskViewImpl`, `RiskSnapshotCalculator`), pico de patrimônio (`EquityPeakUpdater`, `EquityMarkingJob`),
  reconciliação agendada e sob demanda (`ReconciliationService`, `ReconciliationJob`), saúde dos jobs do ledger.
- **Relevância para o Trading REAL**: a reconciliação compara só fatos internos; a visão de risco usa saldo virtual.
- **REAL (2026-11-21)**: P&L REAL da pessoa (`RealPnlService`, `real/RealPnlCalculator`, `RealPnlController` em
  `GET /api/v1/real/pnl`), por custo médio por mint com taxa e aluguel no custo, a partir das ordens assentadas do M6
  (não do ledger REAL), sem período nem bot; não inclui as vendas das saídas condicionais.
- **Relevância para o intent `261121-paper-real-unico`**: dono do P&L por bot e por modo; o P&L PAPER por bot já existe
  (`GET /api/v1/pnl?botId=`); o REAL por bot, o rateio e o total por modo começam aqui.
- **Profundidade**: profunda (2026-11-21) no P&L PAPER e REAL; superficial na visão de risco, no pico e na
  reconciliação.

### realexecution
- **Local**: `apps/api/src/main/java/com/memetrade/realexecution/`
- **Responsabilidade**: ordens REAL (M6, `RealExecutionPort`): fila de decisões dos bots (`DecisionConsumer`), máquina
  de estados `PROPOSED` → `QUOTED` → `SIGNED` → `SENT` → `CONFIRMED`/`FAILED`/`AMBIGUOUS`/`CANCELLED`
  (`RealOrderStateMachine`, `OrderTransitions`, `OrderDriver`), cotação e swap pela Jupiter (`JupiterClient`,
  `SwapRouter`, `QuoteMath`), montagem da mensagem Solana sem SDK (`domain/solana`), sessão de assinatura do modo
  Assinada (`SigningSessionService`, `SigningDeadlineJob`), confirmação e ordem ambígua (`ConfirmationJob`,
  `AmbiguousJob`), assentamento e lançamentos no ledger REAL (`OrderSettlement`, `RealOrderLedger`,
  `RealLedgerEntries`), modo da pessoa (`RealModeService`), liquidação "vender tudo e sacar" (`LiquidationService`,
  `LiquidationJob`), métricas, expurgo e saúde das dependências (`UpstreamHealthJob`).
- **Dados**: `real_decision_request`, `real_order` (origem `BOT` com `bot_id` ou `USER_LIQUIDATION` sem `bot_id`),
  `real_liquidation` (0019).
- **Relevância para o intent `261121-paper-real-unico`**: `real_order.bot_id` é a única ligação entre uma venda ou
  compra REAL e um bot; as vendas da liquidação ficam sem bot.
- **Profundidade**: profunda nas interfaces, no modelo, na liquidação e nos lançamentos; superficial no `OrderDriver`,
  no cliente da Jupiter e nos jobs.

### realexits
- **Local**: `apps/api/src/main/java/com/memetrade/realexits/`
- **Responsabilidade**: saídas condicionais da API Trigger da Jupiter (stop, alvo, OCO; `ConditionalService`,
  `ConditionalStateMachine`, `TriggerPort`, `SimulatedTriggerAdapter`), uma por posição (mint) da carteira do bot;
  leitura e cancelamento (`ConditionalReadJob`, `ConditionalCancelJob`); gravação da venda executada no ledger REAL
  (`FillPosting`: `SWAP_FILL_OUT` e `NETWORK_FEE` com a referência `conditional:<id>`); `ExitGuardService` implementa o
  `PositionExitGuard` do M6; eventos do canal ao vivo; exportação fiscal CSV (`fiscal/*`, `VENDA_CONDICIONAL` para as
  referências `conditional:`); métricas e expurgo.
- **Dados**: `real_conditional_order` (0022), sem `bot_id`.
- **Relevância para o intent `261121-paper-real-unico`**: a venda condicional não cria `real_order`, então fica fora do
  P&L REAL e não tem bot; é uma das "vendas sem bot" que o intent manda ratear.
- **Profundidade**: profunda no modelo, na gravação da venda, nas interfaces e no mapeamento fiscal; superficial nos jobs
  e na criação.

### administration
- **Local**: `apps/api/src/main/java/com/memetrade/administration/`
- **Responsabilidade**: kill switch (estado único com versão, histórico append-only, `KillSwitchService`,
  `KillSwitchApplier` como único chamador da pausa do sistema, `ApplyLock`, `KillSwitchReconciler` a cada 30 s e na
  subida), estado do sistema com contagens (`SystemStatusAssembler`), lista de tenants.
- **Relevância para o Trading REAL**: pausa todos os bots; não cancela transações enviadas nem ordens condicionais
  externas.
- **Profundidade**: profunda.

### feedback
- **Local**: `apps/api/src/main/java/com/memetrade/feedback/`
- **Responsabilidade**: envio de comentário idempotente com limite de 10 por hora, listagem administrativa sem pessoa
  nem carteira, expurgo diário de 180 dias pelo papel `feedback_retention`, porta J3 para a privacidade.
- **Profundidade**: superficial.

### privacy
- **Local**: `apps/api/src/main/java/com/memetrade/privacy/`
- **Responsabilidade**: catálogo de retenção e sua conformidade, pedidos de titular (criação, transições, verificação
  por assinatura, prazos, expurgo de 12 meses), resumo de acesso montado pelas portas J1 a J4, anonimização de conta
  (para os bots, revoga sessões, apaga feedback, anula o endereço da carteira).
- **Relevância para o Trading REAL**: a anonimização hoje assume só fundos virtuais.
- **Profundidade**: superficial.

### ops
- **Local**: `apps/api/src/main/java/com/memetrade/ops/`
- **Responsabilidade**: `MigrationRunner` (perfil `migrate`) e `HealthProbe` (healthcheck do contêiner sem curl).
- **Profundidade**: profunda.

### db-migrations
- **Local**: `apps/api/src/main/resources/db/changelog/`
- **Responsabilidade**: 15 changesets SQL formatados para Liquibase, de `0001-identity` a `0015-anonymization`, com
  `CHECK` de modo, gatilhos append-only, matriz de privilégios por coluna e papéis de retenção; expand-contract com
  rollback declarado.
- **Profundidade**: profunda nas estruturas, modos, gatilhos e privilégios.

### api-resources
- **Local**: `apps/api/src/main/resources/` (exceto `db/`)
- **Responsabilidade**: configuração por perfil (`application.yml`, `-worker`, `-migrate`, `-test`, `-loadtest`), logs
  JSON mascarados (`logback-spring.xml`), termos v1 e v2 com aviso de risco, política de privacidade e tabela de
  retenção, registro de rotas e jobs (`tenancy/route-registry.yml`, 72 entradas).
- **Profundidade**: profunda.

### api-tests
- **Local**: `apps/api/src/test/`
- **Responsabilidade**: 169 classes de teste unitário, 170 de integração (Testcontainers com PostgreSQL real), regras
  ArchUnit, contratos OpenAPI, migrações e compatibilidade, configuração por processo, observabilidade, Compose e
  workflows, registro de rotas, ferramenta de carteira de teste (`tools/SmokeWalletTool`); suporte em `testsupport/`.
- **Profundidade**: profunda nas regras de arquitetura; superficial no restante (estrutura e nomes).

## Frontends

### web-app
- **Local**: `apps/web/`
- **Responsabilidade**: app do convidado — entrada por carteira, convite com aceite, tela Conta, painel ao vivo,
  scanner com avaliação, bots (lista, assistente, detalhe), P&L, ledger e reconciliação, documentos legais, reaceite,
  pedidos de titular, feedback; CSP com nonce; token só em memória; tipos gerados do OpenAPI guest.
- **Profundidade**: profunda em `package.json`, `jest.config.ts`, `middleware.ts` e `features/auth/wallets.ts`;
  superficial no restante. (Estado de 2026-11-13; as partes abaixo foram relidas em 2026-11-21. Desde o intent
  `261113-trading-real-bot` o app também tem a área REAL em `src/app/real/` e `src/features/real/`.)

### web-bots
- **Local**: `apps/web/src/features/bots/`, `apps/web/src/app/bots/`
- **Responsabilidade**: lista de bots com estado, motivos de pausa e ações válidas (`BotList`), criação com modelo e
  nome (`BotCreate`), assistente de configuração e limites (`BotWizard`, `LimitFields`, `validation.ts`), detalhe do bot
  com comandos, decisões, ordens e posições abertas pelo P&L PAPER do bot (`BotDetail`, que chama
  `api.getPnl({ from, to, botId })` e usa `features/pnl/BotTradesTable`); rotas `/bots`, `/bots/new`,
  `/bots/new/[botId]`, `/bots/[botId]`.
- **Relevância para o intent `261121-paper-real-unico`**: toda a feature é PAPER: títulos "Bots PAPER", botão "Iniciar
  bot PAPER", nenhuma escolha nem exibição de `market`; `createBot` não envia `market`.
- **Profundidade**: profunda nas telas, chamadas e textos; superficial nos testes.

### web-real
- **Local**: `apps/web/src/features/real/`
- **Responsabilidade**: área REAL do convidado: visão geral com o cartão do P&L REAL (`OverviewScreen`, `RealPnlCard`),
  ativação da carteira do bot, depósito e saque, modo de operação, ordens e assinatura do modo Assinada
  (`SigningBanner`, `SigningDialog`, `sign-transaction.ts`), saídas condicionais (`ConditionalDialog`,
  `ConditionalsTab`), exportação fiscal (`FiscalExportPanel`), cartão REAL do painel (`DashboardRealPnl`, só com
  carteira ativa); valores em `bigint` (`money.ts`), DTOs convertidos em `api.ts`, `real-lint.test.ts` com as proibições
  da pasta.
- **Relevância para o intent `261121-paper-real-unico`**: o P&L REAL é mostrado por posição (mint), sem bot; a
  conversão `toPnl` e o cartão são o ponto de partida do P&L REAL por bot e do total REAL.
- **Profundidade**: profunda em `api.ts`, `money.ts` e nos cartões de P&L; superficial nas telas de carteira, modo,
  ordens e assinatura.

### web-dashboard
- **Local**: `apps/web/src/features/dashboard/`
- **Responsabilidade**: painel `/` com o cartão de bots (`BotsCard`, `BotRow`), o cartão do P&L PAPER do dia
  (`PnlCard`, rótulo "MODO PAPER (simulado)") e o cartão REAL (`DashboardRealPnl`) lado a lado, nunca somados; estado
  (`dashboard-reducer.ts`, `dashboard-loader.ts`, `DashboardProvider`), canal ao vivo por `fetch` (`sse-reader.ts`,
  `stream-client.ts`, `envelopes.ts`, que já aceita `market` no resumo do bot), avisos de dados.
- **Relevância para o intent `261121-paper-real-unico`**: o cartão de bots se chama "Bots PAPER" e o de P&L é só PAPER;
  o total por modo e a lista única passam por aqui.
- **Profundidade**: profunda na tela, nos cartões, nos textos e nos envelopes; superficial no cliente do canal e nos
  testes.

### web-pnl-route
- **Local**: `apps/web/src/app/pnl/`
- **Responsabilidade**: rota `/pnl`, página fina que monta `features/pnl/PnlScreen` (P&L, ledger e reconciliação
  PAPER).
- **Profundidade**: profunda na página; `features/pnl` não foi lida nesta execução.

### admin-app
- **Local**: `apps/admin/`
- **Responsabilidade**: app do administrador — entrada por carteira da lista, estado do sistema, convites, feedback,
  kill switch com faixa de aviso, pedidos de titular e anonimização, documentos legais; tipos gerados do OpenAPI admin.
- **Profundidade**: superficial (estrutura, `package.json`, `jest.config.ts`).

### e2e-tests
- **Local**: `apps/web/e2e/`
- **Responsabilidade**: 15 specs Playwright (U1 a U9 exceto U6, documentos legais, reaceite, pedidos de titular,
  anonimização, prontidão do executor), suporte de carteira de teste e de leitura da trilha; rodados por
  `scripts/verify.sh`.
- **Profundidade**: superficial.

## Infraestrutura e entrega

### db-bootstrap
- **Local**: `infra/postgres/`
- **Responsabilidade**: cria e atualiza os logins (dono, aplicação, retenção) e os papéis de grupo `audit_retention`,
  `risk_retention`, `feedback_retention`; transfere a posse para o dono; privilégios padrão.
- **Profundidade**: superficial.

### compose-stack
- **Local**: `docker-compose.yml`, `infra/docker-compose.test.yml`, `apps/*/Dockerfile`, `.env.example`
- **Responsabilidade**: serviços `postgres`, `db-bootstrap`, `db-migrate`, `api`, `worker`, `web`, `admin`, todos em
  `127.0.0.1`, imagens base fixadas por digest, usuário não root, healthchecks, rede com faixa fixa; o worker não
  recebe a chave de assinatura, as origens nem o login de retenção; a sobreposição `test` semeia a conta e os convites
  do smoke.
- **Profundidade**: profunda.

### ci-workflows
- **Local**: `.github/workflows/`, `.github/dependabot.yml`, `.githooks/pre-commit`, `.gitleaks.toml`,
  `.gitleaks-version`, `security/exceptions.yml`
- **Responsabilidade**: CI (`backend`, `frontend`, `admin`, `contract`, `smoke`, `e2e`), segurança (Gitleaks, CodeQL,
  OWASP Dependency-Check, `pnpm audit`), carga manual, Dependabot, hook de pré-commit, exceções com validade.
- **Profundidade**: profunda.

### dev-scripts
- **Local**: `scripts/`
- **Responsabilidade**: `setup.sh` (liga o hook), `verify.sh` (gera carteira de teste fora do repositório, sobe o
  ambiente de teste, espera saúde e a primeira coleta, roda os specs, varre logs atrás de endereço completo, derruba
  tudo), `load-test.sh` e `load/*.mjs` (cenários de carga com testes próprios), `perf-audit.sh`.
- **Profundidade**: superficial (cabeçalho de `verify.sh` lido a fundo).

### project-docs
- **Local**: `README.md`, `docs/adr/`, `docs/api/`, `docs/privacy/`, `PRD_MemeTrade_Analytics_v1.3_SaaS.md`,
  `PromptInicial.txt`
- **Responsabilidade**: guia de execução local, 5 ADRs, anexos de contrato por Unit, retenção e auditoria, requisito de
  produto.
- **Profundidade**: profunda nas seções do PRD sobre carteiras, REAL, risco, arquitetura e gates; superficial no
  restante.
