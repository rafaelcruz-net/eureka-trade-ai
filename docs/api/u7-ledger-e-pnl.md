# U7 — Ledger e P&L (C5, C13, C15)

Documento técnico da Unit `u7-ledger-pnl`: o PaperTradingLedger (módulo `ledger`) e o PortfolioPnL (módulo `pnl`) do beta PAPER. Tudo aqui é simulado: nenhum caminho constrói, assina ou envia transação (BR1.9), e toda linha tem `mode = 'PAPER'` (BR9.2). A especificação REST gerada do código fica em [`apps/api/openapi/openapi-guest.yaml`](../../apps/api/openapi/openapi-guest.yaml); os contratos atualizados estão no [`contract-summary.md`](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/inception/contract-design/contract-summary.md) (C5, C9, C11, C13, C15 e Escalas).

## Módulos e fronteiras

| Módulo | Pacotes | Tabelas | Quem usa de fora |
|---|---|---|---|
| `ledger` | `api`, `application`, `domain`, `infra`, `web` | `paper_account`, `paper_order`, `fill`, `ledger_entry`, `position`, `pnl_daily`, `simulation_policy` | U5 pela `PaperExecution` (C15); o `pnl` pela `LedgerReadModel` e pelos eventos `FillRecorded` e `PaperAccountFunded` |
| `pnl` | `api`, `application`, `domain`, `infra`, `web` | `equity_peak`, `reconciliation_check` | U6 pela `PortfolioRiskView` (C13) |

O `pnl` depende só de `ledger.api`; o `ledger` não depende do `pnl`; os domínios não dependem de infraestrutura, web nem Spring (`LedgerArchitectureTest`).

## Modelo do ledger

- **Escalas:** valores em USDC com 6 casas, preço unitário com 12, pontos percentuais com 4, todos `HALF_EVEN`, arredondados uma vez no cálculo; quantidades truncadas (`DOWN`) nas casas do mint. Nunca ponto flutuante (ArchUnit e `MigrationsIT`).
- **Lançamentos (BR2.2):** compra `TRADE_COST = −referência`, `SLIPPAGE = −(bruto − referência)`, `FEE = −taxa`; venda `TRADE_PROCEEDS = +referência`, `SLIPPAGE`, `FEE`; fechamento `CLOSE = +valor` e `FEE`; o saldo inicial é um `FUNDING` por bot. Lançamento de valor zero não é gravado.
- **Posição (BR2.3, BR2.4):** custo médio guardado como custo total. Numa venda parcial, o custo removido arredondado nunca leva o custo inteiro: o menor valor de USDC (0,000001) fica com a quantidade aberta. Esse caso foi encontrado pelo teste de propriedade e virou teste de regressão.
- **Imutável (BR4.1, BR4.2):** `ledger_entry` e `fill` recusam `UPDATE`, `DELETE` e `TRUNCATE` para qualquer login, inclusive o dono do esquema. A correção é só por `COMPENSATION`, o negativo exato do original, uma vez, nunca de outra compensação. No beta só uma `FEE` é compensada (`LedgerCompensation`, de visibilidade de pacote, sem rota nem tela).
- **Resumo diário (`pnl_daily`):** gravado na transação do preenchimento. O gatilho só deixa `fill_count` subir de um em um; sem preenchimento novo, só `fees` muda (compensação). É dado de relatório: a visão de risco da U6 lê a perda do dia direto dos preenchimentos.

## Matriz de privilégios do papel da aplicação (NFR6.2)

| Tabela | Permitido | Proteção |
|---|---|---|
| `ledger_entry`, `fill` | `INSERT`, `SELECT` | Gatilho recusa `UPDATE`, `DELETE` e `TRUNCATE` de qualquer papel; compensação conferida no `INSERT` |
| `paper_order` | `INSERT`, `SELECT`, `UPDATE (status)` | Só `CLOSE_PENDING → FILLED`, com as outras colunas iguais |
| `position` | `INSERT`, `SELECT`, `UPDATE (quantity, cost_basis, status, updated_at, version)` | `CHECK` de não negativos, de quantidade zero só com custo zero e de `CLOSED` só com quantidade zero |
| `paper_account` | `INSERT`, `SELECT`, `UPDATE (status)` | Só `OPEN → CLOSING` e `CLOSING → CLOSED` sem posição aberta |
| `equity_peak` | `INSERT`, `SELECT`, `UPDATE (peak_equity, peak_at, last_equity, last_equity_at)` | O pico nunca desce |
| `pnl_daily` | `INSERT`, `SELECT`, `UPDATE (realized, fees, fill_count, updated_at)` | Gatilho do resumo diário |
| `reconciliation_check` | `INSERT`, `SELECT`, `DELETE` | A conferência guarda os 100 últimos por pessoa |
| `simulation_policy` | `SELECT` | Política v1 semeada pela migração `0008` |

