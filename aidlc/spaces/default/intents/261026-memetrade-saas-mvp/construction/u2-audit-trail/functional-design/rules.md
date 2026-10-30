# Regras de negócio — U2 Trilha de auditoria

Regras da Unit `u2-audit-trail` ([unit-of-work.md](../../../inception/units-generation/unit-of-work.md)), derivadas de [requirements.md](../../../inception/requirements-analysis/requirements.md) (FR2.3, FR3.3, NFR7), da história US7.3 de [stories.md](../../../inception/user-stories/stories.md) mapeada em [unit-of-work-story-map.md](../../../inception/units-generation/unit-of-work-story-map.md), do componente AuditTrail de [components.md](../../../inception/domain-design/components.md), do contrato C9 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das respostas Q1 a Q9 de [functional-design-questions.md](functional-design-questions.md). O modelo está em [entities.md](entities.md).

## Regras

```yaml
rules:
  # BR1 - Gravação e integridade da trilha (C9)
  - id: BR1.1
    statement: Cada ação sensível gera exatamente um evento na trilha, com ator, ação, tenant quando houver, horário UTC e identificador de correlação.
    category: policy
    applies_to: AuditEventPublisher, AuditEvent
    trigger: Confirmação da transação de uma ação sensível
    logic: IF a transação da ação confirma THEN a entrada da tabela de saída existe e o relay grava um AuditEvent com o mesmo eventId; IF o mesmo eventId chega de novo THEN é descartado sem erro; IF a transação desfaz THEN nenhum evento existe
    violation: Não aplicável (regra de garantia, verificada por teste de "exatamente um evento")
    source: FR2.3
  - id: BR1.2
    statement: O publicador recusa um evento incompleto ou incoerente antes de gravar.
    category: validation
    applies_to: AuditEventCommand
    trigger: Chamada a AuditEventPublisher.publish
    logic: IF eventId, actor, actorType, action, correlationId ou occurredAt faltam OR action não segue o formato de maiúsculas com sublinhado de até 64 caracteres OR tenantId está vazio com actorType PERSON THEN recusar com exceção de programação, desfazendo a transação da ação
    violation: Exceção técnica (defeito do chamador), coberta por teste
    source: FR2.3
  - id: BR1.3
    statement: Um evento gravado na trilha nunca é alterado; só o expurgo de retenção pode apagá-lo.
    category: constraint
    applies_to: AuditEvent
    trigger: Qualquer UPDATE ou DELETE em AuditEvent
    logic: IF a operação é UPDATE THEN recusar sempre; IF é DELETE AND não vem do papel de banco de retenção THEN recusar; IF é DELETE do papel de retenção AND occurredAt não é anterior ao corte de BR3.1 THEN recusar
    violation: Erro do banco; a operação não acontece
    source: FR2.3, NFR7
  - id: BR1.4
    statement: Eventos e detalhes nunca contêm segredos, tokens de sessão, cabeçalhos Authorization, nonces nem assinaturas.
    category: constraint
    applies_to: AuditEventCommand, AuditEvent
    trigger: Chamada a AuditEventPublisher.publish
    logic: IF algum valor de details ou de targetRef tem forma de token JWT, de valor Bearer ou de assinatura Base58 de 64 bytes, OR alguma chave de details é nonce, signature, token, accessToken, refreshToken, authorization ou password (sem diferença de maiúsculas) THEN recusar o evento com exceção de programação
    violation: Exceção técnica (defeito do chamador), coberta por teste
    source: NFR7

  # BR2 - Consulta restrita (Q1 a Q3)
  - id: BR2.1
    statement: Só o administrador consulta a trilha, por uma rota de leitura sem tela.
    category: authorization
    applies_to: GET /api/v1/admin/audit-events
    trigger: Requisição à rota de consulta
    logic: IF o token é de administrador THEN executar a consulta; IF é token de convidado THEN responder 403 FORBIDDEN_ROLE e gravar REQUEST_FORBIDDEN com a rota e o papel; IF não há token válido THEN responder 401 UNAUTHENTICATED
    violation: 401 UNAUTHENTICATED ou 403 FORBIDDEN_ROLE, auditado
    source: NFR7, FR3.3
  - id: BR2.2
    statement: A consulta exige período e aceita filtro por ação; o período tem no máximo 31 dias.
    category: validation
    applies_to: AuditEventQuery
    trigger: Requisição à rota de consulta
    logic: IF from ou to faltam OR to não é maior que from OR to - from passa de 31 dias OR limit fora de 1 a 100 OR há parâmetro não declarado THEN responder 400 VALIDATION_FAILED com a lista de campos; IF action é informada THEN filtrar por igualdade exata
    violation: VALIDATION_FAILED (400)
    source: NFR7
  - id: BR2.3
    statement: O resultado vem em ordem do mais recente para o mais antigo, com desempate pelo eventId, paginado por cursor.
    category: calculation
    applies_to: AuditEventQuery
    trigger: Requisição à rota de consulta
    logic: ordenar por occurredAt decrescente e depois eventId decrescente; o cursor leva occurredAt e eventId do último item e a página seguinte começa estritamente depois dele; a resposta segue a forma comum { items, nextCursor, total } sem total
    violation: Não aplicável
    source: NFR7
  - id: BR2.4
    statement: Na consulta, o administrador vê os eventos de todos os tenants, inclusive os sem tenant, com o endereço completo só em details.
    category: authorization
    applies_to: AuditEvent
    trigger: Montagem da resposta da consulta
    logic: IF o pedido é de administrador THEN devolver os eventos de todos os tenants e os sem tenant; actor sai como gravado (mascarado antes do login); details sai como gravado, inclusive o endereço completo; o endereço completo nunca aparece em log, métrica ou outra resposta
    violation: Não aplicável
    source: NFR7
  - id: BR2.5
    statement: Toda consulta da trilha é uma ação administrativa auditada.
    category: policy
    applies_to: AuditEvent
    trigger: Consulta respondida com sucesso
    logic: IF a consulta responde 200 THEN gravar AUDIT_QUERIED com o administrador como ator, actorType ADMIN, tenant vazio e details com from, to e action
    violation: Não aplicável
    source: FR3.3

  # BR3 - Retenção (Q4, Q5, Q8)
  - id: BR3.1
    statement: Eventos da trilha ficam guardados por 12 meses contados de occurredAt.
    category: policy
    applies_to: AuditEvent, AuditRetentionPolicy
    trigger: Execução diária do expurgo
    logic: corte = início do dia UTC da execução menos retentionMonths (12); IF occurredAt < corte THEN o evento pode ser apagado; ELSE é mantido
    violation: Não aplicável
    source: NFR7
  - id: BR3.2
    statement: O expurgo roda uma vez por dia, com um papel de banco separado, e apaga em lotes só os eventos anteriores ao corte.
    category: constraint
    applies_to: AuditEvent
    trigger: Agendamento diário
    logic: IF já existe execução em andamento THEN não iniciar outra; apagar em lotes de até 1000 eventos com occurredAt < corte usando só o papel de retenção, que não pode inserir nem alterar; IF um lote falha THEN parar, manter o que já foi apagado e repetir na próxima execução
    violation: Falha registrada em log de erro e no estado de saúde degradado
    source: NFR7
  - id: BR3.3
    statement: Cada execução do expurgo deixa um evento na própria trilha.
    category: policy
    applies_to: AuditRetentionRun, AuditEvent
    trigger: Fim de uma execução do expurgo, com ou sem eventos apagados
    logic: gravar AUDIT_RETENTION_PURGED com actor system, actorType SYSTEM, tenant vazio e details com o corte, a quantidade apagada e se terminou ou parou por falha
    violation: Não aplicável
    source: FR2.3, NFR7
  - id: BR3.4
    statement: Entradas entregues da tabela de saída saem depois de 7 dias; entradas pendentes nunca são apagadas.
    category: policy
    applies_to: AuditOutboxEntry
    trigger: Limpeza diária da tabela de saída
    logic: IF deliveredAt existe AND deliveredAt < agora - 7 dias THEN apagar; IF deliveredAt está vazio THEN manter, qualquer que seja a idade
    violation: Não aplicável
    source: FR2.3

  # BR4 - Mascaramento e varredura (Q7, Q9)
  - id: BR4.1
    statement: Fora da trilha, o endereço de carteira aparece só mascarado com os 4 primeiros e os 4 últimos caracteres.
    category: constraint
    applies_to: Logs, métricas, respostas que não são a consulta da trilha
    trigger: Escrita de log, métrica ou resposta
    logic: IF um texto é um endereço de carteira THEN escrever 4 primeiros + "…" + 4 últimos; o completo só aparece em details da trilha e na consulta de BR2.4
    violation: Varredura de BR4.2 falha
    source: NFR7
  - id: BR4.2
    statement: Uma varredura automática da suíte de testes procura, em logs, métricas, payload da tabela de saída e details da trilha, os valores proibidos e falha o build se encontrar algum.
    category: policy
    applies_to: Suíte de testes
    trigger: Execução da suíte
    logic: procurar em logs e métricas o endereço de carteira completo usado nos testes; procurar em logs, métricas, payload e details os tokens de acesso e de renovação emitidos nos testes, qualquer forma de token JWT, o cabeçalho Authorization e o prefixo Bearer, e os nonces e assinaturas de login emitidos nos testes; IF algum aparece THEN o teste falha e, com ele, o build. O endereço completo em details da trilha é permitido e não conta como achado.
    violation: Build falha
    source: NFR7
```

