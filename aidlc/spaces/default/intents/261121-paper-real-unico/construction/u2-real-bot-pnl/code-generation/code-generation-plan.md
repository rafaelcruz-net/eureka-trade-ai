# Plano de geração de código — U2 `u2-real-bot-pnl`

## Summary

- Builds: P&L REAL por bot pelo ledger na ordem de rede (slot na confirmação, custo médio único pela soma dos titulares, caminho só de quantidade, rateio pelo maior resto, linha "Sem bot", taxas, período e conferência exata), em duas fases com o ponto de controle interno.
- Touches: `apps/api` (ledger, pnl, riskcontrol.real, bots.worker, decision, realexecution, realexits, botwallet, audit, shared; migrações `0023` e `0024`), `apps/web/src/features/pnl`, `infra/observability`, `infra/terraform/modules/observability`, `docker-compose.yml`, `docs/runbooks`, `docs/perf`, `scripts/load` e os textos de design e de contrato (A1 a A16).
- Tests: cerca de 280 testes novos ou alterados (unidade escritos antes do código no núcleo financeiro, propriedade com pelo menos 1.000 sequências, integração com Testcontainers, ArchUnit, contrato OpenAPI, Jest e `node:test`), com piso de 80% por parte.

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

- `construction/u2-real-bot-pnl/functional-design/`: `rules.md` (BR1.1 a BR10.5, com as decisões do fundador D1 a D3), `functional-spec.md` (F1 a F9), `entities.md` (ENT-201 a ENT-215) e `traceability.json` (50 ACs).
- `construction/u2-real-bot-pnl/nfr-requirements/` (NFR1.1 a NFR8.14, D-NFR1 a D-NFR3; o NFR1.9 tem o texto pronto do `load-test.yml`).
- `construction/u2-real-bot-pnl/nfr-design/`: decisões F-1 a F-4, o tipo selado `RealCost`, o prazo duro do fluxo (`ReadDeadline`), o pool `pnl-mismatch-audit`, o plano em duas fases e a lista de alinhamentos A1 a A14 (`logical-components.md`).
- `construction/u2-real-bot-pnl/infrastructure-design/`: D-INF1 e D-INF2, as migrações `0023` (índice `CONCURRENTLY`) e `0024`, as chaves novas, a conta de conexões, A15 e A16, as seções 8 e 9 do runbook.
- `inception/contract-design/contract-summary.md` (C10 a C14) e `inception/units-generation/unit-of-work.md` (U2, ponto de controle interno, divisão U2a/U2b).
- `construction/u1-mode-skeleton/code-generation/code-summary.md` (o que a U1 construiu e as pendências A6 a A8).
- `aidlc/spaces/default/memory/team.md` e `project.md`.
- Código lido: `ledger.api.RealLedger`, `RealPosition`, `JdbcRealLedger`, `RealPositions`, `pnl.api` (C10), `RealBotPnlQueryV1`, `RealPnlCalculator`, `RealPnlService`, `RealPnlController`, `PnlModeMetrics`, `RealGateUnavailable`, `RealGateInputsReader`, `RealRiskPolicy`, `RealRiskJobs`, `RealCycle`, `decision.domain.PositionInput`, `LiquidationJob`, `RealOrderLedger`, `FillPosting`, `JdbcWorkerStores`, `botwallet.api.RealChain` (`TxStatus` e `SwapEffect` sem slot), `ChainPort`, `RpcChainWatcher` (as respostas de `getSignatureStatuses` e `getTransaction` já trazem o slot), `RealExecutionPort`, `AuditActions`, `RealReferences`, `WalletHasher`, `RuntimeSettingsGuard`, `apps/web/src/features/pnl/mode-messages.ts`, `scripts/load/`.

## Ordem aplicada

O Testing Contract acima é `custom`:

- **Testes escritos antes do código** (vermelho → verde → refatorar), a partir das regras e dos exemplos em unidades base. Vale para toda regra de dinheiro e da lógica REAL:
  - a tabela só de inserção e o `recordSlot` (ledger sem edição, idempotência do slot);
  - a chave de ordem de rede, a herança e o determinismo (BR1.x);
  - o caminho só de quantidade e o tipo `RealCost` (BR2.5, F-3);
  - o `RealCostBasis` (BR2.1 a BR2.4), com os exemplos de AC3.2.1, AC3.2.2, AC3.4.2 e da Q1;
  - a paridade com os três oráculos (BR9.1, AC5.2.1, AC5.2.2);
  - a falha fechada da barreira e do ciclo com a atribuição falhando e a fotografia adiada (BR2.7, F-4);
  - o rateio (BR4), a linha "Sem bot" (BR5), as taxas (BR6), o resultado (BR7), a conferência (BR8);
  - todos os exemplos das histórias e os cenários do FR5.4 (BR9.4), a fronteira do período (BR9.5), a regressão da condicional que falha antes contra o cálculo da U1 (BR9.3) e o teste de propriedade com pelo menos 1.000 sequências (BR9.2).
- **Teste depois da camada**: configuração, pool e escritor da auditoria limitada, métricas e logs, contrato e textos da tela, IaC e alarme, runbook e cenário de carga.
- O runner é conferido antes do primeiro teste (Etapa 1).
- **Fases (NFR Design, `reliability-design.md`):** Etapa 0, a linha de base da barreira, num commit próprio; **fase 1** (Etapas 2 a 15), que termina no ponto de controle interno; **fase 2** (Etapas 16 a 28).
- **Commits.** Nenhum commit é feito por esta geração sem o pedido do responsável (`team.md`). Os commits das Etapas 0 e 15 e o da troca da fórmula (Etapas 19 a 23, que entram juntas, NFR7.5) são pedidos a ele.
- **Desvios da lista do despacho, mantidos pelo design aprovado:**
  - a leitura de custo com atribuição dos caminhos `GATE` e `CYCLE`, com o prazo duro de 250 ms (`ReadDeadline`), e a fotografia adiada (`0024`, F-4) ficam na **fase 1**. O F9 e o `reliability-design.md` (item 6 dos sete) os põem no ponto de controle interno, e sem o F-4 a barreira com custo `Unknown` gravaria a fotografia do dia depois de uma queda (revisão 02, R-11). Na fase 2 fica a atribuição do caminho `PNL` (500 ms);
  - os alinhamentos A1 a A16 ficam todos na fase 2 (Etapa 27), como o despacho pede; o `logical-components.md` preferia aplicá-los na fase de cada código. São só textos.

## Etapas

### Etapa 0 — Linha de base da barreira (commit próprio, antes de qualquer código da U2)

- [x] Rodar o `RealRiskMeasurementIT` 3 vezes no último commit antes da U2, na máquina que depois mede a U2.
- [x] Criar `docs/perf/u2-barrier-baseline.md`: mediana dos 3 p95 de compra e de venda, SHA e máquina.
- [ ] Pedir ao responsável o commit só desse arquivo. Nenhum commit que mude `RealGateInputsReader`, `JdbcRealLedger.positions` ou `view` entra antes dele.
- Cobre: NFR1.5, NFR1.7.

### Etapa 1 — Conferir os runners e gravar os comandos da Unit

- [x] Conferir que `./apps/api/mvnw -q -f apps/api/pom.xml test -Dtest='LedgerPropertyTest'` e `./apps/api/mvnw -q -f apps/api/pom.xml verify -Dit.test='RealLedgerPositionsIT' -Dtest=none` rodam.
- [x] Conferir que `pnpm --dir apps/web exec jest src/features/pnl/PnlModeBlock.test.tsx` e `node --test scripts/load/load-test-u6-plans.test.mjs` rodam.
- [x] Os comandos exatos ficam em `unit-test-instructions.md`.

## Fase 1 — slot, custo único, caminho só de quantidade e paridade

### Etapa 2 — Testes antes do código: migração `0023` (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/ledger/db/RealEntrySlotAppendOnlyIT.java`: UPDATE, DELETE e TRUNCATE em `real_entry_slot` falham em 3 de 3, e a linha continua igual.
- [x] `ledger/db/RealEntrySlotGrantsIT.java`: com o login da aplicação, SELECT e INSERT funcionam; UPDATE, DELETE e TRUNCATE são recusados.
- [x] `db/Migration0023AdditiveIT.java`: contagem de `real_ledger_entry` e `real_order` igual antes e depois; colunas, gatilhos e permissões das duas tabelas iguais; o texto da migração sem `ALTER`, `UPDATE`, `DELETE` nem `INSERT`.
- [x] `db/Migration0023IndexValidIT.java`: `indisvalid` e `indisready` verdadeiros para `real_ledger_entry_wallet_created_idx`.
- [x] `db/Migration0023IndexRestartIT.java`: índice marcado inválido e linha do changeset apagada; o `MigrationRunner` de novo dá saída 0, índice válido e uma única linha do changeset.
- [x] `db/U2DatabaseRolesIT.java`, parte da `0023`: a matriz de `real_entry_slot` é `SELECT, INSERT`.
- [x] `db/MigrationsIT.java`: `everyExpectedTableExists` passa a esperar `real_entry_slot`.
- Cobre: BR1.4, BR1.5, BR1.7, NFR1.18, NFR2.3, NFR3.4, NFR3.5, NFR3.6.