A migração revoga os privilégios padrão do `db-bootstrap` de qualquer login que não seja o dono e concede só a matriz a quem tinha `INSERT` (o login da aplicação, cujo nome vem do `.env`). `LedgerPrivilegesIT` confere cada linha com o login real da aplicação.

## Idempotência (NFR6.8)

| Operação | Mecanismo |
|---|---|
| `execute` | Chave única `(tenant_id, mode, idempotency_key)` em `paper_order`, `INSERT ... ON CONFLICT DO NOTHING`; a idempotência é conferida antes do estado da conta; o resultado é calculado antes e a ordem é inserida uma vez com o status final |
| `fund` | `IdempotencyGuard` (operação `PAPER_FUND`); índice único parcial de `FUNDING` por bot |
| `closePositions` | `IdempotencyGuard` (operação `PAPER_CLOSE_POSITIONS`); ordens `CLOSE` com a chave `<chave>:<positionId>`; com a conta já em `CLOSING` ou `CLOSED`, a posição `CLOSE_PENDING` não ganha ordem nova |
| `POST /api/v1/reconciliation/run` | `IdempotencyGuard` (operação nova `RECONCILIATION_RUN`, além do plano, porque toda rota `POST` leva `Idempotency-Key` e o `project.md` pede o mecanismo comum) |

Uma chave do C15 que não é UUID vira o UUID por nome de `paper:<chave>`, determinístico.

## Ordem das travas e tempos de espera (BR2.8, NFR10.8)

1. `paper_account` do bot (`FOR UPDATE`);
2. as `position` do bot em ordem de `(chain, token_mint)`;
3. a linha de `pnl_daily` do dia;
4. `equity_peak` (pelo ouvinte síncrono de `FillRecorded`, na mesma transação).

`execute`, `fund` e `closePositions` fazem `SET LOCAL lock_timeout = '2s'` e `statement_timeout = '5s'`. **O `SET LOCAL` vale até o fim da transação do ciclo da U5**: as gravações seguintes da U5 e da trilha no mesmo ciclo também ficam com esses limites. É intencional (o ciclo inteiro precisa caber no orçamento) e a U5 deve registrá-lo no seu design. As tarefas usam `lock_timeout = '1s'` por conta; a reconciliação, `statement_timeout = '30s'` em `REPEATABLE READ`. Uma trava não obtida (SQLSTATE `55P03`) vira `CannotAcquireLockException`: o ciclo desfaz e a tarefa pula a conta na rodada (`LedgerLockTimeoutIT`, `LedgerLockOrderIT` com 50 repetições).

## Contexto de autenticação do ciclo (C10)

A `PaperExecution` e a `PortfolioRiskView` leem tenant e pessoa do `AuthContext`. Nas rotas HTTP, ele vem do token. No ciclo do bot, **a U5 precisa ligar o `Principal` do dono do bot antes de chamar a U7** (regra do C10: tarefas em segundo plano recebem um Principal de sistema com o tenant do recurso). Esse mecanismo ainda não existe: os testes da U7 usam um dublê de teste (`CycleAuthContext`).

## Gerador de ciclos de carga (perfil `loadtest`)

Os cenários `u7` e `u7-jobs` de `scripts/load-test.sh` usam o pacote `com.memetrade.ledger.loadtest`, que só existe com o perfil `loadtest` (e este só sobe junto com `test`, `LoadtestProfileGuard`). Ele faz o papel do ciclo da U5 sem criar nenhum `Principal`:

