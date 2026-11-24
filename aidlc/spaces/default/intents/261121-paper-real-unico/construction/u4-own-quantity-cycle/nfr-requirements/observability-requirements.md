# Requisitos de observabilidade — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24.

A U4 passa a medir três coisas, sem dado pessoal, e acrescenta um alarme para a falha persistente da leitura da posição própria (D3, BR2.7) [Q3]. Pela decisão do fundador F2 de 2026-11-24, o alarme é uma **razão**: leituras indisponíveis por `BUDGET_EXCEEDED` ou `READ_FAILED` sobre as leituras dos ciclos REAL, em janelas sobrepostas, sustentada por 15 minutos. O runbook ganha a saída pelas condicionais e por "Vender tudo e sacar" (NFR2.14).

## Convenção de nomes

Mesma convenção da U1 e da U2: Micrometer, exportado em `/actuator/prometheus` do worker (porta de gestão 8082). O exportador do Prometheus troca o ponto por `_` e acrescenta `_total` aos contadores. As métricas operacionais do REAL do worker usam o prefixo `real_` (como `real_release_blocks` e `real_reconciliations` em `RealOpsMetrics`). Os dois nomes da Q3 já seguem esse prefixo, e o denominador novo segue também. As etiquetas levam só valores de enum.

**Alinhamento com o Functional Design.** O `functional-spec.md` ("Efeito nos contratos") lista `STALE_SNAPSHOT` entre os motivos de `real_own_holdings_unavailable_total{reason}`. A Q3 confirmada põe `STALE_SNAPSHOT` em `real_cycle_sell_capped_total{outcome}`, e vale a Q3. `STALE_SNAPSHOT` não é falha da leitura: é a marca do bot que mudou entre a leitura e a trava (BR3.1). Contá-lo no contador de indisponibilidade faria o alarme de NFR2.12 disparar por concorrência normal. O resultado gravado no ciclo continua `REAL_OWN_QUANTITY_UNAVAILABLE` com o motivo `STALE_SNAPSHOT` (BR3.1); só a métrica é a do corte. O nome do contador, "capped", cobre também as vendas que não aconteceram; o significado de cada `outcome` está na tabela abaixo, e a revisão de NFR2.17 impede que um `STALE_SNAPSHOT` repetido passe sem ser visto. O `functional-spec.md` é alinhado na Code Generation, não neste estágio.

## Métricas novas

| ID | Requisito | Nome no Micrometer | Nome exportado (Prometheus) | Etiquetas e séries | Onde é medida |
|---|---|---|---|---|---|
| NFR2.16 | Contar todas as leituras do RealBotHoldings feitas pelos ciclos REAL, qualquer que seja o resultado. É o denominador da razão do alarme [fundador F2]. | `real_own_holdings_reads` | `real_own_holdings_reads_total` | nenhuma: 1 série | no worker, no ciclo REAL (F1, passo 2): +1 por chamada do RealBotHoldings, antes de saber o resultado (`Available` ou `Unavailable` com qualquer motivo). Os ciclos que não leem (NFR1.10) não contam. Registrada com 0 na subida. |
| NFR2.10 | Contar as leituras do RealBotHoldings que terminaram `Unavailable`, por motivo (BR2.3). | `real_own_holdings_unavailable` | `real_own_holdings_unavailable_total` | `reason` ∈ {`BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`}: 3 séries no máximo | no worker, no ciclo REAL (F2, passo 3): +1 por leitura `Unavailable`, na mesma chamada em que NFR2.16 subiu. Toda leitura que sobe esta métrica subiu também o denominador, então a razão nunca passa de 1. As 3 séries são registradas com 0 na subida. |
| NFR2.11 | Contar os cortes da venda do ciclo e as vendas que não aconteceram por causa da quantidade própria (BR1.4, BR1.5, BR3.1). | `real_cycle_sell_capped` | `real_cycle_sell_capped_total` | `outcome` ∈ {`CAPPED`, `NO_OWN_QUANTITY`, `STALE_SNAPSHOT`}: 3 séries no máximo | no worker, no corte (F3). `CAPPED` quando 0 < venda < pedido. `NO_OWN_QUANTITY` quando a venda é 0 (`REAL_NO_OWN_QUANTITY`) e também por mint tirado dos tokens com disponível 0 (BR1.5). `STALE_SNAPSHOT` quando M1 ≠ M0. Uma venda com pedido ≤ disponível não conta. Fica só no Prometheus. As 3 séries são registradas com 0 na subida. |

