# Plano de Code Generation — U4 `u4-real-execution` (Bolt B4)

## Summary

- Builds: execução das ordens REAL — bot REAL, fila de decisões, máquina de estados com uma transação por ordem, Jupiter, cossinatura no modo Assinada, política de swap do TxSigner, confirmação e ambígua, ledger e P&L REAL, troca de modo, vender tudo e sacar
- Touches: `apps/api` (módulo novo `realexecution`, `pnl.real`, `bots`, `shared.solana`, `RuntimeSettingsGuard`, migração 0019, canal ao vivo), `apps/txsigner` (`SwapPolicy`, leitor), `infra/kms-sim` (semente), `infra/docker-compose.real.yml` (validador com contas gravadas, `jupiter-stub`), `docker-compose.yml`, `infra/solana-fixtures/`, `infra/observability/`, `infra/terraform/`, `apps/web/e2e/real-swap/`, `docs/runbooks/real-execution.md`, contrato (adendo da U4)
- Tests: cerca de 260 testes — estados, idempotência e política de swap escritos antes do código; integração com PostgreSQL real (concorrência, queda, privilégios); vetores Solana comuns; ponta a ponta do swap no validador local; piso de 80% na API e no TxSigner

Conversa: português (Brasil). Fontes:
- `construction/u4-real-execution/functional-design/`: BR1.1 a BR10.2, F1 a F10, S1 a S30;
- `construction/u4-real-execution/nfr-requirements/`: U4-NFR*;
- `construction/u4-real-execution/nfr-design/`: componentes, desvio de Q2, posse e compare-and-set, canal;
- `construction/u4-real-execution/infrastructure-design/`: Compose, guard, matriz de privilégios, prova do swap;
- `inception/contract-design/contract-summary.md`: R1, R3, R6, M1 a M6, adendos da U1 e da U3;
- `team.md` e `project.md`.

## Testing Contract

