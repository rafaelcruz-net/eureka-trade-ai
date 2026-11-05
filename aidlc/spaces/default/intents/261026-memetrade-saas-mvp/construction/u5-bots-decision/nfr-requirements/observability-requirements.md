# Requisitos de observabilidade — U5 Bots e decisão

Requisitos de métricas, logs e auditoria da Unit `u5-bots-decision`. As fontes são o NFR9 de [requirements.md](../../../inception/requirements-analysis/requirements.md) e as respostas Q5 e Q6 de [nfr-requirements-questions.md](nfr-requirements-questions.md). A U5 publica só a saúde e o erro exigidos (Q5).

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR9.1 | Saúde do worker | Indicador `botCycles` no worker: `DOWN` com o motivo quando nenhuma fatia termina há mais de 10 minutos e há bots ativos; fora de liveness e readiness (Q6), só no detalhe | NFR9, Q6 |
| NFR9.2 | Erro do ciclo | Contador `bots.cycle.errors` com rótulo `cause` (`MARKET_DATA`, `RISK_GATE`, `EXECUTION`, `DATABASE`, `OTHER`); nenhum rótulo de pessoa, bot ou token | NFR9, Q5 |
| NFR9.3 | Correlação | Cada ciclo ganha um identificador de correlação (`cycleId`) no MDC, levado à U6, à U7 e à trilha; nas rotas, o da requisição | NFR9 |
| NFR9.4 | Auditoria | Um evento por comando e por mudança automática: `BOT_CREATED`, `BOT_STARTED`, `BOT_PAUSED` (com motivo), `BOT_RESUMED`, `BOT_DAILY_LOSS_CLEARED`, `BOT_LIMITS_CHANGED`, `BOT_STOPPED`, `BOT_CLOSING_PENDING`, `BOT_SYSTEM_PAUSE_LIFTED`; ator `system` nas automáticas; nenhum evento em repetição idempotente | DP-2, BR2, BR7 |
| NFR9.5 | Logs | Ciclo `INFO` com `cycleId`, `botId`, ação e resultado; falha `ERROR` com a causa; nunca dados de carteira | `team.md` |
