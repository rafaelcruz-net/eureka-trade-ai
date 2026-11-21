# Detecção de anomalia — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Observability Setup, Q7 (opção A).

> **Nada está implantado.** Os dois detectores são alarmes `ANOMALY_DETECTION_BAND` em `infra/terraform/modules/observability/anomaly.tf`, validados offline. O modelo do CloudWatch só começa a aprender depois do primeiro `apply` da devnet. A faixa só é útil depois de cerca de 2 semanas de dados.

## Detectores

| Alarme | Métrica | Estatística e período | Faixa | Dispara quando | Severidade |
|---|---|---|---|---|---|
| `eureka-real-anomaly-real-orders` | `Eureka/App real_orders_total` (todos os eventos de ordem REAL: cotada, enviada, confirmada, falha...) | Sum, 6 min (três leituras de 120 s) | 3 desvios-padrão | o volume fica **acima** da faixa em 3 de 3 períodos (18 min) | aviso |
| `eureka-real-anomaly-real-refusals` | `Eureka/App real_risk_evaluations_total{outcome=REJECTED}` (recusas da barreira REAL) | Sum, 6 min | 3 desvios-padrão | igual | aviso |

- **Só "acima" da faixa:** uma hora quieta é normal num piloto com o fundador e até 20 convidados. Um volume abaixo do esperado não indica problema de dinheiro.
- **Período de 6 min:** o agente lê a cada 120 s, então cada período soma sempre três leituras do contador. Com 5 min, um período teria duas ou três leituras, e o volume oscilaria só pelo ritmo da leitura.
- **3 de 3 períodos:** uma rajada curta, como um bot que reavalia várias moedas de uma vez, não gera aviso.
- **Dado ausente:** `notBreaching`. Quando nada opera, nada é anômalo. A falta de métricas é coberta pelos alarmes `metrics-absent-*`.
- **Destino:** o tópico `eureka-real-alarms` (e-mail do fundador), com severidade de aviso. O detector nunca bloqueia nada. Os controles continuam sendo os alarmes fixos (`real-execution-signing-refused`, `real-risk-errors`...), a barreira REAL e o kill switch.

## O que cada aviso costuma indicar

| Aviso | Hipóteses a conferir primeiro | Onde olhar |
|---|---|---|
| Muitas ordens | laço de um bot, repetição de ordens depois de falha de um fornecedor, recotações em excesso (`real_orders_total{code=REQUOTED}`) | painel Visão REAL (ordens por código); consulta `ordens-real-ambiguas`; `real_upstream_failures_total` |
| Muitas recusas | limite mal configurado por um usuário, dado de mercado `unknown` ou velho (o REAL recusa), perda do dia atingida | painel Visão REAL (aprovadas e recusadas); frescor do preço; `real_risk_evaluations_total` por código no console |

Se o aviso vier junto com um alarme crítico ou alto, segue o alarme crítico. Se vier sozinho e se repetir sem causa, o fundador decide no estágio de Performance Monitoring se ajusta a faixa (por exemplo, para 4 desvios) ou exclui um período da janela de treino.

## Ajustes do modelo

- A faixa usa o padrão do CloudWatch: o modelo aprende com até 2 semanas de dados e considera sazonalidade de hora e de dia da semana.
- **Antes de abrir aos convidados:** excluir do treino os períodos de testes de carga ou de ensaios na devnet, para o modelo não aprender rajadas artificiais. Na mainnet, o modelo é próprio da conta, porque cada conta tem o seu.
- Os volumes não têm dimensão de pessoa nem de carteira. A anomalia é do piloto inteiro, nunca de um usuário.

## Custo

Um alarme de detecção de anomalia conta como 3 métricas de alarme (a métrica, o limite superior e o inferior da faixa), a US$ 0,10 cada: US$ 0,30 por alarme por mês. Os dois juntos custam US$ 0,60 por mês por ambiente, já incluídos em `alarms.md`, seção 9.

## Conferência depois do primeiro apply da devnet

```bash
# Os dois alarmes existem e usam a faixa
aws cloudwatch describe-alarms --alarm-names eureka-real-anomaly-real-orders eureka-real-anomaly-real-refusals \
  --query 'MetricAlarms[].[AlarmName,ComparisonOperator,ThresholdMetricId,StateValue]' --output table

# O modelo foi criado para cada métrica (aparece depois da primeira avaliação)
aws cloudwatch describe-anomaly-detectors --namespace Eureka/App \
  --query 'AnomalyDetectors[].[SingleMetricAnomalyDetector.MetricName,StateValue]' --output table
```

Nas primeiras 2 semanas, é normal que os alarmes fiquem em `INSUFFICIENT_DATA`, sem nenhuma ordem na devnet, ou que mudem de estado com poucas ordens. Eles só passam a valer como sinal depois desse período.

## Sources

- `observability-setup-questions.md` (Q7).
- `construction/u3-real-risk/nfr-requirements/observability-requirements.md` (`real_risk_evaluations_total`) e `construction/u4-real-execution/nfr-requirements/observability-requirements.md` (`real_orders_total`).
- `infra/terraform/modules/observability/anomaly.tf` e `app-metrics.tf`.
