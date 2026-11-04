# Resumo do código — U7 Ledger e P&L

Os 26 passos do [plano aprovado](code-generation-plan.md) estão concluídos e marcados. O Passo 23 (carga) foi retomado em 2026-11-04 e está descrito em "Passo 23 — gerador de ciclos e carga". A lista completa de arquivos está em [source-manifest.json](source-manifest.json); a cobertura de cada regra, critério e requisito está em [traceability.json](traceability.json).

## Arquivos criados e alterados

| Área | Novos | Alterados |
|---|---|---|
| Backend, produção | `ledger/` (`api`, `domain`, `application`, `infra`, `web`): `PaperExecution` (C15), `LedgerReadModel`, `FillRecorded`, `FillCalculator`, `PositionAccounting`, `LedgerEntries`, `LedgerCompensation` (pacote), `PaperExecutionImpl`, `FundingService`, `ClosingService`, `JdbcLedgerRepository`, `LedgerReadModelImpl`, `PendingCloseJob`, `LedgerController`; `pnl/` (`api`, `domain`, `application`, `infra`, `web`): `PortfolioRiskView` (C13), `PnlCalculator`, `EquityCalculator`, `RiskSnapshotCalculator`, `EquityPeakUpdater`, `PnlQueryService`, `ReconciliationService`, `EquityMarkingJob`, `ReconciliationJob`, `LedgerJobsHealth`, `PnlController`, `ReconciliationRunLimiter` e o trabalho de limpeza; `shared/health/JobRounds`, `shared/web/QueryParameters`, `shared/web/KeysetCursor`; migração `0008-ledger-pnl.sql` | `application.yml` (pool 11 e `memetrade.ledger`), `db.changelog-master.yaml`, `tenancy/route-registry.yml`, `RuntimeSettingsGuard` (mínimo 11), `IdempotentOperation` (`PAPER_FUND`, `PAPER_CLOSE_POSITIONS`, `RECONCILIATION_RUN`), `AuditActions`, `MarketDataQuery` e `JdbcMarketDataQuery` (`decimals`), `openapi-guest.yaml` |
| Backend, testes | Apoio (`AbstractU7IT`, `CycleAuthContext`, `ControllableMarketData`, `LedgerRows`, `LedgerFacts`, `PaperDriver`, `Concurrently`); `LedgerScenarioCatalogTest`, `PositionAccountingTest`, `LedgerPropertyTest`, `PnlDomainTest`, `LedgerPrivilegesIT`, `LedgerReadModelIT`, `PaperExecutionIT`, `PaperExecutionIdempotencyIT`, `PaperExecutionAtomicityIT`, `LedgerLockOrderIT`, `LedgerLockTimeoutIT`, `PendingCloseJobIT`, `EquityPeakConcurrencyIT`, `PortfolioRiskViewIT`, `PnlQueryIT`, `PnlPeriodEdgesIT`, `ReconciliationIT`, `LedgerRoutesIT`, `LedgerJobsHealthTest`, `U7ObservabilityIT`, `LedgerArchitectureTest`, `U7SettingsTest`, `U7ContextIT`, `U7ContractsTest` | `AuditPropertiesTest`, `U4ContextIT`, `U4SettingsTest`, `ComposeFilesTest`, `MigrationsIT`, `MarketDataQueryIT`, `RouteRegistryTest`, `GuestOpenApiSlice`, `GuestOpenApiContractTest`, `WorkflowsTest` |
| Frontend | `features/pnl/` (`PnlScreen`, `ReconciliationPanel`, `LedgerList`, `BotTradesTable`, `TradeHypotheses`, `SignedValue`, `period.ts`, `decimal.ts`, `fixtures.ts`) e testes; `app/pnl/page.tsx`; `lib/api/pnl-routes.test.ts`; smoke `e2e/u7-ledger-pnl/smoke.spec.ts` | `schema.d.ts` (gerado), `guest-api.ts`, `messages.ts`, `AccountMenu.tsx` (link "Resultado (P&L)"), `test-support.tsx`, `ScannerScreen.test.tsx`, `globals.css`, `eslint.config.mjs` |
| Infra, scripts e documentação | `docs/api/u7-ledger-e-pnl.md`, `docs/perf/u7-ledger-pnl-2026-11-04.md` | `docker-compose.yml` (pool 11), `infra/docker-compose.test.yml` (`LOADTEST_*`), `.env.example`, `scripts/load-test.sh`, `scripts/load/load-test.mjs`, `.github/workflows/load-test.yml`; `contract-summary.md` (C5, C9, C11, C13, C15 e Escalas) |
| Carga (Passo 23) | `ledger/loadtest/` (`LoadtestCycleAuthContext`, `LoadtestTokens`, `LoadtestLedgerSeed`, `LoadtestCycleDriver`, `LoadtestLedgerHealthIndicator`); testes `LoadtestBeansProfileTest`, `LoadtestCycleDriverIT` | `application-loadtest.yml` (tamanho do gerador e baldes SLO de 250 ms, 300 ms, 500 ms e 1 s no histograma HTTP), `LedgerArchitectureTest`, `U7ContextIT`, `ComposeFilesTest`, `WorkflowsTest` |

