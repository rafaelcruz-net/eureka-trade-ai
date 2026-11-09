# Varredura de código do desenvolvedor — eureka-trader-ai

Intent: `termos-lgpd` (termos de uso v2, política de privacidade LGPD, pedidos de titulares e anonimização).
Varredura completa (sem base de conhecimento anterior: NO_STORE), profundidade Standard, commit
`ffde88f89326bb3bb4bad499c0c36496ea2bc077`. Nenhum teste, build ou programa foi executado; só leitura de arquivos.

## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply**:
  - `apps/api/src/main/java/com/memetrade/identity/` (termos, convites, criação de conta, sessão, login, idempotência)
  - `apps/api/src/main/java/com/memetrade/audit/` (publicador, validador, relay, expurgo, limpeza da saída)
  - `apps/api/src/main/java/com/memetrade/feedback/` (envio, listagem administrativa, expurgo de 180 dias)
  - `apps/api/src/main/java/com/memetrade/shared/` (máscara de carteira e de logs, erros, filtros web)
  - `apps/api/src/main/resources/` (application*.yml, logback, `terms/v1`, `tenancy/route-registry.yml`)
  - `apps/api/src/main/resources/db/changelog/` (as 11 migrações, com gatilhos e privilégios)
  - `apps/api/pom.xml`
  - `infra/postgres/` (bootstrap dos logins e papéis de retenção)
  - `apps/web/src/features/account/`, `apps/web/src/features/auth/`, `apps/web/src/middleware.ts`
  - `apps/admin/src/` (estrutura, sessão, convites, feedback)
  - `docker-compose.yml`, `infra/docker-compose.test.yml`, `.env.example` (só nomes de variáveis)
  - `.github/workflows/`, `.github/dependabot.yml`, `.githooks/`, `security/exceptions.yml`, `.gitleaks.toml`
  - `README.md`, `docs/adr/`, `docs/api/`
- **Skimmed only**:
  - `apps/api/src/main/java/com/memetrade/{marketdata,scanner,riskassessment,riskcontrol,bots,decision,ledger,pnl,administration,ops}/` (classes listadas, contratos públicos, jobs e SQL de expurgo lidos; regras de negócio internas não lidas linha a linha)
  - `apps/api/src/test/` (estrutura, nomes das classes, testes de arquitetura)
  - `apps/web/src/features/{bots,dashboard,pnl,scanner,feedback}/`, `apps/web/src/lib/`, `apps/web/e2e/`
  - `scripts/` (verify.sh, load-test.sh, perf-audit.sh, setup.sh, scripts/load/)
  - `apps/*/Dockerfile`, `apps/*/package.json`, configs de lint/jest/tsconfig
  - `PRD_MemeTrade_Analytics_v1.3_SaaS.md` (índice e trecho sobre LGPD), `PromptInicial.txt`
- **Left out**:
  - `node_modules/`, `.next/`, `apps/api/target/`, `coverage/`, `test-results/`, `playwright-report/` (saídas e dependências; ignorados pelo `.gitignore`)
  - `.tools/`, `.vscode/`, `.idea/` (ferramentas locais e IDE; ignorados)
  - `.env` (segredos locais; ignorado)
  - `docs/perf/`, `load-test-report.json` (relatórios gerados; excluídos por `.aidlc-source-paths.json` e pela instrução)
  - `PRD_MemeTrade_Analytics_v1.3_SaaS.pdf` (cópia binária do PRD em Markdown)
  - `apps/web/pnpm-lock.yaml`, `apps/admin/pnpm-lock.yaml` (lockfiles gerados)
  - Instalação do AI-DLC: `.claude/`, `.codex/`, `.agents/`, `aidlc/`, `AGENTS.md` (todo marcado AI-DLC), seção AI-DLC do `.gitignore`

### Packages Found
- `apps/api` (`com.memetrade:memetrade-api`) — serviço (monólito modular, processos `api` e `worker` na mesma imagem) — Java 21 — API REST, jobs agendados, ciclo dos bots
  - `shared` — utilitários transversais (relógio, erros RFC 7807, correlação, máscara de carteira, filtros)
  - `identity` — convites, termos, contas, login por assinatura, sessões, idempotência comum, limites de taxa
  - `audit` — tabela de saída (outbox), trilha append-only, consulta administrativa, expurgo de 12 meses
  - `marketdata` — fontes simuladas `sim-a`/`sim-b`, coleta, histórico, campos `unknown`
  - `scanner` — lista e avaliação de tokens para o convidado
  - `riskassessment` — indicador de risco por pesos versionados
  - `riskcontrol` — barreira de ordens (limites, recusas), expurgo de avaliações aprovadas
  - `bots` — bots PAPER, comandos, ciclo de 5 min no worker, canal ao vivo (SSE)
  - `decision` — motor de decisão determinístico, expurgo de HOLD/NO_TRADE
  - `ledger` — ordens, fills e ledger PAPER append-only, fechamento pendente
  - `pnl` — P&L, pico de patrimônio, reconciliação
  - `administration` — kill switch e estado do sistema
  - `feedback` — comentários dos convidados
  - `ops` — `MigrationRunner` (perfil `migrate`) e sonda de saúde
