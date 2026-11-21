# Varredura do código — paper-real-unico

Varredura focada, profundidade Standard, só por leitura de arquivos (nenhum teste, build ou programa do projeto foi
executado). Repositório `eureka-trader-ai` na raiz do projeto, commit de referência
`3497b2bc49052f01adb93de7094a05880532222b`, com a árvore de trabalho como estava em 2026-11-21. A base de conhecimento
anterior (2026-11-13, commit `40e2553`) foi lida como contexto; ela é anterior à construção do intent
`261113-trading-real-bot` e não conhece os módulos `realexecution`, `realexits`, `botwallet`, `riskcontrol/real` nem o
app `apps/txsigner`.

## Developer Code Scan Results

### Scan Coverage
- **Analyzed deeply**:
  - apps/api/src/main/java/com/memetrade/bots/
  - apps/api/src/main/java/com/memetrade/pnl/
  - apps/api/src/main/java/com/memetrade/realexecution/
  - apps/api/src/main/java/com/memetrade/ledger/
  - apps/api/src/main/java/com/memetrade/realexits/
  - apps/web/src/features/bots/
  - apps/web/src/features/real/
  - apps/web/src/features/dashboard/
  - apps/web/src/app/pnl/
  - apps/web/src/app/bots/
- **Skimmed only**:
  - apps/api/src/main/resources/db/changelog/changes/ (0008, 0010, 0016, 0019, 0020, 0021, 0022: tabelas, `CHECK` de modo e de origem)
  - apps/api/src/test/java/com/memetrade/ (contagem de testes de `bots`, `pnl`, `realexecution`, `ledger`, `realexits`; nomes das regras ArchUnit; busca por testes do P&L REAL)
  - apps/web/e2e/real-swap/ (só para confirmar como um bot REAL é criado nos testes)
  - apps/web/src/features/pnl/ (só o ponto de entrada `PnlScreen`, importado por `app/pnl/page.tsx`)
  - apps/web/src/app/real/ (lista de rotas)
  - apps/web/src/lib/api/ (busca do campo `market` em `guest-api.ts` e `schema.d.ts`)
  - apps/api/src/main/java/com/memetrade/ (lista de módulos: `botwallet`, `riskcontrol/real` e `apps/txsigner` existem e não foram lidos)
- **Left out**:
  - node_modules/
  - .next/
  - target/
  - coverage/
  - test-results/, playwright-report/, blob-report/
  - apps/web/src/lib/api/schema.d.ts (tipos gerados; só buscado por texto)
  - .claude/, .codex/, .agents/, aidlc/ e as seções do AI-DLC em AGENTS.md e .gitignore (instalação do AI-DLC)

### Packages Found
- `com.memetrade.bots` — módulo de backend — Java 21 — bots PAPER e REAL (`bot.market`), assistente, comandos, ciclo de 5 min no worker com o ramo REAL (`RealCycle`, `RealDecisionStep`), canal ao vivo; 67 arquivos, 7.478 linhas
- `com.memetrade.pnl` — módulo de backend — Java 21 — P&L PAPER por período e por bot, visão de risco C13, pico de patrimônio, reconciliação PAPER e o P&L REAL por pessoa (`RealPnlService`, `RealPnlCalculator`); 25 arquivos, 2.323 linhas
- `com.memetrade.realexecution` — módulo de backend — Java 21 — ordens REAL (M6): fila de decisões, máquina de estados, cotação e swap pela Jupiter, sessões de assinatura, envio, confirmação, ordens ambíguas, liquidação "vender tudo e sacar", lançamentos no ledger REAL; 56 arquivos, 6.310 linhas
- `com.memetrade.ledger` — módulo de backend — Java 21 — ledger PAPER (C15) e ledger REAL append-only (M2: `RealLedger`, `real_ledger_entry`, posições por custo médio da carteira do bot); 54 arquivos, 5.433 linhas
- `com.memetrade.realexits` — módulo de backend — Java 21 — saídas condicionais (stop, alvo, OCO) da API Trigger da Jupiter por posição da carteira do bot, `PositionExitGuard`, exportação fiscal CSV do ledger REAL; 43 arquivos, 4.112 linhas
- `apps/web/src/features/bots` — feature de frontend — TypeScript/React — lista, criação, assistente e detalhe de bot (só PAPER na interface); 16 arquivos (3 testes)
- `apps/web/src/features/real` — feature de frontend — TypeScript/React — área REAL: ativação da carteira, depósito e saque, modo, ordens, assinatura, condicionais, exportação fiscal, cartão do P&L REAL; 50 arquivos (17 testes)
- `apps/web/src/features/dashboard` — feature de frontend — TypeScript/React — painel ao vivo: cartão de bots, cartão do P&L PAPER, avisos de dados, leitor SSE; 28 arquivos (7 testes)
- `apps/web/src/app/bots`, `apps/web/src/app/pnl` — rotas Next.js — páginas finas que montam `BotList`, `BotCreate`, `BotWizard`, `BotDetail` e `PnlScreen`