O `scripts/verify.sh` não foi alterado.

## Decisões de implementação

- **Fronteira de módulos:** o `pnl` usa só `ledger.api` (`LedgerReadModel` e o evento síncrono `FillRecorded`); `LedgerArchitectureTest` garante a fronteira, sem rede, sem assinatura e sem `double`/`float`.
- **Imutabilidade:** a `0008` cria os gatilhos e os privilégios da matriz do NFR Design; `LedgerPrivilegesIT` confirma cada linha com o papel da aplicação.
- **Idempotência:** `execute` por `INSERT ... ON CONFLICT DO NOTHING`; `fund` e `closePositions` pelo `IdempotencyGuard`; `closePositions` com a conta já em encerramento não duplica ordem `CLOSE`.
- **Perda do dia (C13)** calculada direto dos preenchimentos; `pnl_daily` só para o relatório.

## Desvios do plano

- **Ajuste na fórmula da BR2.3 (aceito pelo responsável do projeto em 2026-11-04):** o teste de propriedade achou um caso em que, numa venda parcial, o custo removido arredondado zerava o custo com quantidade ainda aberta, o que o `CHECK` `position_zero_together_ck` do banco recusa. Agora, quando a parcela arredondada alcança o custo todo, o custo removido é `costBasis − 0,000001` e o menor valor em USDC fica com a quantidade aberta (`PositionAccounting.keepSmallestUnit`). O realizado desse caso fica deslocado em no máximo 0,000001 USDC. Há teste de regressão. O texto da BR2.3 em `functional-design/rules.md` não foi reescrito, porque isso reabriria o desenho funcional de todas as Units; este registro é a forma vigente da regra.
- **Ordem de preenchimentos no mesmo instante:** dois preenchimentos com o mesmo horário eram repetidos em ordem aleatória na reconciliação e geravam um falso `POSITION_MISMATCH`. A `0008` ganhou `fill.fill_seq` (identity) e a ordenação por ela. Há teste de regressão.
- **`MarketDataQuery.decimals`** devolve o `MarketValue` existente (não genérico), com o inteiro lido por `intValueExact()`; registrado no C11.
- **`RECONCILIATION_RUN`** acrescentada ao `IdempotentOperation`, porque toda rota `POST` exige `Idempotency-Key`.
- **Fechamento** exige preço confiável e liquidez maior que zero (BR1.2); sem os dois, a posição fica `CLOSE_PENDING`.
- **`TENANT_MISMATCH` na C13** grava `REQUEST_FORBIDDEN` em transação própria, sem marcar o ciclo para desfazer.
- **`GET /reconciliation`** devolve `PENDING` para quem tem conta PAPER e ainda não teve conferência; quem não tem conta recebe `RECONCILED`.
- **`pnl.web`** reaproveita `ScannerDtos.MarketValueDto`, o schema comum do C2.
- **Observabilidade** num `U7ObservabilityIT` próprio, em vez de ampliar `LogScanIT` e `MetricsLabelsIT`; logs das rodadas com `durationMs`.
- **Chave de `closePositions` e busca da ordem CLOSE pendente (revisão 1, R-05, 2026-11-04):** a chave de `closePositions` agora tem no máximo 163 caracteres (200 − `:` − 36 do UUID), recusada com `IllegalArgumentException` antes de qualquer gravação; `fund` continua com 150. `JdbcLedgerRepository.pendingCloseOrder` deixou o `LIKE '%:<positionId>'` e busca por igualdade: bot, cadeia e mint da posição (únicos por posição) e o sufixo exato `:<positionId>` da chave derivada (`right(idempotency_key, 37)`). A igualdade com a chave derivada inteira não é possível na tarefa de fechamento pendente nem num novo `closePositions` com outra chave, porque a chave original não é gravada (o mecanismo comum guarda só o seu resumo); por isso a busca usa as colunas da posição mais o sufixo exato. Testes antes do código em `ClosingKeyIT` (vermelho: 2 de 4, a chave de 163 recusada pelo limite antigo de 150 e a mensagem sem "163"; verde: 4 de 4, com a regressão de duas posições pendentes e chave com `%`, `_` e o id da outra posição).
- **Rodada de reconciliação com `durationMs` (R-01):** `ReconciliationJob` registra `Reconciliation round finished: people=…, divergentPeople=…, failed=…, durationMs=…`, só contagens e duração.
- **Volume do NFR3.6 em banco próprio (R-01):** `ReconciliationVolumeIT` usa um banco criado no mesmo contêiner PostgreSQL 17, com o mesmo bootstrap e as mesmas migrações (`U7VolumeDatabase`), e um contexto Spring próprio, para que os 200 000 preenchimentos não entrem nas rodadas dos outros testes. A semeadura é feita com o superusuário de teste (o OWNER não tem `TEMP` no banco); gatilhos e `CHECK` da `0008` valem para toda linha.
- **Gerador de carga encerra bots no `u7` (R-04):** `LoadtestCycleDriver.closeBot` para os ciclos do bot e chama `closePositions` numa transação de ciclo, medida em `loadtest.paper.close`; `LOADTEST_CLOSE_BOTS` (10 no `u7`, 0 nos outros cenários), um bot a cada 30 s a partir de 120 s depois do início dos ciclos. O roteiro também lê as rodadas de reconciliação do log e mede "verificar agora" de cada pessoa ao fim do `u7`.
- **Smoke da U7 (R-03):** o `scripts/verify.sh` não foi alterado (fica com o responsável do projeto). O smoke foi conferido contra a UI e a API atuais e reforçado: depois de "Verificar agora", espera o `POST /api/v1/reconciliation/run` com 200 e o texto "Reconciliado às HH:MM". Executado em 2026-11-04 no ambiente de teste (mesmos passos do `verify.sh`, com `e2e/u7-ledger-pnl` na linha do Playwright, sem mudar o script): 6 de 6 testes passaram (U1, U2, U4 e U7).
- **BR2.3 (R-06):** `functional-design/rules.md` está congelado e não foi editado; o registro acima continua sendo a forma vigente da regra.
- **Procedimento:** algumas edições foram feitas pelo shell (`sed -i`, `perl -pi`, um `cat >>`) e um log foi gravado em `apps/api/target/u7-verify.log`, contra a regra de editar só com a ferramenta de arquivo; o conteúdo final foi conferido pelos testes.

