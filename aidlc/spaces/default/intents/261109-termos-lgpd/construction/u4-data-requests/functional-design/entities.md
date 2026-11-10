# Entidades — U4 `u4-data-requests`

Conversa: português (Brasil). O modelo é agnóstico de tecnologia. Fontes:
- `inception/units-generation/unit-of-work.md`;
- `inception/user-stories/stories.md` (US5.1 a US5.9);
- `inception/requirements-analysis/requirements.md` (FR5, FR7.1);
- `inception/domain-design/components.md` (Privacy);
- `inception/contract-design/contract-summary.md` (P1, P2, J1 a J4);
- `functional-design-questions.md` (Q1 a Q8, todas A).

## Modelo

```yaml
entities:
  - name: DataSubjectRequest
    description: Pedido de titular feito pela pessoa na Conta ou registrado pelo administrador a partir do e-mail do encarregado
    attributes:
      - { name: requestId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: false, constraints: "da autenticação (origem ACCOUNT) ou da conta ligada (EMAIL); ausente em pedido por e-mail sem conta" }
      - { name: userId, type: uuid, required: false, constraints: "obrigatório em ACCOUNT; opcional em EMAIL" }
      - { name: type, type: enum, required: true, allowed: [ACCESS, CORRECTION, DELETION, OTHER] }
      - { name: origin, type: enum, required: true, allowed: [ACCOUNT, EMAIL] }
      - { name: details, type: text, required: true, min: 1, max: 1000, constraints: "nunca em log, evento, métrica ou erro" }
      - { name: contact, type: text, required: false, constraints: "e-mail; obrigatório em EMAIL, ausente em ACCOUNT; só no pedido" }
      - { name: status, type: enum, required: true, allowed: [RECEIVED, AWAITING_VERIFICATION, IN_PROGRESS, DONE, REFUSED], default: RECEIVED }
      - { name: receivedAt, type: instant, required: true, constraints: "UTC; em EMAIL, a data informada pelo administrador, nunca no futuro" }
      - { name: dueAt, type: instant, required: true, constraints: "receivedAt mais 15 dias corridos" }
      - { name: response, type: text, required: false, max: 4000 }
      - { name: refusalReason, type: text, required: false, max: 500, constraints: "obrigatório em REFUSED" }
      - { name: closedAt, type: instant, required: false, constraints: "preenchido em DONE ou REFUSED" }
      - { name: identityVerified, type: boolean, required: true, default: false }
      - { name: accessSummary, type: AccessSummary, required: false, constraints: "foto gravada ao concluir um ACCESS com liberação" }
      - { name: overdueNotifiedAt, type: instant, required: false, constraints: "data do único aviso de vencimento" }
      - { name: replySentAt, type: instant, required: false, constraints: "só em EMAIL encerrado" }
    constraints:
      - "um pedido aberto por (tenantId, userId, type) quando userId existe"
      - "estados encerrados (DONE, REFUSED) não mudam"
    relationships:
      - { to: IdentityVerification, cardinality: "1 para 0..1", direction: "tem" }

  - name: IdentityVerification
    description: Verificação por assinatura de um pedido por e-mail ligado a uma conta
    attributes:
      - { name: requestId, type: uuid, required: true, unique: true, references: DataSubjectRequest.requestId }
      - { name: message, type: text, required: true, constraints: "primeira linha fixa de verificação de pedido, domínio, identificador do pedido, finalidade e validade; não é segredo" }
      - { name: issuedAt, type: instant, required: true }
      - { name: expiresAt, type: instant, required: true, constraints: "issuedAt mais 24 h" }
      - { name: state, type: enum, required: true, allowed: [PENDING, CONFIRMED, EXPIRED] }
      - { name: confirmedAt, type: instant, required: false }
    constraints:
      - "a assinatura nunca é guardada"
      - "uso único: depois de CONFIRMED, nenhuma conferência é aceita"

  - name: AccessSummary
    description: Resumo dos dados da pessoa (objeto de valor, P1)
    attributes:
      - { name: walletMasked, type: text, required: true }
      - { name: provider, type: text, required: true }
      - { name: accountCreatedAt, type: instant, required: true }
      - { name: lastLoginAt, type: instant, required: false }
      - { name: acceptedVersions, type: list of (version, acceptedAt), required: true }
      - { name: botCount, type: integer, required: true, min: 0 }
      - { name: paperOrderCount, type: integer, required: true, min: 0 }
      - { name: feedbackCount, type: integer, required: true, min: 0 }
      - { name: requests, type: list of (type, status, receivedAt), required: true }
```

## Resumo

- **`DataSubjectRequest`:** o pedido, com o ciclo de vida e o prazo.
- **`IdentityVerification`:** a confirmação por assinatura dos pedidos por e-mail.
- **`AccessSummary`:** o resumo montado pelas interfaces públicas dos outros módulos e guardado como foto ao concluir.

Tudo pertence ao módulo Privacy. Os outros módulos são lidos só pelas interfaces J1 a J4.
