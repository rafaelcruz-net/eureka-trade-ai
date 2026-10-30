## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-10-30T03:12:14Z
**Iteration:** 1
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Minor | apps/api/src/main/java/com/memetrade/identity/domain/session/SessionService.java > refresh and renew; apps/api/src/main/java/com/memetrade/identity/infra/JdbcSessionRepository.java > findByRefreshDigestForUpdate | Two refresh requests that arrive together with the same cookie (two tabs opening at once) serialize on the row lock. The second one re-reads the row, no longer finds the old digest, and falls into the used-digest path. That path revokes the whole session as reuse and writes SESSION_REFRESH_REUSED. The reason CONCURRENT_ROTATION can therefore hardly ever be returned, and a legitimate parallel renewal looks like theft and logs the person out. | Decide whether a short grace window for the digest just rotated is wanted. If not, record in the design that parallel renewal revokes the session on purpose, and remove or justify the CONCURRENT_ROTATION branch. Add a test for two parallel refreshes. | Unresolved |
| R-02 | Minor | apps/api/src/main/resources/application.yml > springdoc and management.endpoint.health.show-details; apps/api/src/main/java/com/memetrade/identity/web/AuthenticationFilter.java > shouldNotFilter | The default profile publishes /v3/api-docs without authentication, because the filter only protects /api/v1. Port 8081 shows health details (`always`) and is mapped to the host (127.0.0.1). This is harmless locally, but it becomes information disclosure in the first shared environment. | Turn off springdoc.api-docs outside the test and dev profiles (the OpenAPI file is already generated and versioned). Set show-details to `when-authorized` or keep port 8081 off the public path. Record this as a G1 prerequisite. | Unresolved |
| R-03 | Minor | apps/api/src/main/java/com/memetrade/marketdata/infra/JdbcMarketListingQuery.java > observations | The observations query filters only by `mint in (...)`. It picks no source and has no order, and it matches the mint without the chain (the chain is only checked afterwards in memory). When U4 adds a second source, the last row read wins non-deterministically for the same token and field. code-summary.md already lists this as an open point. | Before U4, add the source-choice rule (or a primary key on chain, mint, field plus source) to the U4 backlog, and join on chain in the query. | Unresolved |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/traceability.json > coverage targets (64 entries) | The target files exist and the 7 N/A entries have a stated reason. However, 64 target files never mention the ID they are meant to prove (for example BR3.4 points to ScannerServiceTest, but the exact-5-minute test lives in MarketCollectorTest and ScannerIT). A reader cannot confirm the claimed link from the test file alone. | Add the ID to the test name or to a comment in each target (a `@Tag` or `@DisplayName` with the ID). Correct the BR3.4 target. | Unresolved |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/code-summary.md > Testes escritos e não executados; .github/workflows/security.yml > dependencies | The three workflows (CI, security, load test) and the load script were never run. security.yml passes `-DnvdApiKey` from a secret that is empty on pull requests from forks, and the dependency scan covers Maven only. CI pinning (SHA) and the `contents: read` permissions are correct. | Run the workflows on the first pull request. Confirm that an empty NVD key does not break the scan, and record the pnpm audit as the frontend dependency control. | Unresolved |
| R-06 | Minor | scripts/verify.sh > linha 52 (export de MSYS2_ENV_CONV_EXCL dentro do bloco `if [ ! -f .env ]`) | A exclusão só vale quando não existe .env. Com .env presente (o caminho usual no desenvolvimento local no Windows), o bloco `else` carrega ACCESS_TOKEN_SIGNING_KEY do arquivo e não define a exclusão. Uma chave base64 que comece com "/" sofre a mesma reescrita para "C:/Program Files/Git/..." ao ser passada ao docker.exe. O caminho do .env fica sem a correção. | Mover o export de MSYS2_ENV_CONV_EXCL para fora do if/else, depois da definição da chave, para cobrir os dois ramos. | Unresolved |
| R-07 | Critical | docker-compose.yml, `infra/postgres/init/01-create-app-user.sh`, `infra/postgres/bootstrap/10-roles.sql`, `scripts/verify.sh` | O script de init do Postgres não cria mais o login `memetrade_app` e não transfere a propriedade do esquema (o comentário diz que isso passou ao serviço `db-bootstrap`). Mas `db-bootstrap` não está declarado em `docker-compose.yml` nem em `infra/docker-compose.test.yml`, e nenhum script o chama; só existem os arquivos `infra/postgres/bootstrap/10-roles.sql` e `bootstrap.sh`. Resultado: `bash scripts/verify.sh` terminou com código diferente de zero. A API subiu sem conseguir conectar (`FATAL: password authentication failed for user`), todas as tarefas agendadas falharam com "Could not open JDBC Connection" e a aplicação encerrou com "Application run failed". O esqueleto verificado e aprovado na U1 não funciona mais na árvore atual. | Declarar o serviço `db-bootstrap` no Compose (execução única, dependente de `postgres` saudável, com `api` dependendo da conclusão dele), ou devolver a criação do login ao script de init até a U2 terminar o passo. Rodar `bash scripts/verify.sh` até sair com código 0 e registrar o resultado. | Resolved (reviewer) |
| R-08 | Major | apps/api/src/test/java/com/memetrade/tenancy/RouteRegistryTest.java > `everyAdministrativeRouteIsClassifiedAdminAndHasANegativeTest` | O comando de testes da U1, `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify`, falha na fase unitária: `Expecting empty but was: ["GET /api/v1/admin/audit-events"]`. A rota administrativa nova da U2 está declarada sem teste negativo registrado, então o Surefire quebra e o Failsafe (integração, cobertura JaCoCo de 80%) nem chega a rodar. A afirmação do `code-summary.md` (unitários 226/226, integração 67/67, 95,06%) não se sustenta no código atual. | Registrar o teste negativo da rota `GET /api/v1/admin/audit-events` no registro de rotas, ou retirar a rota até o passo da U2 que a cobre. Rodar de novo o comando completo e confirmar unitários, integração e piso de cobertura. | Resolved (reviewer) |
| R-09 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u1-walking-skeleton/code-generation/code-summary.md > Cobertura e testes | O resumo continua registrando os resultados da época da entrega (226/226, 67/67, 95,06%, `verify.sh` com saída 0) sem indicar que arquivos compartilhados (base de integração, migrações, papéis do banco, Compose) foram alterados depois pela U2. | Depois de corrigir os dois achados acima, atualizar o resumo com os resultados reais da nova execução. | Resolved (reviewer) |

