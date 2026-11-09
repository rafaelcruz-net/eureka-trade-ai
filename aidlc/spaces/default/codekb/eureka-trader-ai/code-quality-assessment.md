# Avaliação de qualidade do código — eureka-trader-ai

Avaliação por leitura, sem rodar testes nem builds. Nenhum número de cobertura medido foi coletado nesta varredura.

## Testes

| Área | Volume | Ferramentas | Piso de cobertura |
|---|---|---|---|
| Backend | 318 arquivos de teste (cerca de 47 mil linhas) para 515 de produção (cerca de 40 mil linhas); 136 integrações `*IT` | JUnit 5, Testcontainers PostgreSQL 17, ArchUnit, testes de contrato OpenAPI, de migração (`MigrationsIT`, `MigrationsBackwardCompatIT`), de papéis de banco (`DatabaseRolesIT`, `DatabaseLoginsIT`) | JaCoCo `check` 80% de linhas (unitários + integração mesclados) no `verify`; exclusões fixas no pom |
| App do convidado | 33 arquivos `*.test.ts(x)` | Jest 30 + Testing Library | 80% de linhas no `jest.config.ts` |
| App administrativo | 10 arquivos `*.test.ts(x)` | Jest 30 + Testing Library | 80% de linhas no `jest.config.ts` |
| Ponta a ponta | 11 specs Playwright (U1 a U9, sem U6, mais o teste de prontidão do runner) + 3 arquivos de suporte | Playwright 1.56.1 | — |
| Carga | `scripts/load/*.test.mjs` (3) | Node test runner | — |

Testes relevantes para o intent: `TermsCatalogTest`, `TermsProhibitedTermsTest`, `AccountRouteIT`,
`InviteVerificationIT`, `InviteVerificationFailureIT`, `InviteConcurrencyIT`, `InviteLookupIT`,
`IdentityAccessMigrationIT`, `SmokeAccountSeederIT`, `DefaultProfileHasNoSeedIT`, `AccountScreen.test.tsx`,
`InviteScreen.test.tsx`, `FeedbackDedupIT`, `AdminFeedbackListIT`, testes do expurgo da trilha em `audit/infra`, e o
smoke `e2e/u3-identity-access/smoke.spec.ts`. `testsupport/SensitiveValueScanner` procura valores sensíveis em logs e
eventos; `scripts/verify.sh` varre os logs da API atrás do endereço completo da carteira do smoke.

Pontos fortes: integração sobre o banco real com as mesmas migrações; testes negativos entre tenants exigidos pelo
registro de rotas; regras de arquitetura executáveis (fronteiras de módulos, nenhum ponto flutuante em valores,
nenhum `now()` no domínio, nenhuma API de chave/mnemônica, nenhum tipo de transação blockchain).

## Lint, formatação e tipos

- Backend: Spotless com palantir-java-format, `removeUnusedImports`, na fase `verify` (falha o build).
- Frontends: ESLint 9 (`eslint-config-next`), Prettier 3.6.2, `tsc --noEmit` com `strict`.
- Supressões: 2 no código de produção (`@SuppressWarnings("checkstyle:ParameterNumber")` em
  `bots/worker/CycleRunner.java`, mas não há Checkstyle configurado; `eslint-disable-next-line
  react-hooks/exhaustive-deps` em `apps/web/src/features/bots/BotDetail.tsx`).
- TODO/FIXME/HACK: 0.

## CI/CD

| Workflow | O que bloqueia |
|---|---|
| `ci.yml` (PR e push em `main`) | `mvnw verify` (testes, JaCoCo 80%, Spotless); `apps/web` lint, format, typecheck, Jest com cobertura; OpenAPI gerado igual ao versionado e tipos do web iguais ao `schema.d.ts`; `verify.sh` (smoke); Playwright completo |
| `security.yml` | Gitleaks (versão fixa, checksum), CodeQL (erros bloqueiam), exceções vencidas, OWASP Dependency-Check (CVSS ≥ 7), `pnpm audit` High/Critical nos dois apps |
| `load-test.yml` | Manual |

