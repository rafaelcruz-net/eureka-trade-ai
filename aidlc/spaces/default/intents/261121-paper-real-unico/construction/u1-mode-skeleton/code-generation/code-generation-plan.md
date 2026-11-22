# Plano de geração de código — U1 `u1-mode-skeleton`

## Summary

- Builds: criação de bot no modo PAPER ou REAL (elegibilidade com o motivo mais forte, 409/503), limites REAL da conta, P&L por modo em dois blocos sem soma, lista com o modo e o spec de ponta a ponta do esqueleto.
- Touches: `apps/api` (bots, pnl, riskcontrol.real, botwallet, testes de arquitetura), `apps/web` (features/bots, features/pnl, dashboard/envelopes.ts), `infra/terraform/main.tf`, `.env.example`, `apps/web/e2e/paper-real-unico`.
- Tests: cerca de 120 testes novos ou alterados (unidade, integração com Testcontainers, contrato OpenAPI, ArchUnit, Jest com Testing Library e um spec Playwright), com piso de 80% por parte.

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
  "input_sha256": "sha256:2f37cce03f8c6795927541f60d5b6d5864ed1047398ed6788af09600b546818f",
  "contract_sha256": "sha256:56d0ddeea38f5a5c511464e5a0af9654c06d4ef7dc7eee1865782c81123753f7"
}
```

## Fontes

- `construction/u1-mode-skeleton/functional-design/`: `rules.md` (BR1.1 a BR10.5, inclusive BR1.9 e BR2.4), `entities.md`, `functional-spec.md`, `frontend-components.md` e `traceability.json` (70 ACs).
- `construction/u1-mode-skeleton/nfr-requirements/` (NFR1.1 a NFR8.12).
- `construction/u1-mode-skeleton/nfr-design/`, com a decisão do fundador sobre o `DecisionRequest` REAL, a ligação no worker, a fila cheia, a guarda da semente, o 422 em `changeLimits` e o teto `real-max-orders`.
- `construction/u1-mode-skeleton/infrastructure-design/`, com as chaves `@ConfigurationProperties`, o Terraform, a linha do `.env.example` e o ponto de inserção no `verify.sh`.
- `inception/contract-design/contract-summary.md` (C1 a C4, C8 a C10, C17 e o alargamento do enum `mode`).
- `inception/units-generation/unit-of-work.md` (U1, RK-1 a RK-8).
- `aidlc/spaces/default/memory/team.md` e `project.md`.

## Ordem aplicada

O Testing Contract acima é `custom`:
- **Testes escritos antes do código** (vermelho → verde → refatorar): toda regra do núcleo financeiro e da lógica REAL de carteira e risco que a U1 toca. Isso inclui:
  - a elegibilidade e o motivo mais forte (BR2.x);
  - a recusa 409 e a falha 503 dentro do comando idempotente (BR1.4, BR2.4);
  - o ramo REAL do ciclo sem limites próprios e o `maxOrderSize` pelo limite da conta (BR1.9);
  - a regra de nunca somar os modos (BR5.x);
  - a linha REAL por bot (C10);
  - a idempotência da criação sob concorrência (NFR1.12).
- **Teste depois da camada**: configuração, adaptadores de leitura, rotas web, telas e infraestrutura.

O runner é conferido antes do primeiro teste (Etapa 2).

## Etapas

### Etapa 1 — Estrutura e configuração de produção

- [x] Criar `apps/api/src/main/java/com/memetrade/pnl/infra/PnlByModeProperties.java` (`@ConfigurationProperties("memetrade.pnl.by-mode")`) com `blockTimeout` (padrão 800 ms, faixa de 100 a 900 ms) e `realMaxOrders` (padrão 50.000), validados com `@Validated`.
- [x] Acrescentar `realEligibilityTimeout` (padrão 500 ms) a `apps/api/src/main/java/com/memetrade/bots/infra/BotProperties.java`, via `@ConfigurationProperties`.
- [x] Acrescentar os valores padrão em `apps/api/src/main/resources/application.yml`.
- [x] Remover do `.env.example` a linha `# MEMETRADE_REAL_SEED_TEST_RELEASE=` (linha 133).
- Cobre: NFR1.9, NFR1.10, NFR3.8 e as obrigações da infraestrutura.

### Etapa 2 — Conferir os runners e gravar os comandos da Unit

