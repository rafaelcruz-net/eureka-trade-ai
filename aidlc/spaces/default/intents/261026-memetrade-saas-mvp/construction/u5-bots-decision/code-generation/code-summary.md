# Resumo da geração de código — U5 Bots e decisão

Os 22 passos do plano aprovado foram executados em ordem. Cada caixa foi marcada ao terminar o passo.

A lista completa de caminhos fica em [source-manifest.json](source-manifest.json), e o rastreio em [traceability.json](traceability.json). A documentação técnica está em `docs/api/u5-bots-decision.md`.

## Arquivos criados e alterados

### Backend — novos (`apps/api`)

- **Módulo `bots`:**
  - `bots.api`: C16 `BotAdministration`, C18 `BotChangeMarks`, `BotState` e `PauseReason`.
  - `bots.domain`:
    - máquina de estados pura `BotStateMachine` e `BotConfigValidator`;
    - modelos v1 (`StrategyTemplates`), `Bot`, `BotLimits`, `TenantScope` e `BotRef`.
  - `bots.application`: `BotCommandService`, `BotQueries`, `BotAdministrationImpl` e `BotChangeMarksImpl`.
  - `bots.infra`:
    - `JdbcBotRepository`, `JdbcCycleRepository` e `LockOrder`;
    - `CycleAuthContext` do worker, `BotAudit`, `BotProperties`, `BotsConfig` e `BotJson`.
  - `bots.worker`:
    - `SlotDispatcher`, `CycleRunner` e `CycleFailureRecorder`;
    - `MidnightJob`, `ClosingCheckJob`, `DecisionPurgeJob`;
    - `BotSchedules` e `BotCyclesHealthIndicator`.
  - `bots.web`: `BotController`, `BotDtos`, `BotWebErrors`, `DashboardMarkPoller` e `DashboardStreamController`.
  - `bots.loadtest`: `LoadtestBotSeed` e `CycleStopwatch`.
- **Módulo `decision`:**
  - `decision.domain`: `DecisionEngine`, as estratégias `MomentumVolumeStrategy` e `RangeBreakoutStrategy`, `ExitRules`, `TokenSelectionResolver` e `OrderSizer`.
  - `decision.infra`: `DecisionJson` e `JdbcDecisionRepository`.
- **Configuração e migração:**
  - `shared.config.ProcessRole` e `ProcessRoleCondition`;
  - `application-worker.yml`;
  - migração `0010-bots-decision.sql`.

### Backend — alterados de Units anteriores (todos permitidos pelo plano)

- **`@ProcessRole(API)`** nas tarefas, nos componentes web e nos componentes de carga das Units U1 a U7. Os arquivos estão em `audit`, `identity`, `marketdata`, `scanner`, `ledger`, `pnl`, `riskcontrol` e `shared.web`; a lista completa está no manifesto.
- **`IdentityConfig`:** cinco beans web passam a existir só na API.
- **Outros arquivos:**
  - `RuntimeSettingsGuard`: faixas por processo.
  - `IdempotentOperation`: 7 operações `BOT_*`.
  - `ErrorCode` e `AuditActions`.
  - Javadoc do `LedgerReadModel`.
  - `route-registry.yml`.
  - `application.yml`, `application-test.yml` e `application-loadtest.yml`.
  - `db.changelog-master.yaml`.
- **Fonte simulada da U4:** `SimulatedMarketSource`, `MarketDataProperties` e `MarketDataConfig` ganham o cenário de sinais frequentes. Ele é aceito só no perfil `test`.
- **OpenAPI:** `openapi/openapi-guest.yaml` foi regenerado.

### Testes do backend

- **Novos:**
  - suítes U5 em `bots`, `decision`, `contract`, `ops`, `config` e `architecture`;
  - `FrequentSignalsScenarioTest`;
  - apoio em `testsupport`: `AbstractU5IT`, `AbstractU5WorkerIT`, `U5Bots`, `U5Outbox` e outros.