### Etapa 3 — Migração `0023` (verde)

- [x] `apps/api/src/main/resources/db/changelog/changes/0023-real-entry-slot.sql`, com os quatro changesets do `infrastructure-specification.md`:
  - `0023-real-entry-slot` (tabela da C14);
  - `0023-real-entry-slot-append-only` (gatilhos com `ledger_reject_change`);
  - `0023-real-entry-slot-privileges` (bloco `DO` da `0018`/`0019`, matriz `SELECT, INSERT`, `REVOKE TRUNCATE` do dono);
  - `0023-real-ledger-entry-wallet-created-idx` (`DROP INDEX CONCURRENTLY IF EXISTS` e `CREATE INDEX CONCURRENTLY`, `runInTransaction:false`, `splitStatements` padrão).
- [x] Cada changeset com exatamente um `--rollback`; `apps/api/src/main/resources/db/changelog/db.changelog-master.yaml` inclui a `0023` depois da `0022`.
- [x] `db/AnonymizationMigrationIT.java` continua verde, desfazendo e reaplicando a `0023`.
- Cobre: BR1.5, BR1.7, NFR1.18, NFR1.19, NFR2.3, NFR3.4 a NFR3.6, NFR8.6.

### Etapa 4 — Testes antes do código: `recordSlot` nos três gravadores (vermelho)

- [x] `apps/api/src/test/java/com/memetrade/ledger/RecordSlotIT.java`, nos três gravadores (confirmação da ordem REAL em `CONFIRMED` e em `FAILED` com transação, `FillPosting` da condicional, confirmação de depósito, saque e taxa da carteira do bot):
  - `RECORDED`, `ALREADY_RECORDED` e `CONFLICT_REFUSED`;
  - a ordem ou o movimento chega ao estado final e os lançamentos ficam gravados nos três casos;
  - slot desconhecido na confirmação: nada gravado;
  - o conflito nunca lança para fora.
- [x] `audit/SlotConflictAuditIT.java`: o evento `REAL_SLOT_CONFLICT` guarda `txSignatureAbbrev` (4 … 4) e `txSignatureHmac` igual a `WalletHasher.hmacOfTxSignature`; a assinatura completa não aparece em nenhuma coluna de `audit_outbox` nem de `audit_event`; o evento leva o `correlationId` da confirmação.
- [x] `audit/infra/WalletHasherTest.java`: o HMAC da assinatura é o da mensagem com a etiqueta `"real-tx-signature:"`, difere do HMAC sem ela e recusa texto que não é assinatura Base58 de 64 bytes, sem ecoá-lo.
- [x] `ledger/infra/LedgerRealSlotMetricsTest.java`: um conflito na confirmação da ordem dá `writer="ORDER_CONFIRMATION"` = 1, contado só depois do commit; as 3 séries começam em 0.
- Cobre: BR1.4, ENT-202, ENT-215, NFR1.21, NFR2.13, NFR3.9, NFR3.10, NFR3.13.

### Etapa 5 — `recordSlot` (verde)

- [x] `ledger/api/RealLedger.java`: `RecordSlotResult recordSlot(String txSignature, long slot)`; `ledger/api/RecordSlotResult.java` (novo).
- [x] `ledger/infra/JdbcRealLedger.java`: `INSERT ... ON CONFLICT (tx_signature) DO NOTHING`, leitura pela chave primária, evento de conflito no outbox na mesma transação.
- [x] `ledger/infra/LedgerRealSlotMetrics.java` (novo): `ledger_real_slot_conflicts_total{writer}` em `afterCommit` e `ledger_real_slot_missing_total`, registradas com 0 na subida.
- [ ] O slot da confirmação, **sem chamada nova à rede**, lido das respostas que já existem (`getSignatureStatuses` e `getTransaction` trazem `slot`):
  - `botwallet/api/RealChain.java`: `TxStatus` e `SwapEffect` ganham `OptionalLong slot`;
  - `botwallet/domain/ChainPort.java`: `IncomingTransfer` e a leitura do estado do saque ganham `OptionalLong slot`;
  - `botwallet/infra/chain/RpcChainWatcher.java` e `botwallet/application/RealChainAdapter.java`: preenchem o campo;
  - dublês de teste `realexecution/FakeRealChain.java`, `botwallet/RecordedChain.java` e `botwallet/domain/FakeChain.java`.
- [x] Gravadores:
  - `realexecution/infra/RealOrderLedger.java` (`settle`, usado por `ConfirmationJob`, `OrderSettlement` e `AmbiguousJob`);
  - `realexits/application/FillPosting.java`;
  - `botwallet/infra/JdbcWorkerStores.java` (depósito, saque e taxa).
- [x] Auditoria: `audit/api/AuditActions.java` (`REAL_SLOT_CONFLICT`); `audit/api/RealReferences.java` (`hashedTransaction`, nunca serializado); `audit/infra/JdbcAuditEventPublisher.java` (troca pelos dois campos de `details`); `audit/infra/WalletHasher.java` (`hmacOfTxSignature`).
- [x] Log `WARN` `event=REAL_SLOT_CONFLICT` com a assinatura abreviada por `WalletDisplay.abbreviate`.
- [x] Refatorar com os testes verdes.
- Cobre: BR1.4, F7, C13, D-NFR2, NFR1.21, NFR2.13, NFR3.9, NFR3.10, NFR3.12.

### Etapa 6 — Testes antes do código: chave de ordem de rede e leitura em fluxo (vermelho)

- [x] `ledger/api/NetworkOrderKeyTest.java`: a ordem total da BR1.1 (slot efetivo; gravadas antes das herdadas; assinatura por bytes, BR1.2; depois `createdAt`, `reference`, `kindRank`, `idempotencyKey`; nunca `entryId`).
- [x] `shared/jdbc/ReadDeadlineTest.java`: relógio falso; passou do limite numa linha, lança `ReadDeadlineExceeded`; o `statement_timeout` reaplicado é o tempo que resta.
- [x] `ledger/RealLedgerNetworkOrderIT.java`:
  - AC3.6.1 (compra de B com assinatura menor antes da venda de A no mesmo slot);
  - AC3.6.2 (gravar as linhas em outra ordem dá a mesma chave);
  - herança da BR1.3 sobre todas as linhas, com o slot 0 antes do primeiro gravado e o exemplo "mesmo slot sem slot gravado";
  - `slotStatus` `RECORDED`, `SLOT_MISSING` e `NO_NETWORK_TX`;
  - filtro de `kinds` depois da herança;
  - `ledger_real_slot_missing_total` conta 3 em 3 linhas `SLOT_MISSING`;
  - carteira de outro tenant: nada; nunca PAPER;
  - linhas antigas sem slot: o resultado é igual ao do fallback de herança (NFR2.4);
  - o oráculo ordena os textos por bytes e confere a ordem do SQL (`COLLATE "C"`).
- [ ] `ledger/RealLedgerNetworkOrderPlanIT.java`: 2 pessoas na densidade do NFR1.14, `ANALYZE`, `EXPLAIN (FORMAT JSON)` do SQL exato; exige `real_ledger_entry_wallet_created_idx`, nenhum `Seq Scan` em `real_ledger_entry`, `Sort Method` diferente de `external merge`, `fetchSize` 500; o mesmo para o SQL do `view`.
- Cobre: BR1.1, BR1.2, BR1.3, BR1.5, BR1.6, AC3.6.1, AC3.6.2, NFR1.15, NFR1.18, NFR2.4, NFR2.10, NFR2.12, NFR4.3.

### Etapa 7 — Chave de ordem de rede e `streamInNetworkOrder` (verde)

