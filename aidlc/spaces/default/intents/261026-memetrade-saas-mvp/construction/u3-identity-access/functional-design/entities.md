# Entidades — U3 Convites, contas e sessões

Modelo de dados do IdentityAccess completo. A base é [unit-of-work.md](../../../inception/units-generation/unit-of-work.md) e [components.md](../../../inception/domain-design/components.md). Também entram os contratos C1, C3, C8 e C17 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e as respostas Q1 a Q10 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. Os nomes de atributos seguem o código, em inglês, e as descrições ficam em português.

`Tenant`, `User`, `Wallet` e `Session` já existem desde a U1 ([entities.md da U1](../../u1-walking-skeleton/functional-design/entities.md)). Aqui aparecem só com o que a U3 acrescenta, marcado em `added_in: u3`. `LoginChallenge` não muda.

```yaml
entities:
  - name: Tenant
    owner: IdentityAccess
    description: Agrupamento de pessoas (ADR-002). Na U3 passa a existir também fora do perfil de teste, por semeadura da migração (Q1).
    changes:
      - added_in: u3
        what: "A migração semeia um tenant de nome 'Beta' e status ACTIVE, com identificador fixo. Não há rota de criação, edição ou suspensão de tenant no beta."
    attributes:
      - { name: tenantId, type: identifier, required: true, unique: true }
      - { name: name, type: text, required: true, max: 120 }
      - { name: status, type: enum, required: true, allowed: [ACTIVE, SUSPENDED], default: ACTIVE }
      - { name: createdAt, type: instant, required: true }
    relationships:
      - { to: User, cardinality: "1:N", direction: "Tenant -> User" }
      - { to: Invite, cardinality: "1:N", direction: "Tenant -> Invite" }

  - name: Invite
    owner: IdentityAccess
    added_in: u3
    description: Convite de uso único emitido pelo administrador para um tenant (FR1.1, FR3.1). Guarda só o resumo do código, nunca o código (Q2).
    attributes:
      - { name: inviteId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant, constraints: "tenant ACTIVE no momento da emissão" }
      - { name: codeDigest, type: text, required: true, unique: true, constraints: "resumo criptográfico (SHA-256) do código; o código nunca é guardado" }
      - { name: codeSuffix, type: text, required: true, min: 4, max: 4, constraints: "os 4 últimos caracteres do código, para o administrador reconhecer o convite na lista" }
      - { name: status, type: enum, required: true, allowed: [PENDING, USED, REVOKED], default: PENDING, constraints: "estado guardado; EXPIRED é derivado na leitura (BR1.2)" }
      - { name: issuedBy, type: text, required: true, constraints: "endereço da carteira de administrador da configuração, mascarado como na trilha da U1 (4 primeiros + '…' + 4 últimos); não aparece na lista de convites" }
      - { name: createdAt, type: instant, required: true }
      - { name: expiresAt, type: instant, required: true, constraints: "createdAt + 1 hora <= expiresAt <= createdAt + 30 dias (Q3)" }
      - { name: usedAt, type: instant, required: false, constraints: "preenchido só quando status = USED" }
      - { name: usedByUserId, type: reference, required: false, references: User, unique: true, constraints: "preenchido só quando status = USED" }
      - { name: revokedAt, type: instant, required: false, constraints: "preenchido só quando status = REVOKED" }
    constraints:
      - "status = USED exige usedAt e usedByUserId; status = REVOKED exige revokedAt; status = PENDING não tem nenhum dos três."
      - "Um convite sai de PENDING no máximo uma vez: para USED ou para REVOKED."
      - "Sem campo de anotação nem de dado pessoal (Q8)."
    relationships:
      - { to: Tenant, cardinality: "N:1", direction: "Invite -> Tenant" }
      - { to: User, cardinality: "1:0..1", direction: "Invite -> User (conta criada com ele)" }

  - name: TermsDocument
    owner: IdentityAccess
    added_in: u3
    description: Termos de uso e aviso de risco, versionados juntos num arquivo do repositório (Q6). É dado de leitura, sem tabela nem rota de edição.
    attributes:
      - { name: version, type: text, required: true, unique: true, constraints: "identificador estável, por exemplo 'v1'" }
      - { name: termsText, type: text, required: true }
      - { name: riskNoticeText, type: text, required: true, constraints: "sem promessa de lucro nem garantia de detecção de fraude (catálogo de termos proibidos da U4)" }
      - { name: current, type: boolean, required: true, constraints: "exatamente uma versão vigente" }

  - name: TermsAcceptance
    owner: IdentityAccess
    added_in: u3
    description: Aceite dos termos e do aviso de risco, gravado na criação da conta (FR1.2, AC1.1.3). Nunca é alterado nem apagado.
    attributes:
      - { name: acceptanceId, type: identifier, required: true, unique: true }
      - { name: userId, type: reference, required: true, references: User }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: termsVersion, type: text, required: true, references: TermsDocument.version }
      - { name: acceptedAt, type: instant, required: true, constraints: "UTC, instante da criação da conta" }
      - { name: inviteId, type: reference, required: true, references: Invite }
    constraints:
      - "Somente inserção; uma versão nova dos termos não exige novo aceite no beta (Q6)."
    relationships:
      - { to: User, cardinality: "N:1", direction: "TermsAcceptance -> User" }

  - name: User
    owner: IdentityAccess
    description: Pessoa com acesso ao produto. Na U3, uma conta BETA_USER nasce só por convite.
    changes:
      - added_in: u3
        what: "Criada na verificação com convite (BR3.3), no tenant do convite, com papel BETA_USER e status ACTIVE."
    attributes:
      - { name: userId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: role, type: enum, required: true, allowed: [BETA_USER, ADMIN] }
      - { name: status, type: enum, required: true, allowed: [ACTIVE, DISABLED], default: ACTIVE }
      - { name: createdAt, type: instant, required: true }

  - name: Wallet
    owner: IdentityAccess
    description: Carteira pública vinculada à pessoa (FR1.5). O endereço é único em todo o sistema.
    changes:
      - added_in: u3
        what: "Vinculada na criação da conta com o provedor informado na verificação; não existe desvincular no beta (Q13 das histórias)."
    attributes:
      - { name: walletId, type: identifier, required: true, unique: true }
      - { name: userId, type: reference, required: true, unique: true, references: User }
      - { name: address, type: text, required: true, unique: true }
      - { name: provider, type: enum, required: true, allowed: [PHANTOM, SOLFLARE] }
      - { name: verifiedAt, type: instant, required: true }

  - name: Session
    owner: IdentityAccess
    description: Sessão da U1, sem mudança de forma. A U3 só a lê para a tela Conta (absoluteExpiresAt, Q10).

  - name: IdentityCounts
    owner: IdentityAccess
    added_in: u3
    description: Visão derivada para o estado do sistema (C17, AC7.4.1). Só contagens, sem endereço nem identificador de pessoa.
    attributes:
      - { name: guestCount, type: integer, required: true, min: 0, constraints: "pessoas BETA_USER com status ACTIVE" }
      - { name: inviteCounts, type: map, required: true, constraints: "chaves PENDING, USED, EXPIRED e REVOKED, pelo estado derivado da BR1.2" }
```

## Resumo

| Entidade | Situação | Papel na U3 |
|---|---|---|
| `Invite` | nova | Convite de uso único com validade, guardado só como resumo do código |
| `TermsDocument` | nova (arquivo) | Termos e aviso de risco vigentes, com versão |
| `TermsAcceptance` | nova | Registro imutável do aceite, ligado ao convite |
| `Tenant` | muda | Tenant `Beta` semeado; escolha do administrador entre os ativos |
| `User`, `Wallet` | mudam | Criados juntos na verificação com convite |
| `Session` | sem mudança | Lida para mostrar o fim absoluto na tela Conta |
| `IdentityCounts` | nova (visão) | Contagens de convidados e convites para a administração |
