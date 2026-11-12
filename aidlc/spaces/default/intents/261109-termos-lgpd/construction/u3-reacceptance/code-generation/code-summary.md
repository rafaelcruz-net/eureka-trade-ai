# Resumo do código — U3 `u3-reacceptance`

Conversa: português (Brasil). Código, nomes e logs em inglês. Os passos 1 a 13 de `code-generation-plan.md` estão concluídos; o Passo 14 está parcial (ver "Testes executados"). A lista de arquivos está em `source-manifest.json` e a ligação com os requisitos em `traceability.json`.

## Arquivos criados e alterados

- **Migração `0013-reacceptance.sql`** (5 changesets, cada um com rollback):
  - `invite_id` passa a aceitar nulo;
  - coluna `source` com padrão `INVITE`;
  - `CHECK` dos valores e `CHECK` cruzado com `invite_id`;
  - restrição única `uq_terms_acceptance_user_version`.
- **Erros:**
  - códigos `TERMS_REACCEPTANCE_REQUIRED` (403) e `TERMS_VERSION_OUTDATED` (409);
  - `ProblemResponse.currentVersion` opcional, mapa de extensões em `ApiException` e o mesmo corpo no `ProblemWriter`;
  - `FilterOrder.TERMS_REACCEPTANCE` = +16.
- **Identity:**
  - portas `TermsAcceptanceLookup`, `TermsAcceptanceStore` e `TermsEvents`;
  - implementações `JdbcTermsAcceptanceLookup` (`EXISTS` com timer de histograma), `JdbcTermsAcceptanceStore` (`ON CONFLICT DO NOTHING`) e `TermsMetrics`;
  - `TermsReacceptanceFilter`: roda só no processo `api` e só para `BETA_USER`, e responde `503` pelo `ProblemWriter` se o banco cair;
  - `TermsAcceptanceService` e `TermsAcceptanceController` (`POST /api/v1/terms/acceptances`, com a idempotência comum, `IdempotentOperation.TERMS_ACCEPT`);
  - `ReacceptanceProperties` (prefixos liberados) e `AuditActions.TERMS_ACCEPTED`;
  - a Conta ganha `terms: TermsStatus`, e a rota entra no `route-registry.yml` com escopo `CLIENT`;
  - o `SmokeAccountSeeder` grava o aceite da vigente fora do retorno antecipado.
- **Contrato:** `openapi-guest.yaml` e `openapi-admin.yaml` regenerados (o esquema `Problem` é comum aos dois). O bloco L2 do resumo de contratos foi atualizado.
- **App do convidado:**
  - `/novo-aceite` com `ReacceptanceScreen`;
  - `TermsConsentBoxes`, `LegalSummary`, `TermsStatusPanel`, `ReacceptanceRedirect` e `safeReturnPath`;
  - tratador único do `403` no `guest-api`, registrado pelo `ApiProvider` e usado pelo canal ao vivo do painel;
  - `InviteScreen` e `AccountScreen` usam os componentes novos;
  - `no-store` para `/novo-aceite` no middleware;
  - tipos regenerados no web e no admin.
- **Ponta a ponta e carga:**
  - spec `apps/web/e2e/reacceptance/smoke.spec.ts`;
  - `scripts/load/reacceptance.mjs` com o teste `node --test`;
  - caso `reacceptance` em `scripts/load-test.sh` e o despacho em `load-test.mjs`.
- **Documentação:** `docs/api/u3-reacceptance.md` e o item 8 do checklist em `docs/api/u1-legal-documents.md`.

## Testes executados

| Comando | Resultado |
|---|---|
| Testes do Passo 2 antes do Passo 3 (Red) | `TermsAcceptanceServiceTest`: 4 falhas e 8 erros; `TermsReacceptanceFilterTest`: 24 erros (`UnsupportedOperationException` dos esqueletos); BUILD FAILURE |
| Os mesmos depois do Passo 3 | 36 de 36 |
| Comando backend da Unit | 58 testes de unidade e 33 de integração, todos verdes |
| `node --test scripts/load/load-test-reacceptance.test.mjs` | 10 de 10 |
| Web `lint`, `typecheck`, `format:check`, `check:api`; admin `check:api`, `typecheck`, `format:check` | todos ok |
| Web `test:coverage` | 511 de 511; 95,67% de linhas |
| `sh apps/api/mvnw -q -B -ntp -f apps/api/pom.xml clean verify` (sem `MEMETRADE_AUDIT_WALLET_HMAC_KEY` no ambiente) | unidade toda verde; integração com 827 testes e 1 falha: `DashboardOpenLatencyIT` (grupo BOTS com p95 de 2161 ms, limite de 2 s). A cobertura do backend medida nesta Unit foi de 93,83% |

