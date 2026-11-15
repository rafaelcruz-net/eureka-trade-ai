# Plano de Code Generation — U3 `u3-real-risk` (Bolt B3)

## Summary

- Builds: barreira de risco REAL (M5) — limites de compra, perda do dia com pausa, teto com reservas, tokens permitidos e bloqueio, frescor e fonte, trava por carteira, trabalhos, expurgo, métricas e alertas
- Touches: `apps/api/src/main/java/com/memetrade/riskcontrol/` (api, real/domain, real/infra), `botwallet.api` e `ledger.api` (leituras M1/M2), `marketdata.api` (preços em lote), migração 0018, regras ArchUnit, `infra/observability/real-risk-alerts.yml`, `infra/terraform/modules/observability/real-risk.tf`, `docs/runbooks/real-risk.md`, `contract-summary.md` (M1, M2, M4, M5)
- Tests: cerca de 140 testes — política pura com os cenários S1 a S17 escritos antes do código, integração com PostgreSQL real (trava, repetição, privilégios, trabalhos), ArchUnit e paridade dos alarmes; piso de 80%

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/functional-design/` (BR1.1 a BR7.3, F1 a F6, S1 a S17), `construction/u3-real-risk/nfr-requirements/` (U3-NFR*), `construction/u3-real-risk/nfr-design/`, `construction/u3-real-risk/infrastructure-design/`, `inception/contract-design/contract-summary.md` (M1, M2, M4, M5) e as regras de `team.md` e `project.md`.

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

A U3 é núcleo financeiro: limites de risco, reservas, perda do dia e deduplicação. Por isso a ordem do contrato é escrever os testes **antes** do código a partir da regra de negócio, em todas as etapas de regra, trava, repetição e banco (**[testes antes]**). Só a configuração, as métricas, os alertas, o Terraform e a documentação seguem implementar e depois testar (**[depois]**).

## Etapas

- [x] **Step 1 — Estrutura e configuração.** Criar `riskcontrol.real.domain` e `riskcontrol.real.infra`, e a M5 (`RealRiskGate`, `OrderIntent`, `GateDecision`, códigos e detalhes) em `riskcontrol.api`. Criar `RealRiskLimits` com as chaves `memetrade.real.risk.*` e os padrões, e a conferência de subida (faixa sempre; valor mais frouxo recusado fora de `test` e `local`). Ajustar as regras ArchUnit existentes: `riskcontrolDependsOnlyOnThePublicInterfacesOfOtherModules` aceita `botwallet.api`, `ledger.api` e `riskassessment.api`; a regra do pool `risk_retention` aceita o `RealRiskPurgeJob`. Criar as regras novas: fronteira de `riskcontrol.real`, chamadores de `blockToken` só em `administration`, e `botwallet` sem `riskcontrol`. Cada regra nova tem um teste que comprova a falha. *(U3-NFR8.1 a U3-NFR8.4, U3-NFR6.2, U3-NFR4.7)*
- [x] **Step 2 — Runner de testes.** Confirmar os comandos por escopo da Unit (`unit-test-instructions.md`) com um teste vazio. *(Testing Contract)*
- [x] **Step 3 — Leituras M1, M2 e M4 [testes antes].** Testes primeiro, depois as leituras nos módulos donos, pelas interfaces públicas:
  - **M1, `botwallet.api`:** `RealStateQuery.readRealState`, com `pausedReasons` e `pausedSince`; `RealPauses.pauseUser` e `resumeUser`, idempotentes.
  - **M2, `ledger.api`:** `RealLedger.positions`, com quantidade em unidades base e custo em lamports; `flowsSince`; e `RealBalance` com as reservas separadas em ordem e saque.
  - **M4, `marketdata.api`:** `referencePrices(Set<TokenId>)` numa só consulta.

  *(BR7.3, BR5.2, Q11)*
- [x] **Step 4 — `RealRiskPolicy` [testes antes].** Escrever os testes de unidade dos cenários S1 a S17, S4b, S4c, S5b e S5c e das regras BR1.1 a BR5.3. Depois implementar a política pura sobre `RealGateInputs`, sem I/O nem relógio próprio. Ela aplica a ordem fixa das conferências, os códigos e detalhes, as contas em lamports e pontos-base com arredondamento contra a compra, e o `unknown` nunca tratado como zero. *(BR1.1 a BR5.3, BR7.2, US2.1, US6.1, US6.2, US6.3)*
- [x] **Step 5 — Migração 0018 [testes antes].** Escrever primeiro o teste de privilégios e gatilhos. Depois a migração cria:
  - as tabelas `real_risk_evaluation`, `real_risk_reservation`, `real_risk_day`, `real_blocked_token` e `real_risk_purge_run`, com os índices;
  - a matriz de privilégios da especificação de infraestrutura;
  - os gatilhos de idade de 13 meses, o de transição da reserva e os que recusam UPDATE e TRUNCATE;
  - um bloco de rollback por changeset.

  *(U3-NFR3.6, U3-NFR3.7, U3-NFR8.6)*
- [x] **Step 6 — `WalletLock`, `RealGateInputsReader` e `JdbcRealRiskStore` [testes antes].** Testes de integração primeiro. Depois:
  - **Trava:** de duas chaves, com 20 ms entre tentativas e limite de 1 s, sem abortar a transação.
  - **Leitor das entradas:** falha fechada com `RealGateUnavailable(dependency)` e filtro por tenant.
  - **Gravação:** com `ON CONFLICT`.

  *(BR6.1, BR6.3, U3-NFR4.1, U3-NFR4.8, U3-NFR6.1)*
- [x] **Step 7 — `TransactionalRealRiskGate` [testes antes].** Testes de integração primeiro, sobre PostgreSQL real. O fluxo de `evaluate` (`MANDATORY`):
  - **Compra:** pega a trava; procura o `decision_id` e devolve o resultado gravado se ele já existir; senão, lê as entradas, roda a política e grava avaliação, reserva, auditoria e pausa.
  - **Venda:** sem trava, com `ON CONFLICT`.
  - **Liquidação:** com as exceções da BR1.1.

  As demais operações:
  - `handOver` pega a trava por dentro e trata o vencimento.
  - `blockToken` e `unblockToken` abrem a própria transação e usam `ON CONFLICT DO NOTHING`.

  Testes:
  - concorrência entre duas compras e entre o ciclo e o `handOver`;
  - compra repetida sem pausar, auditar nem contar;
  - venda repetida em paralelo;
  - ciclo desfeito;
  - separação REAL/PAPER e isolamento entre tenants;
  - chamada sem transação falhando.

  *(BR6.1, BR6.2, BR7.1, U3-NFR4.2 a U3-NFR4.6)*
- [x] **Step 8 — Trabalhos F3, F4 e F5 [testes antes].** Testes primeiro: idempotência, retomada só da pausa de dia anterior, foto com `ON CONFLICT`, vencimento das reservas, perda recalculada depois de um ciclo desfeito. Depois os trabalhos no worker, com ShedLock, batimento e contador de execuções. *(BR4.3 a BR4.5, BR6.2, U3-NFR4.4)*
- [x] **Step 9 — `RealRiskPurgeJob` [testes antes].** Testes primeiro. Depois o trabalho na API, às 04:00 UTC, com nome próprio no ShedLock, pelo pool `risk_retention`, em lotes de 1.000, registrando em `real_risk_purge_run`. O teste confere que o gatilho recusa apagar linha nova. *(U3-NFR3.7)*
- [x] **Step 10 — Métricas [depois].** `RealRiskMetrics` com os nomes, rótulos e buckets do NFR Design, sem mint nem usuário nos rótulos. O medidor de reservas só no worker, por tarefa própria. *(U3-NFR5.1 a U3-NFR5.8)*
- [x] **Step 11 — Alertas e Terraform [depois].**
  - `infra/observability/real-risk-alerts.yml`, com as anotações `threshold`, `window_seconds` e `job`, validado localmente com `promtool` (imagem `prom/prometheus` no Docker).
  - `infra/terraform/modules/observability/real-risk.tf`, com namespace `Eureka/RealRisk`, sem o alarme de p95 (adiado), validado com `terraform fmt -check` e `terraform validate` no Docker.
  - `RealRiskAlarmParityTest`.

  *(U3-NFR5.9 a U3-NFR5.12)*
- [x] **Step 12 — Contrato e configuração [depois].**
  - Atualizar `contract-summary.md` (M1, M2, M4 e M5) com um adendo da U3: `OrderIntent` com origem, `handOver`, regra das vendas, `pausedReasons` e `pausedSince`, leituras novas.
  - Acrescentar as chaves `memetrade.real.risk.*` comentadas no `.env.example`.
  - Teste de subida com cada chave.

  *(Q11, U3-NFR8.3)*
- [x] **Step 13 — Medição informativa [depois].** Teste de integração com 21 carteiras, 10 posições e 4 avaliações por segundo, que registra o p95 da compra e da venda sem portão. *(U3-NFR3.1 a U3-NFR3.4)*
- [x] **Step 14 — Documentação e rastreabilidade.** Runbook `docs/runbooks/real-risk.md` (recusas, pausa por perda, reserva vencida, lista de bloqueio, silenciar o alerta de trabalho parado no rollback), `code-summary.md` (com a troca da forma do alerta U3-NFR5.12 e o namespace), `traceability.json` e `source-manifest.json`.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US2.1 (regra do teto) | 3, 4, 7 |
| US6.1 | 4, 6, 7, 8 |
| US6.2 | 3, 4, 7 |
| US6.3 (recusa da ordem) | 3, 4, 7 |

## Fora desta Unit

Fica para outras Units:
- **U4:** a chamada da M5 pelo ciclo e pelo consumidor da fila, a cotação de 10 s e a liquidação "vender tudo e sacar".
- **U5:** a rota do administrador para a lista de bloqueio.
- **U6:** a tela do teto.

A etapa do `promtool` no `ci.yml` é do responsável do projeto.
