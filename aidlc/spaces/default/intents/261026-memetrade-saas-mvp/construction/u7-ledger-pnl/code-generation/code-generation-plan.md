# Plano de geração de código — U7 Ledger e P&L

## Summary

- Builds: execução PAPER (fund, execute, closePositions) com ledger imutável, posições a custo médio e idempotência; P&L com resumo diário; visão de risco para a U6; maior patrimônio; reconciliação; telas de P&L, ledger, operações e reconciliação
- Touches: `apps/api/` (módulos novos `ledger` e `pnl`, acréscimos em `marketdata.api` e `identity.api`, migração `0008`, OpenAPI do convidado, configuração, testes), `apps/web/src/features/pnl/`, `apps/web/src/app/pnl/`, `apps/web/e2e/u7-ledger-pnl/`, `docker-compose.yml`, `.github/workflows/load-test.yml`, `scripts/load-test.sh`, `scripts/load/`, `docs/`
- Tests: cerca de 220 testes de backend escritos antes do código (catálogo C1 a C15, propriedade, integração com Testcontainers, concorrência, privilégios do banco, ArchUnit, contrato), cerca de 50 de frontend (Jest), um smoke Playwright dos estados vazios e os cenários de carga `u7` e `u7-jobs`, com o piso de 80% de linhas mantido

## Entradas

Unit `u7-ledger-pnl` de [unit-of-work.md](../../../inception/units-generation/unit-of-work.md), histórias US4.11, US4.6 (idempotência), US4.12 (fechamento), US5.1 e US5.2 de [stories.md](../../../inception/user-stories/stories.md).

| Documento | O que dá ao código |
|---|---|
| [functional-spec.md](../functional-design/functional-spec.md), [rules.md](../functional-design/rules.md), [entities.md](../functional-design/entities.md) | Fluxos F1 a F10, regras BR1.1 a BR10.2, entidades, catálogo C1 a C15, acréscimos de contrato |
| [nfr-requirements/](../nfr-requirements/) e [nfr-design/](../nfr-design/) | Metas NFR2.1 a NFR14.1 da U7, matriz de privilégios, ordem e tempos de trava, resumo diário, fronteira `pnl` → `ledger.api` |
| [infrastructure-specification.md](../infrastructure-design/infrastructure-specification.md), [cicd-pipeline.md](../infrastructure-design/cicd-pipeline.md), [monitoring-design.md](../infrastructure-design/monitoring-design.md) | Migração `0008`, pool do agendador, cenários de carga, smoke, rollback |
| [contract-summary.md](../../../inception/contract-design/contract-summary.md) | C5, C9, C11, C13, C15 e regras comuns |

## Decisões de implementação

