# Monitoramento — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-design/observability-design.md`, `construction/u2-real-market-data/nfr-requirements/observability-requirements.md` e `reliability-requirements.md`, `construction/u1-bot-wallet/infrastructure-design/monitoring-design.md` (SNS, CloudWatch e o alerta de worker fora do ar) e `infrastructure-design-questions.md` (Q4). Revisado depois da primeira revisão.

## Metrics & KPIs

| Metric | Source | Threshold | Why it matters |
|---|---|---|---|
| `marketdata_provider_calls_total{source,kind,outcome}` | worker | `outcome="retry"` acima de 10% do total numa hora | instabilidade do fornecedor |
| `marketdata_provider_budget_ratio{source}` | worker | ≥ 0,8 | cota mensal |
| `marketdata_provider_conservative_mode{source}` | worker | 1 por mais de 5 min | contador indisponível |
| `marketdata_round_seconds{source,kind}` | worker | p95 acima de 10 s | desempenho da rodada |
| `marketdata_source_state{source,state}` | worker | medidor que vale 1 no estado atual e 0 nos outros; alerta com `state="FAILED"` = 1 | fonte fora do ar |
| `marketdata_observations_total{field}` e `marketdata_invalid_values_total{field}` | worker | razão inválidos / observações acima de 5% numa hora | qualidade do dado |
| `marketdata_oldest_price_age_seconds` | worker | acima de 60 s em 3 rodadas | o REAL recusa ordens |
| `marketdata_price_fresh_minute` (regra de gravação) | Prometheus / CloudWatch | — | o valor por minuto é 1 se a idade ≤ 60 s e 0 se passar, base do SLO |

## Alerts

| Alert | Condition | Severity | Routes to |
|---|---|---|---|
| Fonte de mercado em falha | `marketdata_source_state{state="FAILED"}` = 1 por mais de 2 min | alta | SNS: e-mail e SMS |
| Preço velho para o REAL | idade acima de 60 s em 3 rodadas seguidas | alta | SNS: e-mail e SMS |
| Worker fora do ar (da U1) | sem métricas do worker por 2 min. Cobre o caso em que nenhuma métrica da U2 chega | crítica | SNS: e-mail e SMS |
| D-A2 Contador indisponível | modo conservador por mais de 5 min | alta | SNS: e-mail e SMS |
| Orçamento mensal | ≥ 80% | média | e-mail |
| Valores inválidos | razão acima de 5% numa hora | média | e-mail |
| D-A1 Fornecedor instável | repetições acima de 10% numa hora | média | e-mail |

- As regras ficam em `infra/observability/`, validadas com `promtool`.
- Os alarmes ficam no módulo `observability` do Terraform.
- Um teste confere que os limites dos alarmes do Terraform são os mesmos das regras do `promtool`, lendo os dois arquivos e comparando nome, limite e janela [Q4].

## SLIs / SLOs

| SLI | SLO target | Measurement window |
|---|---|---|
| `avg_over_time(marketdata_price_fresh_minute[30d])` | 0,99 | 30 dias corridos |

## Logs & Tracing

- Logs em JSON, com `correlationId` por rodada.
- Os logs nunca levam a chave nem o corpo da resposta.
- Na nuvem, os logs ficam no CloudWatch Logs por 30 dias.

## Custo do monitoramento

Cerca de 9 métricas e 7 alarmes custam aproximadamente US$ 4 por mês no CloudWatch. Os logs da U2 são pequenos. Esse valor entra na hospedagem, não no teto do NFR9.