- [x] Conferir que `./mvnw -q -pl . test -Dtest=<classes da U1>` e `./mvnw -q verify -Dit.test=<ITs da U1>` rodam no `apps/api`.
- [x] Conferir que `pnpm --dir apps/web exec jest <arquivos da U1>` roda.
- [x] Gravar os comandos exatos em `unit-test-instructions.md`.

### Etapa 3 — Testes antes do código: elegibilidade REAL e motivo mais forte (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/bots/domain/RealCreationEligibilityTest.java`: os 5 motivos isolados; as 10 combinações de dois motivos, que devolvem sempre o mais forte (KILL_SWITCH > REAL_DISABLED > WALLET_INACTIVE > NOT_RELEASED > QUARANTINE); QUARANTINE com `effectiveAt` em UTC; elegível quando nenhum motivo vale.
- Cobre: AC1.2.x, BR2.1 a BR2.3.

### Etapa 4 — Elegibilidade REAL (verde)

- [x] `bots/domain/RealCreationEligibility.java`, uma função pura sobre o estado lido.
- [x] `bots/application/RealEligibilityReader.java`, que lê `RealStateQuery` e `RealReleaseInfo` (botwallet.api) e o kill switch, com o prazo `realEligibilityTimeout`. Falha ou prazo esgotado viram `EligibilitySourceUnavailable`.
- [x] Refatorar com os testes verdes.

### Etapa 5 — Testes antes do código: criação REAL no comando idempotente (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/bots/application/BotCreateRealIT.java` (Testcontainers):
  - PAPER cria normalmente;
  - REAL elegível cria o rascunho;
  - cada um dos 5 motivos devolve 409 `REAL_NOT_ELIGIBLE` com `reason`;
  - fonte indisponível devolve 503 e não grava nada;
  - a recusa e o 503 não ficam guardados (repetir com a mesma chave reavalia);
  - uma criação concluída repetida com a mesma chave devolve a resposta guardada;
  - a auditoria da recusa sobrevive ao rollback (`REQUIRES_NEW`);
  - `statement_timeout` com `pg_sleep` dentro do ponto de salvamento devolve 503, não 500 (sem erro 25P02);
  - regressão: a carteira inativa deixa de devolver 422 e passa a devolver 409.
- [x] `BotCreateConcurrencyIT.java`: 10 criações simultâneas com a mesma chave criam exatamente 1 bot. Cada resposta é 201 com o mesmo corpo ou 409 `IDEMPOTENCY_CONFLICT`/`IN_PROGRESS`, e pelo menos uma é 201.
- Cobre: AC1.3.x, AC1.4.x, AC1.5.x, BR1.1 a BR1.4, BR2.4, NFR1.12, NFR4.x.

### Etapa 6 — Criação REAL no comando idempotente (verde)

- [x] `bots/application/BotCommandService.java`:
  - `create` confere a elegibilidade dentro do comando idempotente, depois da reivindicação da chave;
  - a leitura roda num ponto de salvamento (`PROPAGATION_NESTED`);
  - `market` vale PAPER quando ausente e não muda depois de criado.
- [x] `bots/infra/RealCreationRefusalAudit.java`: auditoria com `REQUIRES_NEW`, sem `@ProcessRole`.
- [x] `bots/infra/BotCreationMetrics.java`: `bots.real.create.refusals{reason}`, registrado com zero na subida, sem `@ProcessRole` (o worker também registra as 5 séries).
- [x] `bots/web/BotWebErrors.java`: 409 `REAL_NOT_ELIGIBLE` com `reason` e `effectiveAt`; 503 com `correlationId`.
- [x] Refatorar com os testes verdes.

### Etapa 7 — Testes antes do código: bot REAL sem limites próprios (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/bots/BotModePathIT.java`, percurso completo para REAL e para PAPER: validar → ativar → ciclo → pausar → retomar → parar.
  - REAL: nenhuma versão de limites é gravada; `DecisionRequest.limitsVersion = 0`; `maxOrderSize = floor(cap × orderMaxBps / 10000)` convertido por `RealSizing.dollarsOfLamports` e arredondado para baixo; sem teto, sem preço ou com C9 indisponível, `maxOrderSize = 0` dá `QUANTITY_BELOW_MINIMUM`, sem compra, e as vendas continuam.
  - Bots REAL antigos com versão de limites: a versão é ignorada no ramo REAL.
  - PAPER: o comportamento não muda.
