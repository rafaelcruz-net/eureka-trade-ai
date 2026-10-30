# Entidades — U1 Esqueleto de ponta a ponta

Modelo de entidades da Unit `u1-walking-skeleton` ([unit-of-work.md](../../../inception/units-generation/unit-of-work.md)). As entidades vêm de [components.md](../../../inception/domain-design/components.md) (IdentityAccess, MarketData, AuditTrail) no recorte mínimo que a U1 precisa, e os formatos de fronteira seguem [contract-summary.md](../../../inception/contract-design/contract-summary.md) (C1, C2, C9, C10). Os requisitos atendidos estão em [requirements.md](../../../inception/requirements-analysis/requirements.md) (FR1.3 a FR1.5, FR2.1, FR2.3, FR4.1 a FR4.5, FR5.1, FR5.3) e as histórias em [stories.md](../../../inception/user-stories/stories.md) (US1.2, US1.4, US2.1), conforme [unit-of-work-story-map.md](../../../inception/units-generation/unit-of-work-story-map.md).

Os nomes ficam em inglês, como no código (`team.md`). Os tipos são lógicos e independentes de tecnologia. Todo instante é UTC.

## Modelo

```yaml
entities:
  - name: Tenant
    owner: IdentityAccess
    description: Agrupamento de pessoas para administração e cobrança futura (ADR-002). Na U1 só existe por semeadura de teste.
    attributes:
      - { name: tenantId, type: identifier, required: true, unique: true }
      - { name: name, type: text, required: true, max: 120 }
      - { name: status, type: enum, required: true, allowed: [ACTIVE, SUSPENDED], default: ACTIVE }
      - { name: createdAt, type: instant, required: true }
    relationships:
      - { to: User, cardinality: "1:N", direction: "Tenant -> User" }

  - name: User
    owner: IdentityAccess
    description: Pessoa com acesso ao produto. Na U1 só o papel BETA_USER entra pela entrada do convidado; as carteiras de administrador não são linhas desta entidade na U1, vêm da configuração ADMIN_WALLET_ADDRESSES (BR1.5).
    attributes:
      - { name: userId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: role, type: enum, required: true, allowed: [BETA_USER, ADMIN] }
      - { name: status, type: enum, required: true, allowed: [ACTIVE, DISABLED], default: ACTIVE }
      - { name: createdAt, type: instant, required: true }
    relationships:
      - { to: Wallet, cardinality: "1:1", direction: "User -> Wallet" }
      - { to: Session, cardinality: "1:N", direction: "User -> Session" }

  - name: Wallet
    owner: IdentityAccess
    description: Carteira pública vinculada à pessoa. Nunca guarda chave ou frase secreta (FR1.5).
    attributes:
      - { name: walletId, type: identifier, required: true, unique: true }
      - { name: userId, type: reference, required: true, unique: true, references: User }
      - { name: address, type: text, required: true, unique: true, constraints: "endereço público Solana em base58" }
      - { name: provider, type: enum, required: true, allowed: [PHANTOM, SOLFLARE] }
      - { name: verifiedAt, type: instant, required: true }

  - name: LoginChallenge
    owner: IdentityAccess
    description: Desafio de login de uso único (FR1.3).
    attributes:
      - { name: nonce, type: text, required: true, unique: true, constraints: "aleatório com pelo menos 128 bits de entropia" }
      - { name: walletAddress, type: text, required: true }
      - { name: domain, type: text, required: true }
      - { name: chain, type: text, required: true }
      - { name: message, type: text, required: true, constraints: "texto exato emitido para assinatura" }
      - { name: issuedAt, type: instant, required: true }
      - { name: expiresAt, type: instant, required: true, constraints: "issuedAt + 5 minutos" }
      - { name: state, type: enum, required: true, allowed: [ISSUED, CONSUMED, EXPIRED], default: ISSUED }
      - { name: consumedAt, type: instant, required: false }
    constraints:
      - "Um nonce passa de ISSUED para CONSUMED no máximo uma vez."

  - name: Session
    owner: IdentityAccess
    description: Sessão aberta por login. Autentica o uso do produto e nunca autoriza trading (FR1.4).
    attributes:
      - { name: sessionId, type: identifier, required: true, unique: true }
      - { name: userId, type: reference, required: true, references: User }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: role, type: enum, required: true, allowed: [BETA_USER] }
      - { name: refreshTokenDigest, type: text, required: true, unique: true, constraints: "resumo do token de renovação atual; o token nunca é guardado" }
      - { name: usedRefreshTokenDigests, type: list, required: false, constraints: "resumos de tokens de renovação já trocados, para detectar reutilização (BR1.15)" }
      - { name: createdAt, type: instant, required: true }
      - { name: refreshExpiresAt, type: instant, required: true, constraints: "último uso + 8 horas, nunca depois de absoluteExpiresAt" }
      - { name: absoluteExpiresAt, type: instant, required: true, constraints: "createdAt + 12 horas" }
      - { name: state, type: enum, required: true, allowed: [ACTIVE, REVOKED, EXPIRED], default: ACTIVE }
      - { name: revokedAt, type: instant, required: false }

  - name: Token
    owner: MarketData
    description: Token de mercado identificado por chain + mint (FR4.2).
    attributes:
      - { name: chain, type: text, required: true }
      - { name: mint, type: text, required: true }
      - { name: symbol, type: text, required: true, max: 32 }
      - { name: name, type: text, required: false, max: 120 }
      - { name: decimals, type: integer, required: true, min: 0, max: 18 }
      - { name: firstSeenAt, type: instant, required: true }
    constraints:
      - "(chain, mint) é único."

  - name: MarketObservation
    owner: MarketData
    description: Último valor observado de um campo de um token, com origem e horário (FR4.3, FR4.4).
    attributes:
      - { name: observationId, type: identifier, required: true, unique: true }
      - { name: chain, type: text, required: true }
      - { name: mint, type: text, required: true, references: Token }
      - { name: sourceId, type: reference, required: true, references: DataSource }
      - { name: field, type: enum, required: true, allowed: [PRICE, LIQUIDITY, VOLUME_24H, HOLDERS, CREATED_AT] }
      - { name: status, type: enum, required: true, allowed: [KNOWN, UNKNOWN] }
      - { name: value, type: decimal, required: false, constraints: "presente só quando status = KNOWN; pode ser 0" }
      - { name: unknownReason, type: enum, required: false, allowed: [NOT_PROVIDED, SOURCE_UNAVAILABLE, NOT_YET_OBSERVED], constraints: "presente só quando status = UNKNOWN" }
      - { name: observedAt, type: instant, required: true }
    constraints:
      - "Exatamente um entre value e unknownReason está presente, conforme status."
      - "Uma observação vigente por (chain, mint, sourceId, field)."

  - name: DataSource
    owner: MarketData
    description: Fonte de dados de mercado. Na U1, só a fonte simulada.
    attributes:
      - { name: sourceId, type: identifier, required: true, unique: true }
      - { name: name, type: text, required: true }
      - { name: simulated, type: boolean, required: true }
      - { name: status, type: enum, required: true, allowed: [OK, DEGRADED, FAILED] }
      - { name: lastUpdatedAt, type: instant, required: false }

  - name: AuditOutboxEntry
    owner: AuditTrail
    description: Evento de auditoria gravado na mesma transação da ação (C9).
    attributes:
      - { name: eventId, type: identifier, required: true, unique: true }
      - { name: payload, type: document, required: true, constraints: "forma AuditEventCommand de C9" }
      - { name: createdAt, type: instant, required: true }
      - { name: deliveredAt, type: instant, required: false }

  - name: AuditEvent
    owner: AuditTrail
    description: Registro append-only da trilha (ADR-006).
    attributes:
      - { name: eventId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: false, references: Tenant, constraints: "vazio só antes do login ou em ação global" }
      - { name: actor, type: text, required: true, constraints: "userId, 'system' ou endereço mascarado antes do login" }
      - { name: actorType, type: enum, required: true, allowed: [PERSON, ADMIN, SYSTEM, ANONYMOUS] }
      - { name: action, type: text, required: true }
      - { name: targetRef, type: text, required: false }
      - { name: correlationId, type: text, required: true }
      - { name: occurredAt, type: instant, required: true }
      - { name: details, type: document, required: false, constraints: "nunca segredo, token, nonce ou assinatura" }
    constraints:
      - "Nenhuma alteração nem exclusão depois de gravado."

  - name: IdempotencyRecord
    owner: IdentityAccess
    description: Resultado guardado de uma requisição que muda estado, para repetição com a mesma chave.
    attributes:
      - { name: key, type: identifier, required: true }
      - { name: scope, type: text, required: true, constraints: "tenant + pessoa + operação depois do login; carteira + operação antes" }
      - { name: requestDigest, type: text, required: true }
      - { name: responseStatus, type: integer, required: true }
      - { name: responseBody, type: document, required: false, constraints: "nunca contém token de acesso ou de renovação" }
      - { name: createdAt, type: instant, required: true }
      - { name: expiresAt, type: instant, required: true, constraints: "createdAt + 24 horas (provisório, OQ-6); para o desafio de login, a expiração do desafio emitido (BR1.10)" }
    constraints:
      - "(scope, key) é único."
```

## Resumo

| Entidade | Dono | Papel na U1 |
|---|---|---|
| Tenant, User, Wallet | IdentityAccess | Contas existentes; na U1, criadas só pela semeadura do teste |
| LoginChallenge | IdentityAccess | Desafio de uso único com expiração de 5 minutos |
| Session | IdentityAccess | Sessão com token de acesso curto e renovação guardada só como resumo |
| IdempotencyRecord | IdentityAccess | Repetição segura de requisições que mudam estado, sem guardar tokens |
| Token, MarketObservation, DataSource | MarketData | Dados de mercado simulados com fonte, horário e `unknown` explícito |
| AuditOutboxEntry, AuditEvent | AuditTrail | Tabela de saída na transação da ação e trilha append-only |

O mecanismo de idempotência é comum a toda a API; na U1 ele nasce no módulo de identidade, onde fica a primeira rota que muda estado, e passa a ser usado pelas demais Units por interface.