- **Alterados por consequência da U5:**
  - `U4ContextIT`, `U6ContextIT`, `U7ContextIT`;
  - `U4SettingsTest`, `U6SettingsTest`, `U7SettingsTest`, `AuditPropertiesTest`;
  - `ComposeFilesTest`, `WorkflowsTest`, `MigrationsIT`;
  - `RouteRegistry`, `RouteRegistryTest`;
  - `GuestOpenApiSlice`, `AuditArchitectureTest`.

### Frontend (`apps/web`)

- **Telas:** `src/features/bots/` (lista, criação, assistente S4, tela S5, diálogos, validação, decimais exatos, mensagens pt-BR) e as páginas `src/app/bots/**`.
- **Testes:** `rules.test.ts`, `BotScreens.test.tsx` e `BotDetail.test.tsx`.
- **Ajustes:**
  - `guest-api.ts`, `client.ts` (`PUT`) e `schema.d.ts` regenerado;
  - `AccountMenu.tsx` (link Bots), `i18n/messages.ts`, `globals.css`;
  - `test-support.tsx`;
  - `eslint.config.mjs`, com a regra de `DecimalString` estendida às telas de bots;
  - `ScannerScreen.test.tsx`, com a nova ordem de tabulação do cabeçalho.
- **Smoke:** `e2e/u5-bots-decision/smoke.spec.ts`.

### Ambiente, carga e documentação

- **Docker Compose:**
  - `docker-compose.yml`: novo serviço `worker`, `SCHEDULER_POOL_SIZE` 14 na API e a conta de 41 conexões no comentário.
  - `infra/docker-compose.test.yml`: perfil do worker, porta `127.0.0.1:8082` e variáveis `LOADTEST_U5_*` e `MARKET_SIMULATED_SCENARIO`.
- **`.env.example`:** comentário do agendador da API.
- **Carga:** `scripts/load-test.sh` e `scripts/load/load-test.mjs` ganham o ramo `u5`, e `.github/workflows/load-test.yml` aceita `u5`.
- **Documentação:** `docs/api/u5-bots-decision.md`.
- **Contratos:** `inception/contract-design/contract-summary.md`, atualizado no Passo 3.

## Decisões principais

### Tarefas por processo

O `ProcessRoleContextIT`, o `WorkerProcessRoleContextIT` e o `ProcessJobs` conferem que cada tarefa existe só no seu processo. A varredura do classpath também falha se aparecer uma tarefa sem papel.

**API** (`@ProcessRole(API)`):

| Unit | Tarefas |
|---|---|
| U2 | `AuditRelayJob`, `AuditJobSchedule`, `AuditStartup` (recuperação das rodadas atrasadas) |
| U3 | `IdempotencyCleanupJob`, `LoginLimiterEvictionJob`, `SmokeAccountSeeder` e `SmokeInviteSeeder` (perfil `test`) |
| U4 | `MarketCollectorJob`, `MarketHistoryCleanup`, `ScannerRateLimitEvictionJob` |
| U7 | `PendingCloseJob`, `EquityMarkingJob`, `ReconciliationJob`, `ReconciliationRunLimiterEvictionJob`, `LedgerJobsHealth`, os componentes de `ledger.loadtest` |
| U6 | `RiskEvaluationPurgeJob`, os componentes de `riskcontrol.loadtest` |
| U5 | `DashboardMarkPoller` (canal C7), `LoadtestBotSeed` |

**Worker** (`@ProcessRole(WORKER)`): na U5, `SlotDispatcher`, `CycleRunner`, `MidnightJob`, `ClosingCheckJob`, `DecisionPurgeJob`, `BotSchedules`, `BotCyclesHealthIndicator`, `CycleAuthContext` e `CycleStopwatch`.

### Faixas da guarda (`RuntimeSettingsGuard`)

