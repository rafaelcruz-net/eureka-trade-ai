# Desenho de confiabilidade e integridade — U7 Ledger e P&L

Como a Unit `u7-ledger-pnl` atende a [reliability-requirements.md](../nfr-requirements/reliability-requirements.md) (NFR4.1 a NFR4.5, NFR8.1, NFR10.1 a NFR10.8 da U7).

## Transações e travas

| Operação | Transação | Ordem das travas (BR2.8) | Tempos |
|---|---|---|---|
| `fund`, `execute`, `closePositions` | A do ciclo da U5 (`Propagation.MANDATORY`) | `paper_account` → `position` por (chain, mint) → `pnl_daily` da mesma chave → `equity_peak` | `SET LOCAL lock_timeout = '2s'`, `statement_timeout = '5s'`; estourar lança exceção técnica e a U5 desfaz o ciclo (NFR10.2, NFR10.8). O `SET LOCAL` vale até o fim da transação do ciclo, então as gravações seguintes da U5 e da trilha no mesmo ciclo também ficam com esses limites; é intencional (o ciclo inteiro precisa caber no orçamento) e a U5 o registra no seu design |
| Fechamento pendente (por posição) | Própria | `paper_account` → `position` → `equity_peak` | `lock_timeout = '1s'`; estourar pula a conta na rodada e registra o fato |
| Marcação (por conta) | Própria; `LedgerReadModel.lockAccountForMarking` trava a conta e devolve saldo e posições do mesmo instante | `paper_account` → `equity_peak` | `lock_timeout = '1s'`, mesma regra |
| Pico depois de preenchimento | A mesma do preenchimento, pelo ouvinte síncrono de `FillRecorded` | Já com a conta travada; trava `equity_peak` por último | Os do ciclo |
| Reconciliação (por pessoa) | Própria, só leitura, `REPEATABLE READ` para um instantâneo único | Sem trava de linha | `statement_timeout = '30s'` |

Os resultados de domínio (`NotFilled`, `Duplicate`, `Conflict`) nunca lançam exceção: são gravados e confirmados com o ciclo.

## Cálculo determinístico (NFR4.1, NFR8.1)

- `FillCalculator` é puro: recebe referência, liquidez, `decimals`, política, lado, quantidade e saldo, e devolve o preenchimento ou o `NotFilled`; não lê relógio nem banco.
- O `Fill` guarda todas as entradas do cálculo; `FillReplayTest` recalcula cada cenário C1 a C15 a partir do `Fill` gravado e confere igualdade exata.
- Os cenários do catálogo são testes parametrizados escritos antes do código (`LedgerScenarioCatalogTest`), com os valores da tabela da functional-spec.md em texto.

## Integridade e concorrência

| Situação | Mecanismo | Teste | Requisito |
|---|---|---|---|
| Tudo ou nada | Uma transação; falha injetada por um repositório de teste em cada gravação | `PaperExecutionAtomicityIT` | NFR4.3 |
| Mesma chave em paralelo | `ON CONFLICT DO NOTHING` + leitura | `PaperExecutionIdempotencyIT` (barreira, 50 repetições) | NFR6.8 da U7 |
| Soma exata e coerência | Teste de propriedade com semente fixa: 500 sequências aleatórias de fund, compras, vendas, fechamentos e compensações; depois de cada uma, saldo = soma dos lançamentos, posição = soma dos preenchimentos, `pnl_daily` = soma de `fill`, reconciliação `RECONCILED` | `LedgerPropertyTest` | NFR4.4 |
| Pico sem valor falso | Marcação e preenchimento concorrentes (barreira, 50 repetições); o pico gravado é igual a algum patrimônio calculado sob a trava | `EquityPeakConcurrencyIT` | NFR4.5 |
| Sem deadlock | Execução (com a linha de `pnl_daily`), fechamento, fechamento pendente e marcação concorrentes (50 repetições) | `LedgerLockOrderIT` | NFR10.5 |
| Compensação nas pontas do período | Período que corta o dia do preenchimento original de uma taxa compensada: resumo diário e consulta direta dão o mesmo valor; reconciliação `RECONCILED` | `PnlPeriodEdgesIT` | NFR4.4 |
| Trava presa | Uma transação de teste segura a trava da conta por 3 s | `LedgerLockTimeoutIT` | NFR10.8 |

## Falhas e recuperação

| Situação | Comportamento | Requisito |
|---|---|---|
| Banco falha no ciclo | Exceção técnica; a U5 desfaz o ciclo; nova tentativa com a mesma chave grava uma vez | NFR10.2 |
| Reinício com fechamento pendente | A tarefa roda na partida (`ApplicationReadyEvent`) e depois de cada coleta; ShedLock impede duas rodadas; cada posição é conferida sob trava | NFR10.3, NFR10.4 |
| Dado de mercado `unknown` ou desatualizado | `NotFilled(PRICE_UNAVAILABLE)`; P&L `unknown` e parcial; pico mantido | NFR10.7 |
| Divergência | A reconciliação confere ordens contra lançamentos (com as compensações pela junção `compensates_entry_id` → original → ordem), posições contra preenchimentos e `pnl_daily` contra a soma direta; tipos `MISSING_ENTRY`, `AMOUNT_MISMATCH`, `ORPHAN_ENTRY`, `POSITION_MISMATCH`, `SUMMARY_MISMATCH`; fixtures injetam cada tipo. `SUMMARY_MISMATCH` afeta só o relatório: a barreira lê a perda do dia direto dos preenchimentos | NFR10.6 |
| Disponibilidade | Sem meta numérica no beta local | NFR10.1 |
