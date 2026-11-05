# Design de observabilidade — U5 Bots e decisão

Como a Unit `u5-bots-decision` atende NFR9.1 a NFR9.5 de [observability-requirements.md](../nfr-requirements/observability-requirements.md).

| Requisito | Desenho |
|---|---|
| NFR9.1 | `BotCyclesHealthIndicator` no worker: lê o último `BotCycle` terminado; `DOWN` com `NO_SLOT_COMPLETED` quando passa de 10 min e há bots ativos; fora dos grupos de liveness e readiness |
| NFR9.2 | Contador `bots.cycle.errors{cause}` somado pelo `CycleFailureRecorder` |
| NFR9.3 | O `cycleId` vai para o MDC no início do ciclo como `correlationId`, e a U6, a U7 e a tabela de saída o herdam; nas rotas, vale o filtro de correlação da U1 |
| NFR9.4 | `BotAudit` publica os eventos pela C9 na mesma transação; eventos automáticos com ator `system`; a resposta idempotente não publica |
| NFR9.5 | `INFO` por ciclo com `cycleId`, `botId`, ação e resultado; `ERROR` na falha com a causa; formato JSON |

Não há métricas de negócio, painéis nem alertas no beta (Q5 dos requisitos).
