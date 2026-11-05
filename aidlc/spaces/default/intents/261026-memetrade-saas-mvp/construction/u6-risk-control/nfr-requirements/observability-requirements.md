# Requisitos de observabilidade — U6 Barreira de limites

Requisitos de métricas, logs e auditoria da Unit `u6-risk-control`. As fontes são o NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md), o contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e a resposta Q4 de [nfr-requirements-questions.md](nfr-requirements-questions.md). A U6 publica só as métricas de saúde e de erro exigidas pelo NFR9 (Q4).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Métrica de saúde | Indicador de saúde `riskcontrol` no actuator: `UP` quando a última rodada do expurgo terminou com sucesso há menos de 26 horas ou ainda não houve rodada desde o início do processo; `DOWN` com o motivo, sem dado de pessoa, caso contrário | NFR9, Q4 |
| NFR9.2 | Métrica de erro | Contador `riskcontrol.evaluation.errors` das falhas técnicas de `evaluate`, com o rótulo `cause` (`MARKET_DATA`, `PORTFOLIO`, `DATABASE`, `OTHER`) e nenhum rótulo de pessoa, bot ou token; e contador `riskcontrol.purge.errors` | NFR9, Q4 |
| NFR9.3 | Correlação | Todo log da U6 traz o identificador de correlação do ciclo (do worker) ou da requisição (da API) | NFR9 |
| NFR9.4 | Auditoria das recusas | Cada recusa gera exatamente um evento `ORDER_REJECTED` na trilha (C9), com ator `system`, tenant, `targetRef` = bot e detalhes {`rejectionId`, `decisionId`, `reasons`, `pauseTriggers`, `limitsVersion`}. A aprovação não gera evento (o evento da operação é da U7). Teste conta os eventos por caso do catálogo | BR3.5, DP-2 |
| NFR9.5 | Logs de nível certo | Recusa: `INFO` com `rejectionId`, `botId` e o motivo principal. Falha técnica: `ERROR` com a causa e o identificador de correlação, sem stack trace na resposta. Expurgo: `INFO` com o total apagado por rodada | `team.md` (sem falha silenciosa) |

## Fora do escopo do beta

- Contadores por motivo e histograma do tempo de `evaluate` (Q4). O tempo é medido no teste de carga (NFR2.1), não por métrica permanente.
- Alertas automáticos, enquanto o beta roda só localmente.
