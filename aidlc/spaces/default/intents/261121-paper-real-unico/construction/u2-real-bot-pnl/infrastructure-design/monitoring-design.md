# Desenho de monitoramento — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md` (R-01, R-04, R-07) e da decisão do fundador D-INF1 (`infrastructure-specification.md`).

Este arquivo leva o `observability-design.md` do NFR Design para a plataforma. A U2 acrescenta:

- cinco métricas em `/actuator/prometheus` e a etiqueta `cause` no contador da U1;
- uma regra do Prometheus e um alarme do CloudWatch, os dois da divergência do P&L REAL;
- uma métrica na lista de permissão do agente;
- uma seção no runbook de operações do REAL.

Ela não acrescenta painel, consulta salva, tracing nem SLO operacional [Q3].

## Métricas e KPIs

| Métrica (Prometheus) | Fonte | Limite | Por que importa |
|---|---|---|---|
| `pnl_real_reconciliation_mismatches_total{check}` (Micrometer `pnl.real.reconciliation.mismatches`; 8 valores de `check`) | `/actuator/prometheus` da API (porta 8081 local, 8081 no contêiner `api`) | `> 0` em 240 s dispara o alarme `RealOpsPnlReconciliationMismatch` | Toda leitura com o P&L REAL diferente do ledger; o bloco sai `UNAVAILABLE` com `RECONCILIATION_MISMATCH` (NFR2.1, NFR2.11, NFR2.14). **Única métrica da U2 no CloudWatch.** |
| `ledger_real_slot_missing_total` (`ledger.real.slot_missing`) | API e worker | Sem limite nem alarme | Linhas antigas sem slot entregues pelo fluxo; deve cair com o tempo (NFR2.4, NFR2.12). |
| `ledger_real_slot_conflicts_total{writer}` (`ledger.real.slot_conflicts`; 3 valores de `writer`) | API e worker, depois do commit do gravador | Sem limite nem alarme | Conflito de slot recusado e auditado (NFR1.21, NFR2.13). |
| `pnl_real_reconciliation_audit_failures_total` (`pnl.real.reconciliation.audit_failures`) | API | Sem limite nem alarme | Gravação do evento de divergência que falhou, passou de 200 ms ou ficou sem tempo do bloco (NFR3.15, NFR3.16). |
| `pnl_real_attribution_read_seconds{path,outcome}` (temporizador com histograma; limites 0,1; 0,25; 0,5; 0,8; 1 e 2 s) | `PNL` na API; `GATE` e `CYCLE` no worker | p95 ≤ 500 ms (`PNL`) e ≤ 250 ms (`GATE`, `CYCLE`), conferidos no cenário de carga e no Build and Test, não por alarme | Orçamento da atribuição e taxa de `TIMEOUT` (NFR1.3, NFR1.4, NFR1.23). |
| `pnl_mode_block_unavailable_total{market,cause}` (U1, ganha `cause`; 10 séries) | API | `cause="TIMEOUT"` no REAL ≤ 0,5% das respostas 200 de C4 no cenário de carga; 0 das outras causas | Portão efetivo do `pnl-by-mode` (NFR1.6). |
| `hikaricp_connections_pending{pool}` (já existe; ganha `pool="pnl-mismatch-audit"`, ligado por `HikariConfig.setMetricRegistry`, porque o pool não é bean `DataSource`) | API | Pool principal: `> 0` por no máximo 10 s seguidos no cenário de carga | Saturação dos pools (NFR1.16). |
| `http_server_requests_seconds{uri}` (já existe) | API | p95 de C4 ≤ 1.000 ms no cenário de carga | SLI de C4 e de `GET /api/v1/real/pnl` (NFR1.1, NFR1.2, NFR1.24). |

Séries novas: no máximo 133 por processo e 141 na API (com as 8 da etiqueta `cause`), todas registradas com 0 na subida. Nenhuma etiqueta leva tenant, pessoa, bot, carteira, endereço, assinatura, mint ou `correlationId` (`observability-design.md`).

### Coleta e publicação

