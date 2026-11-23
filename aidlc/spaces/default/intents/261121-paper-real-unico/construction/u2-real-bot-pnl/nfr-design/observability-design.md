# Desenho de observabilidade — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Revisado depois de `reviews/review-01.md` (R-01, R-03, R-07) e `reviews/review-02.md` (R-12, R-14).

A U2 acrescenta cinco métricas sem dado pessoal, quatro registros de log, uma regra do Prometheus e um alarme do CloudWatch, o da divergência do P&L REAL com o ledger. Não acrescenta tracing, painel nem SLO operacional [Q5].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Métricas | 3 contadores e 1 temporizador do NFR2.11 a NFR2.13 e NFR1.23, mais o contador de falha da auditoria (NFR3.16): **as cinco**. No máximo 133 séries novas por processo, mais 8 da etiqueta `cause` no contador da U1 `pnl.mode_block.unavailable`: 141 na API. | NFR2.11 a NFR2.13, NFR1.23, NFR3.16, NFR1.6 |
| Logs | 4 eventos em inglês, com `correlationId`, motivo em enum e categoria; nenhum endereço nem assinatura completa. | NFR3.12, NFR3.13 |
| Correlação | O `correlationId` do pedido no bloco, no evento de auditoria e no log. Sem tracing. | NFR3.13, NFR3.14 |
| Alarme | `RealOpsPnlReconciliationMismatch` (Prometheus) e `real_ops_pnl_reconciliation_mismatch` (CloudWatch), janela de 240 s. | NFR2.14 |
| Outros alarmes | Nenhum. | NFR2.15 |
| SLI | p95 de `http.server.requests` por `uri` e de `pnl_real_attribution_read_seconds` por `path`; sem SLO novo. | NFR1.24 |

## Métricas

Convenção da U1: Micrometer, exportado em `/actuator/prometheus` (porta 8081 na API, 8082 no worker); o nome no Micrometer leva o prefixo do módulo dono; o exportador troca o ponto por `_` e acrescenta `_total` aos contadores e `_seconds` aos temporizadores.

| ID | Micrometer | Prometheus | Etiquetas | Dono e ponto de medida | Registro na subida |
|---|---|---|---|---|---|
| NFR2.11 | `pnl.real.reconciliation.mismatches` | `pnl_real_reconciliation_mismatches_total` | `check` ∈ {`REALIZED`, `FEES`, `UNREALIZED`, `COST_CONSERVATION`, `QUANTITY_CONSERVATION`, `EVENT_SUMS`, `UNOWNED_WITHOUT_REASON`, `UNKNOWN_BOT`} | `pnl` (API), em F4: +1 por verificação que falhou, em toda leitura com `MISMATCH`, depois da tentativa de gravar o evento | as 8 séries com 0, para o primeiro incremento aparecer no `increase()` e no delta do agente |
| NFR2.12 | `ledger.real.slot_missing` | `ledger_real_slot_missing_total` | nenhuma | `ledger`, em `streamInNetworkOrder`: +1 por linha entregue com `SLOT_MISSING` (API e worker) | a série com 0 |
| NFR2.13 | `ledger.real.slot_conflicts` | `ledger_real_slot_conflicts_total` | `writer` ∈ {`ORDER_CONFIRMATION`, `CONDITIONAL_FILL`, `WALLET_CONFIRMATION`} | `ledger`, em `recordSlot`: +1 por `CONFLICT_REFUSED`, numa sincronização `afterCommit` da transação do gravador (como o `audit_events_published` faz), para não contar um conflito de uma confirmação desfeita | as 3 séries com 0 |
| NFR3.16 | `pnl.real.reconciliation.audit_failures` | `pnl_real_reconciliation_audit_failures_total` | nenhuma | `pnl` (API), em F4: +1 por gravação do evento que falhou, passou de 200 ms ou ficou sem tempo do bloco | a série com 0 |
| NFR1.23 | `pnl.real.attribution.read` (temporizador com histograma) | `pnl_real_attribution_read_seconds` (`_count`, `_sum`, `_bucket`, `_max`) | `path` ∈ {`GATE`, `CYCLE`, `PNL`}; `outcome` ∈ {`OK`, `TIMEOUT`, `ERROR`, `UNKNOWN_BOT`}; limites fixos 0,1; 0,25; 0,5; 0,8; 1 e 2 s | `pnl.application`: em volta da leitura de atribuição de cada caminho (`performance-design.md` diz o que cada caminho mede); `PNL` na API, `GATE` e `CYCLE` no worker | as 12 combinações |

