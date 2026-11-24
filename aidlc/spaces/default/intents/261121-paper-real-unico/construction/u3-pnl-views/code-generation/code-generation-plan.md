# Plano de geração de código — U3 `u3-pnl-views`

## Summary

- Builds: as telas de P&L da visão única (filtro por modo em `/bots`, período com "Hoje" em `/pnl`, releitura de um bloco, parcelas rateadas e "Sem bot", detalhe do bot REAL com as linhas de C5, comparação C6 e painel com dois cartões) e, no servidor, C4 com `market` e `RANGE`, `listBots` com `market` e contagens, C5, C6, a auditoria limitada das recusas, três contadores e o cenário de carga ampliado.
- Touches: `apps/api` (`pnl.web`, `pnl.modeview`, `pnl.application`, `pnl.api`, `pnl.real`, `bots`, `audit.api`, `tenancy/route-registry.yml`, `openapi-guest.yaml`), `apps/web` (`lib/api`, `features/pnl`, `features/bots`, `features/dashboard`, `e2e/zz-paper-real-unico`), `scripts/load` e os textos de design e de contrato (alinhamentos); nenhuma migração, nenhum Terraform, nenhum arquivo de `.github/workflows/` nem o `scripts/verify.sh`.
- Tests: cerca de 330 testes novos ou alterados: integração com Testcontainers escritos antes do código na composição do P&L no servidor (soma das páginas de C5, cursor, lados de C6 iguais a C4, limites do `RANGE`, nenhum terceiro número), unidade, ArchUnit, contrato OpenAPI, Jest com Testing Library, `node:test` e 10 casos novos de Playwright, com piso de 80% por parte.

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

- `construction/u3-pnl-views/functional-design/`: `rules.md` (52 regras, BR1.1 a BR12.1, com a decisão do fundador da BR2.9 e o "Hoje" da BR2.1), `functional-spec.md` (F1 a F7 e as máquinas de estado), `entities.md`, `frontend-components.md` (os 6 componentes novos, as mudanças, a integração com a API e os 10 casos de ponta a ponta) e `traceability.json` (45 ACs: 33 da U3 e 12 `N/A` entregues pela U1 e pela U2).
- `construction/u3-pnl-views/nfr-requirements/`: NFR1.1 a NFR8.4, as decisões do fundador D-NFR1 (auditoria das recusas) e D-NFR2 (carga de C5, C6 e C4 com `RANGE`), NFR3.13, NFR3.14 e NFR3.17.
- `construction/u3-pnl-views/nfr-design/`: decisões do fundador F-1 (a consulta `ownedByAnotherTenant` na thread do pedido nos dois 404), F-2 (a única exceção ao filtro de tenant) e F-3 (o reducer `pnl-screen-state.ts`), o cursor de C5 de 44 caracteres, o `ReadRefusalAudit`, o `windowedRefresh`, `retry: false` e a lista de alinhamentos A1 a A10 (`logical-components.md`).
- `construction/u3-pnl-views/infrastructure-design/`: D-INF3 (na nuvem, a evidência dos contadores são os logs e a auditoria), D-INF4 (o passo do `ci.yml`), o texto pronto do `load-test.yml` (9 leituras, 7 portões novos), a regra de leitura do relatório e o `terraform plan` vazio.
- `inception/contract-design/contract-summary.md`: C4 (parâmetros e forma), C5, C6, C8 (`BotDirectory`), C10 (`botLines`), C17, regras de posse 2 e 6, PQ-6 e PQ-8.
- `construction/u1-mode-skeleton/code-generation/code-summary.md` e `construction/u2-real-bot-pnl/code-generation/code-summary.md` (o que existe: `ModePnlView`, `ModeBlockExecutor`, `PnlModeMetrics`, `RealBotPnl`, `RealPnlEngine`, `ReconciliationAuditGate`, `RateLimitedAuditPublisher`, `MismatchAuditWriter`, `TableScroll`, `money-format.ts`, `by-mode.ts`, o spec `zz-paper-real-unico`, o cenário `pnl-by-mode` e o texto pronto do `load-test.yml` da U2).
- `aidlc/spaces/default/memory/team.md` e `project.md`.
- Código lido: `pnl/web/PnlController.java` (C4 recusa `from`, `to` e `RANGE` desde a U1), `pnl/api/BotDirectory.java`, `pnl/api/RealBotPnlQuery.java` (sem `botLines`), `pnl/real/RealPnlEngine.java`, `bots/web/BotController.java` (`listBots`), `bots/application/BotQueries.java`, `bots/infra/JdbcBotRepository.java` (`list` ordena por `updated_at desc`), `audit/api/AuditActions.java` (`TENANT_MISMATCH` existe), `tenancy/route-registry.yml`, `ModePnlFailureIT.java`; `apps/web/src/lib/api/client.ts` e `guest-api.ts`, `features/pnl/*` (`PnlScreen.tsx` com `useState`, `period.ts`, `PnlScreen.test.tsx`), `features/bots/BotList.tsx`, `features/dashboard/DashboardProvider.tsx` (assina só `bot.state`, `trade.recorded`, `pnl.updated`, `data.status` e `heartbeat`), `dashboard-loader.ts`, `concurrency.ts`; `scripts/load/*`.

## Ordem aplicada

O Testing Contract acima é `custom`:

- **Testes escritos antes do código** (vermelho → verde → refatorar), a partir das regras, na composição do P&L no servidor, que decide o que entra no número mostrado (NFR7.7; suposição 2 da `reliability-requirements.md`):
  - os limites do `RANGE` de C4 (`from` incluído, `to` excluído, 1 ms antes de `to` entra) e o mesmo realizado em C4 e no lado de C6 com o mesmo par de instantes;
  - os lados de C6 iguais às linhas do mesmo bot em C4, sem terceiro número (C4 com e sem `market`, C6);
  - a soma de todas as páginas de C5 igual à linha do bot em C4 com `period=ALL`;
  - a estabilidade do cursor de C5 com uma ordem nova entre duas páginas, e o próprio cursor (forma, validação estrita, busca da âncora).
- **Teste depois de cada camada**: filtro `market` e contagens de `listBots`, rotas e erros, `ownedByAnotherTenant` e a regra ArchUnit, a auditoria limitada, contadores e logs, paridade de tempo, contrato, cliente, telas, reducers, spec de ponta a ponta e cenário de carga.
- O runner é conferido antes do primeiro teste (Etapa 1).
- **Commits.** Nenhum commit é feito por esta geração sem o pedido do responsável (`team.md`). O contrato de C4 (chaves `paper` e `real` opcionais) e o leitor do `apps/web` entram no mesmo commit (NFR7.8): as Etapas 2 a 25 formam o primeiro commit pedido; as Etapas 26 e 27 podem ir nele ou num segundo commit.
- **Nunca editados por esta geração:** `.github/workflows/` e `scripts/verify.sh` (os textos prontos vão para o `code-summary.md`); `infra/terraform/` e `infra/observability/` (NFR8.1, NFR8.4); o `code-summary.md` da U2 (revisão R-03 do Infrastructure Design).

## Etapas

### Etapa 1 — Conferir os runners e gravar os comandos da Unit

- [x] Conferir que `./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='ModePnlViewTest'` e `./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='ModePnlFailureIT' -Dtest=none` rodam.
- [x] Conferir que `pnpm --dir apps/web exec jest src/features/pnl/period.test.ts src/features/pnl/PnlScreen.test.tsx` e `node --test "scripts/load/*.test.mjs"` rodam (o padrão entre aspas; a forma com diretório falha no Node 24).
- [x] Os comandos exatos ficam em `unit-test-instructions.md`. Se um runner falhar, a Etapa 2 não começa.
- Cobre: NFR7.1, NFR7.3.

## Fase A — testes antes do código: composição do P&L no servidor

### Etapa 2 — Testes antes do código: limites do `RANGE`, lados de C6 e nenhum terceiro número (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/pnl/PnlRangeBoundaryIT.java` (semente de resultado conhecido, nos dois modos):
  - um lançamento exatamente em `from` entra; um em `to` fica de fora; um 1 ms antes de `to` entra;
  - o mesmo par de instantes dá o mesmo realizado e as mesmas taxas na linha do bot em C4 e no lado de C6;
  - o não realizado é o de agora nos dois (AC7.2.1 da U2 continua);
  - `RANGE` sem `from` ou `to`, `from >= to`, duração acima de 366 dias em UTC e `from`/`to` com `ALL` dão 400 `VALIDATION_FAILED` antes de qualquer leitura.