| Ambiente | Como a métrica é lida | O que muda na U2 |
|---|---|---|
| Local e CI | `/actuator/prometheus` direto; `U2MetricsExportIT` confere os cinco nomes exportados | Nada na infraestrutura |
| Nuvem, leitura | Sidecar `metrics-agent` lê `/actuator/prometheus` a cada 120 s nas portas 8081 (api) e 8082 (worker) | Nada |
| Nuvem, configuração do agente | `CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` do sidecar, gerados da lista de permissão para `api-worker` e `txsigner` | Uma revisão nova de cada definição de tarefa, só do sidecar; entram em serviço na implantação do `api-worker` e no passo do fundador com MFA do `txsigner` (D-INF1) |
| Nuvem, publicação | Só a lista de permissão de `infra/terraform/modules/observability/app-metrics.tf` (`app_metric_declarations`) chega ao CloudWatch | `pnl_real_reconciliation_mismatches_total` entra no grupo sem dimensões (`dimensions = [[]]`): o agente soma as 8 séries de `check` e publica o delta por leitura em `Eureka/App` [Q3] |
| Nuvem, o que fica de fora | As outras quatro métricas, a etiqueta `cause` e as métricas `hikaricp_*` do pool novo | Descartadas pelo filtro `keep`; o histograma da atribuição seria descartado de qualquer forma, porque o agente não publica histogramas (`app-metrics.tf`) |

## Alertas

| Alerta | Condição | Severidade | Destino |
|---|---|---|---|
| `RealOpsPnlReconciliationMismatch` (Prometheus, `infra/observability/real-ops-alerts.yml`, grupo `real-ops`) | `sum(increase(pnl_real_reconciliation_mismatches_total[4m])) > 0`; anotações `threshold: "0"` e `window_seconds: "240"`; `summary` aponta para `docs/runbooks/real-operations.md`, seção 8 | `critical` | avaliado na CI por `promtool`; na nuvem, o par do CloudWatch abaixo |
| `real_ops_pnl_reconciliation_mismatch` (CloudWatch, `infra/terraform/modules/observability/real-ops.tf`) | `alarm_name = "${var.name}-real-ops-pnl-reconciliation-mismatch"`; `namespace = local.real_ops_namespace`; métrica `pnl_real_reconciliation_mismatches_total`; `statistic = "Sum"`; `period = 240`; `evaluation_periods = 1`; `threshold = 0`; `GreaterThanThreshold`; `treat_missing_data = "notBreaching"`; `alarm_description` com "U2 NFR2.14" e "(critical)" | `critical` (na descrição, como os demais alarmes de `real-ops.tf`) | `alarm_actions = [var.alarm_topic_arn]`, o tópico SNS único do piloto, que chega ao fundador (único on-call) |
| Nenhum outro alarme | `ledger_real_slot_missing_total`, `ledger_real_slot_conflicts_total`, `pnl_real_attribution_read_seconds` e `pnl_real_reconciliation_audit_failures_total` ficam só no Prometheus e no relatório de carga | — | — (NFR2.15) |

### Onde cada mudança cai

| Arquivo | Mudança | Conferido por |
|---|---|---|
| `infra/observability/real-ops-alerts.yml` | a regra acima, no fim do grupo `real-ops`, com comentário que cita o NFR2.14 e diz que o contador sobe em toda leitura com divergência (o limite de 1 evento por hora vale só para a auditoria) | `promtool check rules` no job `backend` (o arquivo já está na lista do `ci.yml`) |
| `infra/terraform/modules/observability/real-ops.tf` | o recurso `aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch` | `observability_metrics.tftest.hcl` (manual) e `RealOpsAlarmParityTest` |
| `infra/terraform/modules/observability/app-metrics.tf`, `app_metric_declarations` | `"pnl_real_reconciliation_mismatches_total"` no grupo `dimensions = [[]]` | `observability_metrics.tftest.hcl`: o alarme lê uma métrica da lista com um conjunto de dimensões dela |
| `infra/terraform/modules/observability/app-metrics.tf`, `alarm_resource_names` e `metric_alarms` | `"real_ops_pnl_reconciliation_mismatch"` e `aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch`, junto dos outros `real_ops_*` | `observability_metrics.tftest.hcl`: a lista bate com os blocos dos `.tf` |
| `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java` | `assertThat(rules).hasSize(13)` passa a `hasSize(14)`; o nome em kebab da regra (`real-ops-pnl-reconciliation-mismatch`) casa com o `alarm_name`; limite 0 e janela 240 iguais; a expressão contém `> 0` e `[4m]` | job `backend` |
| `docs/runbooks/real-operations.md` | seção nova **"8. Divergência do P&L REAL"** (abaixo) | revisão do responsável |
| `docs/runbooks/real-operations.md` | seção nova **"9. Índice da migração 0023"**: os cinco passos de `cicd-pipeline.md`, "Falha no meio da criação do índice" (`to_regclass`, `db-migrate` de novo, comando que solta a trava do changelog) | revisão do responsável; `Migration0023IndexRestartIT` prova o recomeço |
| `infra/terraform/modules/services/metrics-agent.tf` (sem edição) | `CW_CONFIG_CONTENT` e `PROMETHEUS_CONFIG_CONTENT` do sidecar das famílias `api-worker` e `txsigner` mudam sozinhos, porque são gerados da lista de permissão: uma revisão nova de cada definição de tarefa, só do sidecar (D-INF1) | `terraform plan` revisado pelo fundador |