- [x] `BotLimitsRealRefusedIT.java`: `changeLimits` num bot REAL devolve 422 `BOT_VALIDATION_FAILED`.
- Cobre: BR1.8, BR1.9, AC1.4.x, NFR7.x.

### Etapa 8 — Bot REAL sem limites próprios (verde)

- [x] `bots/worker/CycleRunner.java`: `currentLimitsVersion` e `bots.limits` passam para o ramo PAPER. O ramo REAL monta o `DecisionRequest` com o limite de ordem da conta (C9).
- [x] `bots/application/BotCommandService.java`: `stop`, `resume` e `validate` exigem a versão de limites só no PAPER; o rascunho REAL é validado sem saldo virtual nem limites; `changeLimits` devolve 422 para REAL.
- [x] `bots/worker/RealCycle.java`: passa `cap`, `orderMaxBps` e `solUsd` para o tamanho.
- [x] Documentar o sentinela `limitsVersion = 0` em `bots/web/BotDtos.java` e no OpenAPI.
- [x] Refatorar com os testes verdes.

### Etapa 9 — Limites REAL da conta (C3 e C9), teste depois

- [x] `riskcontrol/api/RealAccountLimits.java` (interface) e o DTO `RealAccountLimitsView`, com o teto, a ordem (lamports e bps), as compras por bot e por dia UTC e a perda do dia (base `min(teto no início do dia, teto atual)`). `NOT_DEFINED` vale quando o teto é 0.
- [x] `riskcontrol/real/infra/RealAccountLimitsReader.java`, que implementa a interface a partir de `RealRiskLimits` e `JdbcRealRiskStore`.
- [x] Rota `GET /api/v1/real/account-limits` em `botwallet` ou `riskcontrol`, conforme o C3, com tenant tirado da autenticação.
- [x] Testes: `RealAccountLimitsReaderTest` (6 casos, incluindo teto 0 e percentuais arredondados para baixo) e `RealAccountLimitsRouteIT` (401, 403 de novo aceite, 200 e 503).
- Cobre: AC1.6.x, BR3.1 a BR3.3, C3, C9.

### Etapa 10 — Rota de elegibilidade (C2), teste depois

- [x] `GET /api/v1/bots/real-eligibility` em `bots/web/BotController.java`, com DTO em `BotDtos.java`.
- [x] Teste `RealEligibilityRouteIT`: cada motivo, elegível, 503 e tenant.
- Cobre: AC1.2.x, C2.

### Etapa 11 — Testes antes do código: P&L por modo sem soma (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/pnl/application/ModePnlViewTest.java`: dois blocos com a moeda de cada um (USD e SOL), sem campo de total geral; um bloco que falha vira `UNAVAILABLE` com `correlationId` e o outro continua; fila cheia (`RejectedExecutionException`) vira `UNAVAILABLE`; prazo conferido de novo depois de abrir a transação; MDC nulo é limpo.
- [x] `RealBotPnlQueryTest.java` (C10, primeira implementação): cada bot só com as próprias ordens (`bot_id`); `unowned` nulo; `prorated` vazio; ordens acima de `realMaxOrders` deixam o bloco `UNAVAILABLE` com `cause=TOO_MANY_ORDERS`; taxas já incluídas no realizado; o total REAL é `realized + unrealized`.
- [x] `ModePnlFailureIT.java`: 4 cenários (PAPER falha, REAL falha, tempo esgotado e pool saturado).
- Cobre: AC2.2.x, AC4.1.x, AC4.2.1, AC4.2.3, BR5.1 a BR5.6, NFR4.4, NFR4.5.

### Etapa 12 — P&L por modo (verde)

- [x] `pnl/api/RealBotPnlQuery.java` (interface da U2) e a implementação da U1 em `pnl/application/RealBotPnlQueryV1.java`, sobre `RealPnlCalculator`, com leitura por pessoa (`real_order_user_created_idx`) e agrupamento por bot em memória.
- [x] `pnl/api/BotDirectory.java` (C8), implementada em `bots/application/BotDirectoryImpl.java`: ACTIVE e CLOSING_PENDING viram `ACTIVE`, PAUSED vira `PAUSED`, STOPPED vira `REMOVED`; DRAFT e VALIDATED ficam de fora.
- [x] `pnl/modeview/ModePnlView.java`, `ModeBlockExecutor.java` (pool `pnl-mode-block`, 8 threads e fila 16, com cópia do MDC) e `PnlModeMetrics.java` (`pnl.mode_block.unavailable{market}`), todos com `@ProcessRole(API)`.
- [x] Rota `GET /api/v1/pnl/by-mode?period=` em `pnl/web/PnlController.java`, com DTOs em `PnlDtos.java` (dinheiro como texto inteiro, `status` como discriminador).
- [x] Refatorar com os testes verdes.

