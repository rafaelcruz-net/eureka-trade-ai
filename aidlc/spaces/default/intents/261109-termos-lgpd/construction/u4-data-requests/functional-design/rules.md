# Regras de negócio — U4 `u4-data-requests`

Conversa: português (Brasil). Fontes:
- `inception/user-stories/stories.md` (US5.1 a US5.9);
- `inception/requirements-analysis/requirements.md` (FR5, FR7.1, NFR1 a NFR4, NFR8);
- `inception/contract-design/contract-summary.md` (P1, P2, J1 a J4);
- `entities.md`;
- `functional-design-questions.md` (Q1 a Q8).

## Regras

```yaml
rules:
  - id: BR4.1
    statement: A pessoa cria pela Conta um pedido de um dos quatro tipos, com texto de 1 a 1000 caracteres, recebido com prazo de 15 dias corridos
    category: validation
    applies_to: DataSubjectRequest
    trigger: POST de pedido do convidado
    logic: >
      SE o tipo está entre ACCESS, CORRECTION, DELETION e OTHER e o texto tem de 1 a 1000 caracteres ENTÃO grava o pedido
      ACCOUNT como RECEIVED, com receivedAt do relógio e dueAt igual a receivedAt mais 15 dias, tenant e pessoa da
      autenticação; SENÃO 400 VALIDATION_FAILED e nada é gravado
    violation: 400 VALIDATION_FAILED
    source: FR5.1; FR5.3; AC5.1.1; AC5.1.3
  - id: BR4.2
    statement: Só um pedido aberto por tipo e pessoa
    category: constraint
    applies_to: DataSubjectRequest
    trigger: criação de pedido com conta, pela rota do convidado ou pelo registro administrativo de um pedido por e-mail ligado a uma conta
    logic: SE já existe pedido do mesmo tipo da mesma pessoa em RECEIVED, AWAITING_VERIFICATION ou IN_PROGRESS, de qualquer origem, ENTÃO 409 DATA_REQUEST_ALREADY_OPEN nas duas rotas (a resposta 409 entra no contrato P2 do registro, de forma aditiva); o índice único parcial garante a regra também em corrida
    violation: 409 DATA_REQUEST_ALREADY_OPEN
    source: FR5.1; AC5.1.2; Q2
  - id: BR4.3
    statement: O texto do pedido, a resposta, o contato, a mensagem de verificação e a assinatura nunca aparecem em log, evento, métrica ou erro
    category: policy
    applies_to: DataSubjectRequest
    trigger: qualquer operação de pedido
    logic: Logs, eventos e métricas trazem só identificador, tipo, origem e estado; os erros de validação nomeiam o campo, nunca o valor
    violation: teste de log e de auditoria falha
    source: FR5.5; NFR1; AC5.1.4; AC5.3.4; AC5.6.8; Q1
  - id: BR4.4
    statement: A pessoa vê só os próprios pedidos; pedido de outra pessoa ou de outro tenant dá 404
    category: authorization
    applies_to: DataSubjectRequest
    trigger: leitura de pedido pelo convidado
    logic: >
      As leituras filtram por tenant e pessoa da autenticação; SE o pedido não pertence à pessoa ENTÃO 404 sem dado. A resposta,
      o motivo e o resumo só aparecem para a pessoa depois de DONE ou REFUSED
    violation: 404 NOT_FOUND
    source: FR5.2; NFR2; AC5.2.1; AC5.2.2; AC5.2.3
  - id: BR4.5
    statement: Só as transições permitidas mudam o estado; estados encerrados não mudam
    category: policy
    applies_to: DataSubjectRequest
    trigger: transição pelo administrador, confirmação de verificação ou vencimento da verificação
    logic: >
      Permitidas: RECEIVED para IN_PROGRESS, menos num pedido EMAIL de ACCESS ou DELETION ligado a uma conta ativa, que
      precisa passar pela verificação (senão 409 INVALID_TRANSITION, com o motivo "verificação de identidade primeiro");
      RECEIVED para AWAITING_VERIFICATION (só EMAIL, pela rota de verificação);
      AWAITING_VERIFICATION para IN_PROGRESS (só pela confirmação); AWAITING_VERIFICATION para REFUSED; IN_PROGRESS para
      DONE; IN_PROGRESS para REFUSED com motivo. SE o pedido está DONE ou REFUSED ENTÃO 409 DATA_REQUEST_CLOSED; SE a
      transição não está na lista ENTÃO 409 INVALID_TRANSITION; SE é REFUSED sem motivo ENTÃO 422 UNPROCESSABLE_FIELDS
    violation: 409 ou 422, sem mudança
    source: FR5.3; AC5.3.2; AC5.3.3
  - id: BR4.6
    statement: Toda criação e mudança de estado gera um evento de auditoria sem o texto
    category: policy
    applies_to: DataSubjectRequest
    trigger: criação, transição, início e confirmação de verificação, vencimento
    logic: DATA_REQUEST_CREATED, DATA_REQUEST_TRANSITIONED, DATA_REQUEST_VERIFICATION_STARTED, DATA_REQUEST_VERIFIED e DATA_REQUEST_OVERDUE, com identificador, tipo, origem, de e para; o ator é a pessoa, o administrador ou o sistema
    violation: não se aplica
    source: FR5.5; AC5.3.4; J6
  - id: BR4.7
    statement: Só o papel ADMIN usa as rotas administrativas de pedidos
    category: authorization
    applies_to: DataSubjectRequest
    trigger: chamada às rotas /api/v1/admin/privacy/**
    logic: SE o token não é de ADMIN ENTÃO 403 FORBIDDEN_ROLE (regra existente das rotas administrativas)
    violation: 403 FORBIDDEN_ROLE
    source: FR5.4; NFR2; AC5.3.5
  - id: BR4.8
    statement: O resumo de Acesso traz só os dados da própria pessoa, sem endereço completo, e é liberado como foto ao concluir
    category: calculation
    applies_to: AccessSummary
    trigger: prévia no detalhe administrativo e conclusão de um ACCESS com releaseAccessSummary
    logic: >
      Monta pelas interfaces J1 (carteira mascarada, provedor, datas, versões aceitas), J2 (bots), J4 (ordens PAPER), J3
      (feedbacks) e os pedidos da pessoa; ao concluir com liberação, grava a foto no pedido, e a pessoa vê a foto
    violation: não se aplica
    source: FR5.2a; AC5.4.1; AC5.4.2; AC5.4.3; Q5
  - id: BR4.9
    statement: Liberar o resumo ou concluir um pedido de Acesso ou de Exclusão por e-mail exige identidade confirmada
    category: authorization
    applies_to: DataSubjectRequest
    trigger: conclusão com liberação do resumo, ou ação sobre Exclusão (U5), em pedido EMAIL
    logic: SE a origem é EMAIL e identityVerified é falso ENTÃO 409 IDENTITY_NOT_VERIFIED e nada é liberado; SE a origem é ACCOUNT ENTÃO a pessoa já está autenticada e a verificação não é exigida
    violation: 409 IDENTITY_NOT_VERIFIED
    source: FR5.2a; AC5.4.4; AC5.4.5
  - id: BR4.10
    statement: Um pedido por e-mail é registrado pelo administrador com o contato, o mesmo prazo, auditoria e expurgo
    category: validation
    applies_to: DataSubjectRequest
    trigger: POST administrativo de registro
    logic: >
      Exige tipo, texto (1 a 1000), contato em formato de e-mail e receivedAt não futuro; a conta é opcional e, quando
      informada, é resolvida pela J1 nova `findAccount(userId)` (tenant e estado ACTIVE ou ANONYMIZED, uso administrativo),
      sem ler tabela do Identity; conta inexistente dá 400 no campo userId; o tenant do pedido vem da conta ligada. Uma
      conta ANONYMIZED é aceita, para manter a ligação na auditoria, mas não admite verificação (409 INVALID_TRANSITION) e
      o administrador recusa o pedido com a resposta modelo que orienta sem expor dado (AC5.6.9). O pedido nasce EMAIL e
      RECEIVED
    violation: 400 VALIDATION_FAILED
    source: FR5.2c; AC5.6.1
  - id: BR4.11
    statement: A verificação gera uma mensagem única por pedido, válida por 24 h, que nunca serve para login nem para operar
    category: policy
    applies_to: IdentityVerification
    trigger: início da verificação pelo administrador
    logic: >
      SE o pedido é EMAIL, ligado a uma conta, do tipo ACCESS ou DELETION e está RECEIVED ENTÃO gera a mensagem com a
      primeira linha fixa de verificação de pedido, o domínio, o identificador do pedido, a finalidade "verificação de
      pedido de titular" e a validade, e leva o pedido a AWAITING_VERIFICATION; SENÃO 409 INVALID_TRANSITION. O login não
      muda: ele só aceita a mensagem exata de um desafio emitido por nonce, então a assinatura de verificação nunca entra
      (provado por teste); a conferência da J1 recusa toda mensagem que não comece pela linha de verificação
    violation: 409 INVALID_TRANSITION
    source: FR5.2c; AC5.6.2; AC5.6.7; Q3
  - id: BR4.12
    statement: Uma assinatura válida da carteira da conta sobre a mensagem, dentro das 24 h, confirma a identidade uma única vez
    category: validation
    applies_to: IdentityVerification
    trigger: conferência pelo administrador
    logic: >
      SE a verificação está PENDING, não venceu e a J1 confere a assinatura com a carteira da conta ENTÃO CONFIRMED,
      identityVerified verdadeiro e o pedido vai para IN_PROGRESS; SE a assinatura é inválida ou de outra carteira ENTÃO
      422 VERIFICATION_INVALID e o pedido continua aguardando; SE já foi confirmada ENTÃO 409 VERIFICATION_ALREADY_USED;
      SE venceu ENTÃO 409 VERIFICATION_EXPIRED. Precedência: SE o pedido já está DONE ou REFUSED ENTÃO 409
      DATA_REQUEST_CLOSED antes de qualquer erro de verificação. A confirmação e a expiração atualizam a verificação e o
      pedido de forma condicional ao estado PENDING e AWAITING_VERIFICATION, numa só transação: só uma das duas vence, e
      a outra vê o estado novo. A recusa manual de um pedido em AWAITING_VERIFICATION marca a verificação como EXPIRED na
      mesma transação. A assinatura nunca é guardada nem registrada
    violation: 422 ou 409
    source: AC5.6.3; AC5.6.4; AC5.6.6; AC5.6.8
  - id: BR4.13
    statement: Sem assinatura válida em 24 h, o pedido é recusado com "identidade não confirmada"
    category: policy
    applies_to: IdentityVerification
    trigger: job a cada 15 minutos
    logic: SE a verificação está PENDING e expiresAt passou ENTÃO EXPIRED, e o pedido vai para REFUSED com o motivo "identidade não confirmada" pelo sistema
    violation: não se aplica
    source: AC5.6.5; Q4
  - id: BR4.14
    statement: Um pedido aberto que passa do prazo gera um único aviso, entra na métrica de vencidos e deixa a saúde DEGRADED
    category: policy
    applies_to: DataSubjectRequest
    trigger: job a cada 15 minutos e consulta da saúde
    logic: >
      SE o pedido está aberto, dueAt passou e overdueNotifiedAt é vazio ENTÃO publica DATA_REQUEST_OVERDUE e grava
      overdueNotifiedAt; a métrica de vencidos conta os abertos com dueAt no passado; a saúde dataRequests fica DEGRADED
      enquanto houver vencido aberto e volta a UP quando não houver
    violation: não se aplica
    source: FR5.7; AC5.7.1; AC5.7.2
  - id: BR4.15
    statement: Pedidos encerrados há 12 meses ou mais são apagados com a verificação e o contato
    category: policy
    applies_to: DataSubjectRequest
    trigger: expurgo diário às 04:00 UTC
    logic: SE closedAt é anterior ou igual ao relógio menos 12 meses ENTÃO apaga o pedido e a verificação, em lotes de 1000; um mais recente fica; a declaração do prazo entra no catálogo de retenção e a linha sai das pendências
    violation: não se aplica
    source: FR5.6; AC5.8.1; Q4
  - id: BR4.16
    statement: A lista administrativa filtra por situação e marca com ícone e texto os pedidos a 3 dias ou menos do prazo, ou vencidos
    category: policy
    applies_to: DataSubjectRequest
    trigger: lista no app do administrador
    logic: SE o filtro de situação é informado ENTÃO só os pedidos dessa situação; SE o pedido está aberto e dueAt está a 3 dias ou menos, ou já passou ENTÃO ícone e texto de alerta (cor nunca sozinha)
    violation: não se aplica
    source: FR7.1; AC5.9.1; AC5.9.2
  - id: BR4.17
    statement: O app do administrador oferece respostas modelo e registra o envio ao contato
    category: policy
    applies_to: DataSubjectRequest
    trigger: detalhe administrativo
    logic: >
      Correção traz o texto modelo (nome dos bots muda pela pessoa; a carteira não se corrige; alternativa: exclusão);
      recusas por identidade não confirmada ou carteira perdida orientam sem expor dado pessoal; em EMAIL encerrado o app
      mostra o texto pronto para o contato e "Marcar como enviada" grava replySentAt (só EMAIL encerrado; senão 409)
    violation: 409 INVALID_TRANSITION em reply-sent fora da regra
    source: FR5.2b; AC5.5.1; AC5.6.9; AC5.6.10; Q6
  - id: BR4.18
    statement: As telas de pedidos funcionam só pelo teclado, com rótulos e foco visíveis
    category: validation
    applies_to: DataSubjectRequest
    trigger: navegação nas telas T5, T6, T6a, T6b e T6c
    logic: Tipo, texto e envio (T5); filtros, pedidos, resposta e ações (T6, T6a) são alcançáveis em ordem, com rótulos visíveis e foco visível
    violation: teste de componente falha
    source: NFR4; AC5.1.6; AC5.3.6
  - id: BR4.19
    statement: Criar pedido e ler a lista respondem com p95 de até 500 ms com 20 convidados
    category: constraint
    applies_to: DataSubjectRequest
    trigger: cenário de carga
    logic: Uma inserção com índice parcial e leituras pelo índice de tenant e pessoa
    violation: o cenário reprova
    source: NFR3; AC5.1.5
  - id: BR4.20
    statement: As rotas de pedidos do convidado ficam liberadas na trava de novo aceite
    category: authorization
    applies_to: DataSubjectRequest
    trigger: requisição a /api/v1/privacy/ de uma pessoa desatualizada
    logic: O prefixo /api/v1/privacy/ entra nos prefixos liberados da trava
    violation: não se aplica
    source: FR4.1; L3; Q8
```

