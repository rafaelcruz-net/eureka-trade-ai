# Desenho de observabilidade — U7 Ledger e P&L

Como a Unit `u7-ledger-pnl` atende a [observability-requirements.md](../nfr-requirements/observability-requirements.md) (NFR9.1 a NFR9.7 da U7). Sem métricas de negócio próprias (Q4 de NFR Requirements).

| Sinal | Desenho | Requisito |
|---|---|---|
| Saúde das tarefas | Indicador `ledgerJobs` do Actuator, fora da prontidão: `UP` em operação normal; `DEGRADED` (HTTP 200, mapeamento já existente da U2) quando a última rodada de marcação, fechamento pendente ou reconciliação falhou, quando alguma passou de 3 intervalos sem rodar, ou quando a última reconciliação encontrou divergência; detalhe com o horário da última rodada de cada tarefa e o número de pessoas com divergência | NFR9.1 |
| Taxa de erro | `http.server.requests` por modelo da rota e status | NFR9.2 |
| Trilha | Eventos `PAPER_ACCOUNT_FUNDED`, `PAPER_ORDER_FILLED`, `PAPER_ORDER_NOT_FILLED` (com o motivo), `POSITION_CLOSE_PENDING`, `POSITION_CLOSE_COMPLETED` (ator `system`), `LEDGER_ENTRY_COMPENSATED` pela C9, na mesma transação; `Duplicate` não publica | NFR9.3 |
| Logs | `CorrelationIdFilter` da U1; cada rodada de tarefa registra início, fim, contas tratadas, contas puladas por trava e falha; sem valores nem pessoa | NFR9.4 |
| Rótulos | Só modelo da rota e status; `MetricsLabelsIT` exercita as rotas da U7 | NFR9.5 |
| Alertas | Nenhum no beta local | NFR9.6 |
| Divergência ao operador | Rodada com divergência: log `WARN` "reconciliation found divergences" com pessoas afetadas (número) e contagem por tipo; mesma contagem no detalhe do `ledgerJobs` | NFR9.7 |