### Build System
- **Type**: Maven (backend, `apps/api/pom.xml`) e pnpm (frontend, `apps/web/package.json`), sem mudança em relação à base anterior
- **Config Files**: `apps/api/pom.xml`, `apps/web/package.json`, `apps/web/tsconfig.json`, `apps/web/jest.config.ts` (não relidos a fundo nesta execução)
- **Build Dependencies** (imports de produção entre módulos, lidos nesta execução):
  - `pnl` → `ledger.api`, `realexecution.api` (`RealPnlService` lê `settledOrders`), `marketdata.api`, `identity.api`, `audit.api`, `scanner.web` (DTO)
  - `bots` → `realexecution.api` (`RealDecisionStep`), `riskcontrol.api` (`RealRiskGate`), `botwallet.api` (`RealStateQuery`), `ledger.api` (`RealLedger`), `decision.*`, `marketdata.api`
  - `realexecution` → `botwallet.api`, `ledger.api` (`RealLedger`), `riskcontrol.api`, `bots.api` (`RealBotOwnership`), `marketdata.api`, `identity.api`, `audit.api`
  - `realexits` → `botwallet.api`, `ledger.api`, `realexecution.api` (implementa `PositionExitGuard` e `RealExitChanges`), `riskcontrol.api`, `marketdata.api`, `identity.api`, `audit.api`; nenhum módulo importa `realexits`
  - `ledger` → `marketdata.api`, `identity.api`, `audit.api`; não conhece `pnl` nem `realexecution`

### APIs Discovered
- REST (bots) — `bots/web/BotController.java`, `DashboardStreamController.java` — 9 operações; `POST /api/v1/bots` aceita `market` opcional (`PAPER` padrão, `REAL` exige carteira do bot ATIVA)
- REST (P&L) — `pnl/web/PnlController.java` (3: `GET /api/v1/pnl?botId&from&to`, `GET /api/v1/reconciliation`, `POST /api/v1/reconciliation/run`) e `RealPnlController.java` (1: `GET /api/v1/real/pnl`, sem `botId` nem período)
- REST (ledger PAPER) — `ledger/web/LedgerController.java` — 3 operações (`trades`, `day-usage`, `ledger`)
- REST (execução REAL) — `realexecution/web/RealOrderController.java`, base `/api/v1/real` — 7 operações (`orders`, `orders/{id}`, sessão de assinatura GET/POST, `reject`, `PUT /mode`, `POST /liquidations`)
- REST (saídas REAL) — `realexits/web/ConditionalController.java`, base `/api/v1/real/conditional-orders` — 5 operações; `realexits/fiscal/FiscalExportController.java` — `GET /api/v1/real/exports/fiscal`
- Interfaces Java entre módulos — `realexecution.api.RealExecutionPort` (M6), `PositionExitGuard`, `RealExitChanges`; `ledger.api.RealLedger` (M2), `PaperExecution` (C15), `LedgerReadModel`; `bots.api.RealBotOwnership`; `pnl.api.PortfolioRiskView` (C13)
- Canal ao vivo — eventos `real.order.state`, `real.signing.requested`, `real.conditional.state` em `bots/web/DashboardEvents.java`; o resumo do bot no canal traz `market`

### Frameworks & Libraries
- Spring Boot — 3.5.16 — web, JDBC (`JdbcClient`), transações; sem mudança de versão observada nesta execução
- Next.js / React — 15.5.28 / 19.2.9 — frontend; sem mudança observada
- Cliente HTTP da Jupiter próprio — `realexecution/infra/jupiter/JupiterClient.java` — cotação e swap (o `realexits` usa a porta `TriggerPort` com um adaptador simulado no perfil de teste)
- Montagem de transação Solana própria — `realexecution/domain/solana/` (`MessageBuilder`, `WireTransaction`, `ShortVec`, `Ed25519Check`), sem SDK Solana em produção

### Test Coverage
- **Test Directories**: `apps/api/src/test/java/com/memetrade/{bots,pnl,realexecution,ledger,realexits}` (bots 12 Test e 20 IT; pnl 3 e 6; realexecution 12 e 18; ledger 7 e 13; realexits 14 e 9); `apps/web/src/features/{bots,real,dashboard}` (3, 17 e 7 arquivos de teste Jest); `apps/web/e2e/real-swap/`
- **Test Frameworks**: JUnit 5, Testcontainers, ArchUnit (`U3ArchitectureTest`, `U4ArchitectureTest`, `U8ArchitectureTest`, `BotWalletArchitectureTest` além das anteriores); Jest + Testing Library; Playwright
- **Coverage Config**: presente (JaCoCo e Jest com piso de 80%), não relida nesta execução
- O P&L REAL tem `pnl/real/RealPnlCalculatorTest.java` (unitário) e aparece em `RealSettlementIT` e `RealRoutesIT`; nenhum teste do pacote `pnl` cobre a venda de uma saída condicional no P&L REAL

