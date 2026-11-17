# Plano de Code Generation — U7 `u7-exits-and-fiscal` (Bolt B7)

## Summary

- Builds: saídas automáticas (stop, alvo e OCO) com domínio, máquina de estados, API R5, trabalhos de leitura e cancelamento, interfaces de extensão (venda dupla, custódia na reconciliação, recusa na anonimização, flag na troca de modo), adaptador simulado e telas; exportação fiscal CSV em fluxo e conferida contra o saldo; carga do `trigger-program.json` no TxSigner com `POLICY_NOT_RECORDED`; `record.sh --trigger`; e2e `real-exports` e `real-exits`
- Touches: `apps/api` (módulo novo `realexits`; `realexecution` com `DecisionConsumer`, `LiquidationJob`, troca de modo e `RealExitChanges`; `botwallet` com interfaces de extensão, `ReconciliationPolicy`, `closeForAnonymization` e `/real/release`; `ledger` com `streamEntries`; `shared` com `PersonRouteRateLimiter` e `RuntimeSettingsGuard`; `bots.web` com o canal; migração 0022), `apps/txsigner` (carga do programa gravado), `apps/web` (`features/real`, canal, e2e), `docker-compose.yml`, `infra/docker-compose.real.yml`, `infra/solana-fixtures/record.sh`, `infra/observability/`, `infra/terraform/`, `docs/runbooks/real-exits.md`, contrato (adendo da U7)
- Tests: cerca de 230 testes — estados, idempotência, venda dupla, lançamento do preenchimento, reconciliação com custódia, recusa na anonimização, CSV com a conferência do saldo, migração, expurgo e a carga do programa no TxSigner escritos antes do código; integração com PostgreSQL real; e2e de exportação e de condicionais com o adaptador simulado; piso de 80% na API, no web e no TxSigner

Conversa: português (Brasil). Fontes:
- `construction/u7-exits-and-fiscal/functional-design/`: BR0 a BR9, S1 a S50, adendo de contrato;
- `construction/u7-exits-and-fiscal/nfr-requirements/`: U7-NFR*;
- `construction/u7-exits-and-fiscal/nfr-design/`: componentes, interfaces de extensão, ajustes das iterações 1 e 2;
- `construction/u7-exits-and-fiscal/infrastructure-design/`: 0022, caminho simulado, e2e, gravação sem chave, rollback;
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

A U7 toca três tipos de lógica:
- o núcleo financeiro: lançamento do preenchimento, limites e venda dupla;
- a carteira do bot e a política do TxSigner;
- dados pessoais: recusa da anonimização e exportação dos próprios dados.

Essas partes têm os testes escritos **antes** do código (**[testes antes]**). Configuração, rotas, telas, métricas, adaptadores, script de gravação e documentação seguem implementar e depois testar (**[depois]**).

O portão BR0 vale do começo ao fim. A `TriggerPolicy` completa e o `JupiterTriggerAdapter` real **não** são construídos nesta Unit: dependem da gravação real (ver "Fora desta Unit").

## Etapas

- [x] **Step 1 — Estrutura e configuração [depois].**
  - Módulo `realexits`, com os pacotes `api`, `domain`, `application`, `infra`, `infra.jupiter`, `fiscal` e `web`.
  - `RealProperties.exits`:
    - `enabled=false` por padrão;
    - `adapter`, com o simulado recusado fora da `LOCALNET`;
    - `MEMETRADE_JUPITER_API_KEY` opcional.
  - `RuntimeSettingsGuard`: worker em 28 e 36, API com mínimo de 23. Mudam junto `application.yml`, `docker-compose.yml`, `ComposeFilesTest`, as constantes e a Javadoc.
  - Regras ArchUnit novas, cada uma com uma fixture que falha:
    - dependências permitidas da `realexits`;
    - `realexecution` e `botwallet` sem importar `realexits`;
    - rede só em `infra.jupiter`;
    - nenhuma assinatura fora do TxSigner;
    - sem PAPER;
    - o adaptador simulado fora do código de produção.

  *(U7-NFR1.8, U7-NFR2.1, U7-NFR4.10, pools)*
- [x] **Step 2 — Runner de testes.** Confirmar os comandos por escopo (`unit-test-instructions.md`) na API, no TxSigner e no web, cada um com um teste vazio. *(Testing Contract)*
- [x] **Step 3 — Migração 0022 [testes antes].**
  - Escrever primeiro o teste de privilégios e gatilhos. Ele cobre:
    - a matriz de privilégios;
    - o índice único parcial;
    - o gatilho de colunas mutáveis;
    - o gatilho de idade de 5 anos;
    - o rollback com a tabela vazia e com dados.
  - Depois, a migração: `real_conditional_order`, os índices, e `not_before` e `attempts` em `real_decision_request`.

  *(U7-NFR3.5 a U7-NFR3.7)*
- [x] **Step 4 — Domínio da condicional [testes antes].**
  - Escrever primeiro os testes, a partir de S1 a S12, S36 e S43:
    - as transições e o compare-and-set da `ConditionalStateMachine`;
    - a `TriggerValidation` (BR3 e BR2.6);
    - o `needsUserCancellation`, com o relógio em 14:59 e em 15:00.
  - Depois, o código.

  *(BR3, BR6.5, BR6.7, U7-NFR1.4, U7-NFR4.1, U7-NFR4.11)*