### Janela do alarme

| Regra (`project.md`, correção do CloudWatch Agent) | Aplicação |
|---|---|
| O agente lê a cada 120 s | o período de 240 s contém duas leituras |
| Alarme com dado ausente como violação: múltiplos de 240 s | não se aplica: o alarme usa `notBreaching` |
| Demais alarmes: pelo menos 180 s (uma leitura) | 240 s ≥ 180 s; `CloudWatchWindows.cloudWindow(240, false)` = 240, igual ao `window_seconds` da regra |

**Comportamento aceito (NFR2.14):** o alarme fica em `ALARM` enquanto a divergência existir e alguém ler o P&L; sem leitura por 240 s ele volta a `OK` e volta a `ALARM` na leitura seguinte. Sem divergência o contador não muda e o agente não publica delta, por isso `notBreaching`.

### Seção nova do runbook: "8. Divergência do P&L REAL"

| Item | Conteúdo |
|---|---|
| O que significa | Uma leitura do P&L REAL de uma pessoa não bateu com o ledger numa das 8 verificações. O bloco REAL saiu `UNAVAILABLE` com `RECONCILIATION_MISMATCH`, sem número parcial. Ordens, barreira e saque não são afetados: a divergência afeta só a leitura do P&L. |
| Alertas | `RealOpsPnlReconciliationMismatch` (Prometheus) e `real_ops_pnl_reconciliation_mismatch` (CloudWatch), críticos. |
| Passo 1 | Achar o evento de auditoria `REAL_PNL_RECONCILIATION_MISMATCH` pelo `correlationId` do bloco ou pela `fingerprint`; ler as verificações que falharam e as diferenças em lamports (que ficam só no evento, nunca no log). |
| Passo 2 | No CloudWatch Logs Insights, buscar `event=REAL_PNL_RECONCILIATION_MISMATCH` com o mesmo `correlationId`; `auditWritten = failed` indica que o evento não foi gravado (ver `pnl_real_reconciliation_audit_failures_total` em `/actuator/prometheus`). |
| Passo 3 | Comparar com a reconciliação da rede (seção 2): divergência aberta da carteira aponta para o ledger; reconciliação `CLEAN` aponta para defeito do cálculo. |
| Passo 4 | Abrir o defeito com teste de regressão que reproduz a sequência (`team.md`, Defeitos). Não compensar o ledger por causa desta divergência: a compensação é só da seção 2. |
| Escalonamento | O fundador é o único on-call (`team.md`, Deployment). |
| Seção relacionada | Seção 9, "Índice da migração 0023" (`cicd-pipeline.md`, Rollback). |

## SLIs e SLOs

| SLI | Meta de SLO | Janela de medição |
|---|---|---|
| p95 de `http_server_requests_seconds{uri="/api/v1/pnl/by-mode"}` | Nenhum SLO operacional novo (NFR4.4); portão de 1.000 ms no cenário `pnl-by-mode` | 600 s de carga depois de 60 s de aquecimento, no workflow manual `load-test` |
| p95 de `http_server_requests_seconds{uri="/api/v1/real/pnl"}` | Só registrado no relatório de carga (NFR1.2) | idem |
| p95 de `pnl_real_attribution_read_seconds{path="PNL"}` e taxa de `outcome="TIMEOUT"` | 500 ms; blocos REAL `TIMEOUT` ≤ 0,5% das respostas 200 de C4 | idem |
| p95 de `pnl_real_attribution_read_seconds{path="GATE"\|"CYCLE"}` | 250 ms | `RealAttributionMeasurementIT`: 10 aquecimentos e 100 medidas; medianas de 3 execuções no Build and Test (NFR1.7, NFR1.8) |
| Leitura de C4 na devnet pelo fundador | 100 pedidos, limite de 1.000 ms, registrado em `docs/perf/` | uma vez, depois da implantação na devnet (NFR1.24) |
| SLO da API no balanceador (já existe, intent `261113-trading-real-bot`) | sem mudança | sem mudança |

