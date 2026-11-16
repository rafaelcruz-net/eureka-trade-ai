# Monitoramento — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `construction/u5-real-operations/nfr-requirements/observability-requirements.md`, `construction/u5-real-operations/nfr-design/observability-design.md` e o padrão da U4.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `real_divergences_open` | worker (`ops-gauges`) | > 0 | dinheiro do ledger sem bater com a rede |
| `real_restore_pause_active{phase}` | worker | > 0 | REAL de todos pausado |
| `real_kill_switch_active` | worker | 1 por 30 min | não esquecer ligado |
| `real_reconciliation_deferred_over_2h` | worker | > 0 | carteira sem conferência |
| `real_pending_txsigner_oldest_seconds` | worker | > 900 | teto ou encerramento sem confirmar |
| `real_release_blocked_oldest_seconds` | worker | > 3600 | liberação parada |
| `real_reconciliations_total{result}`, `real_reconciliation_seconds` | worker | — (painel) | saúde da reconciliação |
| `real_ops_job_runs_total{job}`, `real_ops_job_seconds{job}` | worker e API | sem execução na janela | trabalho parado (U5-NFR5.6) |
| `real_releases{state}`, `real_release_blocks_total{reason}` | worker | — (painel) | liberações por estado e motivo de bloqueio (U5-NFR5.1) |
| `real_kill_switch_unsent_orders` | worker | — (painel) | ordens ainda não canceladas com o kill switch (U5-NFR5.4) |
| `real_pending_txsigner_commands{kind}` | worker | — (painel) | comandos pendentes por tipo (U5-NFR5.5) |

## Alerts

| Alert | Prometheus (`infra/observability/real-ops-alerts.yml`) | CloudWatch (`infra/terraform/modules/observability/real-ops.tf`, `Eureka/RealOps`) | Severity |
|---|---|---|---|
| U5-NFR5.7 Divergência aberta | `max(real_divergences_open) > 0` | `Maximum`, 60 s, `> 0`, `notBreaching` | crítica |
| U5-NFR5.8 Pausa de restauração | `max(real_restore_pause_active) > 0` | `Maximum`, 60 s, `> 0` | crítica |
| U5-NFR5.9 Kill switch ligado | `max(real_kill_switch_active) == 1` por 30 min | `Maximum`, 300 s, 6 de 6, `>= 1` | alta |
| U5-NFR5.10 Reconciliação adiada | `max(real_reconciliation_deferred_over_2h) > 0` | `Maximum`, 300 s, `> 0` | alta |
| U5-NFR5.11 Comando pendente | `max(real_pending_txsigner_oldest_seconds) > 900` | `Maximum`, 60 s, `> 900` | alta |
| U5-NFR5.12 Liberação bloqueada | `max(real_release_blocked_oldest_seconds) > 3600` | `Maximum`, 300 s, `> 3600` | média |
| U5-NFR5.13 Trabalho parado | uma regra por trabalho: `increase(...[janela]) < 1 or absent(...)`; janelas `gates` 10 min, `reconciliation` 30 min, `kill-switch-cancel` 3 min, `txsigner-resend` 3 min, `ops-gauges` 2 min, `purge` 26 h, `restore-check` 3 min com `and on() max(real_restore_pause_active{phase="CHECK_PENDING"}) > 0` | um alarme por trabalho, `Sum` de `real_ops_job_runs_total` por `job`, período e avaliações iguais à janela (`purge` com período de 1 h e 26 avaliações), `< 1`, `breaching`; o `restore-check` é um alarme de metric math `IF(pending > 0 AND runs < 1, 1, 0) >= 1`, com `pending` = `Maximum` de `real_restore_pause_active` `phase=CHECK_PENDING` | crítica |

- **Rotas:** o tópico SNS único, como em U1 a U4.
- **Paridade:** `RealOpsAlarmParityTest`.
- **Pendência:** o caminho das métricas até o CloudWatch, comum a U1 a U5.

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| Fração das reconciliações agendadas que terminam `CLEAN` ou `DEFERRED` | 0,99, informativo | 30 dias corridos |

## Logs & Tracing

- `LogMasking` em endereços.
- O `Authorization` do GitHub é mascarado.
- Cada mudança de liberação, divergência e kill switch vai em INFO, com o motivo.
- 30 dias no CloudWatch Logs.
