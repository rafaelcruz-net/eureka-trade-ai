# Desenho de observabilidade — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` (R-01, R-02, R-03, R-06) e da decisão do fundador F3.

A U4 mede quatro coisas no worker, sem dado pessoal, e acrescenta dois alarmes, cada um com o gêmeo no CloudWatch:

- um alarme por **razão** para a falha sistêmica da leitura da posição própria (decisão do fundador F2, que substitui a BR2.7);
- um alarme por **bot preso**, para o bot ou a carteira com falha persistente que fica abaixo dos 5% da razão (decisão do fundador F3).

As regras têm teste próprio com o `promtool` por Testcontainers (decisão F1). Este arquivo cobre NFR1.13, NFR2.10 a NFR2.13, NFR2.16, NFR2.17, NFR3.9 e NFR3.10, com a resposta confirmada Q4 [Q4], e a linha nova da F3 (NFR2.18, alinhamento A17).

## Decisão do fundador F3 (2026-11-24)

Entra um gauge sem etiqueta, `real_own_holdings_stuck_bots`: o número de bots REAL cujas 3 ou mais últimas leituras da posição própria, seguidas, terminaram indisponíveis por `BUDGET_EXCEEDED` ou `READ_FAILED` (`STALE_SNAPSHOT` não conta). Ele tem um alarme no Prometheus (gauge > 0 por 10 min) e o gêmeo no CloudWatch, nas regras de janela do projeto e com a anotação `cloud_periods`. O custo sobe cerca de US$ 0,40 por conta e por mês. Motivo (revisão R-02): com 63 bots, um bot preso é 1,6% das leituras e uma carteira inteira (3 bots) é 4,8%, os dois abaixo do limite de 5% da razão; sem o gauge, esse bot deixaria de vender pelo ciclo sem alerta nenhum. A F3 não muda a F2: a razão continua para a falha sistêmica.

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Contadores | 3 contadores no worker, 7 séries registradas com zero na subida, prefixo `real_`, em `bots.infra.OwnQuantityMetrics`. | NFR2.10, NFR2.11, NFR2.16 |
| Gauge de bots presos | `real_own_holdings_stuck_bots`, sem etiqueta, calculado de um mapa em memória por bot (`OwnHoldingsStreaks`), limitado e com despejo; registrado com 0. | NFR2.18 (F3) |
| Alarme da razão | `RealOpsOwnHoldingsUnavailable`: `(BUDGET_EXCEEDED + READ_FAILED) / leituras` em `[10m]`, `> 0.05`, `for: 15m`, `severity: high`. | NFR2.12 |
| Gêmeo da razão | `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, `period = 600`, 2 de 2, `notBreaching`. Sem leituras, o valor é 0 e o alarme não dispara. | NFR2.12 |
| Alarme do bot preso | `RealOpsOwnHoldingsStuckBots`: `max(min_over_time(real_own_holdings_stuck_bots[10m])) > 0`, `severity: high`; gêmeo `Minimum`, `period = 300`, 2 de 2, `notBreaching`. | NFR2.18 (F3) |
| Paridade | `RealOpsAlarmParityTest`: `hasSize(16)` e a anotação `cloud_periods` (1 quando ausente). | NFR2.12, NFR2.18 |
| Teste das regras | T20 em `RealOpsOwnHoldingsAlertRuleIT`, `promtool test rules` com o digest lido do `ci.yml`; 10 casos com instantes exatos e `exp_annotations`. | NFR7.6 |
| `STALE_SNAPSHOT` | No contador do corte, sem alarme; revisão semanal da razão pelo runbook. | NFR2.13, NFR2.17 |
| Logs | Inglês, `correlationId` do ciclo, uuid do bot, enum. | NFR3.9 |
| Rastreamento | Nenhum novo; a correlação é o `cycleId` no MDC. | NFR3.10 |

## Contadores (NFR2.10, NFR2.11, NFR2.16)

`bots.infra.OwnQuantityMetrics` (`@Component`, `@ProcessRole(WORKER)`) registra tudo no construtor, como o `RealOpsMetrics`, para que as séries existam com 0 desde a subida. A API não tem o bean, então expõe 0 séries novas.

```java
OwnQuantityMetrics(MeterRegistry meters, OwnHoldingsStreaks streaks) {
    reads = Counter.builder("real_own_holdings_reads").register(meters);
    for (UnavailableReason r : UnavailableReason.values())          // BUDGET_EXCEEDED, READ_FAILED, BOT_NOT_OWNED
        unavailable.put(r, Counter.builder("real_own_holdings_unavailable").tag("reason", r.name()).register(meters));
    for (SellCapOutcome o : SellCapOutcome.values())                 // CAPPED, NO_OWN_QUANTITY, STALE_SNAPSHOT
        capped.put(o, Counter.builder("real_cycle_sell_capped").tag("outcome", o.name()).register(meters));
    Gauge.builder("real_own_holdings_stuck_bots", streaks, OwnHoldingsStreaks::stuckBots).register(meters);
}
```

| Exportado | Etiqueta | Onde sobe |
|---|---|---|
| `real_own_holdings_reads_total` | nenhuma | no `CycleRunner`, logo antes de chamar o RealBotHoldings (F1 passo 2), qualquer que seja o resultado. Ciclos que não leem (conferência prévia, flag desligada, PAPER, corrida `NotRead`) não contam. |
| `real_own_holdings_unavailable_total` | `reason` (3) | quando a chamada que subiu o denominador devolve `Unavailable(reason)`. Por isso a razão nunca passa de 1. |
| `real_cycle_sell_capped_total` | `outcome` (3) | `CAPPED` no corte com 0 < venda < pedido; `NO_OWN_QUANTITY` no corte com venda 0 e por mint tirado dos tokens com disponível 0; `STALE_SNAPSHOT` no corte com M1 ≠ M0. Só no Prometheus. |
| `real_own_holdings_stuck_bots` | nenhuma | gauge lido a cada coleta; ver abaixo. |

`UnavailableReason` é o enum de `pnl.api` (C15). `SellCapOutcome` fica em `bots.domain`, ao lado do `OwnSellCap`.

## Gauge de bots presos (F3, NFR2.18)

### Onde a sequência de cada bot fica

`bots.worker.OwnHoldingsStreaks` (nome proposto), um bean do worker, guarda **só em memória** um `ConcurrentHashMap<UUID, Streak>` chaveado pelo id do bot, com `Streak(int failures, Instant lastSeen)`. O `OwnHoldingsStep` o atualiza logo depois de cada chamada do RealBotHoldings, com o relógio injetado:

| Resultado da leitura | Efeito na entrada do bot |
|---|---|
| `Available` | remove a entrada (zera a sequência). Um `STALE_SNAPSHOT` depois disso não muda nada: a leitura foi boa. |
| `Unavailable(BUDGET_EXCEEDED)` ou `Unavailable(READ_FAILED)` | `failures + 1`, `lastSeen = now` |
| `Unavailable(BOT_NOT_OWNED)` | remove a entrada: o bot não é da pessoa, e o caso já sai como `ERROR` no log |
| `NotRead` (corrida da BR2.5) ou ciclo sem leitura | nada muda |

`stuckBots()` conta as entradas com `failures ≥ 3` e `lastSeen` nos últimos 20 minutos.

### Limite e despejo

- **Despejo por tempo.** Uma entrada com `lastSeen` mais velho que 20 minutos (4 slots, acima do maior intervalo normal entre ciclos de um bot, que é 5 min mais o atraso) sai do mapa. A limpeza roda a cada atualização e a cada leitura do gauge. Um bot que parou, foi pausado, removido, virou PAPER ou teve a flag desligada deixa de ser lido e sai do mapa em até 20 minutos, e o gauge volta a 0 sem intervenção.
- **Teto.** No máximo 10.000 entradas, muito acima dos 63 bots do volume de referência. Acima do teto, a entrada mais antiga por `lastSeen` sai e um `WARN` `event=REAL_OWN_HOLDINGS_STREAKS_FULL` é registrado; o mapa nunca cresce sem limite.
- Nada é gravado no banco: o mapa é um sinal operacional, não um dado de negócio (ADR-004, nada guardado entre leituras para o dinheiro).

### Comportamento numa reinicialização do worker

O mapa começa vazio a cada subida, e o gauge sobe registrado com 0. Um bot que estava preso volta a aparecer depois de mais 3 leituras indisponíveis, ou seja, cerca de 15 minutos de ciclos e mais os 10 minutos do alarme. Numa troca rolling, a tarefa nova começa do zero e a antiga para de ser coletada. Isso é aceito: durante esse atraso, a razão e o log `WARN` por bot continuam valendo, e o bot preso não vende a mais, só deixa de vender. Dois workers por engano dariam dois gauges parciais; a regra usa `max` e o CloudWatch usa `Minimum` por período, e a trava do ShedLock deixa um só despachante.

### Testes

- `OwnHoldingsStreaksTest` (unidade, relógio falso): 2 falhas e depois uma leitura boa → 0; 3 falhas seguidas → 1; 3 falhas de `STALE_SNAPSHOT` (leituras boas) → 0; `BOT_NOT_OWNED` → entrada removida; 21 minutos sem leitura → despejada e 0; 10.001 bots distintos → 10.000 entradas e 1 `WARN`.
- `OwnQuantityMetricsIT` confere o gauge com 0 logo depois da subida.

## Testes dos contadores

- `OwnQuantityMetricsTest` (unidade, `SimpleMeterRegistry`): leitura com prazo esgotado → `reads` 1, `unavailable{BUDGET_EXCEEDED}` 1, as outras 2 séries 0; leitura `Available` → só `reads`; corte de 150 para 100 unidades base → `capped{CAPPED}` 1; M1 ≠ M0 → `capped{STALE_SNAPSHOT}` 1 e `unavailable` inalterado.
- `OwnQuantityMetricsIT` (integração, perfil `worker`): as 8 séries (7 contadores e o gauge) aparecem em `/actuator/prometheus` do worker (porta de gestão 8082) com os nomes exportados e valor 0 logo depois da subida; no perfil da API, 0 delas.
- T7: com a flag desligada, as quatro métricas continuam em 0 depois de ciclos REAL.

## Logs (NFR3.9)

| Evento | Nível | Campos |
|---|---|---|
| `event=REAL_OWN_HOLDINGS_UNAVAILABLE` (`BUDGET_EXCEEDED`, `READ_FAILED`) | `WARN` | `botId`, `reason`, `exceptionClass`, `elapsedMs`, `streak` |
| `event=REAL_OWN_HOLDINGS_UNAVAILABLE` (`BOT_NOT_OWNED`) | `ERROR` | `botId`, `reason` |
| `event=REAL_OWN_HOLDINGS_UNAVAILABLE` com `precheck=NOT_READ` | `WARN` | `botId`, `reason=READ_FAILED` |
| `event=REAL_OWN_SELL_STALE` | `INFO` | `botId`, `markBefore`, `markUnderLock` |
| `event=REAL_OWN_SELL_NO_QUANTITY` | `INFO` | `botId`, `mint`, `requestedBaseUnits` |
| `event=REAL_OWN_SELL_CAPPED` | `INFO` | `botId`, `mint`, `requestedBaseUnits`, `sellBaseUnits` |
| `event=REAL_OWN_HOLDINGS_STREAKS_FULL` | `WARN` | `entries` |

- Todos levam o `correlationId` do MDC, que é o `cycleId`. O campo `streak` do `WARN` deixa o fundador achar o bot preso que o gauge conta, sem pôr o id do bot numa etiqueta.
- Nunca SQL, endereço de carteira, assinatura, token, `Authorization` ou nonce (`OwnQuantityLogRedactionTest`, `security-design.md`). 0 `catch` vazios.

## Contexto da decisão

Nas decisões REAL com a flag ligada, `DecisionJson` grava no `context` um objeto `ownQuantity`:

- `read`: `AVAILABLE`, `UNAVAILABLE` ou `NOT_READ`, com `reason` quando houver;
- `mints`: uma nota por mint da carteira (`OWN_QUANTITY_AVAILABLE`, `NO_OWN_QUANTITY` ou `OWN_QUANTITY_UNAVAILABLE`);
- `cap`: o veredito do corte (`STALE_SNAPSHOT`, `NO_OWN_QUANTITY`, `CAPPED` com pedido e venda, ou `NONE`).

O PAPER e a flag desligada gravam o JSON de hoje, sem a chave (T7, T14).

**A chave é interna e não muda contrato nenhum (revisão R-06).** Conferido no código: as leituras de decisão (`JdbcDecisionRepository.SELECT`, usado por `page` e `last`, que alimentam as rotas de bots e a administração) selecionam `factors`, `inputs` e `summary`, mas **não** a coluna `context`; o `openapi-guest.yaml` e o `openapi-admin.yaml` não têm campo de contexto da decisão. O `context` só é lido pelo `DecisionJson.read`, interno ao módulo `decision`, e pela consulta da virada no runbook. Por isso o `GuestOpenApiContractTest` não muda. Se uma rota passar a expor o contexto, a chave `ownQuantity` entra no contrato e no teste de contrato nessa mudança.

## Alarme da razão (NFR2.12)

### Regra do Prometheus

Em `infra/observability/real-ops-alerts.yml`, grupo `real-ops`, depois de `RealOpsPnlReconciliationMismatch`:

```yaml
      - alert: RealOpsOwnHoldingsUnavailable
        expr: >
          sum(increase(real_own_holdings_unavailable_total{reason=~"BUDGET_EXCEEDED|READ_FAILED"}[10m]))
          / sum(increase(real_own_holdings_reads_total[10m])) > 0.05
        for: 15m
        labels: { severity: high }
        annotations:
          summary: >-
            The own holdings of the REAL bots are unavailable; see docs/runbooks/real-operations.md, section 10.
          threshold: "0.05"
          window_seconds: "600"
          for_seconds: "900"
          cloud_periods: "2"
