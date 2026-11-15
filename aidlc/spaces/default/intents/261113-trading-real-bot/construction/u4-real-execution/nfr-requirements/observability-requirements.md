# Requisitos de observabilidade — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes: `inception/requirements-analysis/requirements.md` (NFR5), `construction/u3-real-risk/infrastructure-design/monitoring-design.md` (padrão) e `nfr-requirements-questions.md` (Q6).

## Métricas

Nenhum rótulo leva mint, endereço, assinatura ou usuário.

| ID | Métrica | Tipo |
|---|---|---|
| U4-NFR5.1 | `real_orders_total{side,mode,state,code}` (transições) | contador |
| U4-NFR5.2 | `real_order_decision_to_send_seconds{mode}`, buckets 0,5, 1, 2, 3, 5, 10 s | histograma |
| U4-NFR5.3 | `real_order_send_to_final_seconds`, buckets 2, 5, 10, 20, 40, 60 s | histograma |
| U4-NFR5.4 | `real_orders_ambiguous_open` e `real_orders_ambiguous_oldest_seconds` | medidores (só no worker) |
| U4-NFR5.5 | `txsigner_swap_decisions_total{outcome,reason}` (no TxSigner) | contador |
| U4-NFR5.6 | `real_upstream_failures_total{provider}` e `real_upstream_pauses_total` | contadores |
| U4-NFR5.7 | `real_execution_job_runs_total{job}` | contador |
| U4-NFR5.8 | `real_decision_queue_wait_seconds` | histograma |

## Alertas

| ID | Alerta | Condição | Severidade |
|---|---|---|---|
| U4-NFR5.9 | Ordem ambígua antiga | `real_orders_ambiguous_oldest_seconds > 300` | crítica |
| U4-NFR5.10 | Recusa da política de swap | `increase(txsigner_swap_decisions_total{outcome="REFUSED"}[5m]) > 0` | crítica |
| U4-NFR5.11 | Falha de assinatura | `increase(real_orders_total{code="SIGNING_REFUSED"}[5m]) > 0` ou TxSigner indisponível | crítica |
| U4-NFR5.12 | Pausa por fornecedor | `increase(real_upstream_pauses_total[5m]) > 0` | alta |
| U4-NFR5.13 | Execução lenta | p95 de `real_order_decision_to_send_seconds{mode="AUTO_FULL"}` acima de 3 s por 10 min | aviso |
| U4-NFR5.14 | Trabalho parado | `increase(real_execution_job_runs_total{job}[janela]) < 1` ou ausente, com janela de 1 min (fila, envio e confirmação) ou 5 min (ambíguas, saúde, liquidação) | crítica |

**Rótulos fechados.** Todo rótulo vem de um enum do código; nenhum texto livre entra. Os valores possíveis:
- **`code`:** os `failureCode` da `RealOrder`, mais `REQUOTED` e `NONE`.
- **`reason`:** os motivos de recusa da política do TxSigner.
- **`provider`:** `JUPITER`, `RPC` e `TXSIGNER`.
- **`job`:** `queue`, `driver`, `confirmation`, `ambiguous`, `signing-deadline`, `health` e `liquidation`.

Na subida, cada contador é registrado com valor zero para todas as combinações de rótulos usadas nos alertas, para que `increase()` dispare já na primeira ocorrência.

**Trabalho parado (U4-NFR5.14).** Há uma regra por trabalho, com o rótulo `job` explícito: `increase(...{job="queue"}[1m]) < 1 or absent(real_execution_job_runs_total{job="queue"})`, e o mesmo para os outros trabalhos.

**Regras e alarmes:**
- As regras ficam em `infra/observability/real-execution-alerts.yml` e são conferidas pelo `promtool`.
- Os alarmes no Terraform usam o namespace `Eureka/RealExecution`, com teste de paridade.
- O alarme de p95 fica adiado.
- Os outros alarmes têm a forma definida no Infrastructure Design, mas dependem da mesma pendência das U1 a U3: a decisão do caminho das métricas até o CloudWatch, a tomar antes da mainnet.
- O medidor `real_orders_sent_oldest_seconds` (U4-NFR3.5) tem alerta crítico acima de 75 s.
