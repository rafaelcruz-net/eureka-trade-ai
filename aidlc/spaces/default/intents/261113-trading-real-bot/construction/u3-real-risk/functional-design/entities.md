# Entidades — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes:
- `inception/units-generation/unit-of-work.md` (U3);
- `inception/domain-design/components.md` (RiskControl, Ledger, BotWallet);
- `inception/contract-design/contract-summary.md` (M1, M2, M4, M5);
- `functional-design-questions.md` (Q1 a Q12);
- o código atual de `riskcontrol` (barreira PAPER), `ledger.api.RealLedger` e `botwallet.domain`.

## Modelo

```yaml
entities:
  - name: OrderIntent
    owner: RiskControl
    description: Pedido de avaliação de uma ordem REAL recebido pela M5 evaluate (amplia o OrderIntent do contrato com a origem e a decisão)
    attributes:
      - { name: tenantId, type: uuid, required: true, description: "do contexto de autenticação ou do bot, nunca do corpo" }
      - { name: userId, type: uuid, required: true }
      - { name: botId, type: uuid, required: false, description: "obrigatório na origem BOT" }
      - { name: decisionId, type: uuid, required: false, description: "decisão do ciclo do bot; obrigatório na origem BOT" }
      - { name: origin, type: enum, allowed: [BOT, USER_LIQUIDATION], required: true }
      - { name: side, type: enum, allowed: [BUY, SELL], required: true }
      - { name: chain, type: text, required: true, default: solana }
      - { name: mint, type: text, required: true }
      - { name: inputLamports, type: integer, required: false, min: 1, description: "obrigatório em BUY: lamports gastos na compra" }
      - { name: tokenBaseUnits, type: integer, required: false, min: 1, description: "obrigatório em SELL: quantidade vendida" }
    constraints:
      - "BUY só com origem BOT; USER_LIQUIDATION só com SELL"
  - name: GateDecision
    owner: RiskControl
    description: Resultado da M5 evaluate
    attributes:
      - { name: outcome, type: enum, allowed: [APPROVED, REJECTED], required: true }
      - { name: approvalId, type: uuid, required: false, description: "só em APPROVED de compra: a reserva da barreira" }
      - { name: code, type: enum, required: false, allowed: [REAL_PAUSED, SIMULATED_SOURCE_ACTIVE, TOKEN_NOT_ELIGIBLE, STALE_DATA, REAL_LIMIT_EXCEEDED, REAL_CAP_EXCEEDED] }
      - { name: detail, type: enum, required: false, allowed: [NOT_RELEASED, REAL_DISABLED, NO_BOT_WALLET, PAUSED_USER, PAUSED_DAILY_LOSS, PAUSED_ADMIN, PAUSED_DIVERGENCE, PAUSED_KILL_SWITCH, PAUSED_UPSTREAM, NO_ASSESSMENT, BLOCKED, SIGNAL_STALE, SIGNAL_UNKNOWN, POSITION_PRICE_UNKNOWN, ORDER_SIZE, DAILY_ORDER_COUNT, DAILY_LOSS, CAP, FREE_BALANCE] }
      - { name: figures, type: map-text-integer, required: false, description: "números usados na conferência, em lamports, unidades base ou pontos-base; só da própria carteira" }
  - name: RealRiskEvaluation
    owner: RiskControl
    description: Registro de cada avaliação REAL (tabela real_risk_evaluation), separado da avaliação PAPER
    attributes:
      - { name: id, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: botId, type: uuid, required: false }
      - { name: decisionId, type: uuid, required: false, unique: true, description: "única quando presente: avaliação repetida devolve o resultado gravado (BR6.1)" }
      - { name: origin, type: enum, allowed: [BOT, USER_LIQUIDATION], required: true }
      - { name: side, type: enum, allowed: [BUY, SELL], required: true }
      - { name: mint, type: text, required: true }
      - { name: inputLamports, type: integer, required: false }
      - { name: tokenBaseUnits, type: integer, required: false }
      - { name: outcome, type: enum, allowed: [APPROVED, REJECTED], required: true }
      - { name: code, type: enum, required: false }
      - { name: detail, type: enum, required: false }
      - { name: figures, type: json, required: false }
      - { name: evaluatedAt, type: instant-utc, required: true }
    constraints:
      - "append-only; a retenção segue a das avaliações PAPER"
  - name: GateReservation
    owner: RiskControl
    description: Reserva da barreira para uma compra aprovada (tabela real_risk_reservation)
    attributes:
      - { name: approvalId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, required: true }
      - { name: botId, type: uuid, required: true }
      - { name: decisionId, type: uuid, required: true, unique: true, description: "uma reserva por decisão" }
      - { name: mint, type: text, required: true }
      - { name: inputLamports, type: integer, required: true, min: 1 }
      - { name: state, type: enum, allowed: [OPEN, HANDED_OVER, EXPIRED], default: OPEN }
      - { name: createdAt, type: instant-utc, required: true }
      - { name: expiresAt, type: instant-utc, required: true, description: "createdAt + 5 min" }
      - { name: closedAt, type: instant-utc, required: false }
  - name: DaySnapshot
    owner: RiskControl
    description: Foto da carteira do bot no início do dia UTC (tabela real_risk_day)
    attributes:
      - { name: tenantId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, required: true }
      - { name: day, type: date-utc, required: true }
      - { name: startValueLamports, type: integer, required: true, min: 0 }
      - { name: startCapLamports, type: integer, required: true, min: 0 }
      - { name: takenAt, type: instant-utc, required: true }
      - { name: takenBy, type: enum, allowed: [DAY_JOB, FIRST_EVALUATION] }
    constraints:
      - "única por (botWalletId, day); nunca alterada depois de gravada"
  - name: BlockedToken
    owner: RiskControl
    description: Token na lista de bloqueio do administrador (tabela real_blocked_token)
    attributes:
      - { name: chain, type: text, required: true }
      - { name: mint, type: text, required: true }
      - { name: reason, type: text, required: true, max: 500 }
      - { name: blockedBy, type: uuid, required: true, description: "id do administrador" }
      - { name: blockedAt, type: instant-utc, required: true }
    constraints:
      - "única por (chain, mint); desbloquear apaga a linha e gera evento de auditoria com o motivo anterior"
  - name: RealStateView
    owner: BotWallet
    description: Leitura da M1 readRealState exposta em botwallet.api (Q11)
    attributes:
      - { name: botWalletId, type: uuid, required: false }
      - { name: realEnabled, type: boolean, required: true }
      - { name: released, type: boolean, required: true, description: "liberação vigente, depois da quarentena" }
      - { name: mode, type: enum, allowed: [SIGNED, AUTO_EXITS, AUTO_FULL] }
      - { name: capLamports, type: integer, min: 0 }
      - { name: feeReserveLamports, type: integer, min: 0 }
      - { name: pausedReasons, type: set-of-enum, allowed: [USER, DIVERGENCE, DAILY_LOSS, KILL_SWITCH, ADMIN, UPSTREAM] }
      - { name: pausedSince, type: map-enum-instant-utc, description: "início de cada pausa ativa, por motivo (BR4.5)" }
  - name: RealPosition
    owner: Ledger
    description: Posição aberta lida pela M2 positions (Q11), derivada dos lançamentos SWAP_FILL_IN e SWAP_FILL_OUT
    attributes:
      - { name: mint, type: text, required: true }
      - { name: tokenBaseUnits, type: integer, required: true, min: 1 }
      - { name: costLamports, type: integer, required: true, min: 0, description: "custo médio da quantidade ainda aberta, com taxas de compra" }
  - name: RealFlows
    owner: Ledger
    description: Depósitos e saques desde um instante, lidos pela M2 flowsSince (Q11)
    attributes:
      - { name: depositedLamports, type: integer, min: 0 }
      - { name: withdrawnLamports, type: integer, min: 0, description: "inclui a taxa do saque" }
```

## Relações

- Uma `GateReservation` nasce de uma `RealRiskEvaluation` aprovada de compra (1 para 0..1).
- Uma carteira do bot tem no máximo uma `DaySnapshot` por dia UTC.
- `BlockedToken` vale para todos os tenants: a lista é do administrador.
- `RealStateView`, `RealPosition` e `RealFlows` não são tabelas da U3. São leituras da BotWallet e do Ledger pelas interfaces públicas.

## Resumo

A U3 guarda quatro coisas:
- as avaliações;
- as reservas das compras aprovadas;
- a foto do dia;
- a lista de bloqueio.

O resto ela lê pela M1 (estado da carteira), pela M2 (saldo, posições e fluxos), pela M4 (preço, frescor e tipo de fonte) e pela avaliação do scanner. Todas as tabelas são REAL e filtradas por tenant, exceto a lista de bloqueio, que é global. Valores em lamports e unidades base; percentuais em pontos-base.
