# Entidades — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes:
- `inception/domain-design/components.md` (RealExecution, ChainWatcher);
- `inception/contract-design/contract-summary.md` (R1, R3, R6, M1 a M6, X1, X3, adendos da U1 e da U3);
- `functional-design-questions.md` (Q1 a Q15);
- o código atual: `bots.domain.Bot`, `botwallet`, `ledger.api.RealLedger`, `riskcontrol.api.RealRiskGate`.

## Modelo

```yaml
entities:
  - name: BotMarket
    owner: Bots
    description: Mercado do bot, fixo desde a criação (Q1)
    attributes:
      - { name: market, type: enum, allowed: [PAPER, REAL], default: PAPER, required: true }
    constraints:
      - "nunca muda depois da criação"
      - "REAL só com carteira do bot ativa do mesmo usuário"
  - name: RealDecisionRequest
    owner: RealExecution
    description: Decisão REAL gravada pelo ciclo pela M6 (tabela real_decision_request)
    attributes:
      - { name: decisionRequestId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: botId, type: uuid, required: true }
      - { name: decisionId, type: uuid, required: true, unique: true }
      - { name: approvalId, type: uuid, required: false, description: "reserva da barreira; só na compra" }
      - { name: side, type: enum, allowed: [BUY, SELL], required: true }
      - { name: mint, type: text, required: true }
      - { name: inputLamports, type: integer, required: false, min: 1 }
      - { name: tokenBaseUnits, type: integer, required: false, min: 1 }
      - { name: reason, type: text, required: true, max: 500, description: "razão do bot para a tela de assinatura (AC3.2.1)" }
      - { name: state, type: enum, allowed: [PENDING, CLAIMED, PROCESSED, DISCARDED], default: PENDING }
      - { name: discardReason, type: enum, required: false, allowed: [RESERVATION_EXPIRED, BOT_NOT_OWNED, ORDER_IN_PROGRESS, STATE_CHANGED, LIQUIDATION_ACTIVE] }
      - { name: createdAt, type: instant-utc, required: true }
      - { name: processedAt, type: instant-utc, required: false }
  - name: RealOrder
    owner: RealExecution
    description: Ordem REAL com estados gravados (tabela real_order)
    attributes:
      - { name: realOrderId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: botId, type: uuid, required: false, description: "vazio na liquidação" }
      - { name: liquidationId, type: uuid, required: false }
      - { name: origin, type: enum, allowed: [BOT, USER_LIQUIDATION], required: true }
      - { name: idempotencyKey, type: text, required: true, unique: true, description: "decisionRequestId, ou liquidationId + mint" }
      - { name: side, type: enum, allowed: [BUY, SELL], required: true }
      - { name: mint, type: text, required: true }
      - { name: inputLamports, type: integer, required: false }
      - { name: inputBaseUnits, type: integer, required: false }
      - { name: operationMode, type: enum, allowed: [SIGNED, AUTO_EXITS, AUTO_FULL], required: true, description: "modo no momento da criação" }
      - { name: state, type: enum, allowed: [PROPOSED, QUOTED, SIGNED, SENT, CONFIRMED, FAILED, AMBIGUOUS, CANCELLED], required: true }
      - { name: quoteAt, type: instant-utc, required: false }
      - { name: expectedOutBaseUnits, type: integer, required: false }
      - { name: minOutBaseUnits, type: integer, required: false }
      - { name: maxSlippageBps, type: integer, min: 1, max: 500, required: true }
      - { name: signingDeadline, type: instant-utc, required: false, description: "quoteAt + 8 s no modo Assinada" }
      - { name: messageDigest, type: text, required: false, description: "SHA-256 da mensagem montada" }
      - { name: txSignature, type: text, required: false, unique: true, description: "gravada antes do envio" }
      - { name: lastValidBlockHeight, type: integer, required: false }
      - { name: outBaseUnits, type: integer, required: false, description: "lido da rede na confirmação" }
      - { name: feeLamports, type: integer, required: false }
      - { name: feePayer, type: enum, allowed: [BOT_WALLET, MAIN_WALLET], required: false, description: "MAIN_WALLET no modo Assinada e nas ordens a mercado da Automática nas saídas" }
      - { name: signingClaimedAt, type: instant-utc, required: false, description: "reivindicação da sessão de assinatura (compare-and-set), BR4.2" }
      - { name: failureCode, type: enum, required: false, allowed: [QUOTE_EXPIRED, UPSTREAM_UNAVAILABLE, SIGNING_REFUSED, SLIPPAGE_EXCEEDED, BLOCKHASH_EXPIRED, CHAIN_REJECTED, SIGNING_DEADLINE_EXPIRED, USER_REJECTED, MODE_LOWERED, REAL_DISABLED, PAUSED, KILL_SWITCH, RISK_REJECTED, ROUTE_TOO_LARGE, LIQUIDATION] }
      - { name: createdAt, type: instant-utc, required: true }
      - { name: updatedAt, type: instant-utc, required: true }
    constraints:
      - "no máximo uma ordem por bot nos estados PROPOSED, QUOTED, SIGNED, SENT, AMBIGUOUS (índice único parcial)"
      - "no máximo uma venda por usuário e mint nesses estados (índice único parcial)"
      - "toda transição é UPDATE condicional ao estado esperado"
  - name: OrderAttempt
    owner: RealExecution
    description: Cada envio da mesma transação assinada (tabela real_order_attempt)
    attributes:
      - { name: attemptId, type: uuid, required: true }
      - { name: realOrderId, type: uuid, required: true }
      - { name: sentAt, type: instant-utc, required: true }
      - { name: outcome, type: enum, allowed: [ACCEPTED, RPC_ERROR, REJECTED], required: true }
  - name: SigningSession
    owner: RealExecution
    description: Visão da ordem QUOTED no modo Assinada (contrato R3)
    attributes:
      - { name: transactionBase64, type: text, required: true, description: "mensagem montada, pagador = carteira principal" }
      - { name: messageDigest, type: text, required: true }
      - { name: deadline, type: instant-utc, required: true }
      - { name: display, type: object, required: true, description: "lado, entrada, saída esperada, saída mínima, taxa e razão do bot" }
  - name: Liquidation
    owner: RealExecution
    description: Pedido "vender tudo e sacar" (tabela real_liquidation)
    attributes:
      - { name: liquidationId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: state, type: enum, allowed: [SELLING, WITHDRAWING, DONE, PARTIAL], required: true }
      - { name: withdrawalId, type: uuid, required: false }
      - { name: remaining, type: json, required: false, description: "posições que ficaram, com o motivo" }
      - { name: createdAt, type: instant-utc, required: true }
    constraints:
      - "no máximo uma liquidação em SELLING ou WITHDRAWING por usuário"
  - name: UpstreamHealth
    owner: RealExecution
    description: Falhas seguidas por fornecedor e por usuário (Q14)
    attributes:
      - { name: userId, type: uuid, required: true }
      - { name: provider, type: enum, allowed: [JUPITER, RPC, TXSIGNER], required: true }
      - { name: consecutiveFailures, type: integer, min: 0 }
      - { name: healthySince, type: instant-utc, required: false }
  - name: SwapSigningRequest
    owner: TxSigner
    description: Pedido de assinatura de swap ao TxSigner (R1 /signatures, operação SWAP)
    attributes:
      - { name: signingRequestId, type: uuid, required: true, description: "= realOrderId; repetir com conteúdo diferente é recusado" }
      - { name: transactionBase64, type: text, required: true }
      - { name: mode, type: enum, allowed: [SIGNED, AUTO_FULL], required: true }
      - { name: inputLamports, type: integer, required: false }
      - { name: inputBaseUnits, type: integer, required: false }
      - { name: quotedOut, type: integer, required: true }
      - { name: minOut, type: integer, required: true }
      - { name: maxSlippageBps, type: integer, required: true }
      - { name: feeLamports, type: integer, required: true }
  - name: RealPnl
    owner: Pnl
    description: P&L REAL por custo médio (Q10), só do ledger REAL
    attributes:
      - { name: realizedLamports, type: signed-integer }
      - { name: unrealized, type: signed-integer-or-unknown }
      - { name: feesLamports, type: integer }
      - { name: positions, type: list }
```

## Relações

- Uma `RealDecisionRequest` gera no máximo uma `RealOrder`; a `idempotencyKey` é o próprio `decisionRequestId`.
- Uma `RealOrder` tem zero ou mais `OrderAttempt`, sempre com a mesma transação assinada.
- Uma `Liquidation` tem uma `RealOrder` de venda por posição e no máximo um saque.
- A `RealOrder` usa a carteira do bot do usuário, que é dona dos fundos nos três modos. No modo Assinada, a carteira principal é pagadora da taxa e cossignatária.

## Resumo

A U4 guarda as decisões, as ordens, os envios, as liquidações e a saúde dos fornecedores, além do mercado do bot. O ledger, a barreira, a carteira e a rede são lidos e escritos pelas interfaces M1, M2, M3 e M5. O TxSigner ganha o pedido de assinatura de swap.
