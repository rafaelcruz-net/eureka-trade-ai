# Resumo do código — U3 Convites, contas e sessões

Os 24 passos do [plano aprovado](code-generation-plan.md) foram implementados e marcados. A lista completa de arquivos está em [source-manifest.json](source-manifest.json); a cobertura de cada regra, critério e requisito está em [traceability.json](traceability.json).

## Arquivos criados e alterados

| Área | Novos | Alterados |
|---|---|---|
| Backend, produção (`apps/api/src/main/java/com/memetrade/identity/`) | `api/` (`IdentityAdministration`, `IssuedInvite`, `InviteView`, `InvitePage`, `InviteStatus`, `IdentityCounts`, `TenantOption`); `domain/invite/`; `domain/terms/`; `domain/account/`; `domain/login/InviteAccountCreation`; `domain/ratelimit/InviteLookupRateLimiter`; `infra/` (`IdentityAdministrationImpl`, `JdbcInviteRepository`, `JdbcIdentityDirectory`, `JdbcGuestAccountRepository`, `JdbcAccountDetailsRepository`, `TermsCatalog`, `TermsProperties`, `SmokeInviteSeeder`); `web/` (`InviteLookupController`, `AccountController`, `AdminInviteController` e DTOs) | `AdminAccess`, `IdempotentOperation`, `LoginVerificationService`, `RejectionReason`, `IdentityConfig`, `IdentityProperties`, `LoginLimiterEvictionJob`, `RateLimitConfig`, `AdminAuthorization`, `AuthController`, `AuthDtos`, `AuthenticationFilter`, `audit/api/AuditActions`, `shared/error/ErrorCode`, `shared/error/ProblemResponse`, `pom.xml` |
| Backend, recursos | `db/changelog/changes/0007-identity-access.sql`; `terms/v1/` (`terms.txt`, `risk-notice.txt`, `version.properties`) | `application.yml`, `application-loadtest.yml`, `application-test.yml`, `db.changelog-master.yaml`, `tenancy/route-registry.yml`, `openapi/openapi-guest.yaml`, `openapi/openapi-admin.yaml` |
| Backend, testes | 26 classes novas (convites, consulta, verificação, falha técnica, concorrência, conta, rotas administrativas, semeador, limites do `loadtest`, migração, termos, arquitetura, varredura de `tenantId`, apoio `AbstractU3IT`, `TestInvites`, `OutboxEvents`) | 19 classes (contrato, `MigrationsIT`, `AuthVerifyIT`, `AuthenticationFilterTest`, `LogScanIT`, `MetricsLabelsIT`, `RouteRegistry` e teste, `ComposeFilesTest`, `WorkflowsTest`, `SmokeWalletTool` e teste, apoio) |
| Frontend (`apps/web/`) | páginas `/convite`, `/convite/[codigo]` e `/conta`; `features/auth/` (`InviteScreen`, `acceptance-store`, `origin`, `AccountMenu`); `features/account/AccountScreen`; quatro arquivos de teste; smoke `e2e/u3-identity-access/smoke.spec.ts` | `lib/api/client.ts`, `lib/api/guest-api.ts`, `lib/api/schema.d.ts` (gerado), `lib/i18n/messages.ts`, `middleware.ts` e teste, `globals.css`, `login-flow.ts`, `test-support.tsx`, `ScannerScreen` e teste, `prohibited-terms.test.ts`, `e2e/support/test-wallet.ts`, `eslint.config.mjs` |
| Infra, scripts e documentação | `docs/api/u3-convites-contas-e-sessoes.md` | `infra/docker-compose.test.yml`, `.github/workflows/load-test.yml`, `scripts/load-test.sh`, `scripts/load/load-test.mjs`; `contract-summary.md` (C1, C3, C8, C9, C17 e tabela de erros) |

Nenhum arquivo foi apagado. O `scripts/verify.sh` não foi alterado nesta Unit.

## Decisões de implementação

- **Código do convite:** gerado com `SecureRandom` (32 bytes, base64url) e guardado só como SHA-256 e final de 4 caracteres. A emissão devolve `IssuedInvite`; o registro de idempotência guarda o convite sem `code`, e o controlador só acrescenta o código à resposta quando a chamada não é repetição.
- **Verificação com convite:** uma transação com desafio consumido, bloqueio da linha do convite, conferências na ordem da BR3.2 e criação de pessoa, carteira, aceite, convite usado, `ACCOUNT_CREATED` e sessão. A violação de `uq_wallet_address` é reconhecida pelo nome da restrição; a transação é desfeita e uma segunda (`rejectAfterRollback`) consome o desafio e grava `LOGIN_REJECTED`.
- **Rota pública estreita:** o `AuthenticationFilter` libera sem token só `GET /api/v1/invites/{um segmento}`.
- **Registro de rotas:** cinco rotas novas; campo `tenantFromClient` aceito só em `ADMIN` (só na emissão); um teste percorre os dois OpenAPI e falha se aparecer `tenantId` sem a marca.
- **Migração `0007`:** tabelas `invite` e `terms_acceptance` (só inserção para a aplicação), renomeação condicionada de `wallet_address_key` para `uq_wallet_address`, índice `app_user(role, status)` e tenant `Beta` semeado uma vez.
- **Termos `v1`:** texto provisório, marcado "Versão provisória, pendente de revisão jurídica"; a API não sobe sem uma versão vigente válida.

## Desvios do plano

