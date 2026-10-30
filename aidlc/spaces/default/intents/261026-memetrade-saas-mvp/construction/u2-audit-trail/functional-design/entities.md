# Entidades — U2 Trilha de auditoria

Modelo de entidades da Unit `u2-audit-trail` ([unit-of-work.md](../../../inception/units-generation/unit-of-work.md)). O AuditTrail é o dono único de `AuditEvent` em [components.md](../../../inception/domain-design/components.md); a forma do evento e da tabela de saída segue o contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). A U2 implementa a história US7.3 de [stories.md](../../../inception/user-stories/stories.md), atribuída pelo [unit-of-work-story-map.md](../../../inception/units-generation/unit-of-work-story-map.md), e os requisitos FR2.3, FR3.3 e NFR7 de [requirements.md](../../../inception/requirements-analysis/requirements.md). As decisões vêm das respostas Q1 a Q9 de [functional-design-questions.md](functional-design-questions.md).

`AuditEvent` e `AuditOutboxEntry` já existem desde a U1 com a forma final (Q10 e Q18 do Contract Design); a U2 acrescenta a consulta, a retenção e a limpeza da tabela de saída, sem mudar a interface publicadora nem os chamadores. Os nomes ficam em inglês, como no código (`team.md`). Os tipos são lógicos. Todo instante é UTC.

## Modelo

```yaml
entities:
  - name: AuditEvent
    owner: AuditTrail
    description: Registro append-only de uma ação sensível (ADR-006). Só é gravado pelo relay a partir da tabela de saída e só é apagado pelo expurgo de retenção.
    attributes:
      - { name: eventId, type: identifier, required: true, unique: true, constraints: "gerado pelo chamador; repetição idempotente reutiliza o mesmo valor" }
      - { name: tenantId, type: reference, required: false, references: Tenant, constraints: "vazio só antes do login (actorType ANONYMOUS) ou em ação global do administrador ou do sistema" }
      - { name: actor, type: text, required: true, max: 120, constraints: "userId, adminId, 'system' ou endereço de carteira mascarado (4 + 4) antes do login" }
      - { name: actorType, type: enum, required: true, allowed: [PERSON, ADMIN, SYSTEM, ANONYMOUS] }
      - { name: action, type: text, required: true, max: 64, constraints: "texto livre em maiúsculas com sublinhado, por exemplo LOGIN_REJECTED (Q6)" }
      - { name: targetRef, type: text, required: false, max: 200 }
      - { name: correlationId, type: text, required: true, max: 100 }
      - { name: occurredAt, type: instant, required: true, constraints: "UTC, do relógio injetável do chamador" }
      - { name: recordedAt, type: instant, required: true, constraints: "UTC, instante em que o relay gravou o evento na trilha" }
      - { name: details, type: document, required: false, constraints: "mapa texto -> texto; pode conter o endereço de carteira completo; nunca segredo, token, cabeçalho Authorization, nonce ou assinatura" }
    constraints:
      - "Nenhuma alteração depois de gravado."
      - "Exclusão só pelo expurgo de retenção, para eventos com occurredAt anterior ao corte de 12 meses."
    relationships:
      - { to: AuditOutboxEntry, cardinality: "1:1", direction: "AuditOutboxEntry -> AuditEvent (mesmo eventId)" }

  - name: AuditOutboxEntry
    owner: AuditTrail
    description: Evento de auditoria gravado na mesma transação da ação (C9). É a única tabela da U2 que os demais módulos tocam, e só pelo publicador.
    attributes:
      - { name: eventId, type: identifier, required: true, unique: true }
      - { name: payload, type: document, required: true, constraints: "forma AuditEventCommand de C9" }
      - { name: createdAt, type: instant, required: true }
      - { name: deliveredAt, type: instant, required: false, constraints: "preenchido pelo relay quando o evento chega à trilha" }
    constraints:
      - "Entrada pendente (deliveredAt vazio) nunca é apagada."
      - "Entrada entregue há mais de 7 dias é apagada pela limpeza diária (Q5)."

  - name: AuditEventQuery
    owner: AuditTrail
    description: Pedido de consulta da trilha feito pelo administrador (Q1, Q2). Objeto de valor, não persistido.
    attributes:
      - { name: from, type: instant, required: true, constraints: "início inclusivo, UTC" }
      - { name: to, type: instant, required: true, constraints: "fim exclusivo, UTC; maior que from; intervalo máximo de 31 dias" }
      - { name: action, type: text, required: false, max: 64, constraints: "igualdade exata quando informado" }
      - { name: cursor, type: text, required: false, constraints: "opaco; leva occurredAt e eventId do último item" }
      - { name: limit, type: integer, required: false, min: 1, max: 100, default: 50 }

  - name: AuditRetentionPolicy
    owner: AuditTrail
    description: Política de retenção da trilha (Q4, Q8). Valor de configuração, igual para todos os tenants.
    attributes:
      - { name: retentionMonths, type: integer, required: true, default: 12, min: 12, constraints: "não pode ser reduzido abaixo de 12 sem nova decisão registrada" }
      - { name: outboxDeliveredRetentionDays, type: integer, required: true, default: 7, min: 1 }

  - name: AuditRetentionRun
    owner: AuditTrail
    description: Uma execução do expurgo. Não é tabela própria; cada execução vira um AuditEvent AUDIT_RETENTION_PURGED, que fica na trilha.
    attributes:
      - { name: cutoff, type: instant, required: true, constraints: "início do dia UTC da execução menos retentionMonths" }
      - { name: deletedCount, type: integer, required: true, min: 0 }
      - { name: startedAt, type: instant, required: true }
```

## Resumo

| Entidade | Papel na U2 |
|---|---|
| AuditEvent | Trilha append-only; ganha `recordedAt` e passa a aceitar só o expurgo de retenção como exclusão |
| AuditOutboxEntry | Tabela de saída da U1; entradas entregues saem depois de 7 dias |
| AuditEventQuery | Consulta do administrador por período e ação, paginada por cursor |
| AuditRetentionPolicy | 12 meses para a trilha, 7 dias para a tabela de saída já entregue |
| AuditRetentionRun | Execução do expurgo, registrada como evento na própria trilha |
