# Instruções de testes da Unit — U2 `u2-real-bot-pnl`

## Estratégia e volume

- **Test Strategy:** `Standard`. Cada componente tem de 5 a 8 testes de unidade, com o caminho feliz e pelo menos 2 casos de erro ou de borda. As fronteiras principais (migrações, ledger em fluxo, barreira, ciclo, bloco REAL, auditoria) têm testes de integração com Testcontainers e PostgreSQL 17 real.
- **Piso:** 80% de cobertura de linhas, medido em separado. No `apps/api`, o JaCoCo junta unidade e integração. No `apps/web`, vale o `coverageThreshold.global.lines = 80` do Jest. `apps/admin` e `apps/txsigner` não mudam e continuam acima do piso. O piso nunca é reduzido.
- **Ordem (Testing Contract `custom`):** os testes vêm antes do código em toda regra de dinheiro e da lógica REAL:
  - `real_entry_slot` só de inserção e o `recordSlot`;
  - a chave de ordem de rede e a herança;
  - o caminho só de quantidade e o `RealCost`;
  - o `RealCostBasis` e a paridade com três oráculos;
  - a barreira e o ciclo com a atribuição falhando e a fotografia adiada;
  - o rateio, a linha "Sem bot", as taxas, o resultado, o período e a conferência;
  - os exemplos das histórias, os cenários do FR5.4, a regressão da condicional e o teste de propriedade.
  
  Nas demais camadas (auditoria limitada, métricas, logs, contrato, tela, IaC, carga), os testes vêm depois de cada camada.
- **Testes obrigatórios por risco que a U2 toca:**
  - isolamento entre contas, inclusive o bot de outra pessoa (404) e a leitura de custo em segundo plano com o tenant da ordem;
  - REAL e PAPER separados e nunca somados;
  - ledger e `real_entry_slot` sem edição;
  - P&L com cenários determinísticos em unidades base;
  - preço ausente vira `unknown`, nunca zero;
  - limites e risco: a compra falha fechada com custo `Unknown`;
  - saque, venda e liquidação nunca bloqueados pela atribuição;
  - REAL desligado por padrão continua verde.

## Ferramentas

| Parte | Ferramentas |
|---|---|
| `apps/api` | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL 17), ArchUnit, JaCoCo, Mockito; propriedade com `java.util.Random` e sementes fixas, no padrão do `LedgerPropertyTest` |
| `apps/web` | Jest com Testing Library (`jest.config.ts`) |
| Carga | `node:test` em `scripts/load/*.test.mjs`; o cenário roda só no workflow manual `load-test` |
| Ponta a ponta | Playwright, com o validador Solana local, via `scripts/verify.sh` |

## Antes do primeiro teste: conferir os runners (Etapa 1)

Todos os comandos rodam a partir da raiz do repositório. Cada runner é conferido com um teste que já existe na mesma área, antes de qualquer teste novo:

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='LedgerPropertyTest' -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealLedgerPositionsIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
pnpm --dir apps/web exec jest src/features/pnl/PnlModeBlock.test.tsx
```

```bash
node --test scripts/load/load-test-u6-plans.test.mjs
```

Se um deles falhar, a Etapa 2 não começa: a falha do runner é corrigida primeiro.

## Comandos desta Unit

Todos cobrem só os arquivos da U2. Os nomes seguem o `code-generation-plan.md`; se um nome mudar na geração, este arquivo muda junto.

### Fase 1 — backend, testes de unidade

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='NetworkOrderKeyTest,ReadDeadlineTest,RealCostTest,RealCostMethodQuantityTest,RealCostBasisTest,RealCostParityTest,WalletHasherTest,LedgerRealSlotMetricsTest,RealRiskPolicyTest,RealGateInputsReaderTest,RealRiskJobsTest,RealCycleCostUnknownTest,RealCostArchitectureTest,LedgerArchitectureTest,ArchitectureTest,RealRiskArchitectureTest,RealExecutionArchitectureTest,NeverSumModesArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### Fase 1 — backend, testes de integração

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealEntrySlotAppendOnlyIT,RealEntrySlotGrantsIT,Migration0023AdditiveIT,Migration0023IndexValidIT,Migration0023IndexRestartIT,U2DatabaseRolesIT,MigrationsIT,AnonymizationMigrationIT,RecordSlotIT,SlotConflictAuditIT,RealLedgerNetworkOrderIT,RealLedgerNetworkOrderPlanIT,RealLedgerPositionsIT,RealLedgerIT,AttributionFailureGateIT,RealCycleAttributionIT,AttributedCostConsistencyIT,AttributedCostTenantIT,DaySnapshotDeferredIT,RealGateInputsReaderIT,RealRiskJobsIT,TransactionalRealRiskGateIT,LiquidationIT,ConfirmationAndAmbiguousIT,RealSettlementIT,FillPostingIT,U2PropertiesBindingIT,RealAttributionMeasurementIT,RealRiskMeasurementIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

O `RealAttributionMeasurementIT` e o `RealRiskMeasurementIT` só registram o p95 no log; eles falham só quando uma avaliação falha (NFR1.8). A comparação com as metas é do Build and Test.

### Fase 2 — backend, testes de unidade

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='RealProrationTest,RealBotPnlExamplesTest,RealPnlFeesTest,RealPnlUnownedTest,RealPnlPeriodBoundaryTest,RealPnlReconciliationTest,ConditionalSaleRegressionTest,RealPnlPropertyTest,RealBotPnlTest,RealBotPnlQueryTest,ReconciliationFingerprintTest,PnlRealMetricsTest,ModePnlViewTest,U2LogCaptureTest,PnlPlanSqlParityTest,RuntimeSettingsGuardMismatchPoolTest,AuditArchitectureTest,NeverSumModesArchitectureTest,ModeBoundaryArchitectureTest,ArchitectureTest,GuestOpenApiContractTest,RealOpsAlarmParityTest,ComposeFilesTest,LoadtestPnlSeedProfileGuardTest,RealLamportPricesTest' -Dsurefire.failIfNoSpecifiedTests=false
```

