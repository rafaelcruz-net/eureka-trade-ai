# Requisitos de observabilidade — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/functional-design/functional-spec.md`, `inception/requirements-analysis/requirements.md` (NFR5) e `nfr-requirements-questions.md` (Q5).

## Métricas

| ID | Métrica |
|---|---|
| NFR5.1 | `marketdata_provider_calls_total{source,kind}`, com o percentual do orçamento mensal em `marketdata_provider_budget_ratio` |
| NFR5.2 | `marketdata_round_seconds{source,kind}`, a duração da rodada de preços, da descoberta e dos detalhes |
| NFR5.3 | `marketdata_source_state{source}`, o estado da fonte (PENDING, OK, DEGRADED, FAILED) |
| NFR5.4 | `marketdata_invalid_values_total{field}` |
| NFR5.5 | `marketdata_active_tokens` e `marketdata_oldest_price_age_seconds` |

## Alertas

| ID | Condição |
|---|---|
| NFR5.6 | Fonte FAILED por mais de 2 min |
| NFR5.7 | Orçamento mensal a 80% |
| NFR5.8 | Mais de 5% de valores inválidos numa hora |
| NFR5.9 | Preço mais velho acima de 60 s em 3 rodadas seguidas (o REAL vai recusar ordens) |

## Logs

| ID | Requisito |
|---|---|
| NFR5.10 | Logs em JSON com `correlationId` por rodada. Sem a chave e sem o corpo bruto da resposta. Um valor inválido registra só o campo e o motivo |
