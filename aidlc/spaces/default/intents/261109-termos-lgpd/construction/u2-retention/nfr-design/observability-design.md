# Desenho de observabilidade — U2 `u2-retention`

Conversa: português (Brasil). Implementa `construction/u2-retention/nfr-requirements/observability-requirements.md` (NFR8.1 a NFR8.5).

| Requisito | Desenho |
|---|---|
| NFR8.1 | O executor comum registra no Micrometer:<ul><li>`memetrade.purge.deleted` (contador, tag `purge`);</li><li>`memetrade.purge.runs` (contador, tags `purge` e `result`);</li><li>`memetrade.purge.last.success` (gauge em segundos desde a época, tag `purge`). O valor fica em cache numa `AtomicReference`, atualizada no fim de cada execução e na subida a partir de `identity_job_run`. A coleta nunca consulta o banco nem lança exceção; sem valor, devolve `NaN`.</li></ul>No Prometheus, os nomes viram `memetrade_purge_deleted_total`, `memetrade_purge_runs_total` e `memetrade_purge_last_success_seconds`. Nenhuma tag tem dado pessoal |
| NFR8.2 | Os indicadores publicam `lastSuccessAt`, `lastResult` e `lastRunAt` (instante de início da execução) nos detalhes |
| NFR8.3 | O log `INFO` tem a forma `Purge finished: purge=..., deleted=..., batches=..., durationMs=...`, e o `ERROR` tem a forma `Purge failed: purge=..., deletedSoFar=..., cause=<classe>`. São logs JSON com `correlationId` próprio da execução |
| NFR8.4 | O evento é `AuditEventCommand.system("LOGIN_CHALLENGES_PURGED" \| "SESSIONS_PURGED", ...)` com `details` `{deleted, batches}` e, nas sessões, `digestsDeleted`. As duas ações entram em `AuditActions` |
| NFR8.5 | O alerta `PURGE_STALE` (Média) dispara quando `loginChallengePurge` ou `sessionPurge` fica em `DEGRADED`. Localmente, aparece na saúde e no log; na nuvem, é desenhado no Infrastructure Design |

## SLI

Fração de dias com `memetrade_purge_runs_total{result="SUCCEEDED"}` incrementado, por expurgo, em 30 dias.
