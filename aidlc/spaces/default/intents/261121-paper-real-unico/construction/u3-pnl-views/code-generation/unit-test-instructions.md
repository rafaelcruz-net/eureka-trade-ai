# Instruções de testes da Unit — U3 `u3-pnl-views`

## Antes do primeiro teste: conferir os runners (Etapa 1)

Todos os comandos rodam a partir da raiz do repositório. Cada runner é conferido com um teste que já existe na mesma área, antes de qualquer teste novo:

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='ModePnlViewTest' -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='ModePnlFailureIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
pnpm --dir apps/web exec jest src/features/pnl/period.test.ts src/features/pnl/PnlScreen.test.tsx
```

```bash
node --test "scripts/load/*.test.mjs"
```

O padrão do `node --test` fica entre aspas, para o Node expandir. A forma com diretório (`node --test scripts/load`) falha no Node 24 do `.nvmrc`.

Se um deles falhar, a Etapa 2 não começa: a falha do runner é corrigida primeiro.

## Estratégia e volume

- **Test Strategy:** `Standard`. Cada componente tem de 5 a 8 testes, com o caminho feliz e pelo menos 2 casos de erro ou de borda. As fronteiras principais (C4 com `market` e `RANGE`, `listBots`, C5, C6, auditoria das recusas, `ownedByAnotherTenant`) têm testes de integração com Testcontainers e PostgreSQL 17 real, com as migrações da aplicação.
- **Piso:** 80% de cobertura de linhas, medido em separado. No `apps/api`, o JaCoCo junta unidade e integração. No `apps/web`, vale o `coverageThreshold.global.lines = 80` do Jest. `apps/admin` e `apps/txsigner` não mudam e continuam acima do piso. O piso nunca é reduzido.
- **Ordem (Testing Contract `custom`):** os testes vêm antes do código na composição do P&L no servidor:
  - os limites do `RANGE` de C4 e o mesmo realizado em C4 e no lado de C6;
  - os lados de C6 iguais às linhas de C4 e nenhum terceiro número;
  - a soma exata de todas as páginas de C5 igual à linha do bot em C4 com `period=ALL`;
  - a estabilidade do cursor de C5 e o próprio cursor.

  Nas demais camadas (filtro `market`, contagens, rotas e erros, auditoria, contadores, logs, contrato, cliente, telas, reducers, ponta a ponta, carga), os testes vêm depois de cada camada.
- **Testes obrigatórios por risco que a U3 toca:**
  - isolamento entre contas: 404 em C5 e C6 sem dado, o 404 antes do 422 e as contagens de `BotPage` só da pessoa;
  - REAL e PAPER nunca somados, no painel, na comparação, no filtro "Todos" e em C4 com `market`;
  - `unknown` como estado visível, nunca zero;
  - REAL desligado por padrão (0 chamadas novas ao TxSigner ou ao KMS);
  - novo aceite dos termos nas 4 rotas (403 `TERMS_REACCEPTANCE_REQUIRED`);
  - P&L com cenários determinísticos: a soma das páginas de C5 e a estabilidade do cursor;
  - auditoria do 404 de outro tenant e do 422 com o limite de 1 por hora.

## Ferramentas

| Parte | Ferramentas |
|---|---|
| `apps/api` | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL 17), ArchUnit, JaCoCo, Mockito, `SimpleMeterRegistry`; propriedade com `java.util.Random` e sementes fixas |
| `apps/web` | Jest 30 com Testing Library (`jest.config.ts`), relógio e fuso injetáveis, `fetch` simulado |
| Carga | `node:test` em `scripts/load/*.test.mjs`; o cenário roda só no workflow manual `load-test` |
| Ponta a ponta | Playwright, com o validador Solana local, via `scripts/verify.sh` |

## Comandos desta Unit

Todos cobrem só os arquivos da U3. Os nomes seguem o `code-generation-plan.md`; se um nome mudar na geração, este arquivo muda junto.

### Fase A — vermelho e verde, testes de unidade (Etapas 2 e 3)

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='PnlNoThirdNumberTest,BotLinesCursorTest,RealBotPnlBotLinesTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### Fase A — vermelho e verde, testes de integração (Etapas 2 e 3)

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='PnlRangeBoundaryIT,PnlCompareEqualsByModeIT,BotPnlLinesSumIT,BotPnlLinesCursorStabilityIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

O vermelho de cada um é rodado e registrado antes do código (falha de compilação ou de asserção), e o resultado vai para o `code-summary.md`.

### Backend — testes de unidade

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='PnlNoThirdNumberTest,BotLinesCursorTest,RealBotPnlBotLinesTest,PnlWindowsTest,ModePnlViewTest,ReadRefusalAuditTest,ReadRefusalAuditFilterTest,ReadRefusalAuditNonBlockingTest,PnlCompareMetricsTest,PnlReadAuditMetricsTest,PnlViewsLogCaptureTest,PnlViewsArchitectureTest,NeverSumModesArchitectureTest,ModeBoundaryArchitectureTest,ArchitectureTest,AuditArchitectureTest,BotsArchitectureTest,GuestOpenApiContractTest,RouteRegistryTest,RealBotPnlTest,RealBotPnlQueryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

O `RealBotPnlTest` e o `RealBotPnlQueryTest` são da U2 e continuam verdes com o coletor de linhas.

### Backend — testes de integração

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='PnlRangeBoundaryIT,PnlCompareEqualsByModeIT,BotPnlLinesSumIT,BotPnlLinesCursorStabilityIT,PnlByModeMarketRouteIT,PnlByModeMarketFailureIT,ModePnlFailureIT,BotListMarketIT,PnlBotCountsTenantIT,BotRoutesIT,BotPnlLinesRouteIT,BotPnlLinesTenantIT,PnlCompareRouteIT,PnlCompareTenantIT,PnlCompareSideFailureIT,BotDirectoryOwnedByAnotherTenantIT,ReadRefusalAuditLimitIT,PnlViewsPrometheusIT,PnlViewsCorrelationIT,BotNotFoundTimingParityIT,PnlViewsReadOnlyIT,PnlReadsDoNotBlockCycleIT,PnlViewsLatencyIT,RealPnlTenantIsolationIT,RealBlockFailureIT,ModePnlLatencyIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

- O `PnlViewsLatencyIT` e o `ModePnlLatencyIT` só registram o p95 no log, ao lado da meta de 1.000 ms; eles falham só quando um pedido medido não responde 200 (NFR1.5).
- O `BotNotFoundTimingParityIT` falha quando `|p95(outro tenant) − p95(inexistente)| > max(5 ms, 10% do maior p95)`. Uma falha instável é defeito a investigar, nunca um teste a ignorar.
- `RealPnlTenantIsolationIT`, `RealBlockFailureIT`, `ModePnlFailureIT` e `BotRoutesIT` são de Units anteriores e continuam verdes com as rotas mudadas.

### Contrato

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='GuestOpenApiContractTest' -Dopenapi.write=true -Dsurefire.failIfNoSpecifiedTests=false
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='GuestOpenApiContractTest' -Dsurefire.failIfNoSpecifiedTests=false
pnpm --dir apps/web run gen:api
pnpm --dir apps/web run check:api
```

O primeiro comando regenera o `openapi-guest.yaml`; o segundo confere que o arquivo versionado é igual ao gerado. O `schema.d.ts` só é regenerado por `gen:api`, nunca à mão.

### Frontend (Jest)

```bash
pnpm --dir apps/web exec jest src/lib/api/client.test.ts src/lib/api/pnl-routes.test.ts src/features/pnl/by-mode.test.ts src/features/pnl/period.test.ts src/features/pnl/PeriodSelector.test.tsx src/features/pnl/pnl-screen-state.test.ts src/features/pnl/PnlScreenViews.test.tsx src/features/pnl/PnlScreen.test.tsx src/features/pnl/PnlModeBlock.test.tsx src/features/pnl/PnlModeBlockVariants.test.tsx src/features/pnl/ProratedLines.test.tsx src/features/pnl/BotPnlLines.test.tsx src/features/pnl/ComparisonView.test.tsx src/features/bots/BotModeFilter.test.tsx src/features/bots/BotList.test.tsx src/features/bots/BotDetail.test.tsx src/features/dashboard/concurrency.test.ts src/features/dashboard/DashboardModeCards.test.tsx src/features/dashboard/DashboardScreen.test.tsx src/features/dashboard/dashboard-state.test.ts src/features/dashboard/lint-rules.test.ts
```

O `PnlModeBlock.test.tsx` (U1 e U2) continua sem edição. O `PnlScreen.test.tsx` (U1) muda só nos casos do conflito 2 do plano, conforme a decisão do responsável.

### Carga (`node:test`)

```bash
node --test "scripts/load/*.test.mjs"
```

Inclui o `load-test-pnl-by-mode.test.mjs` com cada portão novo reprovando sozinho e os quatro casos da regra de leitura do relatório. O cenário `pnl-by-mode` em si roda só no workflow manual `load-test`, disparado pelo fundador depois de aplicar o texto pronto da U3 (`gh workflow run load-test -f scenario=pnl-by-mode`). Esta Unit não edita `.github/workflows/`.

### Ponta a ponta e ponto de controle

O spec `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts` roda só pelo `verify.sh`, que já o lista; a U3 não muda o `scripts/verify.sh`.

```bash
bash scripts/verify-checkpoint.sh
```

Ele roda primeiro o `verify.sh` local. Se a máquina não aguentar (memória), a prova é o run verde do `ci` do mesmo commit, com a árvore limpa fora dos registros do AI-DLC e o commit no remoto (`team.md`).

## Metas de cobertura

| Parte | Meta | Ferramenta |
|---|---|---|
| `apps/api` | ≥ 80% de linhas, unidade e integração mescladas | JaCoCo (`mvnw verify`) |
| `apps/web` | ≥ 80% de linhas | Jest (`coverageThreshold.global.lines = 80`) |
| Regras da U3 | cada regra BR1.1 a BR12.1 com teste; o piso de 80% vale para o módulo inteiro | JaCoCo e Jest |

## Dublês (mocks e stubs)

- **Composição do P&L (Fase A):** sem dublê nos ITs. `PnlRangeBoundaryIT`, `PnlCompareEqualsByModeIT`, `BotPnlLinesSumIT` e `BotPnlLinesCursorStabilityIT` usam o cálculo real da U1 e da U2 sobre o PostgreSQL do contêiner.
- **`RealBotPnlBotLinesTest`:** dublês das portas, no padrão do `RealBotPnlTest` (`RealLedger.streamInNetworkOrder`, `RealExecutionPort.streamSettledOrders`, `BotDirectory`, preços), com sequências de `NetworkOrderedEntry` montadas à mão em lamports e unidades base.
- **Auditoria:** `ReadRefusalAuditNonBlockingTest` usa um `RateLimitedAuditPublisher` duplo que bloqueia 1 s; `ReadRefusalAuditTest` bloqueia o publicador para encher a fila; `ReadRefusalAuditLimitIT` usa o escritor real e injeta a falha da gravação.
- **Falhas de bloco e de lado:** a falha injetada segue o padrão do `RealBlockFailureIT` e do `ModePnlFailureIT` (fonte do bloco que lança ou passa do prazo; pool preso para `REJECTED`).
- **Relógio:** sempre o `Clock` injetado no servidor (prazo da auditoria, janela do filtro, log limitado) e `now`, `timeZone`, `setTimer` e `clearTimer` injetados na tela (`PeriodSelector`, `windowedRefresh`, `DashboardModeCards`).
- **Web:** `fetch` simulado no cliente; `api` falso nas telas; as fixtures ficam em `features/pnl/fixtures.ts`, tiradas do contrato gerado; `AbortController` real.
- **Rede:** nenhuma. Nenhum teste usa devnet, mainnet, KMS real ou chave real.

## Dados de teste

- **Semente de medida:** a da U1 (1 pessoa, 10 bots, 5 PAPER e 5 REAL, 730 dias, 4 ordens por bot por dia, 2.920 por bot), no `PnlViewsLatencyIT`.
- **Cenários de resultado conhecido:** compras, vendas, uma liquidação, uma venda condicional e uma parcela vendida por outro bot, com mais de 100 linhas para um bot REAL, em lamports e unidades base; um par PAPER e REAL de mesmo `templateId` e um par de `templateId` diferente.
- **Dois tenants:** nos testes de isolamento, de 404 antes de 422, de contagens e de paridade de tempo.
- **Carteiras, mints e assinaturas:** sintéticos, nunca reais. As fixtures do caso de 360 px com dados montados também (`mounted-pnl-fixtures.ts`).
- **Carga:** a semente do `LoadtestPnlSeed` só nos perfis `test,loadtest` (todos os bots com `MOMENTUM_VOLUME`, então cada pessoa tem pares para C6).
- **Independência:** cada IT limpa os próprios dados e não depende da ordem de execução.