- **Pacotes:** `ledger` (`api`, `application`, `domain`, `infra`, `web`) dono de `paper_account`, `paper_order`, `fill`, `ledger_entry`, `position`, `pnl_daily`, `simulation_policy`; `pnl` (`api`, `application`, `infra`, `web`) dono de `equity_peak` e `reconciliation_check`. O `pnl` só usa `ledger.api` (`LedgerReadModel` e o evento `FillRecorded`).
- **Aritmética:** `BigDecimal`, `HALF_EVEN`; 6 casas para USDC, 12 para preço unitário, 4 para pontos percentuais; quantidades `DOWN` nas casas do mint. Nenhum `double` ou `float`.
- **Achados abertos das revisões resolvidos aqui:**
  - `closePositions` com a conta já em `CLOSING` ou `CLOSED` não cria ordem para posição `CLOSE_PENDING` e devolve o `CloseResult` atual (revisão 2 do Functional Design);
  - a ordem `CLOSE` gera `Fill` com spread, impacto e consumo `0.0000`, `quotedPrice` igual à referência e `filledAt` igual ao instante do fechamento (ou da conclusão pela tarefa de fechamento pendente);
  - no beta, `LedgerCompensation` só compensa lançamentos `FEE`; outros tipos são recusados pelo mecanismo, e a reconciliação recalcula a posição repetindo os preenchimentos em ordem de `filledAt`;
  - o resultado da execução é calculado antes e a ordem é inserida uma única vez com o status final (a trava da conta serializa a mesma chave); `TENANT_MISMATCH` não grava ordem, só o evento `REQUEST_FORBIDDEN`;
  - o pool do agendador vai a **11** (3 da U1 e U2, 4 da U4, 4 da U7: marcação, fechamento pendente, reconciliação e limpeza do limitador de "verificar agora"); `RuntimeSettingsGuard`, `application.yml`, `docker-compose.yml` e `ComposeFilesTest` acompanham;
  - `LoadtestCycleDriver` e o semeador de carga só existem com o perfil `loadtest` (`@Profile("loadtest")`), ficam em `apps/api/src/main/java/com/memetrade/ledger/loadtest/`, e um teste confirma que sem `loadtest` os beans não existem;
  - o smoke Playwright da U7 só vira portão depois que o responsável do projeto acrescentar `e2e/u7-ledger-pnl` ao `scripts/verify.sh`; o ponto de controle da U7 roda o comando de verificação com ele;
  - o rollback usa o Compose da versão anterior, ou o valor 11, que a imagem anterior aceita (guarda de 7 a 32);
  - o NFR3.4 (tempo da tela de P&L com operações) fica registrado como pendência de entrada da U5 no resumo do código.
- **Contratos:** antes do código da U7, os acréscimos ao C5, C11, C13, C15 e às Escalas entram no `contract-summary.md` e no OpenAPI (Passo 3).
- **Fora do agente:** a linha do Playwright em `scripts/verify.sh` é mudança do responsável do projeto; o texto é preparado no Passo 26.

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

## Como a ordem dos testes se aplica à U7

- **Núcleo financeiro (testes antes do código):** a U7 é o ledger, o P&L e a deduplicação. Em todo passo de banco, repositório, lógica de negócio e API do `ledger` e do `pnl`, os testes são escritos primeiro a partir das regras e do catálogo, rodam e falham (vermelho, com a saída registrada no resumo), e só então o código é escrito até ficarem verdes; depois, refatoração com os testes verdes. O teste valida o requisito, não a implementação.
- **Demais camadas (depois de cada camada):** configuração, carga, telas e smoke seguem implementar e depois testar, como manda o `team.md`.
- **Executores prontos antes do primeiro teste:** o Passo 2 deixa o comando da U7 (marcador `u7`) rodando antes do primeiro teste vermelho (Passo 4).
- **Volume:** 10 a 15 testes por componente; unitários, propriedade, integração, concorrência, ponta a ponta, desempenho (cenários `u7` e `u7-jobs`) e segurança (privilégios, isolamento, só PAPER, sem rede, sem ponto flutuante).
- **Suítes das Units anteriores:** continuam verdes depois da `0008`, do pool do agendador em 11 e dos acréscimos na U1 e na U4.

## Passos

Cada passo indica as histórias (US), regras (BR) e requisitos detalhados (NFR, da U7) que implementa. "Fundação" marca o que vem das responsabilidades da Unit sem história própria. "Vermelho → verde" marca os passos de testes antes do código.

### Fundação, executores e contratos

- [x] **Passo 1 — Configuração.**
  - `application.yml`: propriedades fixas de `memetrade.ledger` (intervalo de reserva 60 s, reconciliação 5 min, limite de "verificar agora" 6, tempos de trava 2 s, 5 s e 1 s); `spring.task.scheduling.pool.size: ${SCHEDULER_POOL_SIZE:11}`; `RuntimeSettingsGuard` recusa menos de 11; `docker-compose.yml` com o padrão 11.
  - Depois, os testes: guarda com 10 recusa e 11 aceita; `ComposeFilesTest` com o padrão 11.
  - Fundação; NFR3.5, NFR10.8.
