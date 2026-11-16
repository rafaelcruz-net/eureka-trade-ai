# Plano de Code Generation — U5 `u5-real-operations` (Bolt B5)

## Summary

- **Builds:** a operação do REAL:
  - liberação por usuário, com quarentena de 24 horas e portões (CI do commit implantado, divergência, kill switch, rede);
  - reconciliação com a rede e divergências com compensação;
  - saque limitado durante uma divergência;
  - kill switch REAL e conferência de restauração do banco;
  - teto e lista de bloqueio pelo administrador, com a visão do administrador;
  - anonimização com a carteira do bot e redação da trilha;
  - `decision.mode` REAL.
- **Touches:**
  - `apps/api`:
    - `botwallet`: `api` com M1 e M7, `application`, `domain`, `infra`, `infra.github`;
    - `administration.web`, `privacy`;
    - `riskcontrol`: `RealRiskPolicy` e `RealRejectionDetail`;
    - `realexecution`: `recheckBeforeSend`;
    - `bots`, `shared.config`;
    - migração 0021.
  - Dockerfiles da API e do TxSigner, `docker-compose.yml`, `infra/docker-compose.real.yml`.
  - `infra/observability/`, `infra/terraform/`.
  - `apps/web/e2e/real-ops/` e `apps/web/e2e/real-bot-wallet/local-validator.ts`.
  - `docs/runbooks/real-operations.md` e o contrato (adendo da U5).
- **Tests:** cerca de 220 testes:
  - escritos antes do código: portões, liberação, reconciliação, compensação, saque limitado, pausas globais, restauração, teto e anonimização;
  - integração com PostgreSQL real (concorrência, privilégios, gatilhos, redação);
  - ponta a ponta da divergência e do kill switch no validador local;
  - piso de 80% na API.

Conversa: português (Brasil). Fontes:
- `construction/u5-real-operations/functional-design/`: BR1.1 a BR7.2, F1 a F10, S1 a S29;
- `construction/u5-real-operations/nfr-requirements/`: U5-NFR*;
- `construction/u5-real-operations/nfr-design/`: componentes, mudanças nas outras Units, CI, redação, restauração;
- `construction/u5-real-operations/infrastructure-design/`: 0021, SHA, Compose, alertas, spec da divergência;
- `inception/contract-design/contract-summary.md`: R4, M1, M2, M5, M6, M7;
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

A U5 toca o núcleo financeiro (liberação, limites, reconciliação, compensação no ledger), a lógica da carteira do bot e do saque e a lógica de dados pessoais (anonimização e redação da trilha). Essas partes têm os testes escritos **antes** do código (**[testes antes]**). A configuração, as rotas do administrador, as métricas, os alertas, a infraestrutura local e a documentação seguem implementar e depois testar (**[depois]**).

## Etapas

- [x] **Step 1 — Estrutura e configuração.**
  - Pacotes em `botwallet`: `application`, `infra.github`; M7 em `botwallet.api`.
  - Configuração `memetrade.real.ops.*`: intervalos, executor de 4, prazos, `ci` (`GREEN` ou `RED` só fora da mainnet).
  - `memetrade.build.sha` e `memetrade.real.founder-user-id`.
  - `RealProperties` com as conferências de subida: MAINNET só no perfil de produção, com `founder-user-id`, e sem `ConfiguredCiStatus` (S8, S28).
  - `RuntimeSettingsGuard`: worker com scheduler de 25 a 32 e pool de 33 a 48; API com mínimo de 22. Mudam junto `application.yml`, `docker-compose.yml`, `ComposeFilesTest`, `AuditPropertiesTest` e `U4SettingsTest`.
  - Dockerfiles da API e do TxSigner: `ARG BUILD_SHA=` e `ENV MEMETRADE_BUILD_SHA`. No `docker-compose.yml`, `build.args` para `BUILD_SHA`.
  - Regras ArchUnit novas, cada uma com teste que comprova a falha:
    - rede só em `botwallet.infra.github` e no RPC;
    - o código de operação sem `signSwap` nem `signWithdrawal`;
    - reconciliação sem PAPER;
    - `botwallet` sem dependência de `riskcontrol`.

  *(U5-NFR1.2, U5-NFR2.1, U5-NFR3.7, U5-NFR8.1, U5-NFR8.2)*