### Etapa 13 — Regra de arquitetura de nunca somar e contrato

- [x] `apps/api/src/test/java/com/memetrade/architecture/NeverSumModesArchitectureTest.java`: só `pnl.modeview` vê PAPER e REAL juntos e não chama os acessores de valor; listas fechadas de tipos; a falha de cada parte da regra é provada.
- [x] `ModeBoundaryArchitectureTest`: `ModePnlView` fica fora de `pnl.real`, e `pnl.real` continua puro (sem PAPER).
- [x] Regenerar `openapi-guest.yaml` e atualizar o `GuestOpenApiContractTest`: rotas C1 a C4; `mode == market`, deprecated, enum `[PAPER, REAL]`; nenhum campo de total geral; 409 `REAL_NOT_ELIGIBLE`; 422 em `changeBotLimits`.
- Cobre: BR5.4, BR6.x, NFR3.x.

### Etapa 14 — `mode == market` e lista com o modo

- [x] `bots/web/BotDtos.java`: `BotSummary.mode` e `BotDetail.mode` passam a refletir `market`, deprecated.
- [x] Testes: `BotSummaryModeTest` (PAPER e REAL) e `BotRoutesIT` atualizado (a lista traz o modo e o filtro por tenant continua; bot de outra pessoa dá 404).
- Cobre: AC2.1.x, AC2.4.x, BR6.1, BR8.1, BR8.2.

### Etapa 15 — Semente de teste e a sua guarda

- [x] `RealTestSeeder` passa a agir só com a rede `LOCALNET`; teste de unidade com os 3 valores de rede.
- [x] `apps/api/src/test/java/com/memetrade/architecture/RealSeedProfileGuardTest.java`: `@Profile("test")` exato; `seed-test-release: false` no `application.yml`; nenhuma ocorrência da chave fora do `infra/docker-compose.real.yml`, inclusive em comentários, nos Dockerfiles, em `.github/workflows/`, no `.env.example` e no Terraform; mais um teste de contexto do perfil padrão sem a semente.
- Cobre: BR10.2, NFR3.8.

### Etapa 16 — Ligação das três chaves