- [x] `ledger/api/NetworkOrderKey.java`, `NetworkOrderedEntry.java` e `SlotStatus.java` (novos). O desempate final é `(createdAt, reference, kindRank, idempotencyKey)` (BR1.1, F-1).
- [x] `ledger/api/RealLedger.java`: `streamInNetworkOrder(tenantId, botWalletIds, kinds, consumer)` e uma sobrecarga com `ReadDeadline`.
- [x] `ledger/infra/JdbcRealLedger.java`: SQL numa constante de pacote, com junção à esquerda em `real_entry_slot`, funções de janela da herança, `COLLATE "C"` em todo desempate de texto, filtro de tenant no SQL; `JdbcTemplate` com `fetchSize` 500 e `RowCallbackHandler` que confere o prazo em cada linha.
- [x] `shared/jdbc/ReadDeadline.java` e `ReadDeadlineExceeded.java` (novos): instante limite pelo `Clock` injetado e `set local statement_timeout` com o tempo que resta antes de cada busca de 500 linhas. Sem vigia e sem `Statement.cancel()`.
- [x] Refatorar com os testes verdes.
- Cobre: BR1.1 a BR1.3, BR1.5, BR1.6, C12, ENT-201, ENT-203, NFR1.12, NFR1.15, NFR2.10, NFR2.12.

### Etapa 8 — Testes antes do código: caminho só de quantidade e `RealCost` (vermelho)

- [x] `ledger/domain/RealCostMethodQuantityTest.java`: IN soma; OUT tira `min(vendido, corrente)`; nunca negativo; venda além do que há; posição zerada some.
- [x] `ledger/api/RealCostTest.java`: `Known` recusa valor negativo; `Unknown` leva o motivo (`NOT_READ`, `TIMEOUT`, `ERROR`, `UNKNOWN_BOT`, `INCONSISTENT_READ`); nada converte `Unknown` em 0.
- [x] `ledger/RealLedgerPositionsIT.java` (alterado):
  - `positions` e `view` trazem a quantidade na ordem de rede, com `cost = Unknown(NOT_READ)`;
  - `view` traz `walletRowCount` com o mesmo filtro da seleção do fluxo;
  - 0 leituras de C13 e de C8.
- [x] `ledger/RealLedgerIT.java`: `tokenBalances` continua igual.
- Cobre: BR2.5, BR2.8, F6.1, F-3, NFR1.11.

### Etapa 9 — `RealCostMethod`, `RealCost` e o caminho só de quantidade (verde)

- [x] `ledger/api/RealCostMethod.java` (porta pura, C11): o caminho só de quantidade e o acumulador de custo por titular, que recebe eventos já atribuídos. `ledger/api/CostFill.java` com o titular no caminho de custo.
- [x] `ledger/api/RealCost.java` (selado `Known(long)` ou `Unknown(RealCostUnknownReason)`) e `RealCostUnknownReason.java` (novos).
- [x] `ledger/api/RealPosition.java`: `(String mint, long tokenBaseUnits, RealCost cost)`; o `long costLamports` sai.
- [x] `ledger/domain/RealPositions.java`: o algoritmo de custo próprio sai; fica a quantidade.
- [x] `ledger/infra/JdbcRealLedger.java` e `ledger/api/RealLedgerView.java`: `positions` e `view` pelo caminho só de quantidade, na ordem de rede, num comando; `walletRowCount`.
- [x] Compilar os leitores de quantidade sem custo: `LiquidationJob`, `DecisionConsumer`, `SigningSessionService` e `ConditionalService` usam só `tokenBaseUnits`.
- [x] `LedgerArchitectureTest.theLedgerNeverDependsOnThePnl` verde, sem exceção.
- Cobre: BR2.5, BR2.8, C11, ENT-206, F-3, NFR1.11, NFR2.5.

### Etapa 10 — Testes antes do código: `RealCostBasis` e a paridade (vermelho)

- [x] `pnl/real/RealCostBasisTest.java`, em unidades base:
  - AC3.2.1 (A compra 100 por 100.000.000 e 100 por 300.000.000: 200 e custo 400.000.000);
  - AC3.2.2 (A vende 50 por 150.000.000: custo retirado 100.000.000, realizado +50.000.000);
  - AC3.2.3 (B é outro titular);
  - AC3.4.2 (condicional em parte: custos retirados 600.000.000 e 500.000.000);
  - exemplo Q1 (3 unidades com custo 10: retira 4, 3 e 3);
  - BR2.1 (taxa fora do custo, RENT da compra dentro), BR2.3 (titular nunca vende mais do que tem), compra sem bot resolvido para UNOWNED, quantidade zerada com custo 0;
  - o rateio só de quantidade de uma venda sem bot e do excedente (BR4.1, BR4.3, BR4.4), que o custo da fase 1 precisa (`reliability-design.md`, suposição 1).
- [x] `pnl/real/RealCostParityTest.java` (BR9.1), com os três oráculos escritos no próprio teste, sem `RealCostBasis` nem `RealProration`:
  - (1) quantidade bruta igual ao caminho só de quantidade, à soma dos titulares e ao `tokenBalances`;
  - (2) conservação do custo;
  - (3) recálculo do custo médio por bot com a regra da Q1 reescrita no teste;
  - (4) `positions()` e `view()` trazem as quantidades de (1), e a leitura de custo traz a soma dos titulares.
  
  Conferido depois de cada evento e no fim, com diferença tolerada 0. Inclui AC5.2.1, AC5.2.2 e vendas próprias de bots com custos médios diferentes (o exemplo da decisão D1).
- Cobre: BR2.1 a BR2.4, BR2.6, BR3.1, BR9.1, AC3.2.1 a AC3.2.3, AC3.4.2, AC5.2.1, AC5.2.2, NFR2.8.

### Etapa 11 — `RealCostBasis` (verde)

- [x] `pnl/real/RealCostBasis.java` (novo): implementa o acumulador de custo por titular de `RealCostMethod`; teto no custo retirado de venda parcial, custo restante na última venda; soma dos titulares por token.
- [x] `pnl/real/Holder.java` (titular `BOT` ou `UNOWNED`, ordem de desempate da BR4.2) e o rateio só de quantidade pelo maior resto, em `long` exato e `BigInteger`.
- [x] Configuração em `pnl/infra/`: bean do `RealCostMethod` nos processos API e worker.
- [x] Refatorar com os testes verdes.
- Cobre: BR2.1 a BR2.4, BR2.6, BR3.1, BR4.1 (quantidade), C11, ENT-205, ENT-206, NFR2.5.

### Etapa 12 — Testes antes do código: barreira, ciclo e liquidação com a leitura de custo (vermelho)

- [x] `riskcontrol/real/AttributionFailureGateIT.java`, na transação de uma rodada e na do ciclo, com erro, `TIMEOUT` por `57014` (`pg_sleep` acima de 250 ms), `TIMEOUT` sem `57014` (relógio falso entre dois blocos de 500 linhas), `UNKNOWN_BOT` e `INCONSISTENT_READ`:
  - a mesma transação continua gravando; o comando seguinte não recebe `57014`; `statement_timeout` (5 s) e `work_mem` voltam ao valor de antes;
  - com custo `Unknown` e preço desconhecido, a compra é recusada sem nenhuma leitura de atribuição;
  - venda, liquidação e saque aceitos (3 de 3);
  - `real_risk_errors_total{dependency="attribution"}` sobe.
- [x] `bots/worker/RealCycleAttributionIT.java`: 0 decisões de compra do token afetado, 1 venda aceita, uma saída por preço decidida, uma saída por custo pulada com `COST_UNKNOWN`, ciclo sem erro.
- [x] `pnl/application/AttributedCostConsistencyIT.java`: lançamento inserido por outra conexão entre o `view` e a leitura dá `INCONSISTENT_READ`; ordem confirmada entre os passos 2 e 3 é atribuída ao bot certo pela leitura pontual.
- [x] `pnl/application/AttributedCostTenantIT.java`: a leitura de custo usa o tenant da ordem ou do bot e não lê nada de outro tenant.
- [x] `riskcontrol/real/DaySnapshotDeferredIT.java` (F-4): marca gravada sem fotografia; a primeira compra recusada com `DAY_SNAPSHOT_PENDING` sem gravar `FIRST_EVALUATION`; venda, liquidação e saque seguem; com custo `Known`, fotografia `DAY_JOB` com a base no maior entre cotação e custo; a perda aparece no `judge` e pausa com `DAILY_LOSS`; sem a marca, a primeira compra continua gravando `FIRST_EVALUATION`.
- [x] Testes de unidade alterados: `riskcontrol/real/domain/RealRiskPolicyTest.java` (`positionsValue` selado), `riskcontrol/real/infra/RealGateInputsReaderTest.java` (`buy` nunca pede custo; `value` troca `NOT_READ` pelo resultado), `riskcontrol/real/infra/RealRiskJobsTest.java` (`photo` e `judge` com valor `Unknown`), `bots/worker/RealCycleCostUnknownTest.java` (novo).
- [x] `architecture/RealCostArchitectureTest.java` (novo): só `riskcontrol.real`, `bots.worker` e `pnl` dependem de `RealCost.Known`; `realexecution` e `botwallet` não dependem de `RealCost`; `RealGateInputsReader.buy` não depende de `RealWalletCost`.
- Cobre: BR2.7, BR3.4 (barreira), F6.2, F-3, F-4, NFR1.11, NFR1.20, NFR3.1, NFR3.11, NFR7.3.