O registro com 0 na subida usa um componente por módulo (nomes propostos `PnlRealMetrics` e `LedgerRealSlotMetrics`), no padrão de `JdbcAuditEventPublisher`, que registra todas as séries de motivo no construtor. Cada processo registra só as séries do que ele mede: a API registra as 5 métricas; o worker registra o temporizador (`GATE`, `CYCLE`), o `SLOT_MISSING` e o conflito.

### Etiqueta `cause` no contador da U1 (revisão R-03)

O contador `pnl.mode_block.unavailable` (`PnlModeMetrics`, `pnl.modeview`, exportado como `pnl_mode_block_unavailable_total`) passa de `market` para `market` e `cause`:

| Etiqueta | Valores |
|---|---|
| `market` | `PAPER`, `REAL` |
| `cause` | `TIMEOUT`, `REJECTED`, `ERROR`, `RECONCILIATION_MISMATCH`, `TOO_MANY_ORDERS` |

São 2 × 5 = 10 séries, todas registradas com 0 na subida (eram 2): 8 a mais. `TIMEOUT` vale para o prazo esgotado em **qualquer** parte do bloco (fila, abertura da transação, atribuição, fluxo do ledger, rateio, preços, conferência), seja por `57014` ou pela conferência do instante limite no fluxo. É esse contador que o portão de 0,5% do cenário `pnl-by-mode` lê (`performance-design.md`). O bloco PAPER usa só `TIMEOUT`, `REJECTED` e `ERROR`; as outras séries dele ficam em 0. `ModePnlViewTest` e `AbstractPaperRealIT` passam a somar as séries de cada mercado.

**Séries.** 12 combinações do temporizador × 10 séries (7 faixas com a `+Inf`, `_count`, `_sum`, `_max`) = 120, mais 8 da divergência, 1 de falha da auditoria, 1 de `SLOT_MISSING` e 3 de conflito: 133 no máximo por processo. Na API, mais as 8 séries novas de `cause`: 141. Nenhuma etiqueta leva tenant, pessoa, bot, carteira, endereço, assinatura, mint ou `correlationId`.

**Classificação do `outcome`.** `TIMEOUT` para SQLSTATE `57014` (o `statement_timeout` reaplicado em cada busca) e para `ReadDeadlineExceeded`, a conferência do instante limite em cada linha do fluxo; `INCONSISTENT_READ` conta como `ERROR`, com `reason = INCONSISTENT_READ` no log; uma leitura que passou do orçamento nunca é registrada como `OK`; `UNKNOWN_BOT` quando uma ordem tem bot fora do diretório; `ERROR` para qualquer outra exceção; `OK` no resto.

**Testes.** `PnlRealMetricsTest` e `LedgerRealSlotMetricsTest`: depois de uma divergência forçada em `FEES`, `check="FEES"` vale 1 e as outras 7 valem 0; 3 linhas `SLOT_MISSING` lidas dão 3; um conflito na confirmação da ordem dá `writer="ORDER_CONFIRMATION"` = 1; uma leitura com tempo esgotado no `PNL` soma 1 a `_count{path="PNL",outcome="TIMEOUT"}`. `U2MetricsExportIT`: as cinco aparecem em `/actuator/prometheus` com os nomes exportados.

## Logs

Em inglês, pelo SLF4J, com o `correlationId` do MDC (o pool `pnl-mode-block` copia o MDC do pedido, U1).

| Evento | Nível | Campos | NFR |
|---|---|---|---|
| Divergência do P&L REAL (inclui `UNKNOWN_BOT`) | `ERROR` | `event=REAL_PNL_RECONCILIATION_MISMATCH`, `checks`, `window`, `auditWritten` (`true`, `deduplicated` ou `failed`) | NFR3.12 |
| Conflito de slot | `WARN` | `event=REAL_SLOT_CONFLICT`, `writer`, `recordedSlot`, `refusedSlot`, `txSignature` abreviada (`WalletDisplay.abbreviate`) | NFR3.12, NFR3.7 |
| Falha ou tempo esgotado da atribuição | `WARN` | `event=REAL_ATTRIBUTION_READ_FAILED`, `path`, `outcome`, `reason` (`TIMEOUT`, `ERROR`, `UNKNOWN_BOT`, `INCONSISTENT_READ`), `exceptionClass` | NFR3.12 |
| Fotografia do dia adiada (F-4) | `WARN` | `event=REAL_RISK_DAY_PHOTO_DEFERRED`, `reason=COST_UNKNOWN` | NFR3.12 |
| Saída por custo pulada no ciclo | `INFO` | `event=REAL_EXIT_SKIPPED`, `reason=COST_UNKNOWN`, `rule` | NFR3.12 |
| Bloco REAL `UNAVAILABLE` | `WARN` | o registro da U1 (`market`, `code`), sem mudança | NFR3.12 |
| Falha da gravação do evento de divergência | `ERROR` | `event=REAL_PNL_MISMATCH_AUDIT_FAILED`, `category` (`CONNECTION_TIMEOUT`, `DEADLINE`, `REJECTED`, `ERROR`), `exceptionClass` | NFR3.15 |

