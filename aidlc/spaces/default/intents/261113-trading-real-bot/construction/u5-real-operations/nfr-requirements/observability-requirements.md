# Requisitos de observabilidade — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `inception/requirements-analysis/requirements.md` (NFR5), `construction/u4-real-execution/nfr-requirements/observability-requirements.md` (padrão) e `nfr-requirements-questions.md` (Q4).

## Rótulos fechados

Todo rótulo vem de um enum; nenhum usuário, endereço ou mint. Os contadores são registrados com zero na subida, para todas as combinações.

| Rótulo | Valores |
|---|---|
| `job` | `gates`, `reconciliation`, `kill-switch-cancel`, `txsigner-resend`, `restore-check`, `ops-gauges`, `purge` |
| `state` | `QUARANTINE`, `ACTIVE`, `BLOCKED`, `REVOKED` |
| `reason` | `CI_RED`, `CI_UNREACHABLE`, `DIVERGENCE_OPEN`, `KILL_SWITCH_ON`, `NETWORK_MISMATCH` |
| `result` | `CLEAN`, `DIVERGENT`, `DEFERRED`, `UNAVAILABLE` |
| `kind` | `LOWER_CAP`, `DEACTIVATE` |
| `phase` | `CHECK_PENDING`, `RESTORE` |

## Métricas

Os medidores lidos do banco são atualizados só no worker, pelo trabalho `ops-gauges`, a cada 30 s.

| ID | Métrica | Tipo |
|---|---|---|
| U5-NFR5.1 | `real_releases{state}`, `real_release_blocked_oldest_seconds` e `real_release_blocks_total{reason}` | medidores e contador |
| U5-NFR5.2 | `real_reconciliations_total{result}` e `real_reconciliation_seconds` (buckets 0,5, 1, 2, 5, 10, 30 s) | contador e histograma |
| U5-NFR5.3 | `real_divergences_open` e `real_reconciliation_deferred_over_2h` | medidores |
| U5-NFR5.4 | `real_kill_switch_active`, `real_kill_switch_unsent_orders` e `real_restore_pause_active{phase}`; a pausa `RESTORE_CHECK_PENDING` entra como `phase="CHECK_PENDING"` | medidores |
| U5-NFR5.5 | `real_pending_txsigner_commands{kind}` e `real_pending_txsigner_oldest_seconds` | medidores |
| U5-NFR5.6 | `real_ops_job_runs_total{job}` e `real_ops_job_seconds{job}` | contador e temporizador |

## Alertas

| ID | Alerta | Condição | Severidade |
|---|---|---|---|
| U5-NFR5.7 | Divergência aberta | `max(real_divergences_open) > 0` | crítica |
| U5-NFR5.8 | Pausa de restauração | `max(real_restore_pause_active) > 0`, com o `phase` no texto | crítica |
| U5-NFR5.9 | Kill switch ligado | `max(real_kill_switch_active) == 1` por 30 min | alta |
| U5-NFR5.10 | Reconciliação adiada | `max(real_reconciliation_deferred_over_2h) > 0` | alta |
| U5-NFR5.11 | Comando ao TxSigner pendente | `max(real_pending_txsigner_oldest_seconds) > 900` | alta |
| U5-NFR5.12 | Liberação bloqueada há mais de 1 h | `max(real_release_blocked_oldest_seconds) > 3600` | média |
| U5-NFR5.13 | Trabalho parado | uma regra por `job`: `increase(real_ops_job_runs_total{job="<job>"}[janela]) < 1 or absent(real_ops_job_runs_total{job="<job>"})`. Janelas: `gates` 10 min; `reconciliation` 30 min; `kill-switch-cancel` 3 min; `txsigner-resend` 3 min; `ops-gauges` 2 min; `purge` 26 h; `restore-check` 3 min, só com a conferência pendente | crítica |

**Batimento:** toda execução de trabalho, mesmo sem nada a fazer, incrementa `real_ops_job_runs_total{job}` ao terminar bem; `kill-switch-cancel` e `restore-check` rodam sempre e só agem sob a condição deles. A regra de `restore-check` é `(increase(real_ops_job_runs_total{job="restore-check"}[3m]) < 1 or absent(...)) and on() max(real_restore_pause_active{phase="CHECK_PENDING"}) > 0`.

**Medidores sem dado:** quando os medidores param de chegar, o aviso vem do alerta de trabalho parado de `ops-gauges`, que inclui o `absent()`. Os alertas de medidor não repetem esse papel.

**Onde ficam:**
- **Regras:** `infra/observability/real-ops-alerts.yml`, conferidas pelo `promtool` no passo que já existe.
- **Alarmes:** no Terraform (`Eureka/RealOps`), com teste de paridade, dependendo da mesma pendência do caminho das métricas até o CloudWatch.
