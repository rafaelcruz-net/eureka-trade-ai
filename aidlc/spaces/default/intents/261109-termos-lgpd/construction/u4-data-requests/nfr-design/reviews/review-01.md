## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T10:53:07Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-design/observability-design.md > NFR8.1 e NFR8.2 (gauges em cache) | Os gauges `open` e `overdue` e o indicador `dataRequests` leem um valor em memória atualizado "pelo job de 15 minutos e a cada transição". Com várias instâncias (NFR3.7 supõe isso), o ShedLock faz o job rodar em uma só instância, então as demais nunca recarregam o cache e só enxergam as próprias transições. Depois de uma subida o cache também começa em zero, e a saúde fica `UP` e o alerta `DATA_REQUEST_OVERDUE` não dispara com um pedido já vencido. O gauge de vencidos é a base do alerta legal (prazo de 15 dias). | Desenhar o refresco do cache de modo que cada instância o tenha correto: carga na subida (como a recuperação do expurgo faz) mais atualização por instância fora da trava (ou leitura no banco com TTL curto). Registrar qual das opções vale e o teste correspondente (duas instâncias, uma subida com vencido aberto). | New |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-design/security-design.md > NFR2.4 e performance-design.md > NFR3.1 (criação) | O desenho nunca diz como os `POST` de P1 e P2 usam o mecanismo comum de idempotência (`IdempotentOperation` em `identity/api`, que hoje não tem entradas de pedidos de titular), embora o contrato exija `Idempotency-Key` e a regra do projeto mande usar o mecanismo comum. Falta também a ordem entre a idempotência e `DataRequestRateLimit`: se a contagem da hora rodar antes da consulta da chave, a repetição de um pedido já criado conta de novo no limite de 10 por hora e pode receber `429` em vez da resposta guardada. A criação concorrente também faz contagem e `INSERT` sem atomicidade, então duas requisições com a contagem em 9 passam. | Listar as novas entradas de `IdempotentOperation` (criação do convidado e as operações de P2) com a retenção. Fixar que a consulta da chave vem antes do limite, que o `429` não consome a chave e que o limite é um teto aproximado com a corrida aceita (ou serializado, por exemplo com `pg_advisory_xact_lock` por pessoa). | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-design/scalability-design.md > NFR3.7 (travas e pool) | A trava `dataRequestTimers` usa `lockAtMostFor` de 35 minutos, copiado do expurgo diário, para um job de 15 minutos que termina em até 30 s: uma queda no meio da execução pula dois ciclos de vencimento. O texto do pool diz "de 18 para 20" mas não diz se o mínimo `MIN_SCHEDULER_POOL` do `RuntimeSettingsGuard` também sobe para 20 (hoje é 18, com o Javadoc listando 18 jobs). | Reduzir o `lockAtMostFor` do job de 15 minutos (por exemplo, 10 minutos) e declarar que `MIN_SCHEDULER_POOL`, o Javadoc e o teste do guard passam para 20 junto com o padrão do `application.yml`. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-design/reliability-design.md > NFR7.1 (expurgo) | O desenho do `DataSubjectRequestPurgeJob` não diz se emite o evento de contagem `*_PURGED` (como o `IdentityJobRunner` faz, na mesma transação do registro em `privacy_job_run`) nem como o `PrivacyJobRunner` reaproveita o padrão sem o Identity. | Declarar o evento de expurgo (só contagens, sem tenant) na transação do registro, e listar as ações novas no `AuditActions`. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Verificação no código (`RuntimeSettingsGuard`, `application.yml`, `IdentityJobRunner`, `IdempotentOperation`, `RetentionCatalog`, `SecurityHeadersFilter`, `AdminRouteFilter`) | Pool de 18 e `Enforcer.planned("u4-data-requests")` conferem; `Cache-Control: no-store` global existe; `/api/v1/admin/**` já passa pelo filtro `ADMIN_ROUTE`; `privacy/` não tem executor de jobs ainda | A base do desenho é consistente com o código. As lacunas estão na idempotência e no cache dos gauges |
| traceability.json | 25 NFRs cobertos, NFR8.3 marcado `Deferred` para o Infrastructure Design | Cobertura completa e coerente com a origem |

### Summary

O desenho cobre todos os NFRs de forma implementável e coerente com o código existente. Há dois pontos Major a corrigir, ambos dentro do limite para READY: o cache dos gauges é incorreto com várias instâncias, e a idempotência dos `POST` com o limite por hora não está definida.
