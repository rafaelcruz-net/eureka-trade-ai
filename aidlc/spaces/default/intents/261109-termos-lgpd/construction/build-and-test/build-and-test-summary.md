# Resumo do Build and Test

Conversa: português (Brasil). Intent `261109-termos-lgpd`, Units U1 a U5 (termos v2, retenção, novo aceite, pedidos do titular e anonimização), sobre a base do intent `261026-memetrade-saas-mvp`. Test Strategy `Comprehensive`, piso de 80% de linhas por parte (backend, `apps/web`, `apps/admin`).

## Estado do build e pré-requisitos

- Build e pré-requisitos: `build-instructions.md`. Java 21, Maven wrapper, Node LTS do `.nvmrc`, pnpm 10.18.3, Docker Desktop.
- As pendências do responsável no `scripts/verify.sh`, no `scripts/perf-audit.sh` e no `.github/workflows/load-test.yml` foram aplicadas por patch dele (commit `5d30541`). O `verify.sh` roda os 19 specs de ponta a ponta, inclusive `e2e/reacceptance`, `e2e/data-requests` e `e2e/anonymization`.
- A CI do GitHub Actions passou a rodar em todo push para `main` (até então nunca tinha rodado). A primeira execução completa verde é a do commit `63a7710` (execução `43067822342`).

## Tipos de teste