- [x] **Passo 2 — Base de testes e comando da Unit.**
  - Marcador JUnit `u7`; `AbstractU7IT` sobre a base de integração da U2, com conexão adicional pelo papel da aplicação para os testes de privilégio; `MutableClock`; dado de mercado simulado controlável por teste (preço, liquidez, `decimals`, `unknown`, desatualizado); construtor de contas e ordens de teste.
  - Um teste de subida do contexto com o marcador `u7` roda verde com o comando de [unit-test-instructions.md](unit-test-instructions.md); as suítes anteriores rodam verdes.
  - Fundação; NFR12.1.
- [x] **Passo 3 — Contratos e acréscimos em código existente.**
  - `contract-summary.md`: C5, C11, C13, C15, C9 e Escalas como no functional-spec.md e no logical-components.md (inclusive `SUMMARY_MISMATCH`).
  - U4: `decimals` em `MarketDataQuery` (`MarketValue<Integer>`); U1: `PAPER_FUND` e `PAPER_CLOSE_POSITIONS` em `IdempotentOperation`; U1: entradas da U7 em `tenancy/route-registry.yml`.
  - Interfaces `PaperExecution`, `LedgerReadModel`, `FillRecorded`, `PortfolioRiskView` e os tipos de resultado em `ledger.api` e `pnl.api`.
  - Depois, os testes: contrato da U4 com `decimals`; enum de idempotência; registro de rotas.
  - Fundação; BR1.10; NFR6.8.

### Banco de dados (vermelho → verde)

- [x] **Passo 4 — Migração `0008-ledger-pnl` e privilégios.**
  - Vermelho: `LedgerPrivilegesIT` com o papel da aplicação, uma linha da matriz do security-design.md por teste (permitido e recusado), `CHECK mode = 'PAPER'`, transições de `paper_order` e `paper_account`, pico que não diminui, `pnl_daily` com `fill_count` que não diminui, índice único de `FUNDING` por bot, unicidade de chave por (tenant, modo), política v1 semeada; `MigrationsIT` roda as consultas das Units anteriores sobre o esquema novo.
  - Verde: a migração com tabelas, escalas `numeric`, índices, gatilhos, privilégios e semeadura.
  - US5.2, US4.6; BR3.1, BR3.4, BR4.1, BR9.2, BR1.1; NFR6.2, NFR5.3, NFR4.2.

### Lógica de negócio (vermelho → verde)

- [x] **Passo 5 — Cálculo do preenchimento.**
  - Vermelho: `LedgerScenarioCatalogTest` (parte de preenchimento) com C1 a C15, cada número por igualdade exata; casos de BR1.2 (sem preço, desatualizado 5:01, liquidez zero, `decimals` `unknown`), BR1.4 (teto de 10%, truncado a zero), BR1.5 (venda maior, posição zero), BR2.5 (saldo insuficiente, C15).
  - Verde: `FillCalculator` puro.
  - US4.11, US4.6; BR1.1 a BR1.8, BR2.5, BR6.7; NFR4.1, NFR8.1.
- [x] **Passo 6 — Contabilidade da posição e lançamentos.**
  - Vermelho: decomposição da BR2.2 (soma da compra e da venda), custo médio com custo total, venda parcial e total, reinício do custo (C3, C7, C11), fechamento ao preço de referência com `Fill` de spread zero (C12), compensação só de `FEE` (C13) e recusa de outros tipos.
  - Verde: `PositionAccounting`, `LedgerEntries`, `LedgerCompensation` (visibilidade de pacote).
  - US4.11, US4.12, US5.2; BR2.2 a BR2.4, BR4.2, BR5.1; NFR4.1.
