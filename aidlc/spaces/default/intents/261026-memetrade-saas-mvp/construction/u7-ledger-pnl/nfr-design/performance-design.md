# Desenho de desempenho — U7 Ledger e P&L

Como a Unit `u7-ledger-pnl` atende a [performance-requirements.md](../nfr-requirements/performance-requirements.md) (NFR3.1 a NFR3.6 da U7), com o resumo diário escolhido na Q1 de [nfr-design-questions.md](nfr-design-questions.md).

## Resumo diário do P&L (Q1)

| Item | Desenho |
|---|---|
| Tabela e dono | `pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, mint, utc_day, realized, fees, fill_count, updated_at)`, do módulo `ledger`; `CHECK mode = 'PAPER'`; chave única (bot_id, mode, chain, mint, utc_day) |
| Dia | `utc_day` = dia UTC de `Fill.filledAt`, que vem do `Clock` injetado (nunca `now()` do banco) |
| Atualização no preenchimento | Na mesma transação do preenchimento ou do fechamento: `INSERT ... ON CONFLICT DO UPDATE SET realized = realized + :r, fees = fees + :f, fill_count = fill_count + 1` |
| Atualização na compensação | Outra instrução, só para compensação de `FEE`: `UPDATE pnl_daily SET fees = fees + :valorCompensado` na linha do dia do preenchimento da ordem original; `fill_count` não muda |
| Proteção | Gatilho: `fill_count` nunca diminui; `realized` e `fees` só mudam junto de `fill_count + 1` ou, sem mudança de `fill_count`, só `fees` (compensação). É dado derivado: só relatório. A barreira não o usa |
| Atribuição de compensação a um dia | `COMPENSATION.compensates_entry_id` → `FEE` original → `FEE.reference_id` (a ordem) → `fill.filled_at`; índice `ledger_entry (compensates_entry_id)` |
| Consulta do período | Dias UTC inteiros dentro de [from, to): soma das linhas de `pnl_daily`. Primeiro e último dia parciais: consulta direta de `fill` (realizado, taxas da ordem) com `filled_at` no intervalo, mais as compensações de `FEE` dessas ordens pela junção acima. Totais = soma das linhas por token (BR6.5). Teste: período que corta o dia do preenchimento original de uma taxa compensada dá o mesmo valor pelo resumo e pela consulta direta |
| Perda do dia (C13) | Consulta direta, não o resumo: soma de `fill.realized` e das taxas (com compensações) das ordens do bot com `filled_at` no dia UTC, com índice (bot_id, filled_at); poucas linhas por dia. Assim um resumo errado nunca muda o que a barreira vê |
| Garantia | A reconciliação confere, para cada (bot, token, dia), que o resumo é igual à soma direta; diferença aparece como `SUMMARY_MISMATCH` (acréscimo ao enum do C5) e afeta só o relatório |

## Decisões

| Decisão | Detalhe | Requisito |
|---|---|---|
| Transação curta no ciclo | Nada de rede dentro da `PaperExecution` além do banco; a leitura da C11 é uma consulta local da U4 | NFR3.1 |
| Saldo por índice | `SUM(amount)` em `ledger_entry` com índice (bot_id, mode) incluindo `amount` (índice de cobertura); com 40 000 lançamentos por bot cabe em poucos milissegundos | NFR3.1, NFR2.4 |
| Primeira página do ledger | Índice (owner_user_id, created_at desc, entry_id); cursor por tupla, sem `OFFSET` | NFR3.2 |
| Marcação em lote | A tarefa lê as posições de uma conta numa consulta e o dado de mercado dos tokens em lote pela C11 | NFR3.5 |
| Reconciliação por pessoa | Consultas agregadas por ordem com `GROUP BY` e índice (reference_id), unidas às compensações pela junção `compensates_entry_id` → lançamento original → ordem; comparação em memória por pessoa | NFR3.6 |
| Tela de P&L | Uma chamada ao `GET /pnl`; valores como texto; sem biblioteca de gráfico no beta | NFR3.4 |

## Orçamento da execução (p95 250 ms)

| Etapa | Orçamento |
|---|---|
| Trava da conta e inserção idempotente | 40 ms |
| Leitura do dado de mercado (C11) | 40 ms |
| Cálculo | 10 ms |
| Trava da posição, `fill`, até 3 lançamentos, posição e linha de `pnl_daily` | 90 ms |
| Maior patrimônio e evento | 40 ms |
| Folga | 30 ms |

## Medição

- `scripts/load-test.sh`, cenário `LOAD_SCENARIO=u7`: 60 bots de teste com ciclos de teste a cada 30 s chamando `execute`, com as três tarefas ativas, por 10 minutos (NFR3.1); 20 usuários consultando P&L (30 e 366 dias), ledger, operações, uso do dia e reconciliação, com 10 000 preenchimentos por pessoa gerados antes (NFR3.2, NFR3.3).
- NFR3.5: nenhuma rodada de marcação ou de fechamento pendente acima de 10 s em 60 rodadas seguidas (o que também atende o p95 ≤ 10 s em 20 rodadas do requisito). Com a coleta a cada 30 s (U4), 60 rodadas pedem 30 minutos: o NFR3.5 roda num cenário próprio, `LOAD_SCENARIO=u7-jobs`, de 30 minutos, com os ciclos do NFR3.1 rodando ao mesmo tempo.
- NFR3.6: teste de integração com o volume do NFR2.1.
