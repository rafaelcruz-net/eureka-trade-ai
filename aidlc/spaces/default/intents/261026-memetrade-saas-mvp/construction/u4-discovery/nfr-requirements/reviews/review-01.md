## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-30T18:04:11Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/nfr-requirements/observability-requirements.md > NFR9.1 | O requisito diz que os indicadores `marketSources` (DOWN com todas as fontes FAILED) e `riskRecalculation` respondem HTTP 200. A configuração real em `apps/api/src/main/resources/application.yml` (management.endpoint.health.status) só mapeia `DEGRADED: 200`, e a ordem de status põe `DOWN` como o pior. Na prática, o `/actuator/health` agregado fica `DOWN` e responde 503 quando todas as fontes falham, o que pode derrubar o healthcheck do Compose e o `scripts/verify.sh`. O critério não é verificável como escrito. | Definir como cada indicador é exposto sem afetar o health agregado: por exemplo, status personalizado mapeado para 200 ou fora do grupo usado pelo healthcheck. Acrescentar um critério testável (HTTP 200 e corpo com todas as fontes FAILED). | New |
| R-02 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/nfr-requirements/security-requirements.md > NFR6.3 | O limite de 60 por minuto diz "cada rota", o que não deixa claro se a contagem é por rota ou total por pessoa. O requisito não define a remoção de contadores ociosos (a memória do processo cresce com as pessoas) nem o limite para chamadas sem sessão, que respondem 401 antes de haver pessoa para contar. | Definir o escopo da contagem (por rota ou total), a política de remoção de entradas inativas e a ordem entre autenticação e limite. Acrescentar um teste de remoção. | New |
| R-03 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/nfr-requirements/reliability-requirements.md > NFR10.3 vs performance-requirements.md > NFR2.1 | O pior caso do NFR10.3 (3 tentativas de 5 s mais esperas de 1 s e 2 s, ou seja 18 s) excede a meta de 10 s do NFR2.1 e o intervalo mínimo configurável de 5 s. O NFR2.1 não declara que vale só para o caminho de sucesso. | Declarar que o NFR2.1 mede só a coleta sem falha e que uma coleta em andamento faz as próximas serem puladas pelo bloqueio (NFR10.7), sem acúmulo de execuções. | New |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/nfr-requirements/scalability-requirements.md > NFR2.5 | O teto de 4,7 milhões de linhas usa 288 intervalos (24 h), mas a retenção do NFR4.4 admite até 25 h (300 intervalos), ou seja cerca de 4,8 milhões de linhas, acima do teto. | Alinhar o teto à retenção de 25 h ou declarar que o limite vale logo após a limpeza. | New |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/nfr-requirements/performance-requirements.md > NFR1.2 | O limite de 60 s vale só com coleta a cada 30 s, mas o intervalo é configurável até 60 s, onde a idade chegaria a cerca de 120 s. O requisito não amarra a meta ao valor padrão. | Declarar que a meta vale para o intervalo padrão de 30 s e que outro valor exige recalcular a meta. | New |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u4-discovery/nfr-requirements/security-requirements.md > NFR6.1 | O critério "responde conforme o contrato" para token de administrador na rota do convidado não é mensurável. | Escrever o código HTTP esperado para cada papel (convidado, administrador, sem token). | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| traceability | PASS por leitura: `traceability.json` declara NFR1 a NFR14 e todos têm IDs por Unit existentes nos artefatos | Cobertura completa |
| required-sections | PASS por leitura | Os artefatos têm requisitos, critérios e origem |
| upstream-coverage | PASS por leitura | Q1 a Q5 aparecem em desempenho, segurança, escala e stack |

### Summary

Os requisitos são mensuráveis e rastreáveis, e as decisões do responsável (Q1 a Q5) estão refletidas. Há um ponto relevante a corrigir (mapeamento HTTP dos indicadores de saúde, contradito pela configuração real) e vários ajustes menores de precisão. Nada disso bloqueia o estágio.