Nenhum registro leva endereço de carteira, assinatura completa, token, cabeçalho `Authorization`, nonce ou assinatura de login, nem as diferenças em lamports (que ficam só no evento de auditoria). Nenhum `catch` vazio. Teste `U2LogCaptureTest`: cada evento gera 1 registro com o `correlationId` e 0 registros com os valores semeados proibidos.

## Correlação (NFR3.13, NFR3.14)

- O bloco `UNAVAILABLE` leva o `correlationId` do pedido (U1). O evento `REAL_PNL_RECONCILIATION_MISMATCH` e o log usam o mesmo valor, lido do MDC na thread do pool. `CorrelationParityIT` compara os três.
- O `REAL_SLOT_CONFLICT` leva o `correlationId` da confirmação (o do trabalho ou do pedido que confirmou).
- Sem biblioteca de tracing (NFR8.14): a U2 não chama outro processo.

## Alarme da divergência (NFR2.14)

### Prometheus

Regra nova no arquivo que já existe `infra/observability/real-ops-alerts.yml`, grupo `real-ops` (assim a lista de arquivos do `promtool` em `ci.yml`, que é do responsável, não muda):

| Campo | Valor |
|---|---|
| `alert` | `RealOpsPnlReconciliationMismatch` |
| `expr` | `sum(increase(pnl_real_reconciliation_mismatches_total[4m])) > 0` |
| `labels` | `severity: critical` |
| `annotations.summary` | aponta para `docs/runbooks/real-operations.md`, seção "Divergência do P&L REAL" |
| `annotations.threshold` | `"0"` |
| `annotations.window_seconds` | `"240"` |

O comentário da regra cita o NFR2.14 e diz que o contador sobe em toda leitura com divergência (o limite de 1 por hora vale só para o evento de auditoria).

### CloudWatch

- **Lista do agente.** `pnl_real_reconciliation_mismatches_total` entra em `app_metric_declarations` de `infra/terraform/modules/observability/app-metrics.tf`, no grupo sem dimensões (`dimensions = [[]]`): a soma das 8 séries de `check`.
- **Alarme.** `aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch` em `real-ops.tf`:

| Atributo | Valor |
|---|---|
| `alarm_name` | `"${var.name}-real-ops-pnl-reconciliation-mismatch"` |
| `alarm_description` | `"U2 NFR2.14: the REAL P&L of a person does not match the ledger (critical)."` |
| `namespace` | `local.real_ops_namespace` (`Eureka/App`) |
| `metric_name` | `"pnl_real_reconciliation_mismatches_total"` |
| `statistic` | `"Sum"` |
| `period` | `240` |
| `evaluation_periods` | `1` |
| `threshold` | `0` |
| `comparison_operator` | `"GreaterThanThreshold"` |
| `treat_missing_data` | `"notBreaching"` (sem divergência o contador não muda) |
| `alarm_actions` | `[var.alarm_topic_arn]`, o tópico SNS único do piloto |

- **Inventário.** `"real_ops_pnl_reconciliation_mismatch"` entra em `alarm_resource_names` e o recurso entra na lista `metric_alarms` do mesmo arquivo.
- **Janela.** Com `notBreaching`, a regra das janelas pede uma leitura por período: `CloudWatchWindows.cloudWindow(240, false)` = 240. O período de 240 s também contém duas leituras do agente (a cada 120 s), como a correção de `project.md` pede.

### Testes

| Teste | Mudança |
|---|---|
| `promtool check rules infra/observability/real-ops-alerts.yml` (job `backend`) | passa com uma regra a mais |
| `RealOpsAlarmParityTest.everyRuleHasAnAlarmWithTheSameNameThresholdWindowAndJob` | `assertThat(rules).hasSize(13)` passa a `hasSize(14)`; o nome em kebab da regra (`real-ops-pnl-reconciliation-mismatch`) casa com o `alarm_name`; limite 0 e janela 240 iguais |
| `RealOpsAlarmParityTest.eachExpressionUsesItsOwnThresholdWindowAndJob` | a expressão contém `> 0` e `[4m]` |
| `infra/terraform/tests/observability_metrics.tftest.hcl` | o alarme lê uma métrica da lista com um conjunto de dimensões dela; o período (240) atende à regra das leituras; `alarm_resource_names` igual aos blocos do módulo |