- [x] `pnl/PnlCompareEqualsByModeIT.java`: com `ALL` e com `RANGE` de 30 dias, total, realizado, não realizado e taxas do lado PAPER de C6 iguais à linha do mesmo bot no bloco PAPER de C4 (`compareTo == 0`); os do lado REAL iguais em `long`; diferença 0.
- [x] `pnl/web/PnlNoThirdNumberTest.java` (unidade, propriedade com `java.util.Random` e 3 sementes fixas, pelo menos 200 respostas por semente): o JSON de C4 sem `market`, com `market=PAPER`, com `market=REAL` e o de C6 só têm as chaves `period`, `paper` e `real`; nenhum número fora dos blocos ou dos lados; nenhum campo de diferença, percentual, ranking ou vencedor em C6; a chave do bloco não pedido ausente, nunca `null`.
- [x] Rodar e registrar o vermelho (não compila ou falha: `RANGE` recusado, C6 e `market` inexistentes).
- Cobre: BR2.4, BR3.1, BR5.4, BR6.1, BR6.2, BR6.3, AC4.1.2, AC7.1.3, AC7.2.3, AC8.1.1, AC8.1.2, NFR1.1, NFR2.3, NFR3.6, NFR4.1, NFR4.2, NFR4.4, NFR7.7.

### Etapa 3 — Testes antes do código: C5, soma das páginas e estabilidade do cursor (vermelho)

- [x] `pnl/application/BotLinesCursorTest.java`: o cursor é `base64url` sem preenchimento de `0x01 || botId (16 bytes) || SHA-256("pnl-line:" + idempotencyKey + "|" + origin)[0..16)` (44 caracteres); recusa comprimento diferente de 44, caractere fora do alfabeto, versão diferente de `0x01` e `botId` diferente do caminho, sem ecoar o texto; o cursor não contém assinatura, slot, mint nem endereço.
- [x] `pnl/application/RealBotPnlBotLinesTest.java` (dublês das portas, no padrão do `RealBotPnlTest`):
  - linhas na ordem decrescente da chave de ordem de rede da primeira linha do evento, com desempate pela ordem do enum `origin`;
  - `limit` respeitado; `nextCursor` igual ao cursor da última linha devolvida quando há mais, `null` no fim;
  - a página começa depois da âncora; âncora não encontrada dá a falha de cursor (400 na rota);
  - a compra traz `realizedLamports = 0`; as parcelas `LIQUIDATION`, `CONDITIONAL` e `SOLD_BY_OTHER_BOT` do bot aparecem com a própria origem;
  - um evento só de taxa (ordem que falhou) vira linha com o lado da ordem, valor 0 e a taxa;
  - bot que não é REAL da pessoa: vazio (404 na rota).
- [x] `pnl/BotPnlLinesSumIT.java`: cenário com compras, vendas, uma liquidação, uma venda condicional e uma parcela vendida por outro bot, mais de 100 linhas; percorrer todas as páginas com `limit=50` até `nextCursor` nulo; a soma com `Math.addExact` de `realizedLamports` e de `feeLamports` é igual ao realizado e às taxas da linha do bot no bloco REAL de C4 com `period=ALL`; diferença 0; o número de linhas é o do cenário.
- [x] `pnl/BotPnlLinesCursorStabilityIT.java`: ler a 1ª página, gravar uma ordem nova confirmada do mesmo bot, ler as seguintes com o cursor; 0 linhas repetidas (pela identidade `idempotencyKey` mais `origin`), 0 perdidas; a linha nova fora das páginas seguintes e presente numa 1ª página lida de novo; uma confirmação atrasada com slot mais antigo que a âncora aparece uma vez só.
- [x] Rodar e registrar o vermelho (`botLines` e `BotLinesCursor` não existem).
- Cobre: BR4.2, BR4.3, BR4.4, BR4.6, AC3.10.1, AC3.10.2, NFR1.11, NFR2.3, NFR3.7, NFR7.7.

## Fase B — backend

### Etapa 4 — Janela `RANGE` e modo único na `ModePnlView`; `lineOf` no PAPER (verde da Etapa 2)

- [x] `pnl/web/PnlWindows.java` (novo): converte `period`, `from` e `to` em `PnlWindow`; as recusas da Etapa 2 com o campo do erro; usado por C4 e C6.
- [x] `pnl/modeview/UnavailableSink.java` (novo, interface funcional `(PnlMarket, Cause) -> void`).
- [x] `pnl/modeview/ModePnlView.java`: `statement(window, paper, real, sink)` e `single(market, window, block, sink)`; o `sink` conta o bloco `UNAVAILABLE` (C4 passa `PnlModeMetrics::unavailable`); a view continua genérica e nunca toca num valor.
- [x] `pnl/application/PaperModeBlockReader.java`: `PnlWindow.Range` pelo mesmo `PnlQueryService.reportSince(person, from, to, bot)` de `GET /api/v1/pnl`; `lineOf(person, bot, window)` usado pelo bloco e pelo lado PAPER de C6.
- [x] `pnl/web/PaperBlockSource.java` e `RealBlockSource.java`: recebem a janela; `RealBotPnl` já aceita `PnlWindow`.
- [x] Refatorar com os testes verdes.
- [x] Testes depois da camada: `pnl/web/PnlWindowsTest.java` (6 casos), `pnl/application/ModePnlViewTest.java` (alterado: `single` envia 1 tarefa; o `sink` de cada rota recebe só a sua contagem). `ModePnlFailureIT.onlyThePeriodAllIsAcceptedInTheU1` muda só de nome: as asserções continuam valendo (`RANGE` sem `from`/`to` e `from`/`to` sem `RANGE` dão 400).
- Cobre: BR2.4, AC7.2.3, NFR1.1, NFR2.3, NFR3.6, NFR4.7.

### Etapa 5 — C4 com `market`, a chave do outro bloco omitida e o teste de contrato dos 3 casos

- [x] `pnl/web/PnlController.java` (`byMode`): `market` opcional (`PAPER` ou `REAL`, senão 400 `VALIDATION_FAILED`), `QueryParameters.of(parameters, {period, from, to, market})`; com `market`, `ModePnlView.single`; sem ele, os dois blocos como hoje; a descrição do 400 da U1 sai.
- [x] `pnl/web/ModePnlDtos.java` (`PnlByModeDto`): `paper` e `real` opcionais, com `@JsonInclude(NON_ABSENT)`; o esquema deixa de exigi-los; nunca `null`.
- [x] Testes depois da camada:
  - `pnl/web/PnlByModeMarketRouteIT.java`: os 3 casos (sem `market`, as duas chaves presentes e não nulas; `market=PAPER`, `real` ausente do JSON; `market=REAL`, `paper` ausente); `market=OTHER` dá 400; `tenantId` ou `accountId` na consulta dão 400 e nunca mudam a pessoa lida; sem token 401; termos desatualizados 403 `TERMS_REACCEPTANCE_REQUIRED`; 0 gravações em 3 leituras;
  - `pnl/web/PnlByModeMarketFailureIT.java`: falha injetada no bloco pedido, nos dois valores de `market`: 200 com `UNAVAILABLE`, `code` e `correlationId`, e `pnl_mode_block_unavailable_total` sobe 1;
  - `contract/GuestOpenApiContractTest.java`: o parâmetro `market` com o enum `[PAPER, REAL]`; `PnlByMode` só com `period`, `paper` e `real`, as duas últimas fora de `required`; nenhum total fora dos blocos.
- Cobre: BR1.6, BR3.1, BR3.2, BR3.3, BR8.1, AC4.2.6, NFR1.1, NFR3.1, NFR3.4, NFR3.6, NFR4.2, NFR4.7, NFR4.13, NFR7.4.

### Etapa 6 — `listBots` com `market`, `matchingCount` e `totalCount`

- [x] `bots/web/BotController.java` (`list`): `market` opcional na lista fechada de parâmetros; 400 fora de `PAPER` e `REAL`.
- [x] `bots/application/BotQueries.java` e `bots/infra/JdbcBotRepository.java`: `market` como mais um filtro da página que já existe (tenant, dono, estado, cursor); `totalCount` (bots da pessoa, sem estado nem modo) e `matchingCount` (os filtros da página) na mesma transação só de leitura. A ordem da página não muda (ver o conflito 1 em "Riscos e conflitos").
- [x] `bots/web/BotDtos.java` (`BotPageDto`): `matchingCount` e `totalCount`, aditivos.
- [x] Testes depois da camada:
  - `bots/web/BotListMarketIT.java`: `market=PAPER` e `REAL` só com bots do modo; os dois filtros juntos; contagens certas; `market=OTHER` dá 400; a mesma ordem da API em todos os filtros; 0 gravações;
  - `bots/web/PnlBotCountsTenantIT.java`: as contagens da pessoa A não mudam quando a pessoa B cria bots; `matchingCount ≤ totalCount` em todos os casos;
  - `bots/web/BotRoutesIT.java` continua verde.
- Cobre: BR1.2, BR1.3, BR1.5, BR6.3, BR8.1, AC2.3.2, AC2.3.3, NFR1.2, NFR3.1, NFR3.4, NFR3.5, NFR3.6, NFR4.4.

### Etapa 7 — `botLines` e o cursor de C5 (verde da Etapa 3)

