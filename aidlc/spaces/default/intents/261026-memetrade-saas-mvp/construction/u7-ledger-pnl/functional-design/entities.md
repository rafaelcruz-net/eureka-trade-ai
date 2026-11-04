# Entidades — U7 Ledger e P&L

Modelo de dados do PaperTradingLedger e do PortfolioPnL. A base é [unit-of-work.md](../../../inception/units-generation/unit-of-work.md) e [components.md](../../../inception/domain-design/components.md). Também entram os contratos C5, C13 e C15 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e as respostas Q1 a Q12 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. Os nomes de atributos seguem o código, em inglês, e as descrições ficam em português. Valores em USDC têm escala 6 (Q1); preços unitários em USDC têm escala 12, para não apagar spread e impacto em tokens de preço muito baixo (acréscimo à tabela "Escalas"); quantidades de token têm as casas do mint; percentuais são pontos com 4 casas. Toda linha carrega `tenantId`, `ownerUserId` e `mode`, e `mode` só aceita `PAPER`.

```yaml
entities:
  - name: PaperAccount
    owner: PaperTradingLedger
    description: Conta PAPER de um bot, criada pelo primeiro FUNDING (Q2). É como a U7 sabe de quem é um bot sem ler a U5.
    attributes:
      - { name: botId, type: identifier, required: true, unique: true, constraints: "identificador do bot da U5, só referência" }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: status, type: enum, required: true, allowed: [OPEN, CLOSING, CLOSED], default: OPEN }
      - { name: createdAt, type: instant, required: true }
    relationships:
      - { to: PaperOrder, cardinality: "1:N", direction: "PaperAccount -> PaperOrder" }
      - { to: LedgerEntry, cardinality: "1:N", direction: "PaperAccount -> LedgerEntry" }
      - { to: Position, cardinality: "1:N", direction: "PaperAccount -> Position" }
      - { to: EquityPeak, cardinality: "1:1", direction: "PaperAccount -> EquityPeak" }

  - name: PaperOrder
    owner: PaperTradingLedger
    description: Ordem simulada aprovada pela barreira da U6 e entregue pela U5 (C15). Uma por chave de idempotência por tenant e modo.
    attributes:
      - { name: orderId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: botId, type: reference, required: true, references: PaperAccount }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: decisionId, type: identifier, required: false, constraints: "referência à decisão da U5; obrigatório em TRADE, vazio em CLOSE" }
      - { name: idempotencyKey, type: text, required: true, max: 200, constraints: "em CLOSE, '<chave do closePositions>:<positionId>'" }
      - { name: contentDigest, type: text, required: true, constraints: "SHA-256 do conteúdo canônico; TRADE: (botId, decisionId, side, chain, mint, requestedQuantity, limitsVersion); CLOSE: (botId, CLOSE, positionId, quantity, limitsVersion)" }
      - { name: kind, type: enum, required: true, allowed: [TRADE, CLOSE] }
      - { name: side, type: enum, required: true, allowed: [BUY, SELL] }
      - { name: chain, type: text, required: true }
      - { name: tokenMint, type: text, required: true }
      - { name: requestedQuantity, type: decimal, required: true, min: 0, constraints: "como pedida, antes do truncamento" }
      - { name: limitsVersion, type: integer, required: true, constraints: "versão dos limites vigente na avaliação; para CLOSE, a que a U5 passa em closePositions" }
      - { name: status, type: enum, required: true, allowed: [FILLED, PARTIALLY_FILLED, NOT_FILLED, CLOSE_PENDING] }
      - { name: notFilledReason, type: enum, required: false, allowed: [PRICE_UNAVAILABLE, NO_LIQUIDITY, QUANTITY_BELOW_MINIMUM, NO_POSITION, ACCOUNT_CLOSING, INSUFFICIENT_BALANCE, TENANT_MISMATCH] }
      - { name: createdAt, type: instant, required: true }
    constraints:
      - "Único (tenantId, mode, idempotencyKey) (BR3.1)."
      - "idempotencyKey e limitsVersion nunca nulos (AC4.6.5)."
      - "kind = TRADE exige decisionId; kind = CLOSE exige decisionId vazio."
    relationships:
      - { to: Fill, cardinality: "1:0..1", direction: "PaperOrder -> Fill" }

  - name: Fill
    owner: PaperTradingLedger
    description: Preenchimento simulado com as hipóteses usadas (FR9.1, AC4.11.1).
    attributes:
      - { name: fillId, type: identifier, required: true, unique: true }
      - { name: orderId, type: reference, required: true, unique: true, references: PaperOrder }
      - { name: filledQuantity, type: decimal, required: true, min: 0, constraints: "truncada nas casas do mint; maior que zero" }
      - { name: referencePrice, type: decimal, required: true, constraints: "USDC por unidade, escala 12" }
      - { name: quotedPrice, type: decimal, required: true, constraints: "preço com o spread, antes do impacto, escala 12" }
      - { name: fillPrice, type: decimal, required: true, constraints: "escala 12" }
      - { name: spreadPct, type: decimal, required: true, constraints: "pontos, 4 casas" }
      - { name: impactPct, type: decimal, required: true, constraints: "pontos, 4 casas" }
      - { name: slippagePct, type: decimal, required: true, constraints: "spreadPct + impactPct" }
      - { name: liquidityUsed, type: decimal, required: true, constraints: "liquidez do pool lida no preenchimento, USDC" }
      - { name: grossAmount, type: decimal, required: true, constraints: "filledQuantity × fillPrice, escala 6" }
      - { name: fees, type: decimal, required: true, constraints: "escala 6" }
      - { name: realized, type: decimal, required: false, constraints: "só em SELL e CLOSE, escala 6" }
      - { name: marketSource, type: text, required: true }
      - { name: marketObservedAt, type: instant, required: true }
      - { name: simulationPolicyVersion, type: text, required: true, references: SimulationPolicy.version }
      - { name: filledAt, type: instant, required: true }

  - name: LedgerEntry
    owner: PaperTradingLedger
    description: Lançamento append-only (FR10.1). Nunca alterado nem apagado; correção só por COMPENSATION.
    attributes:
      - { name: entryId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: botId, type: reference, required: true, references: PaperAccount }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: type, type: enum, required: true, allowed: [FUNDING, TRADE_COST, TRADE_PROCEEDS, SLIPPAGE, FEE, CLOSE, COMPENSATION] }
      - { name: asset, type: text, required: true, constraints: "USDC no beta" }
      - { name: amount, type: decimal, required: true, constraints: "com sinal: positivo entra no saldo, negativo sai; escala 6; nunca zero" }
      - { name: referenceId, type: text, required: true, constraints: "orderId; para FUNDING, a chave da operação; para COMPENSATION, o entryId compensado" }
      - { name: compensatesEntryId, type: reference, required: false, unique: true, references: LedgerEntry }
      - { name: createdAt, type: instant, required: true }
    constraints:
      - "Somente inserção: UPDATE e DELETE falham no banco (BR4.1)."
      - "COMPENSATION exige compensatesEntryId e amount igual ao negativo do original; cada lançamento é compensado no máximo uma vez."
      - "No máximo um FUNDING por botId (índice único parcial)."

  - name: Position
    owner: PaperTradingLedger
    description: Posição por bot e token a custo médio ponderado (FR10.2). Guarda o custo total, não o preço médio, para não acumular arredondamento.
    attributes:
      - { name: positionId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: botId, type: reference, required: true, references: PaperAccount }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: chain, type: text, required: true }
      - { name: tokenMint, type: text, required: true }
      - { name: tokenDecimals, type: integer, required: true, min: 0, max: 18 }
      - { name: quantity, type: decimal, required: true, min: 0 }
      - { name: costBasis, type: decimal, required: true, min: 0, constraints: "USDC, escala 6; zero quando quantity é zero" }
      - { name: status, type: enum, required: true, allowed: [OPEN, CLOSE_PENDING, CLOSED] }
      - { name: updatedAt, type: instant, required: true }
      - { name: version, type: integer, required: true, constraints: "trava otimista além do bloqueio de linha" }
    constraints:
      - "Única (botId, chain, tokenMint)."
      - "quantity = 0 se e só se costBasis = 0."

  - name: SimulationPolicy
    owner: PaperTradingLedger
    description: Política de preenchimento versionada (Q3, Q5, Q6, Q12). Dado de configuração com versão; v1 semeada pela migração.
    attributes:
      - { name: version, type: text, required: true, unique: true }
      - { name: spreadPct, type: decimal, required: true, default: "0.5000" }
      - { name: impactPerConsumedPct, type: decimal, required: true, default: "1.0000", constraints: "pontos de impacto por ponto de liquidez consumida" }
      - { name: feePct, type: decimal, required: true, default: "0.3000" }
      - { name: maxLiquidityConsumptionPct, type: decimal, required: true, default: "10.0000" }
      - { name: moneyScale, type: integer, required: true, default: 6 }
      - { name: priceScale, type: integer, required: true, default: 12 }
      - { name: moneyRounding, type: enum, required: true, allowed: [HALF_EVEN], default: HALF_EVEN }
      - { name: quantityRounding, type: enum, required: true, allowed: [DOWN], default: DOWN }
      - { name: activatedAt, type: instant, required: true }

  - name: EquityPeak
    owner: PortfolioPnL
    description: Maior patrimônio virtual já atingido pelo bot, para o drawdown da U6 (Q10).
    attributes:
      - { name: botId, type: reference, required: true, unique: true, references: PaperAccount }
      - { name: peakEquity, type: decimal, required: true, constraints: "USDC, escala 6; começa no valor do FUNDING" }
      - { name: peakAt, type: instant, required: true }
      - { name: lastEquity, type: decimal, required: false, constraints: "vazio quando o último cálculo foi unknown" }
      - { name: lastEquityAt, type: instant, required: true }

  - name: ReconciliationCheck
    owner: PortfolioPnL
    description: Resultado de uma conferência entre operações e ledger por pessoa (Q9, FR10.5).
    attributes:
      - { name: checkId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: status, type: enum, required: true, allowed: [RECONCILED, DIVERGENT] }
      - { name: divergenceCount, type: integer, required: true, min: 0 }
      - { name: divergences, type: list, required: true, constraints: "orderId (ou positionId), kind (MISSING_ENTRY, AMOUNT_MISMATCH, ORPHAN_ENTRY, POSITION_MISMATCH), expected, found" }
      - { name: trigger, type: enum, required: true, allowed: [SCHEDULED, ON_DEMAND] }
      - { name: checkedAt, type: instant, required: true }
```

## Resumo

| Entidade | Componente | Papel |
|---|---|---|
| `PaperAccount` | PaperTradingLedger | Dono e estado PAPER de cada bot, criado pelo `FUNDING` |
| `PaperOrder` | PaperTradingLedger | Ordem com chave de idempotência e versão dos limites |
| `Fill` | PaperTradingLedger | Preenchimento com todas as hipóteses da política |
| `LedgerEntry` | PaperTradingLedger | Lançamento imutável, com sinal, em USDC |
| `Position` | PaperTradingLedger | Quantidade e custo total por bot e token |
| `SimulationPolicy` | PaperTradingLedger | Parâmetros versionados de spread, impacto, taxa, teto e arredondamento |
| `EquityPeak` | PortfolioPnL | Maior patrimônio para o drawdown |
| `ReconciliationCheck` | PortfolioPnL | Último resultado da conferência |