O `RealPnlPropertyTest` roda pelo menos 1.000 sequências com 3 sementes fixas e termina em até 60 s na CI. A semente e o índice da sequência aparecem na mensagem de falha.

### Fase 2 — backend, testes de integração

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealBlockFailureIT,RealPnlTenantIsolationIT,RealPnlIdempotentReadIT,RealNeverPaperIT,RealPnlReadOnlyIT,RealPnlRouteIT,ReconciliationAuditLimitIT,MismatchAuditPoolContextIT,MismatchAuditAfterTheBlockIT,U2MetricsExportIT,U2PropertiesBindingIT,ModePnlFailureIT,ModePnlLatencyIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

O `CorrelationParityIT` do plano foi fundido no `RealBlockFailureIT`. O `MismatchAuditAfterTheBlockIT` (revisão R-01) confere que o evento de divergência só é gravado depois que a transação do bloco terminou.

### Frontend

```bash
pnpm --dir apps/web exec jest src/features/pnl/PnlModeBlock.test.tsx src/features/pnl/PnlScreen.test.tsx
```

O tipo gerado é conferido com `pnpm --dir apps/web run check:api` depois de regenerar o `schema.d.ts`.

### Carga (`node:test`)

```bash
node --test scripts/load/load-test-pnl-by-mode.test.mjs scripts/load/load-test-pnl-plans.test.mjs
```

O cenário `pnl-by-mode` em si roda só no workflow manual `load-test`, disparado pelo fundador depois de aplicar o texto do NFR1.9 (`gh workflow run load-test -f scenario=pnl-by-mode`). Esta Unit não edita `.github/workflows/`.

### Ponta a ponta e ponto de controle

O spec `e2e/zz-paper-real-unico/paper-real-unico.spec.ts` roda só pelo `verify.sh` e continua verde com o bloco REAL da U2 (NFR7.6). A U2 não muda o `scripts/verify.sh`.

```bash
bash scripts/verify-checkpoint.sh
```

Ele roda primeiro o `verify.sh` local. Se a máquina não aguentar (memória), a prova é o run verde do `ci` do mesmo commit, com a árvore limpa fora dos registros do AI-DLC e o commit no remoto (`team.md`). Ele roda no fim da fase 1 (Etapa 15) e no fim da Unit (Etapa 28).

## Metas de cobertura

| Parte | Meta | Ferramenta |
|---|---|---|
| `apps/api` | ≥ 80% de linhas, unidade e integração mescladas | JaCoCo (`mvnw verify`) |
| `apps/web` | ≥ 80% de linhas | Jest (`coverageThreshold.global.lines = 80`) |
| Pacotes novos do núcleo (`pnl.real`, `ledger.api` novos) | cada regra BR1.1 a BR10.5 com teste; o piso de 80% vale para o módulo inteiro | JaCoCo |

## Dublês (mocks e stubs)

- **Cálculos puros** (`RealCostMethod`, `RealCostBasis`, `RealProration`, montagem dos eventos, conferência): sem dublê. Os testes montam sequências de `NetworkOrderedEntry` e de `OrderAttribution` à mão, em lamports e unidades base.
- **Oráculos da paridade** (`RealCostParityTest`): escritos no próprio teste, sem `RealCostBasis` nem `RealProration`.
- **`RealBotPnl`** (unidade): dublês das portas `RealLedger.streamInNetworkOrder`, `RealExecutionPort.streamSettledOrders`, `BotDirectory`, a leitura das carteiras e o `marketdata.api`. O dublê de preços conta as consultas (NFR1.13).
- **Relógio:** sempre o `Clock` injetado, inclusive no `ReadDeadline`. O caso "tempo esgotado entre dois blocos de 500 linhas" usa um `Consumer` de teste que avança o relógio falso na linha 501.
- **Tempo esgotado do banco:** `pg_sleep` acima do orçamento num duplo da leitura, para provocar o `57014` real.
- **Rede:** nenhuma. O slot da confirmação vem dos dublês `FakeRealChain`, `RecordedChain` e `FakeChain`, com respostas gravadas do RPC.
- **Auditoria limitada:** o teste prende as conexões do pool `pnl-mismatch-audit` ou do pool principal com conexões próprias; o gancho de teste do escritor devolve o `pg_backend_pid()` da trava e do `insert`.
- **Web:** fixtures em `features/pnl/fixtures.ts` com os motivos novos, tiradas do contrato gerado.

## Dados de teste

- **Exemplos das histórias:** os bots seguem a convenção de `stories.md` (A criado em 2026-11-01T10:00Z; B e C em 2026-11-02T10:00Z, com o id de B menor que o de C). Cada exemplo reproduz exatamente o resultado do `rules.md`.
- **Carteiras, mints e assinaturas:** sintéticos, nunca reais. As assinaturas são Base58 de 64 bytes geradas no código de teste.
- **Medições:** 21 carteiras com 10 posições (2 sem preço) e 2 anos de ordens na densidade do NFR1.14 (4 por bot por dia), semeadas por cada IT.
- **Carga:** a semente do `LoadtestPnlSeed` só existe nos perfis `test,loadtest` (guarda em `LoadtestPnlSeedProfileGuardTest`).
- **Independência:** cada teste de integração limpa os próprios dados e não depende da ordem de execução. Os testes de migração que mexem em `databasechangelog` usam contêiner próprio.