### Etapa 13 — Leitura de custo `GATE` e `CYCLE` e adaptação da barreira, do ciclo e da liquidação (verde)

- [x] C13: `realexecution/api/RealExecutionPort.java` (`streamSettledOrders`) e `realexecution/infra/JdbcRealExecutionStore.java` (`streamSettled`, por `real_order_user_created_idx`, sem `ORDER BY`, filtro por tenant e pessoa, `fetchSize` 500, com `ReadDeadline`); `RealExecutionService.java`.
- [x] `pnl/api/RealWalletCost.java`, `WalletCost.java` (selado) e `CostPath.java` (novos).
- [x] `pnl/application/AttributedCostReader.java` (novo): bots (C8), ordens (C13), lançamentos da carteira com todos os tipos (C12), acumulador; ponto de salvamento (`PROPAGATION_NESTED`), `work_mem` local, prazo duro de 250 ms nas duas camadas, valores restaurados no sucesso, `ROLLBACK TO SAVEPOINT` na falha; `expectedWalletRows` contra `walletRowCount`; leitura pontual da ordem e do bot antes de `ORDER_RECORD_MISSING` ou `UNKNOWN_BOT`.
- [x] `pnl/application/PnlRealMetrics.java` (novo): o temporizador `pnl_real_attribution_read_seconds{path,outcome}`, com as séries de `GATE` e `CYCLE` no worker. Log `WARN` `event=REAL_ATTRIBUTION_READ_FAILED`.
- [x] `pnl/infra/PnlRealProperties.java` (novo, `@ConfigurationProperties`, `@Validated`): `memetrade.pnl.real.attribution.gate-and-cycle-timeout` (250 ms, de 50 a 250) e `memetrade.pnl.real.read.work-mem` (12 MB, de 1 a 12), só no `application.yml`.
- [x] Barreira: `riskcontrol/api/RealGateUnavailable.java` (`ATTRIBUTION` e a fábrica `afterSavepoint` com `transactionUsable = true`); `riskcontrol/real/domain/PricedPosition.java` com `RealCost`; `riskcontrol/real/domain/RealRiskPolicy.java` (`positionsValue` selado); `riskcontrol/real/infra/RealGateInputsReader.java` (`buy` nunca pede custo; `value` pede `RealWalletCost` só com posição sem preço); `riskcontrol/real/infra/RealRiskMetrics.java` (`attribution`).
- [x] F-4: migração `db/changelog/changes/0024-real-risk-day-deferred.sql` (tabela, índice de expurgo e permissões com `risk_retention`, cada changeset com um `--rollback`) no `db.changelog-master.yaml`; `riskcontrol/real/infra/JdbcRealRiskStore.java` (marca `ON CONFLICT DO NOTHING`); `RealRiskJobs.java` (`photo` com a marca e o maior entre cotação e custo, `judge` sem pausar com `Unknown`); `RealRiskPurgeJob.java` (expurgo da marca); `riskcontrol/api/RealRejectionCode.java` ou o detalhe `DAY_SNAPSHOT_PENDING`; log `REAL_RISK_DAY_PHOTO_DEFERRED`; `MigrationsIT` e `U2DatabaseRolesIT` com `real_risk_day_deferred`.
- [x] Ciclo: `bots/worker/RealCycle.java` (`inputs` pede `RealWalletCost` no caminho `CYCLE`); `decision/domain/PositionInput.java` e `DecisionEngine.java` (custo desconhecido não decide compra; saídas por custo puladas com `COST_UNKNOWN`; PAPER sempre com custo conhecido); `decision/infra/DecisionJson.java`; log `REAL_EXIT_SKIPPED`.
- [x] `LiquidationJob`, venda da barreira e saque: sem mudança de comportamento; conferido pelos testes da Etapa 12.
- [x] Teste de ligação `config/U2PropertiesBindingIT.java` (as duas chaves desta fase; valor fora da faixa impede a subida).
- [x] Refatorar com os testes verdes.
- Cobre: BR2.6, BR2.7, BR3.4, C13, F6.2, F-3, F-4, NFR1.4, NFR1.11, NFR1.12, NFR1.20, NFR1.23, NFR3.1, NFR3.11, NFR8.6.

### Etapa 14 — Medição da fase 1 (registrada, sem portão na CI)

- [x] `riskcontrol/real/RealAttributionMeasurementIT.java` (novo): 21 carteiras com 10 posições, 2 sem preço; 2 anos na densidade do NFR1.14; 4 leituras de custo por segundo com lançamentos concorrentes; 10 aquecimentos e 100 medidas; p95 pelo posto mais próximo; taxa de `INCONSISTENT_READ`; a compra com posição sem preço medida sem leitura de atribuição.
- [x] `RealRiskMeasurementIT` 3 vezes na mesma máquina da Etapa 0. A comparação com as metas é do Build and Test (NFR1.8).
- Cobre: NFR1.4, NFR1.5, NFR1.7, NFR1.8, NFR1.26.

### Etapa 14a — Divisão U2a: desempenho da leitura de custo (decisão do fundador, 2026-11-22)

A medição da Etapa 14 acionou o gatilho da divisão: com 2 anos de histórico, a leitura de custo `GATE` levou p95 de 686 ms, com 100 de 100 em `TIMEOUT` (NFR1.4 pede 250 ms). A compra com posição sem preço levou p95 de 442 ms (NFR1.26 pede 400 ms). Além disso, o `RealLedgerNetworkOrderPlanIT` ficou vermelho com uma base de só 2 pessoas. O fundador escolheu dividir a U2:
- **U2a:** a fase 1 mais esta etapa, até o ponto de controle interno.
- **U2b:** a fase 2, que só começa depois do ponto de controle verde da U2a.

As duas ficam registradas como duas entregas com ponto de controle dentro desta Unit, para não reabrir o desenho já aprovado.

- [x] **Testes antes do código (vermelho):**
  - Em `pnl/real/RealCostBasisEpochTest.java` (novo): o custo calculado lendo só desde a última vez em que a quantidade de cada titular e mint chegou a zero (a "época") é igual ao custo calculado com o histórico inteiro, em cenários com várias zeragens, rateios, excedente, condicional, RENT e `SLOT_MISSING`.
  - Em `RealCostParityTest`, um quarto oráculo: custo por época igual ao custo pelo histórico completo, com semente fixa e pelo menos 400 eventos.
- [x] **Leitura por época (verde):**
  - A leitura de custo (`GATE`, `CYCLE` e, mais tarde, `PNL`) começa no primeiro lançamento depois da última zeragem da posição da carteira em cada mint, na ordem de rede. Antes desse ponto o custo é zero, então nada se perde.
  - O ponto de início vem de uma consulta pelo índice `real_ledger_entry_wallet_created_idx`, sem tabela nova e sem gravar custo, mantendo a regra de calcular na leitura.
  - Quando o ponto não pode ser achado dentro do prazo, o custo continua `Unknown(TIMEOUT)`, como hoje.
  - Os lançamentos sem slot (`SLOT_MISSING`) seguem a mesma ordem de reserva.
- [x] **`RealLedgerNetworkOrderPlanIT` com uma base realista:** semear 20 outras pessoas com o volume de referência, para que a carteira medida seja uma fração pequena da tabela. O critério do teste (índice usado pelo nome e nenhum `external merge`) não muda. Se mesmo assim o planejador não usar o índice, a consulta é ajustada, nunca o critério.
- [x] **Medição de novo** (`RealAttributionMeasurementIT`, 3 execuções): a leitura `GATE` precisa ficar em p95 ≤ 250 ms com `TIMEOUT` ≤ 0,5%, e a compra com posição sem preço em p95 ≤ 400 ms. Se não ficar, a U2a para e o resultado volta ao fundador.
- [x] O `RealAttributionMeasurementIT` roda num banco próprio (Testcontainers dedicado), para o milhão de linhas não deixar mais lentos os outros ITs da mesma JVM.
- Cobre: NFR1.4, NFR1.6, NFR1.18, NFR1.26, F9.

