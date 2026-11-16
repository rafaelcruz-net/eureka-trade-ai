# Entidades — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: `inception/domain-design/components.md` (BotWallet, Administration, Privacy), `inception/contract-design/contract-summary.md` (R4, M1, M2, M5, M6, M7), `functional-design-questions.md` (Q1 a Q12) e o código atual (`real_release` da U1, `PauseReason`, `bot_cycle` e `decision` da U4).

## Modelo

```yaml
entities:
  - name: RealRelease
    owner: BotWallet
    description: Liberação do REAL de um usuário numa rede (tabela real_release da U1, ampliada)
    attributes:
      - { name: releaseId, type: uuid, required: true, unique: true }
      - { name: userId, type: uuid, required: true }
      - { name: network, type: enum, allowed: [LOCALNET, DEVNET, MAINNET], required: true }
      - { name: requestedBy, type: uuid, required: true, description: "administrador" }
      - { name: requestedAt, type: instant-utc, required: true }
      - { name: effectiveAt, type: instant-utc, required: true, description: "requestedAt + 24 h" }
      - { name: state, type: enum, allowed: [QUARANTINE, ACTIVE, BLOCKED, REVOKED], default: QUARANTINE }
      - { name: blockedReason, type: enum, required: false, allowed: [CI_RED, CI_UNREACHABLE, DIVERGENCE_OPEN, KILL_SWITCH_ON, NETWORK_MISMATCH] }
      - { name: gatesCheckedAt, type: instant-utc, required: false }
      - { name: revokedBy, type: uuid, required: false }
      - { name: revokedAt, type: instant-utc, required: false }
    constraints:
      - "no máximo uma liberação não revogada por usuário e rede"
      - "na MAINNET, no máximo 20 liberações não revogadas de convidados (fora o fundador)"
  - name: GateSnapshot
    owner: BotWallet
    description: Resultado dos portões conferidos ao vencer a quarentena (Q1)
    attributes:
      - { name: ciGreen, type: boolean, required: true }
      - { name: ciRunRef, type: text, required: false, description: "id da execução da CI em main lida" }
      - { name: noOpenDivergence, type: boolean, required: true }
      - { name: killSwitchOff, type: boolean, required: true }
      - { name: networkMatches, type: boolean, required: true }
  - name: ReconciliationRun
    owner: BotWallet
    description: Reconciliação de uma carteira do bot com a rede (tabela real_reconciliation_run)
    attributes:
      - { name: runId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, required: true }
      - { name: trigger, type: enum, allowed: [ORDER_CONFIRMED, SCHEDULE, AFTER_COMPENSATION, AFTER_RESTORE], required: true }
      - { name: slot, type: integer, required: true, description: "último slot finalizado lido" }
      - { name: chainLamports, type: integer, required: true }
      - { name: ledgerLamports, type: integer, required: true }
      - { name: tokenDiffs, type: json, required: true, description: "por mint: rede, ledger e diferença em unidades base" }
      - { name: inTransitOrders, type: integer, required: true }
      - { name: result, type: enum, allowed: [CLEAN, DIVERGENT, DEFERRED, UNAVAILABLE], required: true, description: "DEFERRED: carteira não assentada (ordem ou saque em trânsito, entrada ainda não registrada)" }
      - { name: startedAt, type: instant-utc, required: true }
      - { name: finishedAt, type: instant-utc, required: true }
  - name: Divergence
    owner: BotWallet
    description: Divergência aberta por uma reconciliação (tabela real_divergence)
    attributes:
      - { name: divergenceId, type: uuid, required: true, unique: true }
      - { name: runId, type: uuid, required: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, required: true }
      - { name: lamportsDiff, type: signed-integer, required: true, description: "rede − ledger" }
      - { name: tokenDiffs, type: json, required: true }
      - { name: state, type: enum, allowed: [OPEN, COMPENSATED, RESOLVED], default: OPEN }
      - { name: compensationEntryIds, type: list-of-uuid, required: false, description: "um lançamento por ativo divergente (SOL e cada mint)" }
      - { name: lastRunId, type: uuid, required: true, description: "última reconciliação que mediu a divergência; a compensação confere contra ela" }
      - { name: compensatedBy, type: uuid, required: false }
      - { name: reason, type: text, required: false, max: 500 }
    constraints:
      - "no máximo uma divergência OPEN ou COMPENSATED por carteira"
  - name: RealKillSwitch
    owner: Administration
    description: Interruptor global do REAL (tabela real_kill_switch, uma linha), lido pela M7
    attributes:
      - { name: active, type: boolean, default: false }
      - { name: activatedAt, type: instant-utc, required: false }
      - { name: activatedBy, type: uuid, required: false }
      - { name: reason, type: text, required: false, max: 500 }
      - { name: version, type: integer, required: true, description: "compare-and-set nas mudanças" }
  - name: PendingTxSignerCommand
    owner: BotWallet
    description: Comando ao TxSigner ainda não confirmado (LOWER_CAP, DEACTIVATE), reenviado a cada minuto (Q8, Q10)
    attributes:
      - { name: commandId, type: uuid, required: true, unique: true, description: "requestId idempotente no TxSigner" }
      - { name: botWalletId, type: uuid, required: true }
      - { name: kind, type: enum, allowed: [LOWER_CAP, DEACTIVATE], required: true }
      - { name: payload, type: json, required: true, description: "só ids e valores; nunca endereço" }
      - { name: attempts, type: integer, default: 0 }
      - { name: confirmedAt, type: instant-utc, required: false }
  - name: RestoreMarker
    owner: BotWallet
    description: Exigência de reconciliação completa depois de restauração do banco (Q11, BR2.5)
    attributes:
      - { name: detectedAt, type: instant-utc, required: true }
      - { name: unknownSignatures, type: integer, required: true, description: "assinaturas da carteira do bot na rede que o banco não conhece" }
      - { name: confirmedBy, type: uuid, required: false, description: "administrador que confirma depois da reconciliação limpa" }
      - { name: clearedAt, type: instant-utc, required: false }
  - name: PauseReason (ampliado)
    owner: BotWallet
    description: Motivos novos de pausa global (expansão do enum da U1)
    attributes:
      - { name: values, type: enum, allowed: [USER, DIVERGENCE, DAILY_LOSS, KILL_SWITCH, ADMIN, UPSTREAM, RESTORE, RESTORE_CHECK_PENDING] }
    constraints:
      - "KILL_SWITCH, RESTORE e RESTORE_CHECK_PENDING são globais: a M1 readRealState os inclui pela leitura ao vivo, sem linha por usuário"
  - name: RealUserView
    owner: Administration
    description: Visão do administrador por usuário (R4), sem chave e com a carteira principal abreviada (Q9)
    attributes:
      - { name: userId, type: uuid }
      - { name: mainWalletShort, type: text, description: "4 primeiros … 4 últimos" }
      - { name: botWalletAddress, type: text }
      - { name: mode, type: enum, allowed: [SIGNED, AUTO_EXITS, AUTO_FULL] }
      - { name: capLamports, type: integer }
      - { name: balance, type: object, description: "livre, reservado, acima do teto" }
      - { name: release, type: object, description: "estado, efeito, motivo de bloqueio" }
      - { name: pausedReasons, type: set-of-enum }
      - { name: recentOrders, type: list, description: "últimas 20" }
      - { name: openDivergences, type: list }
```

## Relações

- Um usuário tem no máximo uma `RealRelease` não revogada por rede.
- Uma `ReconciliationRun` `DIVERGENT` abre no máximo uma `Divergence` por carteira. Uma divergência já aberta é atualizada, nunca duplicada.
- O `RealKillSwitch` é único e global.
- `PendingTxSignerCommand` pertence a uma carteira do bot.

## Resumo

A U5 guarda a liberação com os portões, as reconciliações e as divergências, o kill switch REAL, os comandos pendentes ao TxSigner e a marca de restauração. A visão do administrador é montada na leitura, a partir de M1, M2, M6 e destas tabelas.