- [x] `pnl/api/RealBotPnlQuery.java`: `RealPnlLinePage botLines(UUID tenantId, UUID userId, UUID botId, Optional<String> cursor, int limit)` (C10, aditivo); `pnl/api/RealPnlLinePage.java`, `pnl/api/RealPnlLineItem.java` e `pnl/api/RealPnlLineOrigin.java` (`BOT`, `LIQUIDATION`, `CONDITIONAL`, `SOLD_BY_OTHER_BOT`), novos.
- [x] `pnl/real/RealPnlEngine.java`: um coletor opcional (`RealPnlEngine.LineCollector`, interface nova) recebe cada linha do bot pedido com a chave de ordem de rede e a `idempotencyKey` da primeira linha do ledger do evento; nenhum cálculo novo.
- [x] `pnl/application/RealBotPnl.java`: `botLines` faz a mesma passada da pessoa de C4 com o coletor; as linhas do bot ficam em ordem crescente; a página é lida do fim para o começo e cortada pela âncora.
- [x] `pnl/application/BotLinesCursor.java` (novo): codifica, valida e acha a âncora.
- [x] Refatorar com os testes verdes da Etapa 3 (as duas ITs ficam verdes depois da Etapa 8).
- Cobre: BR4.2, BR4.3, BR4.6, C10, NFR1.3, NFR1.11, NFR2.3.

### Etapa 8 — Rota C5 `GET /api/v1/bots/{botId}/pnl/lines`, com o 404 antes de qualquer leitura

- [x] `pnl/web/PnlController.java` (`botLines`): validação sem banco (uuid, `limit` de 1 a 100 com padrão 50, cursor com o mesmo `botId`) → `directory.botOf(tenant, pessoa, botId)` filtrado por `REAL`; vazio → `refuseNotFound` (Etapa 10), antes de qualquer tarefa → 1 tarefa REAL no pool `pnl-mode-block` (800 ms) → 200; falha da passada (prazo, fila cheia, `TOO_MANY_ORDERS`, divergência) → 503 `SERVICE_UNAVAILABLE` com `correlationId` e log `WARN` `event=PNL_BOT_LINES_UNAVAILABLE cause=<rótulo>`.
- [x] `pnl/web/ModePnlDtos.java`: `RealBotPnlLinePageDto` e `RealBotPnlLineItemDto` (`currency` `SOL`, `items`, `nextCursor`; `at`, `side`, `mint`, `origin`, `tokenBaseUnits`, `amountLamports`, `feeLamports`, `realizedLamports`), com o 503 declarado (alinhamento A7).
- [x] `apps/api/src/main/resources/tenancy/route-registry.yml`: a rota com `scope: CLIENT` e o teste negativo `BotPnlLinesTenantIT`.
- [x] Testes depois da camada:
  - `pnl/web/BotPnlLinesRouteIT.java`: 400 para cursor malformado, de outro bot e de outra versão, e para `limit` 0 e 101; 404 nos 3 casos (outro tenant, uuid inexistente, bot PAPER da pessoa) com o mesmo corpo, igual byte a byte fora do `correlationId`; 503 com `correlationId` e nenhuma repetição; 401 e 403 de termos; o corpo sem o endereço semeado da carteira do bot; 0 gravações em 3 leituras;
  - `pnl/BotPnlLinesTenantIT.java`: duas pessoas de tenants diferentes; nada da outra pessoa nas linhas;
  - `tenancy/RouteRegistryTest.java` continua verde.
- Cobre: BR4.3, BR4.5, BR8.1, BR8.2, AC3.10.1, AC3.10.3, NFR1.3, NFR1.11, NFR3.1, NFR3.2, NFR3.4, NFR3.6, NFR3.7, NFR4.13.

### Etapa 9 — Rota C6 `GET /api/v1/pnl/compare`: lados em paralelo e 404 antes de 422

- [x] `pnl/web/PnlController.java` (`compare`): validação sem banco (os dois uuid e o período por `PnlWindows`) → `botOf(paperBotId)` filtrado por `PAPER` e `botOf(realBotId)` filtrado por `REAL`, sempre os dois, sem atalho → 404 `reason` `PAPER_BOT` ou `REAL_BOT` por `refuseNotFound` → 422 `VALIDATION_FAILED` com `reason` `TEMPLATE_MISMATCH` (sem valor de P&L) → `ModePnlView.statement(window, paperSide, realSide, compareSink)`, os dois lados em paralelo no pool, cada um com 800 ms; lado que falha vira `UNAVAILABLE`, resposta 200.
- [x] Lado PAPER por `PaperModeBlockReader.lineOf`; lado REAL por `RealBotPnlQuery.botPnl`.
- [x] `pnl/web/ModePnlDtos.java`: `PnlComparisonDto`, `PaperComparisonSideDto` (com `disclaimer`) e `RealComparisonSideDto` (sem aviso); `route-registry.yml` com a rota e o teste negativo `PnlCompareTenantIT`.
- [x] Logs: `INFO` `event=PNL_COMPARE_TEMPLATE_MISMATCH` e `WARN` `event=PNL_COMPARE_SIDE_UNAVAILABLE market=<enum> cause=<rótulo>`.
- [x] Testes depois da camada:
  - `pnl/web/PnlCompareRouteIT.java`: 200 com os dois lados, cada um na própria moeda, aviso só no PAPER; 400 (uuid, período); 422 só com dois bots da pessoa de `templateId` diferentes, corpo sem P&L; 401 e 403; o corpo sem o endereço semeado; 0 gravações em 3 leituras com 200;
  - `pnl/PnlCompareTenantIT.java`: o bot REAL de outro tenant **e** de `templateId` diferente dá 404 `REAL_BOT`, nunca 422; o mesmo com o PAPER (`PAPER_BOT`); bot do modo errado → 404; nenhum `templateId`, nome ou valor no corpo;
  - `pnl/web/PnlCompareSideFailureIT.java`: falha no PAPER, no REAL e nos dois: sempre 200, o lado em erro `UNAVAILABLE` com `correlationId`.
- Cobre: BR5.1, BR5.2, BR5.3, BR5.4, BR5.5, BR6.2, BR8.1, AC8.1.1, AC8.1.3, AC8.1.4, NFR1.4, NFR3.1, NFR3.3, NFR3.4, NFR3.6, NFR3.7, NFR4.1, NFR4.9, NFR4.13.

### Etapa 10 — `BotDirectory.ownedByAnotherTenant` e a regra ArchUnit

- [x] `pnl/api/BotDirectory.java`: `boolean ownedByAnotherTenant(UUID tenantId, UUID botId)`, com o javadoc da exceção F-2; `bots/application/BotDirectoryImpl.java` e `bots/infra/JdbcBotRepository.java`: `select exists (select 1 from bot where bot_id = :id and tenant_id <> :tenant)` pela chave primária, na transação só de leitura do pedido.
- [x] `pnl/web/PnlController.java`: método privado `refuseNotFound(pessoa, rota, lado, botId)`, o único chamador; roda a consulta nos dois 404 (uuid inexistente e outro tenant); log `INFO` `event=PNL_READ_NOT_FOUND route=<enum> side=<enum> reason=<TENANT_MISMATCH|NOT_FOUND>`; só um `true` chama `ReadRefusalAudit.tenantMismatch` (sem esperar); em C6, a consulta roda só para o lado do `reason`.
- [x] Testes depois da camada:
  - `bots/application/BotDirectoryOwnedByAnotherTenantIT.java`: `true` para o bot de outro tenant; `false` para uuid inexistente e para bot do próprio tenant; o método só devolve `boolean`;
  - `architecture/PnlViewsArchitectureTest.java` (novo): `ONLY_THE_404_PATH_ASKS_OTHER_TENANTS` (só `PnlController` depende de `BotDirectory.ownedByAnotherTenant`) e a amostra `architecture/othertenant/AskingOtherTenantsSample.java`, que faz a regra falhar;
  - `architecture/BotsArchitectureTest.java` continua verde (`bots -> pnl.api`, nunca `pnl -> bots.api`).
- Cobre: BR5.3, BR8.1, BR8.2, AC3.10.3, AC8.1.4, NFR3.1, NFR3.2, NFR3.3, NFR3.13, NFR3.17.

### Etapa 11 — `ReadRefusalAudit`: executor, filtro em memória e log `ERROR` limitado