## Resumo

| ID | Regra | Categoria |
|---|---|---|
| BR4.1 | Criação com tipo, texto e prazo de 15 dias | validation |
| BR4.2 | Um aberto por tipo e pessoa | constraint |
| BR4.3 | Nada de texto, contato, mensagem ou assinatura em log ou evento | policy |
| BR4.4 | Só os próprios pedidos; 404 para outros | authorization |
| BR4.5 | Transições permitidas; encerrados não mudam | policy |
| BR4.6 | Evento de auditoria em cada criação e transição | policy |
| BR4.7 | Rotas administrativas só para ADMIN | authorization |
| BR4.8 | Resumo de Acesso sem dado de terceiro, gravado como foto | calculation |
| BR4.9 | Identidade confirmada para liberar em pedido por e-mail | authorization |
| BR4.10 | Registro de pedido por e-mail | validation |
| BR4.11 | Mensagem de verificação única, 24 h, sem uso em login | policy |
| BR4.12 | Confirmação de uso único pela carteira da conta | validation |
| BR4.13 | Recusa automática sem assinatura em 24 h | policy |
| BR4.14 | Aviso único de vencimento, métrica e saúde | policy |
| BR4.15 | Expurgo de 12 meses | policy |
| BR4.16 | Filtro e alerta de prazo na lista | policy |
| BR4.17 | Respostas modelo e registro do envio | policy |
| BR4.18 | Teclado e foco | validation |
| BR4.19 | p95 de até 500 ms | constraint |
| BR4.20 | Prefixo liberado na trava | authorization |
