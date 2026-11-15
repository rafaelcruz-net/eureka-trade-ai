# Varredura de código do desenvolvedor — eureka-trader-ai

Intent: `261113-trading-real-bot`. Data: 2026-11-13. Commit de referência: `40e25530928b5fcb5d82bcc4b7afc283a6e12369`.
Profundidade: Comprehensive. Tipo: varredura completa (full rescan) do repositório, caminho do snapshot `./`.
Feita só por leitura de arquivos: nenhum teste, build ou programa do projeto foi executado. O único comando rodado foi
o `aidlc engine workspace codekb-scope-diff` (cálculo do fingerprint e conferência do bloco de escopo).

## Developer Code Scan Results

### Scan Coverage

- **Analyzed deeply**:
  - `apps/api/src/main/java/com/memetrade/marketdata/` (porta `MarketSourceAdapter`, fonte simulada, coleta, resolução de campos, divergência, configuração)
  - `apps/api/src/main/java/com/memetrade/ledger/` (contrato `PaperExecution` C15, `LedgerReadModel`, execução, `FillCalculator`, `FillRecorder`, enums PAPER)
  - `apps/api/src/main/java/com/memetrade/riskcontrol/` (contrato `OrderGate` C14, `OrderGateService`, `LimitChecks`, `SlippageEstimate`)
  - `apps/api/src/main/java/com/memetrade/bots/` (ciclo `CycleRunner`, `SlotDispatcher`, `CycleAuthContext`, `BotAdministration`, estados e motivos de pausa, comandos)
  - `apps/api/src/main/java/com/memetrade/administration/` (kill switch: serviço, aplicador com trava, reconciliação)
  - `apps/api/src/main/java/com/memetrade/pnl/` (`PortfolioRiskView` C13 e responsabilidades das classes)
  - `apps/api/src/main/java/com/memetrade/shared/` (`ModeGuardFilter`, `ErrorCode`, `ProcessRole`, `RuntimeSettingsGuard`)
  - `apps/api/src/main/java/com/memetrade/identity/api/` (idempotência comum, `Principal`, `Role`, `AuthContext`) e `identity/domain/signature/`
  - `apps/api/src/main/java/com/memetrade/audit/domain/AuditEventValidator.java` e `audit/api/AuditActions.java`
  - `apps/api/src/main/resources/` (todas as 15 migrações, `application*.yml`, `tenancy/route-registry.yml`, termos)
  - `apps/api/openapi/` (rotas dos dois contratos)
  - `apps/api/pom.xml`
  - `apps/api/src/test/java/com/memetrade/architecture/` (regras ArchUnit)
  - `apps/web/package.json`, `apps/web/jest.config.ts`, `apps/web/src/middleware.ts`, `apps/web/src/features/auth/wallets.ts`
  - `apps/admin/package.json`, `apps/admin/jest.config.ts`
  - `.github/workflows/`, `.github/dependabot.yml`, `security/exceptions.yml`, `.githooks/pre-commit`
  - `docker-compose.yml`, `infra/docker-compose.test.yml`, `apps/*/Dockerfile`, `.env.example`
  - `scripts/verify.sh` (cabeçalho e preparação)
  - `PRD_MemeTrade_Analytics_v1.3_SaaS.md` (§7, §8, §9, §12 e §16)
- **Skimmed only**:
  - `apps/api/src/main/java/com/memetrade/identity/` (domínio, infra e web: lidos pela lista de classes e pelo comentário de abertura de cada uma)
  - `apps/api/src/main/java/com/memetrade/audit/` (exceto o validador e as ações)
  - `apps/api/src/main/java/com/memetrade/privacy/`
  - `apps/api/src/main/java/com/memetrade/feedback/`
  - `apps/api/src/main/java/com/memetrade/scanner/`
  - `apps/api/src/main/java/com/memetrade/riskassessment/`
  - `apps/api/src/main/java/com/memetrade/decision/`
  - `apps/api/src/main/java/com/memetrade/ops/`
  - `apps/api/src/test/` (estrutura, nomes e contagens; só as regras de arquitetura lidas a fundo)
  - `apps/web/src/` (estrutura por feature e rotas do App Router)
  - `apps/web/e2e/`
  - `apps/admin/src/`
  - `infra/postgres/`
  - `scripts/load/`, `scripts/load-test.sh`, `scripts/perf-audit.sh`, `scripts/setup.sh`
  - `docs/adr/`, `docs/api/`, `docs/privacy/`
  - `README.md`, `PromptInicial.txt`
