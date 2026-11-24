# Desenho de monitoramento — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` (R-01) e da decisão do fundador de 2026-11-23 (D-INF3, abaixo).

## Decisão do fundador (2026-11-23)

| ID | Decisão | Efeito neste desenho |
|---|---|---|
| D-INF3 | **Evidência na nuvem dos três contadores (R-01, opção a).** Na nuvem, a evidência são os logs `ERROR` limitados e os eventos de auditoria, que já existem. Os contadores ficam só no Prometheus e só são lidos no ambiente local e na CI. | Não existe caminho de leitura de `/actuator/prometheus` na nuvem: o Terraform não liga `enable_execute_command` e a porta 8081 só aceita o balanceador. O texto que citava um "caminho de manutenção" do `rollback-runbook.md` saiu. O `terraform plan` continua vazio. |

Este arquivo leva o `observability-design.md` do NFR Design para a plataforma. A U3 acrescenta três contadores em `/actuator/prometheus` e nada mais [Q2]:

- nenhum alarme, nenhuma regra em `infra/observability/`;
- nenhuma entrada na lista de permissão do CloudWatch (`app_metric_declarations`), então o sidecar não muda;
- nenhum painel, consulta salva, rastreamento ou SLO operacional novo.

Na nuvem, a evidência das recusas e das falhas fica nos eventos de auditoria e nos logs JSON com `correlationId` (D-INF3). Os contadores são lidos só no ambiente local e na CI.

## Métricas e KPIs

| Métrica (Prometheus) | Fonte | Limite | Por que importa |
|---|---|---|---|
| `pnl_compare_template_mismatches_total` (Micrometer `pnl.compare.template_mismatches`; 1 série) | `/actuator/prometheus` da API (porta 8081) | sem limite nem alarme | Respostas 422 `TEMPLATE_MISMATCH` de C6 depois da posse; uma subida repetida sugere sondagem de pares (NFR3.9). |
| `pnl_compare_side_unavailable_total{market,cause}` (`pnl.compare.side_unavailable`; 10 séries: 2 `market` × 5 `cause`) | API | sem limite nem alarme | Lados de C6 devolvidos `UNAVAILABLE`, separados dos blocos de C4 (NFR3.15). |
| `pnl_read_audit_failures_total{action}` (`pnl.read_audit.failures`; 2 séries) | API | sem limite nem alarme | Gravação da auditoria das recusas que falhou, passou de 200 ms, ficou sem conexão ou foi recusada pela fila cheia (NFR3.16). |
| `pnl_mode_block_unavailable_total{market,cause}` (U1 e U2, já existe) | API | o mesmo de antes; continua contando só C4 | Base dos portões e alarmes da U2; a U3 não muda o significado (NFR3.15). |
| `hikaricp_connections_pending{pool}` (já existe, inclusive `pool="pnl-mismatch-audit"`) | API | pool principal: `> 0` por no máximo 10 s seguidos no cenário de carga (U2) | Diagnóstico da disputa das 3 threads pelas 2 conexões do `pnl-mismatch-audit`. |
| `http_server_requests_seconds{uri}` (já existe, `percentiles-histogram` ligado) | API | sem limite operacional novo | SLI de latência das 4 rotas da U3 (NFR1.16). |

As 13 séries novas são registradas com 0 na subida, com etiquetas só de enum, sem tenant, pessoa, bot, carteira, endereço ou `correlationId`.

### Coleta e publicação

| Ambiente | Como a métrica é lida | O que muda na U3 |
|---|---|---|
| Local e CI | `/actuator/prometheus` direto; `PnlViewsPrometheusIT` confere os três nomes com 0 antes do primeiro evento | Nada na infraestrutura |
| Nuvem, leitura | Sidecar `metrics-agent` lê `/actuator/prometheus` a cada 120 s | Nada |
| Nuvem, publicação | Só a lista de `infra/terraform/modules/observability/app-metrics.tf` chega ao CloudWatch (`Eureka/App`) | Nada: os três contadores **não** entram, e o filtro `keep` os descarta [Q2] |
| Nuvem, consulta | Os contadores **não** são lidos na nuvem: não há caminho para a porta 8081 (sem `enable_execute_command`, porta aberta só ao balanceador). A evidência é a tabela abaixo (D-INF3) | Nada |

### Evidência na nuvem de cada contador (D-INF3)

| Contador (local e CI) | Evidência na nuvem | Onde se lê |
|---|---|---|
| `pnl_compare_template_mismatches_total` | evento de auditoria `PNL_COMPARE_TEMPLATE_MISMATCH` (1 por hora por pessoa) e log `INFO` `event=PNL_COMPARE_TEMPLATE_MISMATCH` (cada 422) | trilha de auditoria; CloudWatch Logs Insights |
| `pnl_compare_side_unavailable_total{market,cause}` | log `WARN` `event=PNL_COMPARE_SIDE_UNAVAILABLE market=<enum> cause=<rótulo>` | CloudWatch Logs Insights |
| `pnl_read_audit_failures_total{action}` | log `ERROR` `event=PNL_READ_AUDIT_FAILED action=<enum> category=<...>`, no máximo 1 por minuto por pessoa e ação | CloudWatch Logs Insights |

O log `ERROR` limitado mostra que houve falha da auditoria e a categoria, mas não o número exato de falhas dentro do mesmo minuto; o fundador aceitou essa perda de precisão na nuvem.

## Alertas