- [x] `pnl/application/ReadRefusalAudit.java` (novo, `@ProcessRole(API)`, encerrado com o contexto): `THREADS = 1` (thread daemon `pnl-read-audit-1`), `QUEUE = 16` (`ArrayBlockingQueue`, `AbortPolicy`), `BUDGET = 200 ms` contados do envio pelo `Clock`, filtro `SUBMITTED_WINDOW = 60 min` e `SUBMITTED_KEYS = 10_000` (no teto, sai a chave mais antiga), `LOG_EVERY = 1 min`; MDC copiado; tarefas `tenantMismatch` e `templateMismatch` com `AuditEventCommand.person(...)` e `RateLimitedAuditPublisher.publishOncePerHour(comando, impressão, prazo)`; impressão digital SHA-256 de `pnl-read-audit|<pessoa>|<ação>`; `details` só com `route`, `side`, `botIds` e `fingerprint`. Uma recusa por fila cheia ou uma falha não marca o filtro.
- [x] `pnl/application/ReadAuditAction.java` (novo enum); `audit/api/AuditActions.java`: `PNL_COMPARE_TEMPLATE_MISMATCH`; `audit/api/RateLimitedAuditPublisher.java`: o javadoc cita a U3 (alinhamento A10).
- [x] Log `ERROR` `event=PNL_READ_AUDIT_FAILED action=<enum> category=<QUEUE_FULL|DEADLINE|CONNECTION_TIMEOUT|ERROR> exceptionClass=<nome simples>`, no máximo 1 por minuto por pessoa e ação; nunca a mensagem da exceção.
- [x] Testes depois da camada:
  - `pnl/application/ReadRefusalAuditTest.java`: as constantes; um 404 de uuid inexistente não chama o executor; com o publicador bloqueado e pessoas diferentes, 1 em execução e 16 na fila aceitas, a 18ª recusada, conta 1 e gera 1 log `ERROR`; 10 recusas da mesma pessoa e ação em 1 minuto geram 1 log e 10 contagens;
  - `pnl/application/ReadRefusalAuditFilterTest.java`: a mesma pessoa e ação 2 vezes em 60 minutos dão 1 tarefa; outra ação ou pessoa, 1 a mais; com 10.000 chaves na janela, a 10.001ª entra, a mais antiga sai e a recusa nova segue;
  - `pnl/application/ReadRefusalAuditNonBlockingTest.java`: o controlador de C5 e o de C6 com um publicador duplo que bloqueia 1 s respondem o 404 de outro tenant e o 422 em menos de 100 ms;
  - `pnl/ReadRefusalAuditLimitIT.java`: 3 respostas 404 de outro tenant da mesma pessoa na mesma hora (C5, lado PAPER e lado REAL de C6) gravam 1 evento `TENANT_MISMATCH` com a ação, a rota e o `correlationId` da primeira; 1 resposta 422 grava o próprio `PNL_COMPARE_TEMPLATE_MISMATCH`; 0 eventos nos 404 que não são de outro tenant; 10 recusas seguidas, 1 evento; outra pessoa, mais 1; com a auditoria falhando, as 10 respostas iguais às de sem falha; 20 pessoas recusando ao mesmo tempo não fazem nenhuma gravação do `ReconciliationAuditGate` desistir pelo prazo; o evento sem nome, `templateId`, valor ou endereço;
  - `architecture/AuditArchitectureTest.java` continua verde (`PNL_USES_ONLY_THE_AUDIT_API`, `MISMATCH_POOL_ONLY_IN_ITS_WRITER`).
- Cobre: BR5.2, NFR1.10, NFR1.15, NFR3.13, NFR3.14, NFR3.16, NFR4.13.

### Etapa 12 — Os três contadores, os logs e o `correlationId`

- [x] `pnl/modeview/PnlCompareMetrics.java` (novo, `@ProcessRole(API)`): `pnl.compare.template_mismatches` (1 série) e `pnl.compare.side_unavailable{market,cause}` (10 séries, o enum `PnlModeMetrics.Cause`), registradas com 0 no construtor; `sideUnavailable` é o `sink` de C6.
- [x] `pnl/application/PnlReadAuditMetrics.java` (novo): `pnl.read_audit.failures{action}` (2 séries com 0).
- [x] `PnlController` sobe `template_mismatches` em todo 422, depois da posse.
- [x] Testes depois da camada:
  - `pnl/modeview/PnlCompareMetricsTest.java` e `pnl/application/PnlReadAuditMetricsTest.java` (`SimpleMeterRegistry`): as 13 séries com 0; 2 recusas 422 dão 2, e 1 resposta 404 mais 1 resposta 400 não mudam; tempo esgotado no lado REAL dá `{market="REAL",cause="TIMEOUT"} = 1`, as outras 9 em 0 e `pnl.mode_block.unavailable` sem mudança; gravação falhando dá `{action="TENANT_MISMATCH"} = 1`;
  - `pnl/PnlViewsPrometheusIT.java`: os três nomes exportados em `/actuator/prometheus` (porta 8081), com 0 antes do primeiro evento;
  - `pnl/PnlViewsLogCaptureTest.java`: os 5 eventos de NFR3.10, 1 registro por evento com o `correlationId` da resposta; 0 registros com endereço, `templateId` de outra pessoa, token, `Authorization`, nonce ou assinatura;
  - `pnl/PnlViewsCorrelationIT.java`: o mesmo `correlationId` no corpo RFC 7807, no lado `UNAVAILABLE` de C6, no bloco `UNAVAILABLE` de C4, no evento de auditoria e no log.
- Cobre: BR3.3, BR5.5, NFR3.7, NFR3.9, NFR3.10, NFR3.11, NFR3.12, NFR3.15, NFR3.16, NFR8.4.

### Etapa 13 — Paridade de tempo dos 404, leitura sem trava e medida da latência

- [x] `pnl/BotNotFoundTimingParityIT.java` (método de NFR1.5): a pessoa A pede, intercalando, o bot de B e um uuid novo a cada pedido; 10 de aquecimento e 100 medidos por caso, em C5 e em C6 (lado PAPER e lado REAL); p95 pelo posto mais próximo; falha se `|p95(outro tenant) − p95(inexistente)| > max(5 ms, 10% do maior p95)`; os dois p95 e a diferença no log.
- [x] `pnl/PnlViewsReadOnlyIT.java`: 0 linhas a mais nas tabelas tocadas depois de 3 leituras seguidas de C4 com `market` e com `RANGE`, `listBots`, C5 e C6; só `AccessShareLock`, nenhum `FOR UPDATE` nem trava consultiva.
- [x] `pnl/PnlReadsDoNotBlockCycleIT.java`: C5 e C6 em laço, em 4 threads, durante um ciclo de bot; o ciclo termina dentro da trava de 2 s. Se o ciclo não puder rodar no contexto da API do IT (como achou a U2 no `RealPnlReadOnlyIT`), a barreira, que usa a mesma trava da carteira, faz o papel dele, e o desvio vai para o `code-summary.md`.
- [x] `pnl/web/PnlViewsLatencyIT.java` (padrão de `AbstractU9MeasurementIT` e do `DashboardOpenLatencyIT`, semente da U1): os 11 casos (C4 `market=PAPER` e `REAL` com `ALL` e `RANGE` de 30 dias; `listBots` com `PAPER`, `REAL` e sem `market`; C5 1ª página e seguinte; C6 `ALL` e `RANGE` de 30 dias); o p95 de cada caso no log ao lado da meta de 1.000 ms; falha só se um pedido medido não responder 200.
- Cobre: NFR1.1, NFR1.2, NFR1.3, NFR1.4, NFR1.5, NFR1.9, NFR1.10, NFR1.16, NFR3.17, NFR4.13.

### Etapa 14 — Fronteiras: `NEVER_SUM_MODES`, ponto flutuante e modos

- [x] `architecture/NeverSumModesArchitectureTest.java`: os tipos novos na lista fechada REAL (`RealPnlLinePage`, `RealPnlLineItem`, `RealPnlLineOrigin`, `RealBotPnlLinePageDto`, `RealBotPnlLineItemDto`, `RealComparisonSideDto`), PAPER (`PaperComparisonSideDto`) e neutra (`PnlComparisonDto`, montador ao lado de `PnlByModeDto`); um tipo de `ModePnlDtos` fora das três listas falha.
- [x] `architecture/ArchitectureTest.java` (sem ponto flutuante) cobre os pacotes novos; `architecture/ModeBoundaryArchitectureTest.java` continua verde.
- Cobre: BR6.1, BR6.2, NFR2.1, NFR4.1, NFR4.5.

### Etapa 15 — Contrato OpenAPI regenerado, com o 503 de C5, e tipos do `apps/web`

- [x] Regenerar `apps/api/openapi/openapi-guest.yaml` (`GuestOpenApiContractTest` com `-Dopenapi.write=true`, depois sem): C4 (`market`, `paper` e `real` opcionais), `listBots` (`market`, `matchingCount`, `totalCount`), C5 (200, 400, 404 e o 503 `SERVICE_UNAVAILABLE`, alinhamento A7) e C6 (200, 400, 404 com `reason`, 422 `TEMPLATE_MISMATCH`).
- [x] `contract/GuestOpenApiContractTest.java`: os casos de C5 e C6; nenhum campo de endereço ou assinatura em C4, C5 e C6; nenhum total geral; `BotPage` sem total de dinheiro.
- [x] Regenerar `apps/web/src/lib/api/schema.d.ts` com `gen:api`, nunca à mão, e conferir com `check:api`; o `check:api` do `apps/admin` não muda (o `apps/admin` não consome C4).
- Cobre: BR3.1, BR6.2, NFR3.7, NFR4.1, NFR4.2, NFR7.4, NFR7.8.

## Fase C — `apps/web`

### Etapa 16 — Cliente: `AbortController`, `RequestCancelled` e `retry: false`; funções da U3

