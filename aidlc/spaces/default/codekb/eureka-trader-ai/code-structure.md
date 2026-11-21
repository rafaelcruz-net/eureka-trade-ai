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
| `bots` (2026-11-21) | 7 | 16 | 11 | 10 | 7 | 14 | 2 | 7.478 |
| `decision` | — | 21 | — | 2 | — | — | — | 1.589 |
| `ledger` (2026-11-21) | 17 | 13 | 8 | 9 | 2 | — | 5 | 5.433 |
| `pnl` (2026-11-21) | 2 | 5 | 7 | 5 | 5 (+ `real`: 1) | — | — | 2.323 |
| `realexecution` (2026-11-21) | 10 | 17 (com `domain/solana`: 10) | — | 27 (com `infra/jupiter`: 2) | 2 | — | — | 6.310 |
| `realexits` (2026-11-21) | 1 | 12 | 7 | 11 | 2 (+ `fiscal`: 9) | — | — | 4.112 |
| `administration` | 1 | 8 | — | 9 | 4 | — | — | 2.135 |
| `feedback` | 2 | 5 | — | 10 | 3 | — | — | 1.220 |
| `privacy` | — | 39 (em `anonymization`, `purge`, `request` e catálogo de retenção) | — | 26 | 5 | — | 1 | 6.298 |
| `ops` | `HealthProbe`, `MigrationRunner` | | | | | | | 83 |

`decision` não tem pacote `api`: o módulo `bots` importa diretamente `decision.domain` e `decision.infra`
(`BotsArchitectureTest` trata os dois como uma unidade).

As linhas marcadas com 2026-11-21 foram recontadas nesta varredura focada (arquivos `.java` de produção por
subpacote). As demais são do commit de 2026-11-13; os módulos `botwallet` (com `api`, `application`, `domain`, `infra`,
`web`) e `riskcontrol/real` existem hoje e não foram contados, e o app `apps/txsigner` também ficou fora desta execução.

### Arquivos-chave da área de P&L e REAL (2026-11-21)

| Módulo | Arquivo | Papel |
|---|---|---|
| `pnl` | `real/RealPnlCalculator.java` | Cálculo puro do P&L REAL por custo médio por mint, em `long` |
| `pnl` | `application/RealPnlService.java` | Monta os fills a partir de `RealExecutionPort.settledOrders` e os preços em lamports |
| `pnl` | `web/RealPnlController.java` | `GET /api/v1/real/pnl` |
| `pnl` | `application/PnlQueryService.java`, `domain/PnlCalculator.java`, `web/PnlController.java` | P&L PAPER por período, com `botId` opcional |
| `ledger` | `api/RealLedger.java`, `infra/JdbcRealLedger.java` | M2: gravação e leituras do ledger REAL por carteira do bot |
| `ledger` | `domain/RealPositions.java`, `domain/RealBalances.java`, `domain/RealEntryRules.java` | Posições por custo médio, saldo e convenção de sinal do REAL |
| `ledger` | `domain/PositionAccounting.java`, `application/FillRecorder.java` | Custo médio PAPER por bot no momento do fill |
| `realexecution` | `api/RealExecutionPort.java`, `api/RealOrderView.java`, `api/RealDecision.java` | M6 e as visões públicas das ordens |
| `realexecution` | `infra/JdbcRealExecutionStore.java` (762 linhas) | Tabelas `real_decision_request`, `real_order`, `real_liquidation` |
| `realexecution` | `infra/OrderDriver.java`, `infra/OrderSettlement.java`, `infra/RealOrderLedger.java`, `domain/RealLedgerEntries.java` | Máquina de passos da ordem e os lançamentos `order:<id>` |
| `realexecution` | `infra/LiquidationService.java`, `infra/LiquidationJob.java` | "Vender tudo e sacar" |
| `realexits` | `application/ConditionalService.java`, `application/FillPosting.java`, `infra/ConditionalReadJob.java` | Condicionais e a gravação da venda condicional no ledger REAL |
| `realexits` | `fiscal/*` | Exportação fiscal CSV pelo `RealLedger.streamEntries` |
| `bots` | `worker/RealCycle.java`, `application/RealDecisionStep.java`, `domain/RealSizing.java` | Ramo REAL do ciclo |
| `bots` | `domain/BotMarket.java`, `application/RealBotOwnershipImpl.java` | Modo do bot e posse para o M6 |

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
- **REAL em inteiros (2026-11-21)**: lamports e unidades base em `long` com `Math.addExact`/`subtractExact`, frações
  por `BigInteger` (`ceilShare` no P&L, divisão para baixo nas posições); preços em `BigDecimal` com 18 casas para baixo;
  nunca ponto flutuante.