```

- Janelas de 10 min avaliadas a cada 1 min se sobrepõem em 9 min; a persistência vem do `for: 15m`, que não depende da versão do Prometheus.
- Com a flag desligada, as duas somas dão 0, a divisão dá `NaN`, e `NaN > 0.05` é falso.
- `BOT_NOT_OWNED` e `STALE_SNAPSHOT` ficam fora do numerador. O cálculo do limite está em `observability-requirements.md`.

### Gêmeo no CloudWatch (revisão R-01)

No CloudWatch, este alarme é o único aviso da falha sistêmica, porque o Prometheus é local. Em metric math, uma entrada sem ponto num período deixa a expressão sem ponto. Por isso a expressão preenche os numeradores e protege o denominador:

```hcl
resource "aws_cloudwatch_metric_alarm" "real_ops_own_holdings_unavailable" {
  alarm_name          = "${var.name}-real-ops-own-holdings-unavailable"
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 0.05
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  metric_query {
    id          = "e1"
    expression  = "IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)"
    return_data = true
  }
  # m1, m2: real_own_holdings_unavailable_total { reason = BUDGET_EXCEEDED | READ_FAILED }; m3: reads, no dimensions
}
```

- `m1`, `m2` e `m3` no namespace `Eureka/App`, estatística `Sum`, `period = 600`; ação no tópico SNS único (`alarm_actions`).
- Um período sem ponto de `BUDGET_EXCEEDED` (ou de `READ_FAILED`) conta como 0 nesse numerador, e a falha do outro motivo continua visível.
- Sem leituras no período (`m3` ausente ou 0), o valor é 0 e o alarme não dispara: flag desligada ou nenhum bot REAL ativo nunca alarmam. Worker parado é coberto pelo alarme `metrics_absent` que já existe.
- `app-metrics.tf`: grupo novo `{ dimensions = [["reason"]], metrics = ["real_own_holdings_unavailable_total"] }` e `real_own_holdings_reads_total` no grupo `[[]]`; `real_ops_own_holdings_unavailable` entra em `alarm_resource_names` e em `metric_alarms`. O `alarm_metric_reads` já lê as `metric_query` de alarmes de expressão.
- 600 s ≥ 180 s (uma leitura do agente a cada 120 s, cerca de 5 por período). Como o alarme não dispara com dado ausente, não precisa de múltiplo de 240 s (`project.md`, correção de 2026-11-20).
- **Teste offline do Terraform** (`observability_metrics.tftest.hcl`): confere a expressão exata acima no `metric_query` `e1`.
- **Observability Setup:** conferência real, com o worker rodando com a flag ligada na devnet, de que `real_own_holdings_reads_total` tem ponto em todo período de 600 s de uma janela de 1 hora, e de que um contador parado publica delta 0.

## Alarme do bot preso (F3, NFR2.18)

### Regra do Prometheus

No mesmo grupo, depois da regra da razão:

```yaml
      - alert: RealOpsOwnHoldingsStuckBots
        expr: max(min_over_time(real_own_holdings_stuck_bots[10m])) > 0
        labels: { severity: high }
        annotations:
          summary: >-
            A REAL bot has 3 or more own-holdings reads unavailable in a row; see docs/runbooks/real-operations.md,
            section 10.
          threshold: "0"
          window_seconds: "600"
          cloud_periods: "1"
