# Estrutura do código — eureka-trader-ai

Responsabilidade de cada componente em [component-inventory.md](component-inventory.md); dependências em
[dependencies.md](dependencies.md); rotas e contratos em [api-documentation.md](api-documentation.md).

## Layout do repositório

```text
./
├── apps/
│   ├── api/                      Spring Boot (Maven, um artefato memetrade-api)
│   │   ├── openapi/              openapi-guest.yaml, openapi-admin.yaml (gerados do código, versionados)
│   │   ├── src/main/java/com/memetrade/<módulo>/{api,domain,infra,web,application,worker,loadtest}
│   │   ├── src/main/resources/   application*.yml, db/changelog (15 migrações), terms/v1 e v2,
│   │   │                         tenancy/route-registry.yml, logback-spring.xml
│   │   ├── src/test/java/com/memetrade/<módulo e transversais>
│   │   ├── src/test/resources/   fixtures de mercado gravadas, changelogs parciais, termos de teste
│   │   ├── pom.xml, mvnw, .mvn/, Dockerfile
│   ├── web/                      Next.js do convidado (App Router)
│   │   ├── src/app/              rotas: / (painel), entrar, convite, conta, scanner, bots, pnl, termos,
│   │   │                         privacidade, novo-aceite, api/health
│   │   ├── src/features/         auth, account, bots, dashboard, scanner, pnl, legal, reacceptance,
│   │   │                         data-requests, feedback
│   │   ├── src/components/shell, src/lib/{api,auth,format,i18n,theme}, src/content, src/middleware.ts
│   │   ├── e2e/                  specs Playwright por Unit e suporte (carteira de teste, leitura da trilha)
│   │   └── scripts/              check-api-types.mjs, guest-bundle-test.mjs
│   └── admin/                    Next.js do administrador
│       ├── src/app/              rotas: / (estado), convites, feedback, kill-switch, pedidos, privacidade, termos
│       ├── src/features/         auth, status, invites, killswitch, feedback, data-requests, accounts, legal, layout
│       └── scripts/check-api-types.mjs
├── infra/
│   ├── docker-compose.test.yml   sobreposição do perfil test (smoke, e2e, carga)
│   └── postgres/{bootstrap,init} logins e papéis de grupo
├── scripts/                      verify.sh, setup.sh, load-test.sh, perf-audit.sh, load/*.mjs
├── security/exceptions.yml       exceções versionadas das varreduras
├── docs/{adr,api,privacy,perf}   ADRs, anexos de contrato, retenção; perf são relatórios gerados
├── .github/                      workflows ci, security, load-test; dependabot
├── .githooks/pre-commit          Gitleaks
├── docker-compose.yml, .env.example, .editorconfig, .gitattributes, .nvmrc, .gitleaks.toml
└── PRD_MemeTrade_Analytics_v1.3_SaaS.md, PromptInicial.txt, README.md
```

## Organização do backend

Organizado **por módulo de negócio**, não por camada técnica global. Contagem de arquivos de produção por módulo e
subpacote (commit de referência):

| Módulo | api | domain | application | infra | web | worker | loadtest | Linhas |
|---|---|---|---|---|---|---|---|---|
| `shared` | — | — | — | `clock`, `config`, `error`, `health`, `logging`, `retention`, `wallet`, `web` (33 arquivos) | | | | 1.823 |
| `identity` | 23 | 79 (em `account`, `anonymization`, `idempotency`, `invite`, `legal`, `login`, `person`, `purge`, `ratelimit`, `session`, `signature`, `terms`) | — | 50 | 29 | — | — | 11.839 |
| `audit` | 7 | 14 | — | 30 | 2 | — | — | 3.445 |
| `marketdata` | 17 | 11 | — | 16 (com `infra/simulated`) | — | — | — | 3.211 |
| `scanner` | — | 7 | — | 3 | 5 | — | — | 1.259 |
| `riskassessment` | 15 | 10 | — | 8 | — | — | — | 1.819 |
| `riskcontrol` | 9 | 16 | — | 15 | 2 | — | 4 | 3.622 |
| `bots` | 6 | 14 | 9 | 10 | 7 | 12 | 2 | 6.592 |
| `decision` | — | 21 | — | 2 | — | — | — | 1.589 |
| `ledger` | 9 | 9 | 8 | 8 | 2 | — | 5 | 4.717 |
| `pnl` | 2 | 5 | 6 | 5 | 4 | — | — | 2.000 |
| `administration` | 1 | 8 | — | 9 | 4 | — | — | 2.135 |
| `feedback` | 2 | 5 | — | 10 | 3 | — | — | 1.220 |
| `privacy` | — | 39 (em `anonymization`, `purge`, `request` e catálogo de retenção) | — | 26 | 5 | — | 1 | 6.298 |
| `ops` | `HealthProbe`, `MigrationRunner` | | | | | | | 83 |

`decision` não tem pacote `api`: o módulo `bots` importa diretamente `decision.domain` e `decision.infra`
(`BotsArchitectureTest` trata os dois como uma unidade).

## Classificação dos arquivos

