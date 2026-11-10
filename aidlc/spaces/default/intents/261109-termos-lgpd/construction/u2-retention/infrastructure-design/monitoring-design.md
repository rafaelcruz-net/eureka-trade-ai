# Desenho de monitoramento — U2 `u2-retention`

Conversa: português (Brasil). Implementa `construction/u2-retention/nfr-design/observability-design.md` no ambiente local (actuator, Prometheus, logs JSON). A ligação com os painéis e alarmes do produto está em `aidlc/spaces/default/intents/261026-memetrade-saas-mvp/operation/observability-setup/`.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `memetrade_purge_deleted_total{purge}` | API (Micrometer) | sem limite; acompanha o volume | NFR8.1 |
| `memetrade_purge_runs_total{purge,result}` | API | `result="FAILED"` acima de 0 | falha do expurgo (NFR7.1) |
| `memetrade_purge_last_success_seconds{purge}` | API (cache) | mais de 48 h atrás | atraso do expurgo (NFR7.2) |
| Saúde `loginChallengePurge`, `sessionPurge` | actuator | `DEGRADED` | AC3.1.3 |
| Duração por lote (log) | API | p95 ≤ 500 ms | NFR3.1 |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| `PURGE_STALE` | `loginChallengePurge` ou `sessionPurge` em `DEGRADED`; pela métrica, `time() - memetrade_purge_last_success_seconds > 172800` | Média | responsável do projeto (SNS "Média" no desenho da nuvem); local: saúde e log |
| `PURGE_FAILED` | `memetrade_purge_runs_total{result="FAILED"}` aumentou | Média | igual |
| `AUDIT_FULL_WALLET_REJECTED` | log `ERROR` da recusa com motivo `FULL_WALLET` | Alta | responsável do projeto: um chamador tentou gravar carteira completa |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Dias com execução bem-sucedida de cada expurgo novo | 100%, com tolerância de um dia recuperado na subida | 30 dias corridos |
| Eventos novos da trilha com endereço completo | 0 (consultas de conferência do checklist) | contínuo |

## Logs & Tracing

- **Logs:** JSON, como hoje. O `INFO` "Purge finished" e o `ERROR` "Purge failed" trazem só o nome do expurgo, as contagens, a duração e a classe do erro. O `correlationId` é próprio de cada execução.
- **Tracing:** não há tracing novo; o expurgo não chama serviço externo.
- **Painel:** o painel de operação do produto ganha uma linha com as três métricas por expurgo.
