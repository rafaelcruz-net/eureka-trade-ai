# Desenho de escalabilidade — U4 `u4-data-requests`

Conversa: português (Brasil). Implementa `construction/u4-data-requests/nfr-requirements/scalability-requirements.md` (NFR3.6 a NFR3.8).

| Requisito | Desenho |
|---|---|
| NFR3.6 | O volume do beta é pequeno; os testes usam 1000 pedidos |
| NFR3.7 | A trava ShedLock `dataRequestTimers` (job de 15 minutos) tem `lockAtMostFor` de 5 minutos e `lockAtLeastFor` de 1 minuto: uma queda no meio pula no máximo um ciclo. A trava `dataSubjectRequestPurge` (diária) tem 35 e 5 minutos, como os expurgos da U2. As atualizações condicionais com `RETURNING` garantem um único aviso e uma única recusa, mesmo se dois processos rodarem |
| NFR3.8 | O índice único parcial `uq_open_request_per_type` em `(tenant_id, user_id, type) WHERE status IN ('RECEIVED','AWAITING_VERIFICATION','IN_PROGRESS') AND user_id IS NOT NULL` transforma a corrida em `DuplicateKeyException`, convertida em `409 DATA_REQUEST_ALREADY_OPEN` |

O pool do agendador da API passa de 18 para 21, com três `@Scheduled` novos: o job de 15 minutos, o expurgo e o refresher de 60 s das métricas. Mudam juntos:
- `spring.task.scheduling.pool.size` em `application.yml`;
- `RuntimeSettingsGuard.MIN_SCHEDULER_POOL`, que passa a 21;
- o padrão `SCHEDULER_POOL_SIZE:-21` do Compose;
- o intervalo "21 to 32 [21]" do `.env.example`;
- os testes que fixam o valor.
