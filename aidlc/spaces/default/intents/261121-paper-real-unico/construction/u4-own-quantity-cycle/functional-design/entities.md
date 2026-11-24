# Entidades — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Unit `service`: o ciclo do bot REAL passa a vender só a quantidade do próprio bot (FR6), atrás da flag `memetrade.real.own-quantity-sell`, desligada por padrão.

Convenções dos tipos lógicos (as mesmas da U1 e da U2):
- `lamports`: inteiro com sinal, exato, nunca ponto flutuante;
- `base_units`: inteiro não negativo, a quantidade de token em unidades base;
- `tokens`: decimal exato, a quantidade em token inteiro que o motor de decisão vê (`base_units ÷ 10^decimais`);
- `instant`: instante em UTC;
- `ref<X>`: referência a outra entidade.

Os IDs desta Unit começam em `ENT-401`, para não colidir com os da U1 (`ENT-0xx`) e da U2 (`ENT-2xx`). Nada aqui grava atribuição no ledger (ADR-004): tudo é calculado na leitura. A única gravação nova é a marca do bot (`ENT-407`), que já existe e passa a ter mais um uso.

```yaml
entities:
  - id: ENT-401
    name: OwnQuantitySellFlag
    description: >
      A configuração que liga a venda só da quantidade própria. Variável da definição de tarefa da API e do worker,
      versionada no Terraform (team.md, Flags). Com o valor padrão, o comportamento é exatamente o de hoje.
    kind: configuration
    attributes:
      - { name: key, type: text, required: true, unique: true, allowed: ["memetrade.real.own-quantity-sell"] }
      - { name: enabled, type: boolean, required: true, default: false, constraints: "false em todo ambiente até a implantação auditada separada (BR5.2)" }
    constraints:
      - "lida no início de cada ciclo REAL pelo worker e a cada resposta da lista de bots pela API"
      - "o mesmo valor nas definições de tarefa da API e do worker; mudar o valor é uma implantação só da flag, nunca junto com código"
    relationships:
      - { target: ENT-410, cardinality: "1:1", direction: "ENT-410 reflete o valor visto pela API" }

  - id: ENT-402
    name: BotHolding
    description: >
      C15. A posição própria de um bot REAL num mint, num único instantâneo: a quantidade e o custo dele pelo caminho de
      atribuição da U2 (desde a época do mint), menos as vendas dele em aberto. Sem preço, taxas, período, realizado nem
      linha "Sem bot".
    kind: value
    attributes:
      - { name: mint, type: text, required: true, constraints: "endereço Base58 do token" }
      - { name: ownTokenBaseUnits, type: base_units, required: true, min: 0, constraints: "a quantidade do titular bot no ledger do instantâneo (BR1.1)" }
      - { name: ownCostLamports, type: lamports, required: true, min: 0, constraints: "o custo do titular bot pelo RealCostBasis (U2 BR2.1, BR2.2)" }
      - { name: openSellBaseUnits, type: base_units, required: true, min: 0, constraints: "a soma das vendas do bot em aberto no mesmo instantâneo (ENT-405, BR1.3)" }
      - { name: availableTokenBaseUnits, type: base_units, required: true, min: 0, constraints: "max(0, ownTokenBaseUnits − openSellBaseUnits) (BR1.2)" }
    constraints:
      - "availableTokenBaseUnits <= ownTokenBaseUnits"
      - "ownTokenBaseUnits = 0 implica ownCostLamports = 0"
      - "só existe para um mint com quantidade na carteira do bot (quantidade da carteira > 0) ou com venda do bot em aberto"
    relationships:
      - { target: ENT-403, cardinality: "N:1", direction: "ENT-403 Available contém N BotHolding" }
      - { target: ENT-405, cardinality: "1:N", direction: "BotHolding desconta N OpenSell do mesmo mint" }

  - id: ENT-403
    name: HoldingsResult
    description: >
      C15. A resposta da leitura da posição própria de um bot: disponível, com as posições e o instante da leitura,
      ou indisponível, com o motivo. Nunca lança exceção para o ciclo e nunca traz a posição da carteira inteira.
    kind: sealed-value
    attributes:
      - { name: case, type: enum, required: true, allowed: [Available, Unavailable] }
      - { name: holdings, type: "list<ref<ENT-402>>", required: false, constraints: "só no caso Available; um item por mint" }
      - { name: readAt, type: instant, required: false, constraints: "só no caso Available; o instante do instantâneo" }
      - { name: reason, type: "ref<ENT-404>", required: false, constraints: "só no caso Unavailable" }
    constraints:
      - "exatamente um dos dois casos"
      - "Unavailable nunca vira zero nem a posição da carteira inteira (BR2.3)"
    relationships:
      - { target: ENT-404, cardinality: "N:1", direction: "Unavailable leva um motivo" }

  - id: ENT-404
    name: OwnQuantityUnavailableReason
    description: >
      Os motivos de o ciclo não poder vender por falta da posição própria, em dois enums (alinhamento A11):
      `pnl.api.UnavailableReason` (C15) com os três motivos da leitura, que são o rótulo `reason` da métrica
      `real_own_holdings_unavailable_total`; e `bots.domain.OwnQuantityReason`, o motivo da nota do ciclo e do log,
      com os mesmos três mais STALE_SNAPSHOT, detectado pelo ciclo sob a trava do bot (BR3.1) e contado em
      `real_cycle_sell_capped_total{outcome="STALE_SNAPSHOT"}`.
    kind: enum
    attributes:
      - { name: value, type: enum, required: true, allowed: [BUDGET_EXCEEDED, READ_FAILED, BOT_NOT_OWNED, STALE_SNAPSHOT] }
    constraints:
      - "BUDGET_EXCEEDED, READ_FAILED e BOT_NOT_OWNED vêm do HoldingsResult (C15, pnl.api.UnavailableReason)"
      - "STALE_SNAPSHOT é do ciclo (bots.domain.OwnQuantityReason): a marca do bot mudou entre a leitura e a trava (BR3.1); não entra no enum de C15"

  - id: ENT-405
    name: OpenSell
    description: >
      C16, com a extensão desta Unit aceita pelo responsável em 2026-11-24 (D1). Uma venda do bot que ainda não está
      no ledger: uma ordem SELL em andamento ou uma decisão SELL ainda na fila (PENDING). Conta como em aberto pelo
      lado conservador.
    kind: value
    attributes:
      - { name: source, type: enum, required: true, allowed: [ORDER, DECISION_REQUEST], constraints: "DECISION_REQUEST é a extensão desta Unit (BR1.3)" }
      - { name: sourceId, type: uuid, required: true, constraints: "realOrderId para ORDER; decisionRequestId para DECISION_REQUEST" }
      - { name: mint, type: text, required: true }
      - { name: baseUnits, type: base_units, required: true, min: 1, constraints: "ORDER: a quantidade de entrada da ordem; DECISION_REQUEST: a quantidade pedida na decisão" }
      - { name: state, type: enum, required: true, allowed: [PENDING, PROPOSED, QUOTED, SIGNED, SENT, AMBIGUOUS], constraints: "PENDING só para DECISION_REQUEST; AMBIGUOUS conta como em aberto" }
    constraints:
      - "só do bot pedido e do tenant pedido; só lado SELL"
      - "lida no mesmo instantâneo do ledger (C16, transação só de leitura do RealBotHoldings)"
    relationships:
      - { target: ENT-402, cardinality: "N:1", direction: "N OpenSell são descontadas de um BotHolding" }

  - id: ENT-406
    name: OwnSellCap
    description: >
      O corte da venda pedida pelo ciclo. Calculado sob a trava do bot, a partir do HoldingsResult lido antes da
      transação do ciclo.
    kind: calculation
    attributes:
      - { name: botId, type: uuid, required: true }
      - { name: mint, type: text, required: true }
      - { name: requestedBaseUnits, type: base_units, required: true, constraints: "a quantidade da decisão SELL do motor, convertida para unidades base com arredondamento para baixo" }
      - { name: availableBaseUnits, type: base_units, required: true, constraints: "ENT-402.availableTokenBaseUnits do mint" }
      - { name: sellBaseUnits, type: base_units, required: true, constraints: "min(requestedBaseUnits, availableBaseUnits) (BR1.4)" }
      - { name: outcome, type: "ref<ENT-408>", required: true }
    constraints:
      - "sellBaseUnits = 0 nunca vira ordem: o resultado é NO_OWN_QUANTITY (BR1.5)"
      - "sellBaseUnits <= availableBaseUnits sempre"

  - id: ENT-407
    name: BotChangeMark
    description: >
      A marca de mudança da linha do bot, um número de uma sequência global que só cresce. Já existe e é renovada no
      fim de todo ciclo concluído, na mesma transação da decisão. A U4 a lê antes do instantâneo e de novo sob a trava
      do bot, para saber se alguma decisão do bot foi gravada no meio (BR3.1, BR3.2).
    kind: value
    attributes:
      - { name: botId, type: uuid, required: true }
      - { name: mark, type: bigint, required: true, min: 0, constraints: "inteiro de 64 bits (bot.change_seq, sequência bot_change_seq); estritamente crescente; nunca reaproveitada nem expurgada" }
    constraints:
      - "toda transação que grava uma decisão SELL do bot renova a marca antes do commit (BR3.2)"
      - "outras mudanças do bot (edição, pausa) também renovam a marca; isso só pode fazer o ciclo deixar de vender, nunca vender a mais"

  - id: ENT-408
    name: OwnQuantityCycleOutcome
    description: >
      O que a U4 acrescenta ao resultado do ciclo REAL e ao contexto da decisão gravada (o objeto interno
      `context.ownQuantity`, com `read`, `mints` e `cap`, gravado pelo `DecisionJson` só nas decisões REAL com a flag
      ligada; nenhuma rota nem contrato expõe `decision.context`, alinhamento A10). O resultado do ciclo segue o
      padrão REAL_<resultado> que já existe.
    kind: enum
    attributes:
      - { name: cycleOutcome, type: enum, required: false, allowed: [REAL_NO_OWN_QUANTITY, REAL_OWN_QUANTITY_UNAVAILABLE], constraints: "só quando o motor decidiu SELL e a venda não virou decisão REAL" }
      - { name: decisionNote, type: enum, required: true, allowed: [OWN_QUANTITY_AVAILABLE, NO_OWN_QUANTITY, OWN_QUANTITY_UNAVAILABLE], constraints: "uma nota por mint da carteira, gravada no contexto da decisão (BR1.5, BR2.3), inclusive para os mints tirados dos tokens do pedido; assim um resultado NO_TOKENS ou NO_TRADE do motor nunca esconde NO_OWN_QUANTITY nem OWN_QUANTITY_UNAVAILABLE" }
      - { name: unavailableReason, type: "ref<ENT-404>", required: false, constraints: "só com OWN_QUANTITY_UNAVAILABLE" }
    constraints:
      - "com a flag desligada, nada desta entidade é gravado"
      - "os dois resultados novos do ciclo entram na lista de resultados aceitos do ciclo numa migração só de expansão"

  - id: ENT-409
    name: OwnDecisionInputs
    description: >
      As entradas do motor de decisão de um bot REAL com a flag ligada. As posições avaliadas para saída são as
      próprias; o conjunto de mints que impede uma compra continua sendo o da carteira (as compras não mudam).
    kind: value
    attributes:
      - { name: ownPositions, type: "list<OwnPositionInput>", required: true, constraints: "um item por mint com availableTokenBaseUnits > 0" }
      - { name: "ownPositions[].quantity", type: tokens, required: true, min: "> 0", constraints: "availableTokenBaseUnits ÷ 10^decimais, exato" }
      - { name: "ownPositions[].cost", type: "lamports convertido em US$ pela cotação do SOL do ciclo", required: true, constraints: "piso(ownCostLamports × available ÷ own); igual a ownCostLamports quando não há venda em aberto (BR1.6)" }
      - { name: "ownPositions[].openedAt", type: instant, required: true, constraints: "o instante de abertura que o bot já guarda (BotHolding do módulo bots), como hoje" }
      - { name: heldNotSellable, type: "set<mint>", required: true, constraints: "mints com quantidade na carteira e sem quantidade disponível do bot, ou todos os mints da carteira quando a leitura está indisponível" }
      - { name: balanceUsd, type: decimal, required: true, constraints: "o saldo operável da carteira, como hoje (ADR-006)" }
      - { name: requestTokens, type: "list<mint>", required: true, constraints: "os tokens do pedido de decisão, como hoje (seleção do bot mais as posições), menos os mints de heldNotSellable (BR1.6)" }
    constraints:
      - "ownPositions ∪ heldNotSellable = mints com quantidade na carteira (caminho só de quantidade da U2, BR2.5)"
      - "ownPositions ∩ heldNotSellable = vazio"
      - "um mint de heldNotSellable sai de requestTokens: o motor não o avalia para saída nem para compra"
      - "conjunto que impede compra = mints de ownPositions ∪ mints de heldNotSellable = mints da carteira, igual ao de hoje (BR4.2)"
      - "só no ramo REAL com a flag ligada; o motor de decisão, o pedido de decisão e o ramo PAPER não mudam"
      - "o registro de abertura do bot (BotHolding do módulo bots) de um mint de heldNotSellable não é fechado (BR1.8)"

  - id: ENT-410
    name: BotPageOwnQuantityState
    description: >
      C7. Campo aditivo da página de bots da API (`BotPage.realOwnQuantitySell`) com o valor da flag visto pela API.
    kind: value
    attributes:
      - { name: realOwnQuantitySell, type: boolean, required: true, default: false, constraints: "igual a ENT-401.enabled na API; sempre presente; false com a configuração padrão" }
    constraints:
      - "não depende do filtro, do modo nem da pessoa; não é dado de cliente"

  - id: ENT-411
    name: OwnQuantityNotice
    description: >
      O aviso fixo da lista `/bots` (FR6.2). Texto aprovado na Q4, sem alteração.
    kind: ui-state
    attributes:
      - { name: text, type: text, required: true, allowed: ["Cada bot real vende só a quantidade que ele mesmo comprou. Posições herdadas ou de outros bots continuam na carteira e podem ser vendidas em Vender tudo e sacar."] }
      - { name: role, type: text, required: true, allowed: ["status"] }
      - { name: visible, type: boolean, required: true, constraints: "realOwnQuantitySell = true E o filtro de modo da lista não é Simulado (Todos ou Real), sem depender da página corrente (BR6.1, D5)" }
    constraints:
      - "texto puro, sem depender de cor nem de ícone; não promete resultado"
    relationships:
      - { target: ENT-410, cardinality: "1:1", direction: "o aviso lê realOwnQuantitySell" }
```