| Alerta | Condição | Severidade | Destino |
|---|---|---|---|
| Nenhum alarme novo | Os três contadores ficam só no Prometheus [Q2] | — | — |
| Alarmes existentes que tocam a U3 | `RealOpsPnlReconciliationMismatch` (Prometheus) e `real_ops_pnl_reconciliation_mismatch` (CloudWatch), da U2: como C5 e o lado REAL de C6 usam a mesma passada conferida da U2, uma divergência numa leitura da U3 também sobe `pnl_real_reconciliation_mismatches_total` | `critical` (sem mudança) | tópico SNS único do piloto, fundador (sem mudança) |

| Arquivo | Muda? | Conferido por |
|---|---|---|
| `infra/observability/*.yml` | não (0 regras novas) | `promtool check rules` do job `backend`, com as mesmas regras |
| `infra/terraform/modules/observability/real-ops.tf` | não | `RealOpsAlarmParityTest`, sem mudança |
| `infra/terraform/modules/observability/app-metrics.tf` | não | `observability_metrics.tftest.hcl` (manual), sem mudança |
| `infra/terraform/modules/services/metrics-agent.tf` | não, nem a configuração gerada | `terraform plan` vazio |
| `docs/runbooks/real-operations.md` | não | — |

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| p95 de `http_server_requests_seconds` por `uri` em `/api/v1/pnl/by-mode`, `/api/v1/bots`, `/api/v1/bots/{botId}/pnl/lines` e `/api/v1/pnl/compare` | Nenhum SLO operacional novo (NFR4.6). Referência de desempenho: p95 ≤ 1.000 ms, conferido como portão só no cenário `pnl-by-mode` ampliado | os 600 s medidos do cenário, depois de 60 s de aquecimento |
| p95 por leitura rotulada no cliente (`load-test.mjs`): C4 `RANGE` 30 e 366 dias, C5 1ª página e página seguinte, C6 `ALL`, `RANGE` 30 e 366 dias | ≤ 1.000 ms em cada uma das 7 leituras novas; 0 respostas diferentes de 200 (NFR1.6) | idem; o histograma por `uri` não separa os períodos, por isso a medida é por rótulo no cliente |
| p95 dos 11 casos do `PnlViewsLatencyIT` | ≤ 1.000 ms, só registrado no log do job `backend` (NFR1.5) | 100 pedidos medidos por caso, depois de 10 de aquecimento |

## Logs e rastreamento

| Item | Decisão |
|---|---|
| Agregação | O encoder JSON que já existe (`logback-spring.xml`) para stdout; na nuvem, o driver `awslogs` do ECS para os grupos `/ecs/...` do `api-worker`. Sem grupo, retenção ou consulta salva nova. |
| Eventos da U3 | `PNL_READ_NOT_FOUND` (`INFO`, `reason` em enum), `PNL_COMPARE_TEMPLATE_MISMATCH` (`INFO`), `PNL_COMPARE_SIDE_UNAVAILABLE` (`WARN`), `PNL_BOT_LINES_UNAVAILABLE` (`WARN`), `PNL_READ_AUDIT_FAILED` (`ERROR`, no máximo 1 por minuto por pessoa e ação, com `category`) (NFR3.10). |
| Correlação | `correlationId` da thread do pedido, levado pelo MDC copiado ao pool `pnl-mode-block` e ao executor `pnl-read-audit`; igual na resposta, no evento de auditoria e no log (NFR3.11). |
| Dados proibidos | Nenhum endereço de carteira, `templateId` de outra pessoa, token, cabeçalho `Authorization`, nonce ou assinatura (`project.md`, Forbidden; NFR3.7). |
| Consulta na nuvem | CloudWatch Logs Insights por `event=` e `correlationId`, nos grupos que já existem. |
| Rastreamento | 0 bibliotecas de rastreamento e 0 telemetria no `apps/web` (NFR3.12). |
| Painéis | 0 painéis novos (NFR8.4). |

## Sources

- `infrastructure-design-questions.md` desta pasta: Q2 e o resumo confirmado [Q2].
- `reviews/review-01.md` (R-01) e a decisão do fundador de 2026-11-23 (D-INF3).
- `construction/u3-pnl-views/nfr-design/observability-design.md`: contadores, registro com 0, logs, SLI, NFR8.4.
- `construction/u3-pnl-views/nfr-design/performance-design.md` e `nfr-requirements/performance-requirements.md`: NFR1.5, NFR1.6 (9 leituras, 7 portões).
- `construction/u2-real-bot-pnl/infrastructure-design/monitoring-design.md`: formato, coleta do sidecar, alarme da divergência.
- Arquivos lidos: `infra/terraform/modules/observability/app-metrics.tf`, `apps/api/src/main/resources/application.yml` (`percentiles-histogram`, porta 8081), `infra/observability/` (lista dos arquivos de regras).
- `aidlc/spaces/default/memory/project.md`: Corrections (CloudWatch Agent a 120 s); Forbidden (logs sem segredos).

## Assumptions & Open Questions

1. [assumption] Um alarme para as recusas de comparação ou para as falhas da auditoria da leitura, se um dia for preciso, é decidido em outro estágio; os contadores já ficam prontos no Prometheus.
2. Decidido pelo fundador (D-INF3): na nuvem, os logs `ERROR` limitados e os eventos de auditoria bastam para o diagnóstico do fundador, que é o único on-call; os contadores ficam só no ambiente local e na CI.
