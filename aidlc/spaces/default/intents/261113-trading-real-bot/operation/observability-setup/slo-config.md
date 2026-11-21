# SLOs, SLIs e orçamentos de erro — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Observability Setup, Q3 (opção A).

> **Nada está implantado.** Os alarmes de consumo estão em `infra/terraform/modules/observability/slo.tf`, validados offline. O teste `observability_metrics.tftest.hcl` recalcula a matemática abaixo. Cada SLO só começa a ser medido depois do primeiro `apply` da devnet. Os comandos de conferência estão no fim do arquivo.

## 1. SLOs com alarme de consumo

| SLO | SLI (o que conta como bom) | Meta | Janela | Orçamento de erro | Fonte |
|---|---|---|---|---|---|
| Disponibilidade do api (U1:NFR4.1) | pedidos ao grupo-alvo do api respondidos sem 5xx, medidos no balanceador (o ponto mais perto do usuário) | 99,5% | 30 dias corridos | 0,5% dos pedidos | `AWS/ApplicationELB`: `RequestCount`, `HTTPCode_Target_5XX_Count`, `HTTPCode_ELB_5XX_Count` |
| Disponibilidade do TxSigner (U1:NFR4.1) | intervalos de 240 s em que o TxSigner publicou métricas (batimento) | 99,5% | 30 dias corridos | 216 minutos (54 intervalos) | `Eureka/App process_uptime_seconds{job=txsigner}`, SampleCount por intervalo de 240 s |
| Frescor dos dados de mercado (U2:NFR4.1) | intervalos de 240 s com o preço mais velho até 60 s (base local: `marketdata_price_fresh_minute`) | 99% | 30 dias corridos | 432 minutos (108 intervalos) | `Eureka/App marketdata_oldest_price_age_seconds`, Maximum por intervalo de 240 s |

### Escolhas de medição

- **api:** o SLI do desenho da U1 fala dos pedidos de leitura da carteira e de saque. O balanceador não separa rotas, então o alarme mede todas as rotas do api. Isso é mais rígido que o desenho, nunca mais frouxo.
  - O `HTTPCode_ELB_5XX_Count` existe só por balanceador. Por isso ele inclui os 5xx gerados para o web e o admin, o que também torna a medida mais conservadora.
  - Uma janela com menos de 10 pedidos conta como saudável. Com o tráfego do piloto, um único 5xx em 3 pedidos não deve acordar ninguém. Uma indisponibilidade de verdade aparece pelo batimento e pelos alarmes de trabalho parado.
- **TxSigner:** ele não tem balanceador, e a métrica de latência não traz o status da resposta. Por isso o SLI é por tempo: um intervalo de 240 s sem batimento conta como indisponível. Falhas de resposta com o processo vivo aparecem no alarme `real-execution-signer-unavailable`.
- **Frescor:** um intervalo sem dado conta como ruim. Se o worker cai, o usuário vê preço velho.
- **Por que intervalos de 240 s:** o agente lê a cada 120 s (decisão do responsável, 2026-11-20). Com intervalos de 1 minuto, metade deles ficaria vazia por natureza e contaria como ruim. Um intervalo de 240 s sempre tem duas leituras. A medida fica mais grossa: um único valor acima de 60 s marca o intervalo inteiro como ruim, o que é conservador.

## 2. Taxa de consumo (burn rate)

Os dois alarmes de cada SLO usam a mesma fórmula, sobre um orçamento de 30 dias (720 h):

```
taxa de consumo        = fatia do orçamento × 720 h / horas da janela
razão de erro máxima   = taxa de consumo × (1 − meta)
intervalos ruins tolerados = teto(razão de erro máxima × intervalos de 240 s da janela)
```

| Janela | Fatia do orçamento | Taxa | Disponibilidade (99,5%) | Frescor (99%) | Severidade |
|---|---|---|---|---|---|
| 1 h | 2% | 14,4 | razão > 0,072; ou ≥ 2 de 15 intervalos ruins | ≥ 3 de 15 intervalos ruins (0,144) | alta |
| 6 h | 5% | 6 | razão > 0,03; ou ≥ 3 de 90 intervalos ruins | ≥ 6 de 90 intervalos ruins (0,06) | média |