```

- "Gauge > 0 por 10 min" é o `min_over_time` sobre 10 minutos maior que 0: todas as amostras da janela acima de 0. A persistência vem da janela, sem `for`, então o resultado não depende de estado de avaliação.
- Um bot preso entra no gauge depois da 3ª leitura indisponível seguida (cerca de 10 a 15 min de ciclos) e o alarme dispara 10 min depois.
- A janela `[10m]` e o `> 0` no fim cumprem a conferência genérica do `RealOpsAlarmParityTest`.

### Gêmeo no CloudWatch

```hcl
resource "aws_cloudwatch_metric_alarm" "real_ops_own_holdings_stuck_bots" {
  alarm_name          = "${var.name}-real-ops-own-holdings-stuck-bots"
  namespace           = local.real_ops_namespace
  metric_name         = "real_own_holdings_stuck_bots"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}
```

- `Minimum` por período: o período só passa do limite se todas as leituras do agente nele forem > 0. Dois períodos de 300 s seguidos = 600 s, a janela da regra.
- Regras de janela do projeto: o alarme não dispara com dado ausente, então cada período precisa de pelo menos uma leitura do agente (300 s ≥ 180 s; 2 a 3 leituras por período) e não precisa de múltiplo de 240 s. `cloudWindow(600, false) × cloud_periods (1) = 600 = period × evaluation_periods`.
- `app-metrics.tf`: `real_own_holdings_stuck_bots` no grupo `[[]]`; `real_ops_own_holdings_stuck_bots` em `alarm_resource_names` e em `metric_alarms`.
- Custo: 1 métrica personalizada (cerca de US$ 0,30) e 1 alarme padrão (cerca de US$ 0,10), ou seja, cerca de US$ 0,40 a mais por conta e por mês.

## Paridade (`RealOpsAlarmParityTest`)

- `assertThat(rules).hasSize(14)` passa a `hasSize(16)`.
- O registro `Rule` ganha `cloudPeriods` (anotação `cloud_periods`, 1 quando ausente). A janela confere como `alarm.windowSeconds() == cloudWindow(rule.windowSeconds(), twoReads) × rule.cloudPeriods()`: razão 600 × 2 = 1.200; bot preso 600 × 1 = 600. As 14 regras de hoje não têm a anotação e continuam com o mesmo resultado.
- No alarme de expressão, o `PERIOD` do teste acha `period = 600` no primeiro `metric_query` com `metric`; o `THRESHOLD` acha `0.05`. No do bot preso, `period = 300`, `threshold = 0`. O `JOB_DIMENSION` não acha `job` nos dois.
- Teste `theOwnHoldingsAlertIsARatioWithItsTwinInCloudWatch`: na regra, `for: 15m` igual a `for_seconds`, o seletor `reason=~"BUDGET_EXCEEDED|READ_FAILED"` e o denominador `real_own_holdings_reads_total`; no alarme, o texto exato `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, as três `metric_query` de métrica com `period = 600`, `evaluation_periods = 2`, `datapoints_to_alarm = 2` e `notBreaching`; em `app-metrics.tf`, os nomes de métrica e do recurso.
- Teste `theStuckBotsAlertHasItsTwinInCloudWatch`: na regra, `min_over_time(real_own_holdings_stuck_bots[10m])` e `> 0`; no alarme, `metric_name = "real_own_holdings_stuck_bots"`, `statistic = "Minimum"`, `period = 300`, `evaluation_periods = 2`, `notBreaching`, sem `dimensions`; em `app-metrics.tf`, a métrica e o recurso.
- O teste "só os jobs parados tratam dado ausente como `breaching`" continua com o mesmo resultado.