### Etapa 14b — Marca de zeragem para limitar a leitura da compra (decisão do fundador, 2026-11-22)

A Etapa 14a resolveu a leitura de custo `GATE` (p95 de 82 ms, sem `TIMEOUT`), mas a compra com posição sem preço ficou em p95 de 490 ms contra 400 ms, porque o `view` da barreira ordena todo o histórico da carteira sob a `WalletLock`. Achar a última zeragem com exatidão exige percorrer esse histórico, por causa do mínimo da BR2.5, da herança de slot da BR1.3 e das confirmações gravadas fora de ordem. Por isso o fundador aprovou uma marca de zeragem gravada. Ela é só de inserção, guarda um dado derivado da posição (não é custo) e é conferida contra o recálculo completo.

- [x] **Testes antes do código (vermelho).** Equivalência entre o `view` restrito pela marca e o `view` completo: quantidades, lotes abertos, entradas de custo e ordem de herança iguais. Os casos são:
  - várias zeragens;
  - `SLOT_MISSING`;
  - empates no mesmo slot;
  - um mint que nunca zera;
  - RENT;
  - saques;
  - venda maior que a posição;
  - confirmação gravada fora de ordem.

  O oráculo de paridade ganha a conferência "a marca é igual ao ponto de zeragem achado pelo recálculo completo".
- [x] **Migração `0025-real-position-epoch.sql` (aditiva).** Tabela `real_position_epoch`, com a carteira, o mint, a chave de ordem de rede da zeragem e o instante. Ela é só de inserção: tem o gatilho `ledger_reject_change`, o papel da aplicação só tem SELECT e INSERT, e as permissões vêm do bloco `DO` das migrações 0018 e 0019. O dono é o `ledger`.
- [x] **Gravação da marca.** A marca é gravada na mesma transação do lançamento que zera a quantidade da carteira num mint, ou seja, nos três gravadores do ledger, com a quantidade calculada pela BR2.5. Uma confirmação fora de ordem que invalida uma marca não altera nem apaga a marca anterior: ela grava uma marca nova ou faz o `view` cair para a leitura completa daquele mint, com o motivo registrado. Nunca há UPDATE nem DELETE.
- [x] **`view` restrito.** O `view` lê cada mint a partir da última marca válida, mais a âncora de herança: o último slot gravado antes da marca, buscado pelo índice. Um mint sem marca lê o histórico inteiro, que fica documentado e medido como pior caso. O `RealEpochs` continua sendo a fonte única.
- [x] **`RealLedgerNetworkOrderPlanIT` verde,** com o mesmo critério. Rever o `work_mem` do `view` e voltar ao padrão onde der.
- [x] **Medir de novo (3 execuções).** Metas:
  - `GATE`: p95 ≤ 250 ms e `TIMEOUT` ≤ 0,5%;
  - compra com posição sem preço: p95 ≤ 400 ms;
  - compra normal, com tudo cotado: p95 ≤ 150 ms e o limite da Etapa 0.

  Se alguma meta falhar, a execução para e volta ao fundador.
- [x] **Compra normal (decisão do fundador, 2026-11-23).**
  - O teto absoluto da compra normal, com tudo cotado, passa a ser **p95 ≤ 500 ms**. Ele substitui os 150 ms herdados do 261113, que já eram excedidos antes da U2 nesta máquina (base de 206 ms).
  - O limite relativo é decidido por uma comparação direta intercalada, na mesma máquina e na mesma sessão. O código da Etapa 0 (`30017d4`) e o código atual rodam alternados, pelo menos 3 vezes cada. Passa se a mediana atual ficar dentro de +10% da mediana anterior.
  - O alinhamento do NFR1.5 (teto de 500 ms) fica para o Build and Test.
- Cobre: NFR1.4, NFR1.5, NFR1.26, NFR3.5, F9.

### Etapa 15 — Ponto de controle interno da fase 1

- [ ] Os sete itens do `reliability-design.md` no mesmo commit:
  1. `0023` com os três testes de migração;
  2. `recordSlot` nos três gravadores, com o evento abreviado e com HMAC e a métrica;
  3. `streamInNetworkOrder` com o teste de AC3.6.2 e o `RealLedgerNetworkOrderPlanIT`;
  4. `RealCostMethod`, `RealCost` e o caminho só de quantidade, com os usuários adaptados;
  5. `RealCostBasis`;
  6. a leitura de custo `GATE` e `CYCLE`, a `0024` (F-4) e os testes da Etapa 12;
  7. `RealCostParityTest` verde, e os testes da barreira REAL e os do 261113 verdes.
- [x] O C10 continua com o `RealBotPnlQueryV1` da U1: nenhuma tela muda.
- [ ] Pedir o commit ao responsável; conferir o `ci` verde (todos os jobs, inclusive `smoke`) e rodar `bash scripts/verify-checkpoint.sh`.
- [ ] **Gatilho da divisão.** Se o `ci` não ficar verde no commit dos sete itens mais, no máximo, um commit de correção, ou se a medição da Etapa 14 passar das metas de NFR1.4 ou NFR1.26 no Build and Test, a U2 é dividida (U2a, fase 1; U2b, fase 2, nova Unit dependente da U2a). A divisão é registrada neste plano e no estado do intent e volta ao fundador.
- Cobre: F9, NFR7.4, NFR8.4.

## Fase 2 — rateio, conferência e o resto

### Etapa 16 — Testes antes do código: `RealProration` (vermelho)

- [ ] `pnl/real/RealProrationTest.java`, em unidades base:
  - AC3.3.1 (liquidação com dois bots), AC3.3.2 (três bots com resto: A 388.888.889), AC3.3.3 (origem LIQUIDATION), AC3.3.4 (titular sem quantidade não entra);
  - AC3.4.1 (empate: recebido A 333.333.334; taxa A 1.667, B 1.667, C 1.666), AC3.4.3 (origem CONDITIONAL), AC3.4.4 (parte não executada não gera parte);
  - AC3.5.1 (excedente para B e C, SOLD_BY_OTHER_BOT), AC3.5.2 (excedente para UNOWNED com UNOWNED_EXCESS), AC3.5.3 (sem excedente, sem parte);
  - AC3.6.1 (excedente para B no mesmo slot);
  - exemplos Q2 (A 1, B 1), Q3 (UNOWNED_NO_COST) e D2 (B 25 e UNOWNED 25; realizado de "Sem bot" +220);
  - em todo evento, as partes somam o evento (BR8.4).
- Cobre: BR4.1 a BR4.8, BR5.2, BR5.3, AC3.3.1 a AC3.3.4, AC3.4.1, AC3.4.3, AC3.4.4, AC3.5.1 a AC3.5.3, AC3.6.1.

### Etapa 17 — `RealProration` (verde)

- [ ] `pnl/real/RealProration.java` (novo): maior resto com desempate da BR4.2; venda sem bot (BR4.3); venda própria com excedente entre todos os outros titulares (BR4.4, D2); recebido e taxa pela quantidade vendida (BR4.5); origem das partes (BR4.7); `ProrationPart` (ENT-208).
- [ ] Refatorar com os testes verdes.
- Cobre: BR4.1 a BR4.8, ENT-208.

### Etapa 18 — Testes antes do código: o cálculo do P&L REAL por bot (vermelho)