| Processo | Pool de banco | Agendador |
|---|---|---|
| `api` | 10 a 50 | 14 a 32 (padrão 14; era 13) |
| `worker` | 14 a 30 (Compose fixa 14) | 4 a 8 (Compose fixa 4) |

Um valor fora da faixa impede a subida, com o nome da variável na mensagem. `MEMETRADE_PROCESS` aceita só `api` ou `worker`.

### Token neutro

`BotCommandService.NEUTRAL_TOKEN` é `solana:11111111111111111111111111111111`. Ele é usado só para ler a visão de risco (C13) na retomada de um bot com seleção só por regras, que não tem token explícito. Ele nunca vira ordem nem posição.

### Ressalva de ordem das marcas de mudança (C18)

`bot_change_seq` é uma sequência do PostgreSQL. Ela é renovada na mesma transação da mudança, e o valor é tomado quando a linha é gravada, não no commit. Duas transações concorrentes podem confirmar fora da ordem dos números.

- O poller compara a marca da pessoa por diferença, não por "maior". A marca é o máximo de `change_seq` dos bots da pessoa. Quando muda, o poller relê o estado inteiro pela interface pública.
- **Caso restante:** a transação A pega o número 10 e a B pega o 11. B confirma, o poller lê 11 e envia o estado. Em seguida A confirma, mas o máximo continua 11. A mudança de A só chega ao canal na próxima mudança de qualquer bot da pessoa, ou quando a tela reabre e lê o estado.
- **Por que é raro:** duas mudanças de bots da mesma pessoa, a menos de uma leitura (2 s) uma da outra, confirmando na ordem inversa.
- **Quem consome a C18 (U9)** não deve usar a marca como relógio total nem supor que cada mudança gera um número maior que todos os já vistos.
- **Correção possível, se a U9 precisar:** marca tomada no commit (por exemplo, uma tabela de marcas atualizada por último na transação, sob a trava do dono).

### Formatador

Rodei `mvnw spotless:apply` no backend e `prettier --write` nos arquivos do frontend da U5. Os dois são formatadores do projeto exigidos pela CI. Nenhum outro arquivo foi alterado por comando de shell.

## Saídas vermelhas dos passos com teste antes do código

| Passo | Vermelho registrado |
|---|---|
| 4 (esquema) | `BotSchemaIT`: 10 erros, `relation "bot_change_seq"` / `"bot_owner" does not exist` |
| 5 a 7 (máquina de estados, motivos, decisão) | 77 testes, 76 falhando com `UnsupportedOperationException: red phase`; 1 passando (invariante de `BotStatus`) |
| 8 a 12 (repositórios e travas, comandos com idempotência e limite de 3 ativos, ciclo, meia-noite, encerramento) | compilação dos testes falhou com 84 `cannot find symbol` (classes ainda inexistentes) |
| 13 (rotas) | `BotRoutesIT`: 11 de 12 falhando (rotas inexistentes) |
| 14 (canal ao vivo) | compilação falhou: `cannot find symbol DashboardMarkPoller` |

Depois de cada vermelho, o código foi implementado até ficar verde e refatorado com a suíte verde.

## Resultados

### Backend

Comando: `apps/api/mvnw -f apps/api/pom.xml verify`, com JaCoCo unitário e de integração unidos e o portão de 80%.

- **Resultado:** `BUILD SUCCESS` em 8 min 16 s.
  - Testes unitários: 813, sem falhas.
  - Testes de integração: 647, sem falhas.
  - `All coverage checks have been met`.
- **Cobertura de linhas:**
  - total unido: 93,78% (11.033 de 11.765);
  - pacotes `bots` e `decision`: 92,17% (2.721 de 2.952).

Resultados por classe da U5 e das classes alteradas (testes / falhas):