## T20: teste das regras com o `promtool` (NFR7.6)

**Classe:** `apps/api/src/test/java/com/memetrade/botwallet/RealOpsOwnHoldingsAlertRuleIT.java` (Failsafe, dentro do `mvnw verify`).

1. **Imagem.** Lê `../../.github/workflows/ci.yml` com SnakeYAML, pega `jobs.backend.steps[name == "Alert rules (promtool)"].run` e extrai `prom/prometheus@sha256:[0-9a-f]{64}`. Exige exatamente 1 digest distinto; senão falha com o motivo. O `ci.yml` não muda e continua o único lugar do digest (F1).
2. **Contêiner.** `GenericContainer` dessa imagem com `infra/observability` copiado para `/rules`, entrada `promtool`, comando `test rules /rules/tests/real-ops-own-holdings-unavailable.test.yml` e `OneShotStartupCheckStrategy` com prazo de 2 min. Código de saída diferente de 0 falha o teste, com a saída do `promtool` na mensagem.
3. **Teste sem Docker.** `CiPromtoolDigestTest` (unidade) roda o passo 1 sozinho.

**Arquivo:** `infra/observability/tests/real-ops-own-holdings-unavailable.test.yml`, com `rule_files: [../real-ops-alerts.yml]` e `evaluation_interval: 1m`. Todo bloco usa `interval: 15s`. Todo caso que espera alerta traz `exp_labels: { severity: high }` e **`exp_annotations` com as anotações completas da regra**, com o texto exato do `summary` depois do dobramento do YAML (`>-` junta as linhas com um espaço) e os valores de `threshold`, `window_seconds`, `for_seconds` (só na razão) e `cloud_periods` (revisão R-03). Um teste de unidade (`AlertRuleTestAnnotationsTest`) lê os dois arquivos e confere que as `exp_annotations` do arquivo de teste são iguais às anotações da regra, para que uma mudança de texto na regra falhe no Java antes do `promtool`.