- [x] `apps/web/src/lib/api/client.ts`: `RequestOptions.signal?: AbortSignal`, ligado ao `AbortController` do tempo limite de 10 s (ouvinte `abort` com `once`, removido no `finally`); sinal externo abortado lança `RequestCancelled`, sem repetir e sem esperar o recuo; `RequestOptions.retry?: boolean` (padrão `true`); com `retry: false`, 503 e tempo esgotado viram erro na primeira tentativa.
- [x] `apps/web/src/lib/api/guest-api.ts`: `getPnlByMode({ market?, period }, { signal })`, `listBots({ state?, market?, cursor?, limit? }, { signal })`, `listBotPnlLines(botId, { cursor?, limit }, { signal })` com `retry: false`, `comparePnl({ paperBotId, realBotId, period }, { signal })`, `getPnl(period, { signal })` com `retry: false` (alinhamento A8), `getBot(botId, { signal })`. Nenhum id de tenant ou de pessoa.
- [x] `apps/web/src/features/pnl/by-mode.ts`: `paperLoad` e `realLoad` aceitam a chave ausente (pedido com o `market` do outro modo) sem virar erro.
- [x] Testes depois da camada:
  - `src/lib/api/client.test.ts` (alterado): abortar não repete e lança `RequestCancelled`; com o `fetch` simulado respondendo 503, `listBotPnlLines` e `getPnl` fazem exatamente 1 `fetch` e lançam o erro com o `correlationId`; `getPnlByMode` continua repetindo como hoje; o ouvinte do sinal é removido;
  - `src/lib/api/pnl-routes.test.ts` (alterado): as URLs de C4 (com e sem `market`, `ALL` e `RANGE` com `from` e `to` em `Z`), `listBots` com `market`, C5 com `limit=50` e `cursor`, C6; nenhuma leva `tenantId` ou `accountId`;
  - `src/features/pnl/by-mode.test.ts` (novo): chave ausente, `UNAVAILABLE`, falha da rota.
- Cobre: BR1.2, BR2.4, BR3.1, BR8.1, NFR1.8, NFR3.1, NFR4.10, NFR4.11.

### Etapa 17 — `BotModeFilter` e a lista `/bots`

- [x] `apps/web/src/features/bots/BotModeFilter.tsx` (novo): `fieldset` com `legend` "Modo" e três rádios nativos (`name="bot-mode-filter"`); foco inicial no rádio marcado; setas trocam e aplicam; "Mostrando N de M bots." em `aria-live="polite"` com `PAPER` ou `REAL`; 44 px abaixo de 768 px; `data-testid` nos rádios.
- [x] `apps/web/src/features/bots/BotList.tsx`: o filtro lido de `?mode=` (inválido vale Todos e sai do endereço, sem pedido); `router.replace` na troca; vagas `list` e `byMode` com `AbortController`; `listBots` com `market` e C4 com o mesmo `market` (sem ele em Todos); os textos de vazio pelo filtro e "Ver todos os bots", com foco no rádio Todos; sem aviso do simulado com Real; o P&L da linha procurado pelo `botId`; "Tentar de novo" relê só o bloco em erro; a tela não ordena nem conta.
- [x] `apps/web/src/features/bots/mode-messages.ts`: `list.filter.*`, `list.shown`, `list.emptyPaper`, `list.emptyReal`.
- [x] Testes depois da camada:
  - `src/features/bots/BotModeFilter.test.tsx` (novo): as três opções e o padrão; foco inicial; troca por seta sem mover o foco; a linha "Mostrando…" só com filtro;
  - `src/features/bots/BotList.test.tsx` (alterado): 1 `listBots` e no máximo 1 C4 por troca; `?mode` inválido vale Todos sem pedido; os dois textos de vazio diferentes; "Ver todos os bots"; nenhum aviso com Real; resposta antiga descartada; nenhum bot de outro modo com o filtro marcado; nenhum total.
- Cobre: BR1.1, BR1.2, BR1.3, BR1.4, BR1.5, BR1.6, BR1.7, BR3.2, BR6.3, AC2.3.1, AC2.3.2, AC2.3.3, AC2.3.4, AC2.3.5, AC4.2.6, NFR1.8, NFR3.6, NFR4.4, NFR4.11, NFR5.1.

### Etapa 18 — `PeriodSelector`, reusando `period.ts`

- [x] `apps/web/src/features/pnl/PeriodSelector.tsx` (novo): `fieldset` "Período" com cinco rádios nativos ("Desde o início", "Hoje", "Últimos 7 dias", "Últimos 30 dias", "Intervalo") e dois `input type="date"` com `label`; o par UTC calculado por `toUtcPeriod`, `todayIn` e `addDays` de `period.ts` (que não muda) no momento da aplicação, fixo até a próxima; o intervalo com "Aplicar"; as quatro mensagens de BR2.3 (o início depois de hoje e os 366 dias em UTC ficam aqui, fora de `period.ts`), `aria-invalid`, `role="alert"`, foco no primeiro campo com erro e rascunho mantido; o texto "Mostrando: …" em `aria-live="polite"`; `now` e `timeZone` injetáveis.
- [x] `apps/web/src/features/pnl/mode-messages.ts`: `pnl.period.*`.
- [x] Testes depois da camada:
  - `src/features/pnl/period.test.ts` (alterado, só casos novos): "Hoje" em America/Sao_Paulo às 2026-11-23T02:30Z dá `from` 2026-11-22T03:00Z e `to` 2026-11-23T03:00Z; intervalo inclusivo; 366 dias em America/New_York atravessando o fim do horário de verão recusados e 365 aceitos;
  - `src/features/pnl/PeriodSelector.test.tsx` (novo): as cinco opções e o padrão; Hoje, 7 e 30 dias com relógio fixo; o par fixado igual depois da meia-noite local; o intervalo futuro recusado no campo De; as quatro mensagens; nenhum `onChange` com erro; foco no campo com erro.
- Cobre: BR2.1, BR2.2, BR2.3, BR2.5, BR2.7, AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4, AC7.2.2, NFR2.4, NFR5.1, NFR7.5.

### Etapa 19 — Reducer `pnl-screen-state.ts` e a tela `/pnl`

- [x] `apps/web/src/features/pnl/pnl-screen-state.ts` (novo, reducer puro, decisão F-3): os dois `BlockLoad`, o período aplicado com o par UTC fixo, a comparação com os dois lados e a tabela por token; vagas `byMode`, `retry:PAPER`, `retry:REAL`, `compare` e `tokens`, cada `*_LOADED` com `requestId` e ignorado se não for o da vaga; ações `PERIOD_APPLIED`, `BLOCK_RETRY_REQUESTED {market}`, `COMPARE_SIDE_RETRIED {side}` e as de carga.
- [x] `apps/web/src/features/pnl/PnlScreen.tsx`: `useReducer`; `AbortController` por vaga num `useRef`, todos abortados ao sair; `PeriodSelector` no lugar do formulário antigo, com "Desde o início" por padrão e o período no endereço (`?periodo=hoje|7d|30d|intervalo&de=&ate=`, inválido abre em "Desde o início"); C4 sem `market` na carga e na troca; "Tentar de novo" com `market` e foco no título do bloco relido; a tabela "Ver por token" dentro do bloco Simulado lida de `GET /api/v1/pnl` com o mesmo par só com período concreto, e a nota "Escolha um período para ver o resultado por token" em "Desde o início" (BR2.9), com carregando, erro com código e "Tentar de novo" próprios; `ComparisonView` (Etapa 22); `ReconciliationPanel` e `LedgerList` embaixo como hoje; `RequestCancelled` ignorado.
- [x] Testes depois da camada:
  - `src/features/pnl/pnl-screen-state.test.ts` (novo): resposta antiga descartada pelo `requestId`; "Tentar de novo" põe só um bloco em carregando; o lado sem erro da comparação não muda;
  - `src/features/pnl/PnlScreenViews.test.tsx` (novo): 1 C4 sem `market` por troca de período, mais 1 C6 com a comparação aberta e mais 1 `GET /api/v1/pnl` só com período concreto; em "Desde o início", 0 pedidos a `GET /api/v1/pnl` e a nota; "Tentar de novo" com 1 pedido com o `market` e o outro bloco com o mesmo texto; foco no título relido; endereço com intervalo futuro aplica "Desde o início" sem pedido com `RANGE`; `?comparar=` trocado depois da meia-noite lê C6 com o mesmo `from` e `to` dos blocos; resposta antiga descartada; nenhum "Total geral";
  - `src/features/pnl/PnlScreen.test.tsx` (U1): ver o conflito 2 em "Riscos e conflitos".
- Cobre: BR2.1, BR2.4, BR2.6, BR2.7, BR2.9, BR3.2, BR3.3, BR3.4, BR6.2, AC4.4.4, AC7.1.1, AC7.1.5, AC7.2.3, NFR1.8, NFR4.8, NFR4.11, NFR5.1, NFR7.5.

### Etapa 20 — `PnlModeBlock` `compact` e `bot`, `ProratedLines` e a linha "Sem bot"

