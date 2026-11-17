# Monitoramento — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `construction/u7-exits-and-fiscal/nfr-requirements/observability-requirements.md` (U7-NFR5.x) e `construction/u7-exits-and-fiscal/nfr-design/observability-design.md`.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `real_conditionals{state}` | worker (`exits-gauges`) | — (painel) | condicionais por estado |
| `real_conditional_fills_total{side}`, `real_conditional_fill_lag_seconds` | worker | — (painel) | preenchimentos e atraso até o ledger |
| `real_conditional_cancels_total{reason}` | worker | — (painel) | cancelamentos por motivo |
| `real_conditional_cancelling_oldest_seconds` | worker | > 900 | cancelamento parado |
| `real_conditional_unread_oldest_seconds` | worker | > 7200 | condicional sem leitura |
| `txsigner_trigger_refusals_total{reason}` | TxSigner | > 0 | servidor fora da política |
| `real_fiscal_exports_total{result}` | API | — (painel) | exportações |
| `real_exits_job_runs_total{job}` | worker e API | sem execução na janela | trabalho parado |

## Alerts

| Alert | Prometheus (`infra/observability/real-exits-alerts.yml`) | CloudWatch (`infra/terraform/modules/observability/real-exits.tf`, `Eureka/RealExits`) | Severity |
|---|---|---|---|
| U7-NFR5.7 Cancelamento parado | `max(real_conditional_cancelling_oldest_seconds) > 900` | `Maximum`, 60 s, `> 900` | crítica |
| U7-NFR5.8 Condicional sem leitura | `max(real_conditional_unread_oldest_seconds) > 7200` | `Maximum`, 300 s, `> 7200` | alta |
| U7-NFR5.9 Recusa da política | `increase(txsigner_trigger_refusals_total[5m]) > 0` | `Sum`, 300 s, `> 0` | alta |
| U7-NFR5.10 Trabalho parado | uma regra por trabalho: no worker, `read` 2 min, `cancel` 3 min e `exits-gauges` 2 min; na API, `purge` 26 h | um alarme por trabalho, `Sum`, `< 1`, `breaching` | crítica |

- **Rotas:** o tópico SNS único, como nas outras Units.
- **Paridade:** `RealExitsAlarmParityTest` confere as regras do Prometheus e os alarmes do CloudWatch. Na nuvem, a paridade só tem efeito depois que existir o caminho das métricas até o CloudWatch.
- **Pendência:** o caminho das métricas até o CloudWatch, comum a U1 a U7.

## Logs

- `LogMasking` nos endereços.
- O cabeçalho da chave da Jupiter é mascarado.
- A exportação só registra o período e a contagem de linhas.
