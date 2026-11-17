# Requisitos de observabilidade — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes: `nfr-requirements-questions.md` (Q4) e os padrões da U4 e da U5.

## Métricas (rótulos fechados; contadores registrados com zero na subida)

| ID | Métrica | Tipo |
|---|---|---|
| U7-NFR5.1 | `real_conditionals{state}` | medidor |
| U7-NFR5.2 | `real_conditional_fills_total{side}` e `real_conditional_fill_lag_seconds` (rede até ledger) | contador e histograma |
| U7-NFR5.3 | `real_conditional_cancels_total{reason}` e `real_conditional_cancelling_oldest_seconds` | contador e medidor |
| U7-NFR5.4 | `txsigner_trigger_refusals_total{reason}` | contador |
| U7-NFR5.5 | `real_fiscal_exports_total{result}` (`OK`, `RATE_LIMITED`, `INVALID`) | contador |
| U7-NFR5.6 | `real_exits_job_runs_total{job}` | contador (batimento) |
| U7-NFR5.6b | `real_conditional_unread_oldest_seconds` (idade da leitura bem-sucedida mais antiga entre as `OPEN`) | medidor, base do alerta U7-NFR5.8 |

## Alertas (Prometheus e CloudWatch, com teste de paridade)

| ID | Alerta | Severidade |
|---|---|---|
| U7-NFR5.7 | `real_conditional_cancelling_oldest_seconds > 900` (mesmo limiar de U7-NFR4.11) | crítica |
| U7-NFR5.8 | `real_conditional_unread_oldest_seconds > 7200` | alta |
| U7-NFR5.9 | recusa da `TriggerPolicy` (servidor fora da política) | alta |
| U7-NFR5.10 | trabalho parado (`read` 2 min; `cancel` 3 min) | crítica |

| ID | Requisito | Verificação |
|---|---|---|
| U7-NFR5.11 | Os logs da U7 mascaram endereços (`LogMasking`) e nunca trazem a chave de API nem o conteúdo da exportação | teste de log com captura de saída |