Forma de um caso (caso 1):

```yaml
  - interval: 15s
    input_series:
      - series: 'real_own_holdings_reads_total'
        values: '0+0x3 3+0x19 6+0x19 9+0x19 12+0x19 15+0x19 18+0x19'
      - series: 'real_own_holdings_unavailable_total{reason="READ_FAILED"}'
        values: '0+0x3 3+0x19 6+0x19 9+0x19 12+0x19 15+0x19 18+0x19'
    alert_rule_test:
      - { eval_time: 15m, alertname: RealOpsOwnHoldingsUnavailable, exp_alerts: [] }
      - eval_time: 16m
        alertname: RealOpsOwnHoldingsUnavailable
        exp_alerts: [{ exp_labels: { severity: high }, exp_annotations: { <as 5 anotações da regra> } }]
```

`a+0xN` gera N + 1 amostras de 15 s. Nos casos de 3 bots, cada incremento de 3 marca um ciclo dos 3 bots.

| # | Caso | Séries (amostras de 15 s) | Avaliações e resultado esperado |
|---|---|---|---|
| 1 | Falha persistente (razão) | leituras e `READ_FAILED` iguais: `0+0x3 3+0x19 6+0x19 9+0x19 12+0x19 15+0x19 18+0x19` (incrementos em 1, 6, 11, 16, 21 e 26 min) | razão verdadeira desde 1 min. `15m`: nenhum; `16m`: dispara; `25m`: disparado |
| 2 | Falha isolada | leituras `0+0x3 1+0x19 2+0x19 3+0x19 4+0x19 5+0x19 6+0x19`; `READ_FAILED` `0+0x3 1+0x119` (uma falha em 1 min) | verdadeira de 1 a 10 min, falsa a partir de 11 min. `10m`, `16m`, `30m`: nenhum |
| 3 | Jitter | leituras e `READ_FAILED` iguais: `0+0x3 3+0x11 6+0x27 9+0x15 12+0x23 15+0x19 18+0x27 21+0x3` (incrementos em 1, 4, 11, 15, 21, 26 e 33 min; intervalos de 3, 7, 4, 6, 5 e 7 min) | toda janela de 10 min tem um incremento. `15m`: nenhum; `16m`, `25m`, `33m`: disparado |
| 4 | Fronteira | leituras `0+0x3 3+0x19 6+0x19 9+0x19 12+0x19 15+0x19 18+0x59` e `READ_FAILED` `0+0x3 3+0x19 6+0x19 9+0x19 12+0x19 15+0x79` (último incremento de falha entre as amostras de 20m45s e 21m00s; leituras boas depois) | `16m` e `30m`: disparado; `31m`: nenhum. Em `31m`, `t − 10 min = 21m00s` cai exatamente na amostra do incremento: o 2.x a inclui e o 3.x a exclui, mas a amostra de 20m45s está fora nos dois, então o resultado é o mesmo |
| 5 | Ruído da cauda | leituras `0+3.15x720` (12,6 por minuto por 3 h); `BUDGET_EXCEEDED` `0+0x315 1+0x315 2+0x88` (1 a cada cerca de 1.000 leituras) | `30m`, `90m`, `180m`: nenhum |
| 6 | Rajada normal | leituras `0+3.15x80`; `READ_FAILED` `0+0x39 3+0x40` (3 falhas em 10 min, 2,4% de 126) | `11m`, `20m`: nenhum |
| 7 | Fora do numerador | leituras `0+3x120`; `real_own_holdings_unavailable_total{reason="BOT_NOT_OWNED"}` `0+3x120`; `real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}` `0+3x120` | `16m`, `30m`: nenhum (o numerador é um vetor vazio) |
| 8 | Flag desligada | leituras `0+0x120`; `READ_FAILED` `0+0x120` | `16m`, `30m`: nenhum (`NaN`) |
| 9 | Um bot preso entre 63 (F3) | `real_own_holdings_stuck_bots` `0+0x59 1+0x80` (1 desde 15 min); leituras `0+3.15x140`; `READ_FAILED` `0+0.05x140` (1 bot de 63, 1,6%) | `RealOpsOwnHoldingsStuckBots`: `24m` nenhum (a janela ainda tem a amostra 0 de 14m45s); `25m` e `35m` disparado. `RealOpsOwnHoldingsUnavailable`: `25m` e `35m` nenhum |
| 10 | Falha passageira (F3) | `real_own_holdings_stuck_bots` `0+0x59 1+0x19 0+0x40` (1 só de 15m00s a 19m45s) | `RealOpsOwnHoldingsStuckBots`: `20m`, `25m`, `30m` nenhum |