**Falha de `DashboardOpenLatencyIT` (U9 do intent anterior).** Ela não vem da trava. Com as rotas do painel liberadas por configuração, sem a consulta da trava, o p95 do mesmo grupo deu 2274 ms, também acima do limite. Na U2, horas antes, o teste passou. A causa provável é a carga desta máquina (um servidor Java da IDE rodando ao lado). O limite não foi alterado. O teste volta a ser conferido no Build and Test, e uma falha lá entra como defeito.

## Testes escritos e não executados nesta etapa

- **`e2e/reacceptance/smoke.spec.ts`:** precisa do ambiente do `verify.sh`, e o responsável do projeto acrescenta o spec ao script.
- **Cenário de carga `reacceptance` contra o ambiente (AC4.2.4, NFR3.1):** fica para o Build and Test, depois que a opção entrar no `load-test.yml`.

## Desvios do plano

1. **Red sobre esqueletos.** O Red falhou nas asserções, não na compilação: os esqueletos de `TermsAcceptanceService` e do filtro lançavam `UnsupportedOperationException`.
2. **Fixtures de teste.** Mudaram para a trava não bloquear as ITs que semeiam convidados:
   - `TestAccounts` ganhou `betaUserWithoutAcceptance` e `acceptTerms`;
   - ajustes em `BotRoutesIT`, `IdentityAccessMigrationIT`, `AccountRouteIT`, `RouteRegistryTest`, `MigrationsBackwardCompatIT` (aceita só o relaxamento de `invite_id`) e nas fatias de OpenAPI.