| Classe | Testes | Falhas |
|---|---|---|
| `BotLifecycleCatalogTest` (L1 a L14) | 29 | 0 |
| `DecisionCatalogTest` (D1 a D18) | 26 | 0 |
| `DecisionPropertyTest` (semente 20261028, 600 casos) | 1 | 0 |
| `DecisionReplayTest` | 5 | 0 |
| `TokenSelectionResolverTest` | 5 | 0 |
| `BotConfigValidatorTest` | 11 | 0 |
| `BotSchemaIT` | 10 | 0 |
| `BotRepositoriesIT` | 8 | 0 |
| `LockClauseSourceTest` | 2 | 0 |
| `BotCommandsIT` | 17 | 0 |
| `BotAdministrationIT` | 5 | 0 |
| `CycleIT` | 12 | 0 |
| `CycleFailureIT` | 5 | 0 |
| `LockOrderIT` (50 repetições cada, 155 s) | 2 | 0 |
| `MidnightIT` | 5 | 0 |
| `ClosingCheckIT` | 2 | 0 |
| `DecisionPurgeIT` | 2 | 0 |
| `BotRoutesIT` | 12 | 0 |
| `DashboardStreamIT` | 8 | 0 |
| `U5OpenApiContractTest` | 4 | 0 |
| `U5ObservabilityIT` | 4 | 0 |
| `BotsArchitectureTest` | 10 | 0 |
| `U5ContractsTest` | 3 | 0 |
| `U5SettingsTest` | 6 | 0 |
| `ProcessRoleContextIT` | 4 | 0 |
| `WorkerProcessRoleContextIT` | 4 | 0 |
| `LoadtestBotSeedIT` | 4 | 0 |
| `CycleStopwatchTest` | 1 | 0 |
| `FrequentSignalsScenarioTest` | 4 | 0 |
| `MigrationsIT` | 17 | 0 |
| `RouteRegistryTest` | 9 | 0 |
| `ComposeFilesTest` | 21 | 0 |
| `WorkflowsTest` | 9 | 0 |
| `U4SettingsTest` | 7 | 0 |
| `U6SettingsTest` | 4 | 0 |
| `U7SettingsTest` | 4 | 0 |
| `AuditPropertiesTest` | 7 | 0 |

Duas correções vieram do `verify` inteiro e não aparecem nas execuções isoladas:

- **Padrão do agendador.** `U7SettingsTest` e `AuditPropertiesTest` ainda esperavam 13 threads. Eles foram atualizados para o padrão 14 da U5.
- **Eventos de auditoria pendentes.** As suítes U5 deixavam centenas de eventos pendentes no outbox, com o relógio fixo no passado. Isso tomava o lote de 100 que `AuthSessionIT` e `AuthVerifyIT` repassam e leem logo depois da própria ação, e esses dois testes falhavam. As bases `AbstractU5IT` e `AbstractU5WorkerIT` agora repassam o outbox pelo relay real depois de cada teste (`U5Outbox`). Nenhum teste da U3 foi alterado.

### Frontend

Comandos: `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage`, depois `lint`, `typecheck`, `format:check` e `check:api`.

- **Testes:** 23 suítes e 245 testes, todos passando. Os 3 arquivos de bots somam 39 testes.
- **Cobertura de linhas:** 95,69% (1.758 de 1.837). Instruções 95,47%, ramos 89%, funções 92,36%.
- **Verificações:**
  - ESLint sem erros, inclusive a regra de `DecimalString` nas telas de bots;
  - `tsc` sem erros;
  - Prettier em ordem;
  - `schema.d.ts` igual ao `openapi-guest.yaml`.

### Smoke e `verify.sh`

- **Smoke da U5:** `e2e/u5-bots-decision/smoke.spec.ts` compila e foi listado pelo Playwright (`playwright test --list`). Ele **não foi executado**:
  - ele só entra no `verify.sh` por ação do responsável do projeto;
  - um hook recusou a criação de uma cópia temporária do `verify.sh` com a linha nova.