## Resumo

| ID | Regra | Fonte |
|---|---|---|
| BR1.1 | Exatamente um evento por ação sensível | FR2.3 |
| BR1.2 | Publicador recusa evento incompleto | FR2.3 |
| BR1.3 | Trilha sem alteração; exclusão só pelo expurgo | FR2.3, NFR7 |
| BR1.4 | Nenhum segredo, token, nonce ou assinatura no evento | NFR7 |
| BR2.1 | Só o administrador consulta; convidado recebe 403 auditado | NFR7, FR3.3 |
| BR2.2 | Período obrigatório de até 31 dias e filtro por ação | NFR7 |
| BR2.3 | Mais recentes primeiro, cursor estável | NFR7 |
| BR2.4 | Todos os tenants; endereço completo só em details | NFR7 |
| BR2.5 | A consulta gera AUDIT_QUERIED | FR3.3 |
| BR3.1 | Retenção de 12 meses | NFR7 |
| BR3.2 | Expurgo diário em lotes com papel de banco separado | NFR7 |
| BR3.3 | Cada expurgo gera AUDIT_RETENTION_PURGED | FR2.3, NFR7 |
| BR3.4 | Tabela de saída entregue sai em 7 dias; pendente nunca | FR2.3 |
| BR4.1 | Endereço mascarado fora da trilha | NFR7 |
| BR4.2 | Varredura de logs, métricas e eventos falha o build | NFR7 |