3. **`upToDate`.** Significa "existe aceite da versão vigente", a mesma decisão da trava, e não `acceptedVersion == currentVersion`. Numa conta sem nenhum aceite, `terms` fica ausente, e o app trata isso como "não está em dia".
4. **Recarga em `/novo-aceite`.** "Tentar de novo" e a recarga depois de um `409` leem L1 pelo navegador com `cache: no-cache`, para não repetir o `409` com a versão velha.
5. **Textos do convite.** O convite mantém os textos completos dos termos e do aviso de risco (o spec u3 existente depende deles) e acrescenta o resumo e os links.
6. **Rota de volta.** `safeReturnPath` é mais rígido que o NFR2.4: recusa também `\` e a própria `/novo-aceite`.
7. **Consulta da trava.** Também filtra por `tenant_id`.
8. **Contador `INVITE`.** `memetrade.terms.acceptances{source=INVITE}` existe, mas não é incrementado na criação de conta pelo convite (código da U1, sem mudança).
9. **Testes do seeder e das rotas de bot.** O teste do seeder ficou em `SmokeAccountSeederIT`; o `403` das rotas de bot está em `TermsReacceptanceRoutesIT`.
10. **Formatação.** O `spotless:apply` rodou no módulo inteiro e pode ter só reformatado arquivos da U2 ainda não commitados.

## Volta do Build and Test (Loop-back 1, 2026-11-11): Passos 15 e 16

O Build and Test mediu o p95 da consulta da trava em 16,8, 8,4 e 7,0 ms, acima da meta de 5 ms (NFR3.2). A meta não mudou. O temporizador `memetrade.terms.reacceptance.check` continua medindo só a consulta das requisições.

- **Passo 15: aquecimento da consulta da trava.** Novo componente `TermsReacceptanceWarmup` em `identity/infra`:
  - **Quando roda:** no `ApplicationReadyEvent`, antes de a API passar a aceitar tráfego.
  - **Onde roda:** só no processo `api`, com `@ProcessRole(API)` e o mesmo `@ConditionalOnProperty(memetrade.scheduling.enabled)` do `AuditStartup` e do `IdentityJobStartup`. Por isso não roda no worker nem no perfil `migrate`.
  - **O que faz:** pega ao mesmo tempo as conexões do pool, até o `maximum-pool-size`. Em cada uma, roda 6 vezes o texto em que `JdbcTermsAcceptanceLookup.SQL` vira com os parâmetros trocados por `?`. Usa o mesmo vínculo de tipos da consulta real, UUIDs aleatórios e a versão vigente, e descarta o resultado. Com isso, o pgjdbc prepara a consulta no servidor, o que acontece depois de 5 execuções.
  - **Medição e estado:** não passa pelo `JdbcTermsAcceptanceLookup`, então não grava amostra no temporizador. Não guarda estado: os únicos campos são o `DataSource` e o `CurrentTerms`.
  - **Falhas:** cada consulta tem limite de 2 s. Qualquer falha vira um único `WARN` com a classe da exceção e nunca impede a subida.
  - **Testes:** `TermsReacceptanceWarmupTest` (9 testes de unidade, sem banco) e `TermsReacceptanceWarmupIT` (4 testes sobre o PostgreSQL de teste, com um pool próprio de 4 conexões). Eles conferem que cada conexão fica com a consulta preparada no servidor, com o texto exato da consulta da trava; que o temporizador fica sem amostra; que o componente existe na `api` e não existe no worker nem com o agendamento desligado; e que uma falha do banco ou do catálogo vira só o `WARN`. O `TermsReacceptanceWarmup` entrou na lista de trabalhos da `api` do `ProcessRoleContextIT`.
- **Passo 16: espera por conexão no cenário `reacceptance`.**
  - `application.yml` publica `hikaricp.connections.acquire` com histograma (`percentiles-histogram`), o que antes não acontecia.
  - `scripts/load/reacceptance.mjs` lê `hikaricp_connections_acquire_seconds` do pool `memetrade-pool` entre os dois instantâneos. O valor sai no relatório como `acquireP95Ms`, ao lado de `checkP95Ms`, e numa linha "só informativo" da tabela. A regra de reprovação não mudou.
  - `load-test-reacceptance.test.mjs` ganhou 3 testes:
    - lê só a série do pool da API;
    - informa o valor sem reprovar a execução;
    - registra "sem amostra" quando não há medição.

### Testes executados na volta

| Comando | Resultado |
|---|---|
| `spotless:apply` | ok |
| Testes do aquecimento: `TermsReacceptanceWarmup*Test`, `TermsReacceptanceWarmup*IT`, `ProcessRoleContextIT` e `WorkerProcessRoleContextIT` | 9 de 9 de unidade e 13 de 13 de integração; BUILD SUCCESS |
| `node --test 'scripts/load/*.test.mjs'` | 52 de 52 |
| `mvnw verify` completo, sem `MEMETRADE_AUDIT_WALLET_HMAC_KEY` | 1326 testes de unidade e 1 erro: `SecurityExceptionsTest`. O erro vem do `security/exceptions.yml` ainda não commitado do Build and Test: as datas `approvedAt`/`expires` sem aspas são lidas pelo YAML como `Date`, e `LocalDate.parse` recebe `Wed Nov 11 00:00:00 UTC 2026`. A causa não está nesta volta. O arquivo não foi alterado |
| `mvnw verify '-Dtest=!SecurityExceptionsTest,!*IT'` (unidade sem o teste acima; integração completa) | 1323 de 1323 testes de unidade. Na integração, 947 testes com 6 falhas e 27 erros, todos por falta de conexão ao PostgreSQL de teste: `remaining connection slots are reserved` e esgotamento do Hikari. A causa é o pool fixo que o Build and Test pôs no `application.yml` (`minimum-idle: ${DB_POOL_MAX_SIZE:20}`). Com ele, cada contexto Spring em cache nos testes mantém 20 conexões abertas, contra 5 antes, e o contêiner de teste tem 300 vagas (`max_connections=300`). BUILD FAILURE |
| O mesmo comando com `-Dspring.datasource.hikari.minimum-idle=5` (só propriedade de sistema da execução, sem arquivo alterado) | 1323 de 1323 testes de unidade e 949 de 949 de integração; "All coverage checks have been met"; BUILD SUCCESS |

### Observações da volta

1. **`node --test scripts/load/`.** O comando com a pasta não é aceito pelo Node 24.22.1 no Windows (`Cannot find module ...\scripts\load`). Rodamos `node --test 'scripts/load/*.test.mjs'`.
2. **Pendências do Build and Test, fora desta volta, que impedem o `mvnw verify` puro de ficar verde.** Nenhum dos dois arquivos foi alterado aqui:
   - **`security/exceptions.yml`:** colocar as datas entre aspas. Sem isso, o `SecurityExceptionsTest` falha.
   - **Pool fixo:** decidir como os testes de integração convivem com ele. Por exemplo, `minimum-idle` menor no perfil `test` dos ITs ou mais `max_connections` no contêiner de teste do `AbstractPostgresIT`. A decisão é do responsável do projeto.
3. **Prontidão.** O aquecimento roda dentro do `ApplicationReadyEvent`, antes de a prontidão passar para `ACCEPTING_TRAFFIC`. O pior caso fica limitado ao `connection-timeout` do pool (2 s) e ao limite de 2 s por consulta.

## Pendências do responsável do projeto

1. **`scripts/verify.sh`:** acrescentar `e2e/reacceptance` ao fim da lista de specs do Playwright, antes do ponto de controle da U3.
2. **`.github/workflows/load-test.yml`:** acrescentar a opção `reacceptance` e trocar o fim da descrição por `..., u5, legal or reacceptance)`, antes do Build and Test.
3. **Itens da U2 ainda pendentes:**
   - a chave HMAC no `verify.sh`, no `ci.yml`, no `perf-audit.sh` e no `.env`;
   - o link do runbook.
