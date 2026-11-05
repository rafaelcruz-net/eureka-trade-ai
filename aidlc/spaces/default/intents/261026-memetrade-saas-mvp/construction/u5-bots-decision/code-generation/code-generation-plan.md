# Plano de geração de código — U5 Bots e decisão

## Summary

- Builds: assistente e ciclo de vida dos bots PAPER, motor de decisão com as duas estratégias, ciclo de 5 minutos no novo processo worker (com a barreira da U6 e a execução da U7), pausa de sistema, marcas e canal ao vivo, telas S4, lista e S5
- Touches: `apps/api/` (módulos novos `bots` e `decision`, papel do processo nas tarefas das Units anteriores, migração `0010`, OpenAPI, configuração, testes), `apps/web/src/features/bots/`, `apps/web/src/app/bots/`, `apps/web/e2e/u5-bots-decision/`, `docker-compose.yml`, `infra/docker-compose.test.yml`, `.github/workflows/load-test.yml`, `scripts/load-test.sh`, `scripts/load/`, `docs/`, `contract-summary.md`
- Tests: cerca de 220 testes de backend (catálogos D e L escritos antes do código, propriedade, integração com U6 e U7 reais, concorrência, travas, ArchUnit, contrato), cerca de 30 de frontend, um smoke Playwright do ciclo de vida e o cenário de carga `u5`, com piso de 80% de linhas

## Entradas

A Unit é a `u5-bots-decision` de [unit-of-work.md](../../../inception/units-generation/unit-of-work.md). As histórias são US4.1 a US4.5, US4.7 a US4.10, US4.12 e US7.2.

| Documento | O que dá ao código |
|---|---|
| [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), [entities.md](../functional-design/entities.md), [frontend-components.md](../functional-design/frontend-components.md) | Fluxos F1 a F11, BR1.1 a BR9.1, entidades, catálogos D1 a D18 e L1 a L14, telas |
| [nfr-requirements/](../nfr-requirements/) e [nfr-design/](../nfr-design/) | Metas NFR2.1 a NFR14.1, `LockOrder`, gravador de falha depois do rollback, pool de 14, canal com leitor único, `CycleAuthContext` |
| [infrastructure-specification.md](../infrastructure-design/infrastructure-specification.md), [cicd-pipeline.md](../infrastructure-design/cicd-pipeline.md), [monitoring-design.md](../infrastructure-design/monitoring-design.md) | Serviço `worker`, `@ProcessRole`, tabela de trabalhos por processo, guarda por processo, `0010`, cenário `u5`, smoke, rollback |
| [contract-summary.md](../../../inception/contract-design/contract-summary.md) | C4, C7, C9, C10, C11, C12, C13, C14, C15, C16, C18, C20 |

## Decisões de implementação

- **Pacotes.**
  - `bots`, com `api`, `application`, `domain`, `infra`, `web`, `worker` e `loadtest`.
  - `decision`, com `domain` e `infra`.
  - Os dois só dependem das APIs públicas listadas no security-design.md.
- **Aritmética.** `BigDecimal` com `HALF_EVEN`, nas escalas da tabela "Escalas".
- **Achados abertos das revisões, resolvidos aqui:**
  - **Token neutro do resume (BR2.4).** Vai para `snapshot` como dois argumentos: `chain = "solana"` e `tokenMint = "11111111111111111111111111111111"`.
  - **Marca do canal.** A marca por `changeSeq` máximo pode perder uma mudança quando duas transações da mesma pessoa confirmam fora de ordem. O efeito é só atraso de tela, registrado no resumo. A próxima mudança ou o `heartbeat` com nova leitura completa (a cada 60 s) corrige.
  - **Coleta da U4 a cada 30 s.** Um teste de configuração falha se o padrão mudar sem revisão.
  - **`identity.api`.** `AuthContext` e `Principal` existem em `identity.api`. A conferência é feita no Passo 1.
  - **Segredos do worker.** O `cicd-pipeline.md` ainda diz que o worker recebe as mesmas variáveis da API. Vale a especificação: o worker não recebe a chave de assinatura, as origens, os proxies, as carteiras de admin nem os logins de retenção.
  - **Porta do worker.** O `ComposeFilesTest` confere que o `docker-compose.yml` principal não publica porta do worker e que só o overlay de teste publica `127.0.0.1:8082`.
  - **Healthcheck do worker.** Usa `interval` de 10 s, `timeout` de 5 s, `retries` de 6 e `start_period` de 60 s, como a API.
  - **Memória.** Na tabela de memória, o PostgreSQL entra com o limite real do Compose, 1 GB.