- **Limite da consulta no `application.yml`:** ficou fixo em `10`, sem variável de ambiente. Só o perfil `loadtest` lê `INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE`, como na especificação de infraestrutura e no limite da entrada. A validação na partida aceita de 1 a 100 000.
- **Driver PostgreSQL com escopo `compile`** (antes `runtime`), para ler o nome da restrição violada sem depender da mensagem do banco. Não é dependência nova.
- **Testes de idempotência da emissão** ficaram em `AdminInviteRouteIT` (Passo 13), onde a resposta é composta.
- **C17:** a interface usa `InviteView`, `InvitePage`, `IssuedInvite`, `TenantOption` e `InviteStatus`, recebe o `Principal` em todas as operações e `limit` na lista. O papel é conferido por `AdminAccess.isAdministrator(Principal)`, porque a regra de arquitetura da U2 só deixa `AdminAuthorization` referenciar `Role.ADMIN`. O `contract-summary.md` foi atualizado.
- **`acceptedTerms` em `GET /api/v1/account`:** opcional (a conta de smoke da U1 não tem aceite) e com o texto da versão aceita, para a tela mostrar "ver" (BR5.2).
- **Limitador da consulta:** uma consulta recusada não entra na janela, para o `Retry-After` ser exato.
- **Falha técnica:** falha de banco responde `503` pelo tratador existente; as demais, `500`.
- **Primeira entrada:** grava `ACCOUNT_CREATED` e não `LOGIN_SUCCEEDED`.
- **`ErrorCode`:** ganhou `code()`, para `422` responder com o código `VALIDATION_FAILED`.
- **Retorno à origem:** `safeOrigin` aceita `/scanner` e `/conta`; a tela inicial é `/scanner`.
- **`issuedBy`:** endereço mascarado do administrador configurado; com mais de um configurado, `admin:<prefixo do id>`.
- **Formatação:** o Spotless reformatou alguns arquivos da U4 que já estavam alterados; só a formatação mudou.
- **Testes antigos ajustados de propósito:** `AuthVerifyIT` (campos de convite aceitos; código desconhecido responde `404` e consome o desafio), `RouteRegistryTest` (primeira rota `CLIENT` agora é a da conta), `ScannerScreen.test` (link "Conta" antes de "Sair").
- **Uma edição por `sed -i`** no `ProblemResponse.java`, contra a regra de editar só com a ferramenta de arquivo; depois disso, só a ferramenta de arquivo.

## Testes e cobertura

| Comando | Resultado |
|---|---|
| Comando da U3: `apps/api/mvnw -f apps/api/pom.xml -Dgroups=u3 test-compile surefire:test failsafe:integration-test failsafe:verify` | 50 unitários e 83 de integração, 0 falhas |
| Suíte inteira do backend: `apps/api/mvnw -f apps/api/pom.xml verify` | 559 unitários e 332 de integração, 0 falhas; JaCoCo "All coverage checks have been met": 95,97% de linhas (96,12% no módulo `identity`); Spotless ok. A primeira execução falhou no `AuthVerifyIT` pela mudança de comportamento acima; corrigido e verde na segunda |
| Frontend da U3 com cobertura | 169 testes, 95,03% de linhas |
| Frontend inteiro: `jest --coverage` | 174 testes, 97,35% de linhas; arquivos da U3 entre 83% (`acceptance-store.ts`) e 100% |
| `tsc`, ESLint, Prettier, `check:api` | Limpos; tipos batem com o `openapi-guest.yaml` |
| `next build` | Compilou; a etapa standalone falhou com `EPERM` ao criar links simbólicos no Windows (ambiente, não código) |
| `docker compose ... config --quiet`, `node --check`, `bash -n` | Válidos |

Linha de base antes da U3: 512 unitários e 251 de integração, 95,72% de linhas.

## Testes escritos e não executados

| Teste | Motivo |
|---|---|
| Smoke Playwright `e2e/u3-identity-access/smoke.spec.ts` (só listado com `playwright test --list`) | Precisa do ambiente do `scripts/verify.sh` com `SMOKE_INVITE_CODE`, que depende da mudança do responsável do projeto (abaixo). É a evidência do NFR3.4 e da parte de log do NFR6.4 |
| Cenário de carga `LOAD_SCENARIO=u3` | Roda só localmente com o ambiente completo; sem ele, NFR3.1 e NFR3.2 não têm medição |
| Teste manual de rollback (imagens anteriores sobre o volume migrado) | Não feito; a versão automática passou (`MigrationsIT`) |
| p95 de 100 ms das contagens (NFR2.4) e p95 das rotas administrativas (NFR3.3) | O teste de integração mede a média de 10 ações, não um p95 |

## Mudança pendente do responsável do projeto no `scripts/verify.sh`

O texto completo está em `docs/api/u3-convites-contas-e-sessoes.md`, seção "Mudança preparada para o `scripts/verify.sh`":

1. Depois de gerar a carteira de smoke, gerar e exportar `SMOKE_INVITE_CODE` com `SmokeWalletTool --invite-code` e conferir que tem 43 caracteres.
2. Na linha do Playwright, passar `SMOKE_INVITE_CODE` e acrescentar `e2e/u3-identity-access`.
3. Procurar o código do convite no log da `api`, como já é feito com o endereço da carteira.

O ponto de controle da U3 só deve ser aprovado depois de `bash scripts/verify.sh` passar com o smoke da U3.