- [ ] `pnl/real/RealBotPnlExamplesTest.java` (BR9.4): cada exemplo de `rules.md` como está, em lamports e unidades base — AC3.2.1 a AC3.2.4, AC3.3.1, AC3.3.2, AC3.4.1, AC3.4.2, AC3.5.1, AC3.5.2, AC3.6.1, AC3.7.1 a AC3.7.4, AC3.8.1 a AC3.8.4, AC3.9.1 a AC3.9.3, AC5.1.1, AC5.3.1 e AC5.4.1 a AC5.4.2 — e os cenários do FR5.4 sem exemplo próprio (venda no mesmo slot com `SLOT_MISSING`, excedente sem custo).
- [ ] `pnl/real/RealPnlFeesTest.java`: BR6.1 a BR6.6, inclusive a ordem que falhou com taxa paga pela carteira do bot e pela carteira principal, a liquidação que falhou rateada pelas quantidades de antes, a taxa do modo Assinada nunca contada duas vezes e o RENT fora de compra.
- [ ] `pnl/real/RealPnlUnownedTest.java`: BR5.1 a BR5.5, inclusive `ORDER_RECORD_MISSING` em compra, venda e taxa.
- [ ] `pnl/real/RealPnlPeriodBoundaryTest.java` (BR9.5): fill 1 ms antes de `to`, taxa e RENT 1 ms depois; o evento entra inteiro no período, nada no seguinte; o mesmo com `from` e com a taxa do modo Assinada; o não realizado é sempre o de agora (AC7.2.1).
- [ ] `pnl/real/RealPnlReconciliationTest.java`: `MATCH` em `All` e em `Range`; cada verificação (`REALIZED`, `FEES`, `UNREALIZED`, `COST_CONSERVATION`, `QUANTITY_CONSERVATION`, `EVENT_SUMS`, `UNOWNED_WITHOUT_REASON`, `UNKNOWN_BOT`) falha sozinha com a diferença em lamports; igualdade exata, sem tolerância; o total do modo pelo lado do ledger (BR7.5).
- [ ] `pnl/real/ConditionalSaleRegressionTest.java` (BR9.3): o cenário de AC5.1.1 roda contra o `RealPnlCalculator` da U1 e **falha** (vermelho registrado); depois passa contra o cálculo novo.
- [ ] `pnl/real/RealPnlPropertyTest.java` (BR9.2), no padrão do `LedgerPropertyTest`: `java.util.Random` com 3 sementes fixas declaradas, pelo menos 1.000 sequências de 10 a 60 eventos por execução, com compras de bots, vendas próprias com e sem excedente, liquidações, condicionais inteiras e parciais, ordens que falham, taxas do modo Assinada, mesmo slot, `SLOT_MISSING`, compras sem bot, vendas além do registrado e bots removidos. Para cada uma: BR8.1 a BR8.5 dão `MATCH` em `All` e em períodos aleatórios; BR9.1 vale; duas execuções iguais; linhas com slot gravadas em outra ordem dão o mesmo resultado; nada negativo; posição zerada com custo 0. Semente e índice na mensagem de falha; ≤ 60 s na CI.
- [ ] `pnl/application/RealBotPnlTest.java` (dublês das portas): F1 por pessoa; F5 devolve só a linha do bot e vazio para bot que não é da pessoa; preços lidos uma vez, só para os mints com posição aberta (NFR1.13); bloco vazio sem bot nem carteira; teto `real-max-orders` no fluxo dá `TOO_MANY_ORDERS` sem número parcial (F-2).
- [ ] `pnl/application/RealBotPnlQueryTest.java` (da U1, alterado para a implementação nova): os casos que continuam valendo (cada bot com as próprias ordens, total, teto) e os que mudam (realizado bruto, `unowned` presente).
- Cobre: BR2.x, BR3.1 a BR3.4, BR4.x, BR5.1 a BR5.5, BR6.1 a BR6.6, BR7.1 a BR7.6, BR8.1 a BR8.5, BR9.2 a BR9.5, BR10.1, AC3.2.x, AC3.3.x, AC3.4.x, AC3.5.x, AC3.6.3, AC3.7.x, AC3.8.x, AC3.9.x, AC5.1.1 a AC5.1.3, AC5.3.1, AC5.3.3, AC5.3.4, AC5.4.1 a AC5.4.3, AC7.2.1, NFR1.13, NFR2.1, NFR2.2, NFR2.6, NFR2.7, NFR2.9.

### Etapa 19 — `RealBotPnl`, a implementação nova de C10 (verde)

- [ ] `pnl/real/`: montagem dos `TradeEvent` (ENT-207) a partir dos lançamentos agrupados por (carteira, referência, assinatura), com a tentativa virtual do modo Assinada; aplicação de F2.1 a F2.4; lado do ledger (F3, passo 1) acumulado por período pelo instante único do evento; não realizado com o valor rateado (BR7.2) e `unknown` (BR7.3); conferência (`ReconciliationResult`, ENT-213).
- [ ] `pnl/application/RealBotPnl.java` (novo, a "V2" de `RealBotPnlQuery`): uma passada por pessoa em REPEATABLE READ só de leitura, `set local work_mem`, ordens (C13) para o índice compacto com o teto `real-max-orders` no fluxo (F-2), lançamentos (C12), preços uma vez; atribuição `PNL` (passos 1 e 3) com o prazo de 500 ms dentro do prazo do bloco de 800 ms; períodos `All` e `Range`.
- [ ] `pnl/api/RealPnlUnavailable.java`: causa `RECONCILIATION_MISMATCH`. `pnl/api/UnownedPnlLine.java`: motivos `UNOWNED_EXCESS`, `UNOWNED_NO_COST` e `ORDER_RECORD_MISSING`.
- [ ] `pnl/infra/PnlRealProperties.java`: `memetrade.pnl.real.attribution.pnl-timeout` (500 ms, de 50 a 500, menor que `block-timeout`).
- [ ] Saem `pnl/application/RealBotPnlQueryV1.java`, `pnl/real/RealPnlCalculator.java` e `pnl/real/RealPnlCalculatorTest.java` (substituído pelos testes da Etapa 18). O `RealExecutionPort.settledOrders(..., max)` fica para os chamadores atuais.
- [ ] `GET /api/v1/real/pnl` (BR10.3): `pnl/application/RealPnlService.java` e `pnl/web/RealPnlController.java` passam a usar o total e as posições do `RealBotPnl`, **pelo pool** `pnl-mode-block` (`ModeBlockExecutor`, mercado REAL, 800 ms); a forma da resposta não muda; fila cheia dá o erro técnico que a rota já tem.
- [ ] `pnl/web/RealBlockSource.java`: a divergência vira `UNAVAILABLE` com `RECONCILIATION_MISMATCH`, sem número parcial.
- [ ] Refatorar com os testes verdes.
- Cobre: BR10.1, BR10.2, BR10.3, BR7.5, BR8.6, BR8.7, C10, F1 a F5, F8, F-2, D-NFR1, NFR1.1, NFR1.3, NFR1.12, NFR1.13, NFR1.15, NFR1.16, NFR2.1, NFR2.6.

### Etapa 20 — Testes de integração do bloco REAL (escritos antes de ligar o bloco à rota)

- [ ] `pnl/web/RealBlockFailureIT.java`, 7 casos (divergência, `UNKNOWN_BOT`, atribuição com erro, tempo esgotado na atribuição, tempo esgotado entre dois blocos de 500 linhas sem `57014`, `TOO_MANY_ORDERS`, fila cheia): 200 em todos, bloco PAPER igual ao de antes, a série certa de `pnl_mode_block_unavailable_total{market="REAL",cause}` sobe 1.
- [ ] `pnl/RealPnlTenantIsolationIT.java`: duas pessoas de tenants diferentes, mesmo mint e mesmo slot; nada da outra no P&L, na conferência nem no evento; bot de outra pessoa dá 404 sem campo do bot (AC3.2.6); `UNKNOWN_BOT` sem o id nem o nome do bot alheio.
- [ ] `pnl/RealPnlIdempotentReadIT.java`: 0 gravações em 3 leituras com `MATCH`; depois de uma divergência, a leitura seguinte refaz a conferência e volta a `PRONTO` quando a causa some.
- [ ] `pnl/RealNeverPaperIT.java`: com ordens nos dois modos, o fluxo entrega 0 lançamentos PAPER e a conferência REAL não muda com lançamentos PAPER; os testes da U1 de nunca somar os modos continuam verdes.
- [ ] `pnl/RealPnlReadOnlyIT.java`: C4 em laço em paralelo com avaliações da barreira e um `RealCycle`; 0 `FOR UPDATE` e 0 travas; a espera pela trava continua ≤ 1.000 ms; o ciclo termina sem `RealGateUnavailable` por trava (AC3.11.3).
- [ ] `pnl/web/RealPnlRouteIT.java`: `GET /api/v1/real/pnl` com o realizado bruto, a forma da resposta igual, o erro técnico na divergência e na fila cheia.
- [ ] `pnl/web/ModePnlFailureIT.java` e `pnl/web/ModePnlLatencyIT.java` (da U1) verdes, este com o p95 só registrado.
- Cobre: BR3.4, BR8.6, BR8.7, BR10.2, AC3.2.5, AC3.2.6, AC3.11.1 a AC3.11.3, AC5.3.2, AC5.3.5, NFR1.2, NFR1.10, NFR1.16, NFR1.22, NFR3.1 a NFR3.3, NFR3.8, NFR4.2, NFR4.3, NFR4.5.

### Etapa 21 — Auditoria limitada da divergência (teste depois)