- [x] `apps/api/src/test/java/com/memetrade/config/ByModePropertiesBindingIT.java`: as variáveis `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT`, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT` e `MEMETRADE_PNL_BYMODE_REALMAXORDERS` ligam; um valor fora da faixa faz a subida falhar.
- [x] `infra/terraform/main.tf`: as três variáveis no `api_environment` do `module "services"`, com os valores padrão.
- Cobre: NFR1.9, NFR1.10, NFR8.1.

### Etapa 17 — Web: envelopes e tipos gerados (teste depois)

- [x] Regenerar os tipos do contrato (`pnpm --dir apps/web run gen:api` ou o script que já existe) e conferir com `check:api`.
- [x] `apps/web/src/features/dashboard/envelopes.ts`: `oneOf(raw.mode, ['PAPER', 'REAL'])`; atualizar fixtures e testes que fixam PAPER; teste novo de que um resumo de bot REAL é aceito.
- [x] Estender ao `features/pnl` a proibição de `Number(`, `toFixed` e `parseFloat` no `eslint.config.mjs`, com teste em `lint-rules.test.ts`.
- Cobre: BR10.5, BR7.5.

### Etapa 18 — Web: criação no modo (teste depois)

- [x] `features/bots/ModeStep.tsx`: cartões "Simulado" e "Real"; o "Real" indisponível usa `aria-disabled` e o motivo é lido numa região `aria-live`.
- [x] `features/bots/RealConfirmDialog.tsx`, sobre o `Dialog`: a caixa de confirmação libera o botão; recusa de domínio fecha o diálogo, relê a elegibilidade e volta para "Simulado"; erro técnico oferece "Tentar de novo" com a mesma chave.
- [x] `features/bots/RealBotLimitsStep.tsx`: os limites da conta só para leitura, em SOL, com "não definido" e o link "Alterar limites".
- [x] `BotCreate.tsx` e `BotWizard.tsx`: o passo "Modo" vira a etapa 1 e o `market` vai no pedido. No REAL, sem saldo virtual.
- [x] Testes `ModeStep.test.tsx`, `RealConfirmDialog.test.tsx`, `RealBotLimitsStep.test.tsx` e `BotCreate.test.tsx` (5 a 8 casos cada), cobrindo teclado, `aria-disabled` e os estados.
- Cobre: AC1.1.x, AC1.2.x, AC1.3.x, AC1.6.x.

### Etapa 19 — Web: lista e blocos por modo (teste depois)

- [x] `features/bots/BotList.tsx`: coluna "Modo" e cartões em 360 px.
- [x] `apps/web/src/components/SimulatedBadge.tsx`.
- [x] `features/pnl/PnlModeBlock.tsx` e `features/pnl/money-format.ts`: SOL sem zeros à direita e com pelo menos 2 casas; US$ com 2 casas; `bigint` só dentro de `features/real/money.ts`, reaproveitado.
  - Estados: Carregando, Pronto, Vazio, Desconhecido e Erro, com `UNAVAILABLE` e o código para o suporte.
  - O aviso do simulado é fixo.
  - A nota "Algumas operações ainda não têm bot dono; o rateio chega na próxima etapa." aparece no bloco REAL não vazio.
  - O rótulo "Taxas (já incluídas no realizado e no custo)".
- [x] `PnlScreen.tsx`: os dois blocos, sem total geral, com o foco inicial no h2 do bloco Simulado.
- [x] Testes `PnlModeBlock.test.tsx`, `money-format.test.ts`, `BotList.test.tsx` e `PnlScreen.test.tsx` atualizados.
- Cobre: AC2.1.x, AC2.2.x, AC4.1.x, AC4.2.1, AC4.2.3, BR5.x, BR7.x, NFR5.x, NFR6.x.

### Etapa 20 — Spec de ponta a ponta do esqueleto

- [x] `apps/web/e2e/paper-real-unico/paper-real-unico.spec.ts`, no validador local:
  - cria um bot PAPER e um REAL;
  - faz uma compra simples em cada um;
  - confere que cada bloco mostra só o próprio modo e a própria moeda e que não existe total geral;
  - confere o aviso fixo do simulado.

  A liberação vem da semente do perfil de teste.
- [x] Texto pronto para o responsável acrescentar ao `scripts/verify.sh`, na linha 162, depois de `e2e/real-v2-exits`: ` e2e/paper-real-unico`. Eu não edito o `verify.sh`.
- Cobre: o esqueleto do `team.md` e o AC2.2.x.

### Etapa 21 — Verificação, documentação e rastreabilidade

- [x] Rodar os comandos da Unit: `./mvnw verify` (com JaCoCo ≥ 80%) no `apps/api`, e `lint`, `typecheck`, `jest --coverage` e `check:api` no `apps/web`.
- [x] Escrever `code-summary.md` e `traceability.json` no formato do verificador: uma linha por AC, BR e NFR da Unit e um único arquivo existente por alvo OK.
- [x] Alinhar os textos do `performance-design.md` ("nunca lidos do ambiente") e do NFR8.1 ("0 mudanças nas definições de tarefa"), registrados como pendência no `code-summary.md`.
- [ ] Verificação do ponto de controle: `bash scripts/verify-checkpoint.sh`.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US1.1 | 18 |
| US1.2 | 3, 4, 10, 18 |
| US1.3 | 5, 6, 18 |
| US1.4 | 5, 6, 7, 8 |
| US1.5 | 5, 6 |
| US2.1 | 14, 19 |
| US2.2 | 11, 12, 19, 20 |
| US2.4 | 13, 14 |
| US3.2 (parte da U1) | 11, 12 |
| US4.1 | 11, 12, 13, 19 |
| US4.2 (parte da U1) | 11, 12, 19 |
| US4.4 e US4.5 (parte da U1) | 19 |
| US5.3 (conferência PAPER) | 11, 12 |

## Riscos e rollback

- **RK-1:** os totais REAL só fecham com o ledger depois da U2. A U1 nunca vai para um ambiente com usuários antes da U2, e o spec usa só compras simples.
- **BR1.9:** a mudança toca o `CycleRunner`. O `BotModePathIT`, as suítes PAPER que já existem e os specs REAL do `verify.sh` protegem essa mudança.
- **Rollback:** volta só a imagem. Não há migração, e as três variáveis do Terraform têm os mesmos valores padrão.
