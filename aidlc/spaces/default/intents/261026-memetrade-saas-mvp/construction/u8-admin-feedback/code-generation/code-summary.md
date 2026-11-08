# Resumo da geração de código — U8 Administração e feedback

Unit `u8-admin-feedback`, gerada a partir do plano aprovado (`code-generation-plan.md`). A lista completa de arquivos está em [`source-manifest.json`](source-manifest.json), e o rastreio de cada AC, BR e NFR em [`traceability.json`](traceability.json). O documento técnico da Unit fica em [`docs/api/u8-admin-feedback.md`](../../../../../../../../docs/api/u8-admin-feedback.md).

## Situação dos passos

| Passos | Situação |
|---|---|
| 1 a 19 | Concluídos e marcados no plano |
| 20 — CI | **Parcial, não marcado.** `security.yml`, `dependabot.yml` e `WorkflowsTest` entraram. O hook do framework recusou a edição do `.github/workflows/ci.yml`; as linhas exatas estão em `docs/api/u8-admin-feedback.md` ("Mudanças no `ci.yml`") |
| 21 — Suítes e cobertura | Concluído: web, admin e backend completos verdes; cobertura do backend 93,72% de linhas (ver "Resultados") |
| 22 — Documentação | Concluído e marcado |

## Arquivos principais

- **Backend, módulo `administration`:** kill switch (`KillSwitchService`, `KillSwitchApplier`, `ApplyLock`, `KillSwitchReconciler`, `KillSwitchStartup`, `JdbcKillSwitchStore`), estado do sistema (`SystemStatusAssembler`, `SystemStatus`), métricas, saúde e rotas `/api/v1/admin/kill-switch`, `/system-status` e `/tenants`.
- **Backend, módulo `feedback`:** `FeedbackService`, `JdbcFeedbackStore`, `FeedbackPurgeJob` com pool de retenção próprio, métricas, saúde e rotas `POST /api/v1/feedback` e `GET /api/v1/admin/feedback`.
- **Backend, `identity`:** entrada do administrador (`AdminLoginService`, `AdminAuthController`, `AdminWallets`, `AdminLoginLimiter`, `AdminRefreshCookie`, `AdminCorsPolicy`); desafio com escopo pelo domínio; renovação do convidado recusa sessão que não seja `BETA_USER`.
- **Interfaces públicas novas:** `RejectionCounts` (U6), `AuditBacklog` (U2), `IdentityAdministration.tenantNames` e `maskedAdministratorWallet` (U3).
- **Banco:** `0011-admin-feedback.sql` (6 changesets, só acréscimo) e o grupo `feedback_retention` em `infra/postgres/bootstrap/10-roles.sql`.
- **`apps/admin` (novo):** Next.js com lockfile próprio, tipos gerados só de `openapi-admin.yaml`, CSP com nonce, telas de estado, convites, kill switch e feedback, faixa "Kill switch ACIONADO" e "Acesso restrito".
- **`apps/web`:** botão e diálogo de feedback no menu da conta, `check:bundle` (GuestBundleTest) e smoke `e2e/u8-admin-feedback`.
- **Infra:** serviço `admin` no `docker-compose.yml` (127.0.0.1:3001), `ADMIN_WALLET_ADDRESSES` no overlay de teste, `ADMIN_APP_ORIGIN` no `.env.example`, pool do agendador 16.

## Decisões principais

- **Ordem do kill switch:** grava o estado e o histórico numa transação; depois aplica fora dela com lock consultivo de sessão em pool próprio de uma conexão; a reconciliação a cada 30 s sempre reaplica o estado gravado.
- **Cerca contra aplicação antiga:** `reconcile_marked_at` gravado com `clock_timestamp()` do banco. A aplicação só grava o resultado se nenhuma marcação nova chegou durante ela (`MARKED_DURING_APPLY` fica para a reconciliação).
- **Limite do feedback sem corrida:** lock consultivo de transação por pessoa (`pg_advisory_xact_lock(0x55384642, hashtext(user_id))`) em vez de `FOR UPDATE` em `app_user`, para não ler tabela de outro módulo.
- **Pool do lock de aplicação com `minimumIdle=0`:** a conexão abre na primeira aplicação e não fica ociosa. Mudado no Passo 21 depois que a suíte completa esgotou as conexões do PostgreSQL de teste.
- **Pool do estado do sistema** fica fora do contexto como `Executor` para não desligar o executor padrão do Spring Boot.

## Saídas vermelhas antes do código (testes primeiro)

- **Passo 2 (antes do primeiro vermelho):** `U8SettingsTest` 6/6.
- **Passo 5 — kill switch:** testes de unidade 22 executados, 4 falhas e 18 erros (`UnsupportedOperationException: arrives in Passo 6`); ITs 20 executados, 5 falhas e 14 erros (só `KillSwitchCrashIT.theWorstCaseFitsFortyFiveSeconds` passou); os PUTs devolviam 404.
- **Passo 7 — feedback (deduplicação e limite):** `FeedbackServiceTest` 14 executados, 3 falhas e 11 erros; ITs 10 executados, 9 falhas (o 403 com token de administrador já passava pelo filtro existente).

