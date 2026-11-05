## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-05T04:06:00Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u6-risk-control/infrastructure-design/infrastructure-specification.md > Deployment > Dimensionamento | O texto diz que hoje são 11 tarefas e que o expurgo é a 12ª. O código tem 12 métodos `@Scheduled` (por exemplo `ScannerRateLimitEvictionJob` e `ReconciliationRunLimiterEvictionJob`, além dos listados no comentário do `application.yml`). A contagem do guard (3 + 4 + 4) pode não coincidir com a dos métodos, e o pool de 12 pode ficar sem folga. | Conferir a contagem real na Code Generation e, se o expurgo for a 13ª tarefa, subir o piso e o padrão para 13. Registrar a contagem usada no comentário do `RuntimeSettingsGuard`. | New |
| R-02 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u6-risk-control/infrastructure-design/cicd-pipeline.md > Cenário de carga `u6` > Mudanças no ferramental de carga | O `load-test.sh` exporta só `LOADTEST_U6_ENABLED`. O cenário depende do `LoadtestLedgerSeed`, que lê `LOADTEST_LEDGER_PEOPLE` (20), `LOADTEST_FILLS_PER_PERSON` (0) e `LOADTEST_CYCLE_BOTS` (60). Ele também depende dos convites `SMOKE_INVITE_CODES` que o ramo `u7` gera para as 19 pessoas além da conta de smoke. Nada diz que o ramo `u6` define essas variáveis. `LOADTEST_U6_PEOPLE` duplicaria `LOADTEST_LEDGER_PEOPLE`. | Dizer que o ramo `u6` do `load-test.sh` define as variáveis `LOADTEST_LEDGER_*` e gera os convites como o `u7`, e derivar `LOADTEST_U6_PEOPLE` de `LOADTEST_LEDGER_PEOPLE` ou documentar que os dois têm de coincidir. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Pool do agendador (achado anterior) | FIXED | O pool, o piso do `RuntimeSettingsGuard`, o padrão do `application.yml`, o `docker-compose.yml` e os testes estão listados. Resta só a conferência da contagem (Minor acima). |
| Conexão de retenção (achado anterior) | FIXED | A U6 tem pool próprio `RiskRetentionDataSource` (1 conexão, 30 s, aberto só na rodada), com o mesmo login. O bean da U2 e o ArchUnit da U2 ficam intactos. Há regra de ArchUnit da U6 e o `RiskRetentionPoolIT` cobre as duas rodadas e a rodada concorrente com a purga da trilha. |
| Cenário de carga (achado anterior) | FIXED | Há gatilho do expurgo (`LoadtestRiskPurgeTrigger` com ShedLock), variáveis `LOADTEST_U6_*` no overlay, contagem de linhas (270.000 recusas, 405.000 detalhes, 200.000 aprovadas antigas), duas janelas na mesma execução, mudanças em `load-test.sh`, `load-test.mjs` e `load-test.yml`, e a regra de que o limite de 900 MB só muda por decisão do responsável do projeto. Restam só as variáveis do semeador do ledger (Minor acima). |
| traceability.json (achado anterior) | FIXED | NFR4.1, 4.2, 4.5, 4.6, 7.1, 8.1, 8.2 e 9.4 foram incluídos, e NFR2.2 e NFR3.2 constam como Deferred. |
| Corte do expurgo (achado anterior) | FIXED | Seleção em 31 dias e gatilho em 30 dias no mesmo `now()`. Os testes usam 29, 30,5 e 32 dias, mais o `DELETE` direto da linha de 30,5 dias. |
| Login de retenção compartilhado (achado anterior) | FIXED | O isolamento está declarado como convenção sustentada por ArchUnit, com logins separados previstos para a hospedagem futura. |
| Matriz de privilégios, gatilhos, `10-roles.sql` e rollback | PASS | Sem mudança desde a revisão anterior. |
| Q1 = B e segredos | PASS | Não há smoke da U6 e não há segredo novo. |

### Summary

Os seis achados anteriores foram resolvidos de forma coerente com o código existente: pool do agendador, pool de retenção próprio, cenário de carga executável, rastreabilidade, corte do expurgo e isolamento do login. Restam dois Minors, a conferência da contagem de tarefas do agendador e as variáveis do semeador do ledger no ramo `u6`, que não bloqueiam a Code Generation.