- **`bash scripts/verify.sh`:** executado sem alteração; saiu com código 0 e a mensagem "walking skeleton verified".
  - Na primeira tentativa, com a saída redirecionada para um arquivo, um hook bloqueou o comando. A segunda tentativa, sem redirecionamento, rodou.
  - O Compose subiu também o `memetrade-worker-1`.
  - Os 6 testes Playwright da linha atual (U1, U2, U4 e U7) passaram em 1,5 min.
  - A varredura dos logs não achou o endereço da carteira.
  - O ambiente foi removido no fim; `docker ps` não mostra contêineres do projeto.

## Testes não executados e por quê

| O que | Comando | Motivo |
|---|---|---|
| Smoke Playwright da U5 | `pnpm --dir apps/web exec playwright test e2e/u5-bots-decision` (dentro do `verify.sh`) | Só roda no ambiente que o `verify.sh` sobe. A linha do `verify.sh` é mudança do responsável do projeto, e a cópia temporária foi recusada por um hook |
| Cenário de carga `u5` | `SPRING_PROFILES_ACTIVE=test,loadtest LOAD_SCENARIO=u5 bash scripts/load-test.sh` | São 30 minutos de carga, mais a preparação de 60 bots e 400.000 decisões, com api, worker, web e PostgreSQL ao mesmo tempo. Isso é inviável nesta sessão (8 GB para o Docker e o tempo da geração). O ferramental foi conferido com `node --check`, `bash -n`, `WorkflowsTest`, `LoadtestBotSeedIT`, `CycleStopwatchTest` e `FrequentSignalsScenarioTest`. NFR2.1 a 2.3, 2.5, 2.6, 3.1 e 3.2 ficam adiados |
| Suíte no Linux | job `backend` e `frontend` da CI | Esta geração rodou só no Windows. O Linux roda na CI do PR (NFR13.1) |

Nenhum contêiner ficou rodando: os testes usam Testcontainers, que encerra o contêiner, e o `verify.sh` derruba o ambiente na saída.

## Desvios do plano

1. **Registro de rotas.** As entradas do `route-registry.yml` foram acrescentadas nos Passos 10, 13 e 14, junto das rotas e da tarefa. O plano previa o Passo 3.
2. **Testes de travas.** L8 e L11 foram testados em `BotCommandsIT` e `LockOrderIT`. Não houve um `SystemPauseIT` separado.
3. **Código do 503.** O 503 por espera de trava usa o código já existente `SERVICE_UNAVAILABLE`, não `SERVICE_BUSY`.
4. **Rota nova.** Foi acrescentada `GET /api/v1/bots/{botId}` (detalhe do rascunho e dos limites), registrada no contrato C4.
5. **Eventos do canal.** `trade.recorded` leva um payload reduzido, e `pnl.updated` é só um aviso de marca. O `threshold` do fator é opcional e ganhou `label`.
6. **`LedgerChangeMarks`.** A U7 não publicou esse bean. O `DashboardMarkPoller` usa `Optional<LedgerChangeMarks>` e, sem ele, usa como marca o instante da operação mais recente da pessoa.
7. **Alcance do `@ProcessRole`.** Ele foi aplicado também aos componentes web e a cinco beans do `IdentityConfig`, para o worker subir sem segredos web.
8. **Testes de outras Units.**
   - O scanner de `RouteRegistry` (teste) não avalia mais condições, para enxergar `BotSchedules`.
   - `AuditArchitectureTest` permite construir `Principal` em `bots.domain.Bot` e `BotRef`, como o design da U5 prevê.