- [ ] `audit/api/RateLimitedAuditPublisher.java` (novo, `publishOncePerHour(command, fingerprint, deadline)`, `WRITTEN`, `DEDUPLICATED`, `LOCK_BUSY` ou falha).
- [ ] `audit/infra/MismatchAuditDataSource.java` (Hikari de 2 conexões, `minimum-idle` 2, espera constante de 250 ms, `setMetricRegistry`, `setPoolName("pnl-mismatch-audit")`; não é bean `DataSource` nem `PlatformTransactionManager`; só com `MEMETRADE_PROCESS=api`) e `audit/infra/MismatchAuditWriter.java` (trava consultiva `0x504E4C4D`, recência no outbox pendente e em `audit_event` num comando, `insert` no outbox, tudo numa transação do pool próprio; reusa `AuditEventValidator` e `AuditPayloadCodec`).
- [ ] `pnl/application/ReconciliationAuditGate.java` (novo): impressão digital SHA-256 do texto canônico; executor de 2 threads e fila de 8; espera de no máximo o menor entre 200 ms e o que resta do bloco; tarefa vencida desiste; incrementa `pnl_real_reconciliation_mismatches_total{check}` e lança `RealPnlUnavailable(RECONCILIATION_MISMATCH)`.
- [ ] `audit/api/AuditActions.java`: `REAL_PNL_RECONCILIATION_MISMATCH`.
- [ ] `pnl/infra/PnlRealProperties.java`: `memetrade.pnl.mismatch-audit.pool-size` (2, de 1 a 2) e `write-timeout` (200 ms, de 50 a 200). `shared/config/RuntimeSettingsGuard.java`: ramo `api` confere o `pool-size`.
- [ ] `docker-compose.yml`: comentário da conta de conexões com os trechos novos, cada um numa linha (67 de 100; overlay do REAL com o worker de 37, 78 de 100). `infra/ComposeFilesTest.java`: 67 e a leitura do `DB_POOL_MAX_SIZE` do worker em `infra/docker-compose.real.yml` (78).
- [ ] Testes: `audit/ReconciliationAuditLimitIT.java` (10 leituras gravam 1 evento e sobem o contador 10 vezes; divergência diferente grava mais 1; duas threads gravam 1; publicação falhando muda nada na resposta; pool próprio preso: desiste em até 250 ms; pool principal preso: o evento é gravado; mesmo `pg_backend_pid()` na trava e no `insert`), `pnl/application/ReconciliationFingerprintTest.java`, `config/MismatchAuditPoolContextIT.java` (o worker sobe sem o bean; a prontidão não muda), `shared/config/RuntimeSettingsGuardMismatchPoolTest.java`, `architecture/AuditArchitectureTest.java` (só o `MismatchAuditWriter` depende do `MismatchAuditDataSource`; `pnl` usa só `audit.api`) e `config/U2PropertiesBindingIT.java` com as chaves desta fase.
- Cobre: BR8.7, F4, ENT-214, NFR1.16, NFR3.8, NFR3.15, NFR3.16, NFR4.4.

### Etapa 22 — Métricas e logs (teste depois)

- [ ] `pnl/modeview/PnlModeMetrics.java`: `pnl.mode_block.unavailable` com `market` e `cause` (`TIMEOUT`, `REJECTED`, `ERROR`, `RECONCILIATION_MISMATCH`, `TOO_MANY_ORDERS`), 10 séries com 0 na subida; `TIMEOUT` para qualquer parte do bloco. `ModeBlockExecutor` classifica a causa.
- [ ] `pnl/application/PnlRealMetrics.java`: `pnl_real_reconciliation_mismatches_total{check}` (8 séries), `pnl_real_reconciliation_audit_failures_total` e as séries `PNL` do temporizador, só na API.
- [ ] Logs `REAL_PNL_RECONCILIATION_MISMATCH` (`ERROR`) e `REAL_PNL_MISMATCH_AUDIT_FAILED` (`ERROR`), com `correlationId` do MDC, sem endereço, assinatura completa nem diferenças em lamports.
- [ ] Testes: `pnl/application/PnlRealMetricsTest.java`, `pnl/application/ModePnlViewTest.java` e `testsupport/AbstractPaperRealIT.java` (somam as séries de cada mercado), `pnl/U2MetricsExportIT.java` (as cinco métricas e `hikaricp_connections_pending{pool="pnl-mismatch-audit"}` em `/actuator/prometheus`), `pnl/U2LogCaptureTest.java` e `pnl/CorrelationParityIT.java` (o mesmo `correlationId` no bloco, no evento e no log).
- Cobre: NFR1.6 (contador do portão), NFR1.23, NFR1.24, NFR2.11, NFR2.12, NFR2.13, NFR3.7, NFR3.12, NFR3.13, NFR3.14, NFR3.16.

### Etapa 23 — Contrato e textos da tela, no mesmo commit da troca da fórmula (teste depois)

- [ ] `pnl/web/ModePnlDtos.java`: `allowableValues` de `unowned.reasons` com os três motivos; descrição das taxas sem "Na U1, só informação: já incluídas no realizado e no custo"; código `RECONCILIATION_MISMATCH`.
- [ ] Regenerar `apps/api/openapi/openapi-guest.yaml` e atualizar `contract/GuestOpenApiContractTest.java` (enum novo; nenhum campo de endereço nem de assinatura em C4 e em `GET /api/v1/real/pnl`; nenhum total geral).
- [ ] Regenerar `apps/web/src/lib/api/schema.d.ts` (`gen:api`) e conferir com `check:api`; nunca à mão.
- [ ] `apps/web/src/features/pnl/mode-messages.ts` (BR10.4): `fees` do bloco REAL vira "Taxas"; `unownedNote` sai, e `PnlModeBlock.tsx` deixa de mostrá-la.
- [ ] `apps/web/src/features/pnl/PnlModeBlock.test.tsx`: o rótulo "Taxas" e a ausência da nota; `fixtures.ts` com os motivos novos.
- [ ] `architecture/NeverSumModesArchitectureTest.java`: os tipos novos (`NetworkOrderedEntry`, `NetworkOrderKey`, `SlotStatus`, `RecordSlotResult`, `RealCost`, `WalletCost`, `ReconciliationResult` e os de `RealCostBasis` e `RealProration`) na lista fechada REAL; `architecture/ModeBoundaryArchitectureTest.java` e uma regra nova de que `ledger.infra` e `pnl` do REAL não dependem de tipos do ledger PAPER.
- [ ] `architecture/ArchitectureTest.java` (sem ponto flutuante) cobre os pacotes novos.
- Cobre: BR5.6, BR10.4, BR10.5, NFR3.8, NFR4.1 a NFR4.3, NFR7.5.

### Etapa 24 — Alarme, IaC e runbook (teste depois)

- [ ] `infra/observability/real-ops-alerts.yml`: `RealOpsPnlReconciliationMismatch`, `sum(increase(pnl_real_reconciliation_mismatches_total[4m])) > 0`, `critical`, `threshold: "0"`, `window_seconds: "240"`, comentário com o NFR2.14.
- [ ] `infra/terraform/modules/observability/real-ops.tf`: `aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch` (período 240, `notBreaching`).
- [ ] `infra/terraform/modules/observability/app-metrics.tf`: a métrica em `app_metric_declarations` (`dimensions = [[]]`), o nome em `alarm_resource_names` e o recurso em `metric_alarms`. `infra/terraform/main.tf` não muda (D-INF1).
- [ ] `botwallet/RealOpsAlarmParityTest.java`: `hasSize(14)`, `> 0` e `[4m]`; `infra/terraform/tests/observability_metrics.tftest.hcl` com o alarme novo.
- [ ] `docs/runbooks/real-operations.md`: seção "8. Divergência do P&L REAL" e seção "9. Índice da migração 0023" (os cinco passos do `cicd-pipeline.md`).
- [ ] À mão, antes do commit: `terraform validate`, `terraform test` e Checkov em `infra/terraform/` (pendência da CI do responsável).
- Cobre: NFR2.14, NFR2.15, NFR8.1, NFR8.2, D-INF1.

### Etapa 25 — Cenário de carga `pnl-by-mode` e o texto pronto do `load-test.yml` (teste depois)

