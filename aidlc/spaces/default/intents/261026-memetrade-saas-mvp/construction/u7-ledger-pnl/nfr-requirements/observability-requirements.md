# Requisitos de observabilidade — U7 Ledger e P&L

Requisitos de monitoração, log e auditoria da Unit `u7-ledger-pnl`, derivados do NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md), das regras de [rules.md](../functional-design/rules.md) e da resposta Q4 de [nfr-requirements-questions.md](nfr-requirements-questions.md): a U7 publica só a métrica de saúde e a de erro exigidas pelo NFR9, sem contadores de negócio próprios. A divergência da reconciliação fica visível na tela de cada pessoa (BR8.4).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Saúde das tarefas da U7 | Um indicador de saúde das tarefas de marcação, fechamento pendente e reconciliação fica `DEGRADED` (HTTP 200, como na U2 e na U4) quando a última rodada falhou, passou de 3 intervalos sem rodar ou a última reconciliação encontrou divergência; nunca `DOWN`, para não derrubar a saúde geral; o detalhe traz o horário da última rodada de cada tarefa e o número de pessoas com divergência na última reconciliação, sem identificá-las | NFR9, NFR10.6, padrão da U2 |
| NFR9.7 | Divergência visível ao operador | Uma rodada de reconciliação com alguma divergência registra um log `WARN` com a quantidade de pessoas e de divergências por tipo, sem pessoa, bot nem valor; o operador vê a mesma contagem na saúde (NFR9.1). Não é métrica de negócio (Q4) | NFR10.6, BR8.4 |
| NFR9.2 | Taxa de erro das rotas da U7 | A métrica `http.server.requests` cobre as rotas C5 pelo modelo da rota e o status; a taxa de 5xx sai dela. Nenhuma métrica de negócio nova (Q4) | NFR9, Q4 |
| NFR9.3 | Eventos de auditoria | `PAPER_ACCOUNT_FUNDED`, `PAPER_ORDER_FILLED`, `PAPER_ORDER_NOT_FILLED` (com o motivo), `POSITION_CLOSE_PENDING`, `POSITION_CLOSE_COMPLETED` (ator `system`) e `LEDGER_ENTRY_COMPENSATED` publicados pela C9 na mesma transação; uma repetição `Duplicate` não publica evento. Teste confere um evento por ação | BR2.6, BR5.2, BR5.3, BR4.2 |
| NFR9.4 | Logs com correlação | 100% das requisições C5 com `correlationId` no log e no `ProblemDetail`; as tarefas registram início, fim, quantidade tratada e falha de cada rodada, com identificadores e sem valores pessoais | NFR9 |
| NFR9.5 | Rótulos seguros | Nenhum rótulo de métrica com pessoa, bot, token ou valor; o teste de rótulos (`MetricsLabelsIT`) passa a exercitar as rotas da U7 | NFR7, NFR9 |
| NFR9.6 | Sem alertas no beta local | Métricas, saúde e logs disponíveis no ambiente; alertas definidos com a hospedagem | NFR10 |
