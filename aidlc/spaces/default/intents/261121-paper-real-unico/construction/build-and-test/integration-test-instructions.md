# Instruções dos testes de integração — intent `261121-paper-real-unico`

Test Strategy do intent: `Standard` (unidade mais integração nas fronteiras principais; ponta a ponta, desempenho e segurança onde os requisitos pedem). O piso de 80% de linhas por parte soma-se à estratégia (`team.md`, Testing Posture).

## 1. Ferramentas

- **Backend:** JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL 17 com as migrações Liquibase da aplicação; promtool por digest para T20), ArchUnit. Os ITs (`*IT`) rodam no failsafe; a cobertura mescla unidade e integração no JaCoCo (`jacoco-merged.exec`).
- **Web:** Jest 30 com Testing Library, `fetch` simulado, relógio e fuso injetáveis.
- **Ponta a ponta:** Playwright 1.56.1 dentro do `scripts/verify.sh`, com o Compose de teste, o validador Solana local, o KMS simulado e o swap gravado.
- **Determinismo:** relógio injetável, sementes fixas, nenhuma rede real; nenhum teste usa devnet, mainnet, KMS real ou chave real.

## 2. Fronteiras cobertas entre Units

| Fronteira | Units | Testes de integração principais |
|---|---|---|
| C4 `GET /api/v1/pnl/by-mode`: forma por modo (U1), bloco REAL calculado (U2), `market` e `RANGE` (U3) | U1, U2, U3 | `ModePnlFailureIT`, `ModePnlLatencyIT`, `RealBlockFailureIT`, `PnlByModeMarketRouteIT`, `PnlByModeMarketFailureIT`, `PnlViewsLatencyIT` |
| Cálculo REAL por bot (C10, `RealBotPnl`) usado por C4, C5 e C6 | U2, U3 | `RealPnlRouteIT`, `PnlCompareEqualsByModeIT`, `BotPnlLinesSumIT`, `BotPnlLinesCursorStabilityIT`, `RealPnlIdempotentReadIT`, `RealPnlReadOnlyIT` |
| Leitura do ledger REAL em ordem de rede (C12) e slot (C14, `real_entry_slot`) | U2, U4 | `RealLedgerNetworkOrderIT`, `RealLedgerNetworkOrderPlanIT`, `RecordSlotIT`, `SlotConflictAuditIT`, `Migration0023AdditiveIT`, `Migration0023IndexValidIT`, `RealEntrySlotAppendOnlyIT` |
| Atribuição (C13, C8) nos caminhos `GATE`, `CYCLE` e `PNL` | U2, U4 | `AttributionFailureGateIT`, `AttributedCostConsistencyIT`, `AttributedCostTenantIT`, `RealCycleAttributionIT` |
| Posição própria do bot (C15, `RealBotHoldings`) e o ciclo REAL | U2, U4 | `RealBotHoldingsParityIT`, `RealBotHoldingsOrderPathParityIT`, `RealBotHoldingsTenantIT`, `RealBotHoldingsIsolationIT`, `RealBotHoldingsDeadlineIT`, `OwnQuantitySellIT`, `OwnQuantityNoOwnIT`, `OwnQuantityExitsIT`, `OwnQuantityOneTransactionIT`, `OwnQuantitySellDefaultIT`, `OwnQuantityFlagOffIT`, `RealOpenSellsIT`, `Migration0026IT`, `Migration0027IndexRestartIT` |
| Criação nos dois modos (C1), elegibilidade (C2), limites (C3) | U1 | `BotCreateRealIT`, `BotCreateConcurrencyIT`, `BotModePathIT`, `RealEligibilityRouteIT`, `RealAccountLimitsRouteIT`, `BotRoutesIT` |
| Isolamento entre contas e auditoria de 404 | U1 a U4 | `RealPnlTenantIsolationIT`, `BotPnlLinesTenantIT`, `PnlCompareTenantIT`, `PnlBotCountsTenantIT`, `BotDirectoryOwnedByAnotherTenantIT`, `BotNotFoundTimingParityIT` |
| Auditoria limitada e pool próprio | U2, U3 | `MismatchAuditAfterTheBlockIT`, `ReconciliationAuditLimitIT`, `MismatchAuditPoolContextIT`, `ReadRefusalAudit*` |
| Regras de alerta (Prometheus) e paridade com o CloudWatch | U2, U4 | `RealOpsOwnHoldingsAlertRuleIT` (T20, `promtool test rules`), `RealOpsAlarmParityTest`, `CiPromtoolDigestTest` |
| Navegador → API → ledgers PAPER e REAL (fatia do intent) | U1 a U4 | `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts` (20 casos, último da suíte pelo prefixo `zz-`) |

## 3. Como rodar

Suíte completa (a prova oficial é a CI do commit, porque nesta máquina ela morre por memória):

```bash
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify      # 2.511 de unidade e 1.684 de integração em fc65b7f
bash scripts/verify.sh                                     # 62 specs Playwright
```

Direcionado, por classe, quando a máquina aguenta (padrão dos `unit-test-instructions.md` das Units):

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify \
  -Dit.test='RealBlockFailureIT,PnlByModeMarketRouteIT,RealBotHoldingsParityIT' \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

Medidas (informativas na CI; a decisão contra a meta é desta etapa):

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='ModePnlLatencyIT,PnlViewsLatencyIT,RealAttributionMeasurementIT,RealRiskMeasurementIT' \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealBotHoldingsMeasurementIT' \
  -Du4.measure.reads=10000 -Du4.measure.cycles=100 \
  -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true   # medida oficial da U4 (NFR1.5)
```

## 4. Metas de cobertura e de resultado

- 0 falhas em unidade, integração e ponta a ponta.
- 80% de linhas por parte, medido pela ferramenta (JaCoCo no `apps/api` e no `apps/txsigner`, `coverageThreshold.global.lines = 80` no Jest de cada app). As exclusões ficam na configuração da ferramenta; o piso nunca baixa.
- Os testes obrigatórios por risco do `team.md` continuam verdes (ver a matriz em `build-and-test-summary.md`, linhas `TGT-RISK-*`).

## 5. Dados de teste

- Fixtures e sementes só no código de teste ou nos perfis `test`/`loadtest` (guardas: `RealSeedProfileGuardTest`, `DefaultProfileHasNoSeedIT`, `RecordedSwapProfileGuardTest`). Carteiras, mints e dados pessoais sempre sintéticos.
- Semente de referência das medidas: 1 pessoa, 10 bots (5 PAPER, 5 REAL), 730 dias, 4 ordens por bot por dia (29.200 ordens). U4: 5 bots REAL, 10 mints, cerca de 14.400 ordens; semente A com zeragens, semente B sem nenhuma.
- Cada IT cria os próprios dados e não depende da ordem; o spec de ponta a ponta que muda a conta do smoke roda por último (`zz-`, `project.md`).
