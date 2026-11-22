# Instruções de testes da Unit — U1 `u1-mode-skeleton`

## Estratégia e volume

- **Test Strategy:** `Standard`. Cada componente tem de 5 a 8 testes de unidade. As fronteiras principais têm testes de integração com Testcontainers e PostgreSQL real.
- **Piso:** 80% de cobertura de linhas, medido em separado. No `apps/api`, o JaCoCo junta unidade e integração. No `apps/web`, vale o `coverageThreshold.global.lines = 80` do Jest.
- **Ordem (Testing Contract `custom`):** os testes vêm antes do código na elegibilidade REAL, na criação REAL dentro do comando idempotente, no bot REAL sem limites próprios (BR1.9), na linha REAL por bot e na regra de nunca somar os modos. Nas demais camadas, os testes vêm depois de cada camada.
- **Testes de risco obrigatórios que a U1 toca:**
  - isolamento e 404;
  - REAL e PAPER nunca somados;
  - idempotência;
  - limites e risco;
  - REAL desligado por padrão;
  - novo aceite dos termos.

## Ferramentas

| Parte | Ferramentas |
|---|---|
| `apps/api` | JUnit 5, Testcontainers, ArchUnit, JaCoCo, Mockito |
| `apps/web` | Jest com Testing Library (`jest.config.ts`) |
| Ponta a ponta | Playwright, com o validador Solana local, via `scripts/verify.sh` |

## Comandos desta Unit

Todos rodam a partir da raiz do repositório e cobrem só os arquivos da U1.

### Backend: testes de unidade

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='RealCreationEligibilityTest,ModePnlViewTest,RealBotPnlQueryTest,RealAccountLimitsReaderTest,BotSummaryModeTest,NeverSumModesArchitectureTest,ModeBoundaryArchitectureTest,RealSeedProfileGuardTest,RealTestSeederNetworkTest,RealCycleOrderCapTest,RecordedSwapStrategyTest,RecordedSwapFirstCycleTest,RecordedSwapProfileGuardTest' -Dsurefire.failIfNoSpecifiedTests=false
```

### Backend: testes de integração

```bash
./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='BotCreateRealIT,BotCreateConcurrencyIT,BotModePathIT,BotLimitsRealRefusedIT,RealAccountLimitsRouteIT,RealEligibilityRouteIT,ModePnlFailureIT,ModePnlLatencyIT,ByModePropertiesBindingIT,BotRoutesIT,GuestOpenApiContractTest,RecordedSwapCycleIT,WorkerProcessRoleContextIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

### Frontend

```bash
pnpm --dir apps/web exec jest src/features/bots/ModeStep.test.tsx src/features/bots/RealConfirmDialog.test.tsx src/features/bots/RealBotLimitsStep.test.tsx src/features/bots/BotCreate.test.tsx src/features/bots/BotList.test.tsx src/features/pnl/PnlModeBlock.test.tsx src/features/pnl/money-format.test.ts src/features/pnl/PnlScreen.test.tsx src/features/dashboard/envelopes.test.ts src/features/dashboard/lint-rules.test.ts
```

### Ponta a ponta

O spec `e2e/zz-paper-real-unico/paper-real-unico.spec.ts` roda só pelo `verify.sh`, depois que o responsável acrescentar `e2e/zz-paper-real-unico`. Ele cria um bot PAPER e um bot REAL pelas telas, com o mint gravado como único token, e faz uma compra em cada modo sem esperar o ciclo de 5 minutos:

- a estratégia de teste do swap gravado (perfil `test`, só no worker) compra uma vez em cada modo;
- o primeiro ciclo de teste (`RecordedSwapFirstCycle`, também só no perfil `test`) roda o bot novo em cerca de 10 s.

A pasta mudou de nome duas vezes. O Playwright roda os arquivos em ordem alfabética, e o spec precisa:

- da carteira do bot que o `e2e/real-bot-wallet` ativa;
- do `AUTO_FULL` que o `e2e/real-v2-exits` devolve;
- rodar depois do `e2e/u7-ledger-pnl`, que espera a conta do smoke sem operações PAPER.

Por isso ele é o último spec (`zz-*`). Sem `RECORDED_FIXTURES`, o spec é pulado, como o `e2e/real-swap`.

```bash
bash scripts/verify-checkpoint.sh
```

O nome das classes e dos arquivos segue o `code-generation-plan.md`. Se um nome mudar na geração, este arquivo é atualizado junto.

**Antes do primeiro teste (Etapa 2),** os dois runners são conferidos com um teste já existente da mesma área:

```bash
./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='BotConfigValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
pnpm --dir apps/web exec jest src/features/dashboard/envelopes.test.ts
```

## Dublês (mocks e stubs)

- **`RealCreationEligibility` e `ModePnlView`:** os testes de unidade usam funções puras e dublês das portas (`RealStateQuery`, `RealReleaseInfo`, o kill switch, `RealBotPnlQuery` e `BotDirectory`).
- **Integração:** banco real (Testcontainers), com as mesmas migrações Liquibase. O tempo esgotado do banco é provocado com `pg_sleep`.
- **Relógio:** o relógio é injetável; nenhum teste chama `now()` direto.
- **Rede:** nenhuma rede real. Os adaptadores de mercado e do RPC usam fixtures gravadas.
- **Web:** dublês do `fetch` com corpos tirados do contrato gerado, mais as fixtures em `features/bots/fixtures.ts` e `features/pnl/fixtures.ts`.

## Dados de teste

- **Carteira:** fixtures sem carteira, mint ou dados pessoais reais. A carteira de teste é gerada só no código de teste.
- **Liberação REAL:** vem do `RealTestSeeder`, que roda no perfil `test` e na rede `LOCALNET`, com a liberação registrada 25 horas antes pelo relógio injetado.
- **Latência:** o `ModePnlLatencyIT` semeia 10 bots e 730 dias, com 4 ordens por bot por dia e as linhas de `pnl_daily` dos dias fechados. Ele só registra o p95 no log.
- **Independência:** cada teste de integração limpa os próprios dados e não depende da ordem de execução.