- [ ] Semente só nos perfis `test,loadtest`: `apps/api/src/main/java/com/memetrade/pnl/loadtest/LoadtestPnlSeed.java` (20 pessoas, 5 bots PAPER e 5 REAL, 730 dias, 4 ordens por bot por dia e a mistura do NFR1.6, os primeiros 365 dias sem slot) e o guarda `architecture/LoadtestPnlSeedProfileGuardTest.java`, como o `RealSeedProfileGuardTest`.
- [ ] `scripts/load/pnl-by-mode.mjs` (tráfego e portões do D-NFR1), `scripts/load/pnl-plans.mjs` (procura o índice pelo nome; falha com `Seq Scan on real_ledger_entry`), `scripts/load/load-test.mjs` (cenário, leitura do contador `cause` antes e depois, relatório `docs/perf/u2-real-bot-pnl-<data>.md`) e `scripts/load-test.sh` (semente e duração).
- [ ] Testes: `scripts/load/load-test-pnl-by-mode.test.mjs`, `scripts/load/load-test-pnl-plans.test.mjs` e `pnl/PnlPlanSqlParityTest.java` (o SQL do `pnl-plans.mjs` é a constante do `JdbcRealLedger`).
- [ ] **Entregável do responsável:** o texto inteiro do `.github/workflows/load-test.yml` do NFR1.9 vai para o `code-summary.md`, com o comando de disparo. Esta geração **não edita** `.github/workflows/` nem `scripts/verify.sh`.
- Cobre: NFR1.1, NFR1.3, NFR1.6, NFR1.9, NFR1.14, NFR1.25, NFR7.8, D-NFR3.

### Etapa 26 — Ponta a ponta da fatia

- [ ] `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts` continua verde com o bloco REAL calculado pela U2: a linha do bot REAL aparece e nenhum total soma os modos. Ajustar só se uma asserção depender do realizado líquido da U1.
- Cobre: NFR7.6.

### Etapa 27 — Alinhamentos de texto A1 a A16

- [ ] A1: `functional-design/rules.md` BR2.7 e `functional-spec.md`, 800 ms → **500 ms** no caminho do P&L.
- [ ] A2: `functional-design/entities.md` ENT-215, `txSignatureAbbrev`, `txSignatureHmac`, `tenantId` e `correlationId`.
- [ ] A3: nomes das métricas em `functional-spec.md` F4 e F7, `rules.md` BR1.4 e BR8.7 e `contract-summary.md` C13.
- [ ] A4: `nfr-requirements/nfr-requirements-questions.md`, linha que aponta para D-NFR1 e D-NFR3.
- [ ] A5: `nfr-requirements/observability-requirements.md`, "as quatro" → "as cinco".
- [ ] A6, A7 e A8 (pendências da U1): `u1-mode-skeleton/nfr-design/performance-design.md`, `u1-mode-skeleton/nfr-requirements/scalability-requirements.md` NFR8.1 e `u1-mode-skeleton/functional-design/rules.md` BR1.9.
- [ ] A9: `rules.md` BR1.1 a BR1.3 e `contract-summary.md` C12 (ordem de gravação da herança e `COLLATE "C"`).
- [ ] A10: `functional-spec.md` suposição 3 e `performance-requirements.md` nota do `real-max-orders` (o teto fica, aplicado no fluxo).
- [ ] A11: `contract-summary.md` C11 e `entities.md` ENT-206 (`RealPosition` com `RealCost`; `WalletCost` selado).
- [ ] A12: `u1-mode-skeleton/nfr-requirements/observability-requirements.md` (etiqueta `cause`).
- [ ] A13: `261113-trading-real-bot/construction/u3-real-risk/functional-design/rules.md` BR4.4 (fotografia adiada, `DAY_SNAPSHOT_PENDING`).
- [ ] A14: `performance-requirements.md` NFR1.4 e NFR1.26.
- [ ] A15: `nfr-design/security-design.md` e `nfr-requirements/tech-stack-decisions.md` NFR8.6 (índice `CONCURRENTLY` com `runInTransaction:false`).
- [ ] A16: `nfr-requirements/scalability-requirements.md` e `nfr-design/scalability-design.md` NFR8.1 e a Q2 da infraestrutura (só o sidecar `metrics-agent` muda).
- [ ] BR5.6 (5): chaves `pnl.noBotReason.UNOWNED_EXCESS`, `UNOWNED_NO_COST` (proposta "Motivo: venda sem custo registrado") e `ORDER_RECORD_MISSING` em `inception/refined-mockups/design-system-mapping.md`, no lugar de `SURPLUS_WITHOUT_OWNER`.
- Cobre: BR1.7, BR2.8, BR5.6 e os alinhamentos do NFR Design e do Infrastructure Design.

### Etapa 28 — Verificação, documentação e rastreabilidade

- [ ] Rodar os comandos da Unit (`unit-test-instructions.md`): fase 1 e fase 2 do backend, Jest e `node:test`.
- [ ] `./mvnw verify` no `apps/api` com JaCoCo ≥ 80%; `lint`, `format:check`, `typecheck`, `jest --coverage` e `check:api` no `apps/web`. O piso nunca é reduzido.
- [ ] Escrever `code-summary.md` (com o texto pronto do `load-test.yml`, o comando de disparo e as pendências), `traceability.json` no formato do verificador (IDs `NFRx.y` sem o prefixo da Unit; uma linha por AC, BR e NFR; um único arquivo existente por alvo OK, os demais numa nota) e `source-manifest.json`.
- [ ] Verificação do ponto de controle: `bash scripts/verify-checkpoint.sh`.
- [ ] O Build and Test da U2 só fecha com as medianas de NFR1.4 e NFR1.5 dentro das metas e uma execução verde de `pnl-by-mode` no SHA candidato, depois que o responsável aplicar o texto do NFR1.9 (NFR1.25).
- Cobre: NFR7.1, NFR7.2, NFR7.3, NFR7.6, NFR1.25.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US3.2 (AC3.2.1 a AC3.2.4; AC3.2.5 e AC3.2.6 da U1 continuam verdes) | 10, 11, 18, 19, 20 |
| US3.3 | 16, 17, 18, 19 |
| US3.4 | 10, 16, 17, 18, 19 |
| US3.5 | 16, 17, 18, 19 |
| US3.6 | 4, 5, 6, 7, 16, 17, 18 |
| US3.7 | 18, 19 |
| US3.8 | 18, 19 |
| US3.9 | 18, 19, 20 |
| US3.11 | 7, 19, 20, 25 |
| US5.1 | 18, 19 |
| US5.2 | 8, 9, 10, 11, 12, 13, 15 |
| US5.3 (AC5.3.2 da U1 continua verde) | 18, 19, 20, 21, 22, 24 |
| US5.4 | 18, 19 |
| US7.2 (AC7.2.1; AC7.2.2 e AC7.2.3 são da U3) | 18, 19 |

## Riscos e rollback

- **Fase 1 atrasada (RK da U2).** O gatilho da divisão está na Etapa 15. Na fase 1 o P&L mostrado continua o da U1, e a U1 não vai para a mainnet antes da U2 (NFR8.4).
- **Slot na confirmação.** Hoje `RealChain` e `ChainPort` não expõem o slot. A Etapa 5 o tira das respostas que o RPC já devolve, sem chamada nova. Uma confirmação sem slot fica `SLOT_MISSING` e segue pela herança (BR1.3); o conflito nunca derruba a confirmação.
- **`RealPosition` muda de forma (F-3).** Todos os leitores de custo passam a tratar `Known` e `Unknown` pelo compilador; os de quantidade não dependem de `RealCost` (regra ArchUnit da Etapa 12). A compra falha fechada; venda, liquidação e saque nunca leem a atribuição.
- **Primeiro `CREATE INDEX CONCURRENTLY` do repositório.** Coberto por `Migration0023IndexValidIT`, `Migration0023IndexRestartIT`, `AnonymizationMigrationIT` e pela seção 9 do runbook.
- **Mesmo commit da fórmula e dos textos (NFR7.5).** As Etapas 19 a 23 entram juntas; sem isso, a tela diria que as taxas estão no realizado.
- **Portão de carga dependente do responsável.** Sem o texto do NFR1.9 aplicado e uma execução verde, a U2 fica pendente no Build and Test. C4 acima de 1.000 ms com o índice dispara o plano B do NFR8.11, que volta ao fundador.
- **Conexões na atualização rolling (D-INF2).** Pendência do responsável antes da primeira implantação na nuvem; a U2 soma 2 conexões fixas por tarefa.
- **Máquina local.** O `verify.sh` local pode morrer por memória; vale o `verify-checkpoint.sh` com o run verde do `ci` do mesmo commit (`team.md`).
- **Rollback.** Volta só a imagem de `api-worker`. A `0023` e a `0024` ficam: a imagem anterior não as usa (`Migration0023AdditiveIT`). Se o REAL estiver ligado, primeiro o flag ou o kill switch; o saque continua disponível. As confirmações feitas com a imagem anterior ficam `SLOT_MISSING`, contadas, sem preenchimento. O alarme, a métrica e a revisão do sidecar podem ficar.