## Resumo

| ID | Entidade | Tipo | Para que serve |
|---|---|---|---|
| ENT-401 | OwnQuantitySellFlag | configuração | Liga a mudança; desligada por padrão; ligar é implantação auditada separada |
| ENT-402 | BotHolding | valor (C15) | Quantidade e custo próprios do bot por mint, menos as vendas em aberto |
| ENT-403 | HoldingsResult | valor selado (C15) | Disponível ou indisponível, nunca exceção, nunca a carteira inteira |
| ENT-404 | OwnQuantityUnavailableReason | enum | Os motivos da indisponibilidade (3 de C15 + STALE_SNAPSHOT do ciclo) |
| ENT-405 | OpenSell | valor (C16 estendido) | Ordem SELL em andamento ou decisão SELL ainda na fila |
| ENT-406 | OwnSellCap | cálculo | O corte: min(pedido, disponível) |
| ENT-407 | BotChangeMark | valor (existente) | Detecta uma decisão do bot gravada entre a leitura e a trava |
| ENT-408 | OwnQuantityCycleOutcome | enum | Resultados novos do ciclo e a nota por mint na decisão |
| ENT-409 | OwnDecisionInputs | valor | Posições próprias para a saída; conjunto da carteira para a compra |
| ENT-410 | BotPageOwnQuantityState | valor (C7) | O estado da flag na lista de bots |
| ENT-411 | OwnQuantityNotice | estado de tela | O aviso fixo do FR6.2 |