Critérios mensuráveis das três métricas:

- 7 séries novas por processo do worker (1 + 3 + 3) e 0 na API. 0 etiquetas com tenant, pessoa, bot, mint, carteira, endereço, assinatura ou `correlationId` (NFR3.7).
- Testes de unidade:
  - depois de uma leitura com prazo esgotado, `real_own_holdings_reads_total` vale 1, `real_own_holdings_unavailable_total{reason="BUDGET_EXCEEDED"}` vale 1 e as outras 2 séries valem 0;
  - depois de uma leitura `Available`, só o denominador sobe;
  - depois de um corte de 150 para 100 unidades base, `real_cycle_sell_capped_total{outcome="CAPPED"}` vale 1;
  - um ciclo com M1 ≠ M0 soma 1 a `{outcome="STALE_SNAPSHOT"}` e 0 a `real_own_holdings_unavailable_total`.
- Teste de integração: as 7 séries aparecem em `/actuator/prometheus` do worker com os nomes exportados acima e com valor 0 logo depois da subida.
- Com a flag desligada, as três métricas continuam em 0 (T7).

## Logs

| ID | Requisito | Critério de aceite mensurável |
|---|---|---|
| NFR3.9 | Os registros de log da U4 são em inglês e trazem o `correlationId` do ciclo, o id do bot (uuid), o motivo ou resultado em enum e, no corte, o pedido e a venda em unidades base. Níveis: leitura `Unavailable` em `WARN`, com a causa técnica e sem SQL; `STALE_SNAPSHOT`, `NO_OWN_QUANTITY` e `CAPPED` em `INFO`; `BOT_NOT_OWNED` em `ERROR`, porque é defeito: o despachante só entrega bots ativos do dono. | Teste de captura de log: cada um dos 5 eventos gera 1 registro com o `correlationId` e o id do bot. 0 registros com endereço de carteira, assinatura, token, cabeçalho `Authorization`, nonce ou assinatura de login (NFR3.7). 0 `catch` vazios no código da U4. |
| NFR3.10 | A U4 não acrescenta rastreamento distribuído. | 0 bibliotecas de tracing novas. A correlação entre o ciclo, a nota da decisão e o log é o `correlationId`. A U4 não chama outro processo. |

## Alarme

### Cálculo do limite

A meta da leitura é p99,9 ≤ 250 ms (NFR1.1), e 250 ms é o corte. Por isso, em operação normal, `BUDGET_EXCEEDED` fica em no máximo p = 0,001 das leituras. `READ_FAILED` (erro de banco) é tratado como próximo de 0 fora de incidente, e o cálculo usa p = 0,001 para os dois motivos somados.

**Volume de referência (NFR1.8).** São 63 bots com um ciclo a cada 5 min, ou seja, 12,6 leituras por minuto e n ≈ 126 leituras numa janela de 10 min. Em média, a janela tem n × p ≈ 0,126 falha normal.

**Limite escolhido: razão > 0,05 (5%), sustentada por 15 min.** Com n = 126, passar de 5% exige pelo menos 7 falhas na janela de 10 min.

- **Uma janela isolada passar do limite.** P(≥ 7 falhas) ≈ C(126, 7) × 0,001⁷ ≈ 6,6 × 10¹⁰ × 10⁻²¹ ≈ 7 × 10⁻¹¹.
- **Ficar acima do limite por 15 min de avaliação.** Com janelas de 10 min, isso exige falhas espalhadas por cerca de 25 min e muito mais que 7 falhas. A probabilidade é ainda menor: na prática, 0 disparos por ruído.
- **Rajada normal.** 3 falhas no mesmo minuto dão 3 / 126 = 2,4%, abaixo de 5%.

