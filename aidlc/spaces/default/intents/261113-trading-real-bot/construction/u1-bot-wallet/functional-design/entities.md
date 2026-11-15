# Entidades — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `inception/domain-design/components.md` (TxSigner, BotWallet, Ledger, Audit, Identity), `inception/contract-design/contract-summary.md` (R1, R2, M1, M2, M8, M9), `inception/units-generation/unit-of-work.md` (U1), `inception/requirements-analysis/requirements.md` e `functional-design-questions.md` (Q1 a Q11). O bloco YAML é a fonte da verdade; tipos lógicos, sem tecnologia.

## Modelo

```yaml
entities:
  - name: BotWalletAccount
    owner: BotWallet
    description: Carteira do bot de um usuário; uma por usuário, chave só no KMS
    attributes:
      - { name: botWalletId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true, unique: true, references: Account.userId }
      - { name: publicAddress, type: solana-address, required: true, unique: true }
      - { name: kmsKeyRef, type: opaque-text, required: true, constraints: [nunca devolvido em resposta nem log] }
      - { name: mainWalletAddress, type: solana-address, required: true, constraints: [igual à carteira verificada no login; nunca muda] }
      - { name: network, type: enum, allowed: [LOCALNET, DEVNET, MAINNET], required: true }
      - { name: status, type: enum, allowed: [ACTIVE, CLOSED], default: ACTIVE }
      - { name: depositCursor, type: tx-signature, description: última entrada processada pela varredura (BR3.1) }
      - { name: createdAt, type: instant-utc, required: true }
      - { name: closedAt, type: instant-utc }
    constraints:
      - uma carteira ACTIVE por usuário
      - a linha é travada de forma exclusiva durante o pedido de saque (BR4.9)
    relationships:
      - { to: RealPreferences, cardinality: "1:1" }
      - { to: WalletConsent, cardinality: "1:N" }
      - { to: ObservedDeposit, cardinality: "1:N" }
      - { to: Withdrawal, cardinality: "1:N" }

  - name: RealPreferences
    owner: BotWallet
    description: Preferências REAL da carteira do bot
    attributes:
      - { name: botWalletId, type: uuid, required: true, unique: true, references: BotWalletAccount.botWalletId }
      - { name: operationMode, type: enum, allowed: [SIGNED, AUTO_EXITS, AUTO_FULL], default: SIGNED }
      - { name: capLamports, type: integer-lamports, required: true, min: 0, constraints: [<= maxCapLamports] }
      - { name: maxCapLamports, type: integer-lamports, required: true, default: 10000000000, max: 10000000000 }
      - { name: maxSlippageBps, type: integer, default: 200, min: 0, max: 500 }
      - { name: feeReserveLamports, type: integer-lamports, required: true, default: 30000000, constraints: [valor por ambiente] }
      - { name: maxWithdrawalFeeLamports, type: integer-lamports, required: true, default: 100000, constraints: [valor por ambiente; espelhado na política do TxSigner] }
      - { name: pausedReasons, type: set-of-enum, allowed: [USER, DIVERGENCE, DAILY_LOSS, KILL_SWITCH, ADMIN, UPSTREAM], default: [] }
      - { name: updatedAt, type: instant-utc, required: true }

  - name: ConsentChallenge
    owner: BotWallet
    description: Desafio emitido para um consentimento assinado
    attributes:
      - { name: challengeId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: kind, type: enum, allowed: [ACTIVATE, RAISE_MODE, RAISE_CAP], required: true }
      - { name: message, type: text, required: true, constraints: [formato canônico de 10 linhas do contract-summary] }
      - { name: nonce, type: text, required: true, unique: true }
      - { name: issuedAt, type: instant-utc, required: true }
      - { name: expiresAt, type: instant-utc, required: true, constraints: [issuedAt + 5 min] }
      - { name: state, type: enum, allowed: [PENDING, IN_USE, CONSUMED, EXPIRED], default: PENDING, description: IN_USE enquanto a ativação espera o TxSigner (BR1.10) }
      - { name: consumedAt, type: instant-utc }

  - name: WalletConsent
    owner: BotWallet
    description: Consentimento aceito, assinado pela carteira principal
    attributes:
      - { name: consentId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, references: BotWalletAccount.botWalletId, constraints: [vazio só durante a ativação] }
      - { name: kind, type: enum, allowed: [ACTIVATE, RAISE_MODE, RAISE_CAP], required: true }
      - { name: nonce, type: text, required: true, unique: true }
      - { name: expiresAt, type: instant-utc, required: true }
      - { name: authorizedMode, type: enum, allowed: [SIGNED, AUTO_EXITS, AUTO_FULL], required: true }
      - { name: authorizedCapLamports, type: integer-lamports, required: true }
      - { name: signedMessageDigest, type: sha256-hex, required: true }
      - { name: signedAt, type: instant-utc, required: true }

  - name: UsedNonce
    owner: BotWallet
    description: Nonce já usado; guardado até a validade mais um dia (Q8)
    attributes:
      - { name: nonce, type: text, required: true, unique: true }
      - { name: purgeAfter, type: instant-utc, required: true, constraints: [expiresAt + 1 dia] }

  - name: RealRelease
    owner: BotWallet
    description: Liberação do REAL para um usuário numa rede; na U1 só por semente de teste (Q9)
    attributes:
      - { name: releaseId, type: uuid, required: true, unique: true }
      - { name: userId, type: uuid, required: true, references: Account.userId }
      - { name: network, type: enum, allowed: [LOCALNET, DEVNET, MAINNET], required: true }
      - { name: requestedBy, type: uuid, required: true }
      - { name: requestedAt, type: instant-utc, required: true }
      - { name: effectiveAt, type: instant-utc, required: true, constraints: [requestedAt + 24 h] }
      - { name: revokedAt, type: instant-utc }

  - name: ObservedDeposit
    owner: BotWallet
    description: Entrada de SOL finalizada na carteira do bot
    attributes:
      - { name: txSignature, type: tx-signature, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, required: true, references: BotWalletAccount.botWalletId }
      - { name: sourceAddressHmac, type: hmac-hex, required: true }
      - { name: lamports, type: integer-lamports, required: true, min: 1 }
      - { name: finalizedAt, type: instant-utc, required: true }
      - { name: knownSource, type: boolean, required: true }

  - name: Withdrawal
    owner: BotWallet
    description: Saque só para a carteira principal registrada
    attributes:
      - { name: withdrawalId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: botWalletId, type: uuid, required: true, references: BotWalletAccount.botWalletId }
      - { name: kind, type: enum, allowed: [PARTIAL, TOTAL], required: true }
      - { name: lamports, type: integer-lamports, required: true, min: 1 }
      - { name: feeLamports, type: integer-lamports }
      - { name: state, type: enum, allowed: [REQUESTED, SIGNED, SENT, CONFIRMED, FAILED, AMBIGUOUS], default: REQUESTED }
      - { name: idempotencyKey, type: uuid, required: true }
      - { name: signingRequestId, type: uuid, required: true, unique: true }
      - { name: blockhash, type: text }
      - { name: lastValidHeight, type: integer }
      - { name: signedTransaction, type: base64-bytes, description: transação assinada guardada para reenvio; nunca uma segunda assinatura (BR4.7) }
      - { name: messageDigest, type: sha256-hex }
      - { name: txSignature, type: tx-signature }
      - { name: failureCode, type: text }
      - { name: requestedAt, type: instant-utc, required: true }
      - { name: updatedAt, type: instant-utc, required: true }
    constraints:
      - (tenantId, idempotencyKey) único

  - name: ReconciliationMark
    owner: BotWallet
    description: Pedido de reconciliação deixado pela U1 (a execução é da U5)
    attributes:
      - { name: botWalletId, type: uuid, required: true }
      - { name: reason, type: enum, allowed: [WITHDRAWAL_CONFIRMED, DEPOSIT_UNKNOWN_SOURCE], required: true }
      - { name: createdAt, type: instant-utc, required: true }

  - name: WalletPolicy
    owner: TxSigner
    description: Política própria do TxSigner por carteira, derivada só de consentimentos que ele confere
    attributes:
      - { name: botWalletAddress, type: solana-address, required: true, unique: true }
      - { name: kmsKeyRef, type: opaque-text, required: true }
      - { name: mainWalletAddress, type: solana-address, required: true, constraints: [fixado na criação] }
      - { name: authorizedCapLamports, type: integer-lamports, required: true }
      - { name: authorizedMode, type: enum, allowed: [SIGNED, AUTO_EXITS, AUTO_FULL], required: true }
      - { name: closed, type: boolean, default: false }
      - { name: creationRequestId, type: uuid, required: true, unique: true, description: challengeId da ativação; repetir devolve a mesma carteira (BR1.10) }
      - { name: maxWithdrawalFeeLamports, type: integer-lamports, required: true, default: 100000 }
      - { name: updatedAt, type: instant-utc, required: true }

  - name: SignerUsedNonce
    owner: TxSigner
    description: Nonces já usados, no banco próprio do TxSigner
    attributes:
      - { name: nonce, type: text, required: true, unique: true }
      - { name: purgeAfter, type: instant-utc, required: true }

  - name: SigningRecord
    owner: TxSigner
    description: Registro de cada pedido de assinatura (assinado ou recusado)
    attributes:
      - { name: signingRequestId, type: uuid, required: true, unique: true }
      - { name: botWalletAddress, type: solana-address, required: true }
      - { name: operationKind, type: enum, allowed: [WITHDRAWAL, SWAP_BUY, SWAP_SELL], required: true }
      - { name: messageDigest, type: sha256-hex, required: true }
      - { name: decision, type: enum, allowed: [SIGNED, REFUSED], required: true }
      - { name: refusalCode, type: text }
      - { name: signature, type: tx-signature }
      - { name: decidedAt, type: instant-utc, required: true }

  - name: LedgerEntry
    owner: Ledger
    description: Lançamento do ledger existente, agora com modo REAL; só inclusão
    attributes:
      - { name: entryId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true }
      - { name: mode, type: enum, allowed: [PAPER, REAL], required: true }
      - { name: entryKind, type: enum, allowed: [DEPOSIT, WITHDRAWAL, NETWORK_FEE, RESERVE, RESERVE_RELEASE, COMPENSATION, SWAP_FILL_IN, SWAP_FILL_OUT, RENT], required: true }
      - { name: botWalletId, type: uuid, constraints: [obrigatório quando mode = REAL] }
      - { name: lamports, type: signed-integer-lamports, required: true, description: "com sinal nos lançamentos de saldo; sempre positivo em RESERVE e RESERVE_RELEASE (BR3.5)" }
      - { name: txSignature, type: tx-signature }
      - { name: reference, type: text, description: id do depósito, saque ou ordem que originou }
      - { name: idempotencyKey, type: text, required: true, unique: true }
      - { name: createdAt, type: instant-utc, required: true }
    constraints:
      - nunca alterado nem apagado; correção só por COMPENSATION
      - na U1 só DEPOSIT, WITHDRAWAL, NETWORK_FEE, RESERVE, RESERVE_RELEASE; SWAP_* e RENT na U4; COMPENSATION na U5
```

## Resumo

| Entidade | Dono | Papel na U1 |
|---|---|---|
| BotWalletAccount | BotWallet | carteira do bot, uma por usuário |
| RealPreferences | BotWallet | modo, teto, teto máximo, reserva, pausas |
| ConsentChallenge, WalletConsent, UsedNonce | BotWallet | desafio, consentimento aceito e nonces usados |
| RealRelease | BotWallet | liberação por usuário e rede (semente de teste na U1) |
| ObservedDeposit | BotWallet | depósitos finalizados |
| Withdrawal | BotWallet | saques e seus estados |
| ReconciliationMark | BotWallet | pedido de reconciliação para a U5 |
| WalletPolicy, SignerUsedNonce, SigningRecord | TxSigner | política própria, nonces e registro de assinaturas, em banco separado |
| LedgerEntry | Ledger | lançamentos REAL da U1 |