- [x] **Step 5 — Interfaces de extensão e venda dupla [testes antes].**
  - Escrever primeiro os testes:
    - `PositionExitGuard`: os resultados `READY`, `WAIT` e `PENDING_SIGNATURE` → `CANCELLED`, sem rede;
    - na U4:
      - `DEFERRED`, com `not_before` e `attempts`;
      - `EXIT_CANCEL_TIMEOUT` depois de 10 tentativas;
      - `NOTHING_TO_SELL`;
      - a liquidação com uma posição em `WAIT`, que não bloqueia as outras;
      - `409 EXIT_CANCELLING` na venda pedida pela pessoa;
    - `EscrowBalances` na `ReconciliationPolicy`: com custódia, sem custódia e com falha na leitura, que vira `UNAVAILABLE`;
    - `OpenExitsCheck` no `closeForAnonymization`, com `409 OPEN_CONDITIONALS`;
    - `ExitsAvailability` na troca de modo e o `exitsEnabled` em `/real/release`.
  - Depois:
    - as interfaces nos módulos donos, com as implementações vazias (`@ConditionalOnMissingBean`);
    - as implementações na `realexits`;
    - as mudanças na U4, na U5 e na U6.

  *(BR4.3, BR5.3b, BR6.9, BR8.1, BR9.3, U7-NFR4.3, U7-NFR4.8 a U7-NFR4.10, S19, S37, S47, S49, S50)*
- [x] **Step 6 — Criação, assinatura e cancelamento [testes antes].**
  - Escrever primeiro os testes:
    - a barreira e o flag;
    - a `Idempotency-Key`, com o mesmo corpo e com corpo diferente;
    - a sessão de assinatura: prazo, mensagem diferente e o consentimento do caminho simulado;
    - o `DELETE`;
    - o isolamento entre contas.
  - Depois:
    - o `ConditionalService` e a `TriggerPort`;
    - o `SimulatedTriggerAdapter` (`@Profile("test")`), sem transação, sem TxSigner e sem ledger;
    - o teste que confere que o contexto de produção não tem o bean simulado.

  *(BR1, BR4.1, BR4.4, U7-NFR1.9, U7-NFR3.4, S1 a S12, S48)*
- [x] **Step 7 — Preenchimento [testes antes].**
  - Escrever primeiro os testes, com transações e RPC sintéticos gerados pelo SDK só no escopo de teste:
    - o `FillPosting`: um `SWAP_FILL_OUT`, e o `NETWORK_FEE` só quando a carteira do bot pagou a taxa;
    - as chaves `cond:<sig>:fill` e `cond:<sig>:fee`, conferidas contra a 0016 (com hash, se a coluna for curta);
    - o lançamento gravado na mesma transação do `FILLED`;
    - a mesma leitura duas vezes gerando um só lançamento;
    - a confirmação `finalized` antes de lançar;
    - o OCO, com `filled_side`;
    - a Jupiter fora, sem mudar o estado.
  - Depois, o `ConditionalReadJob` (30 s, ShedLock, batimento).

  *(BR5, U7-NFR3.1, U7-NFR4.2, U7-NFR4.4, U7-NFR4.6, S13 a S15, S20, S27)*
- [x] **Step 8 — Cancelamento automático [testes antes].**
  - Escrever primeiro os testes:
    - o kill switch, com 2 min até `CANCELLING`;
    - a troca de modo;
    - a revogação;
    - a nova tentativa a cada minuto;
    - o alerta e o `needsUserCancellation` aos 15 min;
    - o cancelamento com o flag de saídas desligado;
    - a corrida entre o preenchimento e o cancelamento.
  - Depois, o `ConditionalCancelJob`.

  *(BR6, U7-NFR3.2, U7-NFR4.5, S16 a S18, S36, S38, S39)*
- [x] **Step 9 — Exportação fiscal [testes antes].**
  - Escrever primeiro os testes:
    - o `FiscalCsvWriter`:
      - BOM, `;`, vírgula decimal e `\r\n`;
      - escape só nos campos de texto;
      - números sempre positivos;
      - campo vazio quando não há valor;
      - `data_utc` e `data_local`;
    - o mapeamento de BR7.3, sem a taxa contada duas vezes;
    - a soma de BR7.6, conferida contra a variação do saldo num ledger de exemplo;
    - o período: 365 dias aceito e 366 recusado;
    - o isolamento por tenant e pessoa, sem PAPER;
    - o limite de 10 por hora;
    - o fluxo: a primeira linha sai antes de ler a última, e 20 mil linhas em até 10 s;
    - a exportação com o kill switch ligado, sem liberação e com o REAL pausado.
  - Depois:
    - `streamEntries` na M2, com transação somente leitura e `fetchSize` 500;
    - o `PersonRouteRateLimiter` parametrizado e o `FiscalExportLimiter`;
    - o `FiscalExportController`, com `StreamingResponseBody`.

  *(BR7, U7-NFR1.6, U7-NFR1.7, U7-NFR3.3, U7-NFR3.8, U7-NFR3.9, U7-NFR4.12, S28 a S35, S45, S46)*