- [x] `apps/web/src/features/pnl/PnlModeBlock.tsx` (só acréscimos, C17): variante `compact` (total, realizado, taxas, aviso no PAPER, link "Ver o resultado…" para `/pnl`); variante `bot` (total, realizado, não realizado e taxas de uma linha, rótulo "Total do bot, em …"); no REAL `full`, o `ProratedLines` por bot e a linha "Sem bot" com `pnl.noBotReason.*` só quando algum valor é diferente de `"0"` (comparação de texto); props opcionais `realUnavailable` e `headingLevel`; região do total com `aria-live="polite"` e `aria-atomic="true"`.
- [x] `apps/web/src/features/pnl/ProratedLines.tsx` (novo): resumo "inclui <valor> <origem>" por item de `summary`; botão "Ver N linhas rateadas de <bot>" com `aria-expanded` e `aria-controls`; até 10 parcelas (data no fuso, token, origem em texto, recebido, taxa, realizado, em SOL por `money-format.ts`); "Ver todas no detalhe do bot" com `count > 10`; cartões com rótulos abaixo de 768 px e tabela com `caption` num `TableScroll` a partir de 768 px.
- [x] `apps/web/src/features/pnl/mode-messages.ts`: `pnl.origins.*`, `pnl.noBot`, `pnl.noBotReason.*`.
- [x] `apps/web/src/features/pnl/fixtures.ts`: bloco REAL com 12 parcelas e "Sem bot", bloco único (chave ausente), página de C5, resposta de C6, sempre sintéticos.
- [x] Testes depois da camada:
  - `src/features/pnl/PnlModeBlockVariants.test.tsx` (novo): `compact` e `bot` com os rótulos acessíveis; "Sem bot" com os três motivos e ausente quando zerada; cada valor igual ao texto da fixture formatado; PAPER em US$ e REAL em SOL; nenhum anúncio na carga inicial; `PnlModeBlock.test.tsx` (U1 e U2) continua verde sem edição;
  - `src/features/pnl/ProratedLines.test.tsx` (novo): nada com `count = 0`; os quatro textos de origem e o código desconhecido; mais de 10 com o link; `aria-expanded`; cada valor igual ao da fixture; nenhuma soma.
- Cobre: BR4.1, BR4.7, BR4.8, BR6.1, BR9.1, BR9.2, BR9.3, BR10.1, BR11.1, BR11.2, AC3.10.2, AC4.4.1, AC4.4.2, AC4.4.3, NFR2.2, NFR4.3, NFR4.5, NFR5.2, NFR5.3, NFR5.4, NFR6.1.

### Etapa 21 — `BotPnlLines` e o detalhe `/bots/{id}`

- [x] `apps/web/src/features/pnl/BotPnlLines.tsx` (novo): C5 com `limit=50`; "Carregar mais" com o cursor, botão desabilitado ao carregar, foco na primeira linha nova (`tabIndex=-1`); fases `loading`, `ready`, `empty`, `error`, `loadingMore`, `moreError` (linhas mantidas e "Tentar de novo" com o mesmo cursor), `cursorStale` (400 de `cursor`: "A lista mudou desde a última página." e "Recarregar do início", que pede a 1ª página sem cursor e troca os itens, alinhamento A9) e `notFound` (`onNotFound`); as seis colunas, compra com "—" e `aria-label` "sem resultado"; tabela com `caption` "Linhas do P&L de <bot>" e `th scope="col"` num `TableScroll` a partir de 768 px, cartões com rótulos abaixo.
- [x] `apps/web/src/features/bots/BotDetail.tsx`: cabeçalho com o selo e "Modo: … · não pode ser trocado" (sai "MODO PAPER (simulado)"); `PnlModeBlock` `bot` a partir da linha do bot no bloco de C4 com o `market` do bot e `period=ALL` ("—" sem linha); REAL com `BotPnlLines` e sem o aviso; PAPER com o aviso e as seções que já existem, sem pedido a C5; 404 de `GET /api/v1/bots/{id}` ou de C5 → só "Bot não encontrado." em `role="alert"` e "Voltar para Meus bots".
- [x] Testes depois da camada:
  - `src/features/pnl/BotPnlLines.test.tsx` (novo): ordem recebida; cursor enviado; foco na primeira linha nova; falha da 2ª página com as 50 linhas mantidas e 1 pedido com o mesmo cursor; `cursorStale` pede sem cursor e nunca repete o recusado; data dd/mm HH:MM com fuso fixo; compra com "—"; os quatro estados distintos; o total nunca é a soma das linhas;
  - `src/features/bots/BotDetail.test.tsx` (alterado): 1 `getBot`, 1 C4 com o `market` do bot e, no REAL, 1 C5; 0 pedidos a C5 no PAPER; aviso só no PAPER; 404 sem nome, modo, P&L nem código; h1, h2 "P&L do bot" e "Linhas do P&L".
- Cobre: BR2.5, BR2.8, BR4.1, BR4.2, BR4.3, BR4.4, BR4.5, BR4.6, BR8.2, BR9.3, BR10.1, BR10.2, AC3.10.1, AC3.10.2, AC3.10.3, AC3.10.4, AC4.2.4, AC4.2.6, AC4.5.1, AC4.5.3, AC7.2.2, NFR1.9, NFR2.2, NFR3.2, NFR4.10, NFR5.1, NFR5.4, NFR6.1, NFR6.2.

### Etapa 22 — `ComparisonView`

- [x] `apps/web/src/features/pnl/ComparisonView.tsx` (novo): botão "Comparar um bot simulado com um bot real" com `aria-expanded` e `aria-controls`; região com h2 "Comparação"; selects "Bot simulado" (PAPER) e "Bot real" (desabilitado até haver bot simulado; só REAL de mesmo `templateId`; o único par já escolhido); estado sem par; `?comparar=<paper>,<real>` (inválido abre vazio sem pedido; fechar tira o parâmetro e devolve o foco ao botão); C6 com o período em vigor; lados `PnlModeBlock` `bot` com h3 "<nome>, simulado, em dólares" e "<nome>, real, em SOL", "Criado em dd/mm", aviso só no PAPER e a frase fixa sobre moedas; 422 explicado com o bot real limpo e o foco nele; 404 com `reason` → "Bot não encontrado." no lado, select limpo e com foco; "Tentar de novo" de um lado atualiza só aquele lado; empilhado abaixo de 768 px; com mais de 50 bots, lê as páginas seguintes de `listBots` antes de montar as opções.
- [x] `apps/web/src/features/pnl/mode-messages.ts`: `compare.*`, com `compare.templateMismatch`.
- [x] Testes depois da camada: `src/features/pnl/ComparisonView.test.tsx` (novo): a lista filtrada pelo `templateId`; o estado sem par; ida e volta do endereço; foco ao abrir, ao fechar, depois de 404 e de 422; moedas e aviso só no PAPER; um lado `UNAVAILABLE` com o outro intacto e 1 C6 no "Tentar de novo"; nenhuma diferença, ranking, destaque ou "Total geral"; resposta antiga descartada.
- Cobre: BR2.6, BR5.1, BR5.2, BR5.3, BR5.4, BR5.5, BR5.6, BR6.1, BR6.2, BR6.3, BR9.2, BR9.3, BR11.2, AC4.2.5, AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4, NFR1.8, NFR4.3, NFR4.4, NFR4.9, NFR4.11, NFR5.1, NFR5.3, NFR5.4.

### Etapa 23 — `DashboardModeCards`, janela de 30 s e eventos REAL no painel

