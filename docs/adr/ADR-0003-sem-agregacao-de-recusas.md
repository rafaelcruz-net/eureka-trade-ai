# ADR-0003 — Sem agregação de recusas: todo 403 grava um REQUEST_FORBIDDEN

- **Status:** aceito (decisão do responsável do projeto em 29/10/2026, antes do plano de geração de código da U2)
- **Regra mantida:** BR2.1 do design funcional da U2 ([rules.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/rules.md))
- **Documentos que ainda descrevem a agregação e precisam de revisão:** [observability-design.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/nfr-design/observability-design.md), [reliability-design.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/nfr-design/reliability-design.md), [security-design.md](../../aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/nfr-design/security-design.md) e o Infrastructure Design da U2

## Contexto

O NFR Design da U2 propunha agregar recusas repetidas para limitar o volume da trilha. A porta `ForbiddenRequestRecorder` ganharia um decorador que contaria as recusas por chave, com teto de 500 chaves. A cada 10 s, uma tarefa gravaria um resumo `REQUEST_FORBIDDEN_REPEATED`, mais um resumo de estouro quando passasse do teto. Também haveria a métrica `audit_forbidden_aggregated_total` e um alerta de recusas repetidas.

O design funcional da U2 diz outra coisa: todo `403` grava um `REQUEST_FORBIDDEN` (BR2.1). No beta por convite, o volume esperado de recusas é pequeno. A agregação traria estado em memória, perda de contagem numa queda abrupta e uma tarefa a mais, sem ganho mensurável.

## Decisão

Fica a BR2.1: cada recusa `403` grava exatamente um `REQUEST_FORBIDDEN` pela porta `ForbiddenRequestRecorder` existente, na transação da recusa.

Saem do desenho:

- o decorador agregador da porta `ForbiddenRequestRecorder`;
- a ação `REQUEST_FORBIDDEN_REPEATED`, o teto de 500 chaves e o resumo de estouro;
- a tarefa de resumo a cada 10 s;
- a métrica `audit_forbidden_aggregated_total` e o alerta de recusas repetidas.

Ficam a métrica `audit_events_recorded_today` e o alerta candidato de volume diário acima de 80 mil eventos.

Implementação e testes:

- `AdminAuthorization`, usado por `AdminRouteFilter` e pela própria operação, grava uma recusa por pedido.
- `AuditedForbiddenRequestRecorder` grava a rota saneada (`RequestPaths.auditRoute`), para o publicador nunca recusar o evento.
- `AdminAuditRouteIT` confere caminhos longos, com JWT, com assinatura e com token na query: sempre `403` e um evento. `AdminAuditRouteIT` confere um evento por recusa e dois eventos para duas recusas. `AuditObservabilityIT` confere que a métrica de agregação não existe.

## Consequências

- A trilha mostra cada recusa com o seu `correlationId`, o que facilita investigar abuso.
- Um ataque de muitas recusas aumenta o volume da trilha. O alerta de volume diário (80 mil, contra o teto de 100 mil) avisa antes do limite de NFR2.1. O limite de tentativas da U1 continua valendo na entrada.
- Nenhuma contagem se perde em queda abrupta, porque não há estado em memória.
- Se o volume real de recusas passar a pressionar a trilha, a agregação volta como decisão nova, com ADR próprio.

## Alternativas rejeitadas

| Alternativa | Por que foi rejeitada |
|---|---|
| Manter a agregação do NFR Design | Contradiz a BR2.1 aprovada e acrescenta estado, tarefa e perda de dados em queda, sem necessidade no volume do beta. |
| Amostrar as recusas (por exemplo, 1 em 10) | Perde eventos de auditoria, o que contraria FR2.3 (exatamente um evento por ação sensível). |
| Gravar as recusas só em log | O log não é append-only nem consultável pelo administrador; a recusa ao recurso de outra conta precisa estar na trilha. |