- **Left out**:
  - `node_modules/`, `.next/`, `target/`, `coverage/`, `test-results/`, `playwright-report/` (saídas de build e dependências)
  - `.tools/`, `.vscode/` (ferramentas locais e IDE)
  - `.env` (segredos locais)
  - `load-test-report.json`, `docs/perf/` (relatórios gerados; `docs/perf/` está no `exclude` de `.aidlc-source-paths.json`)
  - `PRD_MemeTrade_Analytics_v1.3_SaaS.pdf` (cópia em PDF do PRD em Markdown)
  - `apps/web/pnpm-lock.yaml`, `apps/admin/pnpm-lock.yaml` (lockfiles gerados)
  - `apps/web/src/lib/api/schema.d.ts`, `apps/admin/src/lib/api/schema.d.ts` (tipos gerados do OpenAPI)
  - `.claude/`, `.codex/`, `.agents/`, `aidlc/` e as seções do AI-DLC em `AGENTS.md` e `.gitignore` (instalação do AI-DLC)

### Packages Found

- `memetrade-api` — serviço (monólito modular, um artefato Maven, três papéis de processo: `api`, `worker`, `db-migrate`) — Java 21 — API REST, jobs e ciclo dos bots PAPER
- `memetrade-web` — app web (Next.js App Router) — TypeScript — app do convidado (porta 3000)
- `memetrade-admin` — app web (Next.js App Router) — TypeScript — app do administrador (porta 3001)
- `infra/postgres` — scripts de bootstrap — Shell e SQL — logins e papéis de grupo do PostgreSQL
- `scripts` — utilitários — Bash e Node (ESM) — verificação de ponta a ponta, carga, auditoria de desempenho

Módulos de `memetrade-api` (pacotes `com.memetrade.*`): `shared`, `identity`, `audit`, `marketdata`, `scanner`,
`riskassessment`, `riskcontrol`, `bots`, `decision`, `ledger`, `pnl`, `administration`, `feedback`, `privacy`, `ops`.
Inventário completo em `component-inventory.md` do staging.

### Build System

- **Type**: Maven (wrapper `mvnw`, Spring Boot parent 3.5.16) no backend; pnpm 10.18.3 (campo `packageManager`) com Node 24 (`.nvmrc` = 24, `engines` `>=24 <25`) nos frontends.
- **Config Files**: `apps/api/pom.xml`, `apps/api/.mvn/wrapper/maven-wrapper.properties`, `apps/web/package.json`, `apps/admin/package.json`, `apps/*/tsconfig.json`, `apps/*/next.config.ts`, `apps/*/eslint.config.mjs`, `apps/*/.prettierrc.json`, `apps/*/jest.config.ts`, `apps/web/playwright.config.ts`, `apps/*/Dockerfile`, `docker-compose.yml`, `infra/docker-compose.test.yml`.
- **Build Dependencies**: `apps/web` e `apps/admin` geram tipos de `apps/api/openapi/openapi-guest.yaml` e `openapi-admin.yaml` (`gen:api`, conferido por `check:api`); os OpenAPI são gerados do código da API e comparados por `GuestOpenApiContractTest` e `AdminOpenApiContractTest`; as três imagens (`memetrade-api`, `memetrade-web`, `memetrade-admin`) são construídas localmente e ligadas pelo Compose.

### APIs Discovered