- **Por que duas janelas:** a de 1 h pega uma queda forte em minutos, e a de 6 h pega uma degradação lenta que a de 1 h não vê.
- **Antes da quebra:** os dois alarmes disparam com 2% ou 5% do orçamento gasto, bem antes de o SLO estourar, como pede a regra da fase Operation.
- **Como o alarme é montado:**
  - no api, uma razão sobre somas, com período igual à janela (3600 s ou 21600 s) e uma avaliação;
  - no TxSigner e no frescor, uma expressão por intervalo de 240 s que vale 0 ou 1 (`IF(FILL(...))`), avaliada em 15 ou 90 períodos de 240 s, com `datapoints_to_alarm` igual ao número de intervalos ruins tolerados e dado ausente contando como ruim.
  - O arredondamento para cima faz o limite efetivo ficar um pouco acima da razão exata (por exemplo, 2 de 15 é 13% contra 7,2%). Mesmo assim, o alarme dispara bem antes de o orçamento do mês acabar.

## 3. Política do orçamento de erro (piloto, fundador sozinho)

| Orçamento restante no mês | O que fazer |
|---|---|
| > 50% | ritmo normal |
| 25% a 50% | acompanhar os painéis depois de cada implantação; rever os incidentes do mês |
| < 25% | só implantar correções; nenhuma liberação nova de usuário para o REAL |
| esgotado | congelar mudanças que não sejam de confiabilidade. Se o SLO for do TxSigner, avaliar acionar o kill switch REAL; o saque continua sempre disponível. |

O orçamento restante sai da consulta da seção 6. O piloto não tem relatório automático de conformidade. A revisão é manual, todo mês, no estágio de Performance Monitoring.

## 4. Metas de p95 por Unit

As metas de p95 do NFR Design são informativas: não são portão. Na nuvem, elas dependem de o código publicar um summary, porque o CloudWatch Agent descarta histogramas.

| Meta | Requisito | Alarme local (Prometheus) | Alarme na nuvem |
|---|---|---|---|
| Barreira: avaliação de compra com p95 até 150 ms | U3-NFR3.1 e U3-NFR5.11 | `RealRiskSlow` | `real-risk-slow`: escrito, em paridade e **desligado** até a OBS-G1 |
| Execução: da decisão ao envio na Automática total com p95 até 3 s | U4-NFR3.1 e U4-NFR5.13 | `RealExecutionSlow` | `real-execution-slow`: escrito, em paridade e **desligado** até a OBS-G2 |
| Confirmação: do envio ao estado final (75 s é o limite da regra) | U4-NFR3.5 | `RealExecutionSentOld` | `real-execution-sent-old`, que já funciona porque mede a ordem mais antiga e não usa histograma |
| Preenchimento das saídas condicionais | U7 | só no Prometheus local (`real_conditional_fill_lag` é histograma) | lacuna OBS-G4 |
| Exportação fiscal | U7-NFR5.5 (contador por resultado) | — | sem meta de p95 na nuvem; o painel e os logs cobrem |
| Leituras do api (p95 até 300 ms) | U1, U4-NFR3.4 e U5-NFR3.4 | histograma HTTP | painel Serviços: `TargetResponseTime` p95 do balanceador; sem alarme |

## 5. Sinais de ouro por serviço

| Serviço | Latência | Tráfego | Erros | Saturação |
|---|---|---|---|---|
| api | `TargetResponseTime` p50, p95 e p99 | `RequestCount` | `HTTPCode_Target_5XX_Count`; SLO de disponibilidade | CPU e memória do ECS; conexões do RDS |
| worker | `real_risk_evaluation_seconds_max` e trabalhos parados | `real_orders_total` e `real_risk_evaluations_total` | `real_risk_errors_total` e `real_upstream_failures_total` | CPU e memória do ECS |
| TxSigner | `txsigner_latency_seconds_max` e `txsigner_kms_sign_seconds_max` | `AWS/Usage` Sign | `txsigner_kms_errors_total` e recusas | CPU e memória do ECS; conexões do RDS do TxSigner |
| dados de mercado | frescor (SLO) | `marketdata_provider_calls_total` | `marketdata_source_state{state=FAILED}` e valores inválidos | `marketdata_provider_budget_ratio` |