- [x] **Step 2 — Runner de testes.** Confirmar o comando por escopo (`unit-test-instructions.md`) com um teste vazio. *(Testing Contract)*
- [x] **Step 3 — Migração 0021 [testes antes].**
  - Escrever primeiro o teste de privilégios e gatilhos. Ele cobre:
    - a redação permitida;
    - um UPDATE de outra coluna recusado, mesmo com a variável ligada;
    - a aplicação sem UPDATE na trilha;
    - `PUBLIC` sem `EXECUTE`;
    - o gatilho de `revoked_at`;
    - a pré-condição de duplicatas;
    - o CHECK novo do saque;
    - os gatilhos de idade.
  - Depois a migração:
    - as cinco tabelas novas e a matriz de privilégios;
    - em `real_release`: `state` com `DEFAULT`, o backfill e o índice único parcial;
    - as colunas anuláveis e o `redacted_at` do saque;
    - os enums ampliados (`PauseReason`, os CHECKs de 0018 e 0019, `decision.mode`);
    - na trilha: gatilho trocado, função de redação e dois índices;
    - rollback por changeset.

  *(U5-NFR1.3, U5-NFR3.8, U5-NFR3.9, U5-NFR8.6)*
- [x] **Step 4 — Liberação e portões [testes antes].**
  - Testes primeiro, a partir de S1 a S7, S9, S27 e S29:
    - `GateEvaluator` puro, com a ordem dos motivos;
    - `RealReleases.isEffective` só com `ACTIVE`;
    - a trava da mainnet com 19 liberados e dois pedidos simultâneos;
    - `FOUNDER_FIRST` e `PILOT_FULL`;
    - a revogação que cancela as ordens não enviadas e deixa o saque livre;
    - os portões com a CI vermelha e depois verde;
    - a CI de outro commit.
  - Depois:
    - `ReleaseService` e o `GatesJob` (5 min);
    - a porta `CiStatus`, com o `GitHubCiStatus` (WireMock com respostas gravadas: `success`, `cancelled`, em andamento, ausente, 5xx e tempo limite) e o `ConfiguredCiStatus`.

  *(BR1.1 a BR1.6, U5-NFR1.1, U5-NFR3.3, U5-NFR4.1, U5-NFR4.3)*
- [x] **Step 5 — Pausas globais e kill switch [testes antes].**
  - Testes primeiro, a partir de S16, S17 e S26:
    - M1 `readRealState` com `KILL_SWITCH`, `RESTORE` e `RESTORE_CHECK_PENDING`, com `pausedSince`;
    - a barreira da U3 recusando cada motivo novo como `REAL_PAUSED`;
    - a liquidação recusada nessas pausas;
    - a U4 `recheckBeforeSend` lendo pela M1, antes de assinar e antes de enviar;
    - o cancelamento das ordens não enviadas, enquanto as enviadas seguem e o saque continua;
    - o desligamento que não religa os bots.
  - Depois:
    - `GlobalPauses` e a M7 (`current`, `activate`, `deactivate`, com `version`);
    - `RealKillSwitchService` e o `KillSwitchCancelJob` (1 min);
    - as mudanças na U3 (`RealRiskPolicy.SEVERITY`, `RealRejectionDetail`) e na U4.

  *(BR3.1, BR3.2, U5-NFR3.5)*
- [x] **Step 6 — Reconciliação e divergência [testes antes].**
  - Testes primeiro, a partir de S10 a S14 e S12b e S12c:
    - `ReconciliationPolicy` pura: `CLEAN`, `DIVERGENT`, `DEFERRED` com ordem em trânsito ou entrada não lida, e `UNAVAILABLE`;
    - a compensação conferida contra a última medida, com um lançamento por ativo;
    - a nova reconciliação e a despausa;
    - o valor errado, que volta a `OPEN`.
  - Depois:
    - a `ChainPort` ampliada (`tokenBalances`, `finalizedSlot`, `signaturesSince`, `transactionSigners`) no `RpcChainWatcher`, com respostas gravadas do RPC;
    - o `RpcBudget` com `RECONCILIATION` e `RESTORE_CHECK`;
    - o `ReconciliationReader` e o `ReconciliationJob` (15 min e depois de ordem confirmada, executor de 4);
    - o `DivergenceService`.

  *(BR2.1 a BR2.3, U5-NFR3.1, U5-NFR3.2, U5-NFR4.6)*
- [x] **Step 7 — Saque com divergência [testes antes].**
  - Testes primeiro, a partir de S15: o limite é o menor saldo, menos a reserva, e o saque continua disponível com o kill switch, com o REAL desligado e sem a liberação.
  - Depois o `WithdrawalLimit` e a chamada no saque da U1.

  *(BR2.4, U5-NFR4.5)*
- [x] **Step 8 — Restauração [testes antes].**
  - Testes primeiro, a partir de S24, S24b e S24c:
    - o marcador aberto antes de qualquer leitura;
    - o `DepositScanner` parado enquanto ele está aberto;
    - a paginação até esgotar;
    - um depósito, que não é desconhecido;
    - o RPC fora, com nova tentativa a cada minuto;
    - a despausa com a confirmação do administrador.
  - Depois o `RestoreDetector` e o trabalho `restore-check`.

  *(BR2.5, U5-NFR4.4)*