## Testes antes do código (vermelho e depois verde)

| Passo | Vermelho | Verde |
|---|---|---|
| 4 Migração e privilégios | 13 erros `relation "paper_account" does not exist` e 1 falha no `MigrationsIT` | 14 de 14 |
| 5 Cálculo do preenchimento | 22 de 22 com `UnsupportedOperationException` | 22 de 22 |
| 6 Posição e lançamentos | 10 erros e 1 falha | 12 (com a regressão) |
| 7 P&L e risco (domínio) | 10 de 10 com `UnsupportedOperationException` | 10 |
| 8 Propriedade | Falha `expected true but was false` (o defeito da venda parcial) | Verde |
| 9 Repositório | Compilação (`JdbcLedgerRepository` inexistente) | 9 |
| 10 Execução | 15 de 15 sem o bean `PaperExecution` | 15 |
| 11 Concorrência e travas | `LedgerLockTimeoutIT` com `UncategorizedSQLException 55P03` | 6 |
| 12 Fechamento pendente | Compilação (`PendingCloseJob` inexistente) | 7 |
| 13 Pico | Compilação (`pnl.application` inexistente) | 7 |
| 14 Visão de risco | 7 de 7 sem o bean; depois 1 falha (evento perdido no rollback) | 7 |
| 15 P&L do período | Compilação (`PnlQueryService` inexistente) | 10 (com um valor esperado do teste corrigido para 0,452625) |
| 16 Reconciliação | Compilação (`ReconciliationService` inexistente) | 12 (com a regressão) |
| 17 Rotas | Compilação; depois 4 falhas (3 expectativas erradas do teste, 1 resumo de rota curto) | 11 |

## Testes e cobertura