9. **Botão no scanner.** O link "Usar em bot PAPER" não foi acrescentado à tela do scanner da U4. Só o parâmetro `?chain=&mint=` é aceito pelo assistente.
10. **Posições na S5.** O diálogo de encerramento e o aviso de bot pausado usam as linhas por token do P&L (C5) com não realizado diferente de zero ou `unknown`, porque não há rota de posições.
11. **Auditoria e pausa do sistema.** O evento de start sai uma vez por mudança de estado. A pausa do sistema grava um evento por bot, com o ator `system`.
12. **Carga.**
    - As pessoas do cenário `u5` vêm de `LOADTEST_U5_PEOPLE` (padrão 20), não de `LOADTEST_LEDGER_PEOPLE`. Com `LOADTEST_LEDGER_PEOPLE > 0` e `LOADTEST_CYCLE_BOTS=0`, o gerador da U7 recusa a subida (`cycle-bots must cover every person`). Por isso, o ramo `u5` deixa a carga da U7 em zero.
    - Os bots são criados pelo `load-test.mjs` pelas rotas C4 reais, não pelo `LoadtestBotSeed`. A regra ArchUnit impede `bots.loadtest` de depender do contexto de autenticação de carga da U7, que é interno. O semeador só grava as decisões.
13. **Outbox de auditoria.** `U5Outbox` foi criado nas bases de teste da U5, como explicado em "Resultados".

## Pontos em aberto

- **Responsável do projeto:**
  - Acrescentar `e2e/u5-bots-decision` à linha do Playwright em `scripts/verify.sh`. A linha fica assim:

    ```bash
    "${PNPM[@]}" --dir apps/web exec playwright test e2e/u1-walking-skeleton e2e/u2-audit-trail e2e/u4-discovery e2e/u7-ledger-pnl e2e/u5-bots-decision
    ```

    O smoke da U5 vai por último para não mexer nos estados vazios que o smoke da U7 confere.
  - O ponto de controle da U5 depende de o `verify.sh` passar com esse smoke.
  - Rodar o cenário de carga `u5` (`load-test.yml` com `scenario: u5`) e guardar o relatório em `docs/perf/u5-bots-decision-<data>.md`.
- **U7:** publicar o bean `LedgerChangeMarks`. Até lá vale o substituto descrito no desvio 6.
- **U8:**
  - Usar só a C16 (`BotAdministration`): pausa do sistema, liberação com `ADMIN_RELEASED` e resumo do painel.
  - AC7.2.1 e AC7.2.5 (confirmação, indicador, rota do kill switch, 403 e auditoria do administrador) são da U8.
- **U9:** usar só a C18 (`BotChangeMarks`) e o canal C7, sem tratar a marca como ordem total (ver a ressalva acima).

## Volta 1 de Build and Test (2026-11-06)

### Causa

No Build and Test, o cenário de carga `u5` parou na semente (falha F3 em [test-results.md](../../build-and-test/test-results.md)). `LoadtestBotSeed.seedDecisions` gravava um ciclo por minuto passado a partir de `now - 1 min`. Quando o worker real já tinha rodado um ciclo do bot num desses minutos, `JdbcCycleRepository.tryStart` não inseria a linha (`on conflict (bot_id, slot_start) do nothing`) e devolvia `false`. A semente ignorava esse retorno, `finish` não atualizava nada, e a decisão era gravada com um `cycle_id` inexistente. A chave estrangeira `decision_cycle_id_fkey` recusava a gravação, e a preparação terminava em `FAILED`.

### Correção

Só `apps/api/src/main/java/com/memetrade/bots/loadtest/LoadtestBotSeed.java` mudou no código de produção. Ele continua só no perfil `loadtest` (`@Profile("loadtest")`) e só no processo `api`.

- Antes de semear, a semente lê o `slot_start` mais antigo do bot em `bot_cycle`, filtrando por `bot_id`, `tenant_id` e `owner_user_id`. Ela semeia só os minutos anteriores a esse ciclo. Um bot sem ciclo usa o limite de antes (`now - 1 min`).
- Quando `tryStart` devolve `false`, a semente lança `IllegalStateException` com o minuto e o id do bot ("the batch was rolled back"). A exceção sai de dentro do `TransactionTemplate`, então o lote inteiro (ciclos e decisões) é desfeito. Nenhuma decisão fica apontando para ciclo inexistente. O erro já existente em `prepare()` registra a falha e deixa a saúde `loadtestBots` em `FAILED`.
- Para essa leitura, o construtor recebe um `JdbcClient`, que o Spring injeta. As regras do ciclo, da decisão e do ledger não mudaram, e `JdbcCycleRepository` também não.
- Não há valor monetário novo. A mensagem de erro só leva o id do bot e o instante, nada sensível.
- O formatador do projeto (`mvnw spotless:apply`) ajustou os dois arquivos desta volta. O primeiro `verify` tinha falhado só no `spotless:check`.

