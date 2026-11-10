# Requisitos de observabilidade — U4 `u4-data-requests`

Conversa: português (Brasil). Fontes: `construction/u4-data-requests/functional-design/` (BR4.6, BR4.14), `inception/requirements-analysis/requirements.md` (NFR1, NFR8) e `nfr-requirements-questions.md` (Q2).

## Requisitos

| ID | Requisito | Verificação |
|---|---|---|
| NFR8.1 | Métricas sem rótulo de pessoa ou tenant:<ul><li>`memetrade_data_requests_open{type}` (gauge);</li><li>`memetrade_data_requests_overdue` (gauge);</li><li>`memetrade_data_requests_created_total{type,origin}`.</li></ul> | teste de integração das métricas |
| NFR8.2 | A saúde `dataRequests` fica `DEGRADED` (HTTP 200) enquanto houver pedido vencido aberto e volta a `UP`. Ela fica fora dos grupos de liveness e readiness | teste com relógio injetável |
| NFR8.3 | O alerta `DATA_REQUEST_OVERDUE` (Alta) dispara quando o gauge de vencidos passa de zero, porque o prazo de 15 dias é legal | requisito de desenho entregue no Infrastructure Design da U4; aqui a parte testável é o gauge `memetrade_data_requests_overdue` (NFR8.1) |
| NFR8.4 | Os eventos `DATA_REQUEST_*` da auditoria trazem só o identificador, o tipo, a origem e os estados; os logs trazem só identificador e estados | teste de auditoria e `LogScanIT` |
