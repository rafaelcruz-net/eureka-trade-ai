# Desenho de observabilidade — U4 `u4-data-requests`

Conversa: português (Brasil). Implementa `construction/u4-data-requests/nfr-requirements/observability-requirements.md` (NFR8.1 a NFR8.4).

| Requisito | Desenho |
|---|---|
| NFR8.1 | `DataRequestMetrics` registra:<ul><li>gauges `memetrade.data.requests.open` (tag `type`) e `memetrade.data.requests.overdue`;</li><li>o contador `memetrade.data.requests.created` (tags `type` e `origin`).</li></ul>Os gauges vêm de um `DataRequestCountsRefresher` em cada instância, sem ShedLock: a cada 60 s, uma consulta agregada (`count` por `type` dos abertos e dos vencidos) atualiza o cache local. Ele também roda na subida (`ApplicationReadyEvent`), então nenhuma instância fica em zero depois de subir. A coleta lê só o cache e nunca lança exceção; se a consulta falha, o valor anterior fica e o log registra a classe do erro |
| NFR8.2 | `DataRequestsHealthIndicator` lê o mesmo cache de vencidos, que tem no máximo 60 s de atraso em qualquer instância: maior que zero dá `DEGRADED` (HTTP 200), senão `UP`. Ele fica fora de liveness e readiness |
| NFR8.3 | O alerta `DATA_REQUEST_OVERDUE` (Alta) é desenhado no Infrastructure Design sobre o gauge de vencidos |
| NFR8.4 | `AuditActions` ganha `DATA_REQUEST_CREATED`, `DATA_REQUEST_TRANSITIONED`, `DATA_REQUEST_VERIFICATION_STARTED`, `DATA_REQUEST_VERIFIED` e `DATA_REQUEST_OVERDUE`. O `details` traz só os campos de NFR1.1 |