### Testes novos (`LoadtestBotSeedIT`)

| Teste | O que confere |
|---|---|
| `aCycleTheWorkerAlreadyRanInASeededMinuteIsKeptAndNoDecisionPointsToAMissingCycle` | Um ciclo `COMPLETED` do worker em `now - 3 min`. A semente de 10 decisões termina, grava 10 decisões e 10 ciclos novos. Nenhuma decisão fica sem o seu ciclo (`cycle_id` e `decision_id` casados). Todas as decisões são anteriores ao ciclo do worker, que continua intacto (status, `decision_id` nulo, `slot_start`, `finished_at`). |
| `aSlotTheSeedCannotClaimStopsTheSeedWithTheBotAndTheMinuteAndKeepsNothingOfThatBatch` | Um `JdbcCycleRepository` de teste recusa o minuto `now - 2 min`. A semente lança `IllegalStateException` com o id do bot e o minuto, e o bot fica sem decisão e sem ciclo, porque o lote foi desfeito. |

### Vermelho registrado (antes da correção)

Comando: o de "Volta 1: regressão da semente" em [unit-test-instructions.md](unit-test-instructions.md).

- `Tests run: 6, Failures: 1, Errors: 1`, com `BUILD FAILURE`. Os 4 testes que já existiam passaram.
- Primeiro teste novo: `DataIntegrityViolationException` em `insert into decision (...)`, com `violates foreign key constraint "decision_cycle_id_fkey"` e "Key (cycle_id)=(…) is not present in table "bot_cycle"". É a mesma falha do Build and Test.
- Segundo teste novo: esperava `IllegalStateException` e veio a mesma `DataIntegrityViolationException`.

### Resultados (depois da correção)

| Comando | Resultado |
|---|---|
| Volta 1: regressão da semente | `LoadtestBotSeedIT`: 6 testes, 0 falhas, `BUILD SUCCESS` |
| Volta 1: backend da U5 (`-Dgroups=u5`) | unitários 111 e integração 203, 0 falhas, `BUILD SUCCESS` |
| Volta 1: suíte inteira (`mvnw verify`) | unitários 890 e integração 754, 0 falhas; `All coverage checks have been met`; `BUILD SUCCESS` |

- **Cobertura de linhas (relatório unido do JaCoCo):**
  - total: 93,86% (12.349 de 13.157);
  - pacotes `bots` e `decision`: 92,79% (2.819 de 3.038);
  - `LoadtestBotSeed`: 111 de 124 linhas.
- **Nada foi reduzido.** O piso de 80% continua igual.
- **Comparação com a geração.** Os totais de testes e de linhas são maiores que os da geração (813 e 647 testes). A árvore de trabalho já tinha arquivos de outras Units ainda não versionados (por exemplo, `AbstractU9IT`, `U9SettingsTest` e `DashboardChannelLimitIT`). Esta volta não os alterou, e eles entraram na suíte inteira.
- **Logs de ERROR durante a suíte.** Saíram linhas `KILL_SWITCH_APPLY_FAILED` e `Audit job failed`. Elas vêm dos testes de falha injetada das Units anteriores, que passaram.
- **Cenário de carga `u5`.** Não rodou nesta volta. A nova execução fica para o Build and Test, e NFR2.1 a NFR2.3, NFR2.5, NFR2.6, NFR3.1 e NFR3.2 continuam `Deferred` no `traceability.json`.