- `apps/web` (`memetrade-web`) — UI — TypeScript/React (Next.js) — app do convidado (porta 3000)
- `apps/admin` (`memetrade-admin`) — UI — TypeScript/React (Next.js) — app administrativo (porta 3001)
- `infra/` — infraestrutura — SQL/shell — bootstrap de logins e papéis do PostgreSQL, sobreposição de teste do Compose
- `scripts/` — ferramentas — bash/Node — verificação da fatia, carga, desempenho
- `apps/web/e2e` — testes — TypeScript (Playwright) — smokes por Unit (U1 a U9)

### Build System
- **Type**: Maven (Wrapper) no backend; pnpm 10.18.3 com Node 24 nos dois frontends; Docker Compose para o ambiente local
- **Config Files**: `apps/api/pom.xml`, `apps/api/mvnw`, `apps/api/.mvn/`, `apps/web/package.json`, `apps/admin/package.json`, `pnpm-lock.yaml` (dois), `.nvmrc`, `docker-compose.yml`, `infra/docker-compose.test.yml`, `apps/*/Dockerfile`
- **Build Dependencies**: `apps/web` e `apps/admin` geram `src/lib/api/schema.d.ts` a partir de `apps/api/openapi/openapi-guest.yaml` e `openapi-admin.yaml`; os YAML são gerados pelo backend (`GuestOpenApiContractTest`/`AdminOpenApiContractTest` com `-Dopenapi.write=true`); `db-migrate` usa a imagem `memetrade-api`; `api` e `worker` dependem de `db-migrate`, que depende de `db-bootstrap` e `postgres`

### APIs Discovered
- REST convidado (`/api/v1/**`, sem `/admin`) — `apps/api` + `openapi/openapi-guest.yaml` — 25 operações (incluindo o canal SSE `/api/v1/stream/dashboard`)
- REST administrativa (`/api/v1/admin/**`) — `apps/api` + `openapi/openapi-admin.yaml` — 13 operações
- Interfaces Java públicas entre módulos (pacotes `*.api`) — contratos internos (por exemplo `AuditEventPublisher`, `IdempotencyGuard`, `IdentityAdministration`, `OrderGate`, `PaperExecution`, `BotAdministration`, `MarketDataQuery`)
- Registro de rotas e jobs por escopo de dados — `apps/api/src/main/resources/tenancy/route-registry.yml`

### Frameworks & Libraries
- Spring Boot — 3.5.16 — web, validação, JDBC (JdbcClient), actuator
- Liquibase — gerenciado pelo Boot — migrações SQL formatadas
- PostgreSQL driver — gerenciado pelo Boot — acesso a dados e leitura de nome de constraint
- springdoc-openapi — 2.8.17 — geração do OpenAPI 3.1
- nimbus-jose-jwt — 10.10 — token de acesso HMAC
- ShedLock — 6.10.0 — execução única dos jobs
- Resilience4j (retry, timelimiter) — 2.3.0 — chamadas às fontes de mercado
- logstash-logback-encoder — 8.1 — logs JSON com máscara Base58
- Micrometer Prometheus — gerenciado pelo Boot — métricas
- Next.js — 15.5.28; React — 19.2.9; wallet-standard (`@wallet-standard/app` 1.1.0, `@solana/wallet-standard-features` 1.3.0)
- Teste: JUnit 5, Testcontainers, ArchUnit 1.5.1, solanaj 1.28.0 (só escopo de teste), Jest 30.2.0, Testing Library, Playwright 1.56.1

### Test Coverage
- **Test Directories**: `apps/api/src/test/java/com/memetrade/**` (318 arquivos, 136 `*IT`), `apps/api/src/test/resources/`, `apps/web/src/**/*.test.ts(x)` (33), `apps/admin/src/**/*.test.ts(x)` (10), `apps/web/e2e/` (smokes U1 a U9), `scripts/load/*.test.mjs`
- **Test Frameworks**: JUnit 5 + Testcontainers (PostgreSQL 17) + ArchUnit; Jest + Testing Library (jsdom); Playwright
- **Coverage Config**: presente — JaCoCo `check` com 80% de linhas no `verify` do Maven (exclusões fixas no pom); `coverageThreshold` de 80% de linhas em `apps/web/jest.config.ts` e `apps/admin/jest.config.ts`

### Code Quality Indicators
- **Linting**: Spotless + palantir-java-format 2.80.0 (fase `verify`); ESLint 9 (`eslint.config.mjs`) e Prettier 3.6.2 (`.prettierrc.json`) nos dois apps; TypeScript `strict`
- **CI/CD**: `.github/workflows/ci.yml` (backend verify, frontend web, contrato OpenAPI/tipos, smoke `verify.sh`, Playwright completo), `.github/workflows/security.yml` (Gitleaks, CodeQL, OWASP Dependency-Check, pnpm audit), `.github/workflows/load-test.yml` (manual), `.github/dependabot.yml`; ações fixadas por SHA; permissões só leitura
- **Documentation**: README em pt-BR (desatualizado em partes), 5 ADRs, 7 anexos de contrato em `docs/api/`; Javadoc rico e rastreável a BR/NFR em quase toda classe