- [x] **Step 9 — Teto e lista de bloqueio [testes antes].**
  - Testes primeiro, a partir de S18 a S20:
    - o teto vale na hora;
    - `CAP_ONLY_LOWERED`;
    - o `LOWER_CAP` reenviado até o TxSigner confirmar;
    - o token bloqueado recusado para qualquer bot.
  - Depois `adminLowerCap`, a rota da lista de bloqueio e o `TxSignerCommandJob` (1 min).

  *(BR4.1, BR4.2)*
- [x] **Step 10 — Anonimização e redação [testes antes].**
  - Testes primeiro, a partir de S22 e S23:
    - `BALANCE` com saldo;
    - o encerramento com os endereços limpos e o `DEACTIVATE` enviado;
    - a redação da trilha e do outbox na mesma transação, depois dos eventos de encerramento;
    - a corrida com o `AuditOutboxRelay`;
    - `NoWalletAddressAfterAnonymizationIT`, que percorre o esquema inteiro;
    - os logs consultáveis sem o endereço.
  - Depois a M1 `closeForAnonymization` e a chamada no `privacy`.

  *(BR6.1, BR6.2, U5-NFR1.3)*
- [x] **Step 11 — `decision.mode` REAL [testes antes].** Teste primeiro, a partir de S25; depois o ciclo REAL grava `REAL`. O PAPER continua gravando `PAPER`. *(BR7.1, BR7.2)*
- [x] **Step 12 — Rotas do administrador e visão [depois].**
  - O `RealAdminController` (R4) chama só a M1, a M5 (`withPersonLock`) e a M7. As rotas cobrem:
    - liberar e revogar;
    - baixar o teto;
    - a lista de bloqueio;
    - ligar e desligar o kill switch;
    - listar e compensar divergências;
    - confirmar a restauração;
    - a lista de usuários com `WalletDisplay.abbreviate`.
  - Todas as rotas exigem `ADMIN`, `Idempotency-Key` e o motivo.
  - O OpenAPI e os tipos do admin são regenerados.
  - Testes de rota, de idempotência, de papel e de ausência de chave.

  *(BR5.1, U5-NFR3.4, U5-NFR6.1 a U5-NFR6.3)*
- [x] **Step 13 — Expurgo [testes antes].** Testes primeiro; depois o `RealOpsPurgeJob` na API, com o pool `risk_retention`, `NOT EXISTS` nas divergências e o gatilho de idade. *(U5-NFR3.9)*
- [x] **Step 14 — Métricas, alertas e Terraform [depois].**
  - `RealOpsMetrics` com rótulos fechados e contadores com zero na subida.
  - `RealOpsGaugesJob` (30 s) e o batimento por trabalho.
  - `real-ops-alerts.yml`, validado com `promtool`.
  - `real-ops.tf`, com o `restore-check` por metric math e o segredo `github-actions-read-token` sem consumidor, validado com `terraform validate`.
  - `RealOpsAlarmParityTest`.

  *(U5-NFR4.2, U5-NFR5.1 a U5-NFR5.13)*
- [x] **Step 15 — Ponta a ponta [depois].**
  - No `local-validator.ts`, a montagem de transação com várias instruções: `createAccount`, `InitializeMint2`, `CreateIdempotent` e `MintTo`.
  - No `infra/docker-compose.real.yml`, as variáveis do worker: intervalo de 15 s e `ci=GREEN`.
  - O spec `apps/web/e2e/real-ops/divergence.spec.ts` cobre a divergência, a compensação, a despausa e o kill switch.

  *(U5-NFR8.7, infrastructure-specification)*
- [x] **Step 16 — Documentação e rastreabilidade.**
  - Runbook `docs/runbooks/real-operations.md`. Ele cobre:
    - liberação e portões;
    - divergência e compensação;
    - kill switch;
    - restauração;
    - troca do token do GitHub;
    - duplicatas na 0021;
    - rollback, inclusive do CHECK do saque.
  - Adendo da U5 no `contract-summary.md`: R4, M1, M7, `CiStatus`, os motivos e os detalhes novos.
  - `code-summary.md`, com os textos para o responsável: `env BUILD_SHA` e `real-ops-alerts` no `ci.yml`; `--build-arg` e o spec `e2e/real-ops` no `verify.sh`.
  - `traceability.json` e `source-manifest.json`.

## Rastreabilidade história → etapas

| História | Etapas |
|---|---|
| US7.1 | 1, 3, 4, 12 |
| US7.2 | 9, 12 |
| US7.3 | 12 |
| US8.1 | 5, 12, 15 |
| US8.2 | 6, 7, 8, 15 |
| US8.4 | 3, 10 |

## Fora desta Unit

| Item | Onde fica |
|---|---|
| Telas do administrador e do usuário | U6 |
| Condicionais marcadas pelo kill switch | U7 (lê a M7) |
| Tarefa do worker na nuvem, que consome o segredo | Environment Provisioning |
| `ci.yml` e `verify.sh` | o responsável do projeto, com o texto pronto |