- **Pessoas:** o roteiro de carga entra com a conta de smoke e cria uma conta por convite de `SMOKE_INVITE_CODES`, com carteiras geradas em memória. Cada pessoa lê o ledger uma vez; o `LoadtestCycleAuthContext` (o `AuthContext` primário só nesse perfil) guarda o `Principal` daquela sessão validada e depois o liga nas threads do gerador para os bots dessa pessoa.
- **Tokens:** `LoadtestTokens` lê a lista da U4 por `MarketListingQuery` e preço, liquidez e casas por `MarketDataQuery` (C11). Nenhuma tabela da U4 é lida; token com valor `unknown` ou desatualizado não é negociado.
- **Preparação:** `LoadtestLedgerSeed` cria os bots em rodízio entre as pessoas, chama `fund` uma vez por bot, abre uma posição por token e grava pares de compra e venda pela própria `PaperExecution`, numa transação de ciclo por lote, até cada pessoa ter o número pedido de preenchimentos.
- **Ciclos:** `LoadtestCycleDriver` roda um ciclo por bot a cada 30 s em threads próprias (nunca o pool do agendador das tarefas) e mede cada chamada no timer `loadtest.paper.execute` (`loadtest_paper_execute_seconds` no Prometheus, NFR3.1).
- **Acompanhamento:** o componente de saúde `loadtestLedger` fica `UNKNOWN` durante a preparação (não muda a saúde geral nem a prontidão) e `UP` com os ciclos rodando; o detalhe mostra o estado, pessoas, bots, preenchimentos, ciclos e falhas.

Com `LOADTEST_LEDGER_PEOPLE` em 0 (padrão do overlay de teste e de todo cenário que não seja `u7` ou `u7-jobs`) o gerador não faz nada. As regras de arquitetura `AuditArchitectureTest` (Principal só no caminho da sessão) e `U4ArchitectureTest` (tabelas da U4 só nos seus módulos) continuam valendo também para esse pacote. `LedgerArchitectureTest`, `LoadtestBeansProfileTest` e `U7ContextIT` garantem que toda classe do pacote tem `@Profile("loadtest")`, que ele só usa `ledger.api`, `identity.api` e `marketdata.api`, e que nenhum de seus beans existe nos perfis padrão e `test`. Os resultados medidos ficam em `docs/perf/u7-ledger-pnl-<data>.md`.

## Catálogo de cenários

Os cenários C1 a C15 do [functional-spec.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u7-ledger-pnl/functional-design/functional-spec.md) são testes escritos antes do código, com igualdade exata de valor e escala: `LedgerScenarioCatalogTest` (preenchimentos e recusas), `PositionAccountingTest` (lançamentos, posição, fechamento, compensação), `PnlDomainTest` (preço de saída, `unknown`, parcial, pico, perda do dia), `PaperExecutionIT` (fluxos F1 a F3 no banco) e `LedgerPropertyTest` (500 sequências com semente fixa).

## Procedimentos

### Fechamento pendente

Sem preço confiável no encerramento, a ordem `CLOSE` e a posição ficam `CLOSE_PENDING`, e a conta fica `CLOSING`. A tarefa `PendingCloseJob` (ShedLock `ledger-pending-close`) roda na partida, depois de cada coleta de mercado e a cada 60 s: conclui cada posição uma única vez quando o preço volta (evento `POSITION_CLOSE_COMPLETED`, ator `system`) e fecha a conta quando nada fica aberto. Uma conta com a trava presa é pulada e contada no log da rodada. Para acompanhar: o log `Pending close round finished` mostra pendentes, concluídas, puladas, falhas e a duração; o detalhe de `ledgerJobs` mostra a última rodada.

### Divergência na saúde

A `ReconciliationJob` (ShedLock `pnl-reconciliation`, a cada 5 minutos) confere cada pessoa e grava o resultado. Com divergência: log `WARN` `reconciliation found divergences` com o número de pessoas e a contagem por tipo (sem pessoa, bot ou valor), e o componente `ledgerJobs` do Actuator fica `DEGRADED` (HTTP 200, fora da prontidão) com `peopleWithDivergences`. O que fazer: abrir a tela de P&L da pessoa afetada (o aviso lista as operações) e investigar; nunca editar o banco. Uma correção só entra por lançamento compensatório.

### Rollback

A `0008` só acrescenta tabelas, índices, gatilhos, privilégios e a política v1. A imagem da U4 roda sobre o banco migrado (`MigrationsIT`). O pool do agendador volta ao padrão da imagem anterior ao subir o Compose da versão anterior; o valor 11 também é aceito pela imagem anterior (guarda de 7 a 32).

## Pendências registradas

- **NFR3.4** (tempo da tela de P&L com operações): depende das operações reais da U5; fica como pendência de entrada da U5. O smoke da U7 confere só os estados vazios.
- **Principal do ciclo (C10):** quando a U5 entregar o Principal de sistema do ciclo, o gerador de carga pode passar a usá-lo no lugar do Principal guardado da sessão.
- **Smoke da U7 no `scripts/verify.sh`**: o responsável do projeto acrescenta `e2e/u7-ledger-pnl` à linha do Playwright.