Nos casos 9 e 10, `t − 10 min` em `25m` cai exatamente em 15m00s, a primeira amostra 1: incluída pelo 2.x, excluída pelo 3.x; nas duas versões as amostras restantes da janela são todas 1 no caso 9, então o resultado é o mesmo.

## `STALE_SNAPSHOT` sem alarme (NFR2.13, NFR2.17)

- Além dos dois alarmes acima, nenhum outro alarme e nenhum painel novo. O NFR2.13 passa a ter a exceção do alarme da F3 (alinhamento A17).
- **Build and Test:** o `RealBotHoldingsMeasurementIT`, sem ciclos concorrentes, registra `capped{STALE_SNAPSHOT} / reads`, que deve ser 0; acima de 0, a etapa registra e leva ao fundador.
- **Operação (runbook, seção 13):** toda semana, e depois de cada implantação que liga a flag, o fundador roda no Prometheus:

```text
sum(increase(real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}[24h]))
  / sum(increase(real_own_holdings_reads_total[24h]))
```

  Acima de 1%, ele procura no log `event=REAL_OWN_SELL_STALE` os bots com 3 ou mais seguidos e abre defeito para cada um.

## SLI (NFR1.13)

| SLI | Fonte | Limiar |
|---|---|---|
| Saúde da leitura | a razão `(BUDGET_EXCEEDED + READ_FAILED) / leituras` | acima de 5% sustentado por 15 min |
| Bots presos | `real_own_holdings_stuck_bots` | acima de 0 por 10 min |
| Tempo da leitura | o p99,9 do `RealBotHoldingsMeasurementIT` | ≤ 500 ms [fundador F6, 2026-11-25; antes 250 ms], ou seja, ≤ 0,1% de `BUDGET_EXCEEDED` |