- [x] `apps/web/src/features/dashboard/concurrency.ts`: `windowedRefresh(run, { windowMs: 30_000, setTimer, clearTimer })` ao lado do `singleFlight`, com `trigger` e `dispose`.
- [x] `apps/web/src/features/dashboard/dashboard-reducer.ts`: fatia `modeCards` (`PAPER` e `REAL`, cada um com `load`, `requestId`, `lastAnnouncedAt` e `realUnavailable`); ações `MODE_CARDS_REQUESTED`, `MODE_CARD_REQUESTED`, `MODE_CARD_LOADED` e `MODE_CARD_FAILED`.
- [x] `apps/web/src/features/dashboard/DashboardModeCards.tsx` (novo): `section` com `aria-labelledby` "Resultados por modo · desde o início"; dois `PnlModeBlock` `compact` com h3 "Resultado simulado" e "Resultado real"; carga com C4 sem `market` e `period=ALL` e C2 em paralelo; um `windowedRefresh` por cartão (vaga `card:<market>`, C4 com o `market`, sem voltar a carregando); "Tentar de novo" na hora, com foco no h3 do cartão; o cartão real vazio com o link conforme C2; anúncio do total no máximo 1 vez a cada 30 s e nunca na carga inicial; `dispose` ao desmontar.
- [x] `apps/web/src/features/dashboard/DashboardProvider.tsx`: assina também `real.order.state` e `real.conditional.state` (o canal não muda); `pnl.updated` dispara o cartão PAPER; `real.order.state` com `CONFIRMED` ou `FAILED` e `real.conditional.state` disparam o REAL; os demais não releem. A leitura do `PnlCard` sai do `dashboard-loader.ts`; a conferência continua.
- [x] `apps/web/src/features/dashboard/DashboardScreen.tsx`: `DashboardModeCards` no lugar do `PnlCard` e do `DashboardRealPnl`; o foco inicial no título do primeiro cartão; o `BotsCard` continua. `PnlCard.tsx` sai se nada mais o usar; `DashboardRealPnl` continua em `features/real` se outra tela o usar.
- [x] `apps/web/src/features/dashboard/messages.ts`: `dashboard.*`.
- [x] Testes depois da camada:
  - `src/features/dashboard/concurrency.test.ts` (novo): 5 gatilhos em 30 s dão 2 execuções; o gatilho depois da pendente espera a janela seguinte; `dispose` limpa;
  - `src/features/dashboard/DashboardModeCards.test.tsx` (novo, relógio injetável, um bloco por cartão): `pnl.updated` relê só o simulado; `real.order.state` `CONFIRMED` e `real.conditional.state` releem só o real; `SENT` e `real.signing.requested` não releem; dois eventos em 30 s, 1 imediato e 1 no fim; os cinco estados por cartão; aviso só no simulado com valor; os quatro casos do real vazio; nenhum "Total geral";
  - `src/features/dashboard/DashboardScreen.test.tsx` e `dashboard-state.test.ts` (alterados): os casos que esperavam o `PnlCard` passam a esperar os dois cartões; os da conferência e dos bots ficam iguais.
- Cobre: BR2.8, BR3.2, BR3.4, BR7.1, BR7.2, BR7.3, BR7.4, BR7.5, BR9.2, AC4.1.2, AC4.2.2, AC4.3.1, AC4.3.2, AC4.3.3, AC4.3.4, AC4.4.3, AC4.5.3, NFR1.7, NFR1.14, NFR4.8, NFR4.12, NFR5.3, NFR6.2.

### Etapa 24 — Verificação do `apps/web`

- [x] `pnpm --dir apps/web run lint`, `format:check`, `typecheck`, `check:api` e `exec jest --coverage` com o piso de 80% de linhas; o lint de dinheiro de `features/pnl` e de `features/dashboard` passa (`lint-rules.test.ts`); `bigint` só em `features/real/money.ts`; `eslint.config.mjs` sem mudança; 0 variáveis `NEXT_PUBLIC_*` novas.
- Cobre: BR11.1, NFR2.1, NFR3.7, NFR4.5, NFR7.3.

## Fase D — ponta a ponta

### Etapa 25 — Os 10 casos novos do spec `zz-paper-real-unico`

- [x] `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts`: o spec continua o último da suíte e usa os dois bots e as duas compras que já faz; os casos novos só leem telas e rotas:
  1. painel com "Resultado simulado" em US$ e o aviso, "Resultado real" em SOL sem o aviso, nenhum "Total geral" e "Ver o resultado real" para `/pnl`;
  2. `/bots`: foco inicial no filtro; "Simulado" só com o bot PAPER; "Real" só com o bot REAL e sem o aviso; `?mode=` no endereço;
  3. detalhe do bot REAL com a coluna "Origem", a compra com "Bot" e "—" e a data dd/mm HH:MM;
  4. detalhe do bot PAPER com o aviso;
  5. detalhe de um id inexistente com "Bot não encontrado." e nenhum dado;
  6. "Últimos 7 dias" em `/pnl`: `?periodo=7d`, o pedido de C4 com `period=RANGE` e `from`/`to` terminando em `Z`, dois blocos separados e nenhum "Total geral";
  7. comparação dos dois bots da mesma estratégia, cada um na sua moeda, aviso só no lado simulado, sem "Total geral" nem diferença;
  8. 360 px: `scrollWidth` igual a `clientWidth` em `/`, `/bots?mode=REAL`, `/bots/{id}` dos dois bots e `/pnl` com a comparação aberta;
  9. 360 px com dados montados: `page.route` intercepta C4 (um bot REAL com 12 parcelas e uma linha "Sem bot") e C5 (50 linhas com `nextCursor` e a página seguinte); abre "Ver 12 linhas rateadas" em `/pnl` e clica em "Carregar mais" em `/bots/{id}`; mede a largura antes e depois e confere os cartões;
  10. "Desde o início" com a nota no lugar da tabela por token; "Hoje" com a tabela e `?periodo=hoje`.
- [x] `apps/web/e2e/zz-paper-real-unico/mounted-pnl-fixtures.ts` (novo): as fixtures sintéticas do caso 9, sem carteira nem mint real.
- [x] O spec já está na lista do `scripts/verify.sh`: nenhuma linha nova é pedida ao responsável; os casos de 404 e 422 de C6 ficam nos testes de rota do backend.
- Cobre: BR6.2, BR10.1, BR12.1, AC2.3.1, AC2.3.2, AC2.3.3, AC2.3.5, AC3.10.1, AC3.10.2, AC3.10.3, AC3.10.4, AC4.1.2, AC4.2.2, AC4.2.4, AC4.3.1, AC4.3.4, AC4.5.1, AC7.1.2, AC7.1.5, AC7.2.3, AC8.1.1, AC8.1.2, AC8.1.3, NFR4.1, NFR4.3, NFR6.1, NFR7.6.

## Fase E — carga e alinhamentos

### Etapa 26 — Cenário `pnl-by-mode` ampliado: 9 leituras, 7 portões novos e a regra de leitura do relatório

- [x] `scripts/load/pnl-by-mode.mjs`: `PNL_LIMITS.readP95Seconds = 1.0`; `CYCLE` com as 9 leituras rotuladas (`byModeAll`, `realPnl`, `byModeRange30`, `byModeRange366`, `linesFirst`, `linesNext`, `compareAll`, `compareRange30`, `compareRange366`) e `NEW_READS` com as 7 novas; `requestOf(label, person, now)` (`RANGE` de 30 e de 366 dias terminando agora, em UTC, em segundos inteiros); tempo de cada leitura medido no cliente, só depois do aquecimento; `linesNext` encadeado no `nextCursor` da última `linesFirst` da mesma pessoa; `nearestRankP95`; falhas `p95 <rótulo>`, `no read of <rótulo>` e `missing nextCursor`; respostas diferentes de 200 nas 9 leituras; **grupos de portões U2 e U3, cada um com o próprio resultado** (regra de leitura: grupo U2 vermelho deixa U2 e U3 pendentes; só U3 vermelho deixa só a U3 pendente; respostas diferentes de 200 e `UNAVAILABLE` atribuídos ao grupo da leitura pelo rótulo); os portões da U2 sem mudança de limite.
- [x] `scripts/load/load-test.mjs` (`runPnlByModeScenario`): antes do aquecimento, `GET /api/v1/bots?market=REAL` e `?market=PAPER` por pessoa escolhem o bot de C5 e um par de mesmo `templateId` para C6 (falha na hora se faltar); o relatório ganha `reads` (contagem, p50, p95 e p99 por rótulo), os lados `UNAVAILABLE` de C6 por causa (`pnl_compare_side_unavailable_total` antes e depois) e os dois grupos; grava `load-test-report.json`, o `docs/perf/u2-real-bot-pnl-<data>.md` de hoje e o novo `docs/perf/u3-pnl-views-<data>.md` (`pnlViewsMarkdown`).
- [x] `LoadtestPnlSeed.java` e `scripts/load-test.sh` não mudam (todos os bots da semente usam `MOMENTUM_VOLUME`; o `load-test.mjs` confere os pares).
- [x] Testes depois da camada: `scripts/load/load-test-pnl-by-mode.test.mjs` (alterado, `io` e relógio falsos): o ciclo de 9 leituras; o encadeamento do cursor; o p95 pelo posto mais próximo; cada portão novo reprovando sozinho; os portões da U2 sem mudança; os quatro casos da regra de leitura (os dois verdes, só U3 vermelho, só U2 vermelho); `pnlViewsMarkdown`.
- [x] Rodar `node --test "scripts/load/*.test.mjs"` à mão e registrar a saída no `code-summary.md` (até o responsável aplicar o passo do `ci.yml`).
- [x] **Entregáveis do responsável, no `code-summary.md` da U3** (esta geração não edita `.github/workflows/` nem `scripts/verify.sh`):
  - o texto inteiro do `.github/workflows/load-test.yml`, a partir do texto da U2, com a frase nova no parágrafo do `pnl-by-mode` ("Since 261121-paper-real-unico U3 (NFR1.6): it also reads C4 with RANGE of 30 and 366 days, the first and the next page of C5 and C6 with ALL, RANGE of 30 days and RANGE of 366 days, and fails when the p95 of any of these 7 reads, timed by the client, exceeds 1 s."), a frase de que ele substitui o texto da U2 e o comando `gh workflow run load-test -f scenario=pnl-by-mode`; as opções `real` e `pnl-by-mode`, os SHAs das ações e `timeout-minutes` ficam iguais;
  - o passo do job `frontend` do `ci.yml` (D-INF4), depois de "Jest with the 80% line floor": `name: Gates of the load scenarios (scripts/load)` e `run: node --test "scripts/load/*.test.mjs"`;
  - a regra de leitura do relatório (grupos U2 e U3).
