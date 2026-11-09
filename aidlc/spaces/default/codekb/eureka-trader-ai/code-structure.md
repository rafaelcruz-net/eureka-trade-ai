# Estrutura do código — eureka-trader-ai

## Layout do repositório

| Caminho | Conteúdo |
|---|---|
| `apps/api/` | API Spring Boot: `pom.xml`, Maven Wrapper, `Dockerfile`, `openapi/` (contratos gerados) |
| `apps/api/src/main/java/com/memetrade/` | Código de produção, um pacote por módulo (515 arquivos, cerca de 39,8 mil linhas) |
| `apps/api/src/main/resources/` | `application*.yml` (perfis padrão, `worker`, `migrate`, `test`, `loadtest`), `logback-spring.xml`, `db/changelog/` (11 migrações SQL), `terms/v1/`, `tenancy/route-registry.yml` |
| `apps/api/src/test/` | 318 arquivos de teste (136 de integração `*IT`), suporte em `testsupport/`, changelogs parciais e fixtures de mercado |
| `apps/web/` | App do convidado Next.js: `src/app/` (rotas), `src/features/` (telas por domínio), `src/lib/` (API, auth, formatação, i18n, tema), `src/components/shell/`, `src/content/` (termos proibidos), `e2e/` (Playwright) |
| `apps/admin/` | App administrativo Next.js com a mesma organização (`app/`, `features/`, `lib/`, `components/`) |
| `infra/` | `postgres/bootstrap/` (logins e papéis), `postgres/init/`, `docker-compose.test.yml` |
| `scripts/` | `setup.sh`, `verify.sh`, `load-test.sh`, `perf-audit.sh`, `load/*.mjs` |
| `docs/` | `adr/` (5 ADRs), `api/` (anexos de contrato por Unit); `docs/perf/` são relatórios gerados |
| `security/` | `exceptions.yml` (exceções de varredura com validade) |
| `.github/` | `workflows/` (ci, security, load-test), `dependabot.yml` |
| Raiz | `docker-compose.yml`, `.env.example`, `.editorconfig`, `.gitattributes`, `.gitleaks.toml`, `.gitleaks-version`, `.nvmrc`, `.githooks/pre-commit`, `README.md`, PRD |

## Organização interna de um módulo do backend

```
com.memetrade.<módulo>/
  api/          interfaces públicas e tipos de contrato usados por outros módulos
  domain/       regras de negócio, sem Spring Web nem JDBC (ArchUnit)
  application/  orquestração de casos de uso (bots, ledger, pnl)
  infra/        repositórios Jdbc*, jobs @Scheduled, *Config, *Properties, health indicators
  web/          *Controller, *Dtos (records com @Schema), filtros
  worker/       só em bots: ciclo, meia-noite, expurgo de decisões
  loadtest/     drivers de carga ativados só no perfil loadtest
```

Módulos: `shared`, `identity`, `audit`, `marketdata`, `scanner`, `riskassessment`, `riskcontrol`, `bots`,
`decision`, `ledger`, `pnl`, `administration`, `feedback`, `ops`. Responsabilidades em
[component-inventory.md](component-inventory.md).

## Mapa do módulo `identity` (área do intent)

| Subpacote | Arquivos-chave | Papel |
|---|---|---|
| `domain/terms` | `CurrentTerms`, `TermsDocument` | Porta dos termos versionados |
| `infra` | `TermsCatalog`, `TermsProperties` | Lê `classpath:terms/<versão>/` na subida, valida UTF-8 e unicidade da versão vigente |
| `domain/invite` | `InviteService`, `InviteLookupService`, `InviteCodes`, `Invite`, `InviteRepository` | Emissão, revogação e consulta pública de convite (código guardado só como digest + sufixo) |
| `domain/login` | `ChallengeService`, `LoginVerificationService`, `InviteAccountCreation`, `AdminLoginService`, `LoginMessage` | Desafio, verificação da assinatura, criação de conta com aceite, entrada do administrador |
| `domain/account` | `AccountQueryService`, `AccountDetailsRepository`, `GuestAccountRepository` | Tela Conta e escritas da criação de conta |
| `domain/session` | `SessionService`, `AccessTokens`, `RefreshTokens`, `Session*` | Sessão, rotação do refresh, detecção de reuso |
| `domain/idempotency` | `IdempotencyService`, `IdempotencyStore`, `RequestDigest` | Mecanismo comum de idempotência |
| `domain/ratelimit` | `LoginRateLimiter`, `InviteLookupRateLimiter`, `AdminLoginLimiter` | Limites por IP e por carteira |
| `domain/signature` | `Ed25519SignatureVerifier`, `Base58` | Verificação nativa, sem SDK Solana |
| `web` | `AuthController`, `InviteLookupController`, `AccountController`, `AdminAuthController`, `AdminInviteController`, `AuthenticationFilter`, `AdminRouteFilter`, `RefreshCookie`, `AdminRefreshCookie`, CORS | Rotas e filtros |
| `infra` | `Jdbc*Repository`, `IdempotencyCleanupJob`, `LoginLimiterEvictionJob`, `HmacAccessTokens`, `SmokeAccountSeeder`/`SmokeInviteSeeder` (`@Profile("test")`) | Persistência e jobs |