Cada serviço novo tem ao menos uma métrica de saúde (batimento) e uma de erro, como pede a regra da fase Operation.

## 6. Conferência depois do primeiro apply da devnet

```bash
# Os seis alarmes de consumo existem e não estão em INSUFFICIENT_DATA depois de 1 hora no ar
aws cloudwatch describe-alarms --alarm-name-prefix eureka-real-slo- \
  --query 'MetricAlarms[].[AlarmName,StateValue,Threshold,EvaluationPeriods,DatapointsToAlarm]' --output table

# SLI do api nos últimos 30 dias (razão de 5xx); a mesma expressão do alarme
cat > /tmp/sli-api.json <<'JSON'
[
  {"Id":"req","MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"RequestCount","Dimensions":[{"Name":"LoadBalancer","Value":"<arn_suffix do balanceador>"},{"Name":"TargetGroup","Value":"<arn_suffix do grupo-alvo>"}]},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
  {"Id":"t5","MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"HTTPCode_Target_5XX_Count","Dimensions":[{"Name":"LoadBalancer","Value":"<arn_suffix do balanceador>"},{"Name":"TargetGroup","Value":"<arn_suffix do grupo-alvo>"}]},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
  {"Id":"sli","Expression":"1 - FILL(t5,0)/req","ReturnData":true}
]
JSON
aws cloudwatch get-metric-data --metric-data-queries file:///tmp/sli-api.json \
  --start-time "$(date -u -d '-30 days' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)"

# Intervalos de 240 s ruins de frescor nas últimas 24 h (SLO: no máximo 1% dos 360 intervalos do dia, ou seja, 3)
aws cloudwatch get-metric-data --start-time "$(date -u -d '-1 day' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" \
  --metric-data-queries '[{"Id":"age","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"marketdata_oldest_price_age_seconds"},"Period":240,"Stat":"Maximum"},"ReturnData":false},{"Id":"bad","Expression":"IF(FILL(age,3600) > 60, 1, 0)","ReturnData":true}]' \
  --query 'sum(MetricDataResults[0].Values)'
```

Os valores entre `<>` são o final do ARN do balanceador (`app/eureka-real/...`) e do grupo-alvo (`targetgroup/eureka-real-api/...`). Eles saem do comando `aws elbv2 describe-load-balancers --names eureka-real` e do comando `aws elbv2 describe-target-groups --names eureka-real-api`.

## Sources

- `observability-setup-questions.md` (Q3).
- `construction/u1-bot-wallet/infrastructure-design/monitoring-design.md` (SLIs/SLOs), `nfr-requirements/observability-requirements.md` (NFR4.1).
- `construction/u2-real-market-data/infrastructure-design/monitoring-design.md` e `infra/observability/marketdata-alerts.yml` (`marketdata_price_fresh_minute`).
- `construction/u3-real-risk/` a `u7-exits-and-fiscal/nfr-requirements/performance-requirements.md` (metas de p95).
- `construction/u*/nfr-design/performance-design.md`: as metas de latência de que saem os p95.
- `construction/u*/nfr-design/reliability-design.md`: a disponibilidade de 99,5% e o comportamento sem dado.
- `construction/u*/nfr-design/security-design.md`: os sinais de segurança do painel Segurança e o que nunca vai para log nem métrica, como endereço completo, segredo e assinatura.
- `infra/terraform/modules/observability/slo.tf`.
- `.claude/knowledge/aidlc-operations-agent/slo-sli-patterns.md` (orçamento de erro e janela corrida).