- Cobre: NFR1.1, NFR1.3, NFR1.4, NFR1.6, NFR1.16.

### Etapa 27 — Alinhamentos de texto

- [x] A1 (F-1): `nfr-design/nfr-design-questions.md` (nota na Q2) e `nfr-requirements/security-requirements.md` NFR3.17: a verificação `ownedByAnotherTenant` roda na thread do pedido nos dois 404; só o envio ao executor fica depois; a regra de paridade não muda.
- [x] A2 (F-2): `inception/contract-design/contract-summary.md` C8: `boolean ownedByAnotherTenant(UUID tenantId, UUID botId)`, a única consulta fora do tenant, só booleana, só pelo caminho dos 404 (`ONLY_THE_404_PATH_ASKS_OTHER_TENANTS`). No mesmo arquivo: C4 com o parâmetro `market` e `paper`/`real` fora de `required` (BR3.1, a regra 6 de posse ganha a nota), C5 com o 503, C10 com `botLines` já implementado, e PQ-6 e PQ-8 movidas para fechadas (Q1 e Q4 do Functional Design da U3).
- [x] A3: a exceção F-2 no `team.md` (Code Style, "Tenant no código") e no NFR3.1 **não** é escrita aqui; fica como pendência para o ritual de aprendizado, registrada no `code-summary.md`.
- [x] A4 (F-3): `nfr-design/nfr-design-questions.md` Q3: nota da premissa errada (o `PnlScreen` usava `useState`) e da reescrita aceita.
- [x] A5 e NFR1.6: `nfr-requirements/performance-requirements.md` NFR1.6, "Onde muda": o texto pronto do `load-test.yml` fica no `code-summary.md` da U3, que diz substituir o da U2; o `code-summary.md` da U2 não é editado (R-03 do Infrastructure Design, que supera o A6 do NFR Design e a frase da Q3 do Infrastructure Design).
- [x] A9: `functional-design/functional-spec.md`, máquina de estados das linhas: `loadingMore` → `cursorStale` (400 de `cursor`) → `loading` ("Recarregar do início").
- [x] NFR1.6 da U3 em `nfr-requirements/performance-requirements.md` e a suposição 3: os textos de 9 leituras e 7 portões ficam como estão (A5 já confere).
- [x] O conflito 1 (ordem da lista) e o conflito 2 (testes da U1 de `/pnl`), conforme a decisão do responsável na aprovação do plano: o texto de BR6.3 e NFR4.4 em `functional-design/rules.md` e `nfr-requirements/security-requirements.md`, e a frase "ficam verdes sem edição" em `nfr-design/performance-design.md` e `logical-components.md` (A4).
- Cobre: alinhamentos do NFR Design (A1 a A10) e do Infrastructure Design (R-03, D-INF4).

### Etapa 28 — Verificação, documentação e rastreabilidade

- [x] Rodar os comandos da Unit (`unit-test-instructions.md`): vermelho e verde da Fase A, backend de unidade e de integração, Jest e `node:test`.
- [ ] `./apps/api/mvnw -f apps/api/pom.xml verify` com JaCoCo ≥ 80% e Spotless; no `apps/web`, a Etapa 24. O piso nunca é reduzido. Se a máquina não aguentar o `verify` completo (memória), a prova é o `ci` do commit, registrado como na U2.
- [x] Escrever `code-summary.md` (arquivos, decisões, vermelhos registrados, resultados, testes não executados com o motivo, desvios, os textos prontos da Etapa 26, a pendência A3 e a pendência do `pnl-by-mode`), `traceability.json` no formato do verificador (IDs `NFRx.y` sem o prefixo da Unit; uma linha por AC, BR e NFR da Unit; um único arquivo existente por alvo `OK`, os demais numa nota; os 12 ACs da U1 e da U2 como `N/A`) e `source-manifest.json`.
- [ ] Verificação do ponto de controle: `bash scripts/verify-checkpoint.sh` no commit pedido ao responsável. Se o `verify.sh` local morrer por memória, a prova é o run verde do `ci` do mesmo commit, com a árvore limpa fora dos registros do AI-DLC e o commit no remoto (`team.md`).
- [ ] O Build and Test da U3 só fecha com 1 execução verde do grupo U3 do `pnl-by-mode` ampliado no SHA candidato, disparada pelo responsável depois de aplicar o texto pronto (NFR1.6).
- Cobre: NFR7.1, NFR7.2, NFR7.3, NFR7.6, NFR1.6.

## Rastreabilidade história → etapas

| História | Critérios da U3 | Etapas |
|---|---|---|
| US2.3 (filtro por modo) | AC2.3.1 a AC2.3.5 | 6, 16, 17, 25 |
| US3.10 (origem das linhas) | AC3.10.1 a AC3.10.4 | 3, 7, 8, 10, 20, 21, 25 |
| US4.1 (sem total geral) | AC4.1.2 (AC4.1.1, AC4.1.3 a AC4.1.5 da U1 continuam verdes) | 2, 14, 23, 25 |
| US4.2 (aviso do simulado) | AC4.2.2, AC4.2.4, AC4.2.5, AC4.2.6 (AC4.2.1, AC4.2.3 e AC4.2.7 da U1) | 5, 17, 21, 22, 23, 25 |
| US4.3 (painel) | AC4.3.1 a AC4.3.4 | 23, 25 |
| US4.4 (acessibilidade) | AC4.4.1 a AC4.4.4 (AC4.4.5 da U1) | 17, 18, 19, 20, 21, 22, 23 |
| US4.5 (360 px e estados) | AC4.5.1, AC4.5.3 (AC4.5.2 da U1) | 20, 21, 23, 25 |
| US7.1 (período) | AC7.1.1 a AC7.1.5 | 2, 4, 18, 19, 25 |
| US7.2 (fuso e UTC) | AC7.2.2, AC7.2.3 (AC7.2.1 da U2) | 2, 4, 16, 18, 21, 25 |
| US8.1 (comparação) | AC8.1.1 a AC8.1.4 | 2, 9, 10, 22, 25 |

## Riscos e conflitos

- **Conflito 1 — ordem da lista `/bots` (BR6.3, NFR4.4).** As regras dizem "ordem de criação, do mais novo para o mais antigo". O código de hoje (`JdbcBotRepository.list`) pagina por `updated_at desc, bot_id desc`, e o cursor de `listBots` depende disso. Proposta do plano: manter a ordem e o cursor da API (a mesma ordem em todos os filtros, nunca por valor nem misturando modos por valor, que é o que a BR6.3 protege) e alinhar o texto de BR6.3 e NFR4.4 na Etapa 27. Se o responsável preferir a ordem de criação, a Etapa 6 muda a consulta e o cursor da página, e o `BotRoutesIT` ganha o caso.
- **Conflito 2 — testes da U1 de `/pnl` "verdes sem edição" (F-3).** O `PnlScreen.test.tsx` da U1 espera, na abertura, o `GET /api/v1/pnl` com os últimos 7 dias, o carregando da tabela por token e o formulário de período antigo (casos "the period of the last 7 local days goes in UTC", "shows loading, then the empty state", "applies a chosen period…", "a reversed period is explained…"). A decisão do fundador da BR2.1 e da BR2.9 ("Desde o início" por padrão, nenhum pedido a `GET /api/v1/pnl` em "Desde o início", `PeriodSelector` no lugar do formulário) torna esses casos falsos. Proposta do plano: ajustar só esses casos, um a um, para o comportamento novo, sem apagar nenhum, e corrigir a frase no `performance-design.md` e no `logical-components.md` (A4). O `PnlModeBlock.test.tsx` continua sem edição.
- **Texto pronto do `load-test.yml` (A6).** O NFR Design (A6, `performance-design.md`) e a resposta Q3 do Infrastructure Design mandavam pôr uma linha de ponteiro no `code-summary.md` da U2; o `cicd-pipeline.md` revisado (R-03) diz que esse arquivo não é editado. Vale o `cicd-pipeline.md`, o mais recente e revisado.
- **Paridade de tempo num runner compartilhado (NFR3.17).** Se o `BotNotFoundTimingParityIT` ficar instável, é defeito a investigar, nunca teste a ignorar (`team.md`).
- **Ciclo do bot no IT de leitura sem trava.** Ver a Etapa 13.
- **Disputa do `pnl-mismatch-audit`.** 3 threads para 2 conexões; o `ReadRefusalAuditLimitIT` mede. Se houver disputa, um pool próprio volta ao responsável em outro estágio.
- **Portão de carga dependente do responsável.** Sem o texto aplicado e uma execução verde, a U3 fica pendente no Build and Test.
- **Rollback.** Só a imagem volta: 0 migrações, 0 variáveis, 0 recursos Terraform. Os eventos de auditoria já gravados ficam na trilha.
