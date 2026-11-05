# Design de observabilidade — U6 Barreira de limites

Como a Unit `u6-risk-control` atende NFR9.1 a NFR9.5 de [observability-requirements.md](../nfr-requirements/observability-requirements.md), com as respostas Q2 e Q3 de [nfr-design-questions.md](nfr-design-questions.md).

## Métricas (só saúde e erro, Q4 dos requisitos)

| Métrica | Tipo | Rótulos | Quando sobe |
|---|---|---|---|
| Indicador `riskcontrol` | Saúde do actuator | — | Ver reliability-design.md (tabela `risk_purge_run`, fora de liveness e readiness) |
| `riskcontrol.evaluation.errors` | Contador | `cause` = `MARKET_DATA`, `PORTFOLIO`, `DATABASE`, `OTHER` | Toda falha técnica de `evaluate`, contada antes de a exceção subir |
| `riskcontrol.purge.errors` | Contador | — | Toda rodada do expurgo que termina com falha |

Nenhum rótulo leva pessoa, tenant, bot, decisão ou token.

## Logs

| Evento | Nível | Campos |
|---|---|---|
| Ordem recusada | `INFO` | `rejectionId`, `botId`, `decisionId`, motivo principal, número de motivos, gatilhos |
| Ordem aprovada | `DEBUG` | `evaluationId`, `botId`, `decisionId` |
| Falha técnica na avaliação | `ERROR` | causa, `botId`, `decisionId`; a exceção vai no log com a pilha, nunca na resposta |
| Rodada do expurgo | `INFO` | início, fim, corte, total apagado, lotes (Q2: sem evento na trilha) |
| Falha do expurgo | `ERROR` | causa curta e total apagado até a falha |

- **Correlação.** Todos os logs trazem o identificador de correlação pelo MDC. No worker, ele é o do ciclo, ligado pela U5. Na API, é o da requisição (NFR9.3).
- **Formato.** Os logs são JSON, no mesmo formato das outras Units.

## Trilha de auditoria (NFR9.4)

- **`ORDER_REJECTED`.** É publicado pela interface da C9 na transação do ciclo, um por recusa:
  - ator `system`, tenant do contexto e `targetRef` = `botId`;
  - detalhes {`rejectionId`, `decisionId`, `reasons`, `pauseTriggers`, `limitsVersion`}.
- **Aprovação.** Não gera evento.
- **Expurgo.** Não gera evento (Q2). Sua evidência é o log `INFO` e a linha em `risk_purge_run`.
- **Teste.** Conta os eventos na tabela de saída para cada caso do catálogo E1 a E21: um por recusa, zero por aprovação.

## Painéis e alertas

Não há painéis nem alertas no beta local. O relatório de carga (performance-design.md) é a fonte das medidas de tempo.
