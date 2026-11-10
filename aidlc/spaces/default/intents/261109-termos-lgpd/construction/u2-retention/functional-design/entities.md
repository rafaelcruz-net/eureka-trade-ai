# Entidades — U2 `u2-retention`

Conversa: português (Brasil). Esta Unit modela a retenção dos dados, a minimização da carteira na auditoria e os expurgos novos. O modelo não depende de tecnologia.

Fontes:
- `inception/units-generation/unit-of-work.md`;
- `inception/user-stories/stories.md` (US1.4, US3.1, US3.2);
- `inception/requirements-analysis/requirements.md` (FR3.1 a FR3.4, NFR6, NFR7);
- `inception/domain-design/components.md` (Audit, Identity, Privacy);
- `inception/contract-design/contract-summary.md` (J5, J6);
- `functional-design-questions.md` (Q1 a Q10, todas A).

## Modelo

```yaml
entities:
  - name: RetentionRule
    description: Uma linha do catálogo de retenção do módulo Privacy; a fonte única da tabela da política e do teste de conformidade
    attributes:
      - { name: id, type: text, required: true, unique: true, constraints: "identificador estável em maiúsculas, por exemplo LOGIN_CHALLENGES" }
      - { name: dataItem, type: text, required: true, constraints: "texto da coluna 'dado' da política, em pt-BR" }
      - { name: purpose, type: text, required: true, constraints: "texto da coluna 'finalidade'" }
      - { name: period, type: RetentionPeriod, required: true }
      - { name: endAction, type: text, required: true, constraints: "texto da coluna 'ao fim do prazo'" }
      - { name: enforcer, type: Enforcer, required: true }
      - { name: order, type: integer, required: true, unique: true, min: 1, constraints: "ordem de exibição na política" }
    constraints:
      - "o catálogo tem exatamente as 12 linhas da tabela da política v2 (FR3.4 mais o aceite dos termos), nem mais nem menos"
      - "o catálogo é imutável em tempo de execução: mudar uma linha é mudar o código e a política juntos"
    relationships:
      - { to: Enforcer, cardinality: "1 para 1", direction: "é aplicada por" }

  - name: RetentionPeriod
    description: Prazo de uma linha (objeto de valor)
    attributes:
      - { name: kind, type: enum, required: true, allowed: [FIXED, WHILE_ACCOUNT_EXISTS] }
      - { name: duration, type: duration, required: false, constraints: "obrigatória em FIXED; ausente em WHILE_ACCOUNT_EXISTS" }
      - { name: anchor, type: enum, required: false, allowed: [CREATED, DELIVERED, EXPIRED, ENDED, CONCLUDED], constraints: "a partir de quando o prazo conta; obrigatório em FIXED" }
      - { name: label, type: text, required: true, constraints: "texto exibido na política, por exemplo '24 horas após expirar'" }

  - name: Enforcer
    description: Quem aplica o prazo de uma linha (objeto de valor)
    attributes:
      - { name: kind, type: enum, required: true, allowed: [PURGE, GUARD, PLANNED] }
      - { name: purgeId, type: text, required: false, constraints: "nome do expurgo em PURGE (por exemplo loginChallengePurge)" }
      - { name: plannedUnit, type: text, required: false, constraints: "Unit que entrega, em PLANNED (u4-data-requests, u5-anonymization)" }
    constraints:
      - "PURGE exige purgeId de um expurgo que existe e expõe o próprio prazo configurado"
      - "GUARD vale só para WHILE_ACCOUNT_EXISTS"
      - "PLANNED só é aceito para linhas da lista de pendências versionada"

  - name: PurgeDeclaration
    description: O que cada expurgo declara de si para o teste de conformidade
    attributes:
      - { name: purgeId, type: text, required: true, unique: true }
      - { name: retention, type: duration, required: true, constraints: "o prazo que o expurgo aplica de fato, lido da configuração dele" }
      - { name: anchor, type: enum, required: true, allowed: [CREATED, DELIVERED, EXPIRED, ENDED, CONCLUDED] }
    relationships:
      - { to: RetentionRule, cardinality: "1 para 0..1", direction: "atende" }

  - name: PendingRetention
    description: Lista versionada das linhas cujo expurgo chega numa Unit posterior
    attributes:
      - { name: ruleId, type: text, required: true, unique: true, references: RetentionRule.id }
      - { name: plannedUnit, type: text, required: true }
    constraints:
      - "começa só com DATA_SUBJECT_REQUESTS (u4-data-requests); as linhas 'enquanto a conta existir' são GUARD, e a ação no fim delas (anonimização) chega na u5-anonymization sem entrar na lista, porque o teste confere o prazo, não a ação"
      - "a Unit que entrega retira a linha; nenhuma linha nova entra sem decisão registrada"

  - name: WalletReference
    description: A forma da carteira que a auditoria pode guardar (objeto de valor); o endereço completo nunca é gravado
    attributes:
      - { name: masked, type: text, required: true, constraints: "quatro primeiros e quatro últimos caracteres, como a máscara existente" }
      - { name: hmac, type: text, required: true, constraints: "HMAC-SHA-256 do endereço com a chave da configuração, em hexadecimal minúsculo de 64 caracteres" }
    constraints:
      - "a mesma carteira gera sempre o mesmo hmac enquanto a chave não muda"
      - "sem a chave, o hmac não identifica a pessoa"

  - name: AuditEvent
    description: Evento da trilha existente (módulo Audit), ampliado com a referência da carteira
    attributes:
      - { name: actor, type: text, required: true, constraints: "carteira só mascarada, como hoje" }
      - { name: walletHmac, type: text, required: false, constraints: "presente quando o evento envolve uma carteira" }
      - { name: details, type: map, required: true, constraints: "nunca contém um endereço Base58 completo (32 a 44 caracteres)" }
    constraints:
      - "trilha append-only: nenhuma linha existente é alterada"
      - "a saída da trilha (outbox) leva o mesmo walletHmac no payload"

  - name: LoginChallenge
    description: Desafio de login existente (Identity); passa a ter expurgo
    attributes:
      - { name: expiresAt, type: instant, required: true }
      - { name: state, type: enum, required: true, allowed: [ISSUED, CONSUMED, EXPIRED] }

  - name: UserSession
    description: Sessão existente (Identity) do convidado ou do administrador, com os digests de renovação usados
    attributes:
      - { name: state, type: enum, required: true, allowed: [ACTIVE, REVOKED, EXPIRED] }
      - { name: revokedAt, type: instant, required: false }
      - { name: refreshExpiresAt, type: instant, required: true }
      - { name: absoluteExpiresAt, type: instant, required: true }
    relationships:
      - { to: UsedRefreshDigest, cardinality: "1 para N", direction: "tem" }

  - name: UsedRefreshDigest
    description: Digest de renovação já usado de uma sessão (Identity)
    attributes:
      - { name: digest, type: text, required: true, unique: true }
      - { name: usedAt, type: instant, required: true }
    relationships:
      - { to: UserSession, cardinality: "N para 1", direction: "pertence a" }

  - name: PurgeRun
    description: Última execução de cada expurgo novo, guardada numa tabela do Identity com uma linha por expurgo, lida pela saúde de qualquer instância
    attributes:
      - { name: purgeId, type: text, required: true, unique: true, allowed: [loginChallengePurge, sessionPurge] }
      - { name: lastRunAt, type: instant, required: true }
      - { name: lastResult, type: enum, required: true, allowed: [SUCCEEDED, FAILED] }
      - { name: lastSuccessAt, type: instant, required: false }
      - { name: lastCutoff, type: instant, required: true }
      - { name: deleted, type: integer, required: true, min: 0 }
    constraints:
      - "uma linha por expurgo, atualizada no fim de cada execução em transação própria"

  - name: RetentionEnforcerDeclaration
    description: Interface do pacote compartilhado que cada módulo registra para declarar um expurgo (BR2.17)
    attributes:
      - { name: purgeId, type: text, required: true, unique: true }
      - { name: retention, type: duration, required: true, constraints: "lida da configuração do próprio expurgo" }
      - { name: anchor, type: enum, required: true, allowed: [CREATED, DELIVERED, EXPIRED, ENDED, CONCLUDED] }
    constraints:
      - "registrada pelos módulos: Audit (auditRetention, auditOutboxCleanup), Feedback (feedbackPurge), Bots (decisionPurge), RiskControl (riskcontrol-purge), Identity (idempotencyCleanup, loginChallengePurge, sessionPurge)"
      - "o Privacy só recebe a lista; nenhum módulo depende do Privacy"
```

## Resumo

- **Privacy (módulo novo):**
  - `RetentionRule`, com `RetentionPeriod` e `Enforcer`, é o catálogo de retenção;
  - `PurgeDeclaration` liga cada expurgo ao prazo que ele aplica de fato;
  - `PendingRetention` registra as linhas cujo expurgo chega na U4 e na U5.
- **Audit:** o `AuditEvent` passa a guardar a carteira só como `WalletReference` (máscara e HMAC). O endereço completo nunca chega ao `details` nem à saída da trilha.
- **Identity:** `LoginChallenge`, `UserSession` e `UsedRefreshDigest` ganham expurgos, e cada expurgo registra o próprio `PurgeRun`.