Ações de terceiros fixadas por SHA, `permissions: contents: read`, nenhum segredo usado. Hook de pré-commit com
Gitleaks (`.githooks/pre-commit`, ligado por `scripts/setup.sh`).

## Documentação

- Javadoc extenso e rastreável a requisitos (BR, NFR, FR, AC) em quase toda classe; comentários de SQL explicam cada
  gatilho e privilégio.
- 5 ADRs completos (contexto, decisão, consequências) e anexos de contrato por Unit em `docs/api/`.
- README em pt-BR com comandos de execução e teste, porém desatualizado (ver dívidas).

## Avaliação rápida (bom / razoável / fraco)

| Critério | Nota | Observação |
|---|---|---|
| Clareza de nomes | bom | Nomes de domínio explícitos, inglês no código, pt-BR na interface |
| Tamanho das funções | razoável | Maior arquivo 595 linhas (`JdbcLedgerRepository`); vários entre 450 e 500 (`BotCommandService`, `BotDtos`, `JdbcBotRepository`) |
| Tratamento de erros | bom | RFC 7807, nenhum `catch` vazio encontrado, falhas de job registradas e refletidas na saúde |
| Presença de testes | bom | Caminhos críticos com unitário e integração; exceção: CI não roda os testes do app administrativo |
| Duplicação | razoável | Máscara de carteira, Base58 e cliente de API repetidos entre `apps/web` e `apps/admin`; DTO `MarketValueDto` reaproveitado por outros módulos |
| Código morto | bom | Sem blocos comentados; estados de esquema nunca usados (`DISABLED`, `SUSPENDED`) |

## Dívida técnica e riscos

### Relevantes para o intent `termos-lgpd`

1. **Endereço completo na trilha de auditoria** por 12 meses (`ACCOUNT_CREATED`, `LOGIN_SUCCEEDED`,
   `LOGIN_REJECTED`), também na saída por até 7 dias após a entrega. A trilha recusa UPDATE para qualquer login;
   anonimizar exige mudar o gatilho de `0005-audit-trail.sql` ou justificar a retenção.
2. **Sem expurgo** de `login_challenge` (endereço completo e mensagem), `user_session`, `session_used_refresh_digest`
   e convites usados/vencidos.
3. **Sem encerramento de conta, exclusão ou anonimização**: nenhuma rota; `app_user` é referenciado sem
   `ON DELETE` por tabelas append-only; `wallet.address` não aceita marcador (NOT NULL, único, CHECK Base58).
4. **Sem reaceite de termos**: o aceite só é conferido na criação por convite; login sem convite ignora a versão.
   `terms_acceptance` é insert-only e único por convite (um reaceite precisa de outra chave ou outra tabela).
5. **Termos v1 provisórios** ("pendente de revisão jurídica") e sem política de privacidade no produto.
6. Texto livre com possível dado pessoal sem triagem: `feedback_entry.text` (180 dias) e `bot.name` (sem prazo).

### Gerais

7. CI não roda lint, typecheck, Jest nem `check:api` de `apps/admin`; Jest de `apps/web` na CI cobre só
   `features/auth`, `features/scanner`, `lib` e `middleware` (o piso de 80% vale só para esse recorte).
8. Classes de teste e carga no código de produção, ativadas por perfil (`SmokeAccountSeeder`, `SmokeInviteSeeder`,
   pacotes `*/loadtest/`); `DefaultProfileHasNoSeedIT` e `LoadtestProfileGuard` mitigam.
9. Acoplamentos fora de `api` listados em [dependencies.md](dependencies.md#acoplamentos-fora-das-interfaces-api).
10. README desatualizado: cita só 5 módulos, diz que `scripts/perf-audit.sh` não existe (existe) e Gitleaks 8.28.0
    (fixado 8.30.1).
11. Sem portão de acessibilidade automatizado (decisão do time) e sem ambiente além do local.