**Volume baixo, por exemplo na etapa da carteira do fundador com 3 bots.** Nesse caso n ≈ 6 leituras em 10 min, e uma única falha normal (1/6 ≈ 17%) passa de 5%.

- **Uma falha isolada não dispara.** A condição fica verdadeira só enquanto a falha está dentro da janela, no máximo 10 min, e o `for` pede 15 min.
- **O que é preciso para disparar.** Falhas repetidas que mantenham a janela acima do limite por 15 min seguidos, ou seja, pelo menos 2 falhas com até 10 min entre elas, cobrindo 15 min. Em 25 min há n ≈ 15 leituras, e P(≥ 2 falhas) ≈ C(15, 2) × 10⁻⁶ ≈ 10⁻⁴ por janela de 25 min. Isso dá cerca de 0,6% por dia, ou seja, um disparo por ruído a cada 5 meses em média, e menos ainda com o espaçamento exigido. É aceito para um on-call único.

**O que o limite pega:**

- uma falha sistêmica (banco lento, pool esgotado, defeito da leitura), em qualquer volume;
- um único bot com falha persistente enquanto houver até 19 bots REAL lendo (1/19 ≈ 5,3% > 5%), o que cobre a etapa da carteira do fundador e o começo dos convidados.

**O que o limite não pega.** Com 20 bots ou mais, um único bot com falha persistente fica abaixo de 5% e não dispara. Esse caso aparece no log `WARN` com o bot e na revisão do runbook (NFR2.14). É a lacuna aceita pela escolha da razão [fundador F2].

### Requisitos do alarme