- **Inversão de dependência para o REAL (2026-11-21)**: o `realexecution` declara `PositionExitGuard` e
  `RealExitChanges` e o `realexits` as implementa, com implementação vazia quando o módulo está desligado; nenhum módulo
  importa `realexits`.
- **Lançamentos REAL com referência e chave estáveis (2026-11-21)**: `order:<realOrderId>` com chave
  `<realOrderId>:<KIND>`; `conditional:<id>` com chave `cond:<assinatura>:fill` e `:fee`; o mesmo fato nunca entra duas
  vezes.
- **Máquinas de estado com compare-and-set (2026-11-21)**: `RealOrderStateMachine` e `ConditionalStateMachine`, com
  transição gravada só quando o estado de origem confere.

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

### Features analisadas em 2026-11-21

- **`src/features/bots`** (16 arquivos, 3 testes): `BotList`, `BotCreate`, `BotWizard`, `BotDetail`, `LimitFields`,
  `Dialog`, `states.ts`, `validation.ts`, `decimal.ts`, `messages.ts`. Todos os textos dizem "PAPER" ("Bots PAPER",
  "Iniciar bot PAPER"); `BotCreate` chama `api.createBot({ templateId, name })` sem `market`; `BotDetail` busca o P&L do
  bot por `api.getPnl({ from, to, botId })` e mostra as posições abertas com `BotTradesTable` (de `features/pnl`).
- **`src/features/real`** (50 arquivos, 17 testes): `api.ts` (conversão dos DTOs REAL, `realReads`, erros), `money.ts`
  (lamports e unidades base em `bigint`, a exceção nomeada à regra de decimal), `session-real.ts`, `sign-transaction.ts`,
  `conditionals.ts`, `hooks/` (`useLoad`, `useMoneyAction`, `useSigningSession`, `useTracking`), `components/`
  (`RealPnlCard`, `DashboardRealPnl`, `ConditionalDialog`, `ConditionalsTab`, `FiscalExportPanel`, `SigningBanner`,
  `SigningDialog`, `ModeSelector`, `RealBadge` e outros), `screens/` (`OverviewScreen`, `ActivationScreen`,
  `DepositWithdrawScreen`, `ModeScreen`, `OrdersScreen`), `real-lint.test.ts`.
- **`src/features/dashboard`** (28 arquivos, 7 testes): `DashboardScreen` (cartões `BotsCard`, `PnlCard` PAPER e
  `DashboardRealPnl`), `DashboardProvider`, `dashboard-reducer.ts`, `dashboard-loader.ts`, leitor SSE por `fetch`
  (`sse-reader.ts`, `stream-client.ts`), `envelopes.ts` (validação dos eventos; o resumo do bot já traz `market`),
  `lint-rules.test.ts`.
- **`src/app/bots`** (`page.tsx`, `[botId]/page.tsx`, `new/page.tsx`, `new/[botId]/page.tsx`) e **`src/app/pnl`**
  (`page.tsx` que monta `features/pnl/PnlScreen`): páginas finas, com comentários "PAPER beta".
- **Área REAL** em `src/app/real/` (`/real`, `/real/ativacao`, `/real/carteira`, `/real/deposito-saque`, `/real/modo`,
  `/real/ordens`), listada e não lida nesta execução.
