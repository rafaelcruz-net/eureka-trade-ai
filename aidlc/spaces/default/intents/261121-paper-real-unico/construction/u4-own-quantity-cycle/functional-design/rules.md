# Regras de negócio — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. As regras marcadas com `[fundador]` vêm das respostas confirmadas Q1 a Q5. São regras de dinheiro REAL: os testes delas são escritos antes do código (team.md, Testing Posture; [Q5]).

Grupos: BR1 quantidade própria e corte; BR2 leitura e falha fechada; BR3 concorrência; BR4 o que não muda; BR5 flag; BR6 aviso na tela; BR7 conferências.

```yaml
rules:
  # ---------------------------------------------------------------- BR1 quantidade própria e corte
  - id: BR1.1
    statement: A quantidade própria de um bot num mint é a quantidade do titular bot pelo caminho de atribuição da U2, desde a época do mint.
    category: calculation
    applies_to: [ENT-402]
    trigger: leitura do RealBotHoldings (C15)
    logic: >
      IF o bot é titular de lotes do mint THEN ownTokenBaseUnits é a quantidade dele depois de aplicar, na ordem de
      rede (U2 BR1.1), cada compra dele (U2 BR3.1), cada venda própria dele limitada ao que ele tinha (U2 BR2.3, BR4.4)
      e a parte dele em cada venda sem bot ou excedente de outro bot (U2 BR4.3, BR4.4); ownCostLamports é o custo dele
      pelo RealCostBasis (U2 BR2.1, BR2.2). Usa os mesmos algoritmos puros e a mesma leitura por época do caminho
      CYCLE da U2. Sem preço, taxas, período, realizado nem linha "Sem bot".
    violation: o teste de paridade BR7.1 falha
    source: [FR6.1, ADR-006, C15, Q1]

  - id: BR1.2
    statement: "[fundador] A quantidade disponível do bot é a própria menos as vendas dele em aberto, nunca abaixo de zero."
    category: calculation
    applies_to: [ENT-402]
    trigger: leitura do RealBotHoldings (C15)
    logic: >
      availableTokenBaseUnits = max(0, ownTokenBaseUnits − openSellBaseUnits). AMBIGUOUS conta como em aberto.
      As duas parcelas vêm do mesmo instantâneo (BR2.1).
    violation: não se aplica (cálculo)
    source: [FR6.1, ADR-006, C15, C16, Q1]

  - id: BR1.3
    statement: As vendas em aberto do bot são as ordens SELL dele em andamento e as decisões SELL dele ainda na fila.
    category: calculation
    applies_to: [ENT-405]
    trigger: leitura de C16 dentro do instantâneo do RealBotHoldings
    logic: >
      openSellBaseUnits do mint = soma de
        (a) a quantidade de entrada das ordens SELL do bot no mint em PROPOSED, QUOTED, SIGNED, SENT ou AMBIGUOUS;
        (b) a quantidade pedida das decisões SELL do bot no mint ainda PENDING na fila (inclusive as adiadas).
      Uma decisão já PROCESSED aparece pela ordem dela em (a), ou já está no ledger, ou não vendeu nada (FAILED,
      CANCELLED). Uma decisão DISCARDED não vendeu nada. Por isso cada venda do bot é contada uma vez só: ou em aberto
      ou no ledger. O item (b) é extensão desta Unit ao C16, aceita pelo responsável em 2026-11-24 (D1): OpenSell
      ganha source (ORDER ou DECISION_REQUEST) e sourceId. O código não tem openSells hoje; a fila grava a decisão
      antes de existir a ordem, e a decisão pode ficar adiada até cerca de 5 minutos. A criação da ordem e o
      fechamento da decisão como PROCESSED acontecem na mesma transação do consumidor, então o instantâneo vê a venda
      como decisão PENDING ou como ordem, nunca nenhuma das duas.
    violation: sem (b), uma decisão ainda na fila seria vendida de novo pelo ciclo seguinte e o consumidor da fila, que
      limita só ao saldo da carteira, venderia a parte de outro bot
    source: [ADR-006, C16, Q1, Q5]

  - id: BR1.4
    statement: "[fundador] A venda do ciclo é cortada para a quantidade disponível do bot; se a decisão pedir menos, vale o pedido."
    category: constraint
    applies_to: [ENT-406]
    trigger: decisão SELL do motor num ciclo REAL com a flag ligada
    logic: >
      requestedBaseUnits = piso(quantidade da decisão × 10^decimais). sellBaseUnits = min(requestedBaseUnits,
      availableBaseUnits). IF sellBaseUnits > 0 THEN o ciclo pede ao RealExecution a venda de sellBaseUnits (decisão
      SELL com essa quantidade; RealDecision já aceita a quantidade e não muda). Nunca a posição da carteira.
    violation: não se aplica; o corte é aplicado sempre
    source: [FR6.1, ADR-006, C15, Q1]

  - id: BR1.5
    statement: "[fundador] Sem quantidade disponível, o bot não vende o mint, e NO_OWN_QUANTITY fica registrado na decisão."
    category: constraint
    applies_to: [ENT-406, ENT-408, ENT-409]
    trigger: mint da carteira com availableTokenBaseUnits = 0, ou corte com sellBaseUnits = 0
    logic: >
      IF availableTokenBaseUnits = 0 THEN o mint entra em heldNotSellable (BR1.6), sai dos tokens do pedido de decisão
      e o contexto da decisão grava a nota NO_OWN_QUANTITY para ele, mesmo que o motor termine com NO_TOKENS ou
      NO_TRADE. IF, por qualquer caminho, uma decisão SELL chega ao
      corte com sellBaseUnits = 0 THEN nenhuma decisão REAL é gravada no RealExecution e o resultado do ciclo é
      REAL_NO_OWN_QUANTITY.
    violation: não se aplica
    source: [FR6.1, AC6.1.3, Q1]

  - id: BR1.6
    statement: Com a flag ligada, o motor avalia a saída sobre a posição própria e o custo próprio do bot; o conjunto que impede compra continua sendo o da carteira.
    category: policy
    applies_to: [ENT-409]
    trigger: montagem das entradas de decisão de um bot REAL (RealCycle.inputs)
    logic: >
      Para cada mint com quantidade na carteira (caminho só de quantidade, U2 BR2.5):
        IF availableTokenBaseUnits > 0 THEN posição de saída com quantidade = available e custo =
           piso(ownCostLamports × available ÷ own) em lamports, convertido em US$ pela cotação do SOL do ciclo, como hoje;
        ELSE o mint vai para heldNotSellable.
      Com HoldingsResult Unavailable, todos os mints da carteira vão para heldNotSellable.
      Mecanismo (revisão R-02), só no ramo REAL com a flag ligada: depois de montar a lista de tokens do pedido como
      hoje (seleção do bot mais as posições), o ciclo tira dela os mints de heldNotSellable. Sem o mint nos tokens, o
      motor não o avalia para saída nem para compra. Assim o conjunto de mints que impede a compra (posições próprias
      mais heldNotSellable) é exatamente o conjunto de mints da carteira, igual ao de hoje. O motor de decisão, o
      pedido de decisão e o ramo PAPER não mudam. A nota por mint (BR1.5, BR2.3) fica no contexto da decisão para que
      NO_TOKENS ou NO_TRADE nunca escondam o motivo.
      O saldo operável continua o da carteira, sob a barreira e o teto. A leitura de custo da carteira pelo caminho
      CYCLE (U2 BR2.7) não é feita com a flag ligada, porque o custo vem do RealBotHoldings.
    violation: não se aplica
    source: [ADR-006 (Entradas de decisão próprias), Q1, Q2]

  - id: BR1.7
    statement: "[fundador] O ciclo de um bot não vende a parte de outro bot da mesma carteira, com uma única exceção aceita: uma condicional executada em parte entre a leitura e o processamento da decisão."
    category: constraint
    applies_to: [ENT-406]
    trigger: toda venda do ciclo de um bot REAL com a flag ligada
    logic: >
      Consequência de BR1.2, BR1.3, BR1.4 e BR3.1: a quantidade pedida nunca passa da disponível do bot no instantâneo,
      e nenhuma venda do bot gravada depois do instantâneo escapa do desconto. A garantia cobre ciclos concorrentes do
      mesmo bot, ordens em voo, decisões na fila e ordens AMBIGUOUS. Nesses casos, as vendas novas do bot não geram a
      parcela "vendido por outro bot" (U2 BR4.4).
      Exceção aceita pelo responsável em 2026-11-24 (D2): IF uma saída condicional da mesma carteira e mint é
      executada, no todo ou em parte, entre o instantâneo e o processamento da decisão pela fila THEN a parte do bot
      no ledger cai pelo rateio (U2 BR4.3), a decisão já gravada não cai, e o consumidor só corta pelo saldo da
      carteira (BR4.3). A venda pode então levar um pouco da parte de outro bot. Esse excesso vai para a parcela
      "vendido por outro bot" do rateio da U2, e o P&L continua exato e conferido com o ledger (BR7.2).
    violation: o teste T9 (sem condicional no intervalo) falha; o teste T18 documenta a exceção
    source: [FR6.1, AC6.1.1, AC6.1.2, Q5, D2]

  - id: BR1.8
    statement: O registro de abertura de um mint do bot não é fechado só porque o mint ficou sem quantidade disponível.
    category: constraint
    applies_to: [ENT-409]
    trigger: montagem das entradas de decisão de um bot REAL com a flag ligada
    logic: >
      Hoje o ciclo fecha o registro de abertura (BotHolding do módulo bots) de um mint que não está entre as posições.
      Com a flag ligada, IF o mint está em heldNotSellable THEN o registro fica aberto; só é fechado quando a carteira
      não tem mais o mint, como hoje. Assim, uma venda em aberto que falha não reinicia o tempo da posição (saída por
      tempo máximo).
    violation: o tempo de abertura da posição reinicia e a saída por tempo atrasa
    source: [ADR-006, Q2, revisão R-02]

  # ---------------------------------------------------------------- BR2 leitura e falha fechada
  - id: BR2.1
    statement: "[fundador] A posição própria é lida num único instantâneo, numa transação própria só de leitura, antes da transação do ciclo e fora dela."
    category: policy
    applies_to: [ENT-403]
    trigger: início do ciclo de um bot REAL com a flag ligada
    logic: >
      O ciclo chama o RealBotHoldings antes de abrir a transação de escrita do ciclo, com o contexto de autenticação
      do dono do bot, e só para um bot que vai rodar o ciclo (BR2.5). A marca M0 é lida e a leitura dela termina
      antes de o instantâneo abrir (BR3.1). A leitura abre a própria transação, só de leitura, em isolamento de instantâneo, lê o ledger
      (C12), as ordens assentadas (C13) e as vendas em aberto (C16) no mesmo instantâneo, e fecha antes do ciclo.
      As ordens assentadas que os lotes citam são lidas sempre por id, em lotes de até 1.000 ids, nunca pelo fluxo do
      histórico inteiro (alinhamento A20, decisão F6 do fundador, 2026-11-25).
      Nunca participa da transação do ciclo, nem aninhada.
    violation: a leitura dentro da transação do ciclo é recusada no teste de arquitetura e no teste de integração
    source: [C15, ADR-006, Q1]

  - id: BR2.2
    statement: "[fundador] A leitura tem prazo de 500 ms (decisão F6, 2026-11-25; antes 250 ms)."
    category: constraint
    applies_to: [ENT-403]
    trigger: toda leitura do RealBotHoldings
    logic: >
      O prazo de 500 ms vale para a leitura inteira (consultas e trabalho em memória), com corte no banco e no relógio,
      como o caminho CYCLE da U2. O NFR Design confirma o número pela medição; ele só pode baixar sem o responsável.
    violation: passou do prazo → Unavailable(BUDGET_EXCEEDED) (BR2.3)
    source: [C15 (PQ-9), U2 BR2.7, Q1]

  - id: BR2.3
    statement: "[fundador] Se a leitura falhar ou passar do prazo, o bot não vende naquele ciclo (OWN_QUANTITY_UNAVAILABLE) e tenta de novo no próximo."
    category: policy
    applies_to: [ENT-403, ENT-404, ENT-408]
    trigger: HoldingsResult Unavailable
    logic: >
      Toda falha da leitura (erro de banco, corte de tempo, prazo, bot que não é da pessoa) é tratada dentro da
      transação própria, que é desfeita, e vira Unavailable(motivo). Nada é lançado para o ciclo, e a transação do
      ciclo nem chegou a abrir. O ciclo segue: todos os mints da carteira vão para heldNotSellable (BR1.6), o
      contexto da decisão grava OWN_QUANTITY_UNAVAILABLE com o motivo, e, se mesmo assim chegar uma decisão SELL ao
      corte, o resultado do ciclo é REAL_OWN_QUANTITY_UNAVAILABLE sem decisão REAL. O motivo vai para o log e para a
      métrica real_own_holdings_unavailable_total{reason}, com alerta quando persiste (BR2.7). Nunca usa a posição da
      carteira inteira no lugar.
    violation: não se aplica (falha fechada)
    source: [C15, ADR-006, Q1]

  - id: BR2.4
    statement: A leitura confere o tenant e que o bot é um bot REAL da pessoa.
    category: authorization
    applies_to: [ENT-403]
    trigger: toda leitura do RealBotHoldings
    logic: >
      Tenant e pessoa vêm do bot (linha do bot e contexto de autenticação do dono), nunca de parâmetro livre. IF o bot
      não é bot REAL da pessoa no tenant THEN Unavailable(BOT_NOT_OWNED), sem dado nenhum. As três leituras do
      instantâneo filtram pelo tenant.
    violation: Unavailable(BOT_NOT_OWNED)
    source: [ADR-006 (Security/compliance), project.md ALWAYS validar o tenant, C15]

  - id: BR2.5
    statement: A leitura só acontece para um bot REAL, com a flag ligada, que vai rodar este ciclo.
    category: policy
    applies_to: [ENT-401, ENT-403]
    trigger: início do ciclo, antes da transação
    logic: >
      Hoje o ciclo recebe só o bot e o escopo, e só sabe o mercado do bot depois de travar a linha. A lista de bots
      ativos que o despachante de slots lê passa a trazer o mercado de cada bot (o mesmo dado da linha do bot), e o
      ciclo o recebe junto. Antes da transação, sem trava e só lendo:
        IF o bot é PAPER OR a flag está desligada THEN o RealBotHoldings não é chamado;
        IF o slot passou de 5 minutos OR o slot já foi reclamado OR o bot não está ACTIVE sem motivo de pausa OR a
           pausa do sistema está ligada THEN nem a marca nem o RealBotHoldings são lidos (o ciclo segue e pula como
           hoje, dentro da transação).
      Essas conferências prévias só evitam a leitura de 500 ms em ciclos que vão pular; as conferências que valem
      continuam dentro da transação, sob a trava, como hoje. Se o estado mudar entre as duas, o pior caso é uma
      leitura desperdiçada (aceito) ou um bot REAL que passa a rodar sem a leitura: nesse caso o ciclo trata como
      Unavailable(READ_FAILED) e não vende (BR2.3); a nota do contexto registra `read = NOT_READ` e os contadores de
      leitura não sobem, porque não houve leitura (alinhamento A12; `HoldingsSnapshot.NotRead` no código). O ciclo
      PAPER não muda.
    violation: não se aplica
    source: [Q3, NFR4, revisão R-03]

  - id: BR2.6
    statement: A indisponibilidade da posição própria só afeta a venda do ciclo.
    category: policy
    applies_to: [ENT-409]
    trigger: HoldingsResult Unavailable
    logic: >
      O saldo operável, o dimensionamento da compra, o teto da conta e a barreira REAL são os de hoje. Os mints da
      carteira continuam impedindo a compra deles, como hoje. As compras nunca leem a atribuição.
    violation: não se aplica
    source: [Q2, ADR-006]

  - id: BR2.7
    statement: "[fundador] Uma indisponibilidade persistente da posição própria gera alerta."
    category: policy
    applies_to: [ENT-404]
    trigger: real_own_holdings_unavailable_total crescendo de forma sustentada
    logic: >
      Decisão do responsável em 2026-11-24 (D3). A falha fechada continua: qualquer falha da leitura, mesmo de um mint
      só, para todas as vendas do ciclo do bot, inclusive as saídas por tempo e por perda. Para isso não passar
      despercebido, valem dois alarmes (alinhamento A1 da Code Generation: o texto antigo, "3 ciclos seguidos" com
      `increase[5m] > 0` por 15 min e o gêmeo `period = 300` 3 de 3, foi substituído pela decisão F2 do fundador e
      acrescido da F3):
        - falha sistêmica, por razão (F2, NFR2.12): `RealOpsOwnHoldingsUnavailable`, regra local (Prometheus,
          infra/observability) `sum(increase(real_own_holdings_unavailable_total{reason=~"BUDGET_EXCEEDED|READ_FAILED"}[10m]))
          / sum(increase(real_own_holdings_reads_total[10m])) > 0.05` com `for: 15m`; gêmeo na nuvem (CloudWatch)
          `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, `period = 600`, 2 de 2, dado ausente tratado
          como `notBreaching`;
        - bot preso (F3, NFR2.18): `RealOpsOwnHoldingsStuckBots`, `max(min_over_time(real_own_holdings_stuck_bots[10m]))
          > 0`; gêmeo `Minimum`, `period = 300`, 2 de 2, `notBreaching`. Cobre o bot (ou a carteira de 3 bots) com 3 ou
          mais leituras indisponíveis seguidas, que fica abaixo dos 5% da razão.
      Os alarmes vão para o fundador, único on-call. O runbook (seção 10) diz que, nesse estado, o bot não vende pelo
      ciclo e que a saída é pelas condicionais ou por "Vender tudo e sacar", que nunca leem a atribuição (BR4.1). O
      motivo de cada ciclo e o bot ficam no log; os rótulos das métricas são só enums, sem o bot.
    violation: o teste da regra de alerta (promtool) falha
    source: [D3, project.md (Corrections: CloudWatch 120 s, janelas), team.md (Deployment: fundador on-call)]

  # ---------------------------------------------------------------- BR3 concorrência
  - id: BR3.1
    statement: "[fundador] Dois ciclos do mesmo bot nunca vendem juntos mais que a quantidade própria dele: a marca do bot é conferida sob a trava antes de gravar uma venda."
    category: constraint
    applies_to: [ENT-407, ENT-406]
    trigger: decisão SELL de um ciclo REAL com a flag ligada, depois da trava da linha do bot
    logic: >
      Antes do instantâneo, o ciclo lê a marca do bot (M0, inteiro de 64 bits), e essa leitura termina antes de o
      instantâneo abrir. Dentro da transação do ciclo, depois de travar a linha do bot (a trava do ciclo é por bot),
      ele lê a marca de novo (M1).
      IF M1 ≠ M0 THEN nenhuma decisão SELL é gravada; o resultado é REAL_OWN_QUANTITY_UNAVAILABLE com o motivo
      STALE_SNAPSHOT, e o ciclo seguinte lê de novo.
      Por que basta: toda decisão SELL do bot é gravada por um ciclo que tem a trava do bot e renova a marca na mesma
      transação (BR3.2). Uma decisão gravada antes de M0 já estava confirmada quando o instantâneo começou, então
      está descontada (BR1.3) ou no ledger. Uma decisão gravada entre M0 e a trava muda a marca. Depois da trava,
      nenhum outro ciclo do bot grava até o commit.
    violation: não se aplica; sem a regra, o ciclo atrasado venderia de novo a mesma quantidade
    source: [ADR-006, C15, Q1, Q5]

  - id: BR3.2
    statement: Gravar uma decisão SELL do bot renova a marca do bot na mesma transação.
    category: constraint
    applies_to: [ENT-407]
    trigger: todo ciclo concluído que grava uma decisão
    logic: >
      O ciclo já renova a marca no fim, na mesma transação da decisão. A U4 transforma isso em regra com teste: uma
      decisão SELL gravada sem a marca renovada falha o teste.
    violation: o teste da marca falha
    source: [ADR-006, Q5]

  - id: BR3.3
    statement: Uma única transação por ordem REAL continua garantida pelas travas e pela idempotência que já existem.
    category: constraint
    applies_to: [ENT-406]
    trigger: decisão SELL reprocessada pela fila ou repetida com a mesma Idempotency-Key
    logic: >
      Ficam como estão: a decisão do ciclo é única por decisionId; o RealExecution recusa a decisão com ordem do bot
      em andamento (ORDER_IN_PROGRESS); no máximo uma ordem em andamento por bot e uma venda em andamento por pessoa e
      mint (índices únicos parciais); o consumidor da fila trava a pessoa; a ordem usa a Idempotency-Key do
      RealExecution. A U4 não cria caminho novo de envio.
    violation: o teste de AC6.1.4 falha
    source: [AC6.1.4, ADR-006, team.md (uma única transação por ordem REAL)]

  - id: BR3.4
    statement: O assentamento de uma venda tira a quantidade de "em aberto" e a põe no ledger numa só transação.
    category: constraint
    applies_to: [ENT-405]
    trigger: confirmação ou falha de uma ordem na rede
    logic: >
      Precondição de C15, conferida no código: o assentamento grava os lançamentos do ledger e muda a ordem para o
      estado final na mesma transação (OrderSettlement). Por isso o instantâneo vê a venda ou como em aberto ou no
      ledger, nunca nos dois e nunca em nenhum. Se um dia isso mudar, C16 passa a contar também a ordem SELL
      confirmada sem lançamento no instantâneo.
      Ressalva (revisão R-10): a atomicidade vale quando a leitura do efeito na rede existe e a carteira do bot
      existe. Se a leitura do efeito falha, o assentamento não acontece: a ordem não muda de estado, continua SENT e
      conta como em aberto, com disponível reduzido. É o lado seguro: o bot só deixa de vender, nunca vende a mais.
    violation: o teste "assentamento em paralelo com o ciclo" (functional-spec.md, T8) falha
    source: [C15 (precondição), ADR-006]

  # ---------------------------------------------------------------- BR4 o que não muda
  - id: BR4.1
    statement: "[fundador] Vender tudo e sacar, as saídas condicionais, a liquidação e o saque não mudam e nunca leem a atribuição."
    category: policy
    applies_to: [ENT-401]
    trigger: qualquer um desses caminhos, com a flag ligada ou desligada
    logic: >
      Esses caminhos continuam vendendo a posição da carteira pelo caminho só de quantidade (U2 BR2.5). O saque
      continua disponível a qualquer momento (project.md). O resultado dessas vendas continua rateado entre os
      titulares (U2 BR4.3).
    violation: um teste de arquitetura impede que esses caminhos dependam do RealBotHoldings
    source: [Q2, ADR-006, project.md (saque sempre disponível)]

  - id: BR4.2
    statement: "[fundador] As compras do bot não mudam."
    category: policy
    applies_to: [ENT-409]
    trigger: decisão BUY de um bot REAL
    logic: >
      O saldo operável, o dimensionamento, o teto da conta, a barreira REAL e o conjunto de mints que impede uma compra
      são os de hoje. Nenhuma compra lê o RealBotHoldings nem a atribuição.
    violation: não se aplica
    source: [Q2, ADR-006]

  - id: BR4.3
    statement: O RealExecution continua dono da ordem, da fila e da idempotência, e não calcula a quantidade do bot.
    category: policy
    applies_to: [ENT-406]
    trigger: processamento da decisão SELL pela fila
    logic: >
      O consumidor da fila continua limitando a venda a min(quantidade da decisão, posição da carteira). Ele nunca lê
      a atribuição (ADR-006, alternativa rejeitada "o realexecution calcula a quantidade do bot"). As únicas mudanças
      no RealExecution são a leitura de vendas em aberto (C16 com BR1.3) e nada mais.
    violation: um teste de arquitetura impede que o realexecution dependa do pnl
    source: [ADR-006, C15, C16]

  - id: BR4.4
    statement: O PAPER não muda.
    category: policy
    applies_to: [ENT-401]
    trigger: ciclo de um bot PAPER
    logic: O ciclo PAPER não lê a flag, o RealBotHoldings nem nada do REAL.
    violation: o teste de separação REAL/PAPER falha
    source: [NFR4, project.md (REAL e PAPER separados)]

  # ---------------------------------------------------------------- BR5 flag
  - id: BR5.1
    statement: "[fundador] A flag memetrade.real.own-quantity-sell começa desligada; desligada, o comportamento é o de hoje."
    category: policy
    applies_to: [ENT-401]
    trigger: todo ciclo REAL e toda lista de bots
    logic: >
      IF a flag está desligada THEN o ciclo não chama o RealBotHoldings, as entradas de decisão são as da carteira com
      o custo pelo caminho CYCLE da U2, a venda é da posição da carteira, nenhuma nota nem resultado novo é gravado, e
      BotPage.realOwnQuantitySell é false.
    violation: o teste "flag desligada igual a hoje" (functional-spec.md, T7) falha
    source: [Q3, FR6, AC6.2.2]

  - id: BR5.2
    statement: "[fundador] A flag só é ligada numa implantação auditada separada, depois da Performance Validation do 261113, com a aprovação do responsável."
    category: policy
    applies_to: [ENT-401]
    trigger: mudança do valor da flag
    logic: >
      A flag é uma variável das definições de tarefa da API e do worker, versionada no Terraform. Ligar é uma
      implantação só da flag, nunca junto com mudança de código, nos dois serviços com o mesmo valor, primeiro na
      devnet e depois na mainnet com o mesmo SHA, e só depois que a Performance Validation do intent
      261113-trading-real-bot passar e o responsável aprovar.
      Ordem (decisão do responsável em 2026-11-24, D4), porque os dois serviços não viram juntos:
        - para ligar: primeiro a API (o aviso aparece), depois o worker (o ciclo passa a vender só a própria
          quantidade); entre os dois, o aviso aparece um pouco antes da mudança, o que não muda dinheiro;
        - para desligar: primeiro o worker, depois a API; o aviso some um pouco depois da mudança.
      Condição da fila (BR5.5): o worker só é virado com a fila sem decisão SELL PENDING.
    violation: a implantação não é feita
    source: [Q3, D4, team.md (Flags, Fluxo de implantação), requirements.md (Ordem de entrega), ADR-006]

  - id: BR5.3
    statement: "[fundador] BotPage.realOwnQuantitySell reflete a flag."
    category: policy
    applies_to: [ENT-410]
    trigger: toda resposta da lista de bots
    logic: realOwnQuantitySell = valor da flag na API; sempre presente; false com a configuração padrão.
    violation: o teste de contrato da lista falha
    source: [C7, Q3]

  - id: BR5.4
    statement: Desligar a flag volta ao comportamento de hoje no ciclo seguinte, sem migração.
    category: policy
    applies_to: [ENT-401]
    trigger: implantação que desliga a flag (rollback)
    logic: >
      Nada é gravado de forma que dependa da flag, além das notas e resultados do ciclo, que ficam como histórico.
      Decisões e ordens já gravadas seguem pelo RealExecution como qualquer outra.
    violation: não se aplica
    source: [team.md (Rollback, Flags), Q3, D4]

  - id: BR5.5
    statement: "[fundador] O worker só muda a flag com a fila sem decisão SELL PENDING, conferida antes e depois da troca."
    category: policy
    applies_to: [ENT-401, ENT-405]
    trigger: implantação que liga a flag no worker
    logic: >
      Uma decisão SELL gravada com a flag desligada leva a quantidade da posição inteira da carteira. Se ela ainda
      estiver na fila quando a flag liga, o consumidor a processa uma vez, como hoje, e pode vender a parte de outro
      bot. Por isso (D4):
        1. Logo antes de virar o worker, o fundador roda a conferência só de leitura do runbook da virada (seção 11),
           numa sessão `REPEATABLE READ READ ONLY` com um instantâneo único (alinhamento A7): a contagem de decisões
           SELL em PENDING (inclusive as adiadas) é zero e a contagem de ordens SELL de bot em andamento (PROPOSED,
           QUOTED, SIGNED, SENT, AMBIGUOUS) também é zero. Se não for, espera e confere de novo.
        2. Durante a troca rolling, tarefas antigas (flag desligada) e novas (flag ligada) convivem por alguns minutos.
           Depois que a última tarefa antiga sai, o fundador repete as duas contagens e lista, na mesma sessão, as
           decisões SELL gravadas desde a primeira conferência sem a nota da quantidade própria
           (`NOT jsonb_exists(d.context, 'ownQuantity')`, nunca o operador `?`): são as gravadas por tarefa antiga.
        3. Se uma decisão assim existiu, a venda dela é a de hoje (posição da carteira), e o excesso vai para o rateio
           "vendido por outro bot" da U2; o P&L continua exato. O caso fica registrado na nota da implantação.
      Uma ordem SELL em andamento na virada não precisa esperar: já foi cortada pelo consumidor e conta como em aberto
      (BR1.3). A conferência é uma leitura do RealExecution, nunca uma escrita.
    violation: a virada do worker não é feita
    source: [D4, revisão R-05, team.md (Flags)]

  # ---------------------------------------------------------------- BR6 aviso na tela
  - id: BR6.1
    statement: "[fundador] Com realOwnQuantitySell verdadeiro e o filtro diferente de Simulado, a lista de bots mostra o aviso fixo da Q4."
    category: policy
    applies_to: [ENT-411]
    trigger: a lista /bots carregada
    logic: >
      Decisão do responsável em 2026-11-24 (D5): o aviso não depende da página corrente nem da paginação.
      IF realOwnQuantitySell = true AND o filtro de modo é Todos ou Real THEN mostra o texto exato
      "Cada bot real vende só a quantidade que ele mesmo comprou. Posições herdadas ou de outros bots continuam na
      carteira e podem ser vendidas em Vender tudo e sacar."
    violation: o teste da lista falha
    source: [FR6.2, AC6.2.1, Q4, D5]

  - id: BR6.2
    statement: "[fundador] O aviso é texto com role=status, não depende só de cor e não promete resultado."
    category: validation
    applies_to: [ENT-411]
    trigger: aviso visível
    logic: >
      O aviso é um elemento com role="status" cujo nome acessível é o próprio texto; sem ícone obrigatório e sem
      significado só por cor; o texto não fala de lucro nem de garantia (C-16). Em 360 px não há rolagem lateral.
    violation: o teste de acessibilidade da lista falha
    source: [FR6.2, AC6.2.3, Q4, NFR5, NFR6, project.md (acessibilidade básica)]

  - id: BR6.3
    statement: "[fundador] Com a flag desligada, o aviso não aparece."
    category: policy
    applies_to: [ENT-411]
    trigger: a lista /bots carregada com realOwnQuantitySell false ou ausente, ou com o filtro Simulado
    logic: IF realOwnQuantitySell ≠ true OR o filtro de modo é Simulado THEN o aviso não é renderizado.
    violation: o teste da lista falha
    source: [FR6.2, AC6.2.2, Q4]

  # ---------------------------------------------------------------- BR7 conferências
  - id: BR7.1
    statement: Sem vendas em aberto, a quantidade própria do RealBotHoldings é igual à quantidade do bot no RealBotPnl para o mesmo ledger.
    category: constraint
    applies_to: [ENT-402]
    trigger: teste de paridade
    logic: >
      Para todo bot e mint, com openSellBaseUnits = 0, ownTokenBaseUnits (RealBotHoldings) = quantidade do bot
      (RealBotPnl) e ownCostLamports = custo restante do bot (RealBotPnl), no mesmo ledger.
    violation: o teste de paridade falha
    source: [ADR-006 (Consequences), C15]

  - id: BR7.2
    statement: A conferência do P&L da U2 continua valendo com a flag ligada.
    category: constraint
    applies_to: [ENT-402]
    trigger: teste de conferência e conferência em produção
    logic: >
      A soma dos bots mais a linha "Sem bot" bate com o ledger REAL, com a flag ligada ou desligada (U2), sem exceção.
      Com a flag ligada, as vendas novas dos bots não trazem parcela "vendido por outro bot" (BR1.7), exceto nos dois
      casos aceitos pelo responsável: a condicional executada entre a leitura e o processamento da decisão (D2) e uma
      decisão SELL gravada com a flag desligada e processada depois da virada (BR5.5). Nesses casos a parcela aparece
      e o P&L continua exato.
    violation: a conferência acusa diferença
    source: [Q5, FR5, AC6.1.2, D2, D4]