Nenhum SLO operacional novo e nenhum histograma novo.

### Premissa dos alarmes com 500 ms (loop-back 1, decisão F6)

O fundador decidiu (F6, 2026-11-25, depois do Build and Test) que o orçamento da leitura passa de 250 ms para 500 ms. Os limites dos dois alarmes não mudam. A conta foi refeita com 500 ms:

- **Base.** Com a meta p99,9 ≤ 500 ms (NFR1.1) e o corte em execução também em 500 ms (NFR1.7), a taxa de `BUDGET_EXCEEDED` em operação normal fica em no máximo 0,1% das leituras. É a mesma taxa de antes: o que mudou é o tempo que define a cauda, não a cauda permitida.
- **Razão (NFR2.12, `RealOpsOwnHoldingsUnavailable`).** O limite de 5% fica 50 vezes acima da taxa normal de 0,1%, a mesma folga de antes. Uma razão acima de 5% por 15 min continua sendo falha persistente (banco lento, pool esgotado, histórico que passou do prazo), não a cauda normal.
- **Bot preso (NFR2.18, `RealOpsOwnHoldingsStuckBots`).** Com leituras independentes a 0,1%, 3 indisponíveis seguidas acontecem com probabilidade de cerca de 10⁻⁹ por sequência. Com 1 leitura a cada 5 min por bot, um alarme falso é desprezível também com 500 ms. O limite (3 seguidas, gauge > 0 por 10 min) não muda.
- **Ciclo.** A leitura continua fora da transação e da trava do ciclo. Um prazo de 500 ms só alonga a fase prévia do ciclo do bot REAL com a flag ligada, dentro do limite de 2 s do ciclo, e não segura trava.

