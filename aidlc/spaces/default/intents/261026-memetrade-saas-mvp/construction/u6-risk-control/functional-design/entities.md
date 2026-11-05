# Entidades — U6 Barreira de limites

Modelo de dados do RiskControl. As fontes são:
- [unit-of-work.md](../../../inception/units-generation/unit-of-work.md) e [components.md](../../../inception/domain-design/components.md), que dão a entidade `OrderRejection`;
- os contratos C14, C21, C13 e C11 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as respostas Q1 a Q11 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. Os nomes de atributos seguem o código, em inglês, e as descrições ficam em português. As escalas são as da tabela "Escalas" do Contract Design:
- valores em USDC: 6 casas;
- preços unitários em USDC: 12 casas;
- quantidades de token: as casas do mint;
- percentuais: pontos com 4 casas.

Toda linha gravada carrega `tenantId`, `ownerUserId` e `mode`, e `mode` só aceita `PAPER`. A U6 não guarda limites, bots, decisões nem posições. Bot, decisão e versão de limites entram só como identificadores, e os valores dos limites chegam na chamada (`LimitsSnapshot`).

```yaml
entities:
  - name: OrderEvaluation
    owner: RiskControl
    description: A primeira avaliação de uma decisão pela barreira, aprovada ou recusada (Q9). Uma por decisão; é o que permite reconhecer a mesma decisão avaliada de novo.
    attributes:
      - { name: evaluationId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant, constraints: "do contexto de autenticação do ciclo (C10), nunca da entrada" }
      - { name: ownerUserId, type: reference, required: true, references: User, constraints: "do contexto de autenticação do ciclo" }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: botId, type: identifier, required: true, constraints: "identificador do bot da U5, só referência" }
      - { name: decisionId, type: identifier, required: true, constraints: "identificador da decisão da U5, só referência" }
      - { name: side, type: enum, required: true, allowed: [BUY, SELL] }
      - { name: chain, type: text, required: true }
      - { name: tokenMint, type: text, required: true }
      - { name: requestedQuantity, type: decimal, required: true, min: 0, constraints: "maior que zero, nas casas do mint" }
      - { name: limitsVersion, type: integer, required: true, min: 1 }
      - { name: outcome, type: enum, required: true, allowed: [APPROVED, REJECTED] }
      - { name: evaluatedAt, type: instant, required: true, constraints: "UTC, do relógio injetável" }
    constraints:
      - "Único (tenantId, mode, botId, decisionId) (BR2.8)."
      - "Gravada antes da recusa, com inserção que não aborta a transação em conflito (insere se não existe, senão não faz nada e devolve se inseriu), para que a recusa por duplicidade continue na mesma transação (BR2.8)."
      - "A referência entre avaliação e recusa tem uma única direção: OrderRejection.evaluationId. A avaliação não aponta para a recusa; a recusa de uma avaliação REJECTED é a que tem o mesmo evaluationId e reasons diferente de [DUPLICATE]."
      - "Imutável depois de gravada: UPDATE e DELETE recusados para o papel da aplicação (BR4.3)."
    relationships:
      - { to: OrderRejection, cardinality: "1:0..N", direction: "OrderEvaluation -> OrderRejection", constraints: "a recusa da própria avaliação e as recusas por duplicidade que a referenciam" }

  - name: OrderRejection
    owner: RiskControl
    description: Uma ordem recusada pela barreira, com os motivos, a versão dos limites e os dados de mercado usados (AC4.6.1, Q10). É a entidade lida pela rota C21.
    attributes:
      - { name: rejectionId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: botId, type: identifier, required: true }
      - { name: decisionId, type: identifier, required: true }
      - { name: evaluationId, type: reference, required: true, references: OrderEvaluation, constraints: "a avaliação desta decisão; numa recusa por duplicidade, a avaliação original" }
      - { name: side, type: enum, required: true, allowed: [BUY, SELL] }
      - { name: chain, type: text, required: true }
      - { name: tokenMint, type: text, required: true }
      - { name: requestedQuantity, type: decimal, required: true }
      - { name: reasons, type: list, required: true, allowed: [ORDER_SIZE, TOKEN_EXPOSURE, TOTAL_EXPOSURE, SLIPPAGE, MIN_LIQUIDITY, STALE_DATA, INTERVAL, DUPLICATE, INSUFFICIENT_PAPER_BALANCE, DAILY_LOSS, DRAWDOWN], constraints: "ao menos um; sem repetição; na ordem fixa de BR3.1; o primeiro é o motivo principal" }
      - { name: pauseTriggers, type: list, required: true, allowed: [DAILY_LOSS, DRAWDOWN], default: "[]", constraints: "conjunto, pode ser vazio (BR3.3)" }
      - { name: limitsVersion, type: integer, required: true, min: 1 }
      - { name: market, type: object, required: true, constraints: "MarketInputs lidos nesta chamada (BR1.3 lê em toda chamada, inclusive na duplicada)" }
      - { name: evaluatedAt, type: instant, required: true }
    constraints:
      - "Toda recusa gera exatamente um evento ORDER_REJECTED na tabela de saída, na mesma transação (BR3.5)."
      - "DUPLICATE aparece sozinho em reasons: a recusa por duplicidade não roda as outras verificações (BR2.8)."
      - "Imutável depois de gravada (BR4.3)."
    relationships:
      - { to: RejectionReasonDetail, cardinality: "1:1..N", direction: "OrderRejection -> RejectionReasonDetail" }

  - name: RejectionReasonDetail
    owner: RiskControl
    description: O número que barrou um motivo, para explicar a recusa depois (Q10).
    attributes:
      - { name: rejectionId, type: reference, required: true, references: OrderRejection }
      - { name: reason, type: enum, required: true, allowed: [ORDER_SIZE, TOKEN_EXPOSURE, TOTAL_EXPOSURE, SLIPPAGE, MIN_LIQUIDITY, STALE_DATA, INTERVAL, DUPLICATE, INSUFFICIENT_PAPER_BALANCE, DAILY_LOSS, DRAWDOWN] }
      - { name: measured, type: text, required: true, constraints: "valor medido como texto decimal na escala da unidade, ou UNKNOWN quando o dado faltou; nunca zero no lugar de UNKNOWN" }
      - { name: limit, type: text, required: true, constraints: "valor do limite como texto decimal; em DUPLICATE, o evaluationId original" }
      - { name: unit, type: enum, required: true, allowed: [USDC, PERCENT, SECONDS, REFERENCE] }
    constraints:
      - "Único (rejectionId, reason): um detalhe por motivo de reasons, e nenhum detalhe sem motivo."

  - name: MarketInputs
    owner: RiskControl
    description: Valor embutido na recusa com os dados de mercado lidos pela C11 no momento da avaliação. Não é tabela própria.
    attributes:
      - { name: referencePrice, type: market-value, required: true, constraints: "KNOWN com valor (12 casas), fonte e horário de observação, ou UNKNOWN com o motivo" }
      - { name: liquidity, type: market-value, required: true, constraints: "KNOWN com valor (6 casas), fonte e horário, ou UNKNOWN com o motivo" }
      - { name: estimatedSlippagePct, type: text, required: true, constraints: "pontos com 4 casas, ou UNKNOWN quando preço ou liquidez não são utilizáveis (BR3.2)" }
      - { name: orderValue, type: text, required: true, constraints: "USDC 6 casas, ou UNKNOWN sem preço utilizável (BR3.2)" }

  - name: CandidateOrder
    owner: RiskControl
    description: A ordem candidata que a U5 submete à barreira (C14). Não é gravada como tal; seus campos vão para OrderEvaluation.
    attributes:
      - { name: botId, type: identifier, required: true }
      - { name: decisionId, type: identifier, required: true }
      - { name: side, type: enum, required: true, allowed: [BUY, SELL] }
      - { name: chain, type: text, required: true }
      - { name: tokenMint, type: text, required: true }
      - { name: requestedQuantity, type: decimal, required: true, min: 0, constraints: "maior que zero" }

  - name: LimitsSnapshot
    owner: RiskControl
    description: Os valores da versão de limites vigente, entregues pela U5 na chamada (o `BotLimitsVersionRef` da C14). A U6 não os guarda além do número da versão.
    attributes:
      - { name: version, type: integer, required: true, min: 1 }
      - { name: maxOrderSize, type: decimal, required: true, min: 0, constraints: "USDC, maior que zero" }
      - { name: maxTokenExposure, type: decimal, required: true, min: 0, constraints: "USDC, maior que zero, no máximo maxTotalExposure" }
      - { name: maxTotalExposure, type: decimal, required: true, min: 0, constraints: "USDC, maior que zero" }
      - { name: maxSlippagePct, type: decimal, required: true, min: 0, max: 100, constraints: "pontos, 4 casas" }
      - { name: minLiquidity, type: decimal, required: true, min: 0, constraints: "USDC" }
      - { name: minIntervalSeconds, type: integer, required: true, min: 0 }
      - { name: maxDailyLoss, type: decimal, required: true, min: 0, constraints: "USDC, maior que zero" }
      - { name: maxDrawdownPct, type: decimal, required: true, min: 0, max: 100, constraints: "pontos, 4 casas, maior que zero" }
```

## Resumo

| Entidade | O que é | Gravada? |
|---|---|---|
| `OrderEvaluation` | Primeira avaliação de cada decisão, com o resultado | Sim, uma por decisão |
| `OrderRejection` | Ordem recusada com motivos, gatilhos de pausa, versão dos limites e dados de mercado | Sim, uma por recusa |
| `RejectionReasonDetail` | Valor medido e limite de cada motivo | Sim, um por motivo |
| `MarketInputs` | Preço, liquidez, slippage estimado e valor da ordem usados | Embutida na recusa |
| `CandidateOrder` | Ordem submetida pela U5 | Não |
| `LimitsSnapshot` | Valores da versão de limites, entregues pela U5 | Não (só o número da versão) |

O frescor máximo de 60 segundos é uma constante da plataforma (Q10 das histórias), não um limite do bot. Por isso não aparece em `LimitsSnapshot`.
