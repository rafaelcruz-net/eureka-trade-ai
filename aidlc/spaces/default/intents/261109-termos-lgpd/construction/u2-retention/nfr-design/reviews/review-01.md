## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T03:48:48Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-design/scalability-design.md > NFR3.7 e reliability-design.md > NFR7.1 | Os dois `@Scheduled` novos entram no processo da API, mas o pool do agendador já está dimensionado para os 16 métodos existentes (`spring.task.scheduling.pool.size` = 16 em `application.yml`, com o comentário "um thread por método @Scheduled", e `RuntimeSettingsGuard.MIN_SCHEDULER_POOL` = 16). O desenho não menciona o pool. Com 18 métodos em 16 threads, um expurgo longo disputa thread com o relay da auditoria e com os outros jobs, e os testes que fixam 16 (`U4ContextIT`, `U5SettingsTest`, `AuditPropertiesTest`) quebram quando o dimensionamento for corrigido só na hora do código | Registrar no desenho: o pool padrão da API passa a 18 em `application.yml`, o mínimo de `RuntimeSettingsGuard` passa a 18, o comentário de contagem é atualizado, e os testes que fixam 16 entram na lista de testes a ajustar no Code Generation | New |
| R-02 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-design/reliability-design.md > NFR7.1 e observability-design.md > NFR8.4 | O `JdbcAuditEventPublisher.publish` exige transação ativa (propagação MANDATORY) e lança `IllegalTransactionStateException` sem ela. O desenho manda publicar `LOGIN_CHALLENGES_PURGED` e `SESSIONS_PURGED` e gravar `identity_job_run` em `REQUIRES_NEW`, mas não diz em qual transação o evento sai, nem o que acontece com o resultado se a publicação ou a gravação falhar com o banco indisponível (NFR7.4). Também falta dizer que a falha da própria trava (`DataAccessException` do ShedLock) é capturada, como o `AuditJobRunner.runScheduled` já faz | Especificar que o evento e a linha de `identity_job_run` saem na mesma transação curta `REQUIRES_NEW`, que a falha dessa transação vira log `ERROR` com a classe (sem mudar o resultado já apagado) e que o agendador captura `DataAccessException` da trava | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-design/security-design.md > NFR1.1 | O `walletHmac` viaja no payload do `audit_outbox`, mas o relay lê o payload por `AuditPayloadCodec.decode`, que devolve um `AuditEventCommand`, e esse comando não serializa o campo `wallet`. O desenho não diz como o HMAC chega do `decode` ao `insertIntoTrail`. Mudar o codec e o tipo devolvido é implementável, mas fica por adivinhação | Nomear a mudança: o `AuditPayloadCodec` passa a devolver o comando junto com o `walletHmac` (por exemplo, um registro do Audit) e o `insertIntoTrail` grava a coluna; registrar também a ampliação do construtor canônico de `AuditEventCommand`, usado por todos os módulos | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-design/security-design.md > NFR1.2 | O padrão "Base58 de 32 a 44 caracteres delimitado" não diz quais são os delimitadores e não usa a confirmação por decodificação que a checagem de assinatura existente faz (`BASE58_RUN` mais `decodedLength` de 64 bytes). Um identificador longo legítimo em `details` (por exemplo, um UUID sem hífens sem o caractere `0`) seria recusado, e como o publicador roda dentro da transação do negócio, a recusa derrubaria a operação | Definir o delimitador (qualquer caractere fora do alfabeto Base58) e exigir que a sequência decodifique para 32 bytes, como a checagem de assinatura; cobrir com testes de valores longos legítimos | New |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u2-retention/nfr-design/observability-design.md > NFR8.1 | O gauge `memetrade.purge.last.success` é "lido de `identity_job_run`", o que faz uma consulta ao banco a cada coleta do Prometheus. O desenho não diz o que o gauge devolve com o banco indisponível nem se a leitura é em cache | Definir o comportamento (por exemplo, valor em cache atualizado ao fim de cada execução e na subida, ou `NaN` em caso de falha) e garantir que a coleta nunca lance exceção | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Verificação contra o código de `apps/api` | Confirmados: `ProcessRole`, `AuditJobRunner` (ShedLock 35/5 min), `AuditJobHealthIndicator` (DEGRADED 200), índices `login_challenge_expires_idx` e `session_used_refresh_digest_session_idx`, colunas de `user_session`, gatilho append-only de `audit_event` (a coluna nova não é afetada), última migração `0011` (`0012` livre) | Os nomes e as dependências do desenho resolvem no código. Os pontos acima são lacunas, não referências quebradas |
| Cobertura de NFR (`traceability.json`) | 29 de 29 NFR marcados OK, cada um com o alvo no desenho | Todo NFR tem um desenho concreto; a lacuna do pool de agendador não estava em nenhum NFR |

### Summary

O desenho cobre todos os NFR e bate com o código real. A única lacuna relevante é o dimensionamento do pool do agendador da API para os dois jobs novos; as demais são especificações a completar. Com um Major e quatro Minor, o desenho é implementável (READY); o pool deve ser corrigido antes ou durante o Code Generation.