- REST guest (`/api/v1/**`) — `apps/api/openapi/openapi-guest.yaml` — 32 operações
- REST admin (`/api/v1/admin/**` e `/api/v1/legal/**`) — `apps/api/openapi/openapi-admin.yaml` — 24 operações (21 administrativas e 3 de documentos legais compartilhadas)
- Canal ao vivo (SSE lido por `fetch`) — `GET /api/v1/stream/dashboard` — 1 canal
- Interfaces Java públicas entre módulos (pacotes `*.api`) — 15 módulos — contratos C10 a C21, J1 a J7 (detalhe em `api-documentation.md`)
- Eventos de domínio síncronos — `FillRecorded`, `PaperAccountFunded`, `MarketCollectionCompleted`
- Porta de fonte de mercado — `MarketSourceAdapter` (C19) — só implementações simuladas (`sim-a`, `sim-b`)

### Frameworks & Libraries

- Spring Boot — 3.5.16 — web, validação, JDBC, actuator
- Liquibase — gerenciado pelo Boot — migrações SQL formatadas
- PostgreSQL JDBC — 42.7.14 (sobreposição de segurança) — driver
- springdoc-openapi — 2.8.17 — geração do OpenAPI 3.1
- nimbus-jose-jwt — 10.10 — utilidades de JOSE (o token de acesso é HMAC próprio)
- ShedLock — 6.10.0 — execução única dos jobs
- Resilience4j (retry, timelimiter) — 2.3.0 — chamadas às fontes de mercado
- Micrometer Prometheus — gerenciado pelo Boot — métricas
- logstash-logback-encoder — 8.1 — logs JSON mascarados
- Tomcat — 10.1.61 (sobreposição de segurança) — servidor
- Next.js — 15.5.28 — dois apps
- React / React DOM — 19.2.9 — UI
- Wallet Standard (`@wallet-standard/app` 1.1.0, `base` 1.1.0, `features` 1.1.0, `@solana/wallet-standard-features` 1.3.0) — conexão e `signMessage` de Phantom e Solflare
- TypeScript — 5.9.3 — modo strict
- openapi-typescript — 7.9.1 — tipos gerados
- JUnit 5, Testcontainers, ArchUnit 1.5.1, JaCoCo 0.8.15, solanaj 1.28.0 (escopo `test`) — testes do backend
- Jest 30.2.0, Testing Library, Playwright 1.56.1 — testes dos frontends
- Spotless 2.46.1 com palantir-java-format 2.80.0; ESLint 9.39.5; Prettier 3.6.2 — formatação e lint

### Test Coverage

- **Test Directories**: `apps/api/src/test/java/com/memetrade/` (169 classes `*Test`, 170 `*IT`, 54 de suporte em `testsupport/`), `apps/api/src/test/resources/` (19 arquivos: fixtures gravadas `sim-a.json`/`sim-b.json`, changelogs parciais, termos de teste), `apps/web/src/**/*.test.ts(x)` (43), `apps/admin/src/**/*.test.ts(x)` (17), `apps/web/e2e/` (15 specs Playwright), `scripts/load/*.test.mjs` (6).
- **Test Frameworks**: JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), ArchUnit, testes de propriedade e de catálogo de cenários; Jest com jsdom e Testing Library; Playwright; `node:test` nos cenários de carga.
- **Coverage Config**: presente. JaCoCo com regra `check` de 80% de linhas (`coverage.line.minimum` 0.80, unitários e integração mesclados, exclusões de `*Config`, `*Properties`, ponto de entrada e `HealthProbe`); Jest com `coverageThreshold.global.lines` 80 nos dois apps (exclui `schema.d.ts`).

### Code Quality Indicators

