# Desenho de escalabilidade — U7 Ledger e P&L

Como a Unit `u7-ledger-pnl` atende a [scalability-requirements.md](../nfr-requirements/scalability-requirements.md) (NFR2.1 a NFR2.5 da U7).

| Decisão | Detalhe | Requisito |
|---|---|---|
| Volume do beta | Cerca de 200 000 preenchimentos e 800 000 lançamentos no teto; `pnl_daily` com no máximo (60 bots × tokens operados × dias) linhas, pequena | NFR2.1 |
| Índices | `paper_order (tenant_id, mode, idempotency_key)` único; `paper_order (bot_id, created_at)`; `fill (order_id)` único; `fill (bot_id, filled_at)`; `ledger_entry (bot_id, mode) INCLUDE (amount)`; `ledger_entry (owner_user_id, created_at desc, entry_id)`; `ledger_entry (reference_id)`; `ledger_entry (compensates_entry_id)` único; `position (bot_id, chain, mint)` único; `pnl_daily (bot_id, mode, chain, mint, utc_day)` único; `reconciliation_check (owner_user_id, checked_at desc)` | NFR2.2 |
| Plano das consultas | O teste de carga roda `EXPLAIN` das consultas de P&L, ledger e saldo com o volume do NFR2.1 e reprova `Seq Scan` em `ledger_entry` ou `fill` | NFR2.2 |
| Paginação | Cursor por tupla, `limit` padrão 20, máximo 100 | NFR2.3 |
| Saldo | Soma por índice de cobertura; sem coluna de saldo (evita `UPDATE` fora da matriz) | NFR2.4 |
| Várias instâncias | Tarefas com ShedLock; limitador de "verificar agora" por instância (risco aceito no beta, revisto com a hospedagem) | NFR2.5 |