- [x] **Passo 7 — P&L, preço de saída e visão de risco (domínio).**
  - Vermelho: preço de saída e não realizado (C9), `unknown` e parcial (C8), "sem liquidez", soma exata das linhas, perda do dia (C2), exposição pelo custo, pico (C9), período vazio.
  - Verde: `PnlCalculator`, `RiskSnapshotCalculator`, `EquityCalculator` puros.
  - US5.1; BR6.1 a BR6.6, BR7.1 a BR7.3; NFR4.1.
- [x] **Passo 8 — Propriedade com semente fixa.**
  - Vermelho e verde: `LedgerPropertyTest` (500 sequências de fund, compras, vendas, fechamentos e compensações de `FEE`): saldo = soma dos lançamentos; posição = soma dos preenchimentos; nunca saldo negativo; soma das linhas = total.
  - US5.1, US5.2; BR2.3, BR2.5, BR6.5, BR8.2; NFR4.4.

### Repositório e execução (vermelho → verde)

- [x] **Passo 9 — Repositório do ledger e `LedgerReadModel`.**
  - Vermelho: inserção idempotente com `ON CONFLICT DO NOTHING` sem abortar a transação; saldo por índice; página do ledger por cursor; preenchimentos e taxas de um intervalo com as compensações pela junção `compensates_entry_id`; resumo diário; `lockAccountForMarking`; filtros por pessoa e `PAPER`.
  - Verde: `JdbcLedgerRepository` e `LedgerReadModelImpl`.
  - US5.1, US5.2; BR3.1, BR9.1, BR9.2; NFR2.2, NFR2.4.
- [x] **Passo 10 — `fund`, `execute` e `closePositions`.**
  - Vermelho: `PaperExecutionIT` com F1 a F3: exige transação; idempotência antes do estado da conta; `Duplicate` e `Conflict`; cada `NotFilled` gravado com evento e sem lançamento; `TENANT_MISMATCH` sem ordem e com `REQUEST_FORBIDDEN`; ordem inserida uma vez com o status final; `closePositions` com conta já em `CLOSING` ou `CLOSED` não duplica ordem `CLOSE`; `pnl_daily` atualizado na mesma transação e a compensação só em `fees`; evento `FillRecorded` síncrono; tempos de trava por `SET LOCAL`.
  - Verde: `PaperExecutionImpl`, `FundingService`, `ClosingService` com o `IdempotencyGuard`.
  - US4.11, US4.6, US4.12; BR2.1, BR2.6, BR2.7, BR2.8, BR3.1 a BR3.4, BR5.1, BR5.2, BR9.1; NFR4.2, NFR5.2, NFR6.8, NFR10.2, NFR10.8.
- [x] **Passo 11 — Concorrência, atomicidade e travas.**
  - Vermelho e verde: `PaperExecutionIdempotencyIT` (mesma chave em 2 threads, 50 repetições), `PaperExecutionAtomicityIT` (falha injetada em cada gravação), `LedgerLockOrderIT` (execução, fechamento, fechamento pendente e marcação, com `pnl_daily`, 50 repetições), `LedgerLockTimeoutIT` (trava segurada por 3 s).
  - US4.6, US4.11; BR2.1, BR2.8, BR3.2; NFR4.3, NFR6.8, NFR10.5, NFR10.8.
- [x] **Passo 12 — Fechamento pendente.**
  - Vermelho: `PendingCloseJobIT`: conclusão única com duas execuções em paralelo e depois de reinício; `POSITION_CLOSE_COMPLETED` com ator `system`; conta `CLOSED` só sem posições abertas; conta presa pulada.
  - Verde: `PendingCloseJob` com ShedLock, após coleta, a cada 60 s e na partida.
  - US4.12; BR5.3; NFR10.3, NFR10.4.

### P&L, risco e reconciliação (vermelho → verde)

- [x] **Passo 13 — Maior patrimônio e marcação.**
  - Vermelho: `EquityPeakConcurrencyIT` (marcação e preenchimento concorrentes); `unknown` mantém o pico; atualização condicional; ouvinte de `FillRecorded` na mesma transação.
  - Verde: `EquityPeakUpdater` e `EquityMarkingJob`.
  - US5.1; BR7.3; NFR4.5, NFR10.7.