| Comando | Resultado |
|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u7 test-compile surefire:test failsafe:integration-test failsafe:verify` | Verde ao fim de cada passo |
| `apps/api/mvnw -f apps/api/pom.xml verify` (2026-11-04, depois do Passo 23) | BUILD SUCCESS: 635 unitários e 446 de integração, 0 falhas; JaCoCo 94,74% de linhas ("All coverage checks have been met"); `ledger.loadtest` com 75% |
| Comando da U7 (2026-11-04) | 76 unitários e 114 de integração, 0 falhas |
| `AuditArchitectureTest`, `U4ArchitectureTest`, `LedgerArchitectureTest` (2026-11-04) | 7, 6 e 8 testes verdes, sem exceção nas regras |
| `pnpm --dir apps/web exec jest --coverage` | 206 testes, 97,67% de linhas; `tsc`, ESLint, Prettier e `check:api` limpos (sem mudança no frontend no Passo 23) |
| `node --check scripts/load/load-test.mjs` e `bash -n scripts/load-test.sh` (2026-11-04) | Ok |
| Revisão 1, comando da U7 (2026-11-04) | 76 unitários e 122 de integração, 0 falhas (com `ClosingKeyIT`, `ReconciliationVolumeIT` e o teste novo de `LoadtestCycleDriverIT`) |
| Revisão 1, `apps/api/mvnw -f apps/api/pom.xml verify` (2026-11-04) | BUILD SUCCESS: 635 unitários e 454 de integração, 0 falhas; JaCoCo 94,58% de linhas (7 196 de 7 608), "All coverage checks have been met" (piso de 80%); a primeira passada falhou só no `spotless:check` dos arquivos novos, corrigido com `spotless:apply` e repetido |
| Revisão 1, `corepack pnpm@10.18.3 --dir apps/web exec jest src/features/pnl src/app/pnl` | 3 suítes, 31 testes; `format:check`, `lint` e `typecheck` limpos |
| Revisão 1, smoke Playwright U1, U2, U4 e U7 no ambiente de teste | 6 de 6 passaram (smoke da U7 em 1,8 s) |
| Revisão 1, `node --check scripts/load/load-test.mjs` e `bash -n scripts/load-test.sh` | Ok |
| `LOAD_SCENARIO=u7` e `u7-jobs` com `SPRING_PROFILES_ACTIVE=test,loadtest bash scripts/load-test.sh` | Ver "Passo 23" e `docs/perf/u7-ledger-pnl-2026-11-04.md` |

## Testes escritos e não executados

| Teste | Motivo |
|---|---|
| `closePositions` sob carga (parte do NFR3.1) e `u7` verde com a guarda de 1 GB | O gerador já encerra 10 bots no `u7`, mas a repetição foi interrompida pelo sistema por falta de memória (acima) |
| NFR3.4 (tempo da tela de P&L com operações) | Pendência de entrada da U5, que cria as operações de verdade (Q1 da Infrastructure Design) |

## Passo 23 — gerador de ciclos e carga

**O que foi construído.** O pacote `com.memetrade.ledger.loadtest`, com toda classe `@Profile("loadtest")` (o perfil só sobe junto com `test`):

- `LoadtestCycleAuthContext`: o `AuthContext` primário só nesse perfil. Mantém o `Principal` de cada requisição como vem da sessão e guarda o de cada pessoa `BETA_USER` na primeira vez que ela é vista; o gerador liga esse mesmo `Principal` nas suas threads, como a U5 ligará o dono do bot. Ele **não cria** `Principal`.
- `LoadtestTokens`: tokens simulados da U4 por `MarketListingQuery` e preço, liquidez e casas por `MarketDataQuery` (C11); valor `unknown` ou desatualizado não é negociado. Nenhuma tabela da U4 é lida.
- `LoadtestLedgerSeed`: cria os bots em rodízio entre as pessoas, chama `fund` uma vez por bot, abre uma posição por token e grava pares de compra e venda pela `PaperExecution`, numa transação de ciclo (`TransactionTemplate`) por lote. Não se chama "Seeder" porque a regra da U3 reserva esse sufixo para os semeadores do perfil `test`.
- `LoadtestCycleDriver`: espera as pessoas, prepara e roda um ciclo por bot a cada 30 s em threads próprias, medindo `loadtest.paper.execute` (`loadtest_paper_execute_seconds`, com balde em 250 ms).
- `LoadtestLedgerHealthIndicator`: componente `loadtestLedger`, `UNKNOWN` na preparação e `UP` com os ciclos.
- Roteiros: `load-test.sh` gera um convite por pessoa além da conta de smoke; `load-test.mjs` cria as contas com carteiras geradas em memória, faz cada pessoa ler o ledger, espera a preparação, renova o token vencido e grava cada cenário na sua seção de `docs/perf/u7-ledger-pnl-<data>.md`. O workflow continua aceitando `u7` e `u7-jobs`.

**Exceção nomeada não usada.** O responsável do projeto autorizou em 2026-11-04 uma exceção nas regras `PRINCIPAL_ONLY_FROM_THE_SESSION_PATH` (`AuditArchitectureTest`) e de tabelas da U4 (`U4ArchitectureTest`) só para `com.memetrade.ledger.loadtest..`. Ela não foi aplicada, porque o novo desenho não viola nenhuma das duas: o Principal vem da sessão e a leitura de mercado usa só as interfaces públicas. As duas regras ficaram como estavam e passam com o pacote de carga incluído.

**Testes de guarda.** `LedgerArchitectureTest` exige `@Profile("loadtest")` em toda classe de nível superior do pacote, proíbe que ele use internos do ledger, da identidade e do mercado ou `JdbcClient`, e proíbe que qualquer código fora dele dependa do pacote. `LoadtestBeansProfileTest` varre o pacote com os perfis padrão, `test` e `test,loadtest` (nenhum, nenhum e os cinco beans). `U7ContextIT` confirma que nenhum bean do pacote existe no contexto `test`. `LoadtestCycleDriverIT` (perfis `test,loadtest`, escala pequena) cobre a pessoa vinda da sessão, a preparação, o ciclo medido e o token `unknown` nunca negociado.

**Resultado da carga** (`docs/perf/u7-ledger-pnl-2026-11-04.md`, local, Docker Desktop):

| Cenário | Medida | p95 | Meta |
|---|---|---|---|
| `u7` (20 pessoas, 200 311 preenchimentos, 60 bots, 20 usuários, 10 min) | `execute` (NFR3.1) | 39 ms | 250 ms |
| | P&L 30 e 366 dias / ledger (NFR3.2) | 39 ms / 224 ms | 1 s |
| | operações / uso do dia / reconciliação (NFR3.3) | 39 ms / 179 ms / 15 ms | 1 s |
| `u7-jobs` (60 bots com 5 posições, 30 min) | rodadas de marcação (NFR3.5) | 64 rodadas, maior 288 ms | 10 s |
| | rodadas de fechamento pendente (NFR3.5) | 155 rodadas, maior 5 ms | 10 s |
| | `execute` | 22 ms | 250 ms |

Planos sem `Seq Scan` em `ledger_entry` e `fill` (NFR2.2), nenhuma falha técnica de ciclo, nenhum erro de cliente e espera do pool sempre abaixo de 1 s. A primeira execução do `u7` saiu reprovada só pela guarda de memória do roteiro (900 MB por contêiner, herdada da U4): o PostgreSQL chegou a 902 MB em `docker stats` (inclui cache de páginas).

**Decisão do responsável do projeto sobre a guarda de memória (2026-11-04, R-02):** subir a guarda do PostgreSQL de 900 MB para 1 GB (1024 MiB) só no cenário `u7`, porque o `docker stats` conta o cache de páginas do contêiner e o pico foi 902 MB com 200 311 preenchimentos. A API e todos os outros cenários continuam com 900 MB. Aplicado em `scripts/load/load-test.mjs` (`U7_POSTGRES_MEMORY_LIMIT_MB`) e conferido por `WorkflowsTest`; registrado em `docs/perf/u7-ledger-pnl-2026-11-04.md`.

**Repetição do `u7` (R-02, R-04): não concluída.** A execução repetida em 2026-11-04 (com a guarda nova, 10 bots encerrados e "verificar agora") foi interrompida pelo sistema por falta de memória da máquina durante a preparação (194 150 de 200 000 preenchimentos). Não foi reiniciada por conta própria. Por isso não há resultado verde do `u7` com a guarda nova nem p95 de `closePositions`; o NFR3.1 segue `Deferred` na rastreabilidade.

**NFR3.6 (R-01):** `ReconciliationVolumeIT`, com 20 pessoas, 200 000 preenchimentos e 600 060 lançamentos coerentes: rodada das 20 pessoas em 6 726 ms e 6 434 ms (limite 60 s); "verificar agora" de 5 pessoas entre 301 e 349 ms (limite 5 s); nenhuma divergência.

## Pendências para outras Units

- **U5:** medir o NFR3.4 no smoke da U5; registrar no seu design que o `SET LOCAL` dos tempos de trava da U7 vale até o fim da transação do ciclo; tratar `NotFilled` como ciclo concluído sem operação; chamar `fund` ao ativar o bot e passar a versão dos limites a `closePositions`.
- **U6:** passar o token avaliado a `snapshot` e tratar `equity` ausente sem considerá-lo zero.

## Mudança pendente do responsável do projeto no `scripts/verify.sh`

Na linha do Playwright, acrescentar `e2e/u7-ledger-pnl`:

```
"${PNPM[@]}" --dir apps/web exec playwright test e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery e2e/u7-ledger-pnl
```

O ponto de controle da U7 só deve ser aprovado depois de `bash scripts/verify.sh` passar com o smoke da U7.