## Correlação (NFR3.10)

Nenhuma biblioteca de tracing nova. O `cycleId` é o `correlationId` do MDC durante todo o `CycleRunner.run`, inclusive a fase prévia e a leitura do RealBotHoldings. O mesmo `cycleId` está em `bot_cycle` e na decisão gravada.

## Sources

- `nfr-design-questions.md`: Q4 e o resumo confirmado [Q4].
- `reviews/review-01.md` (R-01, R-02, R-03, R-06) e as decisões do fundador F1, F2 e F3 de 2026-11-24.
- `construction/u4-own-quantity-cycle/nfr-requirements/observability-requirements.md` (NFR1.13, NFR2.10 a NFR2.13, NFR2.16, NFR2.17, NFR3.9, NFR3.10, cálculo do limite), `reliability-requirements.md` (NFR7.6), `scalability-requirements.md` (NFR8.2), `tech-stack-decisions.md` (NFR7.7, NFR8.8, NFR8.9, NFR8.14 a NFR8.17).
- `construction/u4-own-quantity-cycle/functional-design/rules.md` (BR2.7, substituída pela F2; BR1.5, BR3.1) e `functional-spec.md` (F2 passo 7, T20).
- `construction/u2-real-bot-pnl/nfr-design/observability-design.md` (par regra e alarme, registro com zero).
- `aidlc/spaces/default/memory/project.md` (CloudWatch Agent a cada 120 s, janelas de 240 s e 180 s; logs sem segredo) e `team.md` (fundador único on-call).
- Código lido: `infra/observability/real-ops-alerts.yml`, `infra/terraform/modules/observability/real-ops.tf`, `app-metrics.tf`, `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java`, `apps/api/src/test/java/com/memetrade/infra/CloudWatchWindows.java`, `botwallet/infra/RealOpsMetrics.java`, `.github/workflows/ci.yml`, `bots/worker/CycleRunner.java`, `decision/infra/DecisionJson.java`, `decision/infra/JdbcDecisionRepository.java` (`SELECT` sem `context`), `apps/api/openapi/openapi-guest.yaml` e `openapi-admin.yaml` (sem campo de contexto).

## Assumptions & Open Questions

1. A suposição 3 de `observability-requirements.md` (o teste offline do Terraform precisaria ler `metric_query`) não se confirma: o `alarm_metric_reads` já lê as `metric_query`.
2. [assumption] O `promtool` da imagem fixada no `ci.yml` aceita o formato `rule_files`/`tests`/`alert_rule_test` com `exp_annotations`, que existe desde o Prometheus 2.5. Se a comparação aceitar a omissão das anotações, elas ficam assim mesmo, porque o teste fica mais estrito.
3. [assumption] Os 20 minutos de despejo e o teto de 10.000 entradas do mapa de sequências são escolha deste estágio; o despejo só precisa ser maior que o maior intervalo normal entre dois ciclos do mesmo bot.
4. A F3 cria um requisito novo (NFR2.18) e muda o NFR2.13, o NFR7.6 e o NFR8.2. As linhas entram em `nfr-requirements` na Code Generation (alinhamento A17 de `logical-components.md`).
