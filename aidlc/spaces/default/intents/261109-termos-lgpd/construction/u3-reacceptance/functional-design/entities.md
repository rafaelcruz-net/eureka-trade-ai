# Entidades — U3 `u3-reacceptance`

Conversa: português (Brasil). Fontes:
- `inception/units-generation/unit-of-work.md`;
- `inception/user-stories/stories.md` (US2.3, US4.1, US4.2);
- `inception/requirements-analysis/requirements.md` (FR2.3, FR4.1 a FR4.4);
- `inception/domain-design/components.md` (Identity);
- `inception/contract-design/contract-summary.md` (L2, L3);
- `functional-design-questions.md` (Q1 a Q7, todas A).

O modelo é agnóstico de tecnologia.

## Modelo

```yaml
entities:
  - name: TermsAcceptance
    description: Um aceite de uma versão dos termos por uma pessoa; registro só de inserção (existente, ampliado)
    attributes:
      - { name: acceptanceId, type: uuid, required: true, unique: true }
      - { name: userId, type: uuid, required: true, references: Account.userId }
      - { name: tenantId, type: uuid, required: true, constraints: "vem da autenticação" }
      - { name: termsVersion, type: text, required: true, constraints: "1 a 32 caracteres" }
      - { name: acceptedAt, type: instant, required: true, constraints: "UTC, relógio injetável" }
      - { name: source, type: enum, required: true, allowed: [INVITE, REACCEPTANCE], default: INVITE }
      - { name: inviteId, type: uuid, required: false, constraints: "obrigatório em INVITE; ausente em REACCEPTANCE" }
    constraints:
      - "uma pessoa tem no máximo um aceite por versão (userId, termsVersion únicos)"
      - "um convite gera no máximo um aceite"
      - "nenhum aceite é alterado nem apagado (só inserção)"
    relationships:
      - { to: Account, cardinality: "N para 1", direction: "pertence a" }

  - name: TermsStatus
    description: A situação da pessoa diante da versão vigente (objeto de valor, L2)
    attributes:
      - { name: acceptedVersion, type: text, required: true, constraints: "a do aceite mais recente" }
      - { name: currentVersion, type: text, required: true, constraints: "a vigente efetiva (BR1.16)" }
      - { name: acceptedAt, type: instant, required: true }
      - { name: upToDate, type: boolean, required: true, constraints: "acceptedVersion igual a currentVersion" }

  - name: ReacceptanceGate
    description: A regra que decide se uma requisição do convidado passa (L3)
    attributes:
      - { name: allowedPrefixes, type: list of text, required: true, constraints: "configurável; inclui legal, aceite, conta, logout e refresh; a U4 acrescenta privacy" }
      - { name: appliesToRole, type: enum, required: true, allowed: [BETA_USER] }

  - name: AcceptTermsCommand
    description: O pedido de aceite (L2)
    attributes:
      - { name: version, type: text, required: true }
      - { name: idempotencyKey, type: text, required: true, constraints: "mecanismo comum do Identity" }
```

## Resumo

- **`TermsAcceptance`:** passa a aceitar novos aceites (`REACCEPTANCE`) sem convite, com no máximo um aceite por pessoa e versão, e continua só de inserção.
- **`TermsStatus`:** é o que a Conta e a rota de aceite devolvem.
- **`ReacceptanceGate`:** é a trava das rotas do convidado.
