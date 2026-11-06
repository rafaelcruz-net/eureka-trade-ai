## Review

**Verdict:** READY
**Reviewer:** aidlc-architecture-reviewer-agent
**Date:** 2026-11-05T18:50:00Z
**Iteration:** 2
**Verdict Dictated:** no

### Findings

| ID | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-01 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/functional-spec.md > Escopo da U2 (coluna "Já entregue pela U1") and rules.md > BR1.3, BR3.1; entities.md > AuditEvent | O escopo lista o gatilho append-only como "já entregue pela U1, sem mudança". Mas BR1.3 passa a permitir DELETE do papel de retenção para eventos anteriores ao corte. Esse corte é calculado pelo banco, e o BR3.1 o faz configurável por `retentionMonths`. Além disso, entities.md diz que `AuditEvent` "ganha `recordedAt`" e, no mesmo texto, que já existe com "forma final desde a U1". O design não enumera o que muda nos objetos de banco da U1: alteração do gatilho, a coluna `recorded_at`, a criação do papel de retenção com seus privilégios e de onde vem o valor de `retentionMonths` que o gatilho usa. Quem implementa precisa adivinhar. | Acrescentar ao functional-spec.md uma lista explícita das mudanças em objetos da U1 (gatilho, coluna `recordedAt`, papel de retenção, relay gravando `recordedAt`). Definir como o gatilho obtém o corte (valor fixo de 12 meses ou parâmetro), de modo que a configuração da aplicação não possa contradizê-lo. Corrigir a contradição "forma final" vs. "ganha `recordedAt`". | Unresolved |
| R-02 | Major | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/rules.md > BR1.4 (and functional-spec.md > F1 passo 3, Cenários de borda) | A recusa de `details` ou `targetRef` com forma proibida lança exceção e desfaz a transação da ação. Isso torna a própria ação sensível (por exemplo `LOGIN_REJECTED`, kill switch, ordem recusada) dependente do conteúdo do evento. Em eventos de antes do login, parte dos valores vem do cliente. Um valor com forma de JWT ou de assinatura de 64 bytes, enviado por quem ataca, pode virar 500 e a ação deixa de ser auditada. O design trata isso só como "defeito do chamador" e não trata valores influenciados por entrada externa. | Definir o comportamento para valores vindos de entrada externa: o chamador nunca repassa texto do cliente a `details` ou `targetRef` sem sanear, ou o publicador substitui o valor por um marcador redigido e grava o evento. Registrar a escolha em BR1.4 e adicionar um cenário de borda com teste. | Unresolved |
| R-03 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/functional-spec.md > F3 passo 7 | `AUDIT_QUERIED` é publicado "na mesma transação de leitura". O publicador exige transação ativa que escreva na tabela de saída. Uma transação marcada como somente leitura recusaria a inserção. O design também não diz o que acontece se a publicação falhar depois de a página já ter sido montada: devolver 200 sem auditoria ou falhar a consulta. | Dizer que a consulta roda em transação de leitura e escrita, ou que a publicação ocorre numa transação própria. Declarar que a falha ao publicar faz a consulta falhar (503) em vez de devolver dados sem auditoria. | Unresolved |
| R-04 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/functional-spec.md > Cenários de borda, linha "Evento gravado depois da primeira página" and rules.md > BR2.3 | A ordem é por `occurredAt` decrescente com instante do chamador, e o relay grava com atraso. Um evento gravado depois, com `occurredAt` mais recente que o cursor, nunca aparece nas páginas seguintes de uma consulta em andamento. O cenário de borda só descreve o caso favorável. | Registrar a limitação (o administrador repete a consulta para ver eventos tardios) ou paginar por `recordedAt`. | Unresolved |
| R-05 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/rules.md > BR3.2, BR3.4; functional-spec.md > F4 passo 1, F5 | O "impedimento de execução simultânea" do expurgo não define o mecanismo, e a API pode ter mais de uma instância. A limpeza da tabela de saída (F5) também não diz com qual papel de banco apaga em `audit_outbox`. | Definir o mecanismo de exclusão mútua (por exemplo, trava no banco) e o papel de banco da limpeza da tabela de saída, ou transferir explicitamente ao NFR Design. | Unresolved |
| R-06 | Minor | aidlc/spaces/default/intents/261026-memetrade-saas-mvp/construction/u2-audit-trail/functional-design/rules.md > BR3.1; requirements.md > NFR7 | O NFR7 exige "política de retenção aprovada antes dos convites". O design fixa 12 meses (Q8) para eventos que guardam o endereço completo, mas não registra quem aprova a política nem em que gate. | Registrar a aprovação da política como pendência do gate pré-convites (G1) com responsável, ou indicar a decisão que já a cobre. | Unresolved |

> R-01 Reviewer note: functional-spec.md > "Escopo da U2" ainda lista o gatilho append-only como "já entregue pela U1 (sem mudança de interface)", enquanto rules.md > BR1.3 e BR3.1 exigem DELETE do papel de retenção antes do corte e entities.md fala em `recordedAt` novo. As mudanças nos objetos de banco da U1 (gatilho, coluna `recorded_at`, papel de retenção, origem do corte) continuam sem lista explícita. A Unit já foi construída; a lacuna é de documentação do design e não bloqueia.

> R-02 Reviewer note: rules.md > BR1.4 e functional-spec.md > F1 passo 3 e "Cenários de borda" ainda recusam com exceção de programação, sem tratar valores influenciados por entrada externa (ex.: `LOGIN_REJECTED` antes do login). O código entregue usa `AuditPublishRejectedException` (IllegalArgumentException), o que confirma o comportamento de lançar exceção; o design não define saneamento ou redação.

> R-03 Reviewer note: functional-spec.md > F3 passo 7 ainda diz "mesma transação de leitura" sem declarar transação de escrita ou o que acontece se a publicação falhar.

> R-04 Reviewer note: A ordem por `occurredAt` com cursor segue sem registrar a limitação de eventos tardios (cenário de borda só descreve o caso favorável).

> R-05 Reviewer note: BR3.2 e F4 passo 1 seguem sem definir o mecanismo de exclusão mútua do expurgo, nem o papel de banco da limpeza da tabela de saída (F5).

> R-06 Reviewer note: BR3.1 segue sem registrar quem aprova a política de retenção e em qual gate, exigido pelo NFR7 antes dos convites.

### Validation Tool Results

| Tool | Result | Interpretation |
|---|---|---|
| Releitura manual de functional-spec.md, rules.md e review-01.md | Sem mudança | Os artefatos não mudaram desde a última revisão READY; os seis achados seguem abertos nos mesmos locais. |
| Verificação das entradas compartilhadas | Sem contradição nova | Nenhum item de unit-of-work.md, contract-summary.md (C8/C9) ou NFR7 passou a contradizer o design. |
| Grep em apps/api/.../audit | Consistente | O código entregue confirma a recusa por exceção (R-02) e não altera o design. |

### Summary

Sem achados Críticos e com dois Major abertos (R-01, R-02) mais quatro Minor, o design permanece implementável e dentro do limiar de READY. Os Major devem ser tratados quando o design for reaberto, registrando as mudanças nos objetos da U1 e o tratamento de valores externos em BR1.4.