### Technical Debt Signals
- Endereço de carteira COMPLETO gravado em `audit_event.details` (e no payload de `audit_outbox`) nos eventos `ACCOUNT_CREATED`, `LOGIN_SUCCEEDED` e `LOGIN_REJECTED` (convidado e administrador) — `identity/domain/login/InviteAccountCreation.java:155`, `LoginVerificationService.java:128,175`, `AdminLoginService.java:207`; a trilha recusa UPDATE para qualquer login (migração `0005`), então não há anonimização no lugar, só exclusão após 12 meses
- `login_challenge` (endereço completo e mensagem assinada) e `user_session`/`session_used_refresh_digest` crescem sem expurgo: não há job que apague linhas expiradas
- Nenhuma rota ou serviço desativa, exclui ou anonimiza conta; `app_user.status = 'DISABLED'` e `tenant.status = 'SUSPENDED'` existem no esquema, mas nenhum código os escreve
- Tabelas financeiras e de risco (`ledger_entry`, `fill`, `paper_order`, `order_rejection`, `decision` etc.) referenciam `app_user(user_id)` sem `ON DELETE` e várias recusam DELETE para todos os logins: `app_user` não pode ser apagado
- `wallet.address` é `NOT NULL`, único e tem CHECK de Base58 (32 a 44): substituir o endereço por marcador exige migração
- Aceite dos termos só é verificado na criação de conta com convite; o login sem convite ignora `acceptedTermsVersion` (BR3.8) — não existe fluxo de reaceite quando a versão vigente muda
- `terms_acceptance` é insert-only para a aplicação (REVOKE UPDATE/DELETE/TRUNCATE) e `uq_terms_acceptance_invite` limita a um aceite por convite
- CI não roda lint, typecheck, Jest nem `check:api` do `apps/admin`; Jest do `apps/web` na CI cobre só `auth`, `scanner`, `lib` e `middleware`
- Classes só de teste/carga no código de produção, protegidas por perfil: `SmokeAccountSeeder`, `SmokeInviteSeeder` (`@Profile("test")`), pacotes `*/loadtest/`
- README desatualizado: lista só 5 módulos, diz que `scripts/perf-audit.sh` não existe (existe) e cita Gitleaks 8.28.0 (`.gitleaks-version` fixa 8.30.1)
- 0 TODO/FIXME; 2 supressões (`@SuppressWarnings("checkstyle:ParameterNumber")` em `CycleRunner`, `eslint-disable-next-line` em `BotDetail.tsx`)

## Handoff Summary
- **Intent-relevant finding**: Os termos são arquivos versionados em `apps/api/src/main/resources/terms/<versão>/` (`version.properties` com `current=true` em exatamente uma pasta, `terms.txt`, `risk-notice.txt`), carregados uma vez na subida por `identity/infra/TermsCatalog.java` (a API não sobe sem uma versão vigente) e expostos por `CurrentTerms`. O aceite é gravado só em `InviteAccountCreation.createAccount` (tabela insert-only `terms_acceptance`, migração `0007`), e a tela Conta (`GET /api/v1/account`, `AccountScreen.tsx`) mostra a versão aceita e seu texto. Não há política de privacidade, rota de titular, exclusão ou anonimização em nenhum lugar do código. O dado pessoal central é o endereço público da carteira: completo em `wallet`, `login_challenge`, `idempotency_record.scope` (pré-login, expira com o desafio), `audit_event.details` e `audit_outbox.payload`; mascarado (4…4) em logs, telas e `invite.issued_by`. O texto livre do feedback (`feedback_entry.text`, 180 dias) e o nome do bot (`bot.name`) também podem conter dado pessoal.
- **Risks / follow-up**: (1) A trilha `audit_event` recusa UPDATE para qualquer login e DELETE dentro de 12 meses; anonimizar a carteira nela exige mudar o gatilho da migração `0005` ou aceitar retenção legal de 12 meses com base documentada. (2) `app_user` não pode ser excluído (FKs sem `ON DELETE` a partir de tabelas append-only); anonimização deve preservar o `user_id` como pseudônimo e tratar `wallet`, `login_challenge`, sessões e feedback. (3) Migrações são expand-contract, aplicadas só pelo `db-migrate` com o login dono; a aplicação nunca migra. Novos expurgos precisam de papel de retenção próprio (padrão `audit_retention`/`risk_retention`/`feedback_retention` em `infra/postgres/bootstrap/10-roles.sql`) e entrada em `route-registry.yml`, cuja ausência quebra o build. (4) Uma nova versão dos termos é uma NOVA pasta, nunca edição de `v1`; contas existentes não reaceitam hoje. (5) Toda rota nova exige teste negativo entre tenants (escopo CLIENT) ou de 403 para convidado (escopo ADMIN) no registro de rotas.