## Resultados

### Por classe (execuções focadas, todas verdes)

| Área | Resultado |
|---|---|
| Kill switch | `KillSwitchServiceTest` 13/13, `KillSwitchReconcilerTest` 9/9, `ApplyLockIT` 6/6, `KillSwitchCrashIT` 5/5, `KillSwitchFencingIT` 6/6, `KillSwitchRaceIT` 1/1 (50 repetições), `KillSwitchPerformanceIT` 2/2 (60 bots, 20 execuções: p95 254 ms, máximo 365 ms) |
| Feedback | `FeedbackServiceTest` 14/14, `FeedbackDedupIT` 8/8, `FeedbackRateLimitIT` 1/1 (exatamente 10 de 20 em 50 repetições), `FeedbackConcurrencyIT` 1/1 (p95 76 ms, máximo 86 ms), `AdminFeedbackListIT` 4/4, `FeedbackPurgeIT` 6/6 |
| Entrada do administrador | `AdminAuthIT` 17/17; regressão do convidado: `AuthChallengeIT` 5, `AuthSessionIT` 8, `AuthVerifyIT` 8, `LoginVerificationServiceTest` 14, `SessionServiceTest` 13, `ChallengeServiceTest` 5, `AuthenticationFilterTest` 5 |
| Rotas e estado | `RouteRegistryTest` 9/9, `SystemStatusIT` 5/5, `AdminCorsIT` 4/4, `AdminGuestRoutesIT` 3/3, `AdminReadLatencyIT` 1/1 (p95: estado 52 ms, convites 15 ms, kill switch 12 ms, feedback 16 ms) |
| Contrato, observação e arquitetura | contratos OpenAPI 29/29, `U8ObservabilityIT` 4/4, arquitetura 62/62 (`U8ArchitectureTest` 9) |
| Infra e CI | `ComposeFilesTest` 22/22, `WorkflowsTest` 10/10; `docker compose config` das duas composições OK |

### Suíte completa do backend (`apps/api/mvnw -f apps/api/pom.xml verify`)

- **1ª execução:** testes de unidade 872/872 verdes. ITs: 736 executados, 7 falhas e 15 erros, `BUILD FAILURE`. Causas:
  - **Conexões esgotadas** (`remaining connection slots are reserved for roles with the SUPERUSER attribute`, `CannotGetJdbcConnection`): derrubou `LedgerLockTimeoutIT`, `LoadtestCycleDriverIT` (6), `PaperExecutionAtomicityIT` (2), `PendingCloseJobIT`, `EquityPeakConcurrencyIT`, `ReconciliationVolumeIT`, `ScannerRateLimitIT` (3), `RiskPurgeIT` (4) e `LoadtestRiskCycleDriverIT`. Os contextos da U8 em cache somaram pools além das 100 conexões padrão. Correção: lock de aplicação com `minimumIdle=0` e `max_connections=300` só no contêiner de teste (`AbstractPostgresIT`, fora de `SERVER_PARAMETERS`, que é igual ao Compose).
  - **`AuditJobLockIT.theLocksOfTheTwoJobsAreIndependent`:** a tabela `shedlock` passou a ter a linha `adminKillSwitchReconcile`; o teste agora filtra `AUDIT_%`.
  - **`AdminFeedbackListIT.theListIsNewestFirstWithTheTenantNameAndNeverThePerson`:** supunha que os dois comentários eram os mais novos do banco compartilhado; agora adianta o relógio para depois do comentário mais novo existente.
- **2ª execução (depois das correções):** interrompida pelo sistema por falta de memória antes de terminar. Até a interrupção: unidade 872/872 verdes; 218 classes de IT executadas; sem nenhum erro de conexão. Duas falhas apareceram:
  - `AdminFeedbackListIT` (empate no mesmo instante com comentário de outra classe; corrigido em seguida trocando `isAfter` por `!isBefore`, **só compilado, não executado de novo**);
  - `JdbcAuditEventReaderIT.bothPreparedFormsReadFromTheIndexWithoutASeparateSort` (U2): o planejador escolheu outro plano (`Index Scan using audit_event_time_id_idx` com filtro de `action`). Passou na 1ª execução; depende do volume de dados do banco compartilhado. **Não corrigido** (ponto em aberto).
- **Ajustes depois da 2ª execução:**
  - `AdminFeedbackListIT` agora percorre a lista inteira e confere só os próprios dois comentários e a ordem entre eles, sem depender do que outras classes deixaram no banco.
  - `JdbcAuditEventReaderIT` agora insere 300 linhas de outra ação na janela e roda `analyze audit_event` antes de conferir o plano, para o índice de ação ser sempre o caminho mais barato.
