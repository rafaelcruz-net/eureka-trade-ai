# Inventário de componentes — eureka-trader-ai

Cada título abaixo é o nome canônico do componente (usado também no bloco Scope of Analysis). Dependências entre
componentes em [dependencies.md](dependencies.md); rotas em [api-documentation.md](api-documentation.md); tabelas e
dados pessoais em [architecture.md](architecture.md#dados-pessoais-e-retenção).

## Backend (`apps/api`)

### shared
- **Local**: `apps/api/src/main/java/com/memetrade/shared/`
- **Responsabilidade**: relógio injetável (`ClockConfig`), erros RFC 7807 (`ApiException`, `ErrorCode`,
  `ProblemDetailsHandler`), correlação (`CorrelationIdFilter`), cabeçalhos de segurança, guarda de modo REAL
  (`ModeGuardFilter`), cursor de tupla, limitador por pessoa e rota, máscara de carteira (`WalletAddress`) e de logs
  (`LogMasking`, `Base58AddressValueMasker`), guarda de configuração na subida (`RuntimeSettingsGuard`,
  `LoadtestProfileGuard`), seleção de processo (`ProcessRole`).
- **Profundidade da varredura**: profunda.

### identity
- **Local**: `apps/api/src/main/java/com/memetrade/identity/`
- **Responsabilidade**: termos versionados (`TermsCatalog`, `CurrentTerms`), convites (emissão, revogação, consulta
  pública), criação de conta por convite com aceite (`InviteAccountCreation`), login por assinatura Ed25519 (convidado
  e administrador), sessões com rotação do refresh, tela Conta (`AccountQueryService`), idempotência comum
  (`IdempotencyService`, limpeza horária), limites de taxa, autenticação e autorização administrativa.
- **Tabelas**: `tenant`, `app_user`, `wallet`, `login_challenge`, `user_session`, `session_used_refresh_digest`,
  `invite`, `terms_acceptance`, `idempotency_record`.
- **Jobs**: `IdempotencyCleanupJob` (1 h), `LoginLimiterEvictionJob` (60 s). Sem expurgo de desafios e sessões.
- **Profundidade da varredura**: profunda.

### audit
- **Local**: `apps/api/src/main/java/com/memetrade/audit/`
- **Responsabilidade**: publicação de eventos validados na saída (`JdbcAuditEventPublisher`, `AuditEventValidator`),
  relay para a trilha (`AuditOutboxRelay`, 1 s), consulta administrativa (`AdminAuditController`), expurgo de 12 meses
  pelo pool de retenção (`AuditRetentionPurge`), limpeza da saída (`AuditOutboxCleanup`), recuperação na subida e
  saúde dos jobs.
- **Tabelas**: `audit_outbox`, `audit_event`, `audit_job_run`, `shedlock`.
- **Profundidade da varredura**: profunda.

### marketdata
- **Local**: `apps/api/src/main/java/com/memetrade/marketdata/`
- **Responsabilidade**: fontes simuladas (`infra/simulated`), coleta com retry e limite de tempo (Resilience4j),
  validação de observações, resolução de campos com `unknown`, divergência entre fontes, histórico e limpeza horária.
- **Tabelas**: `data_source`, `token`, `market_observation`, `observation_sample`, `source_status_mark` (globais).
- **Profundidade da varredura**: superficial.

### scanner
- **Local**: `apps/api/src/main/java/com/memetrade/scanner/`
- **Responsabilidade**: lista do scanner com filtros e cursor, avaliação de um token, limite por pessoa e rota.
- **Profundidade da varredura**: superficial.

### riskassessment
- **Local**: `apps/api/src/main/java/com/memetrade/riskassessment/`
- **Responsabilidade**: indicador de risco por pesos versionados (`RiskCalculator`, `WeightSet`), recálculo,
  administração de versões de pesos.
- **Tabelas**: `risk_weight_set`, `risk_assessment` (globais).
- **Profundidade da varredura**: superficial.

### riskcontrol
- **Local**: `apps/api/src/main/java/com/memetrade/riskcontrol/`
- **Responsabilidade**: barreira de ordens (`OrderGateService`, `LimitChecks`) com limites autorizados, frescor e
  liquidez; registro de avaliações e recusas; consulta de recusas; expurgo diário das avaliações aprovadas antigas
  pelo papel `risk_retention`.
- **Tabelas**: `order_evaluation`, `order_rejection`, `rejection_reason_detail`, `risk_purge_run`.
- **Profundidade da varredura**: superficial.

### bots
- **Local**: `apps/api/src/main/java/com/memetrade/bots/`
- **Responsabilidade**: bots PAPER (rascunho, validação, ativação, pausa, parada, máquina de estados), limites
  versionados, comandos idempotentes, canal ao vivo SSE (`DashboardStreamController`, `DashboardMarkPoller`), ciclo
  de 5 min, meia-noite UTC e expurgo de decisões no processo worker (`worker/`).
- **Tabelas**: `strategy_template`, `bot_owner`, `bot`, `bot_limits_version`, `bot_cycle`, `bot_holding`,
  `system_pause_state`.
- **Profundidade da varredura**: superficial.

### decision
- **Local**: `apps/api/src/main/java/com/memetrade/decision/`
- **Responsabilidade**: motor de decisão determinístico sem IA (`DecisionEngine`, estratégias momentum/volume e
  rompimento de faixa, regras de saída, dimensionamento) e persistência das decisões explicadas com expurgo de
  HOLD/NO_TRADE de 30 dias.
- **Tabelas**: `decision`.
- **Profundidade da varredura**: superficial.

### ledger
- **Local**: `apps/api/src/main/java/com/memetrade/ledger/`
- **Responsabilidade**: execução PAPER (`PaperExecutionImpl`, `FillCalculator`, política de simulação v1),
  financiamento inicial, fechamento de posições e fechamento pendente (`PendingCloseJob`), compensação, consultas do
  ledger e das operações.
- **Tabelas**: `simulation_policy`, `paper_account`, `paper_order`, `fill`, `ledger_entry`, `position`.
- **Profundidade da varredura**: superficial (gatilhos append-only e privilégios lidos por completo).

### pnl
- **Local**: `apps/api/src/main/java/com/memetrade/pnl/`
- **Responsabilidade**: P&L e resumo diário, pico de patrimônio para drawdown, marcação periódica, reconciliação
  agendada e sob demanda (mantém as últimas 100 por pessoa), visão de risco da carteira para a barreira.
- **Tabelas**: `pnl_daily`, `equity_peak`, `reconciliation_check`.
- **Profundidade da varredura**: superficial.

### administration
- **Local**: `apps/api/src/main/java/com/memetrade/administration/`
- **Responsabilidade**: kill switch (estado único, histórico append-only, aplicação com trava consultiva e
  reconciliação a cada 30 s), estado do sistema e lista de tenants para o administrador.
- **Tabelas**: `kill_switch_state`, `kill_switch_change`.
- **Profundidade da varredura**: superficial.

### feedback
- **Local**: `apps/api/src/main/java/com/memetrade/feedback/`
- **Responsabilidade**: envio de comentários idempotente com limite de 10 por hora, listagem administrativa sem
  pessoa nem carteira, expurgo diário de 180 dias pelo papel `feedback_retention`, saúde do expurgo.
- **Tabelas**: `feedback_entry`.
- **Profundidade da varredura**: profunda.

### ops
- **Local**: `apps/api/src/main/java/com/memetrade/ops/`
- **Responsabilidade**: `MigrationRunner` (perfil `migrate`: aplica o Liquibase com o login dono e termina) e
  `HealthProbe` (healthcheck do contêiner).
- **Profundidade da varredura**: superficial.

### db-migrations
- **Local**: `apps/api/src/main/resources/db/changelog/`
- **Responsabilidade**: 11 changesets SQL formatados para Liquibase (0001 identidade a 0011 administração e
  feedback), com gatilhos append-only, CHECKs, índices e matriz de privilégios; expand-contract.
- **Profundidade da varredura**: profunda.

### api-resources
- **Local**: `apps/api/src/main/resources/` (exceto `db/`)
- **Responsabilidade**: configuração por perfil (`application.yml`, `-worker`, `-migrate`, `-test`, `-loadtest`),
  logs JSON mascarados (`logback-spring.xml`), termos e aviso de risco `terms/v1`, registro de rotas e jobs
  (`tenancy/route-registry.yml`).
- **Profundidade da varredura**: profunda.

### api-tests
- **Local**: `apps/api/src/test/`
- **Responsabilidade**: testes unitários, de integração com PostgreSQL real (Testcontainers), de arquitetura
  (ArchUnit), de contrato OpenAPI, de migração e compatibilidade, de configuração e de carga; suporte em
  `testsupport/`.
- **Profundidade da varredura**: superficial.

## Frontends

### web-app
- **Local**: `apps/web/`
- **Responsabilidade**: app do convidado — entrada, convite com aceite de termos e aviso de risco, tela Conta,
  painel ao vivo, scanner, bots, P&L e ledger, feedback; CSP com nonce; token só em memória.
- **Profundidade da varredura**: profunda em `features/auth`, `features/account`, `middleware.ts`; superficial no
  restante.

### admin-app
- **Local**: `apps/admin/`
- **Responsabilidade**: app do administrador — entrada por carteira da lista, estado do sistema, convites, feedback,
  kill switch com faixa de aviso.
- **Profundidade da varredura**: profunda (estrutura, sessão, convites, feedback).

### e2e-tests
- **Local**: `apps/web/e2e/`
- **Responsabilidade**: smokes Playwright por Unit (U1 a U9, exceto U6), suporte de carteira de teste e de leitura da
  trilha; rodados por `scripts/verify.sh`.
- **Profundidade da varredura**: superficial.

## Infraestrutura e entrega

### db-bootstrap
- **Local**: `infra/postgres/`
- **Responsabilidade**: cria e atualiza os logins (dono, aplicação, retenção) e os papéis de grupo
  `audit_retention`, `risk_retention`, `feedback_retention`; transfere a posse para o dono; privilégios padrão.
- **Profundidade da varredura**: profunda.

### compose-stack
- **Local**: `docker-compose.yml`, `infra/docker-compose.test.yml`, `apps/*/Dockerfile`, `.env.example`
- **Responsabilidade**: serviços `postgres`, `db-bootstrap`, `db-migrate`, `api`, `worker`, `web`, `admin`, todos em
  `127.0.0.1`, imagens fixadas por digest, usuário não root, healthchecks; sobreposição `test` semeia a conta e os
  convites do smoke.
- **Profundidade da varredura**: profunda.

### ci-workflows
- **Local**: `.github/workflows/`, `.github/dependabot.yml`, `.githooks/pre-commit`, `.gitleaks.toml`,
  `security/exceptions.yml`
- **Responsabilidade**: CI (backend `verify`, frontend web, contrato, smoke e Playwright completo), segurança
  (Gitleaks, CodeQL, OWASP Dependency-Check, pnpm audit), carga manual, Dependabot, hook de pré-commit.
- **Profundidade da varredura**: profunda.

### dev-scripts
- **Local**: `scripts/`
- **Responsabilidade**: `setup.sh` (liga o hook), `verify.sh` (sobe o ambiente de teste, espera saúde, roda os
  smokes, varre logs atrás de endereço completo), `load-test.sh` e `load/*.mjs` (cenários u1 a u7), `perf-audit.sh`.
- **Profundidade da varredura**: superficial.

### project-docs
- **Local**: `README.md`, `docs/adr/`, `docs/api/`, `PRD_MemeTrade_Analytics_v1.3_SaaS.md`, `PromptInicial.txt`
- **Responsabilidade**: guia de execução, decisões de arquitetura, anexos de contrato, requisito de produto.
- **Profundidade da varredura**: profunda em README, ADRs e `docs/api`; superficial no PRD.
