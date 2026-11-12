## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-11T20:14:41Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/code-generation/code-summary.md > "Falha de DashboardOpenLatencyIT" | O `verify` completo termina com 1 falha: `DashboardOpenLatencyIT` (grupo BOTS, p95 acima do limite de 2 s). Reexecutei o teste isolado e ele falhou de novo (p95 de 2,31 s, 76 s de execução). O resumo atribui a falha à carga da máquina e afirma que a trava não é a causa, mas a evidência é uma única medição sem a trava, também acima do limite, e não há medição da base anterior à U3. Hoje a abertura do painel faz uma consulta extra por requisição de convidado, e o limite de 2 s é uma meta mensurável que não pode ser relaxada. | Antes do ponto de controle, medir o mesmo teste na base sem a U3 (ou com a trava desligada) na mesma máquina e registrar os dois p95. Se a base passar e a U3 não, tratar como defeito da trava. Se ambas falharem, registrar o motivo ambiental com a evidência e confirmar o teste verde no Build and Test. | Accepted risk |
| R-02 | Minor | apps/api/src/main/java/com/memetrade/identity/web/TermsReacceptanceFilter.java > doFilterInternal (bloco `catch`) | O filtro só converte `DataAccessException` em 503 pelo `ProblemWriter`. Qualquer outra exceção de runtime (por exemplo, falha em `terms.current()`) sai do filtro sem passar pelo `ProblemWriter`: a requisição não passa sem a checagem (falha fechada), mas a resposta é o 500 padrão do contêiner, sem corpo RFC 7807 e sem `correlationId`. | Cobrir também falhas inesperadas de `terms.current()`/`lookup` com 503 pelo `ProblemWriter` (relançando com contexto e sem dado pessoal no log), ou registrar por escrito que o 500 padrão é aceito. | Accepted risk |
| R-03 | Minor | aidlc/spaces/default/intents/261109-termos-lgpd/construction/u3-reacceptance/functional-design/rules.md > BR3.3 e apps/api/src/main/java/com/memetrade/bots/web/BotController.java > `POST /api/v1/bots/{botId}/commands/{command}` | Toda rota de bot do convidado fica bloqueada durante a trava (decisão do desenho), inclusive o comando de pausa, enquanto os bots ativos seguem operando no worker. Com a trava ativa, a pessoa não consegue pausar o próprio bot pelo app. Não é desvio do desenho, mas é uma consequência de segurança que o desenho não discute. | Confirmar com o responsável do projeto que bloquear a pausa durante a trava é intencional, ou liberar o comando de pausa no desenho. Registrar a decisão. | Accepted risk |
| R-04 | Minor | apps/api/src/main/java/com/memetrade/identity/infra/TermsReacceptanceWarmup.java > warmUp() and awaitAll() | When only some connections fail, the log says "failed" and warmUp returns 0, although the other connections were warmed. The count is only used by the tests and the INFO log, so there is no functional effect. | Optionally return or log the number of connections warmed together with the failure; not blocking. | New |
| R-05 | Minor | apps/api/src/main/resources/application.yml > spring.datasource.hikari.minimum-idle | The pool is now fixed at DB_POOL_MAX_SIZE (default 20, up to 50 by the guard). The Postgres max_connections budget for several API replicas is not re-stated in this Unit's artifacts; the comment only says docker-compose already covers 20. | Record in code-summary.md or the infrastructure notes that the budget holds up to the guard maximum (50) per replica, or accept it as the local-only deployment today. | New |

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `mvnw verify -Dtest='TermsReacceptanceWarmup*Test' -Dit.test='TermsReacceptanceWarmup*IT,ProcessRoleContextIT'` | Ran (quiet mode); logs show warm-up on 4 connections, the WARN path with `SQLException` only, and no failure lines | Warm-up unit tests, warm-up ITs and the process-role IT pass |
| `node --test scripts/load/load-test-reacceptance.test.mjs` | 13 pass, 0 fail | The load script, including the acquire reporting and the missing-samples case, behaves as designed |
| Conductor evidence (not rerun) | Full verify BUILD SUCCESS (1326 unit + 949 integration tests, JaCoCo met); `reacceptance` scenario passes with checkP95Ms 4.194, acquireP95Ms 1, acceptP95Ms 44.739 | The measured 4.194 ms is under the unchanged 5 ms limit, but the margin is small, so the target should stay watched in later runs |

### Summary

The loop-back repair is implementable and sound: it warms the real lock query path outside the measured timer, keeps no state, fails open on startup and closed on the lock, and does not weaken the 5 ms target. There are no Critical or Major findings, only two Minor observations.