- [x] **Passo 14 — Visão de risco (C13).**
  - Vermelho: `PortfolioRiskViewIT`: exposição pelo custo, perda do dia direto dos preenchimentos (nunca do resumo), patrimônio `Optional`, saldo, última operação; conta de outra pessoa recusada.
  - Verde: `PortfolioRiskViewImpl`.
  - US4.6; BR7.1, BR7.2, BR7.4; NFR5.2.
- [x] **Passo 15 — P&L do período.**
  - Vermelho: `PnlQueryIT`: resumo nos dias inteiros e consulta direta nas pontas; `PnlPeriodEdgesIT` com período que corta o dia de uma taxa compensada; 366 dias; vazio; `unknown` e parcial; "sem liquidez" com fonte e horário.
  - Verde: `PnlQueryService`.
  - US5.1; BR6.1 a BR6.6; NFR3.2, NFR4.4.
- [x] **Passo 16 — Reconciliação.**
  - Vermelho: `ReconciliationIT` com fixtures de cada divergência (`MISSING_ENTRY`, `AMOUNT_MISMATCH`, `ORPHAN_ENTRY`, `POSITION_MISMATCH`, `SUMMARY_MISMATCH`); compensação válida não diverge; últimos 100 por pessoa; log `WARN` com a contagem; `REPEATABLE READ`.
  - Verde: `ReconciliationService` e `ReconciliationJob`.
  - US5.2; BR8.1, BR8.2, BR8.4; NFR10.6, NFR9.7.

### API (vermelho → verde)

- [x] **Passo 17 — Rotas C5.**
  - Vermelho: `LedgerRoutesIT`: cada rota com a própria pessoa; bot de outra pessoa `404`; bot não ativado vazio; dois tenants; `mode` diferente de `PAPER` `403`; validação (`400`); "verificar agora" com `Idempotency-Key` e limite 6 por minuto (`429`, `Retry-After`) e limpeza do limitador; nenhum verbo de escrita.
  - Verde: controladores em `ledger.web` e `pnl.web`, limitador próprio e `ReconciliationRunLimiterEvictionJob`.
  - US5.1, US5.2, US4.11; BR4.3, BR7.5, BR8.3, BR9.1, BR10.2; NFR5.1, NFR6.3, NFR6.5, NFR6.6.
- [x] **Passo 18 — OpenAPI e tipos.**
  - OpenAPI do convidado com as rotas C5 e os acréscimos; tipos do frontend regenerados; teste de contrato do convidado.
  - Depois, os testes: gerado igual ao versionado; teste que proíbe verbos de escrita sob `/ledger`, `/pnl`, `/trades`.
  - Fundação; NFR6.3.

### Arquitetura, saúde e observabilidade (depois de cada camada)

- [x] **Passo 19 — Saúde, trilha e varreduras.**
  - `LedgerJobsHealth` (`DEGRADED`, nunca `DOWN`, com a contagem de divergências); eventos da C9.
  - Depois, os testes: saúde em cada condição; um evento por ação e nenhum em `Duplicate`; `LogScanIT` e `MetricsLabelsIT` ampliados com um fluxo completo.
  - NFR9.1, NFR9.3, NFR9.4, NFR9.5, NFR7.1.
- [x] **Passo 20 — Arquitetura.**
  - `LedgerArchitectureTest`: `pnl` só depende de `ledger.api`; `ledger` não depende de `pnl`; domínio sem infraestrutura nem web; sem rede, assinatura ou SDK Solana; sem `double` ou `float`; `LedgerCompensation` só referenciado em `ledger.domain`; beans de carga só com `@Profile("loadtest")` e um teste confirma que sem `loadtest` eles não existem.
  - NFR6.1, NFR6.4, NFR6.7, BR1.9.

### Frontend (depois de cada camada)