| ID | Requisito | Valor |
|---|---|---|
| NFR2.12 | Um alarme novo para a falha persistente da leitura: a razão das leituras indisponíveis por `BUDGET_EXCEEDED` ou `READ_FAILED` sobre as leituras dos ciclos REAL passa de 5%, em janelas sobrepostas de 10 min, sustentada por 15 min [fundador F2] (D3, BR2.7). `BOT_NOT_OWNED` (defeito, log `ERROR`) e `STALE_SNAPSHOT` (concorrência, outro contador) ficam fora do numerador. | **Prometheus:** a regra `RealOpsOwnHoldingsUnavailable` entra no grupo `real-ops` de `infra/observability/real-ops-alerts.yml`.<br>• Expressão: `sum(increase(real_own_holdings_unavailable_total{reason=~"BUDGET_EXCEEDED\|READ_FAILED"}[10m])) / sum(increase(real_own_holdings_reads_total[10m])) > 0.05`, com `for: 15m`.<br>• `labels: { severity: high }`.<br>• Anotações: `threshold: "0.05"`, `window_seconds: "600"`, `for_seconds: "900"`, `cloud_periods: "2"` e o resumo que aponta para o runbook.<br>• A regra é avaliada a cada 1 min (o intervalo do grupo), então as janelas de 10 min se sobrepõem em 9 min. Uma falha nunca cai entre janelas disjuntas.<br>• Com a flag desligada, o denominador é 0 e a divisão dá `NaN`; `NaN > 0.05` é falso, então o alerta não dispara.<br><br>**CloudWatch:**<br>• Na lista de `app_metric_declarations` de `infra/terraform/modules/observability/app-metrics.tf` entram `real_own_holdings_unavailable_total` com `dimensions = [["reason"]]` e `real_own_holdings_reads_total` com `dimensions = [[]]`.<br>• Alarme de expressão `aws_cloudwatch_metric_alarm.real_ops_own_holdings_unavailable` em `infra/terraform/modules/observability/real-ops.tf`, com `alarm_name = "${var.name}-real-ops-own-holdings-unavailable"`.<br>• Três `metric_query` no namespace `Eureka/App`, estatística `Sum`, `period = 600` nas três: `m1` (`reason = BUDGET_EXCEEDED`), `m2` (`reason = READ_FAILED`) e `m3` (as leituras). A expressão é `e1 = IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, com `return_data = true` (alinhamento A18, revisão R-01: um período sem ponto de um dos numeradores conta como 0, e sem leituras o valor é 0 e o alarme não dispara); o texto exato é conferido pelo `RealOpsAlarmParityTest` e pelo teste offline do Terraform (saída `alarm_expressions`).<br>• `evaluation_periods = 2`, `datapoints_to_alarm = 2`, `threshold = 0.05`, `GreaterThanThreshold`, `treat_missing_data = "notBreaching"`, ação no tópico SNS único do piloto.<br>• Por que esses números: o período de 600 s fica acima do mínimo de 180 s de uma leitura do agente, que lê a cada 120 s, e cada período tem cerca de 5 leituras do agente. O alarme não dispara com dado ausente, por isso não precisa de múltiplo de 240 s. 2 × 600 s = 1.200 s é o par mais próximo, em janelas disjuntas, de "10 min sustentado por 15 min". Com `m3 = 0`, o CloudWatch não gera ponto, e o ponto ausente não dispara.<br>• O nome `real_ops_own_holdings_unavailable` entra em `alarm_resource_names` e no `depends_on` de `app-metrics.tf`.<br><br>**Testes:**<br>• `promtool check rules` do job `backend` passa com uma regra a mais.<br>• T20 (NFR7.6) cobre falha persistente, falha isolada, jitter, incremento na fronteira, ruído normal da cauda, rajada, `BOT_NOT_OWNED` e `STALE_SNAPSHOT` fora do numerador, e flag desligada.<br>• No `RealOpsAlarmParityTest`, `assertThat(rules).hasSize(14)` passa para `hasSize(16)` (esta regra e a do bot preso, NFR2.18; alinhamento A17). O teste passa a ler a anotação `cloud_periods` (1 quando ausente): a janela do alarme (`period × evaluation_periods` = 1.200 s) deve ser igual a `cloudWindow(window_seconds, false) × cloud_periods` = 600 × 2.<br>• As conferências que já existem continuam a passar: `[10m]` na expressão e `> 0.05` no fim. O `PERIOD` do teste acha `period = 600` no primeiro `metric_query`, e o `THRESHOLD` acha `0.05`.<br>• Um teste próprio da regra confere:<br>&nbsp;&nbsp;– na regra: `for: 15m`, igual a `for_seconds`, o seletor `reason=~"BUDGET_EXCEEDED\|READ_FAILED"` e o denominador `real_own_holdings_reads_total`;<br>&nbsp;&nbsp;– no alarme: as três `metric_query` com `period = 600`, a expressão `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, `evaluation_periods = 2`, `datapoints_to_alarm = 2` e `treat_missing_data = "notBreaching"`.<br>• O teste "só o `for_each` dos jobs parados trata dado ausente como `breaching`" continua com o mesmo resultado.<br>• O teste offline do Terraform (`observability_metrics.tftest.hcl`) confere que as três métricas do alarme estão na lista com o conjunto de dimensões usado, e que o período de 600 s é ≥ 180 s. |
| NFR2.13 | Nenhum outro alarme e nenhum painel novo entram na U4, **exceto** o alarme do bot preso da decisão F3 do fundador (NFR2.18; alinhamento A17). | `real_cycle_sell_capped_total` fica só no Prometheus. O corte e `NO_OWN_QUANTITY` são o comportamento esperado, e `STALE_SNAPSHOT` é concorrência normal que se resolve no ciclo seguinte; a revisão de NFR2.17 cuida da repetição. 0 painéis novos. |
| NFR2.17 | Critério de revisão de `STALE_SNAPSHOT`, sem alarme. Um bot que nunca vende porque a marca sempre muda tem de aparecer (revisão R-06). | **Build and Test:** no `RealBotHoldingsMeasurementIT` (NFR1.5), sem ciclos concorrentes, a razão `real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}` / `real_own_holdings_reads_total` é 0; acima de 0, a etapa registra e vai ao fundador. **Operação (runbook, NFR2.14):** o fundador revisa uma vez por semana, e depois de cada implantação que liga a flag, a razão no Prometheus: `sum(increase(real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}[24h])) / sum(increase(real_own_holdings_reads_total[24h]))`. Se ela passar de 1% (o normal é perto de 0, porque o `STALE_SNAPSHOT` só acontece quando um ciclo atrasa e se sobrepõe ao seguinte), ele procura no log `INFO` (NFR3.9) os bots com `STALE_SNAPSHOT` em 3 ciclos seguidos ou mais e abre defeito para cada um. Um bot com 3 `STALE_SNAPSHOT` seguidos é tratado como "bot que não vende" mesmo com a razão geral abaixo de 1%. |
| NFR2.18 | Um bot (ou a carteira inteira, 3 bots) com 3 ou mais leituras indisponíveis seguidas por `BUDGET_EXCEEDED` ou `READ_FAILED` gera alerta, mesmo abaixo dos 5% da razão de NFR2.12 [fundador F3, 2026-11-24; revisão R-02; linha nova pelo alinhamento A17 da Code Generation]. | **Métrica:** gauge sem etiqueta `real_own_holdings_stuck_bots`, calculado de um mapa em memória por bot (`OwnHoldingsStreaks`): leitura boa e `BOT_NOT_OWNED` zeram; `BUDGET_EXCEEDED` e `READ_FAILED` somam 1; conta os bots com 3 ou mais seguidas vistos nos últimos 20 min; despejo por 20 min e teto de 10.000 entradas. Registrado com 0 na subida do worker; na API, ausente. **Prometheus:** regra `RealOpsOwnHoldingsStuckBots`, `max(min_over_time(real_own_holdings_stuck_bots[10m])) > 0`, `labels: { severity: high }`, anotações `threshold: "0"`, `window_seconds: "600"`, `cloud_periods: "1"`, sem `for`. **CloudWatch:** `aws_cloudwatch_metric_alarm.real_ops_own_holdings_stuck_bots`, estatística `Minimum`, `period = 300`, 2 de 2, `threshold = 0`, `notBreaching`, sem `dimensions`; a métrica entra no grupo `[[]]` da lista de permissão. **Testes:** os casos 9 e 10 do T20 (NFR7.6), `OwnHoldingsStreaksTest`, `OwnQuantityMetricsIT` e `RealOpsAlarmParityTest` (`hasSize(16)`, `theStuckBotsAlertHasItsTwinInCloudWatch`). |