```json
{
  "version": 1,
  "methodology": "custom",
  "source": "team",
  "ordering": "No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.",
  "scope": "classic",
  "test_strategy": "comprehensive",
  "project_type": "brownfield",
  "applicable_notes": [
    {
      "layer": "org",
      "text": "We treat tests as a first-class deliverable in every Bolt. The specific\nmethodology (TDD, BDD, ATDD, or classic test-after) is affirmed at\npractices-discovery and recorded in `team.md` under this heading with explicit\n`Methodology` and `Ordering` fields; Code Generation resolves those fields\nindependently from coverage, tooling, and scope notes.\n\nWhen no posture has been affirmed, our default per scope is:\n- **Methodology**: test-after\n- **Ordering**: implement each applicable testable layer, then write and run\n  that layer's tests.\n- `mvp`, `enterprise`, `feature`, `infra`, `classic` add an 80% line-coverage\n  floor and CI execution before merge.\n- `bugfix`, `security-patch` add a targeted regression for the specific\n  bug/vulnerability and require the existing suite to remain green.\n- `express` uses the Minimal strategy: requirement-driven unit tests (one per\n  requirement, with a happy-path floor per component); existing tests remain\n  green.\n- `poc`, `refactor`, `workshop` add no extra new-test floor and require the\n  existing suite to remain green.\n\nThe active `Test Strategy` still applies in every scope and determines test\nvolume/types. Scope floors are additive; they never reduce or replace the\nselected strategy.\n\nBuild and Test verifies defined coverage floors and affirmed quality targets;\nthey may not be weakened to make a step pass.\n\nAffirm a stricter posture in `team.md` if the team commits to one."
    },
    {
      "layer": "team",
      "text": "- **Methodology**: custom\n- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.\n- **Methodology evidence**: decisão do responsável do projeto na entrevista\n  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a\n  passar em testes escritos depois sobre o próprio código; com o teste escrito\n  antes, ele valida o requisito e não a implementação. Test-after no restante\n  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo. No\n  intent `261109-termos-lgpd` (Q5, opção A), o responsável do projeto estendeu a\n  ordem de testes antes do código à lógica de dados pessoais (anonimização,\n  exclusão, novo aceite dos termos e expurgos), porque ela toca tabelas\n  append-only como a trilha de auditoria e as ligadas ao ledger. No intent\n  `261113-trading-real-bot`, o NFR8 dos requisitos estende a ordem de testes\n  antes do código à lógica de carteira e saque, e o plano de Bolts pede os testes\n  de ataque da política do TxSigner escritos antes do código (B1) e os testes de\n  estados e idempotência da execução REAL antes do código (B4).\n- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`, também no\n  intent `261109-termos-lgpd` (Q4, opção A); o piso de cobertura abaixo é somado\n  a ela e nunca a substitui.\n- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no\n  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente\n  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam\n  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A\n  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo\n  no Maven e limite de cobertura do executor de testes do frontend). O piso não\n  pode ser reduzido para fazer uma etapa passar. O processo TxSigner também\n  segue o piso de 80%.\n- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou\n  mantém verdes os testes de:\n  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta\n    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que\n    também validam o tenant;\n  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);\n  - deduplicação por chave de idempotência;\n  - violação de limites autorizados e controles de risco;\n  - dado de mercado ausente vira `unknown`, nunca zero;\n  - pausa do bot impede novas operações;\n  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só\n    acontece por lançamento compensatório;\n  - P&L com cenários determinísticos de resultado conhecido, em aritmética\n    decimal e unidades de token;\n  - REAL desligado por padrão: com a configuração padrão (API, worker e\n    TxSigner), nenhuma carteira do bot é criada, nenhum caminho assina ou envia\n    transação e nenhuma assinatura é pedida ao KMS;\n  - login sem reutilização de assinatura: nonce reutilizado ou assinatura\n    expirada são rejeitados, e a assinatura de login não serve para autorizar\n    trading;\n  - a anonimização não deixa o endereço da carteira em nenhuma tabela nem log\n    consultável;\n  - a versão nova dos termos exige novo aceite antes de qualquer outra rota,\n    **exceto** a leitura da carteira do bot (`GET /api/v1/real/bot-wallet`) e as\n    rotas de saque (`/api/v1/real/bot-wallet/withdrawals` e\n    `/api/v1/real/bot-wallet/withdrawals/{withdrawalId}`), que continuam\n    disponíveis sem o novo aceite para o usuário sempre poder tirar o próprio\n    dinheiro; o teste cobre as duas metades: as rotas da exceção respondem sem o\n    novo aceite e todas as demais respondem `403 TERMS_REACCEPTANCE_REQUIRED`;\n  - saque só para a carteira principal registrada: qualquer outro destino é\n    recusado pela API e pelo TxSigner, e o saque continua disponível com o kill\n    switch acionado, com o REAL desligado e sem a liberação vigente;\n  - a política de conteúdo do TxSigner (ADR-004) recusa toda transação fora da\n    lista: programa fora da lista, assinante ou pagador diferente da carteira do\n    bot, destino fora da carteira do bot ou da carteira principal, saída mínima\n    abaixo da cotação menos a derrapagem, troca de autoridade, fechamento de\n    conta para terceiros e valor acima do teto autorizado; cada recusa registra o\n    motivo;\n  - uma única transação por ordem REAL: repetir a ordem com a mesma\n    `Idempotency-Key`, reprocessar a decisão da fila ou repetir depois de uma\n    ordem ambígua nunca gera segunda transação.\n- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o\n  reproduz.\n- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.\n  Os critérios básicos de acessibilidade do beta definidos em `project.md`\n  continuam valendo.\n- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds\n  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam\n  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação\n  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem\n  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,\n  mint ou dados pessoais reais. No REAL, a rede é o validador Solana local (com\n  contas clonadas para os swaps), o KMS é simulado no ambiente local e as\n  respostas da Jupiter e do RPC usadas em testes de unidade são gravadas; nenhum\n  teste da CI usa devnet, mainnet, KMS real ou chave real.\n- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no\n  Windows. O fim de linha é padronizado em LF por `.gitattributes` e\n  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.\n- **Frequência**: em toda mudança e no merge para `main` rodam os testes de\n  unidade e de integração de todas as partes (backend, `apps/web` e\n  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O\n  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; não há uma\n  suíte maior reservada ao merge.\n- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado\n  em silêncio.\n- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no\n  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.\n- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não\n  funcionais definam metas numéricas."
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
      "Keep the existing test suite green.",
      "This scope adds no extra new-test floor beyond the selected test strategy."
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
  "input_sha256": "sha256:b7f5625e55bdee4b1fae55cf7a9f270603b9853a8e8e627051890f728c613413",
  "contract_sha256": "sha256:98df078ab1ee44bde9eaa1e5c7f5f121064c1ad1d933fdf12d5563b3111d6400"
}
```

## Ordem aplicada

A U4 é núcleo financeiro e lógica do TxSigner. Os estados e a idempotência das ordens REAL, a política de swap, o ledger e o P&L REAL têm os testes escritos **antes** do código (**[testes antes]**), como pedem o `team.md` e o plano de Bolts (B4). A configuração, as rotas de leitura, as métricas, os alertas, a infraestrutura local e a documentação seguem implementar e depois testar (**[depois]**).

## Etapas

- [x] **Step 1 — Estrutura e configuração.**
  - Criar o módulo `realexecution` (`api`, `domain`, `domain.solana`, `infra`, `infra.jupiter`, `web`) e `pnl.real`, e mover o `Base58Codec` para `shared.solana`.
  - Criar a M6 em `realexecution.api` e o `withPersonLock` na M5 (U3).
  - Configuração `memetrade.real.execution.*`: tempos limite, prazos, `maxAccounts`, teto da taxa de prioridade.
  - `RuntimeSettingsGuard`: worker com scheduler de 19 a 26 e pool de 27 a 40.
  - Regras ArchUnit novas, cada uma com teste que comprova a falha:
    - rede só em `infra.jupiter`;
    - `realexecution` sem PAPER;
    - fronteiras do módulo.

  *(U4-NFR1.4, U4-NFR2.1, U4-NFR3.7, U4-NFR8.3)*
- [x] **Step 2 — Runner de testes.** Confirmar os comandos por escopo (`unit-test-instructions.md`) na API e no TxSigner com um teste vazio. *(Testing Contract)*
- [x] **Step 3 — Vetores Solana e montagem da mensagem [testes antes].**
  - Gerar com o SDK, só no escopo de teste, os vetores comuns em `src/test/resources/solana-vectors/` da API e do TxSigner, com um teste que compara os dois arquivos.
  - Testes primeiro, depois `MessageBuilder` e `MessagePlan` na API: mensagem v0 só com chaves estáticas, conta de tamanho, resumo SHA-256 e conferência Ed25519 nativa.

  *(U4-NFR1.1, U4-NFR1.5, desvio de Q2)*
- [x] **Step 4 — Política de swap do TxSigner [testes antes].**
  - Escrever primeiro um teste de ataque por motivo de BR4.3, mais as transações gravadas que devem passar e as com tabela de endereços (`LOOKUP_TABLE_NOT_ALLOWED`).
  - Depois implementar a `SwapPolicy` sobre o leitor do TxSigner.
  - `/signatures` passa a aceitar `SWAP`, com o modo `SIGNED` (cossinatura) e `AUTO_FULL`.
  - Repetir o mesmo `signingRequestId` devolve a mesma assinatura; conteúdo diferente devolve `REQUEST_MISMATCH`.
  - `ProductionChecks` recusa a semente do KMS simulado.

  *(BR4.3, U4-NFR1.2, U4-NFR8.1)*
- [x] **Step 5 — Máquina de estados e idempotência [testes antes].**
  - Testes primeiro, a partir de S1 a S30: transições permitidas, códigos, `QuoteMath`, compare-and-set e uma transação por ordem.
  - Depois `RealOrderStateMachine` e `QuoteMath` (domínio puro).

  *(BR2 a BR5, BR7, BR8, U4-NFR4.1, U4-NFR4.2)*
- [x] **Step 6 — Migração 0019 [testes antes].**
  - Escrever primeiro o teste de privilégios e gatilhos.
  - Depois a migração:
    - campo `market` em `bot`;
    - tabelas `real_decision_request`, `real_order`, `real_order_attempt`, `real_liquidation`, `real_upstream_health` e `real_execution_purge_run`;
    - índices únicos e parciais;
    - matriz de privilégios;
    - gatilhos de colunas mutáveis, de estado final (limpeza da mensagem) e de idade;
    - rollback por changeset.

  *(U4-NFR3.8, U4-NFR3.9)*
- [x] **Step 7 — Bot REAL e decisão no ciclo [testes antes].**
  - Testes primeiro.
  - Campo `market` (BR1.1).
  - Caminho REAL no ciclo:
    - ordem em andamento;
    - posse do bot;
    - M5 `evaluate`;
    - `recordDecision` (`MANDATORY`);
    - `transactionUsable` falso desfaz o ciclo;
    - liquidação ativa.
  - Separação REAL/PAPER e isolamento entre tenants.

  *(BR1.1, BR1.2, BR2.2)*
- [x] **Step 8 — Consumidor e condutor [testes antes].**
  - Testes de integração primeiro, com PostgreSQL real, WireMock da Jupiter e TxSigner em processo:
    - concorrência;
    - posse;
    - queda com contexto novo em cada gravação, inclusive entre a assinatura e `SIGNED` e entre o envio aceito e o resultado.
  - Depois:
    - `DecisionConsumer`: trava da pessoa, `handOver`, `RESERVE`;
    - `OrderDriver`: cotação, mensagem gravada, assinatura, `SENT` antes do envio, posse renovada;
    - `JupiterClient`.

  *(BR2.1, BR3, BR4.1, BR5.1, U4-NFR4.3, U4-NFR4.4)*
- [x] **Step 9 — Sessão de assinatura no modo Assinada [testes antes].**
  - Testes primeiro: prazo, mensagem diferente, reivindicação contra o trabalho de prazo, recusa e reivindicada parada há 15 s.
  - Depois o `SigningSessionService` (`GET` e `POST`), `POST .../reject` e o `SigningDeadlineJob`.

  *(BR4.2, U4-NFR6.2)*
- [x] **Step 10 — Confirmação, ambíguas e fornecedores [testes antes].**
  - Testes primeiro: 60 s, ambígua só pela rede, RPC fora, três falhas e pausa, retomada com fornecedores saudáveis por 5 min.
  - Depois o `ConfirmationJob`, o `AmbiguousJob` e o `UpstreamHealthJob`.

  *(BR5.1, BR5.2, BR9.1, U4-NFR4.5, U4-NFR4.6)*
- [x] **Step 11 — Ledger e P&L REAL [testes antes].**
  - Testes primeiro: lançamentos com os valores da rede, taxa da carteira principal na ordem e cenários de P&L com resultado conhecido.
  - Depois os lançamentos da confirmação, da falha e do cancelamento, e o `RealPnlCalculator`.

  *(BR6.1, BR6.2)*
- [x] **Step 12 — Troca de modo e vender tudo [testes antes].**
  - Testes primeiro.
  - `PUT /api/v1/real/mode`: rebaixar cancela as ordens não enviadas na mesma transação; subir exige consentimento; ordem ambígua devolve 409.
  - `POST /api/v1/real/liquidations` e o `LiquidationJob`: vendas em série e saque com `DONE` ou `PARTIAL`.
  - M6 `cancelUnsent`.

  *(BR7.1, BR7.2, BR8.1, BR8.2)*
- [x] **Step 13 — Rotas de leitura e canal [depois].**
  - `GET` das ordens (lista e item) e `GET /api/v1/real/pnl`.
  - Marcas do canal na mesma transação e eventos `RealOrderStateChanged` e `RealSigningSessionOpened` no `DashboardMarkPoller`.
  - OpenAPI regenerado e tipos do web regenerados. Rotas R3 exigem o aceite dos termos v3.
  - Testes de rota e de isolamento.

  *(BR10.1, BR10.2, U4-NFR6.1)*
- [x] **Step 14 — Expurgo [testes antes].** Testes primeiro; depois o expurgo na API pelo pool `risk_retention` (5 anos e 13 meses), com registro em `real_execution_purge_run`. *(U4-NFR3.9)*
- [x] **Step 15 — Métricas, alertas e Terraform [depois].**
  - `RealExecutionMetrics`: rótulos fechados e contadores com zero na subida.
  - `real-execution-alerts.yml` validado localmente com `promtool`.
  - `real-execution.tf`, sem o p95, validado com `terraform validate`.
  - `RealExecutionAlarmParityTest`.

  *(U4-NFR5.1 a U4-NFR5.14)*
- [x] **Step 16 — Infraestrutura local do swap [depois].**
  - `KMS_SIM_SEED` no `kms-sim`.
  - `infra/solana-fixtures/record.sh` e as contas gravadas, uma vez, com rede, fora dos testes.
  - Validador com `--bpf-program` e `--account`.
  - `jupiter-stub` no `infra/docker-compose.real.yml`.
  - Worker com 27 e 19 no `docker-compose.yml` e no `ComposeFilesTest`.
  - Mint gravado no `sim-a` e estratégia de teste no `RealTestSeeder`.
  - Spec `apps/web/e2e/real-swap/swap.spec.ts`.

  *(U4-NFR8.10, infrastructure-specification)*
- [x] **Step 17 — Medição informativa [depois].** Teste com 21 usuários e 4 decisões por segundo, que registra o p95 por etapa e da decisão ao envio, sem portão. *(U4-NFR3.1 a U4-NFR3.5)*
- [x] **Step 18 — Documentação e rastreabilidade.**
  - Runbook `docs/runbooks/real-execution.md`: ambígua, pausa `UPSTREAM`, recusa da política e rollback com espera de 15 min.
  - Adendo da U4 no `contract-summary.md`: R1 `SWAP`, M6, `withPersonLock`, eventos do canal, campo `market`.
  - `code-summary.md`, com os textos para o responsável: job `txsigner`, `promtool` e `verify.sh`.
  - `traceability.json` e `source-manifest.json`.

## Volta 1 do Build and Test (2026-11-18)

O Build and Test (Loop-back 1 em `construction/build-and-test/test-results.md`) marcou como não cumpridos o U4-NFR8.5 e os dois desvios U4-NFR8.4 e U4-NFR8.7. O responsável aceitou os dois desvios, e as metas foram revisadas em `nfr-requirements/tech-stack-decisions.md` (2026-11-18). As etapas 1 a 18 continuam como estão.

- [x] **Step 19 — Campo `market` no detalhe do bot [depois].**
  - Acrescentar `market` (`PAPER` ou `REAL`) ao `BotDetailDto`.
  - Regenerar o OpenAPI do convidado e os tipos do `apps/web` (`gen:api`), e manter o `check:api` verde.
  - Teste de rota: o detalhe traz o mercado do bot, e um bot de outro tenant continua dando 404.

  *(U4-NFR8.5)*
- [x] **Step 20 — Ritmo das ordens REAL contra o limite por carteira [testes antes].** Origem: a revisão da U1 (review-03, achado 2).
  - Testes primeiro: o caminho de decisão e envio da U4 nunca pede mais de 60 assinaturas por minuto para a mesma carteira em operação normal. Se pedir, a recusa `WALLET_RATE_LIMITED` vira `FAILED` com `SIGNING_REFUSED`, sem segunda transação, e com o motivo registrado.
  - Se o teste mostrar que o ritmo normal pode passar do limite, a recusa por limite passa a voltar para a fila em vez de falhar. Isso fica registrado como desvio no `code-summary.md`.

  *(NFR1.9 da U1, idempotência REAL)*
- [x] **Step 21 — Rastreabilidade no formato do sensor.**
  - Reescrever `traceability.json` no formato que o `aidlc engine sensor-traceability` lê: uma linha por AC, BR e NFR da Unit, com os IDs como no requisito e um arquivo existente por alvo `OK`. Onde a medição está pendente, uma nota diz isso.
  - Rodar o sensor até passar e atualizar o `code-summary.md`.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US3.1 (servidor) | 12 |
| US3.2 | 3, 4, 9 |
| US3.3 | 5, 7, 8, 10 |
| US4.1 (dados) | 5, 13 |
| US4.2 | 11, 13 |
| US5.2 | 12 |
| US8.3 | 5, 10 |

## Fora desta Unit

| Item | Onde fica |
|---|---|
| Telas | U6 |
| Ordens condicionais e exportação fiscal | U7 |
| Liberação, kill switch e rota da lista de bloqueio | U5 |
| `ci.yml` e `verify.sh` | responsável do projeto, com o texto pronto |
