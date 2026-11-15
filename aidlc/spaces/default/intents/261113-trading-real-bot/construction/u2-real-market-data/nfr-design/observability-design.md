# Desenho de observabilidade — U2 `u2-real-market-data`

Conversa: português (Brasil). Fontes: `construction/u2-real-market-data/nfr-requirements/observability-requirements.md` (NFR5.1 a NFR5.10), `reliability-requirements.md` (NFR4.1) e `nfr-design-questions.md` (Q4, Q5).

## Métricas (Micrometer)

Os rótulos nunca levam a chave nem o mint.

- `marketdata_provider_calls_total{source,kind,outcome}`, com `kind` = price, discovery, detail ou usage e `outcome` = ok, retry, error, limited.
- `marketdata_provider_budget_ratio{source}`.
- `marketdata_provider_conservative_mode{source}`, que vale 0 ou 1.
- `marketdata_round_seconds{source,kind}`.
- `marketdata_source_state{source}`.
- `marketdata_invalid_values_total{field}`.
- `marketdata_active_tokens`.
- `marketdata_oldest_price_age_seconds`.

## Alertas

As regras ficam em `infra/observability/`, no Prometheus local, e no CloudWatch na nuvem.

| Alerta | Condição | Severidade |
|---|---|---|
| Fonte de mercado em falha | estado FAILED por mais de 2 min | alta |
| Orçamento mensal | proporção ≥ 0,8 | média |
| Valores inválidos | mais de 5% numa hora | média |
| Preço velho para o REAL | idade acima de 60 s em 3 rodadas seguidas | alta |
| D-A1 Fornecedor instável (alerta do desenho, ligado a NFR3.6) | repetições acima de 10% das chamadas numa hora | média |
| D-A2 Contador indisponível (alerta do desenho, ligado a NFR3.7) | `conservative_mode` = 1 por mais de 5 min | alta |

## SLI

Fração dos minutos do mês com `marketdata_oldest_price_age_seconds` ≤ 60, contra a meta de 99% (NFR4.1).

## Logs

Em JSON, com `correlationId` por rodada, sem a chave e sem o corpo da resposta.
