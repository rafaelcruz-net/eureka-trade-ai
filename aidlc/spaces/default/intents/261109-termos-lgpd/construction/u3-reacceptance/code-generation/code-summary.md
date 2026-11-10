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

## Pendências do responsável do projeto

1. **`scripts/verify.sh`:** acrescentar `e2e/reacceptance` ao fim da lista de specs do Playwright, antes do ponto de controle da U3.
2. **`.github/workflows/load-test.yml`:** acrescentar a opção `reacceptance` e trocar o fim da descrição por `..., u5, legal or reacceptance)`, antes do Build and Test.
3. **Itens da U2 ainda pendentes:**
   - a chave HMAC no `verify.sh`, no `ci.yml`, no `perf-audit.sh` e no `.env`;
   - o link do runbook.
