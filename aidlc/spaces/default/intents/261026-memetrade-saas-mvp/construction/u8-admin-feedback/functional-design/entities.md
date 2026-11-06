# Entidades — U8 Administração e feedback

Fonte de verdade do modelo de dados da Unit `u8-admin-feedback`. As respostas Q1 a Q9 estão em [functional-design-questions.md](functional-design-questions.md). As regras citadas (`BRx.y`) estão em [rules.md](rules.md).

```yaml
entities:
  - name: AdminAccount
    owner: IdentityAccess (U3); a U8 cria e usa pela interface de identidade
    description: >
      Conta do administrador. É uma linha de usuário com papel ADMIN no tenant do sistema (o tenant Beta
      semeado pela U3), criada na primeira entrada de uma carteira listada em ADMIN_WALLET_ADDRESSES
      (BR1.2). Não tem convite, termos nem conta PAPER.
    attributes:
      - { name: userId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true, references: Tenant, constraints: "sempre o tenant do sistema" }
      - { name: role, type: enum, required: true, allowed: [ADMIN] }
      - { name: walletAddress, type: string, required: true, unique: true, constraints: "endereço base58 de 32 bytes; nunca em log completo" }
      - { name: createdAt, type: instant, required: true, constraints: UTC }
    constraints:
      - "Uma carteira de administrador nunca tem conta BETA_USER, e vice-versa (BR1.1)."
      - "Retirar a carteira da configuração não apaga a conta; só impede novas entradas e renovações (BR1.4)."

  - name: AdminSession
    owner: IdentityAccess (U1/U3); a U8 acrescenta o papel ADMIN e os tempos próprios
    description: Sessão do administrador, na mesma tabela de sessões, com papel ADMIN e cookie próprio.
    attributes:
      - { name: sessionId, type: uuid, required: true, unique: true }
      - { name: userId, type: uuid, required: true, references: AdminAccount }
      - { name: role, type: enum, required: true, allowed: [ADMIN] }
      - { name: accessTokenExpiresAt, type: instant, required: true, constraints: "emissão + 15 minutos (Q1)" }
      - { name: sessionExpiresAt, type: instant, required: true, constraints: "abertura + 8 horas, sem prorrogação (Q1)" }
      - { name: revokedAt, type: instant, required: false }
    constraints:
      - "O token de acesso ADMIN só vale em rotas /api/v1/admin/...; o de BETA_USER só fora delas (BR1.5)."

  - name: KillSwitchState
    owner: Administration (U8)
    description: Linha única com o estado atual do kill switch (Q2).
    attributes:
      - { name: id, type: integer, required: true, unique: true, allowed: [1] }
      - { name: state, type: enum, required: true, allowed: [ON, OFF], default: OFF }
      - { name: changedBy, type: uuid, required: false, references: AdminAccount, constraints: "vazio só antes da primeira mudança" }
      - { name: changedAt, type: instant, required: false, constraints: UTC }
      - { name: reason, type: string, required: false, min: 1, max: 500 }
      - { name: pendingBots, type: integer, required: true, default: 0, min: 0, constraints: "bots cuja aplicação ou retirada da pausa ainda falhou (Q3)" }
      - { name: reconcileNeeded, type: boolean, required: true, default: false, constraints: "a última chamada à U5 lançou exceção; o trabalho da BR2.5 reconcilia (R-01)" }
      - { name: version, type: integer, required: true, default: 0, constraints: "cresce a cada mudança de estado" }
    constraints:
      - "Existe exatamente uma linha, criada pela migração com state OFF."
      - "Toda mudança de state grava uma KillSwitchChange na mesma transação (BR2.1, BR2.2)."

  - name: KillSwitchChange
    owner: Administration (U8)
    description: Histórico só de acréscimo de acionamentos e desligamentos (Q2).
    attributes:
      - { name: changeId, type: uuid, required: true, unique: true }
      - { name: version, type: integer, required: true, unique: true, constraints: "igual à KillSwitchState.version depois da mudança" }
      - { name: fromState, type: enum, required: true, allowed: [ON, OFF] }
      - { name: toState, type: enum, required: true, allowed: [ON, OFF] }
      - { name: adminUserId, type: uuid, required: true, references: AdminAccount }
      - { name: reason, type: string, required: true, min: 1, max: 500 }
      - { name: changedAt, type: instant, required: true, constraints: UTC }
      - { name: correlationId, type: string, required: true }
    constraints:
      - "Sem UPDATE nem DELETE (BR2.9)."
      - "fromState diferente de toState."

  - name: FeedbackEntry
    owner: Feedback (U8)
    description: Comentário de um convidado sobre o beta (FR13.1).
    attributes:
      - { name: feedbackId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true, references: Tenant, constraints: "do token, nunca do corpo" }
      - { name: userId, type: uuid, required: true, references: User, constraints: "do token; nunca exposto ao administrador (BR6.6)" }
      - { name: screen, type: enum, required: true, allowed: [S1, S2, S3, S4, S5, S6, S7, S8, S9] }
      - { name: text, type: string, required: true, min: 1, max: 2000, constraints: "guardado exatamente como enviado; contagem em pontos de código" }
      - { name: createdAt, type: instant, required: true, constraints: UTC }
    constraints:
      - "Sem UPDATE. DELETE só pelo expurgo de 180 dias (BR6.7)."

relationships:
  - { from: AdminAccount, to: AdminSession, cardinality: "1:N", direction: "a conta tem sessões" }
  - { from: AdminAccount, to: KillSwitchChange, cardinality: "1:N", direction: "o administrador faz mudanças" }
  - { from: KillSwitchState, to: KillSwitchChange, cardinality: "1:N", direction: "o estado tem histórico" }
  - { from: Tenant, to: FeedbackEntry, cardinality: "1:N", direction: "o tenant tem comentários" }
  - { from: User, to: FeedbackEntry, cardinality: "1:N", direction: "a pessoa envia comentários" }
```

## Resumo

- **AdminAccount e AdminSession.** A U8 não cria tabelas de identidade novas. O administrador é um usuário com papel ADMIN no tenant do sistema, e a sessão dele usa a tabela de sessões existente, com papel ADMIN, token de 15 minutos e sessão de 8 horas.
- **KillSwitchState e KillSwitchChange.** São da Administration: uma linha com o estado atual, mais um histórico só de acréscimo. A U5 continua com a própria cópia (`SystemPauseState`), gravada pela `BotAdministration`.
- **FeedbackEntry.** É do módulo Feedback, com tenant e pessoa vindos do token. Fica guardado 180 dias.
- **Fora daqui.** A U8 não guarda contagens. O estado do sistema é montado na hora pelas interfaces da U3, da U4, da U5, da U6 e da U2.