| Tipo | Arquivo de instruções | Onde roda |
|---|---|---|
| Unidade (backend, `apps/web`, `apps/admin`) | `unit-test-instructions.md` de cada Unit | `mvnw verify`, Jest; CI |
| Integração (Testcontainers, PostgreSQL real) | `integration-test-instructions.md` | `mvnw verify` (Failsafe); CI |
| Ponta a ponta (Playwright) | `integration-test-instructions.md` | `scripts/verify.sh`; job `smoke` da CI |
| Contrato (OpenAPI e tipos gerados) | `build-instructions.md` | `check:api`, testes de contrato; job `contract` da CI |
| Desempenho e carga | `performance-test-instructions.md` | `scripts/load-test.sh` local; `*LatencyIT`/`*IndexIT` na suíte |
| Segurança | `security-test-instructions.md` | Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit`; testes na suíte |
| Acessibilidade | testes de componente e specs com teclado | Jest e Playwright |

## Cobertura esperada por Unit

O piso é medido por parte, não por Unit: 80% de linhas no relatório mesclado do JaCoCo (backend) e no Jest de `apps/web` e de `apps/admin`. Cada Unit mantém seus testes obrigatórios por risco (`team.md`): a anonimização sem endereço de carteira (U5), o novo aceite antes de qualquer rota (U3), o isolamento entre tenants (U3, U4) e a trilha append-only (U2 a U5).

## Target Verification Matrix

Legenda da evidência: **Suíte** = `mvnw verify` local depois do Loop-back 1 (1326 testes de unidade e 949 de integração verdes, JaCoCo atingido) e job `backend` da CI `43067822342`; **Web** = Jest de `apps/web` (533 testes) e job `frontend`; **Admin** = job `admin` da CI; **E2E** = `scripts/verify.sh` (19 specs verdes) e job `smoke`. Os resultados de carga estão em `test-results.md`.

| Target ID | Source | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|---|
| U1-NFR1.1 | u1 security-requirements NFR1.1 | sem dado pessoal em log ou resposta | sem achado | Suíte: `LegalDocumentsHealthIndicatorTest`, `LogScanIT` | build-and-test | Met |
| U1-NFR1.2 | u1 security-requirements NFR1.2 | e-mail do encarregado só como texto e `mailto:` | conforme | Web: `legal-parts.test.tsx` | build-and-test | Met |
| U1-NFR3.1 | u1 performance-requirements NFR3.1 | páginas p95 ≤ 2 s | 146 ms | carga `legal` | build-and-test | Met |
| U1-NFR3.2 | u1 performance-requirements NFR3.2 | rotas `/api/v1/legal/**` p95 ≤ 300 ms | 14 ms | carga `legal` | build-and-test | Met |
| U1-NFR3.3 | u1 performance-requirements NFR3.3 | 0 respostas 5xx | 0 | carga `legal` | build-and-test | Met |
| U1-NFR3.4 | u1 performance-requirements NFR3.4 | `public, max-age=300` e `ETag` só em 200/304 | conforme | Suíte: `LegalDocumentsControllerIT` | build-and-test | Met |
| U1-NFR3.5 | u1 performance-requirements NFR3.5 | catálogo lido uma vez | conforme | Suíte: `LegalDocumentsControllerViewTest` | build-and-test | Met |
| U1-NFR3.6 | u1 scalability-requirements NFR3.6 | dezenas de versões em memória | conforme | Suíte: `TermsCatalogActivationTest` | build-and-test | Met |
| U1-NFR3.7 | u1 scalability-requirements NFR3.7 | 20 acessos simultâneos com cache | conforme | Web: `legal-api.test.ts`; carga `legal` (20 usuários) | build-and-test | Met |
| U1-NFR3.8 | u1 scalability-requirements NFR3.8 | sem estado compartilhado entre instâncias | conforme | Suíte: `ComposeFilesTest` | build-and-test | Met |
| U1-NFR4.1 | u1 reliability-requirements NFR4.1 | WCAG AA básico, teclado, 320 px | conforme | E2E: `e2e/legal-documents`; contraste: `shell.test.tsx` (tokens ≥ 4,5:1) | build-and-test | Met |
| U1-NFR6.1 | u1 security-requirements NFR6.1 | sem segredo nem `NEXT_PUBLIC_*` nas páginas | conforme | Web: `legal-api.test.ts`; Gitleaks sem achado | build-and-test | Met |
| U1-NFR6.2 | u1 security-requirements NFR6.2 | rotas só leitura, CORS dos dois apps | conforme | Suíte: `LegalDocumentsControllerIT` | build-and-test | Met |
| U1-NFR6.3 | u1 security-requirements NFR6.3 | 60/min por IP, `429` com `Retry-After` | conforme | Suíte: `LegalDocumentsRateLimiterIT`; E2E: `e2e/legal-documents` | build-and-test | Met |
| U1-NFR6.4 | u1 security-requirements NFR6.4 | texto sempre escapado | conforme | Web: `LegalDocument.test.tsx` | build-and-test | Met |
| U1-NFR6.5 | u1 security-requirements NFR6.5 | cabeçalhos de segurança mantidos | conforme | E2E: `e2e/legal-documents` | build-and-test | Met |
| U1-NFR6.6 | u1 security-requirements NFR6.6 | versão não revisada nunca vigente | conforme | Suíte: `CurrentTermsActivationTest` | build-and-test | Met |
| U1-NFR7.1 | u1 reliability-requirements NFR7.1 | sempre há versão vigente; falha rápida sem nenhuma | conforme | Suíte: `TermsCatalogActivationTest` | build-and-test | Met |
| U1-NFR7.2 | u1 reliability-requirements NFR7.2 | versão ignorada não derruba a API | conforme | Suíte: `TermsCatalogActivationTest` | build-and-test | Met |
| U1-NFR7.3 | u1 reliability-requirements NFR7.3 | `legalDocuments` `DEGRADED` fora de liveness/readiness | conforme | Suíte: `LegalDocumentsHealthIndicatorTest` | build-and-test | Met |
| U1-NFR7.4 | u1 reliability-requirements NFR7.4 | erro com "tentar de novo" | conforme | Web: `LegalPage.test.tsx` | build-and-test | Met |
| U1-NFR8.1 | u1 observability-requirements NFR8.1 | `INFO`/`WARN` na subida, sem o texto | conforme | Suíte: `LegalDocumentsHealthIndicatorTest` | build-and-test | Met |
| U1-NFR8.2 | u1 observability-requirements NFR8.2 | saúde `legalDocuments` | conforme | Suíte: `LegalDocumentsHealthIndicator` e seus testes | build-and-test | Met |
| U1-NFR8.3 | u1 observability-requirements NFR8.3 | métrica HTTP por rota, sem dado pessoal | conforme | Suíte: `LegalDocumentsControllerIT` | build-and-test | Met |
| U1-NFR9.1 | u1 reliability-requirements NFR9.1 | 80% de linhas; testes de ativação antes do código | piso atingido nas três partes | Suíte (JaCoCo), Web e Admin (Jest) | build-and-test | Met |
| U2-NFR1.1 | u2 security-requirements NFR1.1 | sem endereço completo na trilha e na saída | conforme | Suíte: `WalletAuditIT` | build-and-test | Met |
| U2-NFR1.2 | u2 security-requirements NFR1.2 | validador recusa carteira em `details` | conforme | Suíte: `AuditEventValidatorTest` | build-and-test | Met |
| U2-NFR1.3 | u2 security-requirements NFR1.3 | login recusado sem texto livre | conforme | Suíte: `LoginVerificationServiceTest` | build-and-test | Met |
| U2-NFR1.4 | u2 security-requirements NFR1.4 | expurgos registram só totais | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR2.1 | u2 security-requirements NFR2.1 | expurgo de sistema em todos os tenants | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR3.1 | u2 performance-requirements NFR3.1 | lote ≤ 500 ms p95 | dentro da meta | Suíte: `SessionPurgePerformanceIT` | build-and-test | Met |
| U2-NFR3.2 | u2 performance-requirements NFR3.2 | 100 mil linhas em ≤ 5 min | dentro da meta | Suíte: `SessionPurgePerformanceIT` | build-and-test | Met |
| U2-NFR3.3 | u2 performance-requirements NFR3.3 | HMAC ≤ 1 ms p95 | dentro da meta | Suíte: `WalletHasherTest`, `WalletHmacOverheadTest` | build-and-test | Met |
| U2-NFR3.4 | u2 performance-requirements NFR3.4 | login p95 ≤ 300 ms; conta com convite p95 ≤ 500 ms | verify 134 ms; com convite 224 ms | cargas `u1` e `u3` (2ª rodada, script corrigido) | build-and-test | Met |
| U2-NFR3.5 | u2 performance-requirements NFR3.5 | expurgo de sessões por índice | conforme | Suíte: `SessionPurgePerformanceIT` (`EXPLAIN`) | build-and-test | Met |
| U2-NFR3.6 | u2 scalability-requirements NFR3.6 | 100 mil linhas cumprem NFR3.1 e 3.2 | conforme | Suíte: `SessionPurgePerformanceIT` | build-and-test | Met |
| U2-NFR3.7 | u2 scalability-requirements NFR3.7 | uma só instância expurga | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR3.8 | u2 scalability-requirements NFR3.8 | índice em `wallet_hmac` | conforme | migração `0012-retention.sql`; Suíte: `WalletAuditIT` | build-and-test | Met |
| U2-NFR5.1 | u2 reliability-requirements NFR5.1 | migração só acrescenta | conforme | Suíte: `RetentionMigrationTest` | build-and-test | Met |
| U2-NFR6.1 | u2 security-requirements NFR6.1 | chave só da configuração | conforme | Suíte: `WalletHasherTest`; Gitleaks | build-and-test | Met |
| U2-NFR6.2 | u2 security-requirements NFR6.2 | `.env.example` sem valor; chave efêmera nos scripts | conforme | Suíte: `ComposeFilesTest` (`verify.sh`, `load-test.sh`, `perf-audit.sh`) | build-and-test | Met |
| U2-NFR6.3 | u2 security-requirements NFR6.3 | runbook "troca da chave HMAC da auditoria" | seção presente | `docs/privacy/u2-retencao-e-auditoria.md` § Runbook; conferência do responsável no gate | build-and-test | Met |
| U2-NFR6.4 | u2 security-requirements NFR6.4 | só o Identity lê e apaga as tabelas | conforme | Suíte: `PrivacyArchitectureTest` | build-and-test | Met |
| U2-NFR7.1 | u2 reliability-requirements NFR7.1 | expurgos diários com falha contida | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR7.2 | u2 reliability-requirements NFR7.2 | indicadores `DEGRADED` com atraso | conforme | Suíte: `PurgeHealthIndicatorTest` | build-and-test | Met |
| U2-NFR7.3 | u2 reliability-requirements NFR7.3 | recuperação na subida | conforme | Suíte: `IdentityJobRecoveryIT` | build-and-test | Met |
| U2-NFR7.4 | u2 reliability-requirements NFR7.4 | falha de expurgo não derruba a API | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR7.5 | u2 reliability-requirements NFR7.5 | sem chave válida, falha rápida | conforme | Suíte: `WalletHasherTest` | build-and-test | Met |
| U2-NFR8.1 | u2 observability-requirements NFR8.1 | três métricas sem rótulo pessoal | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR8.2 | u2 observability-requirements NFR8.2 | indicadores de saúde dos expurgos | conforme | Suíte: `PurgeHealthIndicatorTest` | build-and-test | Met |
| U2-NFR8.3 | u2 observability-requirements NFR8.3 | `INFO` por execução | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR8.4 | u2 observability-requirements NFR8.4 | evento de auditoria por execução | conforme | Suíte: `IdentityJobRunnerIT` | build-and-test | Met |
| U2-NFR8.5 | u2 observability-requirements NFR8.5 | linha `PURGE_STALE` na tabela de alertas | linha presente | `u2-retention/infrastructure-design/monitoring-design.md`; o alarme provisionado é da Observability Setup | build-and-test | Met |
| U2-NFR9.1 | u2 reliability-requirements NFR9.1 | testes antes do código; JaCoCo | conforme | plano de código da U2; Suíte: `SessionPurgeJobTest` | build-and-test | Met |
| U3-NFR1.1 | u3 security-requirements NFR1.1 | erros sem dado pessoal | conforme | Suíte: `TermsAcceptanceIT`, `LogScanIT` | build-and-test | Met |
| U3-NFR2.1 | u3 security-requirements NFR2.1 | pessoa e tenant do token | conforme | Suíte: `TermsAcceptanceIT` | build-and-test | Met |
| U3-NFR2.2 | u3 security-requirements NFR2.2 | trava só para convidado | conforme | Suíte: `TermsReacceptanceFilterTest` | build-and-test | Met |
| U3-NFR2.3 | u3 security-requirements NFR2.3 | aceite só de inserção | conforme | Suíte: `TermsAcceptanceIT` | build-and-test | Met |
| U3-NFR2.4 | u3 security-requirements NFR2.4 | volta só para caminho interno | conforme | Web: `return-path.test.tsx`; regressão `origin.test.ts` (`/bots`, `/pnl`) | build-and-test | Met |
| U3-NFR2.5 | u3 reliability-requirements NFR2.5 | trava falha fechada (`503`) | conforme | Suíte: `TermsReacceptanceFilterTest` | build-and-test | Met |
| U3-NFR2.6 | u3 reliability-requirements NFR2.6 | bots PAPER seguem durante a trava | conforme | Suíte: `ReacceptanceWorkerIT` | build-and-test | Met |
| U3-NFR3.1 | u3 performance-requirements NFR3.1 | `POST /api/v1/terms/acceptances` p95 ≤ 500 ms | 39 ms (rodadas: 134, 50, 39 ms) | carga `reacceptance` | build-and-test | Met |
| U3-NFR3.2 | u3 performance-requirements NFR3.2 | trava ≤ 5 ms p95 | 4,2 ms depois do Loop-back 1 (antes: 16,8, 8,4 e 7,0 ms) | carga `reacceptance`, 4ª rodada (temporizador `memetrade.terms.reacceptance.check`; espera por conexão p95 ≈ 1 ms); Loop-back 1 em `test-results.md` | build-and-test | Met |
| U3-NFR3.3 | u3 performance-requirements NFR3.3 | `/novo-aceite` p95 ≤ 2 s | 146 ms (páginas) | carga `legal` | build-and-test | Met |
| U3-NFR3.4 | u3 scalability-requirements NFR3.4 | trava sem estado em memória | conforme | Suíte: `TermsAcceptanceIT` | build-and-test | Met |
| U3-NFR3.5 | u3 scalability-requirements NFR3.5 | aceites concorrentes resultam em um | conforme | Suíte: `TermsAcceptanceIT` | build-and-test | Met |
| U3-NFR3.6 | u3 scalability-requirements NFR3.6 | NFR3.1 com 20 usuários | 20 usuários, aceite 39 ms | carga `reacceptance` | build-and-test | Met |
| U3-NFR4.1 | u3 reliability-requirements NFR4.1 | acessibilidade de T7 e convite; contraste | conforme | Web: `ReacceptanceScreen.test.tsx`; E2E: `e2e/reacceptance` (teclado); contraste pelos tokens (`shell.test.tsx`), sem cor fixa nas telas | build-and-test | Met |
| U3-NFR4.2 | u3 reliability-requirements NFR4.2 | erro visível com tentar de novo | conforme | Web: `ReacceptanceScreen.test.tsx` | build-and-test | Met |
| U3-NFR5.1 | u3 reliability-requirements NFR5.1 | migração `0013` só acrescenta | conforme | Suíte: `ReacceptanceMigrationTest` | build-and-test | Met |
| U3-NFR8.1 | u3 observability-requirements NFR8.1 | contador de bloqueios sem rótulo | conforme | Suíte: `TermsAcceptanceIT` | build-and-test | Met |
| U3-NFR8.2 | u3 observability-requirements NFR8.2 | `TERMS_ACCEPTED` na auditoria | conforme | Suíte: `TermsAcceptanceServiceTest` | build-and-test | Met |
| U3-NFR8.3 | u3 observability-requirements NFR8.3 | sem log por bloqueio | conforme | `TermsAcceptanceService`; Suíte: `LogScanIT` | build-and-test | Met |
| U3-NFR9.1 | u3 security-requirements NFR9.1 | versão nova exige novo aceite antes de qualquer rota | conforme | Suíte: `TermsReacceptanceRoutesIT`; E2E: `e2e/reacceptance` | build-and-test | Met |
| U3-NFR9.2 | u3 security-requirements NFR9.2 | testes antes do código | conforme | plano de código da U3; Suíte: `TermsReacceptanceFilterTest` | build-and-test | Met |
| U4-NFR1.1 | u4 security-requirements NFR1.1 | texto, contato e assinatura fora de logs | conforme | Suíte: `LogScanIT` | build-and-test | Met |
| U4-NFR1.2 | u4 security-requirements NFR1.2 | resumo de Acesso sem endereço completo | conforme | Suíte: `AccessSummaryAssemblerTest` | build-and-test | Met |
| U4-NFR2.1 | u4 security-requirements NFR2.1 | isolamento por tenant | conforme | Suíte: `DataRequestIT`, `route-registry.yml` | build-and-test | Met |
| U4-NFR2.2 | u4 security-requirements NFR2.2 | rotas administrativas só `ADMIN` | conforme | Suíte: `AdminDataRequestIT`, `AdminGuestRoutesIT` | build-and-test | Met |
| U4-NFR2.3 | u4 security-requirements NFR2.3 | verificação só com a carteira da conta | conforme | Suíte: `IdentityVerificationIT` | build-and-test | Met |
| U4-NFR2.4 | u4 security-requirements NFR2.4 | 10 pedidos por hora; 11º recebe `429` | conforme | Suíte: `DataRequestIT` | build-and-test | Met |
| U4-NFR2.5 | u4 security-requirements NFR2.5 | texto sempre como texto | conforme | Web: `DataRequests.test.tsx`; Admin | build-and-test | Met |
| U4-NFR3.1 | u4 performance-requirements NFR3.1 | criar, listar e abrir pedido p95 ≤ 500 ms | 23 ms (2980 amostras) | carga `data-requests` | build-and-test | Met |
| U4-NFR3.2 | u4 performance-requirements NFR3.2 | rotas administrativas p95 ≤ 1 s com 1000 pedidos | 16 ms (298 amostras) | carga `data-requests` | build-and-test | Met |
| U4-NFR3.3 | u4 performance-requirements NFR3.3 | prévia do resumo p95 ≤ 1 s | dentro dos 16 ms das rotas administrativas (o detalhe traz a prévia) | carga `data-requests` | build-and-test | Met |
| U4-NFR3.4 | u4 performance-requirements NFR3.4 | job de 15 min ≤ 30 s com 1000 pedidos | dentro da meta | Suíte: `DataRequestTimersIT` | build-and-test | Met |
| U4-NFR3.5 | u4 performance-requirements NFR3.5 | consultas por índice | conforme | Suíte: `DataRequestTimersIT` (`EXPLAIN`) | build-and-test | Met |
| U4-NFR3.6 | u4 scalability-requirements NFR3.6 | NFR3.2 e 3.4 com 1000 pedidos | ambas cumpridas | carga `data-requests`; Suíte: `DataRequestTimersIT` | build-and-test | Met |
| U4-NFR3.7 | u4 scalability-requirements NFR3.7 | job e expurgo uma vez por ciclo | conforme | Suíte: `DataRequestTimersIT` | build-and-test | Met |
| U4-NFR3.8 | u4 scalability-requirements NFR3.8 | criações concorrentes resultam em um | conforme | Suíte: `DataRequestIT` | build-and-test | Met |
| U4-NFR4.1 | u4 reliability-requirements NFR4.1 | acessibilidade de T5, T6 e T6a a T6c; contraste | conforme | Web: `DataRequests.test.tsx`; Admin; E2E: `e2e/data-requests`; contraste pelos tokens (`shell.test.tsx`, `tokens.test.ts`) | build-and-test | Met |
| U4-NFR4.2 | u4 reliability-requirements NFR4.2 | carregando, erro e vazio | conforme | Admin: `AdminDataRequests.test.tsx` | build-and-test | Met |
| U4-NFR5.1 | u4 reliability-requirements NFR5.1 | migração `0014` só acrescenta | conforme | Suíte: `DataRequestsMigrationTest` | build-and-test | Met |
| U4-NFR7.1 | u4 reliability-requirements NFR7.1 | expurgo diário com recuperação | conforme | Suíte: `DataSubjectRequestPurgeIT` | build-and-test | Met |
| U4-NFR7.2 | u4 reliability-requirements NFR7.2 | job idempotente | conforme | Suíte: `DataRequestTimersIT` | build-and-test | Met |
| U4-NFR8.1 | u4 observability-requirements NFR8.1 | métricas sem rótulo de pessoa | conforme | Suíte: `DataRequestMetricsTest` | build-and-test | Met |
| U4-NFR8.2 | u4 observability-requirements NFR8.2 | `dataRequests` `DEGRADED` com atraso | conforme | Suíte: `DataRequestMetricsTest` | build-and-test | Met |
| U4-NFR8.3 | u4 observability-requirements NFR8.3 | alerta `DATA_REQUEST_OVERDUE` desenhado; gauge testado | linha presente; gauge conforme | `u4-data-requests/infrastructure-design/monitoring-design.md`; Suíte: `DataRequestMetricsTest`; o alarme provisionado é da Observability Setup | build-and-test | Met |
| U4-NFR8.4 | u4 observability-requirements NFR8.4 | eventos só com identificadores | conforme | Suíte: `AdminDataRequestIT`, `LogScanIT` | build-and-test | Met |
| U4-NFR9.1 | u4 security-requirements NFR9.1 | testes antes do código | conforme | plano de código da U4; Suíte: `DataRequestServiceTest` | build-and-test | Met |
| U5-NFR1.1 | u5 security-requirements NFR1.1 | endereço completo em nenhuma tabela nem log | conforme | Suíte: `AnonymizationNoWalletLeftIT` (corrigido para Linux no commit `092fd1f`) | build-and-test | Met |
| U5-NFR1.2 | u5 security-requirements NFR1.2 | evento, log e resposta só com identificadores | conforme | Suíte: `AnonymizationObservabilityIT`, `LogScanIT` | build-and-test | Met |
| U5-NFR2.1 | u5 security-requirements NFR2.1 | só `ADMIN`, a partir de Exclusão em andamento | conforme | Suíte: `AdminAnonymizationIT` | build-and-test | Met |
| U5-NFR2.2 | u5 security-requirements NFR2.2 | conta anonimizada não entra; token antigo recusado | conforme | Suíte: `AnonymizedAccessIT`; E2E: `e2e/anonymization` | build-and-test | Met |
| U5-NFR2.3 | u5 security-requirements NFR2.3 | feedback só apagado pela função | conforme | Suíte: `FeedbackEraseFunctionIT`, `U8PrivilegesIT`, `RolesBootstrapIT` | build-and-test | Met |
| U5-NFR3.1 | u5 performance-requirements NFR3.1 | anonimização p95 ≤ 2 s | dentro da meta | Suíte: `AnonymizationLatencyIT` | build-and-test | Met |
| U5-NFR3.2 | u5 performance-requirements NFR3.2 | cinco consultas indexadas | conforme | Suíte: `AnonymizationIndexIT` | build-and-test | Met |
| U5-NFR3.3 | u5 scalability-requirements NFR3.3 | anonimização síncrona, rara | conforme | `AdminAnonymizationController`; passa com NFR3.1 | build-and-test | Met |
| U5-NFR4.1 | u5 reliability-requirements NFR4.1 | diálogo T8 acessível | conforme | Admin: `AnonymizeAccount.test.tsx` | build-and-test | Met |
| U5-NFR4.2 | u5 reliability-requirements NFR4.2 | layout e acessibilidade da U4 verdes | conforme | Admin: `AdminDataRequests.test.tsx` | build-and-test | Met |
| U5-NFR5.1 | u5 reliability-requirements NFR5.1 | ledger, ordens e trilha intactos | conforme | Suíte: `AnonymizationKeepsHistoryIT` | build-and-test | Met |
| U5-NFR5.2 | u5 reliability-requirements NFR5.2 | falha desfaz tudo | conforme | Suíte: `AnonymizationRollbackIT` | build-and-test | Met |
| U5-NFR5.3 | u5 reliability-requirements NFR5.3 | migração `0015` só acrescenta ou relaxa | conforme | Suíte: `AnonymizationMigrationIT`, `MigrationsBackwardCompatIT` | build-and-test | Met |
| U5-NFR5.4 | u5 reliability-requirements NFR5.4 | trava não obtida em 2 s falha limpo | conforme | Suíte: `AnonymizationLockTimeoutIT` | build-and-test | Met |
| U5-NFR5.5 | u5 reliability-requirements NFR5.5 | anonimizações concorrentes resultam em uma | conforme | Suíte: `AnonymizationConcurrencyIT` | build-and-test | Met |
| U5-NFR5.6 | u5 reliability-requirements NFR5.6 | login, renovação e ciclo concorrentes | conforme | Suíte: `AnonymizationConcurrencyIT` | build-and-test | Met |
| U5-NFR8.1 | u5 observability-requirements NFR8.1 | contador de anonimizações | conforme | Suíte: `AnonymizationObservabilityIT` | build-and-test | Met |
| U5-NFR8.2 | u5 observability-requirements NFR8.2 | `ACCOUNT_ANONYMIZED` na trilha | conforme | Suíte: `AnonymizedAccountEventValidatorTest`, `AnonymizationTransactionIT` | build-and-test | Met |
| U5-NFR8.3 | u5 observability-requirements NFR8.3 | `INFO` no sucesso, `ERROR` na falha | conforme | Suíte: `AnonymizationObservabilityIT` (corrigido para Linux no commit `092fd1f`) | build-and-test | Met |
| U5-NFR8.4 | u5 observability-requirements NFR8.4 | contador de falhas por motivo | conforme | Suíte: `AnonymizationObservabilityIT` | build-and-test | Met |
| U5-NFR9.1 | u5 security-requirements NFR9.1 | testes antes do código | conforme | plano de código da U5 (Passo 2); Suíte: `AnonymizationNoWalletLeftIT` | build-and-test | Met |
| TEAM-COV-API | `team.md` Testing Posture, piso de cobertura | 80% de linhas no backend | piso atingido | JaCoCo `check`: "All coverage checks have been met" | build-and-test | Met |
| TEAM-COV-WEB | `team.md` Testing Posture, piso de cobertura | 80% de linhas em `apps/web` | piso atingido | Jest `test:coverage` com limite (533 testes) | build-and-test | Met |
| TEAM-COV-ADMIN | `team.md` Testing Posture, piso de cobertura | 80% de linhas em `apps/admin` | piso atingido | job `admin` da CI `43067822342` | build-and-test | Met |
| TEAM-E2E | `team.md` Walking Skeleton e Frequência | `verify.sh` verde com todos os specs | 19 de 19 | `bash scripts/verify.sh`; job `smoke` | build-and-test | Met |
| TEAM-SEC-SECRETS | `team.md` Segurança na CI | nenhum segredo | sem achado | Gitleaks local (20 commits) e job `secrets` | build-and-test | Met |
| TEAM-SEC-SAST | `team.md` Segurança na CI | CodeQL sem Crítico ou Alto | sem achado | jobs `sast` do `security.yml` | build-and-test | Met |
| TEAM-SEC-DEPS-WEB | `team.md` Segurança na CI | `pnpm audit` sem Alto | 1 baixo, 3 médios (aviso) | `pnpm audit --audit-level high` em `apps/web` e `apps/admin` | build-and-test | Met |
| TEAM-SEC-DEPS-API | `team.md` Segurança na CI | Dependency-Check sem CVSS ≥ 7 fora das exceções | nenhum achado ≥ 7 fora de EX-001 a EX-013 | OWASP Dependency-Check 12.1.0 local (`BUILD SUCCESS`, `apps/api/target/dependency-check-run4.log`) com Tomcat 10.1.61, pgjdbc 42.7.14, Kotlin 2.4.21 e log4j 2.25.5; a CI confirma depois do push, com o caminho das exceções corrigido no `security.yml` | build-and-test | Met |
| TEAM-CI-MERGE | `org.md` Testing Posture (`feature`) | testes na CI antes do merge | — | o CI Pipeline, mais adiante neste plano, configura a CI para rodar esses testes antes do merge | ci-pipeline | N/A |

## Prontidão

- **Build:** pronto. Backend verde depois do Loop-back 1 e da troca do log4j (1326 de unidade e 949 de integração, JaCoCo atingido); `apps/web`, `apps/admin` e contrato verdes.
- **Testes:** pronto. Todas as metas aplicáveis estão `Met`; a única linha `N/A` é a execução na CI antes do merge, que é do CI Pipeline, mais adiante no plano. A meta U3-NFR3.2, que falhou na primeira execução, passou depois do Loop-back 1 (4,2 ms contra 5 ms, com pouca folga: acompanhar nas próximas execuções).
- **Implantação:** pronto para o CI Pipeline, depois do commit e da CI verde.

## Limitações e pendências

- **Ainda sem commit:** a volta da U3 (`TermsReacceptanceWarmup` e testes), as versões novas no `pom.xml`, as exceções em `security/exceptions.yml`, o pool fixo, a correção do script de carga do `u3`, a leitura da espera por conexão no cenário `reacceptance`, os relatórios em `docs/perf/` e o caminho das exceções no `security.yml` (patch aplicado pelo responsável).
- **OSS Index:** o analisador da Sonatype recusou a consulta sem login (`401`) na execução local, que rodou sem ele. Na CI ele continua ligado; se também der `401` lá, desligá-lo é decisão do responsável.
- **Rastreabilidade:** NFR8 descoberta só pelos alarmes provisionados da U2 (`PURGE_STALE`) e da U4 (`DATA_REQUEST_OVERDUE`), donos `observability-setup` (ver `cross-unit-traceability.md`).
- **Spring Boot 4:** as exceções EX-001 a EX-012 vencem em 2027-02-09 e voltam a bloquear; a migração precisa acontecer antes disso.
- **Alarmes `PURGE_STALE` e `DATA_REQUEST_OVERDUE`:** desenhados; o provisionamento é da Observability Setup.
- **Runbook da chave HMAC (U2 NFR6.3):** a seção existe em `docs/privacy/u2-retencao-e-auditoria.md`; o responsável confere na aprovação deste estágio. O passo no RB-09 é da Incident Response.