- [x] **Step 10 — Rotas, contrato e canal ao vivo [depois].**
  - O `ConditionalController`, com:
    - as rotas R5 do adendo;
    - os códigos de erro novos;
    - as operações em `IdempotentOperation`;
    - o registro de rotas (`scope: CLIENT`, `negativeTest`).
  - A `RealExitChanges` em `realexecution.api`, implementada pela `realexits`. O `DashboardMarkPoller` envia `real.conditional.state` (`DashboardEvents.RealConditionalStateChanged`).
  - OpenAPI e AsyncAPI atualizados; `gen:api` e `check:api` nos dois apps.

  *(adendo, BR5.5, U7-NFR1.9)*
- [x] **Step 11 — TxSigner: carga do programa gravado [testes antes].**
  - Escrever primeiro os testes:
    - sem o `trigger-program.json`, `TRIGGER_CREATE` e `TRIGGER_CANCEL` são recusados com `POLICY_NOT_RECORDED`;
    - com o arquivo e um hash que não confere, o TxSigner não sobe;
    - com o arquivo e o hash certos, a política fica registrada, mas nesta Unit continua recusando tudo até a gravação.
  - Depois:
    - o carregador;
    - a conferência do `.sha256` e da propriedade `txsigner.trigger.program-sha256`;
    - os tipos novos no `/signatures`.

  *(BR0.3, U7-NFR1.2)*
- [x] **Step 12 — Expurgo [testes antes].** Escrever primeiro os testes; depois o `RealExitsPurgeJob` na API, pelo pool `risk_retention`, com 5 anos e o gatilho de idade. *(U7-NFR3.7)*
- [x] **Step 13 — Métricas, alertas e Terraform [depois].**
  - `RealExitsMetrics` e o `RealExitsGaugesJob`.
  - A contagem de recusas no TxSigner.
  - `real-exits-alerts.yml`, validado com `promtool`.
  - `real-exits.tf`, validado com `terraform validate`.
  - O `RealExitsAlarmParityTest` e os testes de log mascarado.

  *(U7-NFR5.1 a U7-NFR5.11)*
- [x] **Step 14 — Telas [depois].**
  - O modo "Automática nas saídas", habilitado por `exitsEnabled`.
  - O diálogo "Definir stop e alvo", com:
    - texto decimal, sem ponto flutuante;
    - os limites de BR2.6;
    - o aviso do stop;
    - o preço atual com a fonte;
    - o consentimento assinado no caminho simulado.
  - A aba "Condicionais", com os estados e o aviso de BR6.7.
  - O botão "Exportar histórico (CSV)", com o período.
  - O evento `real.conditional.state` em `warn.ts` e `envelopes.ts`.

  *(BR8, U7-NFR7.1)*
- [x] **Step 15 — Gravação [depois].** O `infra/solana-fixtures/record.sh --trigger`:
  - só pede transações sem assinatura;
  - grava o programa, os discriminadores, as contas, a derivação, as respostas e os clones;
  - não assina nem envia nada;
  - para se a Jupiter exigir chave.

  O script não é rodado nesta Unit. *(BR0.2, U7-NFR8.2)*
- [x] **Step 16 — Ponta a ponta [depois].**
  - O spec `e2e/real-exports`.
  - O spec `e2e/real-exits`:
    - sempre o último, com um comentário que diz isso;
    - criar, `DELETE` e kill switch dentro de `try/finally`;
    - na saída, volta para AUTO_FULL e deixa o kill switch desligado.
  - As variáveis do perfil de teste em `infra/docker-compose.real.yml`.

  *(U7-NFR8.5, infrastructure-specification)*
- [x] **Step 17 — Documentação e rastreabilidade.**
  - O runbook `docs/runbooks/real-exits.md`, com:
    - o portão da gravação;
    - o flag;
    - o cancelamento parado;
    - o rollback (kill switch, espera e desligar no fim);
    - o risco que sobra de BR2.6, para a decisão do fundador.
  - O adendo da U7 no `contract-summary.md`.
  - O `code-summary.md`, com:
    - os textos para o responsável: o `promtool` no `ci.yml`, os specs no `verify.sh` e a execução do `record.sh --trigger`;
    - a lacuna aceita: o preenchimento sem e2e.
  - `traceability.json` e `source-manifest.json`.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US3.4 | 3 a 8, 10, 11, 13, 14, 16 |
| US4.3 | 9, 10, 14, 16 |
| AC8.1.3 (FR8.2) | 8 |

## Fora desta Unit

| Item | Onde fica |
|---|---|
| `TriggerPolicy` completa (BR2) e `JupiterTriggerAdapter` real | depois da gravação real (portão BR0), numa entrega própria, com os testes de ataque escritos antes do código |
| Ligar o flag na devnet e na mainnet | depois da gravação; na mainnet, também com a decisão do fundador sobre o risco que sobra de BR2.6 |
| `ci.yml` e `verify.sh` | o responsável do projeto, com o texto pronto |
