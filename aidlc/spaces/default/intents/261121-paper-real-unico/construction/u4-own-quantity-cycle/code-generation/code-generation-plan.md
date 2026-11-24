# Plano de geração de código — U4 `u4-own-quantity-cycle`

## Summary

- Builds: com a flag `memetrade.real.own-quantity-sell` (desligada por padrão nos dois processos), o ciclo do bot REAL vende só a quantidade disponível do próprio bot. Entram: a leitura `RealBotHoldings` (C15) num instantâneo só de leitura fora da transação do ciclo, com prazo de 250 ms; `openSells` (C16 estendido com as decisões SELL `PENDING`); as conferências prévias e a marca do bot (M0/M1, `STALE_SNAPSHOT`); o corte `min(pedido, disponível)` antes da fila; as notas `context.ownQuantity`; os dois resultados novos do ciclo; três contadores, o gauge dos bots presos e dois pares regra/alarme; o campo `realOwnQuantitySell` (C7) e o aviso da lista `/bots`; as duas variáveis Terraform da flag (F4) e as seções 10 a 13 do runbook.
- Touches: `apps/api` (`bots.worker`, `bots.domain`, `bots.infra`, `bots.api`, `bots.web`, `pnl.api`, `pnl.application`, `realexecution.api`, `realexecution.infra`, `decision.infra`, `application.yml`, migrações `0026` e `0027`, `db.changelog-master.yaml`, `openapi-guest.yaml`), `apps/web` (`features/bots`, `lib/api/schema.d.ts`, `e2e/zz-paper-real-unico`), `infra/observability` (regras e teste do `promtool`), `infra/terraform` (`main.tf`, `environments/*.tfvars.example`, `modules/services/outputs.tf`, `modules/observability/app-metrics.tf` e `real-ops.tf`, `tests/observability_metrics.tftest.hcl`), `docs/runbooks/real-operations.md` e os textos de design e de contrato (alinhamentos A1 a A19); nenhum arquivo de `.github/workflows/`, nenhum `scripts/verify.sh`, nenhum `tfvars` real.
- Tests: cerca de 190 testes novos ou alterados: testes escritos antes do código (vermelho → verde) no corte e na venda da quantidade própria (T1 a T14, T18, T19, `OwnSellCapTest`) e na leitura `RealBotHoldings` (C15/C16); unidade, integração com Testcontainers (PostgreSQL 17 e `prom/prometheus` pelo digest do `ci.yml`), ArchUnit, migração com `EXPLAIN`, contrato OpenAPI, Jest com Testing Library, 1 caso de Playwright e 3 `run` novos do `terraform test` (manuais), com piso de 80% por parte.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "feature",
  "test_strategy": "standard",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo. No\n  intent `261109-termos-lgpd` (Q5, opção A), a ordem de testes antes do código\n  foi estendida à lógica de dados pessoais. No intent `261113-trading-real-bot`,\n  o NFR8 estendeu a ordem à lógica de carteira e saque, e o plano de Bolts pediu\n  os testes de ataque da política do TxSigner (B1) e os de estados e\n  idempotência da execução REAL (B4) antes do código. No intent\n  `261121-paper-real-unico`, o P&L REAL por bot (custo médio por bot e rateio\n  das vendas sem bot) já está dentro de \"P&L\" no núcleo financeiro, e os\n  cenários são escritos antes do código (CT-15 do registro de restrições).\n- **Volume de testes**: o volume do projeto continua `Comprehensive`; cada intent\n  segue a Test Strategy que o próprio estado registra (o intent\n  `261121-paper-real-unico` registra `Standard`) [Q8]. O piso de cobertura\n  abaixo é somado à estratégia e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend (JaCoCo, unitários e integração mesclados), no TxSigner (JaCoCo) e em\n  cada app web (Jest, `coverageThreshold.global.lines = 80`). As exclusões ficam\n  listadas na configuração da ferramenta, nunca decididas na hora. A ferramenta\n  falha o build abaixo do piso. O piso não pode ser reduzido para fazer uma etapa\n  passar.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L),\n    inclusive na visão única: cada bloco e cada total por modo mostram só o\n    próprio modo, e os modos nunca são somados [Q8];\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: com a configuração padrão (API, worker e\n    TxSigner), nenhuma carteira do bot é criada, nenhum caminho assina ou envia\n    transação e nenhuma assinatura é pedida ao KMS;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading;\n  - a anonimização não deixa o endereço da carteira em nenhuma tabela nem log\n    consultável;\n  - a versão nova dos termos exige novo aceite antes de qualquer outra rota,\n    **exceto** a leitura da carteira do bot (`GET /api/v1/real/bot-wallet`) e as\n    rotas de saque (`/api/v1/real/bot-wallet/withdrawals` e\n    `/api/v1/real/bot-wallet/withdrawals/{withdrawalId}`), que continuam\n    disponíveis sem o novo aceite para o usuário sempre poder tirar o próprio\n    dinheiro; o teste cobre as duas metades: as rotas da exceção respondem sem o\n    novo aceite e todas as demais respondem `403 TERMS_REACCEPTANCE_REQUIRED`;\n  - saque só para a carteira principal registrada: qualquer outro destino é\n    recusado pela API e pelo TxSigner, e o saque continua disponível com o kill\n    switch acionado, com o REAL desligado e sem a liberação vigente;\n  - a política de conteúdo do TxSigner (ADR-004) recusa toda transação fora da\n    lista: programa fora da lista, assinante ou pagador diferente da carteira do\n    bot, destino fora da carteira do bot ou da carteira principal, saída mínima\n    abaixo da cotação menos a derrapagem, troca de autoridade, fechamento de\n    conta para terceiros e valor acima do teto autorizado; cada recusa registra o\n    motivo;\n  - uma única transação por ordem REAL: repetir a ordem com a mesma\n    `Idempotency-Key`, reprocessar a decisão da fila ou repetir depois de uma\n    ordem ambígua nunca gera segunda transação.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (Testcontainers) e não dependem de estado\n  compartilhado nem da ordem de execução. Fixtures nunca usam carteira, mint ou\n  dados pessoais reais. No REAL, a rede é o validador Solana local (com contas\n  clonadas para os swaps), o KMS é simulado no ambiente local e as respostas da\n  Jupiter e do RPC usadas em testes de unidade são gravadas; nenhum teste da CI\n  usa devnet, mainnet, KMS real ou chave real.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: em toda mudança e no push para `main` rodam os testes de\n  unidade e de integração de todas as partes (backend, TxSigner, `apps/web` e\n  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O\n  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; no push\n  para `main` o job `e2e` roda a mesma suíte uma segunda vez, depois dos demais\n  jobs verdes.\n- **Testes da IaC**: os testes do Terraform (`infra/terraform/tests/`), o\n  `terraform validate` e o Checkov existem e rodam à mão; levá-los para a CI é\n  pendência do responsável [Q5].\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas**: JUnit 5, Testcontainers, ArchUnit e JaCoCo no backend e no\n  TxSigner; Jest com Testing Library nos dois apps; Playwright para ponta a ponta;\n  `node:test` nos cenários de carga.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas; o workflow `load-test` é manual."
    },
    {
      "layer": "project",
      "text": "- Um spec de ponta a ponta que muda a conta do smoke (por exemplo, com uma operação PAPER) roda por último no verify.sh, com o prefixo `zz-` na pasta, porque o Playwright roda os arquivos em ordem alfabética e os specs anteriores esperam a conta sem essas operações. (learned 2026-11-22)"
    }
  ],
  "obligations": {
    "strategy": "standard",
    "strategy_volume": [
      "Five to eight tests per component.",
      "Unit tests plus integration tests for key boundaries.",
      "Add E2E, performance, or security tests when requirements demand them."
    ],
    "scope_floor": [
      "Meet an 80% line-coverage floor.",
      "Run the selected tests in CI before merge."
    ],
    "combination_rule": "Apply every selected-strategy obligation and every scope-floor obligation; neither replaces the other, and a targeted scope regression may add the narrowest necessary test type beyond the strategy default."
  },
  "plan_profile": {
    "methodology": "custom",
    "runner_step": "Verify the existing test runner/configuration and record the exact unit-scoped command.",
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
      "Verify the existing test runner/configuration and record the exact unit-scoped command.",
      "Custom ordering - No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
      "Implementation and tests - preserve that exact ordering; do not convert it to layer-local TDD.",
      "Environment/build configuration.",
      "Documentation and traceability."
    ]
  },
  "input_sha256": "sha256:b9e6fe7b430f78ef508409e26ddc7207e4999d1a778d43a867214dd05f9772e7",
  "contract_sha256": "sha256:c4f4514e24029964fae6ac16814415dc4795ac176ff2175003fe9c2756496a8b"
}
```

## Fontes

- `construction/u4-own-quantity-cycle/functional-design/`: `rules.md` (33 regras, BR1.1 a BR7.2, com as decisões D1 a D5 do responsável), `functional-spec.md` (F1 a F6, exemplos E1 a E11, concorrência C0 a C4, testes T1 a T20 e "Efeito nos contratos"), `entities.md` (ENT-401 a ENT-411) e `traceability.json` (AC6.1.1 a AC6.2.3; BR4.1 a BR4.4 `N/A` no reverso). Não há `frontend-components.md`: a U4 é `service`, e a mudança de tela é o F5.
- `construction/u4-own-quantity-cycle/nfr-requirements/`: NFR1.1 a NFR8.4, as decisões do fundador F1 (T20 por Testcontainers com o digest lido do `ci.yml`) e F2 (alarme por razão; meta p99,9 ≤ 250 ms), as linhas de referência de `tech-stack-decisions.md` e o `traceability.json`.
- `construction/u4-own-quantity-cycle/nfr-design/`: a decisão do fundador F3 (gauge `real_own_holdings_stuck_bots` e alarme do bot preso, NFR2.18), a sequência P0 a P3 e S0 a S6, `ORDER_PREFETCH_THRESHOLD = 32`, a consulta de `openSells` com literais, o índice `real_decision_request_bot_pending_sell_idx`, as três barreiras, as regras ArchUnit R1 a R6, o T20 com 10 casos e os alinhamentos A1 a A19 (`logical-components.md`).
- `construction/u4-own-quantity-cycle/infrastructure-design/`: a decisão do fundador F4 (duas variáveis `bool` por ambiente no `tfvars`, com a validação), `infrastructure-specification.md` (saída `api_worker_container_environment`, lista de permissão, saída `alarm_expressions`, dois alarmes, migrações), `monitoring-design.md` (regras, gêmeos, paridade `hasSize(16)`, seções 10 a 13 do runbook) e `cicd-pipeline.md` (testes do job `backend`, `run` novos do `terraform test`, ordem dos commits, virada e desligamento).
- `inception/contract-design/contract-summary.md`: C7 (`BotPage.realOwnQuantitySell`), C8 (`BotDirectory.botsOf`), C12, C13, C15 (`RealBotHoldings`) e C16 (`openSells`).
- `construction/u2-real-bot-pnl/code-generation/code-summary.md` (o que existe: `RealBotPnl` em REPEATABLE READ, `AttributedCostReader` com os caminhos `GATE`/`CYCLE`/`PNL`, `ReadDeadline`, `RealCostMethod`, `RealLedger.view` por época, `RealOpsAlarmParityTest` com `hasSize(14)`, runbook seções 8 e 9; Terraform, Checkov e `promtool` não instalados na máquina local) e `construction/u3-pnl-views/code-generation/code-summary.md` (o `BotList.tsx` com `BotModeFilter`, `?mode=`, `matchingCount`/`totalCount` e o aviso do simulado `bots-disclaimer`).
- `aidlc/spaces/default/memory/team.md` e `project.md`.
- Código lido: `bots/worker/CycleRunner.java` (uma transação `REQUIRES_NEW` em READ COMMITTED; `cycle` faz `tryStart`, `lockBot`, `realCycle.inputs`, `request`, `engine.decide`, `decisions.insert(... json.write(request, outcome) ...)`, `realCycle.order`, `cycles.finish` e `bots.renewMark`; o laço de `request` fecha os registros de abertura fora das posições), `bots/worker/SlotDispatcher.java` (`bots.activeBots()` → `cycles.run(BotRef, slot)`), `bots/worker/RealCycle.java` (`inputs` com `walletCost.costOf(..., CostPath.CYCLE, view)`; `order` vende `position.get().tokenBaseUnits()` da carteira), `bots/application/RealDecisionStep.java` (`Candidate.sell`, `submit`), `bots/infra/JdbcBotRepository.java` (`SELECT_BOT` com `change_seq` e `market`, `activeBots`, `renewMark`, `systemPaused`, `holdings`, `closeHolding`), `bots/infra/LockOrder.java` (`lockBot` com `for update` sobre `SELECT_BOT`), `bots/domain/Bot.java` (`changeSeq`, `real()`), `bots/domain/BotRef.java`, `bots/web/BotDtos.java` (`BotPageDto` com `matchingCount` e `totalCount`), `bots/web/BotController.java` (`list`), `realexecution/api/RealExecutionPort.java` (sem `openSells`; `hasOrderInProgress`, `settledOrder`, `streamSettledOrders`), `realexecution/infra/RealExecutionService.java` (implementa a porta), `realexecution/infra/JdbcRealExecutionStore.java`, `realexecution/infra/DecisionConsumer.java` e `OrderSettlement.java` (não mudam), `decision/infra/DecisionJson.java` (`write(request, outcome)` e `read`), `pnl/application/AttributedCostReader.java` (`readFromEpoch`, `guarded`, classe interna `Accumulation`), `pnl/application/RealBotPnl.java`, `shared/jdbc/ReadDeadline.java` (`after`, `check`, `applyStatementTimeout`, `guard`, `isTimeout`), migrações `0019-real-execution.sql` (`real_decision_request` sem índice por bot; `uq_real_order_bot_in_progress`), `0020-real-bot-cycle.sql` (`bot_cycle_outcome_ck` com 12 valores), `0023-real-entry-slot.sql` (padrão `CONCURRENTLY` com `runInTransaction:false`) e `db.changelog-master.yaml` (última `0025`), `application.yml` (`memetrade.real`, padrão `${...:false}`), `MemetradeApplication` (`@ConfigurationPropertiesScan`); `apps/web/src/features/bots/BotList.tsx` e `BotList.test.tsx`; `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java` (`record Rule` sem `cloudPeriods`, `hasSize(14)`), `infra/CloudWatchWindows.java`, `db/AnonymizationMigrationIT.java`, `db/Migration0023IndexRestartIT.java`, `riskcontrol/real/RealAttributionMeasurementIT.java`, `realexecution/AbstractRealExecutionIT.java`; `infra/terraform/main.tf` (`variable "founder_user_id"`, `app_environment`, `api_environment`, `worker_environment`, `required_version = ">= 1.9.0"`), `modules/services/outputs.tf`, `modules/observability/app-metrics.tf` (`app_metric_declarations`, `alarm_resource_names`, `metric_alarms`, saídas), `modules/observability/real-ops.tf`, `tests/observability_metrics.tftest.hcl` (`run "every_alarm_reads_a_published_metric"`), `infra/observability/real-ops-alerts.yml` (14 regras; não existe `infra/observability/tests/`), `docs/runbooks/real-operations.md` (seções 1 a 9) e, só lido, o passo `Alert rules (promtool)` do `.github/workflows/ci.yml` (digest `prom/prometheus@sha256:63805ebb…`).

## Ordem aplicada

O Testing Contract acima é `custom`:

- **Testes escritos antes do código** (vermelho → verde → refatorar), a partir das regras, porque o corte e a venda REAL são dinheiro (núcleo financeiro e "estados e idempotência das ordens REAL" do `team.md`; NFR7.1):
  - o corte e a venda da quantidade própria: os cenários de dinheiro T1 a T4 (E1 a E7, E10), quantidade zero, vendas em aberto (ordens em andamento, `AMBIGUOUS` e decisões SELL `PENDING`, inclusive adiadas), falha e prazo (T5), flag desligada igual a hoje (T7), assentamento em paralelo (T8), um bot nunca vende a parte de outro (T9), a marca do bot e a concorrência (`STALE_SNAPSHOT`, T10, e a renovação da marca), uma transação por ordem (T11), arquitetura (T13), PAPER e compras sem mudança (T3, T14), o risco residual documentado (T18, T19) e a saída do dinheiro sempre disponível (`OwnQuantityExitsIT`);
  - a leitura `RealBotHoldings` (C15/C16): a semântica de `openSells`, a paridade com o `RealBotPnl` (T12) e os dois caminhos de S4, o tenant (T6), o isolamento e o prazo.
- **Antes do código da própria camada, por NFR7.1 e pelo alinhamento A6:** o T20 (regra de alerta, Etapa 23) e os T15 a T17 (aviso na tela, Etapa 20) entram com o vermelho registrado antes do código da camada. Ver o conflito 1 em "Riscos e conflitos".
- **Teste depois de cada camada:** migrações (com o `EXPLAIN`, o reinício do índice e o `lock_timeout`), a medida, métricas e logs, o C7 e o contrato, o Terraform, o caso de ponta a ponta e o runbook.
- O runner é conferido antes do primeiro teste (Etapa 1).
- **Commits.** Nenhum commit é feito por esta geração sem o pedido do responsável (`team.md`). Um único commit é esperado depois de todas as Etapas, com os testes da Fase A no mesmo commit, antes do código que os faz passar (NFR7.1 aceita "no mesmo commit antes dele"); o vermelho de cada um fica registrado no `code-summary.md`.
- **Nunca editados por esta geração:** `.github/workflows/*` e `scripts/verify.sh` (um texto pronto, se for preciso, vai para o `code-summary.md`); o estado e a trilha do AI-DLC (`aidlc-state.md`, `audit/`); nenhum `tfvars` real (só os `*.tfvars.example`); o `team.md` e o `project.md` (pendências vão para o ritual de aprendizado).

## Etapas

### Etapa 1 — Conferir os runners e gravar os comandos da Unit

- [x] Conferir que `./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='RealCycleOrderCapTest' -Dsurefire.failIfNoSpecifiedTests=false` e `./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealCycleAttributionIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` rodam (o segundo confirma o Testcontainers com o PostgreSQL 17 e a base `AbstractRealExecutionIT`).
- [x] Conferir que `pnpm --dir apps/web exec jest src/features/bots/BotList.test.tsx` roda.
- [x] Conferir que o Docker responde (`docker info`), porque o T20 sobe a imagem `prom/prometheus` pelo Testcontainers; sem Docker local, o T20 fica para a CI e o fato vai para o `code-summary.md`.
- [x] Registrar que o `terraform` não está instalado nesta máquina (U2): os `run` do `terraform test` da Etapa 25 ficam escritos e são rodados à mão pelo responsável (`team.md`, Testes da IaC).
- [x] Os comandos exatos ficam em `unit-test-instructions.md`. Se um runner falhar, a Etapa 2 não começa.
- Cobre: NFR7.2, NFR7.3.

## Fase A — testes antes do código: quantidade própria, corte e leitura

### Etapa 2 — Testes antes do código: o corte e as entradas próprias (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/bots/domain/OwnSellCapTest.java` (unidade, sem Spring):
  - E3: pedido 150.000.000, disponível 100.000.000 → `Sell(100.000.000, capped = true)`; E4: pedido 40.000.000 → `Sell(40.000.000, capped = false)`;
  - E6 e E10: disponível 0 (tudo em aberto; em aberto maior que a própria) → `NoOwnQuantity`, nunca negativo;
  - mint ausente das posições próprias → disponível 0 → `NoOwnQuantity`;
  - M1 ≠ M0 → `Unavailable(STALE_SNAPSHOT)`, conferido **antes** da quantidade (M1 ≠ M0 com disponível cheio continua `STALE_SNAPSHOT`);
  - `Unavailable(BUDGET_EXCEEDED | READ_FAILED | BOT_NOT_OWNED)` e `NotRead` → `Unavailable` com o motivo (`NotRead` vira `READ_FAILED`);
  - decisão que não é SELL → `NotApplicable`;
  - pedido = `piso(quantidade × 10^decimais)` com `BigDecimal.movePointRight` e `RoundingMode.DOWN` (quantidade `0.0000019` com 6 casas → 1); estouro de `long` → `ArithmeticException` (falha técnica do ciclo, nunca valor truncado).
- [x] `bots/domain/OwnDecisionInputsTest.java` (unidade):
  - E5: própria 100.000.000, custo 100.000.000, em aberto 60.000.000 → posição de saída com 40.000.000 e custo `piso(100.000.000 × 40.000.000 ÷ 100.000.000) = 40.000.000` lamports (`BigInteger`, `longValueExact`);
  - disponível 0 → o mint vai para `heldNotSellable`, nota `NO_OWN_QUANTITY`; `Unavailable` → todos os mints da carteira em `heldNotSellable`, nota `OWN_QUANTITY_UNAVAILABLE`;
  - `ownPositions ∪ heldNotSellable` = mints da carteira e `ownPositions ∩ heldNotSellable` = vazio (ENT-409);
  - `requestTokens` = os tokens de hoje menos `heldNotSellable`; o conjunto que impede compra é igual ao da carteira.
- [x] Rodar e registrar o vermelho (não compila: `OwnSellCap`, `SellCapOutcome`, `OwnDecisionInputs` não existem).
- Cobre: BR1.2, BR1.4, BR1.5, BR1.6, BR3.1, BR4.2, AC6.1.1, AC6.1.3, NFR2.4, NFR2.6, NFR3.4.

### Etapa 3 — Testes antes do código: a leitura `RealBotHoldings` (C15) e `openSells` (C16) (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/realexecution/infra/RealOpenSellsIT.java` (estende `AbstractRealExecutionIT`): ordens SELL do bot em `PROPOSED`, `QUOTED`, `SIGNED`, `SENT` e `AMBIGUOUS` entram com `source = ORDER` e `input_base_units`; `CONFIRMED`, `FAILED` e `CANCELLED` não entram; decisões SELL `PENDING` entram com `source = DECISION_REQUEST` e `token_base_units`, **inclusive a adiada** (`not_before` no futuro); `PROCESSED` e `DISCARDED` não entram; BUY nunca; ordem e decisão de outro bot e de outro tenant nunca entram; 0 gravações.
- [x] `pnl/application/RealBotHoldingsReaderTest.java` (unidade, dublês das portas e `Clock` falso):
  - relógio em 251 ms → `Unavailable(BUDGET_EXCEEDED)`, nada lançado; `ReadDeadlineExceeded` e SQLSTATE `57014` → `BUDGET_EXCEEDED`;
  - bot fora de `botsOf(tenant, user, REAL)` → `Unavailable(BOT_NOT_OWNED)`, 0 quantidades, 0 custos, 0 vendas em aberto;
  - `DataAccessException`, `CannotGetJdbcConnectionException`, `ArithmeticException` → `READ_FAILED`;
  - `availableTokenBaseUnits = max(0, own − openSell)`; `own = 0` implica custo 0;
  - transação ativa na thread na entrada → `IllegalStateException` antes de qualquer leitura (defeito, nunca `Unavailable`).
- [x] `pnl/RealBotHoldingsParityIT.java` (T12): semente com compras, vendas próprias, vendas com excedente, condicionais e liquidações rateadas para 3 bots em 4 mints; sem vendas em aberto, `ownTokenBaseUnits` e `ownCostLamports` iguais à quantidade e ao custo restante do mesmo bot no `RealBotPnl`, em 100% dos bots e mints; com vendas em aberto, `available = own − open`.
- [x] `pnl/RealBotHoldingsOrderPathParityIT.java`: o leitor recebe o limite por construtor de pacote; o mesmo instantâneo lido forçando a leitura por id e forçando o fluxo (limite 0); casos com exatamente 32 e 33 ordens distintas nos lotes e as sementes A e B; resultados iguais por mint e iguais ao `RealBotPnl` sem venda em aberto.
- [x] `pnl/RealBotHoldingsTenantIT.java` (T6, NFR3.1): duas pessoas de tenants diferentes, o mesmo mint e ordens no mesmo slot; acrescentar ordens, lançamentos e decisões `PENDING` da outra não muda nada; o id de um bot de outra pessoa e de outro tenant → `BOT_NOT_OWNED` e nenhum dado.
- [x] `pnl/RealBotHoldingsIsolationIT.java` (NFR1.4): com a leitura parada no meio por um `CountDownLatch`, um saque, uma liquidação e o consumidor da fila da mesma pessoa terminam sem esperar (3 de 3); `show transaction_isolation` = `repeatable read` e `show transaction_read_only` = `on` dentro dela; o primeiro comando depois do `BEGIN` é o `set local statement_timeout` e o primeiro `select` é o de S1 (`botsOf`); no máximo 1 conexão ativa do pool de teste por leitura.
- [x] `pnl/RealBotHoldingsDeadlineIT.java` (NFR1.7): `pg_sleep` forçado numa consulta → `BUDGET_EXCEEDED` em ≤ 300 ms; a espera pela conexão conta no prazo (pool de teste preso).
- [x] Rodar e registrar o vermelho (não compila: `RealBotHoldings`, `HoldingsResult`, `BotHolding`, `UnavailableReason`, `OpenSell` e `openSells` não existem).
- Cobre: BR1.1, BR1.2, BR1.3, BR2.1, BR2.2, BR2.3, BR2.4, BR3.4, BR7.1, AC6.1.1, AC6.1.2, NFR1.4, NFR1.7, NFR1.11, NFR2.3, NFR2.9, NFR3.1, NFR3.2.

### Etapa 4 — Testes antes do código: o ciclo REAL com a flag ligada vende só a quantidade própria (vermelho)

Todos estendem `AbstractRealExecutionIT` (rede local simulada pelo `FakeRealChain` e `FakeSwapRouter`, KMS simulado, relógio injetado), com a flag ligada no contexto do teste, mint `XYZ` sintético de 6 casas e carteira do bot com os bots A e B da mesma pessoa.

- [x] `bots/worker/OwnQuantitySellIT.java` (T1, E1, E2): A comprou 100 `XYZ` por 100.000.000, B 300 por 450.000.000; o ciclo de A decide SELL; a decisão gravada e a ordem levam 100.000.000; recebido 200.000.000 e taxa 5.000 → realizado de A +100.000.000, taxas de A +5.000; depois, A com própria 0 e B com 300.000.000 e custo 450.000.000; nenhuma parcela "vendido por outro bot"; a soma dos bots mais "Sem bot" bate com o ledger REAL por igualdade exata.
- [x] `bots/worker/OwnQuantityNoOwnIT.java` (T3, E6, E7):
  - só B tem `XYZ`: o pedido de decisão de A não tem `XYZ` nos tokens, a nota `NO_OWN_QUANTITY` fica em `context.ownQuantity.mints` mesmo com o motor terminando em `NO_TOKENS` ou `NO_TRADE`; 0 decisões SELL; `real_cycle_sell_capped_total{outcome="NO_OWN_QUANTITY"}` +1;
  - ordem SELL de A em `AMBIGUOUS` com toda a própria → nenhuma venda;
  - o conjunto de mints que impede compra é idêntico ao do mesmo ciclo com a flag desligada, para a mesma carteira (compras sem mudança);
  - o registro de abertura (`bot_holding`) do mint em `heldNotSellable` continua aberto (BR1.8).
- [x] `bots/worker/OwnQuantityOpenSellsIT.java` (T4, E5, C3): decisão SELL de A de 60.000.000 ainda `PENDING` (e uma adiada) → saída avaliada sobre 40 `XYZ`; uma segunda decisão de 40.000.000 é gravada; as duas somam a própria de A; ordem em andamento e `AMBIGUOUS` descontadas.
- [x] `bots/worker/OwnHoldingsStepTest.java` (T5, unidade com dublês e contador de chamadas):
  - P0 a P3 na ordem: bot PAPER, flag desligada, slot passado de 5 min, slot já reclamado, bot fora de `ACTIVE`, bot com motivo de pausa e pausa do sistema → 0 chamadas do `RealBotHoldings` e 0 leituras de M0 (salvo quando P1 é a conferência que falha);
  - M0 vem da mesma consulta de P1 e é lida antes de o `RealBotHoldings` ser chamado (ordem das chamadas conferida);
  - `real_own_holdings_reads_total` +1 antes da chamada, qualquer que seja o resultado; `NotRead` não conta.
- [x] `bots/worker/OwnHoldingsUnavailableIT.java` (T5, E8): para `BUDGET_EXCEEDED`, `READ_FAILED` (erro de banco injetado) e `BOT_NOT_OWNED`, 0 exceções chegam ao `CycleRunner`, a transação do ciclo segue utilizável, a nota `OWN_QUANTITY_UNAVAILABLE` com o motivo fica no contexto, todos os mints da carteira saem dos tokens, 0 decisões SELL, `real_own_holdings_unavailable_total{reason}` +1, 1 log (`WARN`; `ERROR` para `BOT_NOT_OWNED`); uma compra de outro token no mesmo ciclo segue como hoje; o ciclo seguinte lê de novo; bot pausado, slot expirado ou já reclamado não leem.
- [x] `bots/worker/OwnQuantitySettlementRaceIT.java` (T8, C2): ordem SELL de A de 100.000.000 em `SENT` no começo do instantâneo e assentada durante o ciclo → o ciclo não vende; o ciclo seguinte lê própria 0; com a leitura do efeito na rede falhando, a ordem continua `SENT`, conta como em aberto e o disponível fica 0.
- [x] `bots/worker/OwnQuantityOtherBotsShareIT.java` (T9, propriedade com `java.util.Random` e 3 sementes fixas, pelo menos 50 sequências por semente): A e B na mesma carteira, sequências de compra, venda própria, liquidação e condicionais que terminam antes da leitura ou depois do processamento da decisão; as vendas do ciclo de A somadas nunca passam da própria de A; nenhuma parcela "vendido por outro bot" nas vendas novas; conferência com o ledger exata.
- [x] `bots/worker/OwnQuantityConditionalGapIT.java` (T18, E11, D2): a condicional executada entre a leitura e o processamento da decisão; a venda leva 50.000.000 de B, que aparecem como "vendido por outro bot"; a soma dos bots mais "Sem bot" bate com o ledger; `pnl_real_reconciliation_mismatches_total` continua 0.
- [x] Rodar e registrar o vermelho.
- Cobre: BR1.1, BR1.2, BR1.3, BR1.4, BR1.5, BR1.6, BR1.7, BR1.8, BR2.3, BR2.5, BR2.6, BR3.4, BR4.2, BR7.2, AC6.1.1, AC6.1.2, AC6.1.3, NFR1.10, NFR2.3, NFR2.5, NFR2.9, NFR3.4.

### Etapa 5 — Testes antes do código: concorrência, idempotência, flag, PAPER e saídas do dinheiro (vermelho)

- [x] `bots/worker/OwnQuantityStaleSnapshotIT.java` (T10, C0, C1): dois ciclos de A (slots t e t+5 min) com barreiras de teste; os dois leem M0 = mesma marca; o primeiro grava SELL e renova a marca; o segundo lê M1 ≠ M0 sob a trava e termina `REAL_OWN_QUANTITY_UNAVAILABLE` com `STALE_SNAPSHOT` e 0 decisões; total vendido ≤ própria; um commit de decisão de A entre M0 e a trava só gera `STALE_SNAPSHOT`, nunca uma venda; `real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}` +1 e `real_own_holdings_unavailable_total` sem mudança.
- [x] `bots/worker/RenewMarkOnSellIT.java` (BR3.2): todo ciclo que termina `REAL_RECORDED` com SELL deixa `bot.change_seq` maior que o M1 lido sob a trava, na mesma transação.
- [x] `bots/worker/OwnQuantityOneTransactionIT.java` (T11, AC6.1.4): a decisão SELL cortada reprocessada pela fila, repetida com a mesma `Idempotency-Key` e repetida depois de uma ordem `AMBIGUOUS` gera 1 transação; `ORDER_IN_PROGRESS` recusa a segunda decisão com ordem do bot em andamento.
- [x] `bots/worker/OwnQuantityFlagOffIT.java` (T7, E9): com a flag desligada, 0 chamadas do `RealBotHoldings`, as entradas de decisão e o JSON gravado iguais aos de hoje (sem a chave `ownQuantity`), a venda é a posição inteira da carteira (400.000.000 e o rateio para B como "vendido por outro bot"), as quatro métricas da U4 em 0 e nenhum resultado novo de ciclo.
- [x] `bots/worker/OwnQuantityPaperUnchangedIT.java` (T14): o `DecisionRequest` do PAPER e o JSON gravado (`DecisionJson.write(request, outcome)`) iguais, campo a campo, com a flag ligada e desligada; 0 chamadas do `RealBotHoldings` e 0 leituras da flag no ramo PAPER.
- [x] `bots/worker/OwnQuantityFlagFlipIT.java` (T19, BR5.5): decisão SELL gravada com a flag desligada (posição da carteira) e processada depois de ligar a flag → vende como hoje, o excesso vai para o rateio "vendido por outro bot" e a conferência fecha; a consulta da virada com `jsonb_exists(d.context, 'ownQuantity')` acha exatamente essa decisão.
- [x] `realexecution/OwnQuantityExitsIT.java` (NFR2.2, NFR3.5): flag ligada e um `RealBotHoldings` que sempre lança erro de banco: o saque para a carteira principal, "Vender tudo e sacar" e a liquidação são aceitos (3 de 3).
- [x] `bots/infra/OwnQuantitySellDefaultIT.java` (NFR3.6, BR5.1): sem a variável, `OwnQuantitySell.enabled()` é `false` no perfil da API e no perfil `worker`; com `MEMETRADE_REAL_OWN_QUANTITY_SELL=true`, `true`; o `RealProperties` continua com os valores dele (dois registros com o prefixo `memetrade.real`).
- [x] `architecture/OwnQuantityArchitectureTest.java` (T13, regras R1 a R6 do `security-design.md`) e a amostra `architecture/ownquantity/HoldingsOutsideTheCycleSample.java`, que faz R1 falhar; `architecture/OwnHoldingsSqlNoLockTest.java` (nenhuma constante SQL nova com `for update`, `for share` ou `pg_advisory`); `architecture/OwnHoldingsSqlTenantTest.java` (as constantes `OPEN_SELLS_SQL` e a de P1 contêm `tenant_id = :tenant`).
- [x] Rodar e registrar o vermelho.
- Cobre: BR2.1, BR3.1, BR3.2, BR3.3, BR4.1, BR4.3, BR4.4, BR5.1, BR5.4, BR5.5, BR7.2, AC6.1.4, AC6.2.2, NFR1.3, NFR1.4, NFR2.2, NFR2.6, NFR3.3, NFR3.5, NFR3.6, NFR4.1, NFR4.2, NFR7.1, NFR7.4.

### Etapa 6 — Registrar o vermelho da Fase A

- [x] Rodar os comandos da Fase A de `unit-test-instructions.md` (unidade e integração) e registrar, por classe, o motivo do vermelho (falha de compilação ou de asserção) no `code-summary.md`. Nenhum código de produção da U4 existe neste ponto.
- Cobre: NFR7.1.

## Fase B — banco e leitura (verde da Etapa 3)

### Etapa 7 — Migrações `0026` e `0027`

- [x] Testes escritos primeiro (camada de dados): `apps/api/src/test/java/com/memetrade/db/Migration0026IT.java` (a imagem anterior grava os 12 valores de hoje no esquema novo; a nova grava `REAL_NO_OWN_QUANTITY` e `REAL_OWN_QUANTITY_UNAVAILABLE`; um valor fora da lista é recusado; as linhas de `bot_cycle` antes e depois têm diferença 0; teste de texto: os dois arquivos só têm `ALTER TABLE bot_cycle` com `DROP CONSTRAINT`, `ADD CONSTRAINT ... NOT VALID` e `VALIDATE CONSTRAINT`, e `DROP INDEX CONCURRENTLY IF EXISTS` e `CREATE INDEX CONCURRENTLY`, sem `GRANT`, `REVOKE`, `INSERT`, `UPDATE` nem `DELETE`), `db/Migration0026LockTimeoutIT.java` (com uma transação aberta segurando `bot_cycle` em `ACCESS SHARE`, o changeset 1 falha com `55P03` em ≤ 6 s, a regra antiga continua — um insert com `REAL_NO_OWN_QUANTITY` é recusado — e não há linha do changeset em `DATABASECHANGELOG`; depois de fechar a transação, a segunda execução aplica os dois changesets) e `db/Migration0027IndexRestartIT.java` (no padrão do `Migration0023IndexRestartIT`: com um índice `INVALID` deixado de propósito, a migração o remove e cria um válido, `pg_index.indisvalid = true`; a regra `NOT VALID` da `0026` não é afetada).
- [x] `apps/api/src/main/resources/db/changelog/changes/0026-bot-cycle-own-quantity-outcomes.sql`: `--liquibase formatted sql`; changeset `memetrade:0026-bot-cycle-outcome-not-valid` com `SET LOCAL lock_timeout = '5s';` e um único `ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck, ADD CONSTRAINT bot_cycle_outcome_ck CHECK (outcome IS NULL OR outcome IN (<as 12 de hoje>, 'REAL_NO_OWN_QUANTITY', 'REAL_OWN_QUANTITY_UNAVAILABLE')) NOT VALID;` e o `--rollback` com a lista de hoje `NOT VALID` seguida de `VALIDATE`; changeset `memetrade:0026-bot-cycle-outcome-validate` com `SET LOCAL lock_timeout = '5s';` e `ALTER TABLE bot_cycle VALIDATE CONSTRAINT bot_cycle_outcome_ck;`, `--rollback SELECT 1;`. Nenhum `lock_timeout` de sessão.
- [x] `db/changelog/changes/0027-real-decision-request-bot-pending-sell-idx.sql`: changeset `memetrade:0027-real-decision-request-bot-pending-sell-idx runInTransaction:false` com `DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;` e `CREATE INDEX CONCURRENTLY real_decision_request_bot_pending_sell_idx ON real_decision_request (bot_id) WHERE state = 'PENDING' AND side = 'SELL';`, um único `--rollback DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;`. Nunca `CREATE INDEX IF NOT EXISTS`.
- [x] `db/changelog/db.changelog-master.yaml`: as duas inclusões depois da `0025`, nesta ordem.
- [x] `db/AnonymizationMigrationIT.java` (alterado): desfaz e reaplica a `0026` e a `0027` (um `--rollback` por changeset).
- Cobre: NFR1.12, NFR2.7, NFR3.8, NFR8.6.

### Etapa 8 — `openSells` (C16 estendido, D1) e o `EXPLAIN`

- [x] `realexecution/api/OpenSell.java` (novo record `OpenSell(OpenSellSource source, UUID sourceId, String mint, long baseUnits)`, `baseUnits > 0`) e `realexecution/api/OpenSellSource.java` (enum `ORDER`, `DECISION_REQUEST`).
- [x] `realexecution/api/RealExecutionPort.java`: `List<OpenSell> openSells(UUID tenantId, UUID botId)` (só leitura; participa da transação só de leitura do chamador).
- [x] `realexecution/infra/JdbcRealExecutionStore.java`: constante de pacote `OPEN_SELLS_SQL` com o `UNION ALL` do `performance-design.md`, com os estados, `'SELL'` e `'PENDING'` **literais** e `tenant_id = :tenant` nas duas partes; `realexecution/infra/RealExecutionService.java` delega.
- [x] Testes depois da camada: `realexecution/infra/RealOpenSellsPlanIT.java` (2 tenants; 20.000 decisões fechadas, 6 `PENDING` — 3 SELL, 1 adiada, 3 BUY —, 14.600 ordens finais e 1 ordem SELL em andamento do bot medido; `ANALYZE`; `EXPLAIN (FORMAT JSON)` do `OPEN_SELLS_SQL` exige `"Index Name": "real_decision_request_bot_pending_sell_idx"` e `"Index Name": "uq_real_order_bot_in_progress"` e nenhum `Seq Scan` em `real_decision_request` nem em `real_order`); o `RealOpenSellsIT` da Etapa 3 fica verde. Se o planejador escolher outro caminho, o índice é ajustado antes do commit, nunca o teste.
- Cobre: BR1.3, BR4.3, C16, NFR1.11, NFR1.12, NFR3.1.

### Etapa 9 — Extrair `EpochAttribution` sem mudar comportamento

- [x] `pnl/application/EpochAttribution.java` (novo, de pacote): o acumulador de época que hoje é a classe interna `Accumulation` do `AttributedCostReader`, com os mesmos algoritmos (`RealCostMethod` por titular, `Attribution.of`, a leitura por id e o índice compacto do fluxo); `pnl/application/AttributedCostReader.java` passa a usá-lo, sem cópia de código.
- [x] Testes que continuam verdes sem edição: `RealBotPnlTest`, `RealBotPnlQueryTest`, `AttributedCostConsistencyIT`, `AttributedCostTenantIT`, `RealCycleAttributionIT`, `AttributionFailureGateIT`, `RealCostParityTest`, `RealCostBasisEpochTest`, `RealCycleCostUnknownTest`, `RealCostArchitectureTest`.
- Cobre: BR1.1, NFR2.9, NFR8.5.

### Etapa 10 — `RealBotHoldings` (C15) no `pnl` (verde da Etapa 3)

- [x] `pnl/api/RealBotHoldings.java` (`HoldingsResult holdings(UUID tenantId, UUID userId, UUID botId, Duration budget)`), `pnl/api/HoldingsResult.java` (selado: `Available(List<BotHolding> holdings, Instant readAt)` e `Unavailable(UnavailableReason reason)`), `pnl/api/BotHolding.java` (`mint`, `ownTokenBaseUnits`, `ownCostLamports`, `openSellBaseUnits`, `availableTokenBaseUnits`, com as invariantes de ENT-402 no construtor) e `pnl/api/UnavailableReason.java` (`BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`).
- [x] `pnl/application/RealBotHoldingsReader.java` (novo, `@ProcessRole(WORKER)`): `ReadDeadline.after(clock, budget)` antes de pedir a conexão; `TransactionTemplate` próprio `PROPAGATION_REQUIRES_NEW`, `ISOLATION_REPEATABLE_READ`, `readOnly`, recusando transação ativa; S0 `applyStatementTimeout`; S1 `BotDirectory.botsOf(tenant, user, REAL)` como primeira consulta; S2 a carteira pela leitura da `botwallet.api` que o `RealBotPnl` já usa; S3 `RealLedger.view(tenant, wallet, Optional.empty())`; S4 até `ORDER_PREFETCH_THRESHOLD = 32` ordens distintas por `settledOrder`, acima disso o fluxo de C13 com o prazo e por id só o que faltar; S5 `RealExecutionPort.openSells`; S6 `EpochAttribution` e `openPositions(Holder.bot(botId))`, `available = Math.max(0, own − open)` com `Math.addExact`/`Math.subtractExact`; `deadline.check()` depois do commit; mapeamento das exceções do `reliability-design.md`, nunca a posição da carteira inteira; construtor de pacote com o limite para o `RealBotHoldingsOrderPathParityIT`.
- [x] Refatorar com os testes verdes da Etapa 3.
- Cobre: BR1.1, BR1.2, BR1.3, BR2.1, BR2.2, BR2.3, BR2.4, BR7.1, C15, NFR1.1, NFR1.4, NFR1.7, NFR1.9, NFR1.11, NFR2.3, NFR2.6, NFR2.9, NFR3.1, NFR3.2, NFR4.2, NFR8.5.

### Etapa 11 — Medida da leitura e do ciclo (`RealBotHoldingsMeasurementIT`)

- [x] `pnl/RealBotHoldingsMeasurementIT.java` (padrão do `RealAttributionMeasurementIT` e do `RealRiskMeasurementIT`, Failsafe):
  - **semente A** (NFR1.5): 1 pessoa, 1 carteira do bot, 5 bots REAL e 10 mints, 730 dias, 4 ordens por bot por dia (14.600), 5% de vendas com excedente, 2% de condicionais, 2% de liquidações rateadas, 3 ordens SELL em andamento (uma `AMBIGUOUS`) e 3 decisões SELL `PENDING` (uma adiada) do bot medido, com marcas de zeragem;
  - **semente B** (A19): a mesma, sem nenhuma zeragem em 2 anos (S4 pelo fluxo);
  - por semente, 200 de aquecimento e **10.000 leituras medidas**; p99,9 na 9.990ª (`ceil(0,999 × 10.000)`), p50 (5.000ª), p95 (9.500ª) e p99 (9.900ª) como informação; contagem acima de 250 ms (no máximo 10 para cumprir a meta);
  - ciclo: 10 de aquecimento e 100 medidas com a flag ligada e desligada, p95 na 95ª;
  - razão `capped{STALE_SNAPSHOT} / reads` registrada (deve ser 0);
  - uma linha de log por semente e medida, com a meta ao lado; **sem portão de tempo**: falha só por leitura com erro ou quantidade errada.
- [ ] Conferir o `ORDER_PREFETCH_THRESHOLD = 32` com as duas sementes antes do commit e registrar o resultado no `code-summary.md`; ajustar o valor só sem afrouxar o prazo.
- Cobre: NFR1.1, NFR1.2, NFR1.5, NFR1.6, NFR1.13, NFR2.17.

## Fase C — o ciclo (verde das Etapas 2, 4 e 5)

### Etapa 12 — A flag nos dois processos

- [x] `bots/api/OwnQuantitySell.java` (novo, `boolean enabled()`).
- [x] `bots/infra/OwnQuantitySellProperties.java` (novo `@ConfigurationProperties(prefix = "memetrade.real") record OwnQuantitySellProperties(boolean ownQuantitySell) implements OwnQuantitySell`, sem `@ProcessRole`, lido pela `@ConfigurationPropertiesScan`; padrão `false`).
- [x] `apps/api/src/main/resources/application.yml`: `memetrade.real.own-quantity-sell: ${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}` com o comentário da U4; 0 rotas, 0 actuator gravável e 0 tabelas para a flag.
- [x] O `OwnQuantitySellDefaultIT` da Etapa 5 fica verde.
- Cobre: BR5.1, BR5.4, NFR3.6, NFR8.7.

### Etapa 13 — Domínio puro do corte e das entradas

- [x] `bots/domain/OwnSellCap.java` (`verdict(outcome, holdings, m0, m1, decimals)`, função pura do `security-design.md`), `bots/domain/SellCapOutcome.java` (`CAPPED`, `NO_OWN_QUANTITY`, `STALE_SNAPSHOT`), `bots/domain/OwnQuantityNote.java` (`OWN_QUANTITY_AVAILABLE`, `NO_OWN_QUANTITY`, `OWN_QUANTITY_UNAVAILABLE`), `bots/domain/OwnQuantityReason.java` (motivo da nota do ciclo: os 3 de C15 mais `STALE_SNAPSHOT`, alinhamento A11) e `bots/domain/OwnDecisionInputs.java` (posições próprias, `heldNotSellable`, `requestTokens`, notas por mint). Nenhuma dependência de infraestrutura nem de Spring; sem `float`/`double`.
- [x] Os testes da Etapa 2 ficam verdes; refatorar com eles verdes.
- Cobre: BR1.2, BR1.4, BR1.5, BR1.6, BR3.1, NFR2.4, NFR2.6.

### Etapa 14 — Despacho com o mercado, conferências prévias e `OwnHoldingsStep`

- [x] `bots/worker/DispatchedBot.java` (novo record `DispatchedBot(BotRef ref, BotMarket market)`); `bots/infra/JdbcBotRepository.java`: `activeDispatch()` (a consulta de `activeBots` com `market`), `precheckRow(BotRef)` (P1: `select state, pause_reasons, market, change_seq from bot where bot_id = :bot and tenant_id = :tenant and owner_user_id = :owner`) e `slotClaimed(UUID botId, Instant slot)` (P2, `bot_cycle_slot_uq`); `systemPaused()` (P3) sem mudança.
- [x] `bots/worker/SlotDispatcher.java`: `activeDispatch()` → `cycles.run(DispatchedBot, slot)`.
- [x] `bots/worker/HoldingsSnapshot.java` (selado: `Skipped`, `Read(long m0, HoldingsResult result)`, `NotRead`) e `bots/worker/OwnHoldingsStep.java` (novo, `@ProcessRole(WORKER)`): P0 a P3 em autocommit, M0 da linha de P1, `real_own_holdings_reads_total` +1 antes da chamada, `RealBotHoldings.holdings(tenant, owner, bot, 250 ms)`, atualização do `OwnHoldingsStreaks` (Etapa 16) e o log do `Unavailable`.
- [x] `bots/worker/CycleRunner.java`: `run(DispatchedBot, Instant)` chama o `OwnHoldingsStep` dentro de `auth.as(owner)` e **antes** de `transactions.execute(...)`; `run(BotRef, Instant)` continua para os chamadores de hoje, com o mercado decidido por P1.
- [x] Testes depois da camada: `bots/infra/ActiveDispatchIT.java` (o mercado de cada bot ativo; só `ACTIVE`; o tenant vem da linha); o `OwnHoldingsStepTest` da Etapa 4 fica verde; `CycleIT`, `CycleFailureIT`, `LockOrderIT` e `RecordedSwapCycleIT` continuam verdes.
- Cobre: BR2.1, BR2.5, NFR1.4, NFR1.9, NFR1.10, NFR2.16, NFR3.1.

### Etapa 15 — `CycleRunner`, `RealCycle` e `DecisionJson` com a quantidade própria

- [x] `bots/worker/RealCycle.java`: com a flag ligada e `Read`, `ownInputs(...)` monta as posições de saída pelo `OwnDecisionInputs` (custo próprio convertido em US$ pela cotação do SOL do ciclo) **sem** chamar `walletCost.costOf` (BR1.6); com `Unavailable` ou `NotRead`, todos os mints da carteira em `heldNotSellable`; `order(...)` recebe o veredito do `OwnSellCap`: `Sell(units)` vira `RealDecisionStep.Candidate.sell(..., units, ...)`, nunca `position.tokenBaseUnits()` da carteira; com a flag desligada, o caminho de hoje sem mudança.
- [x] `bots/worker/CycleRunner.java`: no ramo REAL com a flag ligada, tira `heldNotSellable` dos tokens **antes** de `market.read`; o laço que fecha registros de abertura pula os mints de `heldNotSellable` (BR1.8); M1 = `bot.changeSeq()` da linha devolvida por `locks.lockBot` (sem consulta a mais); o veredito do corte é calculado depois de `engine.decide` (e da estratégia gravada do perfil de teste) e **antes** de `decisions.insert`; `step.submit` só com venda > 0; resultados `REAL_NO_OWN_QUANTITY` e `REAL_OWN_QUANTITY_UNAVAILABLE` gravados por `cycles.finish`; `bots.renewMark` no fim como hoje; logs `REAL_OWN_SELL_STALE`, `REAL_OWN_SELL_NO_QUANTITY`, `REAL_OWN_SELL_CAPPED` e `REAL_OWN_HOLDINGS_UNAVAILABLE` com `precheck=NOT_READ`.
- [x] `decision/infra/DecisionJson.java`: sobrecarga `write(DecisionRequest, DecisionOutcome, OwnQuantityContext)` que grava `context.ownQuantity` (`read`, `reason`, `mints`, `cap` com pedido e venda) só nas decisões REAL com a flag ligada; `OwnQuantityContext` é um record do próprio `decision.infra` com textos de enum, para o `decision` não depender de `bots` nem de `pnl`; `write(request, outcome)` e `read(...)` sem mudança.
- [x] Testes depois da camada: `decision/infra/DecisionJsonOwnQuantityTest.java` (a chave só com o contexto; o JSON sem contexto igual ao de hoje, byte a byte; `read` ignora a chave e reconstrói o mesmo `DecisionRequest`); os testes das Etapas 4 e 5 ficam verdes; `RealCycleOrderCapTest`, `RealCycleCostUnknownTest`, `RealBotDecisionModeTest` e `RealCycleAttributionIT` continuam verdes.
- Cobre: BR1.4, BR1.5, BR1.6, BR1.7, BR1.8, BR2.3, BR2.6, BR3.1, BR3.2, BR3.3, BR4.2, BR4.4, BR5.1, BR5.4, AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, NFR1.2, NFR1.3, NFR2.4, NFR2.5, NFR3.3, NFR3.4, NFR4.1.

### Etapa 16 — Contadores, gauge dos bots presos e logs

- [x] `bots/infra/OwnQuantityMetrics.java` (novo, `@Component`, `@ProcessRole(WORKER)`): no construtor, `real_own_holdings_reads` (1 série), `real_own_holdings_unavailable{reason}` (3 séries, `pnl.api.UnavailableReason`) e `real_cycle_sell_capped{outcome}` (3 séries, `SellCapOutcome`): 3 contadores com 7 séries registradas com 0; o gauge `real_own_holdings_stuck_bots` sobre `OwnHoldingsStreaks::stuckBots`.
- [x] `bots/worker/OwnHoldingsStreaks.java` (novo, `@ProcessRole(WORKER)`): `ConcurrentHashMap<UUID, Streak(int failures, Instant lastSeen)>`; `Available` e `BOT_NOT_OWNED` removem; `BUDGET_EXCEEDED`/`READ_FAILED` somam 1; `NotRead` não muda; `stuckBots()` conta `failures ≥ 3` com `lastSeen` nos últimos 20 min; despejo por 20 min a cada atualização e leitura; teto de 10.000 entradas, com `WARN` `event=REAL_OWN_HOLDINGS_STREAKS_FULL`; relógio injetado.
- [x] Testes depois da camada:
  - `bots/infra/OwnQuantityMetricsTest.java` (`SimpleMeterRegistry`): prazo esgotado → `reads` 1, `unavailable{BUDGET_EXCEEDED}` 1 e as outras 2 em 0; `Available` → só `reads`; corte de 150 para 100 → `capped{CAPPED}` 1; M1 ≠ M0 → `capped{STALE_SNAPSHOT}` 1 e `unavailable` inalterado;
  - `bots/worker/OwnHoldingsStreaksTest.java`: 2 falhas e uma leitura boa → 0; 3 falhas → 1; leituras boas com `STALE_SNAPSHOT` → 0; `BOT_NOT_OWNED` → removida; 21 min sem leitura → despejada; 10.001 bots → 10.000 entradas e 1 `WARN`;
  - `bots/OwnQuantityMetricsIT.java`: as 8 séries (7 contadores e o gauge) em `/actuator/prometheus` do worker (porta de gestão 8082) com 0 logo depois da subida; no perfil da API, 0 delas;
  - `bots/OwnQuantityLogRedactionTest.java`: os 5 eventos (indisponível, `STALE_SNAPSHOT`, `NO_OWN_QUANTITY`, `CAPPED`, `BOT_NOT_OWNED`) com 1 registro cada, `correlationId` e `botId`; 0 registros com o endereço semeado da carteira do bot ou da principal, a assinatura semeada, token, `Authorization`, nonce ou SQL.
- Cobre: BR2.3, NFR2.10, NFR2.11, NFR2.13, NFR2.16, NFR2.18, NFR3.7, NFR3.9, NFR3.10.

### Etapa 17 — Fronteiras ArchUnit (verde do T13)

- [x] O `OwnQuantityArchitectureTest` da Etapa 5 fica verde: R1 (só `bots.worker..` depende de `RealBotHoldings` e `HoldingsResult`), R2 (`realexecution..`, `realexits..`, `botwallet..` e `riskcontrol..` não dependem de `pnl..`), R3 (`pnl.application.RealBotHoldings*` sem `LockOrder`, `WalletLock`, `RealRiskGate.withPersonLock` nem `JdbcCycleRepository`), R4 (sem `float`/`double`), R5 (sem `PaperExecution`, `LedgerReadModel` nem tipos do P&L PAPER), R6 (`OwnSellCap` sem infraestrutura nem Spring).
- [x] Continuam verdes: `ArchitectureTest` (sem ponto flutuante, com os pacotes novos), `BotsArchitectureTest` (`bots -> pnl.api`), `RealCostArchitectureTest`, `ModeBoundaryArchitectureTest`, `NeverSumModesArchitectureTest`, `RealExecutionArchitectureTest`, `PnlViewsArchitectureTest`.
- Cobre: BR2.1, BR4.1, BR4.3, BR4.4, NFR1.4, NFR2.6, NFR3.3, NFR3.5, NFR4.2.

### Etapa 18 — Testes obrigatórios por risco que continuam verdes

- [x] Rodar, sem edição: pausa do bot (`CycleIT`, `KillSwitchIT`), deduplicação por chave (`PaperExecutionIdempotencyIT`, `DecisionConsumerIT`), uma transação por ordem REAL (`DecisionConsumerIT`, `ConfirmationAndAmbiguousIT`, `OrderDriverIT`), saque sempre disponível e só para a carteira principal (`WithdrawalLimitIT`, `BotWalletRoutesIT`), REAL desligado por padrão (`RealOffByDefaultRoutesIT`, `RealReleaseRouteOffIT`), isolamento entre contas com tarefa em segundo plano (`RealPnlTenantIsolationIT`, `RealBotHoldingsTenantIT`), modos nunca somados (`NeverSumModesArchitectureTest`, `RealNeverPaperIT`), ledger sem edição (`RealPositionEpochAppendOnlyIT`) e a liquidação (`LiquidationIT`, `LiquidationExitIT`).
- Cobre: BR3.3, BR4.1, BR4.4, NFR2.2, NFR3.3, NFR3.5, NFR7.4.

## Fase D — contrato e tela

### Etapa 19 — C7 `BotPage.realOwnQuantitySell` e o contrato regenerado

- [x] `bots/web/BotDtos.java` (`BotPageDto`): `boolean realOwnQuantitySell`, sempre enviado, com a descrição de C7; fora de `required` no OpenAPI (ver o conflito 2).
- [x] `bots/web/BotController.java` (`list`): lê `OwnQuantitySell.enabled()` da API; o valor não depende do filtro, do modo nem da pessoa.
- [x] Testes depois da camada: `bots/web/BotListOwnQuantityFieldIT.java` (padrão → `false` presente no JSON em todos os filtros; com a propriedade ligada → `true`; o campo não muda com `market`, `state` nem `cursor`); `contract/GuestOpenApiContractTest.java` (o campo `realOwnQuantitySell` `boolean` em `BotPage`; nenhum total de dinheiro em `BotPage`).
- [x] Regenerar `apps/api/openapi/openapi-guest.yaml` (`GuestOpenApiContractTest` com `-Dopenapi.write=true`, depois sem) e `apps/web/src/lib/api/schema.d.ts` com `pnpm --dir apps/web run gen:api`, nunca à mão, conferido por `check:api`; o `check:api` do `apps/admin` não muda.
- Cobre: BR5.3, AC6.2.1, AC6.2.2, C7, NFR6.2, NFR7.8.

### Etapa 20 — Aviso da lista `/bots` (FR6.2, D5): T15 a T17 antes do código da tela

- [x] Primeiro, `apps/web/src/features/bots/BotList.test.tsx` (alterado, casos novos, vermelho registrado):
  - T15: com `realOwnQuantitySell: true` e o filtro "Todos" ou "Real", `getByRole('status', { name: <texto exato> })` acha o aviso, inclusive quando a página corrente só tem bots PAPER ou está vazia por causa da paginação (D5);
  - T16: com `false`, com o campo ausente (API anterior) e com o filtro "Simulado", 0 avisos e nenhum erro;
  - T17: o nome acessível é o texto exato; o aviso não depende de cor (texto puro, sem ícone obrigatório); o texto não contém "lucro" nem "garant"; o aviso não fica dentro de nenhum bloco ou total do simulado.
- [x] `apps/web/src/features/bots/mode-messages.ts`: `list.ownQuantityNotice` = "Cada bot real vende só a quantidade que ele mesmo comprou. Posições herdadas ou de outros bots continuam na carteira e podem ser vendidas em Vender tudo e sacar."
- [x] `apps/web/src/features/bots/BotList.tsx`: `<p role="status" className="notice" data-testid="bots-own-quantity-notice">` com o texto, visível quando `page.realOwnQuantitySell === true` e `mode !== 'PAPER'`, ao lado do `bots-disclaimer` e com os mesmos tokens de cor (contraste ≥ 4,5:1 no tema escuro, par de cores registrado no `code-summary.md`); o parágrafo quebra linha; o filtro da U3 e o resto da tela não mudam (RK-6).
- [x] Os testes ficam verdes; `BotModeFilter.test.tsx` e `BotScreens.test.tsx` continuam verdes.
- Cobre: BR6.1, BR6.2, BR6.3, AC6.2.1, AC6.2.2, AC6.2.3, NFR4.3, NFR5.1, NFR5.2, NFR6.2.

### Etapa 21 — Caso de 360 px no spec `zz-paper-real-unico`

- [x] `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts`: um caso novo que intercepta `GET /api/v1/bots` por `page.route` (a resposta real com `realOwnQuantitySell: true`, porque a flag é desligada no `verify.sh`), abre `/bots` e `/bots?mode=REAL` em 360 px, confere o aviso por `role="status"` e o nome exato, `scrollWidth ≤ clientWidth` da página, e a ausência do aviso em `/bots?mode=PAPER`. O spec continua o último da suíte e já está no `scripts/verify.sh`: nenhuma linha nova é pedida ao responsável.
- [x] Sem interceptação, o caso confere que a lista real (flag desligada) não mostra o aviso (BR5.1).
- Cobre: BR6.2, BR6.3, AC6.2.3, NFR6.1, NFR7.5.

### Etapa 22 — Verificação do `apps/web`

- [x] `pnpm --dir apps/web run lint`, `format:check`, `typecheck`, `check:api` e `exec jest --coverage` com o piso de 80% de linhas; 0 variáveis `NEXT_PUBLIC_*` novas.
- Cobre: NFR7.3, NFR7.8.

## Fase E — observabilidade, IaC e runbook

### Etapa 23 — Regras do Prometheus com o T20 antes (NFR7.1, NFR7.6)

- [x] Primeiro (vermelho registrado): `infra/observability/tests/real-ops-own-holdings-unavailable.test.yml` (`rule_files: [../real-ops-alerts.yml]`, `evaluation_interval: 1m`, todo bloco com `interval: 15s`) com os 10 casos do `observability-design.md` (falha persistente; falha isolada; jitter; fronteira; ruído da cauda; rajada; `BOT_NOT_OWNED` e `STALE_SNAPSHOT` fora do numerador; flag desligada; um bot preso entre 63; falha passageira), cada caso que espera alerta com `exp_labels: { severity: high }` e as `exp_annotations` completas (o `summary` já dobrado pelo `>-`);
- [x] `apps/api/src/test/java/com/memetrade/botwallet/RealOpsOwnHoldingsAlertRuleIT.java` (Failsafe): lê `../../.github/workflows/ci.yml` com SnakeYAML, pega `jobs.backend.steps[name == "Alert rules (promtool)"].run`, extrai `prom/prometheus@sha256:[0-9a-f]{64}` e exige exatamente 1 digest distinto (senão falha com o motivo); `GenericContainer` dessa imagem com `infra/observability` em `/rules`, entrada `promtool`, comando `test rules /rules/tests/real-ops-own-holdings-unavailable.test.yml`, `OneShotStartupCheckStrategy` com 2 min; saída diferente de 0 falha com a saída do `promtool`;
- [x] `botwallet/CiPromtoolDigestTest.java` (unidade, sem Docker): o digest único; 0 ou 2 digests distintos e passo ausente falham com a mensagem;
- [x] `botwallet/AlertRuleTestAnnotationsTest.java` (unidade): as `exp_annotations` do arquivo de teste iguais às anotações das duas regras; o `for: 15m` igual a `for_seconds`;
- [x] Depois, `infra/observability/real-ops-alerts.yml`: `RealOpsOwnHoldingsUnavailable` e `RealOpsOwnHoldingsStuckBots` no grupo `real-ops`, depois de `RealOpsPnlReconciliationMismatch`, com o texto exato do `monitoring-design.md` (expressões, `for: 15m` só na razão, `labels: { severity: high }`, anotações `summary`, `threshold`, `window_seconds`, `for_seconds` e `cloud_periods`).
- [x] O `ci.yml` não muda: o laço do `promtool check rules` já confere o arquivo, agora com 16 regras.
- Cobre: BR2.7, AC6.1.3, NFR2.12, NFR2.18, NFR7.6, NFR7.7.

### Etapa 24 — Gêmeos no CloudWatch, lista de permissão e paridade

- [x] `infra/terraform/modules/observability/real-ops.tf`: `aws_cloudwatch_metric_alarm.real_ops_own_holdings_unavailable` (4 `metric_query`: `e1` com `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)` e `return_data = true`; `m1` e `m2` com `real_own_holdings_unavailable_total` e `reason` `BUDGET_EXCEEDED`/`READ_FAILED`; `m3` com `real_own_holdings_reads_total` sem dimensões; `Sum`, `period = 600`; `evaluation_periods = 2`, `datapoints_to_alarm = 2`, `threshold = 0.05`, `GreaterThanThreshold`, `notBreaching`, `alarm_actions = [var.alarm_topic_arn]`) e `aws_cloudwatch_metric_alarm.real_ops_own_holdings_stuck_bots` (`Minimum`, `period = 300`, 2 de 2, `threshold = 0`, `notBreaching`, sem `dimensions`), com as `alarm_description` do `infrastructure-specification.md`.
- [x] `infra/terraform/modules/observability/app-metrics.tf`: `real_own_holdings_reads_total` e `real_own_holdings_stuck_bots` no grupo `[[]]`; grupo novo `{ dimensions = [["reason"]], metrics = ["real_own_holdings_unavailable_total"] }`; `real_cycle_sell_capped_total` fora da lista; os dois nomes em `alarm_resource_names` e os dois recursos em `metric_alarms`; saída nova `alarm_expressions` (`alarm_name → [expressões das metric_query]`, só dos alarmes de expressão). `metrics-agent.tf` não muda.
- [x] Testes depois da camada: `botwallet/RealOpsAlarmParityTest.java` (alterado): `hasSize(16)`; `Rule` ganha `cloudPeriods` (anotação `cloud_periods`, 1 quando ausente) e a janela confere `cloudWindow(rule.windowSeconds(), twoReads) × rule.cloudPeriods()`; testes novos `theOwnHoldingsAlertIsARatioWithItsTwinInCloudWatch` e `theStuckBotsAlertHasItsTwinInCloudWatch`; o teste "só os jobs parados tratam dado ausente como `breaching`" com o mesmo resultado.
- Cobre: BR2.7, NFR2.12, NFR2.13, NFR2.18, NFR3.7, NFR8.1, NFR8.2, NFR8.8, NFR8.9.

### Etapa 25 — A flag no Terraform (F4) e os testes offline

- [x] `infra/terraform/main.tf`: `variable "real_own_quantity_sell_api"` (`bool`, `default = false`, a descrição da F4) ao lado de `founder_user_id`; `variable "real_own_quantity_sell_worker"` (`bool`, `default = false`, `validation { condition = !var.real_own_quantity_sell_worker || var.real_own_quantity_sell_api, error_message = "real_own_quantity_sell_worker needs real_own_quantity_sell_api: turn the api on first and the worker off first (U4 NFR2.8)." }`); `api_environment` com `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_api)` e `worker_environment` com `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_worker)`, com o comentário da U4 e da F4; `app_environment` sem mudança.
- [x] `infra/terraform/environments/devnet.tfvars.example` e `mainnet.tfvars.example`: `real_own_quantity_sell_api = false` e `real_own_quantity_sell_worker = false`, com o comentário da ordem (ligar a API e depois o worker; desligar o worker e depois a API). Nenhum `tfvars` real é criado nem editado.
- [x] `infra/terraform/modules/services/outputs.tf`: saída `api_worker_container_environment` (`{ for c in jsondecode(aws_ecs_task_definition.api_worker.container_definitions) : c.name => { for e in try(c.environment, []) : e.name => e.value } }`, só `environment`, sem segredos).
- [x] `infra/terraform/tests/observability_metrics.tftest.hcl`: em `every_alarm_reads_a_published_metric`, os dois `alarm_names` (`eureka-real-real-ops-own-holdings-unavailable` e `eureka-real-real-ops-own-holdings-stuck-bots`), os três nomes em `app_metric_names` e `real_cycle_sell_capped_total` fora, `app_metric_dimension_sets` (`["reason"]` e `[""]`) e `alarm_expressions` com o texto exato; `run "own_quantity_flag_default"` (`command = apply`: `api` e `worker` com `"false"` e a chave ausente do `metrics-agent`); `run "own_quantity_flag_api_only"` (`api` `"true"` e `worker` `"false"`); `run "own_quantity_flag_worker_without_api"` com `expect_failures = [var.real_own_quantity_sell_worker]`. Nenhuma asserção lê `local.*`; nenhuma fica mais frouxa (3 painéis e 5 consultas salvas continuam).
- [x] O `terraform` não está instalado nesta máquina: `terraform fmt -check`, `terraform validate`, `terraform test` e Checkov ficam com o responsável, rodados à mão antes do commit, e o resultado vai para o Build and Test (`team.md`).
- Cobre: BR5.2, BR5.4, NFR2.8, NFR3.6, NFR8.3, NFR8.4, NFR8.7.

### Etapa 26 — Runbook, seções 10 a 13

- [x] `docs/runbooks/real-operations.md`, depois da "9. Índice da migração 0023", com o escalonamento em cada seção (o fundador é o único on-call; defeito vira issue com o `correlationId` do ciclo):
  - **10. Posição própria indisponível:** os dois alarmes e os gêmeos; nesse estado o bot não vende pelo ciclo, e a saída é pelas condicionais, por "Vender tudo e sacar" e pelo saque; o motivo no log (`event=REAL_OWN_HOLDINGS_UNAVAILABLE`, `reason`); `BUDGET_EXCEEDED` sistêmico (`hikaricp_connections_pending`, carga do RDS, `ledger_real_epoch_fallbacks_total`); bot preso (`WARN` com `streak` ≥ 3 e o `botId`; carteira inteira = 3 bots da mesma pessoa; depois de reinício, cerca de 25 min); `BOT_NOT_OWNED` sempre vira defeito; se não resolver, desligar a flag do worker;
  - **11. Virada da flag de quantidade própria:** as pré-condições (Performance Validation do 261113, medida do NFR1.5, aprovação, código em serviço, e na mainnet a prova do alarme da razão na devnet), a mudança do `tfvars` do ambiente (F4), os passos de ligar (API, conferência, worker, conferência depois da troca) e de desligar (worker, depois API), o caminho manual do fundador enquanto o `deploy.yml` não existe, as três consultas só de leitura numa sessão `REPEATABLE READ READ ONLY` (com `jsonb_exists(d.context, 'ownQuantity')`) e o registro na nota da implantação;
  - **12. Migração 0026 ou 0027 barrada:** o `55P03`, a transação em `pg_stat_activity` (nunca matar à força a do ciclo ou da fila), a repetição com o mesmo SHA, o índice `INVALID` da `0027` e a consulta de `indisvalid`, a trava do changelog presa;
  - **13. Revisão de `STALE_SNAPSHOT`:** a consulta semanal de 24 h e o critério (> 1% ou 3 seguidos de um bot, `event=REAL_OWN_SELL_STALE`).
- [x] As seções 1 a 9 não mudam.
- Cobre: BR2.7, BR5.2, BR5.5, NFR2.8, NFR2.14, NFR2.17.

## Fase F — alinhamentos, verificação e documentação

### Etapa 27 — Alinhamentos do Functional Design (A1 a A12, A15)

- [x] A1: `functional-design/rules.md`, BR2.7 e a linha BR2.7 do resumo: substituída pela F2 (razão `[10m]` `> 0.05` com `for: 15m`; CloudWatch `IF(FILL...)`, `period = 600`, 2 de 2, `notBreaching`), mais a referência ao alarme do bot preso (F3).
- [x] A2: `functional-spec.md`, T20: os 10 casos por Testcontainers com o digest do `ci.yml`.
- [x] A3: `functional-spec.md`, E8: "persistindo acima de 5% das leituras por 15 minutos, alerta (NFR2.12)".
- [x] A4: `functional-spec.md`, "Efeito nos contratos", métrica: 3 motivos em `real_own_holdings_unavailable_total`; `STALE_SNAPSHOT` em `real_cycle_sell_capped_total{outcome}`; mais `real_own_holdings_reads_total` e o gauge.
- [x] A5: `functional-spec.md`, "Efeito nos contratos", migração: dois changesets na `0026` com `lock_timeout` de 5 s, mais a `0027`.
- [x] A6: `functional-spec.md`, "Testes da tela": T15 a T17 antes do código.
- [x] A7: `functional-spec.md` F6 passo 4 e `rules.md` BR5.5: também 0 ordens SELL de bot em andamento, as duas consultas e a consulta por `jsonb_exists`, numa sessão `REPEATABLE READ READ ONLY`.
- [x] A8: `functional-spec.md` F3 passo 1: M1 = `change_seq` da linha de `lockBot`; o veredito antes de `decisions.insert`; `step.submit` só com venda > 0.
- [x] A9: `functional-spec.md` F1 passos 0 e 1: M0 na mesma consulta de P1, antes de P2, P3 e do instantâneo.
- [x] A10: `functional-spec.md` F2 e `entities.md` ENT-408: o objeto `context.ownQuantity` (`read`, `mints`, `cap`), interno, sem rota nem contrato.
- [x] A11: `entities.md` ENT-404: dois enums (`pnl.api.UnavailableReason` com 3 valores; `bots.domain.OwnQuantityReason` com os 3 mais `STALE_SNAPSHOT`).
- [x] A12: `rules.md` BR2.5: `read = NOT_READ` na nota e sem subir os contadores de leitura.
- [x] A15: `functional-spec.md`, "O que existe hoje", `SlotDispatcher`: `DispatchedBot(ref, market)` e `run(BotRef, Instant)` mantido.
- [x] Os nomes reais do código (por exemplo, `OwnQuantityReason`, `HoldingsSnapshot`) entram onde o texto dizia "nome proposto".
- Cobre: alinhamentos A1 a A12 e A15 do NFR Design.

### Etapa 28 — Alinhamentos dos requisitos e do contrato (A13, A14, A16 a A19)

- [x] A13: `inception/contract-design/contract-summary.md` C15: `concurrency` (a garantia é a marca do bot, não `withPersonLock`), `transaction` (`ReadDeadline` com o tempo que resta, por comando e por busca, e a conferência final com a espera da conexão) e o consumidor (`CycleRunner` pelo `OwnHoldingsStep`, antes da transação).
- [x] A14: `contract-summary.md` C16: `OpenSell(source, sourceId, mint, baseUnits)` com as decisões SELL `PENDING`, consulta com literais e o índice `real_decision_request_bot_pending_sell_idx`. No mesmo arquivo, C7 ganha a nota "sempre enviado pelo servidor, fora de `required`" (conflito 2).
- [x] A16: `nfr-requirements/reliability-requirements.md` NFR2.7, rollback: changeset 2 volta com `SELECT 1`; changeset 1 recria a lista de hoje `NOT VALID` e valida.
- [x] A17: `nfr-requirements/` — linha nova **NFR2.18** em `observability-requirements.md` (gauge `real_own_holdings_stuck_bots`, alarme `RealOpsOwnHoldingsStuckBots` e gêmeo `Minimum` 300 s 2 de 2 `notBreaching`, `cloud_periods: "1"`); NFR2.13 com a exceção desse alarme; NFR7.6 em `reliability-requirements.md` com os casos 9 e 10; NFR8.2 em `scalability-requirements.md` com cerca de US$ 1,90; `hasSize(16)` em NFR2.12 e em `tech-stack-decisions.md` NFR8.9; NFR2.18 no `nfr-requirements/traceability.json` (alvo de NFR2) e fora do `reverse` `N/A` do `nfr-design/traceability.json`.
- [x] A18: `observability-requirements.md` NFR2.12: a expressão `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, conferida pelo teste e pelo teste offline.
- [x] A19: `performance-requirements.md` NFR1.5: 10.000 medidas por semente, p99,9 na 9.990ª, sem portão de tempo, a semente B e o `RealBotHoldingsOrderPathParityIT`.
- [x] A diferença da F4 com o `team.md` (Deployment, Flags: "mudam por commit em `main`") **não** é escrita aqui: fica como pendência para o ritual de aprendizado, registrada no `code-summary.md`.
- Cobre: alinhamentos A13, A14, A16 a A19 do NFR Design e a F4 do Infrastructure Design.

### Etapa 29 — Verificação

- [x] Rodar os comandos da Unit (`unit-test-instructions.md`): Fase A (vermelho e verde), backend de unidade e de integração, T20, contrato, Jest.
- [ ] `./apps/api/mvnw -f apps/api/pom.xml verify` com JaCoCo ≥ 80% e Spotless; no `apps/web`, a Etapa 22. O piso nunca é reduzido. Se a máquina não aguentar o `verify` completo (memória), a prova é o `ci` do commit, registrado como na U2 e na U3.
- [ ] Verificação do ponto de controle: `bash scripts/verify-checkpoint.sh` no commit pedido ao responsável. Se o `verify.sh` local morrer por memória, a prova é o run verde do `ci` do mesmo commit, com a árvore limpa fora dos registros do AI-DLC e o commit no remoto (`team.md`).
- [x] Os `run` do `terraform test` (Etapa 25) e o Checkov ficam com o responsável; a mediana de 3 execuções do `RealBotHoldingsMeasurementIT` vai para `docs/perf/u4-own-quantity-cycle-<data>.md` no Build and Test.
- Cobre: NFR7.2, NFR7.3, NFR7.4, NFR7.5.

### Etapa 30 — Rastreabilidade

- [x] `construction/u4-own-quantity-cycle/code-generation/traceability.json` no formato do verificador de rastreabilidade (`project.md`, Corrections de 2026-11-20): `stage: code-generation`, `unit: u4-own-quantity-cycle`; IDs `NFRx.y` **sem** o prefixo da Unit; **uma linha por AC, BR e NFR da Unit** (AC6.1.1 a AC6.2.3, BR1.1 a BR7.2, NFR1.1 a NFR8.4 e NFR2.18); cada alvo `OK` com **um único arquivo existente** (por exemplo, `apps/api/src/main/java/com/memetrade/bots/domain/OwnSellCap.java` para BR1.4), e os demais arquivos e testes numa nota; as linhas de referência de `tech-stack-decisions.md` (NFR2.15, NFR7.7, NFR7.8, NFR8.5 a NFR8.17) com o mesmo tratamento do estágio anterior.
- [x] Tabela história → etapas abaixo conferida contra o arquivo.
- Cobre: rastreabilidade de todos os AC, BR e NFR da Unit.

### Etapa 31 — `code-summary.md` e `source-manifest.json`

- [x] `construction/u4-own-quantity-cycle/code-generation/code-summary.md`: arquivos, decisões, vermelhos registrados, resultados, testes não executados com o motivo (Terraform; T20 sem Docker, se for o caso), desvios, o par de cores do aviso, o valor confirmado de `ORDER_PREFETCH_THRESHOLD`, a pendência da F4 para o ritual de aprendizado e, se algum for preciso, os textos prontos para o responsável (nenhuma mudança em `.github/workflows/` nem em `scripts/verify.sh` é esperada).
- [x] `construction/u4-own-quantity-cycle/code-generation/source-manifest.json`: `"stage": "code-generation"`, `"unit": "u4-own-quantity-cycle"`, `"version": 1` e só o array `writes` (um `{ "path": ... }` por arquivo criado ou alterado no repositório), sem `deletes` e sem nenhum caminho em `aidlc/`.
- Cobre: NFR7.2.

## Rastreabilidade história → etapas

| História | Critérios | Etapas |
|---|---|---|
| US6.1 (o bot real vende só o que comprou) | AC6.1.1 | 2, 3, 4, 10, 13, 15 |
| | AC6.1.2 | 3, 4, 10, 15 |
| | AC6.1.3 | 2, 4, 15, 16, 23 |
| | AC6.1.4 | 5, 15, 18 |
| US6.2 (aviso de que cada bot vende só o que comprou) | AC6.2.1 | 19, 20 |
| | AC6.2.2 | 5, 12, 19, 20, 21, 25 |
| | AC6.2.3 | 20, 21 |

## Riscos e conflitos

- **Conflito 1 — ordem dos testes da tela e da regra de alerta.** O brief desta geração põe o aviso da tela e os alarmes entre as camadas de teste depois do código. O NFR7.1 aprovado (Q2 do NFR Requirements) e o alinhamento A6 pedem os 20 cenários T1 a T20 antes do código, inclusive T15 a T17 (tela) e T20 (regra). Proposta do plano: seguir o NFR7.1, que é o critério de aceite aprovado e o mais estrito; os testes da tela e o T20 entram com o vermelho registrado antes do código da própria camada (Etapas 20 e 23), e as outras partes dessas camadas (métricas, gêmeos no CloudWatch, Terraform, runbook, contrato) ficam teste depois. Isso não contradiz o `team.md`, que pede o mínimo.
- **Conflito 2 — C7 opcional ou sempre presente.** O C7 diz "campo opcional e aditivo; ausente ou false"; a BR5.3 diz "sempre presente". Proposta do plano: o servidor sempre envia o campo, e o OpenAPI o deixa fora de `required`, para que o tipo gerado aceite a API anterior sem o campo (T16, NFR6.2). A nota entra no C7 na Etapa 28.
- **Medida com 10.000 leituras (A19) contra 2.000 (NFR1.5).** Vale o A19, que só aumenta a amostra, como a suposição 1 do `performance-requirements.md` permite; o texto do NFR1.5 é alinhado na Etapa 28.
- **BR2.7 substituída pela F2 e acrescida da F3.** O alarme de "3 ciclos seguidos" sai; entram a razão e o bot preso. Alinhado na Etapa 27 (A1).
- **`STALE_SNAPSHOT` na métrica.** O `functional-spec.md` o põe no contador de indisponibilidade; vale a Q3 do NFR Requirements (contador do corte). Alinhado na Etapa 27 (A4).
- **F4 contra o `team.md` (Flags).** O `team.md` diz que as flags mudam por commit em `main`; com a F4, o padrão `false` continua versionado e o valor ligado fica no `tfvars` de cada conta, fora do repositório. Esta geração não edita o `team.md`; a pendência vai para o ritual de aprendizado.
- **Ferramentas ausentes na máquina local.** O `terraform` e o Checkov não estão instalados: os `run` novos do `terraform test` ficam escritos e são rodados à mão pelo responsável (pendência do `team.md`). O `promtool` roda pelo Testcontainers no T20; sem Docker local, a prova é o job `backend` da CI.
- **`ORDER_PREFETCH_THRESHOLD = 32` é ponto de partida.** A medida das duas sementes confirma ou ajusta o valor antes do commit; ele nunca afrouxa o prazo de 250 ms.
- **Plano do `openSells`.** Se o planejador não provar a implicação dos índices parciais com os literais, o índice ou o texto da consulta é ajustado antes do commit, nunca o `RealOpenSellsPlanIT`.
- **Interface `RealExecutionPort` com método novo.** Nenhuma implementação de teste da porta existe (os testes usam o Mockito), mas um dublê novo teria de implementar `openSells`; o `RealExecutionService` é a única implementação de produção.
- **`DecisionJson` sem depender de `bots` nem de `pnl`.** O contexto da quantidade própria é um record do próprio `decision.infra`, com textos de enum; o `decision.domain` não muda (NFR4.1).
- **Ciclo da estratégia gravada (perfil de teste).** O corte vale também para uma SELL decidida pelo `RecordedSwapStrategy` com a flag ligada; o `RecordedSwapCycleIT` roda com a flag desligada e continua verde.
- **Concorrência nos ITs.** O T10 e o T8 dependem de barreiras de teste (`CountDownLatch`) e não de tempo; se ficarem instáveis, é defeito a investigar, nunca teste a ignorar (`team.md`).
- **Performance Validation do 261113 pendente.** A flag não liga em nenhum ambiente até ela passar; a implantação do código com `"false"` não depende dela.
- **Rollback.** Só a imagem volta: a `0026` (regra mais larga) e a `0027` (índice não usado) ficam. Uma imagem anterior à U4 ignora a variável da flag; antes de voltar à imagem da U4, desligar a flag do worker no `tfvars` e refazer a virada.
