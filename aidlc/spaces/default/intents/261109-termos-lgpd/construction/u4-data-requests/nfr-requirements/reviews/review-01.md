## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-10T10:47:49Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-requirements/security-requirements.md > NFR2.4 (e nfr-requirements-questions.md > Q3) | O requisito pede "10 criações por hora por pessoa" usando o `PersonRouteRateLimiter` existente. Esse limitador tem janela deslizante fixa de 60 s (`WINDOW`), descarta chaves ociosas após 2 min (`IDLE`) e guarda tudo em memória do processo. Ele só expressa "N por minuto", nunca "10 por hora". Além disso, NFR3.7 prevê várias instâncias, e um contador em memória não vale entre instâncias. O requisito, como escrito, não é implementável com o componente que cita. | Escolher e escrever a meta que o componente cumpre (por exemplo, N por minuto, com N explícito) ou declarar um limitador novo com janela de uma hora, guardado em banco, e seu teste. Registrar a limitação de instância única do limitador atual como risco aceito ou resolvê-la. | New |
| R-02 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-requirements/reliability-requirements.md > NFR7.1 e tech-stack-decisions.md > Persistência | NFR7.1 deixa em aberto "`identity_job_run` ou tabela equivalente do Privacy". A tabela `identity_job_run` tem a restrição `identity_job_run_purge_ck CHECK (purge_id IN ('loginChallengePurge', 'sessionPurge'))` (migração 0012), então registrar `dataSubjectRequestPurge` nela exige alterar a restrição. Já a decisão de tecnologia diz que a migração `0014` cria só `data_subject_request` e `data_subject_verification`, e NFR5.1 diz que ela "só acrescenta". As três afirmações não fecham: ou a migração altera uma restrição da U2 (e o `IdentityJobRunner`/`PurgeRunStore` ficam no módulo Identity, que o Privacy não deveria ler), ou falta uma tabela de execução do Privacy na lista. | Decidir o armazenamento do estado do expurgo. Se for tabela do Privacy, incluí-la na migração `0014` e na lista de tabelas de tech-stack-decisions.md. Se for `identity_job_run`, dizer que a migração relaxa a restrição, e tratar isso em NFR5.1 e na fronteira de módulos. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-requirements/security-requirements.md > NFR2.4 | O contrato P1 `createDataRequest` (contract-summary.md) lista só 201, 400 e 409. A resposta `429 RATE_LIMITED` do NFR2.4 não está no contrato, e o Functional Design não a cita (BR4.19 trata só de desempenho). | Registrar a resposta 429 como aditiva em P1 (como já feito com o 409 de P2) ou anotar que o NFR cria essa mudança de contrato. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-requirements/performance-requirements.md > NFR3.5 e NFR3.2 | A verificação por `EXPLAIN` com 1000 linhas pode escolher varredura sequencial e falhar sem defeito real. O "p95 em 50 chamadas" num teste de integração depende da máquina e tende a ser instável. | Dizer que o `EXPLAIN` roda com a varredura sequencial desligada ou com volume suficiente, e que a meta de p95 é medida no cenário de carga, ficando no teste de integração só um teto folgado. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u4-data-requests/nfr-requirements/observability-requirements.md > NFR8.3 | O alerta `DATA_REQUEST_OVERDUE` é verificado por "desenho de alertas no Infrastructure Design", ou seja, não é testável nesta Unit. | Anotar como requisito de desenho entregue em outro estágio, com o gauge `memetrade_data_requests_overdue` como parte testável aqui. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Rastreabilidade NFR1 a NFR9 (traceability.json) | NFR7 mapeado em NFR7.1 e NFR7.2 (confiabilidade do expurgo, como na inception); NFR6 N/A justificado | IDs herdados corretamente da inception |
| Conferência no código (`PersonRouteRateLimiter`, migração 0012, `PurgeHealthIndicator`, ShedLock) | Janela fixa de 60 s; restrição CHECK de `purge_id` com dois valores; saúde fora de liveness e readiness confirmada; ShedLock em uso | Sustenta os dois Major; NFR8.2 e NFR3.7 estão coerentes com o código |

### Summary

Os requisitos são mensuráveis e herdam bem os NFRs da inception, mas NFR2.4 promete um limite por hora que o limitador citado não consegue dar, e NFR7.1 deixa indefinido onde o estado do expurgo mora, em conflito com a migração aditiva prevista. Com dois Major e nenhum Critical, o veredito é READY, e as duas correções devem entrar antes do Infrastructure Design.
