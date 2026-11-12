# Instruções de testes de integração, contrato e ponta a ponta

Conversa: português (Brasil). Cobre a interação entre as Units U1 a U5 desta intent e com os módulos existentes.

## Ferramentas e configuração

| Tipo | Ferramenta | Configuração |
|---|---|---|
| Integração do backend | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), Failsafe | `apps/api/pom.xml`; perfil `test` |
| Contrato | testes `*OpenApiContractTest` e `check:api` nos dois apps | `apps/api/openapi/*.yaml`, `apps/*/src/lib/api/schema.d.ts` |
| Ponta a ponta | Playwright pelo `scripts/verify.sh` | `apps/web/playwright.config.ts` |

## Interações entre Units cobertas

| Interação | Units | Testes |
|---|---|---|
| Catálogo legal → aceite no convite e novo aceite | U1 → U3 | `TermsAcceptanceIT`, `TermsReacceptanceFilterTest`, `e2e/reacceptance` |
| Trava de novo aceite libera `/api/v1/legal/**` e `/api/v1/privacy/**` | U3 → U1, U4 | `TermsReacceptanceRoutesIT` (teste obrigatório do time), `AdminGuestRoutesIT` |
| HMAC da carteira na trilha e expurgos | U2 → todas as Units que publicam evento | `WalletAuditIT`, `LoginChallengePurgeJobTest`, `SessionPurgeJobTest`, `RetentionConformanceTest` |
| Pedidos do titular → resumo de Acesso pelas J1 a J4 | U4 → Identity, Bots, Ledger, Feedback | `DataRequestIT`, `AccessSummaryAssemblerTest`, `IdentityVerificationIT` |
| Pedido de Exclusão → anonimização pelas J1 a J3 e J6 | U4 → U5 → Identity, Bots, Feedback, Audit | `AnonymizationTransactionIT`, `AnonymizationRollbackIT`, `AnonymizationConcurrencyIT`, `AnonymizedAccessIT`, `AnonymizationKeepsHistoryIT` |
| Anonimização × login, renovação e ciclo de bot | U5 → Identity, Bots | `AnonymizationConcurrencyIT` |
| Migrações `0012` a `0015` sobre a base anterior | todas | `MigrationsBackwardCompatIT`, `*MigrationIT`, `U8PrivilegesIT`, `RolesBootstrapIT` |
| Contrato P1/P2 e tipos gerados | U1, U3, U4, U5 | `GuestOpenApiContractTest`, `AdminOpenApiContractTest`, `check:api` |

## Como rodar

```bash
# Toda a integração do backend (parte de clean verify)
env -u MEMETRADE_AUDIT_WALLET_HMAC_KEY sh apps/api/mvnw -B -ntp -f apps/api/pom.xml clean verify

# Contrato nos apps
corepack pnpm@10.18.3 --dir apps/web run check:api
corepack pnpm@10.18.3 --dir apps/admin run check:api

# Ponta a ponta: só o responsável roda diretamente; o AI-DLC roda pelo ponto de controle
bash scripts/verify.sh
```

## Specs de ponta a ponta desta intent

| Spec | Unit | No `verify.sh` |
|---|---|---|
| `e2e/legal-documents` | U1 | sim |
| `e2e/reacceptance` | U3 | sim (desde o commit `5d30541`) |
| `e2e/data-requests` | U4 | sim (desde o commit `5d30541`) |
| `e2e/anonymization` | U5 | sim (desde o commit `5d30541`, com `SMOKE_ANONYMIZATION_INVITE_CODE`) |

## Metas de cobertura

80% de linhas no backend (JaCoCo mesclado), no `apps/web` e no `apps/admin` (Jest). O piso é medido por parte, não por Unit.

## Dados de teste

- Banco por Testcontainers com as migrações da aplicação, sem estado compartilhado nem dependência de ordem.
- Carteiras Ed25519 geradas em código de teste; nunca carteira, mint ou dado pessoal real.
- Relógio injetável nos serviços e jobs.
- No `verify.sh`, a conta e os convites do smoke são semeados só no perfil `test`.