- [x] **Passo 21 — Telas de P&L, ledger, operações e reconciliação.**
  - `apps/web/src/features/pnl` e `apps/web/src/app/pnl`: título com "MODO PAPER (simulado)"; filtro de período no fuso da pessoa convertido para UTC; realizado, não realizado e taxas separados com sinal e texto; `unknown` com motivo e "parcial"; "sem liquidez" com fonte e horário; ledger agrupado por operação com "ver hipóteses"; operações do bot como componente para a tela da U5; reconciliação com aviso de divergência ou "Reconciliado" e "verificar agora"; estados de carregando, vazio e erro distintos; decimais como texto; `data-testid`; regra de lint que proíbe converter `DecimalString` para `number`.
  - Depois, os testes (Jest): cada estado; conversão de fuso; nenhum número parcial como completo; aviso de divergência; acessibilidade básica; catálogo de termos proibidos.
  - US5.1, US5.2, US4.11; BR6.3, BR6.4, BR6.6, BR8.4, BR10.1, BR10.2; NFR11.1, NFR14.1.
- [x] **Passo 22 — Smoke Playwright `e2e/u7-ledger-pnl`.**
  - Entra com a conta de smoke da U1 e confere os estados vazios: P&L com "Nenhuma operação neste período", ledger vazio, "Reconciliado" e o rótulo "MODO PAPER (simulado)" (Q1 da Infrastructure Design, opção B).
  - US5.1, US5.2; BR6.6, BR10.1.

### Ambiente, carga e documentação

- [x] **Passo 23 — Cenários de carga.**
  - `LoadtestCycleDriver` e o semeador de carga em `ledger.loadtest`, só com `@Profile("loadtest")`; `scripts/load-test.sh` e `scripts/load/load-test.mjs` com `LOAD_SCENARIO=u7` (10 minutos) e `u7-jobs` (30 minutos), limites e `EXPLAIN` do cicd-pipeline.md; `.github/workflows/load-test.yml` aceita os dois; `WorkflowsTest` atualizado; relatório em `docs/perf/u7-ledger-pnl-<data>.md`.
  - Depois, os testes: os beans não existem sem `loadtest`; `node --check` e `bash -n`.
  - NFR3.1, NFR3.2, NFR3.3, NFR3.5.
- [x] **Passo 24 — Execução das suítes e cobertura.**
  - Comando da U7, suíte inteira do backend (`verify` com JaCoCo 80%) e do frontend (Jest com cobertura); corrigir sem reduzir o piso; anotar a cobertura e as saídas vermelhas dos passos de testes antes do código no resumo.
  - NFR12.1.
- [x] **Passo 25 — Documentação.**
  - `docs/api/u7-ledger-e-pnl.md`: modelo do ledger, matriz de privilégios, ordem das travas, catálogo de cenários, procedimentos (fechamento pendente, divergência na saúde), pendência do NFR3.4 para a U5 e o efeito do `SET LOCAL` até o fim do ciclo, para a U5 registrar no seu design.
  - Fundação.
- [x] **Passo 26 — Mudança preparada para o `scripts/verify.sh` e condição do ponto de controle.**
  - Texto da mudança para o responsável do projeto: acrescentar `e2e/u7-ledger-pnl` à linha do Playwright.
  - O ponto de controle da U7 só é aprovado depois de o comando de verificação (`bash scripts/verify.sh`) passar com o smoke da U7 incluído.
  - NFR3.4.

## Rastreio das histórias

| História | Passos |
|---|---|
| US4.11 | 4, 5, 6, 9, 10, 11, 17, 21 |
| US4.6 (idempotência) | 3, 4, 5, 10, 11, 14 |
| US4.12 (fechamento) | 6, 10, 12 |
| US5.1 | 7, 8, 9, 13, 15, 17, 21, 22 |
| US5.2 | 4, 6, 8, 9, 16, 17, 21, 22 |