### Por que a expressão não depende da versão do Prometheus

- **Sem subconsulta.** A expressão não usa a forma `[janela:passo]`, então não depende de a subconsulta devolver 3 ou 4 pontos conforme o intervalo seja fechado (2.x) ou aberto à esquerda (3.x).
- **A persistência vem do `for: 15m`.** O `for` conta o tempo em que a condição ficou verdadeira em avaliações seguidas do grupo. Isso é estado do avaliador, e não muda entre 2.x e 3.x.
- **A fronteira do seletor de faixa afeta no máximo uma amostra.** No `[10m]` restante, a diferença entre as versões é só a amostra que cai exatamente em t − 10 min: incluída no 2.x, excluída no 3.x.
  - Com amostras a cada 15 s, essa é 1 de cerca de 40 amostras da janela.
  - O numerador e o denominador vêm do mesmo alvo, no mesmo scrape e com os mesmos instantes, então a mesma amostra entra ou sai dos dois.
  - O `increase()` extrapola as duas séries com os mesmos instantes, e a extrapolação quase não muda a razão.
- **Um incremento na fronteira não some.** As janelas se sobrepõem em 9 min, então esse incremento já foi contado nas 9 avaliações anteriores. A diferença entre as versões pode atrasar ou adiantar o fim da condição em no máximo 1 avaliação (1 min). Isso não muda o resultado de um `for` de 15 min com uma falha persistente, e o caso (4) do T20 confere.

## SLI e SLO

| ID | Requisito | Valor |
|---|---|---|
| NFR1.13 | A U4 não declara SLO operacional novo. O SLI da saúde da leitura é a razão de NFR2.12, `(BUDGET_EXCEEDED + READ_FAILED) / leituras`. O SLI do tempo é o p99,9 do `RealBotHoldingsMeasurementIT` (NFR1.5). | O limiar operacional é o alarme de NFR2.12 (> 5% sustentado por 15 min), e a meta de cauda é p99,9 ≤ 250 ms, ou seja, ≤ 0,1% de `BUDGET_EXCEEDED` (NFR1.1). O prazo de 250 ms é aplicado em execução (NFR1.7), então uma leitura lenta aparece como `BUDGET_EXCEEDED` no contador, sem histograma novo: o CloudWatch Agent descarta histogramas (`app-metrics.tf`). |