## Sources

- `inception/units-generation/unit-of-work.md` (U4) e `unit-of-work-story-map.md` (US6.1, US6.2)
- `inception/user-stories/stories.md` (AC6.1.1 a AC6.1.4, AC6.2.1 a AC6.2.3)
- `inception/requirements-analysis/requirements.md` (FR6.1, FR6.2)
- `inception/domain-design/decisions.md` (ADR-006)
- `inception/contract-design/contract-summary.md` (C7, C15, C16)
- `construction/u2-real-bot-pnl/functional-design/rules.md` (BR2.1 a BR2.8, BR4.4)
- `construction/u4-own-quantity-cycle/functional-design/functional-design-questions.md` [Q1] a [Q5]
- Código lido: `bots/worker/RealCycle.java`, `bots/worker/CycleRunner.java`, `bots/application/RealDecisionStep.java`, `bots/infra/LockOrder.java`, `bots/infra/JdbcBotRepository.java` (`renewMark`), `realexecution/api/RealExecutionPort.java`, `realexecution/api/RealDecision.java`, `realexecution/infra/DecisionConsumer.java`, `realexecution/infra/OrderSettlement.java`, `decision/domain/DecisionEngine.java`, `decision/domain/PositionInput.java`, `pnl/application/AttributedCostReader.java`, migrações `0019-real-execution.sql` e `0020-real-bot-cycle.sql`

## Assumptions & Open Questions

- **D1 (responsável, 2026-11-24):** a extensão de C16 com as decisões SELL ainda na fila (`source = DECISION_REQUEST`, `sourceId`) foi aceita.
- **D5 (responsável, 2026-11-24):** o aviso depende só da flag e do filtro de modo, não da página.
- **Revisão R-02:** o mecanismo de `heldNotSellable` está fechado em ENT-409: o mint sai dos tokens do pedido só no ramo REAL com a flag ligada; o motor e o PAPER não mudam.
- [assumption] `ENT-407` reaproveita a marca de mudança que o bot já tem (`bigint`) e que o ciclo já renova; nenhuma coluna nova.
- [assumption] As unidades de token nos exemplos usam um mint de 6 casas decimais (1 `XYZ` = 1.000.000 unidades base).