## Logs e rastreamento

| Item | Decisão |
|---|---|
| Formato e destino | JSON pelo `LogstashEncoder`, nos grupos de log que já existem do `api-worker`, com a retenção atual. Nenhum grupo novo. |
| Eventos da U2 | `REAL_PNL_RECONCILIATION_MISMATCH` (`ERROR`), `REAL_SLOT_CONFLICT` (`WARN`, assinatura abreviada), `REAL_ATTRIBUTION_READ_FAILED` (`WARN`), `REAL_RISK_DAY_PHOTO_DEFERRED` (`WARN`), `REAL_EXIT_SKIPPED` (`INFO`), `REAL_PNL_MISMATCH_AUDIT_FAILED` (`ERROR`), e o bloco `UNAVAILABLE` da U1 sem mudança (NFR3.12). |
| Nunca no log | Endereço de carteira, assinatura completa, token, `Authorization`, nonce, assinatura de login e diferenças em lamports (NFR3.7, `U2LogCaptureTest`). |
| Consulta na nuvem | Ad hoc no CloudWatch Logs Insights pelo campo `event` e pelo `correlationId`. Nenhuma consulta salva nova. |
| Correlação | O mesmo `correlationId` no bloco `UNAVAILABLE`, no evento de auditoria e no log, lido do MDC na thread do pool (`CorrelationParityIT`, NFR3.13). |
| Rastreamento distribuído | Nenhum: a U2 não chama outro processo (NFR3.14, NFR8.14). |

## Painéis

| Painel | Mudança |
|---|---|
| Os painéis que já existem (`modules/observability/dashboards.tf`) | Nenhuma; nenhum widget novo |
| Painel novo | Nenhum (NFR2.15) |

## Sources

- `infrastructure-design-questions.md`: Q3 e o resumo confirmado [Q3].
- `construction/u2-real-bot-pnl/nfr-design/observability-design.md`: métricas, etiqueta `cause`, logs, correlação, alarme, testes, runbook, SLI.
- `construction/u2-real-bot-pnl/nfr-design/performance-design.md` (portão de carga) e `scalability-design.md` (custo).
- `construction/u2-real-bot-pnl/nfr-requirements/observability-requirements.md`: NFR1.23, NFR1.24, NFR2.11 a NFR2.15, NFR3.12 a NFR3.14, NFR3.16; `performance-requirements.md`: NFR1.6 a NFR1.8.
- `construction/u1-mode-skeleton/infrastructure-design/monitoring-design.md` (formato e coleta).
- Infraestrutura e código lidos: `infra/observability/real-ops-alerts.yml` (13 regras, grupo `real-ops`, severidades), `infra/terraform/modules/observability/real-ops.tf` (`local.real_ops_namespace`, alarmes com `notBreaching`, `alarm_actions`), `app-metrics.tf` (`app_metric_declarations`, `alarm_resource_names`, `metric_alarms`, regras das janelas), `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java` (`hasSize(13)`), `.github/workflows/ci.yml` (lista do `promtool`), `docs/runbooks/real-operations.md` (seções 1 a 7).
- `aidlc/spaces/default/memory/project.md`: correção do CloudWatch Agent (120 s, 240 s e 180 s, US$ 350 por conta); `team.md`: Deployment (on-call).

## Assumptions & Open Questions

1. [assumption] O `observability-design.md` diz que `alarm_resource_names` e `metric_alarms` ficam em `real-ops.tf`; no código eles ficam em `app-metrics.tf`. Este desenho põe cada mudança no arquivo onde a lista existe hoje.
2. A seção 9 do runbook ("Índice da migração 0023") cobre o caso raro de o `db-migrate` falhar no meio da criação do índice; os passos estão em `cicd-pipeline.md` e ela entra na tabela "Onde cada mudança cai" e na rastreabilidade (NFR1.18, NFR2.3).
3. [assumption] O custo de cerca de US$ 0,30 por mês da métrica e de US$ 0,10 do alarme segue os preços que os estágios anteriores usaram; a região ainda não foi escolhida (`team.md`, Deployment).