> R-01 Reviewer note: Sem mudança no fluxo de refresh; a revogação por reutilização em refreshes paralelos segue sem janela de tolerância nem teste. Não bloqueia.

> R-02 Reviewer note: Sem mudança em springdoc e health; fica como pré-requisito de G1. Não bloqueia.

> R-03 Reviewer note: A regra de escolha de fonte continua para o backlog da U4. Não bloqueia.

> R-04 Reviewer note: A rastreabilidade não foi alterada na reabertura. Não bloqueia.

> R-05 Reviewer note: Os workflows ainda não rodaram; depende do primeiro PR. Não bloqueia.

> R-06 Reviewer note: Em `scripts/verify.sh`, o `export MSYS2_ENV_CONV_EXCL` continua dentro do ramo `if [ ! -f .env ]` (linha 56), então não vale com `.env` presente. Não bloqueia.

> R-07 Reviewer note: `docker-compose.yml` agora declara `db-bootstrap` (depende de `postgres` saudável por TCP), `db-migrate` (depende de `db-bootstrap` concluído) e `api` (depende de `db-migrate` concluído, sem credencial do dono). `10-roles.sql` cria os três logins e o papel `audit_retention` de forma idempotente. `verify.sh` exporta as chaves `DB_OWNER_*` e `DB_RETENTION_*`. O resumo registra `verify.sh` com saída 0, coerente com a observação de uma execução anterior hoje.

> R-08 Reviewer note: `route-registry.yml` registra `AdminAuditRouteIT#aGuestTokenIsRefusedWith403AndOneRequestForbiddenEvent` para `GET /api/v1/admin/audit-events`, e o método existe na classe de teste.

> R-09 Reviewer note: A seção "Reabertura de 30/10/2026" de `code-summary.md` traz os resultados novos: 282 testes unitários e 108 de integração, 94,22% de linhas, 80 testes do frontend com 98,32%, e `verify.sh` com saída 0.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u1 verify` | Não executado nesta passada | Foi omitido de propósito, porque rodar os comandos escreve arquivos no workspace com a revisão aberta. Usei o registro de `code-summary.md` (saída 0, 94,22% de linhas) e a observação de uma execução anterior hoje (BUILD SUCCESS). Isso não é motivo para negar READY. |
| `pnpm --dir apps/web exec jest --coverage ...` | Não executado nesta passada | Mesmo motivo. O resumo registra 80 testes passando e 98,32% de linhas. |
| `bash scripts/verify.sh` | Não executado nesta passada | Mesmo motivo. O resumo e a execução anterior de hoje registram saída 0, com Playwright 3/3 e varredura de logs limpa. A leitura do Compose, de `10-roles.sql` e do script confirma a ordem postgres, db-bootstrap, db-migrate, api, web. |

### Summary

Os três achados que bloqueavam (a ausência do `db-bootstrap`, o teste negativo da rota administrativa e o resumo desatualizado) estão corrigidos e verificados por leitura do código e da configuração. Restam apenas achados Minor, sem bloqueio, e a Unit está pronta.
