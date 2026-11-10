# Desenho de confiabilidade — U2 `u2-retention`

Conversa: português (Brasil). Implementa `construction/u2-retention/nfr-requirements/reliability-requirements.md` (NFR5.1, NFR7.1 a NFR7.5, NFR9.1).

| Requisito | Desenho |
|---|---|
| NFR7.1 | `@Scheduled(cron = "${memetrade.identity.purge.challenges-cron:0 20 3 * * *}", zone = "UTC")` e `"...sessions-cron:0 40 3 * * *"`, só no processo da API (`@ProcessRole(API)`). O executor comum do Identity roda a trava, os lotes e, no fim, grava a execução em `identity_job_run` numa transação `REQUIRES_NEW`. Uma exceção num lote para o laço, mantém os lotes feitos, grava `FAILED` e registra `ERROR` com a classe |
| NFR7.2 | `LoginChallengePurgeHealthIndicator` e `SessionPurgeHealthIndicator` leem `identity_job_run`. A referência é o último sucesso ou, sem ele, o instante de subida do processo. Mais de 48 h dá `DEGRADED`, que responde HTTP 200 como os indicadores existentes. Os dois ficam fora dos grupos de liveness e readiness |
| NFR7.3 | `IdentityJobRecovery`, no `ApplicationReadyEvent`, roda pelo executor comum (com a trava) cada expurgo sem linha, sem sucesso ou com sucesso há mais de 24 h, como o `AuditJobRecovery` |
| NFR7.4 | O job roda fora da thread de requisição. As exceções são capturadas no executor, registradas e convertidas em `FAILED`, sem propagar para o agendador nem para a API. No fim, o evento `*_PURGED` e a linha de `identity_job_run` saem juntos numa transação curta `REQUIRES_NEW`: o publicador exige transação ativa, e assim a contagem e o registro ficam consistentes. Se essa transação falhar, o executor registra `ERROR` com a classe, e o que já foi apagado fica apagado. Como no `AuditJobRunner.runScheduled`, o método agendado captura `DataAccessException` da trava do ShedLock e registra a falha sem propagar |
| NFR7.5 | A validação de `memetrade.audit.wallet-hmac-key` falha a subida com `BindValidationException`, que nomeia a propriedade e nunca mostra o valor |
| NFR5.1 | A migração `0012-retention.sql` (Liquibase) tem só `ALTER TABLE ... ADD COLUMN`, `CREATE INDEX` e `CREATE TABLE`, cada uma com o rollback dela. Um teste lê o arquivo e falha se encontrar `UPDATE`, `DELETE` ou `DROP` fora do bloco de rollback |
| NFR9.1 | O plano de código põe primeiro os testes de BR2.5, BR2.6 e BR2.8 (relógio injetável e Testcontainers). O teste de conformidade entra na suíte padrão. O piso continua 80% no JaCoCo |

## Degradação

Se o expurgo falhar, a API continua servindo. A saúde mostra o atraso depois de 48 h, e a recuperação na subida tenta de novo.
