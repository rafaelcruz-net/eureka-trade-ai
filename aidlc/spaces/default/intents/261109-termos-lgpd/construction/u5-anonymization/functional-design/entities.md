# Entidades — U5 `u5-anonymization`

Conversa: português (Brasil). O modelo é agnóstico de tecnologia. Fontes:
- `inception/units-generation/unit-of-work.md`;
- `inception/user-stories/stories.md` (US6.1 a US6.3);
- `inception/requirements-analysis/requirements.md` (FR6, NFR5, NFR9);
- `inception/domain-design/components.md`;
- `inception/contract-design/contract-summary.md` (P2 anonimização, J1 a J3, J6);
- `functional-design-questions.md` (Q1 a Q5, todas A).

## Modelo

```yaml
entities:
  - name: Account
    description: Conta do convidado (Identity, existente), com o estado novo ANONYMIZED
    attributes:
      - { name: userId, type: uuid, required: true, unique: true }
      - { name: status, type: enum, required: true, allowed: [ACTIVE, DISABLED, ANONYMIZED] }
    constraints:
      - "ANONYMIZED é final: nenhum login, renovação ou sessão nova"

  - name: Wallet
    description: Carteira da conta (Identity, existente), sem endereço depois da anonimização
    attributes:
      - { name: address, type: text, required: false, unique: true, constraints: "nulo só com anonymizedAt preenchido" }
      - { name: provider, type: enum, required: false, allowed: [PHANTOM, SOLFLARE], constraints: "nulo só com anonymizedAt preenchido" }
      - { name: anonymizedAt, type: instant, required: false }

  - name: AnonymizationResult
    description: Resultado da anonimização (objeto de valor, J1), igual na repetição
    attributes:
      - { name: userId, type: uuid, required: true }
      - { name: anonymizedAt, type: instant, required: true }
      - { name: sessionsRevoked, type: integer, required: true, min: 0 }
      - { name: botsStopped, type: integer, required: true, min: 0 }
      - { name: feedbackDeleted, type: integer, required: true, min: 0 }
      - { name: alreadyAnonymized, type: boolean, required: true }

  - name: AnonymizedBot
    description: Bot da pessoa depois da anonimização (Bots, existente)
    attributes:
      - { name: name, type: text, required: true, constraints: "Bot anonimizado" }
      - { name: state, type: enum, required: true, allowed: [STOPPED] }
    constraints:
      - "posições PAPER abertas ficam como estão; nenhuma ordem nova"
```

## Resumo

A U5 não cria entidade nova de negócio:
- **`Account`:** ganha o estado `ANONYMIZED`.
- **`Wallet`:** passa a aceitar endereço e provedor nulos depois da anonimização.
- **`AnonymizationResult`:** descreve o que foi feito.

Ledger, ordens, recusas, aceites e a trilha ficam intactos, ligados só ao identificador interno.
