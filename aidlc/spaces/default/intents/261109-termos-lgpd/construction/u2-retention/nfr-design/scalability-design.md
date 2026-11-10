# Desenho de escalabilidade — U2 `u2-retention`

Conversa: português (Brasil). Implementa `construction/u2-retention/nfr-requirements/scalability-requirements.md` (NFR3.6 a NFR3.8).

| Requisito | Desenho |
|---|---|
| NFR3.6 | O lote de 1000 e uma execução por dia atendem o volume do beta com folga. O teste de 100 mil linhas usa 1000 vezes o volume diário |
| NFR3.7 | Cada job usa o `LockingTaskExecutor` do ShedLock com as travas `loginChallengePurge` e `sessionPurge`, `lockAtMostFor` de 35 minutos e `lockAtLeastFor` de 5 minutos, como o `AuditJobRunner`. A tabela `identity_job_run` guarda a execução, e os indicadores de qualquer instância leem essa tabela |
| NFR3.8 | Índice `audit_event_wallet_hmac_idx` sobre `wallet_hmac`, parcial (`WHERE wallet_hmac IS NOT NULL`), criado pela migração da Unit |

## Pool do agendador

Os dois `@Scheduled` novos entram no processo da API, que hoje tem um thread por método (16). As mudanças:
- `spring.task.scheduling.pool.size` passa de 16 para 18 em `application.yml`, e o comentário da contagem é atualizado;
- `RuntimeSettingsGuard.MIN_SCHEDULER_POOL` passa de 16 para 18;
- os testes que fixam 16 (`U4ContextIT`, `U5SettingsTest` e `AuditPropertiesTest`) são ajustados para 18 na geração de código.

## Estado compartilhado

Nenhum estado fica em memória entre instâncias. A trava e a última execução ficam no PostgreSQL.