- **3ª execução (final):** `BUILD SUCCESS`. Unidade 872/872, integração 738/738, `jacoco:check` "All coverage checks have been met", **93,72% de linhas** no backend.

### Frontend

| App | Testes | Cobertura de linhas | Demais |
|---|---|---|---|
| `apps/web` (Jest completo) | 260/260 (24 suítes) | 95,59% (comandos 95,39%, ramos 89,1%, funções 92,17%) | typecheck, lint e formato OK; `check:bundle` varreu 856 arquivos sem texto administrativo |
| `apps/admin` (Jest completo) | 85/85 (10 suítes) | 91,34% (comandos 90,52%, ramos 83,6%, funções 85,71%) | typecheck, lint, formato e `check:api` OK |

O `ScannerScreen.test.tsx` (U4) foi ajustado: o botão de feedback entra na ordem do Tab entre "Conta" e "Sair".

## O que não rodou

- **`bash scripts/verify.sh`:** não executado. O sistema estava com pouca memória, e o smoke da U8 depende das mudanças do `verify.sh` que o responsável do projeto aplica.
- **`next build` local de `apps/web` e `apps/admin`:** compilam ("Compiled successfully"), mas a cópia de symlinks da saída `standalone` falha no Windows com `EPERM`. É uma limitação do ambiente local; na CI (Linux) o build roda inteiro.
- **Playwright do smoke `e2e/u8-admin-feedback`:** escrito, com typecheck, lint e formato OK; não executado.

## Desvios do plano

1. Escopo do desafio pelo domínio do app do administrador (`localhost:3001`) em vez de uma coluna nova.
2. Lock por pessoa do feedback com `pg_advisory_xact_lock` em vez de `FOR UPDATE` em `app_user` (fronteira de módulo).
3. ShedLock pelo `LockingTaskExecutor`, não pela anotação `@SchedulerLock`.
4. Token de cerca do kill switch pelo `clock_timestamp()` do banco.
5. Motivos de `LOGIN_REJECTED` da entrada administrativa: `NONCE_INVALID` e `SIGNATURE_INVALID`.
6. Campo extra no corpo do feedback (por exemplo `tenantId`) devolve 400 (corpo fechado) em vez de ser ignorado, como diziam BR6.2 e NFR5.1. O tenant continua vindo só do token.
7. Saúde do expurgo do feedback usa o último sucesso em memória (referência: início do processo).
8. Sem Playwright dentro do `apps/admin`; o smoke roda pelo `apps/web`.
9. `MigrationsBackwardCompatIT` confere colunas só de acréscimo e o SQL anterior, sem subir a imagem anterior.
10. `apps/admin` sem workspace pnpm, com lockfile próprio.
11. Renovação do convidado agora recusa sessões que não sejam `BETA_USER`.
12. Marcação durante a aplicação vira `MARKED_DURING_APPLY` e fica para a reconciliação.
13. `ci.yml` não alterado (hook); `scripts/verify.sh` não alterado (protegido). As linhas exatas estão no documento da Unit.
14. Teste: `max_connections=300` no contêiner PostgreSQL de teste.
15. Smoke: o marcador do feedback vem de `SMOKE_FEEDBACK_MARKER` (passado pelo `verify.sh`) e não entra no motivo do kill switch.

## Pontos em aberto

- **CORS do convidado sem `PUT`:** o assistente da U5 usa `PUT /bots/{id}/draft` entre origens, mas o `GuestCorsPolicy` não permite `PUT`. Isso existia antes da U8; o `AdminCorsIT` não afirma nada sobre isso.
- **Build local no Windows:** `EPERM` na saída `standalone` do Next.
- **Ações do responsável do projeto:** aplicar as mudanças do `verify.sh` e do `ci.yml` e rodar o `verify.sh`.

## Volta 1 de Build and Test (2026-11-07)

- **Achado T1:** o `traceability.json` estava no formato `unit`/`basePath`/`links` com `target` em texto livre, e o leitor de rastreabilidade o recusava. Por isso, todos os IDs da U8 apareciam como não cobertos.
- **Correção:**
  - o arquivo foi reescrito no formato `stage`/`unit`/`upstream_ids`/`coverage`, com as mesmas 91 linhas e os mesmos status (85 `OK`, 3 `N/A`, 3 `Deferred`);
  - cada `target` `OK` agora é um único arquivo existente, o primeiro da lista original;
  - o texto original de cada linha ficou em `note`.
- **Código:** nenhum código de produção ou de teste mudou nesta volta.
- **Conferência:** `aidlc engine sensor-traceability --stage build-and-test` não recusa mais o arquivo da U8. Os IDs da U8 ainda não cobertos são só os `Deferred` já registrados (NFR6.7, NFR12.1, NFR13.1).