- **Contratos.** Antes do código da U5, entram no `contract-summary.md` e no OpenAPI:
  - na C4: os acréscimos `ADMIN_RELEASED`, `WAITING_ACTIVE_SLOT`, os motivos de NO_TRADE novos, `exitReason`, `requestedQuantity`, `limitsVersion`, `factors[].token` e o mapeamento `maxDrawdown`/`maxSlippage`;
  - os usos novos de `LedgerReadModel` e `PortfolioRiskView`.
- **Fora do agente.**
  - A linha do Playwright no `scripts/verify.sh` é mudança do responsável do projeto. O texto é preparado no Passo 22.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "enterprise",
  "test_strategy": "comprehensive",
  "project_type": "greenfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo.\n- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`; o piso de\n  cobertura abaixo é somado a ela e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente\n  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam\n  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A\n  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo\n  no Maven e limite de cobertura do executor de testes do frontend). O piso não\n  pode ser reduzido para fazer uma etapa passar.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: nenhum caminho assina ou envia transação com a\n    configuração padrão;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem\n  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,\n  mint ou dados pessoais reais.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: o smoke de ponta a ponta roda em toda mudança; a suíte\n  completa roda no merge para `main`.\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no\n  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas."
    }
  ],
  "obligations": {
    "strategy": "comprehensive",
    "strategy_volume": [
      "Ten to fifteen tests per component.",
      "Unit, integration, and E2E tests.",
      "Add performance and security tests when NFRs demand them."
    ],
    "scope_floor": [
      "Meet an 80% line-coverage floor.",
      "Run the selected tests in CI before merge."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "custom",
    "runner_step": "Bootstrap the minimal test runner/configuration and record the exact unit-scoped command.",
    "runner_ready_before_first_test": true,
    "testable_layers": [
      "Data model / database behavior",
      "Repository / data access",
      "Business logic",
      "API / endpoint",
      "Frontend behavior"
    ],
    "steps": [
      "Project structure and production configuration skeleton.",
      "Bootstrap the minimal test runner/configuration and record the exact unit-scoped command.",
      "Custom ordering - No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
      "Implementation and tests - preserve that exact ordering; do not convert it to layer-local TDD.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:f1cb93725f957e70a8f4c222cf42d92297f54716d55a23775d600834a2d717d9",
  "contract_sha256": "sha256:2d166aad550ef9b43828422fd7cf817706d0f3d0e98b32e96e1d485322f4ec16"
}
```

## Como a ordem dos testes se aplica à U5

- **Núcleo financeiro, testes antes do código.** Vale para a máquina de estados e os motivos de pausa, a decisão, o ciclo, os comandos com a deduplicação e o máximo de 3 ativos, a meia-noite e o encerramento. Nesses passos, os testes nascem das regras e dos catálogos, rodam e falham (vermelho registrado no resumo), e só então vem o código, até ficarem verdes. A refatoração é feita com os testes verdes.
- **Testes depois de cada camada.** Valem para configuração e papel do processo, telas, smoke, carga e documentação.
- **Executores prontos.** O Passo 2 deixa o comando da U5 rodando antes do primeiro vermelho (Passo 4).
- **Suítes anteriores.** Continuam verdes com o `@ProcessRole`, o agendador da API em 14 e a `0010`.

## Passos

### Fundação, executores e contratos

- [x] **Passo 1 — Papel do processo e configuração.**
  - Conferir no código a lista de trabalhos (`@Scheduled`, ouvintes de partida, runners, `SmartLifecycle`, semeadores e componentes de perfil) e confirmar que `identity.api.AuthContext` e `identity.api.Principal` existem.
  - Criar `@ProcessRole` e aplicá-lo conforme a tabela "Trabalhos por processo".
  - `RuntimeSettingsGuard` por processo (API com agendador de 14 a 32; worker com 4 a 8 e pool de 14 a 30).
  - `application.yml`: `memetrade.process` e as propriedades da U5.
  - `docker-compose.yml` e overlay de teste: serviço `worker` com `HealthProbe` e a conta de conexões em comentário.
  - Depois, os testes: `ProcessRoleContextIT` (API e worker), guarda, `ComposeFilesTest` (porta só no overlay, segredos fora do worker), worker sobe sem as variáveis web, padrão da coleta da U4 de 30 s.
  - Fundação; NFR10.9, NFR2.4.
- [x] **Passo 2 — Base de testes e comando da Unit.**
  - Marcador `u5`, `AbstractU5IT` com os contextos `api` e `worker`, `MutableClock`, dublês da C11 e da C12, construtor de bots e de contas pela U7.
  - Um teste de subida com o marcador `u5` roda verde pelo comando de [unit-test-instructions.md](unit-test-instructions.md).
  - Fundação; NFR12.1.
- [x] **Passo 3 — Contratos e interfaces públicas.**
  - `contract-summary.md`: acréscimos da C4 e usos novos de `LedgerReadModel` e `PortfolioRiskView`; C7, C16 e C18 como implementadas.
  - Javadoc de `LedgerReadModel` citando a U5.
  - Operações `BOT_*` em `IdempotentOperation`; rotas C4 e C7 em `tenancy/route-registry.yml`.
  - `bots.api`: `BotAdministration`, `BotChangeMarks` e tipos.
  - Depois, os testes: registro de rotas e enum de idempotência.
  - Fundação; C4, C7, C16, C18.

### Banco de dados (vermelho → verde)

- [x] **Passo 4 — Migração `0010-bots-decision`.**
  - Vermelho: `BotSchemaIT` com:
    - `CHECK mode = 'PAPER'`;
    - unicidades (bot e fatia; dono; versão de limites; avaliação de `BotHolding`);
    - estratégias v1 semeadas com as faixas da BR4.3 a BR4.6;
    - `system_pause_state` com linha única;
    - sequência `bot_change_seq`;
    - `MigrationsIT` até a `0010`.
  - Verde: a migração.
  - US4.1, US4.4; BR1.1, BR3.2; NFR5.3.

### Lógica de negócio (vermelho → verde)

- [x] **Passo 5 — Máquina de estados e motivos de pausa.**
  - Vermelho: `BotLifecycleCatalogTest` com todas as combinações de transição (L9) e motivos (`PERSON`, `DAILY_LOSS`, `DRAWDOWN`, `SYSTEM`, `ADMIN_RELEASED`, `WAITING_ACTIVE_SLOT`), retomada com perda diária (L6) e meia-noite em memória (L3 a L5).
  - Verde: `BotStateMachine`.
  - US4.4, US4.7, US4.8, US7.2; BR2.1, BR2.4, BR2.5, BR2.6; NFR4.1.
- [x] **Passo 6 — Validação e padrões do assistente.**
  - Vermelho: parâmetros na faixa e no passo, seleção, saldo de 100 a 10.000, coerência dos limites e padrões proporcionais com intervalo de 240 s.
  - Verde: `BotConfigValidator`, `DefaultLimits`.
  - US4.1, US4.2, US4.9; BR1.2 a BR1.6, BR5.1; NFR6.2.
- [x] **Passo 7 — Decisão.**
  - Vermelho: `DecisionCatalogTest` com D1 a D18, `DecisionPropertyTest` com seed fixa e `DecisionReplayTest`; motivos de NO_TRADE por campo `unknown`.
  - Verde: `DecisionEngine`, `MomentumVolumeStrategy`, `RangeBreakoutStrategy`, `ExitRules`, `OrderSizer`, `TokenSelectionResolver`.
  - US4.5; BR4.1 a BR4.10; NFR4.1, NFR4.6, NFR8.1.

### Repositório, comandos e ciclo (vermelho → verde)

- [x] **Passo 8 — Repositórios e `LockOrder`.**
  - Vermelho: `TenantScope` em toda consulta; `LockOrder` com a conferência de ordem; teste de código-fonte que proíbe `FOR UPDATE` e `FOR SHARE` fora dele.
  - Verde: `JdbcBotRepository`, `JdbcDecisionRepository`, `LockOrder`.
  - US4.4; BR2.10, BR2.11; NFR5.1, NFR10.4.
- [x] **Passo 9 — Comandos.**
  - Vermelho: `BotCommandsIT` com:
    - criar, rascunho, start (com `fund`), pause, resume, stop (com `closePositions`) e limites;
    - idempotência (duplo clique em paralelo);
    - 3 ativos com ativações simultâneas (L1, L2);
    - kill switch e corrida (L8, L11);
    - `snapshot` com o token neutro;
    - `lock_timeout` com 503.
  - Verde: `BotCommandService`.
  - US4.2, US4.3, US4.7, US4.8, US4.9, US4.12, US7.2; BR2.2 a BR2.7, BR5.1; NFR4.2, NFR4.3, NFR10.6, NFR10.7.
- [x] **Passo 10 — Ciclo.**
  - Vermelho:
    - `CycleIT` com U6 e U7 reais e relógio controlado:
      - fatia única;
      - SKIPPED por pausa (L7) e por fatia vencida;
      - reinício (L10);
      - gatilhos de pausa aplicados;
      - venda parcial (L14);
      - uma ordem por fatia (NFR4.7);
      - REAL desligado (zero chamadas de assinatura);
    - `CycleFailureIT` com o gravador depois do rollback;
    - `LockOrderIT` com ciclo e resume em paralelo (L12).
  - Verde: `SlotDispatcher`, `CycleRunner`, `CycleFailureRecorder`, `CycleAuthContext`.
  - US4.5, US4.6, US4.7, US4.8; BR3.1 a BR3.7; NFR4.4, NFR4.7, NFR10.2, NFR10.3.
- [x] **Passo 11 — Meia-noite, conferência do encerramento e expurgo.**
  - Vermelho: `MidnightIT` (L3 a L5, UTC−3), `ClosingCheckIT` (L13) e `DecisionPurgeIT`.
  - Verde: `MidnightJob`, `ClosingCheckJob`, `DecisionPurgeJob`, com ShedLock.
  - US4.7, US4.12; BR2.6, BR2.8, BR9.1; NFR10.5, NFR10.8.
- [x] **Passo 12 — Administração e marcas.**
  - Vermelho: `BotAdministrationIT` com pausa e liberação de sistema repetíveis e lotes; contagens; resumo; marcas por `changeSeq`.
  - Verde: implementações de `BotAdministration` e `BotChangeMarks`.
  - US7.2; BR7.1 a BR7.3, BR8.1.

### API (vermelho → verde)

- [x] **Passo 13 — Rotas C4.**
  - Vermelho: `BotRoutesIT` com:
    - a própria pessoa e outra pessoa, e dois tenants;
    - `403` para `mode` diferente de PAPER;
    - validação e códigos 409 e 422.
  - Verde: controladores em `bots.web`.
  - US4.1 a US4.5, US4.8 a US4.10; BR6.1, BR2.9; NFR5.1, NFR6.2.
- [x] **Passo 14 — Canal C7.**
  - Vermelho: `DashboardStreamIT` com:
    - eventos `bot.state`, `trade.recorded` e `pnl.updated`;
    - `heartbeat` e `auth.expired`;
    - `Last-Event-ID`;
    - nenhum evento de outra pessoa;
    - latência de até 4 s.
  - Verde: `DashboardStreamController`, `DashboardMarkPoller`.
  - US5.4 (para a U9); BR8.2; NFR3.3.
- [x] **Passo 15 — OpenAPI e tipos.**
  - OpenAPI do convidado com C4, C7 e os acréscimos; tipos do frontend regenerados.
  - Depois, os testes de contrato.
  - Fundação.

### Saúde, observabilidade e arquitetura (depois de cada camada)

- [x] **Passo 16 — Saúde, métricas, logs e trilha.**
  - `BotCyclesHealthIndicator` informativo e `bots.cycle.errors`.
  - `cycleId` como correlação.
  - Eventos `BOT_*` de NFR9.4.
  - Depois, os testes: um evento por ação e nenhum na repetição; `LogScanIT` e `MetricsLabelsIT` com um ciclo.
  - NFR9.1 a NFR9.5, NFR7.1.
- [x] **Passo 17 — Arquitetura.**
  - `BotsArchitectureTest` confere:
    - dependências só por APIs públicas;
    - sem rede, Solana, assinatura nem IA;
    - sem `double`/`float` e sem `BigDecimal.ZERO` no lugar de `Unknown`;
    - beans de carga só com `loadtest`.
  - NFR4.5, NFR6.1, NFR6.4.

### Frontend (depois de cada camada)

- [x] **Passo 18 — Telas.**
  - Assistente S4, lista e tela do bot S5, conforme o frontend-components.md, com `data-testid` e lint de `DecimalString`.
  - Depois, os testes (Jest): estados, validação, diálogos com foco e Esc, motivos em texto, horário de reinício no fuso da pessoa, 404 da C21 como "nenhuma recusa", `DUPLICATE` como "decisão já avaliada", aviso de dados desatualizados e de posições em bot pausado.
  - US4.1 a US4.4, US4.8 a US4.10, US4.12; BR6.1 a BR6.4; NFR11.1, NFR14.1.
- [x] **Passo 19 — Smoke Playwright `e2e/u5-bots-decision`.**
  - Com a conta de smoke: assistente (etapas 1 a 4), start, pause, resume e stop, conferindo estados, motivos e o rótulo PAPER, e a tela visível em menos de 2 s (Q1, opção A).
  - US4.1, US4.3, US4.8, US4.12; NFR3.2.

### Ambiente, carga e documentação

- [x] **Passo 20 — Cenário simulado e carga.**
  - Fonte simulada da U4 com o cenário de sinais frequentes, só no perfil `test`.
  - `LoadtestBotSeed` e o cronômetro do ciclo em `bots.loadtest`.
  - Ramos `u5` em `scripts/load-test.sh` e `scripts/load/load-test.mjs`, com o actuator do worker em 8082, os tempos nos logs e `docker stats`.
  - `load-test.yml` aceita `u5`.
  - Depois, os testes: perfil, `WorkflowsTest`, `node --check` e `bash -n`.
  - NFR2.1 a NFR2.6, NFR3.1, NFR3.2.
- [x] **Passo 21 — Execução das suítes e cobertura.**
  - Rodar o comando da U5, o `verify` inteiro do backend, o Jest com cobertura, o `bash scripts/verify.sh` e, se houver memória, o cenário `u5`.
  - Corrigir sem reduzir o piso e anotar no resumo os resultados, a cobertura e as saídas vermelhas.
  - NFR12.1, NFR13.1.
- [x] **Passo 22 — Documentação e ação pendente.**
  - `docs/api/u5-bots-decision.md`: estados e motivos, ciclo, estratégias, travas, processo worker, rollback e pendências.
  - Preparar o texto da mudança do `scripts/verify.sh`: acrescentar `e2e/u5-bots-decision` à linha do Playwright.
  - O ponto de controle da U5 depende de o `verify.sh` passar com esse smoke.
  - Fundação.

## Rastreio das histórias

| História | Passos |
|---|---|
| US4.1 | 4, 6, 13, 18, 19 |
| US4.2 | 6, 9, 13, 18 |
| US4.3 | 9, 13, 18, 19 |
| US4.4 | 4, 5, 8, 13, 18 |
| US4.5 | 7, 10, 13 |
| US4.7 | 5, 9, 10, 11 |
| US4.8 | 5, 9, 10, 13, 18, 19 |
| US4.9 | 6, 9, 13, 18 |
| US4.10 | 13, 18 |
| US4.12 | 9, 11, 18, 19 |
| US7.2 | 5, 9, 12 |