- **Linting**: Spotless com palantir-java-format (`format-check` no `verify`); ESLint (`eslint.config.mjs`) e Prettier (`.prettierrc.json`) por app; `tsc --noEmit`; não há Checkstyle (há um `@SuppressWarnings("checkstyle:ParameterNumber")` órfão em `CycleRunner`).
- **CI/CD**: `.github/workflows/ci.yml` (jobs `backend`, `frontend`, `admin`, `contract`, `smoke`, `e2e` só em push para `main`), `security.yml` (Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit` dos dois apps), `load-test.yml` (manual), `dependabot.yml` (Maven, npm com espera de 5 dias, Docker, Compose, Actions); ações fixadas por SHA; `permissions: contents: read`. A lacuna anotada no `team.md` (CI sem lint, tipos, Jest e `check:api` do admin) está fechada: o job `admin` roda os quatro.
- **Documentation**: `README.md` em pt-BR (execução local, testes por Unit, `verify.sh`, segurança); 5 ADRs em `docs/adr/`; anexos de contrato por Unit em `docs/api/`; Javadoc de abertura em praticamente todas as classes, citando regras de negócio (BRx.y) e NFRs.

### Technical Debt Signals

- Nenhum `TODO`, `FIXME` ou `HACK` no código de `apps/`, `scripts/`, `infra/` e `docs/`.
- 13 exceções de segurança ativas em `security/exceptions.yml`, todas vencendo em `2027-02-09` (EX-001 a EX-012: Spring Framework 6.2.19 sem correção aberta; seguimento previsto: Spring Boot 4 / Spring 7.0.9+).
- Quatro sobreposições de versão no `pom.xml` (Tomcat, Log4j API, pgjdbc, Kotlin) por CVE, a remover quando o BOM do Boot alcançar.
- `@SuppressWarnings`: 56 `unchecked`, 4 `deprecation`, 2 `unused`, 1 `resource`, 1 `checkstyle:ParameterNumber` (este sem Checkstyle configurado); 1 `eslint-disable-next-line`.
- Política de simulação duplicada: `riskcontrol/domain/SlippageEstimate.V1` copia em código os parâmetros da política v1 do ledger (mantidos iguais por `SlippageConformanceIT`).
- Limites de frescor diferentes para o mesmo preço: 60 s na barreira (`memetrade.riskcontrol.freshness-max-age`) e 5 min no preenchimento (`FillCalculator.MAX_PRICE_AGE`).
- `RiskWeightAdministration` existe na interface pública de `riskassessment`, mas não há rota REST nem tela no app administrativo para os pesos.
- Arquivos maiores: `JdbcLedgerRepository` (595 linhas), `JdbcBotRepository` (503), `DataRequestService` (502), `BotDtos` (495), `BotCommandService` (493); no frontend `BotDetail.tsx` (656) e `BotWizard.tsx` (609).
- Fontes de mercado fixas no código: `MarketDataProperties.KNOWN_SOURCES = {sim-a, sim-b}`; ArchUnit proíbe `java.net` em `marketdata`.

## Handoff Summary

- **Intent-relevant finding**: o Trading REAL hoje está bloqueado de propósito em quatro camadas, e cada uma precisa de uma decisão explícita, não de um ajuste pontual. (1) Banco: 13 tabelas de negócio têm `CHECK (mode = 'PAPER')` (`0008-ledger-pnl.sql` linhas 41, 69, 115, 138, 172, 198, 215, 231; `0009-risk-control.sql` 28, 59, 93; `0010-bots-decision.sql` 83, 201). (2) HTTP: `shared/web/ModeGuardFilter.java` recusa qualquer `mode` diferente de `PAPER` com `403 REAL_MODE_UNAVAILABLE`; `AccountQueryService` devolve REAL sempre `UNAVAILABLE`. (3) Build e arquitetura: `pom.xml` bane `solanaj`, `bitcoinj`, `sol4k` e `sava` fora do escopo `test` (`maven-enforcer-plugin`, linhas 187 a 196) e `ArchitectureTest.productionCodeNeverGeneratesOrImportsKeysOrMnemonics` proíbe `KeyPairGenerator`, `PrivateKey`, `PKCS8EncodedKeySpec` e `*Mnemonic*` em produção; `BotsArchitectureTest`, `LedgerArchitectureTest` e `RiskControlArchitectureTest` proíbem rede, `Signature`, `Keypair` e SDK Solana nesses módulos; `ArchitectureTest.theMarketModuleNeverUsesTheNetwork` proíbe `java.net` em `marketdata`. (4) Texto legal: os termos v2 (`terms/v2/terms.txt`, `risk-notice.txt`, `terms-summary.txt`) dizem que o modo REAL está indisponível; mudar isso exige uma versão nova e o fluxo de reaceite existente.
- **Risks / follow-up**:
  - Conflito de regras a resolver nos requisitos antes de qualquer desenho: a "carteira do bot gerada pelo sistema, chave no KMS" esbarra em `project.md` Forbidden ("NEVER incluir no código de produção APIs de importação ou geração de chave secreta", "NEVER incluir o SDK Solana no código de produção") e nas regras ArchUnit e do enforcer; o modo "Assinada (padrão)" esbarra em "NEVER executar ordens no navegador depois que o bot é ativado"; "sem G3 nesta fase" e "fundador solo é o único revisor" esbarram em `project.md` Mandated ("ALWAYS manter o trading REAL automático desabilitado até que os gates G2 e G3 ... sejam cumpridos") e em `team.md` Deployment ("a autoaprovação do responsável do projeto não satisfaz G2/G3").
  - O ciclo do bot (`bots/worker/CycleRunner.java`) roda decisão, barreira e execução numa única transação de banco em READ COMMITTED com `lock_timeout` 2 s e `statement_timeout` 5 s, e `PaperExecution` exige essa transação (`Propagation.MANDATORY`). Cotação, assinatura, envio e confirmação na Jupiter/Solana não cabem nela: o REAL precisa de uma máquina de estados de execução fora da transação do ciclo (intenção registrada, envio, confirmação, reconciliação) e de tratamento de transação ambígua, sem repetição cega após timeout (PRD §8).
  - O validador da trilha (`audit/domain/AuditEventValidator.java`, linhas 31 a 59) recusa a chave `signature` e qualquer sequência Base58 de 80 a 90 caracteres (formato de uma assinatura de transação Solana) e trata palavras Base58 de 32 a 44 caracteres que decodificam para 32 bytes como carteira (o mesmo formato de um mint, de uma conta e da carteira do bot). Registrar a assinatura da transação e os endereços on-chain na auditoria exige uma regra nova e explícita.
  - Só existem fontes simuladas (`sim-a`, `sim-b`, fixas em `MarketDataProperties.KNOWN_SOURCES`); a porta `MarketSourceAdapter` (C19) está pronta para um adaptador real, mas o G0/C-06 continua citado no código.
  - A barreira (`riskcontrol`) e a visão de risco (`pnl/api/PortfolioRiskView`) leem só o ledger PAPER (saldo virtual, exposição pelo custo, perda do dia pelos fills) e estimam o slippage com a política simulada v1; o REAL precisa de saldo on-chain e do impacto de preço da cotação.
  - O kill switch (`administration`) pausa bots pelo motivo `SYSTEM` e o ciclo checa `systemPaused()` no início; ele não cancela uma transação já enviada nem ordens condicionais mantidas fora da plataforma (Jupiter).
  - A anonimização (`privacy`, `0015-anonymization.sql`) para os bots e apaga o endereço da carteira (`wallet.address` passa a nulo); com fundos REAL, isso apagaria o destino do saque ("só para a carteira principal").
  - A chave de idempotência da ordem já inclui o modo (`uq_paper_order_key UNIQUE (tenant_id, mode, idempotency_key)`), e as tabelas append-only e a reconciliação interna são uma boa base, mas a reconciliação compara só fatos internos, sem fonte on-chain.
  - A mensagem de login fixa `memetrade.auth.chain: solana:mainnet` (`application.yml`); o pedido de "tudo em devnet antes da mainnet" precisa de configuração por ambiente.