### Comportamento e runbook

O alarme fica em `ALARM` enquanto a divergência existir e alguém ler o P&L; sem leitura por 240 s ele volta a `OK` e volta a `ALARM` na próxima leitura. Essa oscilação está aceita no NFR2.14. A seção nova "Divergência do P&L REAL" de `docs/runbooks/real-operations.md` diz: ler o evento `REAL_PNL_RECONCILIATION_MISMATCH` pelo `correlationId` ou pela `fingerprint`; conferir as verificações e as diferenças em lamports; abrir o defeito com teste de regressão; o saque e as ordens continuam, porque a divergência afeta só a leitura do P&L. Escalonamento: o fundador é o único on-call (`team.md`).

### Sem outros alarmes (NFR2.15)

`ledger_real_slot_missing_total`, `ledger_real_slot_conflicts_total`, `pnl_real_attribution_read_seconds` e `pnl_real_reconciliation_audit_failures_total` ficam só no Prometheus e no relatório de carga. O histograma da atribuição não vai para o CloudWatch (o agente descarta histogramas). A falha da atribuição na barreira já aparece em `real_risk_errors_total{dependency="attribution"}`, coberto pelo alarme `real_risk_errors` que existe. Nenhum painel novo.

## SLI (NFR1.24)

- p95 de `http_server_requests_seconds` por `uri` (`/api/v1/pnl/by-mode`, `/api/v1/real/pnl`), que já tem histograma.
- p95 de `pnl_real_attribution_read_seconds` por `path`, e a taxa de `outcome="TIMEOUT"` no `PNL`, que é o portão efetivo no cenário de carga (`performance-design.md`).
- Nenhum SLO operacional novo. A repetição na devnet é uma leitura de C4 pelo fundador depois da implantação, com 100 pedidos e o limite de 1.000 ms, registrada em `docs/perf/`.

## Sources

- `nfr-design-questions.md`: Q5 e o resumo confirmado [Q5].
- `reviews/review-01.md` (R-01, R-03, R-07).
- Código lido também: `pnl/modeview/PnlModeMetrics.java` (contador `pnl.mode_block.unavailable` só com `market`).
- `construction/u2-real-bot-pnl/nfr-requirements/observability-requirements.md`: convenção de nomes, NFR2.11 a NFR2.15, NFR1.23, NFR1.24, NFR3.12 a NFR3.14, NFR3.16; `security-requirements.md`: NFR3.7, NFR3.15; `reviews/review-02.md`: R-04, R-08, R-10.
- `construction/u2-real-bot-pnl/functional-design/functional-spec.md`: F4, F6.2, F7; `rules.md`: BR1.4, BR8.7.
- `inception/contract-design/contract-summary.md`: C12, C13.
- `construction/u1-mode-skeleton/nfr-design/observability-design.md` (convenção e MDC do pool).
- Código lido: `infra/observability/real-ops-alerts.yml`, `infra/terraform/modules/observability/real-ops.tf`, `app-metrics.tf` (`app_metric_declarations`, `alarm_resource_names`, `metric_alarms`), `infra/terraform/tests/observability_metrics.tftest.hcl`, `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java`, `apps/api/src/test/java/com/memetrade/infra/CloudWatchWindows.java`, `audit/infra/JdbcAuditEventPublisher.java` (registro na subida, `afterCommit`), `riskcontrol/api/RealGateUnavailable.java`.
- `aidlc/spaces/default/memory/project.md`: correção do CloudWatch Agent (120 s, janelas de 240 s e 180 s); `team.md`: Deployment (on-call), Way of Working (`.github/workflows/` é do responsável).

## Assumptions & Open Questions

1. [assumption] O incremento do contador de conflito depois do commit é escolha deste estágio, para não contar um conflito de uma confirmação desfeita; o evento de auditoria do conflito segue a mesma transação (outbox), então os dois ficam coerentes.
2. [assumption] Os nomes dos eventos de log (`REAL_ATTRIBUTION_READ_FAILED`, `REAL_PNL_MISMATCH_AUDIT_FAILED`) e dos componentes de métrica são propostas; a Code Generation pode trocá-los.
3. O texto "as quatro" de `observability-requirements.md` passa a "as cinco" na Code Generation (lista em `logical-components.md`).