## Sources

- `nfr-requirements-questions.md`: Q3 e o resumo confirmado [Q3].
- `reviews/review-01.md` (R-01, R-02, R-06) e a decisão do fundador de 2026-11-24 F2: meta p99,9 ≤ 250 ms; alarme por razão em janelas sobrepostas, com gêmeo no CloudWatch; independente da semântica de fronteira 2.x/3.x.
- `inception/requirements-analysis/requirements.md`: NFR1, NFR3, NFR8.
- `construction/u4-own-quantity-cycle/functional-design/rules.md`: BR1.4, BR1.5, BR2.3, BR2.7 (D3), BR3.1; `functional-spec.md`: F1, F2, F3, T20, "Efeito nos contratos".
- `construction/u2-real-bot-pnl/nfr-requirements/observability-requirements.md`: convenção de nomes, registro das séries com 0, forma do NFR2.14 da U2 (regra no grupo `real-ops`, alarme em `real-ops.tf`, `hasSize(14)`).
- `aidlc/spaces/default/memory/project.md`: correção do CloudWatch Agent (leitura a cada 120 s; janelas de 240 s quando o dado ausente dispara, pelo menos 180 s nas demais; US$ 350 por conta); Forbidden (log de segredos).
- `aidlc/spaces/default/memory/team.md`: Deployment (fundador único on-call), Way of Working (`ci.yml` é do responsável).
- Código lido:
  - `infra/observability/real-ops-alerts.yml`: 14 regras, anotações `threshold` e `window_seconds`;
  - `infra/terraform/modules/observability/real-ops.tf`, `app-metrics.tf` (lista, conjuntos de dimensões, `alarm_resource_names`, regras das janelas) e `anomaly.tf` (`datapoints_to_alarm`);
  - `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java`: `hasSize(14)`, janela igual a `period × evaluation_periods`, expressão com `[<janela>m]` e `[<>]=? <limite>`;
  - `apps/api/src/test/java/com/memetrade/infra/CloudWatchWindows.java`;
  - `botwallet/infra/RealOpsMetrics.java`: prefixo `real_`;
  - `.github/workflows/ci.yml`: lista do `promtool` e digest da imagem.

## Assumptions & Open Questions

1. **Decidido (fundador, F2, 2026-11-24):**
   - o alarme é a razão de NFR2.12, com o gêmeo no CloudWatch;
   - a meta de cauda é p99,9 ≤ 250 ms;
   - a BR2.7 ("3 ciclos seguidos", "período de 300 s, 3 de 3") é substituída por esta forma. O `rules.md` é alinhado na Code Generation, não neste estágio.
2. **Ajuste do teste de paridade.** O `RealOpsAlarmParityTest` passa a ler `cloud_periods` (1 quando ausente) e a contar 15 regras. É uma mudança de código de teste na Code Generation da U4, sem política nova.
3. [assumption] O teste offline do Terraform precisa aceitar um alarme de expressão (`metric_query`) ao conferir que cada métrica do alarme está na lista. Se hoje ele só lê `metric_name` no nível do recurso, a Code Generation estende a conferência às três `metric_query`.
4. [assumption] `p = 0,001` para `BUDGET_EXCEEDED` e `READ_FAILED` somados vem da meta p99,9. Se a medida do NFR1.5 mostrar uma cauda pior, o limite de 5% continua com margem até p ≈ 0,01 com 63 bots: P(≥ 7 de 126) ≈ 2 × 10⁻⁴ por janela, ainda sem sustentar 15 min.
5. [assumption] A severidade `high` (não `critical`) segue a de alarmes do REAL que não indicam dinheiro errado: o bot só deixa de vender pelo ciclo, e o dinheiro continua saindo pelas outras saídas.
6. **Divergência com o Functional Design:** `STALE_SNAPSHOT` sai do contador de indisponibilidade e vai para o contador do corte, como na Q3 (ver "Convenção de nomes"). A repetição é coberta pela revisão de NFR2.17, sem alarme.