```

## Resumo das regras

| ID | Regra | Categoria |
|---|---|---|
| BR1.1 | Quantidade própria = titular bot no caminho de atribuição da U2 | calculation |
| BR1.2 | Disponível = max(0, própria − em aberto), AMBIGUOUS em aberto | calculation |
| BR1.3 | Em aberto = ordens SELL em andamento + decisões SELL na fila (extensão de C16) | calculation |
| BR1.4 | Venda = min(pedido, disponível) | constraint |
| BR1.5 | Disponível zero → sem venda, NO_OWN_QUANTITY | constraint |
| BR1.6 | Saída sobre a posição própria; compra impedida pelo conjunto da carteira | policy |
| BR1.7 | Não vende a parte de outro bot, com a exceção aceita da condicional no intervalo (D2) | constraint |
| BR1.8 | O registro de abertura de um mint sem quantidade disponível não é fechado | constraint |
| BR2.1 | Um instantâneo, transação própria, antes e fora do ciclo | policy |
| BR2.2 | Prazo de 500 ms (F6) | constraint |
| BR2.3 | Falha ou prazo → OWN_QUANTITY_UNAVAILABLE, sem venda, nova tentativa no próximo ciclo | policy |
| BR2.4 | Tenant e posse do bot conferidos | authorization |
| BR2.5 | Só bot REAL, flag ligada, que vai rodar o ciclo; o mercado chega com a lista de bots ativos | policy |
| BR2.6 | A indisponibilidade só afeta a venda | policy |
| BR2.7 | Alerta de indisponibilidade: razão acima de 5% por 15 min (F2) e bot preso por 10 min (F3) (D3, A1) | policy |
| BR3.1 | Marca do bot conferida sob a trava (STALE_SNAPSHOT) | constraint |
| BR3.2 | Decisão SELL renova a marca na mesma transação | constraint |
| BR3.3 | Uma transação por ordem REAL, com as travas que já existem | constraint |
| BR3.4 | Assentamento atômico: em aberto ou no ledger | constraint |
| BR4.1 | Vender tudo, condicionais, liquidação e saque não mudam | policy |
| BR4.2 | Compras não mudam | policy |
| BR4.3 | RealExecution não calcula a quantidade do bot | policy |
| BR4.4 | PAPER não muda | policy |
| BR5.1 | Flag desligada por padrão = hoje | policy |
| BR5.2 | Ligar só por implantação auditada separada, depois da Performance Validation | policy |
| BR5.3 | C7 reflete a flag | policy |
| BR5.4 | Desligar volta ao de hoje, sem migração | policy |
| BR5.5 | Worker só vira com a fila sem decisão SELL PENDING, conferida antes e depois (D4) | policy |
| BR6.1 | Aviso com o texto exato da Q4 com a flag ligada e o filtro diferente de Simulado (D5) | policy |
| BR6.2 | role=status, sem depender de cor, sem promessa | validation |
| BR6.3 | Flag desligada ou filtro Simulado → sem aviso | policy |
| BR7.1 | Paridade com o RealBotPnl | constraint |
| BR7.2 | Conferência com o ledger continua, sem exceção; a parcela "vendido por outro bot" só nos casos aceitos | constraint |

## Sources

- `construction/u4-own-quantity-cycle/functional-design/functional-design-questions.md` [Q1] a [Q5] (resumo confirmado)
- `inception/requirements-analysis/requirements.md` (FR6.1, FR6.2, NFR4 a NFR6, Ordem de entrega)
- `inception/user-stories/stories.md` (AC6.1.1 a AC6.1.4, AC6.2.1 a AC6.2.3)
- `inception/domain-design/decisions.md` (ADR-006)
- `inception/contract-design/contract-summary.md` (C7, C15, C16)
- `construction/u2-real-bot-pnl/functional-design/rules.md` (BR1.1, BR2.1 a BR2.7, BR3.1, BR4.3, BR4.4)
- `aidlc/spaces/default/memory/team.md` (Testing Posture, Deployment: Flags e Rollback) e `project.md` (Mandated, Forbidden)
- Código: `RealCycle`, `CycleRunner`, `RealDecisionStep`, `LockOrder.lockBot`, `JdbcBotRepository.renewMark`, `RealExecutionPort`, `DecisionConsumer`, `OrderSettlement`, `DecisionEngine`

## Assumptions & Open Questions

- **Decisões do responsável em 2026-11-24.** D1: extensão de C16 aceita (BR1.3). D2: risco da condicional no intervalo aceito (BR1.7, BR7.2). D3: alerta de indisponibilidade persistente, com as condicionais e "Vender tudo e sacar" como saída (BR2.7). D4: ordem e condição da virada da flag (BR5.2, BR5.5). D5: aviso pela flag e pelo filtro, sem depender da página (BR6.1).
- **Trava do ciclo (BR3.1).** O C15 diz que a trava do ciclo é "por bot e pessoa (`RealRiskGate.withPersonLock`)". No código, o ciclo trava só a linha do bot; a trava da pessoa é tomada pelo consumidor da fila, pela liquidação e pela barreira só em compras. A garantia de concorrência usa a marca do bot (BR3.1).
- **Prazo (BR2.2).** 250 ms vinha do resumo confirmado da Q1 e do caminho CYCLE da U2. Depois do Build and Test, o fundador decidiu 500 ms (F6, 2026-11-25, loop-back 1; alinhamento A20): é decisão dele depois da medição na CI, nunca um afrouxamento feito pela Code Generation.
- **Resultados novos do ciclo.** `REAL_NO_OWN_QUANTITY` e `REAL_OWN_QUANTITY_UNAVAILABLE` (o maior tem 29 caracteres; a coluna tem 32) entram na regra de valores aceitos do resultado do ciclo numa migração só de expansão, especificada em `functional-spec.md` ("Efeito nos contratos").
- [assumption] A lista de bots ativos do despachante de slots pode trazer o mercado do bot sem mudar o contrato público (BR2.5); é uma mudança interna do módulo bots.
