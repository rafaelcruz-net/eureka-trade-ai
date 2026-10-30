# Requisitos de observabilidade — U4 Descoberta e avaliação

Requisitos de métricas, logs e saúde da Unit `u4-discovery`, derivados do NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos fluxos de [functional-spec.md](../functional-design/functional-spec.md) e do padrão já usado na U1 e na U2 (Micrometer com saída Prometheus, logs JSON com `correlationId`, saúde no Actuator). Sem alertas automáticos enquanto o beta roda só localmente.

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Saúde das fontes e do recálculo | Indicador `marketSources` no `/actuator/health`: `UP` com alguma fonte `OK` ou `DEGRADED`; `UNKNOWN` com todas `PENDING`; `DOWN` com todas `FAILED`. Indicador `riskRecalculation`: degradado quando o último recálculo bem-sucedido tem mais de 5 minutos. Os dois respondem HTTP 200 e ficam fora do grupo de prontidão, como na U2 | NFR9, BR1.4, BR3.7 |
| NFR9.2 | Métricas de cada fonte | `market_source_status{source}` (0 PENDING, 1 OK, 2 DEGRADED, 3 FAILED); `market_collection_total{source, result}`; `market_collection_duration_seconds{source}`; `market_collection_retries_total{source}`; `market_collection_discarded_total{source, reason}`; `market_source_last_success_timestamp_seconds{source}` | NFR9, F1, BR1.5 |
| NFR9.3 | Métricas do recálculo e da limpeza | `risk_recalculation_total{result}`, `risk_recalculation_duration_seconds`, `risk_assessments{status, band}` (contagem atual), `market_history_cleanup_deleted_total`, `market_history_cleanup_duration_seconds`, `market_history_samples` (contagem atual) | NFR9, F5, F6 |
| NFR9.4 | Métricas das rotas | Latência e contagem por rota e código de resposta pelo padrão do Spring (`http_server_requests_seconds`); `scanner_rate_limited_total{route}`; `scanner_excluded_by_unknown` (histograma por consulta) | NFR9, NFR3.1, NFR6.3 |
| NFR9.5 | Rótulos sem dado pessoal nem alta cardinalidade | Nenhum rótulo de métrica com mint, chain, pessoa, carteira ou tenant; rótulos só com fonte, resultado, motivo, rota, status e faixa | NFR7, NFR9 |
| NFR9.6 | Logs das mudanças de estado e dos trabalhos | Log `INFO` em cada mudança de estado de fonte (fonte, estado anterior, novo, categoria da falha); `WARN` em coleta `DEGRADED` com o número de descartes por motivo; `ERROR` em coleta `FAILED` depois das tentativas, só com a categoria e a classe da exceção, nunca a mensagem nem o conteúdo da resposta da fonte; `INFO` no fim de cada recálculo e limpeza, com duração e contagens. Cada execução agendada tem `correlationId` próprio | NFR9, F1, F5, F6 |
| NFR9.7 | Correlação nas requisições | 100% das requisições às rotas da U4 com `correlationId` nos logs e no `ProblemDetail` (padrão da U1) | NFR9 |
| NFR9.8 | Ativação de pesos visível | Log `INFO` na ativação com as versões anterior e nova e o ator mascarado; o evento `RISK_WEIGHTS_ACTIVATED` na trilha é a fonte da verdade | BR4.2 |

## Painel e alertas

- **Painel:** sem painel dedicado no beta local; as métricas ficam disponíveis no `/actuator/prometheus` para leitura durante o script de carga.
- **Alertas candidatos (para quando houver hospedagem):**
  - todas as fontes `FAILED` por mais de 2 minutos;
  - fonte `DEGRADED` por mais de 10 minutos;
  - recálculo sem sucesso por mais de 5 minutos;
  - p95 da lista acima de 1 s por 10 minutos.

  Ficam registrados aqui, sem implementação na U4.