| Categoria | Onde | Exemplos |
|---|---|---|
| Modelo / entidade | `*/domain`, `*/api` (records) | `Bot`, `BotLimits`, `MarketValue`, `DecisionOutcome`, `FillQuote`, `KillSwitchSnapshot`, `DataSubjectRequest` |
| Controlador | `*/web` | `BotController`, `LedgerController`, `PnlController`, `ScannerController`, `AdminKillSwitchController`, `AuthController` |
| Serviço / caso de uso | `*/application`, `*/domain` | `BotCommandService`, `PaperExecutionImpl`, `OrderGateService`, `KillSwitchService`, `AnonymizationService` |
| Repositório | `*/infra/Jdbc*` | `JdbcLedgerRepository`, `JdbcBotRepository`, `JdbcMarketStore`, `JdbcKillSwitchStore` |
| Utilitário | `shared/*`, `*/domain` puros | `KeysetCursor`, `WalletAddress`, `Scales`, `Numbers`, `Base58`, `Digests` |
| Configuração | `*/infra/*Config`, `*Properties`, `application*.yml` | `MarketDataConfig`, `LedgerProperties`, `RuntimeSettingsGuard` |
| Middleware (filtros) | `shared/web`, `identity/web` | `CorrelationIdFilter`, `SecurityHeadersFilter`, `ModeGuardFilter`, `AuthenticationFilter`, `IdempotencyKeyFilter`, `TermsReacceptanceFilter`, `AdminRouteFilter` |
| Jobs | `*/infra/*Job`, `bots/worker` | `MarketCollectorJob`, `EquityMarkingJob`, `ReconciliationJob`, `SlotDispatcher`, `MidnightJob`, `KillSwitchReconciler` |
| Migração | `src/main/resources/db/changelog/changes/` | `0001-identity.sql` a `0015-anonymization.sql` |
| Teste | `src/test/java` | 169 `*Test`, 170 `*IT`, 54 de suporte (detalhe em [code-quality-assessment.md](code-quality-assessment.md#testes)) |
| Estático | `src/main/resources/terms/`, `apps/*/src/app/globals.css` | termos v1 e v2, aviso de risco, tabela de retenção |

## Padrões de código

- **Tipos selados para resultados e ausência**: `MarketValue` (`Known` | `Unknown`), `GateResult` (`Approved` |
  `Rejected`), `ExecutionResult` (`Filled` | `NotFilled` | `Duplicate` | `Conflict`), `FundResult`, `CloseResult`,
  `FactorValue`. Recusas de domínio são resultados, não exceções; só falhas técnicas lançam.
- **Domínio puro**: `FillCalculator`, `LimitChecks`, `SlippageEstimate`, `DecisionEngine`, `BotStateMachine`,
  `PnlCalculator`, `RiskCalculator` não leem relógio nem banco; o instante entra como argumento.
- **Aritmética decimal com escalas fixas**: USDC 6 casas, preço unitário 12, percentuais 4, `HALF_EVEN`; quantidades
  truncadas para as casas do mint; nunca ponto flutuante.
- **Transações explícitas**: `TransactionTemplate` com isolamento e propagação declarados no ciclo; `SET LOCAL
  lock_timeout` e `statement_timeout`; `Propagation.MANDATORY` nos contratos chamados de dentro do ciclo; ordem global
  de travas (`bots/infra/LockOrder`).
- **Idempotência**: chave por operação (`order:<decisionId>`, `fund:<botId>`, `close:<botId>`) e digest SHA-256 do
  conteúdo; mesma chave e conteúdo devolve o resultado gravado, conteúdo diferente devolve conflito.
- **Execução única de jobs**: ShedLock com nome por job (`market-collect-<fonte>`, `bots-slot-dispatch`,
  `pnl-equity-marking`, `pnl-reconciliation`).
- **Processo por anotação**: `@ProcessRole(API | WORKER)` em todo bean que roda sozinho.
- **Configuração validada na subida**: `*Properties` recusam valores fora de faixa citando a variável, nunca o valor.
- **Logs e métricas sem dado pessoal**: rótulos só de listas fechadas; carteira mascarada; correlação por `cycleId` ou
  `correlationId`.

## Organização dos frontends

- **App Router** com uma pasta por rota em português (`/entrar`, `/convite/[codigo]`, `/conta`, `/scanner`, `/bots`,
  `/bots/new`, `/bots/[botId]`, `/pnl`, `/termos`, `/privacidade`, `/novo-aceite`; no admin `/convites`, `/feedback`,
  `/kill-switch`, `/pedidos`, `/pedidos/[id]`, `/pedidos/novo`, `/privacidade`, `/termos`).
- **Por feature** em `src/features/<feature>` com tela, estado, mensagens e testes lado a lado.
- **Cliente de API tipado** em `src/lib/api` (`client.ts`, `guest-api.ts` ou `admin-api.ts`) sobre `schema.d.ts`
  gerado do OpenAPI; token em memória (`src/lib/auth/token-store.ts`).
- **Carteira** em `src/features/auth/wallets.ts`: Wallet Standard com `standard:connect` e `solana:signMessage`; o app
  só vê o endereço público e a assinatura. Não há `signTransaction` nem `signAndSendTransaction`.
- **Segurança no servidor Next**: `middleware.ts` gera CSP com nonce por requisição, `connect-src` só para o próprio
  app e a origem da API, `frame-ancestors 'none'` e `no-store` nas páginas privadas.
- **Textos de interface** em pt-BR em `src/lib/i18n/messages.ts` e `messages.ts` por feature; termos proibidos de
  promessa de lucro em `src/content/prohibited-terms.json` (com teste).