### Code Quality Indicators
- **Linting**: Spotless/palantir no backend e ESLint/Prettier no frontend (configuração não relida); `features/real/real-lint.test.ts` e `features/dashboard/lint-rules.test.ts` são regras de lint em forma de teste
- **CI/CD**: não relido nesta execução (fora do foco)
- **Documentation**: Javadoc de abertura em quase todas as classes lidas, citando BR, NFR, contrato e Unit do intent de origem; TSDoc nos componentes

### Technical Debt Signals
- **P&L REAL ignora as vendas das saídas condicionais**: `pnl/application/RealPnlService.java` monta os fills só de `RealExecutionPort.settledOrders` (tabela `real_order`, origens `BOT` e `USER_LIQUIDATION`), mas `realexits/application/FillPosting.java` grava a venda condicional direto no ledger REAL (`SWAP_FILL_OUT`, referência `conditional:<id>`) sem criar `real_order`. O Javadoc de `FillPosting` diz "the position and the P&L REAL follow from the ledger", o que não vale para o P&L
- **Dois custos médios REAL diferentes**: `ledger/domain/RealPositions` (custo = só a perna em SOL, parte vendida arredondada para baixo) e `pnl/real/RealPnlCalculator` (custo = SOL + taxa + aluguel, parte vendida arredondada para cima); a posição da barreira e a do P&L podem divergir em lamports
- **Campo `mode` fixo em `PAPER` ao lado de `market`**: `bots/application/BotSummaryView.java` devolve sempre `mode = "PAPER"` e o modo real do bot vai em `market`; a tabela `bot` tem `mode CHECK = 'PAPER'` (0010) e `market IN ('PAPER','REAL')` (0019)
- **Interface web só PAPER para bots**: `features/bots/BotCreate.tsx` chama `api.createBot({ templateId, name })` sem `market`; a lista e o painel se chamam "Bots PAPER"; só o spec `e2e/real-swap/swap.spec.ts` cria um bot REAL, pela API
- **`bot_holding` do bot REAL** aberto em `REAL_RECORDED` (decisão gravada), antes da confirmação na rede (`bots/worker/CycleRunner.java`)
- Nenhum `TODO`/`FIXME` nos cinco módulos de backend; 2 `@SuppressWarnings`

## Handoff Summary
- **Intent-relevant finding**: o P&L REAL hoje é por pessoa e por mint, nunca por bot, e não tem período: `GET /api/v1/real/pnl` (`pnl/web/RealPnlController.java`) chama `RealPnlService.pnl(tenant, user)`, que lê todas as ordens assentadas da pessoa e aplica `RealPnlCalculator.of` (custo médio por mint). A informação para o P&L por bot já existe em `real_order.bot_id` (origem `BOT`), mas as vendas sem bot ficam fora dela: a liquidação grava `real_order` com `origin = 'USER_LIQUIDATION'` e `bot_id` nulo (`0019-real-execution.sql`, `real_order_origin_shape_ck`), e a saída condicional nem cria `real_order` (`realexits/application/FillPosting.java`). Todos os bots REAL de uma pessoa dividem a mesma carteira do bot e o mesmo ledger (`real_ledger_entry` não tem `bot_id`), e o ciclo de um bot REAL vende a posição INTEIRA do mint na carteira (`bots/worker/RealCycle.java`, ramo SELL usa `position.get().tokenBaseUnits()`), inclusive o que outro bot comprou. O P&L PAPER já é por bot (`GET /api/v1/pnl?botId=`, uma conta PAPER por bot, custo médio por bot em `ledger/domain/PositionAccounting`).
- **Risks / follow-up**:
  - a separação REAL/PAPER é física e precisa continuar: `real_ledger_entry` com `CHECK (mode = 'REAL')` e `ledger_entry` só PAPER; o cartão REAL e o PAPER do painel nunca somam (`DashboardRealPnl`, `PnlCard`);
  - as unidades são diferentes: PAPER em USDC com 6 casas (`BigDecimal`), REAL em lamports (`long`); um total por modo não pode virar uma soma única;
  - o rateio das vendas sem bot precisa decidir sobre a ordem dos fills (o cálculo atual ordena por `updated_at, real_order_id`) e sobre o que fazer com a venda condicional, que não está em `real_order`;
  - qualquer mudança no ciclo REAL (vender só a própria quantidade) toca a ordem de testes antes do código do `team.md` (execução REAL e idempotência) e as regras ArchUnit `U4ArchitectureTest`;
  - a criação de bot REAL pela interface não existe hoje; a "lista única" do intent precisa da escolha de `market` no `BotCreate` e do rótulo do modo em texto na lista e no painel.