## Mapa do app do convidado (`apps/web/src`)

| Rota (`app/`) | Tela (`features/`) |
|---|---|
| `/entrar` | `auth/LoginScreen.tsx` |
| `/convite`, `/convite/[codigo]` | `auth/InviteScreen.tsx` (textos dos termos, duas marcas de aceite, `acceptance-store.ts`) |
| `/conta` | `account/AccountScreen.tsx` |
| `/` | `dashboard/DashboardScreen.tsx` (canal ao vivo por `fetch` com leitura contínua) |
| `/scanner` | `scanner/ScannerScreen.tsx` |
| `/bots`, `/bots/new`, `/bots/new/[botId]`, `/bots/[botId]` | `bots/*` |
| `/pnl` | `pnl/PnlScreen.tsx` |
| (diálogo global) | `feedback/FeedbackDialog.tsx` |

`middleware.ts` gera CSP com nonce por requisição e põe `Cache-Control: no-store` em `/convite*` e `/conta*`.
`lib/auth/token-store.ts` guarda o token só na memória. Textos de interface em `lib/i18n/messages.ts` (pt-BR).

## Mapa do app administrativo (`apps/admin/src`)

| Rota | Tela |
|---|---|
| `/` | `status/SystemStatusPage.tsx` (ou `auth/AdminSignIn.tsx` sem sessão) |
| `/convites` | `invites/InvitesPage.tsx`, `IssueInviteDialog.tsx`, `RevokeInviteDialog.tsx` |
| `/feedback` | `feedback/FeedbackListPage.tsx` (texto como texto puro, sem pessoa nem carteira) |
| `/kill-switch` | `killswitch/KillSwitchPage.tsx`, `KillSwitchDialog.tsx` |

Não há tela da trilha de auditoria no app administrativo.

## Classificação dos arquivos

| Categoria | Onde |
|---|---|
| Model/Entity | records em `*/domain/` e `*/api/`; DTOs em `*/web/*Dtos.java`; `schema.d.ts` gerado nos frontends |
| Controller/Handler | `*/web/*Controller.java`; `app/**/page.tsx` |
| Service/UseCase | `*/domain/*Service.java`, `*/application/*` |
| Repository/DAO | `*/infra/Jdbc*.java` |
| Utility | `shared/*`, `apps/*/src/lib/*` |
| Configuration | `*/infra/*Config.java`, `*/infra/*Properties.java`, `application*.yml`, `next.config.ts` |
| Middleware | `shared/web/*Filter.java`, `identity/web/AuthenticationFilter.java`, `AdminRouteFilter.java`, `apps/*/src/middleware.ts` |
| Test | `apps/api/src/test/**`, `**/*.test.ts(x)`, `apps/web/e2e/**`, `scripts/load/*.test.mjs` |
| Migration | `apps/api/src/main/resources/db/changelog/changes/*.sql`, `infra/postgres/bootstrap/10-roles.sql` |
| Static/Asset | `terms/v1/*.txt`, `globals.css`, `content/prohibited-terms.json` |

## Padrões de código recorrentes

- Records imutáveis com validação no construtor compacto; `toString()` sobrescrito para nunca vazar endereço, código
  de convite, nonce, assinatura ou texto de feedback.
- Tipos selados para resultados (`Result.Created`/`Refused`, `VerificationOutcome`) e para `unknown` de mercado.
- `Clock` injetável em todo domínio (ArchUnit proíbe `now()` direto).
- SQL explícito em text blocks via `JdbcClient`; paginação por cursor de tupla, sem `OFFSET`.
- Violação de unicidade reconhecida pelo NOME da constraint (`PSQLException.getServerErrorMessage()`).
- Jobs: `@Scheduled` com zona UTC, ShedLock, `ProcessRole` para escolher o processo, health indicator por job.
- Javadoc cita os IDs de requisito (BR, NFR, FR, AC) de cada Unit.
- Frontends: estados de carregamento, erro e dado distintos; foco no título; `data-testid` para testes.
