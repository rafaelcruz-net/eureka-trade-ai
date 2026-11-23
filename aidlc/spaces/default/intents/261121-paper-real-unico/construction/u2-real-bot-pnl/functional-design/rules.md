# Regras de negócio — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

Todos os valores REAL são inteiros exatos: lamports e unidades base. As frações usam inteiros de precisão arbitrária e nunca ponto flutuante. Os IDs `BRx.y` são desta Unit; quando uma regra cita a U1, o ID da U1 vem com o prefixo "U1".

As regras marcadas **[fundador]** vêm das respostas confirmadas Q1, Q2, Q3, Q5 e Q6 ou das três decisões do fundador de 2026-11-22, tomadas depois da revisão `reviews/review-01.md`:
- D1: a posição da carteira é a soma dos titulares, com um caminho só de quantidade;
- D2: o excedente é rateado entre todos os outros titulares;
- D3: UNKNOWN_BOT derruba só o bloco de P&L REAL.

Nenhuma delas pode ser mudada na Code Generation.

```yaml
rules:
  # ---------------------------------------------------------------- BR1 ordem de rede e slot
  - id: BR1.1
    statement: As operações REAL de uma carteira do bot são aplicadas na ordem de rede, pela chave NetworkOrderKey.
    category: constraint
    applies_to: [ENT-201, ENT-203, ENT-207]
    trigger: todo cálculo de posição, custo, rateio, P&L ou total REAL
    logic: >
      Ordenar por effectiveSlot. No mesmo slot, as linhas com slot gravado vêm antes das herdadas. Entre as gravadas,
      a ordem é txSignature (BR1.2) e, dentro da mesma assinatura, o lugar do lançamento na transação. Entre as
      herdadas, a ordem de gravação. O desempate final nunca é um UUID aleatório (entryId). O ledger não tem coluna de
      sequência de inserção, e a C14 proíbe acrescentar uma. Por isso, os desempates deterministas, nesta ordem, são:
        (1) createdAt;
        (2) reference;
        (3) a ordem do tipo dentro do evento: SWAP_FILL_IN ou SWAP_FILL_OUT = 0, NETWORK_FEE = 1, RENT = 2, demais = 3,
            como o kind_rank do código de hoje;
        (4) idempotencyKey.
      Todo desempate de texto (txSignature, reference, idempotencyKey) compara com COLLATE "C", byte a byte, na
      consulta e no código (decisão F-1; alinhamento A9).
      A idempotencyKey é única e montada só com identificadores de negócio (id da ordem e tipo, ou assinatura do fill
      e sufixo). Por isso a ordem é a mesma em toda leitura e em todo ambiente. A ordem de gravação nunca decide entre
      duas linhas com slot gravado e assinaturas diferentes.
    violation: um lançamento fora de ordem é recusado pelo acumulador (C11), e o bloco REAL fica UNAVAILABLE
    source: [FR3.8, ADR-011, C12, Q4, review-01 R-06]

  - id: BR1.2
    statement: No mesmo slot, a assinatura menor vem primeiro.
    category: constraint
    applies_to: [ENT-201]
    trigger: duas linhas com o mesmo slot gravado e assinaturas diferentes
    logic: >
      Comparar as assinaturas como texto, caractere a caractere, pelo código ASCII de cada caractere. Os lançamentos
      da mesma transação têm a mesma assinatura e seguem createdAt, reference, kindRank e idempotencyKey (BR1.1;
      alinhamento A9). No banco, a comparação usa COLLATE "C".
    violation: não se aplica (ordem total)
    source: [FR3.8, AC3.6.1]

  - id: BR1.3
    statement: Uma linha sem slot gravado herda o slot da última linha com slot gravado antes dela na mesma carteira.
    category: calculation
    applies_to: [ENT-201, ENT-203]
    trigger: slotStatus SLOT_MISSING ou NO_NETWORK_TX
    logic: >
      Percorrer todas as linhas da carteira na ordem de gravação (createdAt, reference, kindRank, idempotencyKey, com
      COLLATE "C" nos textos; nunca entryId; alinhamento A9), de qualquer tipo. SE a linha tem
      slot gravado ENTÃO ele vira o slot corrente. SENÃO a linha recebe effectiveSlot = slot corrente (0 quando ainda
      não houve nenhum) e inherited = true. A herança é calculada sobre todas as linhas, e nunca só sobre os tipos que
      um leitor pediu, para que a chave seja a mesma para todos os leitores.
    violation: não se aplica
    source: [ADR-011, C12, PQ-4]

  - id: BR1.4
    statement: O slot é gravado na confirmação, uma vez por assinatura, sem nunca sobrescrever.
    category: policy
    applies_to: [ENT-202, ENT-215]
    trigger: >
      uma ordem REAL chega a CONFIRMED ou a FAILED com transação; uma condicional é executada (FillPosting); um
      depósito, saque ou taxa da carteira do bot é confirmado
    logic: >
      Na mesma transação da mudança de estado e dos lançamentos, gravar (assinatura, slot) com recordSlot. SE a
      assinatura não tem slot ENTÃO grava (RECORDED). SE já tem o mesmo slot ENTÃO não faz nada (ALREADY_RECORDED). SE
      já tem outro slot ENTÃO mantém o primeiro, recusa o novo (CONFLICT_REFUSED), audita REAL_SLOT_CONFLICT e conta na
      métrica ledger_real_slot_conflicts_total (alinhamento A3). A confirmação nunca falha por causa do slot. SE o slot não é conhecido na
      confirmação ENTÃO nada é gravado e a linha fica SLOT_MISSING. O slot nunca é buscado na rede durante a leitura.
    violation: conflito auditado; o lançamento e a mudança de estado seguem
    source: [ADR-011, C13, C14]

  - id: BR1.5
    statement: Nenhuma linha anterior à migração 0023 é preenchida; elas ficam SLOT_MISSING e são contadas.
    category: policy
    applies_to: [ENT-202, ENT-203]
    trigger: leitura de uma linha com assinatura e sem linha em real_entry_slot
    logic: >
      slotStatus = SLOT_MISSING, calculado na leitura, sem coluna nova e sem alterar a linha antiga. Cada leitura
      conta as linhas SLOT_MISSING na métrica de C12. RESERVE, RESERVE_RELEASE sem assinatura e COMPENSATION são
      NO_NETWORK_TX.
    violation: não se aplica
    source: [PQ-4, C12, C14, Q4]

  - id: BR1.6
    statement: A mesma chave de ordem vale para a carteira e para cada bot, em todos os leitores.
    category: constraint
    applies_to: [ENT-201, ENT-206]
    trigger: RealPositions, RealCostMethod, RealCostBasis, RealBotPnl, RealBotHoldings
    logic: a chave é calculada só pelo provedor da leitura em fluxo (C12); nenhum leitor reordena
    violation: o teste de paridade (BR9.1) falha
    source: [ADR-011, ADR-002, Q4]

  - id: BR1.7
    statement: >
      O ADR-011 fica alinhado à decisão do Contract Design: tabela lateral real_entry_slot, sem coluna slot em
      real_order nem em real_ledger_entry, sem preenchimento das linhas antigas, com SLOT_MISSING.
    category: policy
    applies_to: [ENT-202]
    trigger: Code Generation da migração 0023 e das leituras
    logic: >
      Onde o ADR-011 (e a responsabilidade da U2 em unit-of-work.md) fala em "coluna slot nas duas tabelas" e
      "preenchimento das linhas antigas", vale C14: a migração 0023-real-entry-slot.sql cria só a tabela lateral, os
      gatilhos que recusam UPDATE, DELETE e TRUNCATE e as permissões (SELECT e INSERT para a aplicação). O fallback do
      ADR-011 continua (BR1.3). Na NetworkOrderKey de C12, o último desempate deixa de ser entryId e passa a ser
      (createdAt, reference, ordem do tipo, idempotencyKey) (BR1.1). O decisions.md e o contract-summary.md não são
      editados; este registro vale para a Code Generation e para a U4.
    violation: não se aplica
    source: [PQ-2, PQ-4, C12, C14, ADR-011, Q4]

  # ---------------------------------------------------------------- BR2 custo médio único
  - id: BR2.1
    statement: O custo de uma compra é o total de lamports efetivamente pagos pelo token, sem a taxa da rede.
    category: calculation
    applies_to: [ENT-206, ENT-207]
    trigger: evento BUY
    logic: >
      custo da compra = |perna SOL do SWAP_FILL_IN| + RENT da mesma referência (conta de token criada pela compra).
      A taxa da rede nunca entra no custo: ela vai para a linha de taxas (BR6.1). O custo nunca usa preço.
    violation: não se aplica
    source: [FR3.10, Q5, AC3.6.1, AC3.7.2]

  - id: BR2.2
    statement: "[fundador] Numa venda parcial, o custo retirado é arredondado para cima; a última venda leva o custo restante."
    category: calculation
    applies_to: [ENT-206, ENT-208]
    trigger: um titular com quantidade q e custo c vende s unidades, com 1 <= s <= q
    logic: >
      SE s = q ENTÃO custo retirado = c (a posição zera com custo 0). SENÃO custo retirado = teto(c × s ÷ q), e o
      custo restante é c − custo retirado. O resto do arredondamento fica no lote aberto. A mesma regra vale para
      cada bot e para a parcela sem dono; a posição da carteira é a soma delas (BR2.4).
    violation: não se aplica
    source: [Q1, FR5.2, ADR-002, AC3.2.2, AC3.4.2]

  - id: BR2.3
    statement: Um titular nunca vende mais do que tem; a quantidade além do que ele tem é tratada pelo rateio.
    category: constraint
    applies_to: [ENT-206, ENT-208]
    trigger: venda com quantidade maior que a do titular
    logic: >
      A parte de cada titular é limitada à quantidade dele logo antes da venda. O que passa disso segue BR4.4
      (venda própria) ou BR4.3 (venda sem bot). Quantidade e custo nunca ficam negativos.
    violation: não se aplica
    source: [FR3.4, Q3]

  - id: BR2.4
    statement: "[fundador] Um único custo médio REAL: o custo da carteira é a soma dos custos dos titulares, todos pela mesma regra."
    category: constraint
    applies_to: [ENT-206]
    trigger: toda leitura de custo REAL (RealBotPnl, RealBotHoldings, o fallback de valor da barreira, RealCycle, GET /api/v1/real/pnl)
    logic: >
      O RealCostBasis é o único algoritmo de custo (BR2.1, BR2.2). O custo da carteira por token é a soma dos custos
      dos bots e da parcela sem dono, calculados com a atribuição de cada evento (BR3, BR4). Não existe um segundo
      custo médio da carteira inteira. Com vendas próprias de bots que têm custos médios diferentes, um custo médio da
      carteira divergiria da soma dos bots e faria a conferência falhar sem defeito real. Só o custo depende da
      atribuição; a quantidade vem do caminho só de quantidade (BR2.5), que não depende dela.
    violation: o teste de paridade (BR9.1) falha
    source: [Q1, decisão do fundador D1 (2026-11-22), FR5.2, ADR-002, C11, AC5.2.1, AC5.2.2]

  - id: BR2.5
    statement: "[fundador] A quantidade de token vem de um caminho só de quantidade, direto do ledger, que nunca depende da atribuição."
    category: constraint
    applies_to: [ENT-206]
    trigger: >
      JdbcRealLedger.positions e view; a barreira REAL (RealGateInputsReader); as entradas de venda do RealCycle; os
      candidatos do LiquidationJob; o RealBotHoldings da U4 (quantidade da carteira)
    logic: >
      Por carteira e token, a quantidade é lida só do ledger, na chave de ordem de rede (BR1.1), e só dos
      SWAP_FILL_IN e SWAP_FILL_OUT. Cada IN soma a quantidade. Cada OUT tira min(vendido, quantidade corrente), a mesma
      regra que leva o que passa disso para UNOWNED sem custo (BR5.3). Esse caminho não lê ordens (C13) nem o
      diretório (C8). Por isso nem uma falha dessas leituras nem um UNKNOWN_BOT bloqueiam uma venda, uma liquidação ou
      a barreira de venda. O algoritmo próprio de custo do RealPositions sai (arredondamento para baixo, ordem de
      gravação). O que fica é este caminho de quantidade, como função pura do lado do ledger. Por construção, a
      quantidade dele é igual à soma das quantidades dos titulares (BR9.1).
    violation: o teste de paridade (BR9.1) falha
    source: [decisão do fundador D1 (2026-11-22), FR5.2, ADR-002, review-01 R-01]

  - id: BR2.6
    statement: O custo restante depois de uma venda parcial pode ficar até 1 lamport abaixo da fração exata, por titular; ao zerar, fica exato.
    category: constraint
    applies_to: [ENT-206]
    trigger: venda parcial (BR2.2)
    logic: >
      Pela Q1, teto(c × s ÷ q) − c × s ÷ q < 1. O custo restante de cada titular que vende em parte fica abaixo da
      fração exata por menos de 1 lamport a cada venda parcial dele, e volta a ser exato quando a posição dele zera. O
      custo da carteira (soma dos titulares) fica abaixo, no máximo, pelo número de titulares que venderam em parte.

      Quem usa o custo REAL:
        (a) o fallback de valor da barreira (value()), quando o preço de uma posição é desconhecido: perda diária e
            instantâneo do dia;
        (b) o PositionInput do RealCycle.inputs;
        (c) o GET /api/v1/real/pnl e o RealBotPnl.
      A barreira de compra não depende desse custo: uma compra com uma posição sem preço é recusada antes
      (positionPrices, POSITION_PRICE_UNKNOWN). O teto e a saída mínima do ADR-004 do 261113 usam a cotação.

      Isso inverte a escolha do 261113 de arredondar o custo retirado para baixo para nunca subestimar o custo
      restante. A inversão é aceitável por três motivos:
        - o fundador escolheu o arredondamento contra o usuário no realizado (Q1);
        - a diferença é de poucos lamports, abaixo da granularidade de qualquer limite;
        - no fallback de valor, um custo um pouco menor dá um valor um pouco menor, então a perda diária medida fica
          igual ou maior, o lado conservador para o limite.
      Este texto substitui "o custo restante nunca fica menor que o real" de C11 e do ADR-002 (BR2.8).
    violation: não se aplica
    source: [Q1, ADR-002, C11, review-01 R-04]

  - id: BR2.7
    statement: "[fundador] O custo com atribuição é uma leitura separada, com orçamento de tempo e mapeamento de falha."
    category: policy
    applies_to: [ENT-204, ENT-206]
    trigger: qualquer leitor de custo REAL (BR2.6, itens a a c)
    logic: >
      A atribuição (ordens de C13 e bots de C8) é lida fora do caminho de quantidade, só por quem precisa de custo.
      A barreira só a lê quando uma posição da carteira está sem preço e o value() precisa do custo, e nunca para
      montar as quantidades.

      Orçamento inicial, que o NFR Design confirma pela medição sob o lock da carteira e só pode baixar sem o
      fundador:
        - 250 ms para a leitura de atribuição no caminho da barreira e do RealCycle;
        - 500 ms no caminho do P&L, dentro do prazo do bloco de 800 ms (memetrade.pnl.by-mode.block-timeout)
          (alinhamento A1 da Code Generation, decisão D-NFR1 do fundador).

      Mapeamento de falha (timeout, erro de leitura ou UNKNOWN_BOT):
        - P&L: o bloco REAL fica UNAVAILABLE. Com erro técnico, a causa é técnica. Com UNKNOWN_BOT, a causa é
          RECONCILIATION_MISMATCH, auditada (BR3.4). O bloco PAPER sai normalmente.
        - Barreira: a nova dependência ATTRIBUTION entra no mapeamento de falha do RealGateInputsReader, como a
          LEDGER. Sem custo, a posição sem preço fica sem valor. A compra é recusada, como hoje com preço
          desconhecido (POSITION_PRICE_UNKNOWN), e a perda diária não é avaliada como cumprida, o que também recusa a
          compra (falha fechada). A venda, a liquidação e o saque nunca leem a atribuição e seguem.
        - RealCycle: a quantidade sempre chega. O custo chega como desconhecido. O ciclo não decide compra desse token
          com custo desconhecido, e a venda segue pela quantidade (a U4 detalha).
    violation: falha fechada para compra; nunca bloqueia venda, liquidação nem saque
    source: [decisão do fundador D1 (2026-11-22), review-01 R-01, NFR1]

  - id: BR2.8
    statement: O ADR-002 e o C11 ficam alinhados à soma dos titulares, ao caminho só de quantidade e ao arredondamento da Q1.
    category: policy
    applies_to: [ENT-206]
    trigger: Code Generation de C11 e do ledger; U4 (RealBotHoldings)
    logic: >
      Onde o ADR-002 e o C11 dizem outra coisa, vale o seguinte:
        (1) A porta RealCostMethod continua pura (sem I/O, sem relógio, sem ponto flutuante). Ela tem dois usos: o
            caminho só de quantidade (BR2.5), que recebe os fills da carteira sem atribuição; e o acumulador de custo
            por titular (BR2.1, BR2.2), que recebe eventos já atribuídos.
        (2) O CostFill do caminho de custo ganha o titular (ENT-205), e o evento de venda chega já rateado (BR4). O
            CostFill do caminho de quantidade não muda.
        (3) A atribuição é uma leitura separada, em pnl.application, com o orçamento e a falha da BR2.7. O ledger
            nunca a lê e nunca depende do pnl (LedgerArchitectureTest.theLedgerNeverDependsOnThePnl sem exceção).
        (4) As posições de positions() e view() trazem a quantidade do caminho só de quantidade. O custo da carteira
            vem da leitura de custo (soma dos titulares), e só para quem precisa dele (BR2.6, itens a a c).
        (5) O texto "o custo restante nunca fica menor que o real" passa a ser o da BR2.6.
        (6) A paridade do ADR-002 passa a ser a da BR9.1, com oráculos independentes.
      O decisions.md e o contract-summary.md não são editados; este registro vale para a Code Generation e para a U4.
    violation: não se aplica
    source: [decisão do fundador D1 (2026-11-22), ADR-002, C11, review-01 R-02]

  # ---------------------------------------------------------------- BR3 titulares e atribuição
  - id: BR3.1
    statement: O titular de uma compra é o bot da ordem; sem bot resolvido, é a parcela sem dono.
    category: calculation
    applies_to: [ENT-204, ENT-205, ENT-207]
    trigger: evento BUY
    logic: >
      SE a referência é order:<id> e a ordem tem bot REAL da pessoa ENTÃO o titular é esse bot. SE a ordem não existe
      mais (expurgada) ENTÃO o titular é UNOWNED e a linha "Sem bot" ganha o motivo ORDER_RECORD_MISSING. Cada compra
      tem um só titular; uma compra nunca é rateada.
    violation: não se aplica
    source: [FR3.2, ADR-004, C13, AC3.2.1, AC3.2.3]

  - id: BR3.2
    statement: Cada venda é classificada pela origem da ordem ou da referência.
    category: calculation
    applies_to: [ENT-207]
    trigger: SWAP_FILL_OUT de negociação
    logic: >
      SE order:<id> com origin BOT ENTÃO OWN_SALE com o bot da ordem como vendedor (BR4.4). SE order:<id> com origin
      USER_LIQUIDATION ENTÃO SALE_WITHOUT_BOT com origem LIQUIDATION (BR4.3). SE conditional:<id> ENTÃO
      SALE_WITHOUT_BOT com origem CONDITIONAL (BR4.3), sobre a quantidade executada. SE a ordem não existe mais ENTÃO
      OWN_SALE com UNOWNED como vendedor e o motivo ORDER_RECORD_MISSING.
    violation: não se aplica
    source: [FR3.3, FR3.4, FR5.1, C13, AC5.1.3]

  - id: BR3.3
    statement: Um bot pausado ou removido continua titular dos seus lotes e do seu P&L.
    category: policy
    applies_to: [ENT-205, ENT-210]
    trigger: qualquer cálculo
    logic: >
      O estado do bot (ACTIVE, PAUSED, REMOVED, do diretório C8) nunca tira o bot da posse, do rateio nem das linhas.
      Ele participa do rateio pela quantidade que tem, e a linha dele aparece com o estado. Rascunhos não aparecem
      (BR4.1 da U1).
    violation: não se aplica
    source: [FR3.11, AC3.9.1, AC3.9.2, AC3.9.3]

  - id: BR3.4
    statement: "[fundador] Uma ordem cujo bot não é bot REAL da pessoa deixa só o bloco de P&L REAL indisponível."
    category: validation
    applies_to: [ENT-204, ENT-213]
    trigger: ordem com botId fora dos bots REAL da pessoa no diretório, na leitura de atribuição
    logic: >
      O cálculo do P&L para. A conferência registra UNKNOWN_BOT, e o evento REAL_PNL_RECONCILIATION_MISMATCH é
      auditado com esse motivo. Só o bloco REAL do P&L (C4, C10 e GET /api/v1/real/pnl) fica UNAVAILABLE, com
      cause=RECONCILIATION_MISMATCH (BR8.7). A ordem nunca vai para a linha "Sem bot". A venda, a liquidação e a
      barreira de venda nunca são bloqueadas, porque usam o caminho só de quantidade (BR2.5). No fallback de custo da
      barreira, um UNKNOWN_BOT conta como falha de atribuição (BR2.7).
    violation: bloco de P&L REAL UNAVAILABLE, auditado; venda e liquidação seguem
    source: [decisão do fundador D3 (2026-11-22), ADR-007, NFR3, Q6]

  # ---------------------------------------------------------------- BR4 rateio
  - id: BR4.1
    statement: "[fundador] Todo rateio de um inteiro usa o método do maior resto."
    category: calculation
    applies_to: [ENT-208]
    trigger: rateio de quantidade de token, de valor recebido ou de taxa
    logic: >
      Dado um total V e pesos w_i > 0 com soma W: cada participante recebe chão(V × w_i ÷ W). As unidades que sobram
      vão uma a uma para os participantes com o maior resto (V × w_i mod W), na ordem de desempate de BR4.2. A soma
      das partes é sempre exatamente V.
    violation: a conferência (BR8.4) falha
    source: [FR3.7, Q2, AC3.3.2, AC3.4.1]

  - id: BR4.2
    statement: O empate no maior resto é decidido pela data de criação do bot, depois pelo id; a parcela sem dono fica por último.
    category: calculation
    applies_to: [ENT-205]
    trigger: dois participantes com o mesmo resto
    logic: >
      Ordem: bots pela data de criação (mais antiga primeiro), depois pelo id na forma textual canônica, em
      minúsculas, comparada caractere a caractere (a mesma ordem do diretório); UNOWNED por último.
    violation: não se aplica
    source: [FR3.7, AC3.4.1]

  - id: BR4.3
    statement: Uma venda sem bot (liquidação ou condicional) é rateada entre os titulares pela quantidade de logo antes da venda.
    category: calculation
    applies_to: [ENT-207, ENT-208]
    trigger: evento SALE_WITHOUT_BOT com quantidade vendida S
    logic: >
      Participantes: os titulares com quantidade > 0 do token na carteira logo antes da venda, bots e UNOWNED. Seja H
      a soma das quantidades. SE S <= H ENTÃO a quantidade de cada um é o rateio de S pelo maior resto, com peso igual
      à quantidade dele (BR4.1). SE S > H ENTÃO cada titular vende tudo o que tem, e S − H vai para UNOWNED sem custo
      (BR5.3). Uma condicional executada em parte é rateada só sobre a quantidade executada.
    violation: não se aplica
    source: [FR3.3, Q2, AC3.3.1, AC3.3.2, AC3.4.1, AC3.4.2, AC3.4.4, AC3.9.1]

  - id: BR4.4
    statement: "[fundador] Na venda de um bot, ele vende primeiro a própria quantidade; o excedente é rateado entre todos os outros titulares, a parcela sem dono incluída."
    category: calculation
    applies_to: [ENT-207, ENT-208]
    trigger: evento OWN_SALE com vendedor X e quantidade vendida S
    logic: >
      X vende min(S, quantidade de X). Seja E o excedente. SE E > 0 ENTÃO os participantes são os outros titulares
      com quantidade > 0: os outros bots e UNOWNED com a quantidade registrada dele. Seja H' a soma das quantidades
      deles. SE E <= H' ENTÃO E é rateado pelo maior resto, com peso igual à quantidade de cada um (BR4.1), como na
      BR4.3. SE E > H' ENTÃO cada um vende tudo o que tem, e E − H' vai para UNOWNED sem custo (BR5.3). A parte de
      UNOWNED da quantidade registrada leva UNOWNED_EXCESS; a parte sem custo leva UNOWNED_NO_COST. Um bot que vendeu
      só o que tinha não gera parte de outro titular.
    violation: não se aplica
    source: [FR3.4, Q3, decisão do fundador D2 (2026-11-22), AC3.5.1, AC3.5.2, AC3.5.3, AC3.6.1]

  - id: BR4.5
    statement: "[fundador] O valor recebido e a taxa de uma venda são rateados pela quantidade vendida por cada participante."
    category: calculation
    applies_to: [ENT-208]
    trigger: evento OWN_SALE com mais de um participante, ou SALE_WITHOUT_BOT
    logic: >
      Depois de BR4.3 ou BR4.4, os pesos são as quantidades vendidas por participante. O recebido e a taxa são
      rateados separadamente pelo maior resto (BR4.1), com o desempate de BR4.2. Como as quantidades vendidas vêm do
      rateio da quantidade de antes da venda, a proporção é a do FR3.3.
    violation: a conferência (BR8.4) falha
    source: [FR3.3, FR3.4, Q2, stories.md ponto aberto 1 e 3, AC3.3.1, AC3.5.1, AC3.7.4]

  - id: BR4.6
    statement: A parte não executada de uma condicional não gera parte nenhuma.
    category: calculation
    applies_to: [ENT-208]
    trigger: condicional executada em parte
    logic: o evento usa só a quantidade, o recebido e a taxa do SWAP_FILL_OUT executado; nada é reservado ou rateado para o resto
    violation: não se aplica
    source: [FR3.3, AC3.4.2, AC3.4.4]

  - id: BR4.7
    statement: Cada parte de um bot que não é venda própria fica marcada com a origem.
    category: calculation
    applies_to: [ENT-208, ENT-209]
    trigger: parte de bot num evento rateado
    logic: >
      SALE_WITHOUT_BOT de liquidação: LIQUIDATION ("fechado por liquidação"). SALE_WITHOUT_BOT de condicional:
      CONDITIONAL ("fechado por condicional"). Excedente de OWN_SALE recebido por outro bot: SOLD_BY_OTHER_BOT
      ("vendido por outro bot"). Cada uma vira um ProratedShare do bot. A parte própria do vendedor não vira share.
    violation: não se aplica
    source: [FR3.3, FR3.4, AC3.3.3, AC3.4.3, AC3.5.1, AC3.6.1]

  - id: BR4.8
    statement: Um titular sem quantidade logo antes da venda não recebe parte.
    category: constraint
    applies_to: [ENT-208]
    trigger: rateio
    logic: só titulares com quantidade > 0 entram como participantes, inclusive na distribuição das sobras
    violation: não se aplica
    source: [FR3.3, AC3.3.4]

  # ---------------------------------------------------------------- BR5 parcela sem dono
  - id: BR5.1
    statement: "[fundador] O custo da parcela sem dono é o custo da carteira pelo ledger menos o custo dos bots, retirado pela regra da Q1."
    category: calculation
    applies_to: [ENT-205, ENT-206]
    trigger: parte de UNOWNED numa venda
    logic: >
      A posição de UNOWNED num token é a da carteira menos a soma das posições dos bots (quantidade e custo). Pela
      BR2.4, ela é exatamente a posição das compras sem bot resolvido. Quando UNOWNED vende s da quantidade registrada
      dele, o custo retirado segue BR2.2 sobre essa posição.
    violation: não se aplica
    source: [Q3, FR5.2, AC3.5.2]

  - id: BR5.2
    statement: A parte de UNOWNED vendida da quantidade registrada leva o motivo UNOWNED_EXCESS.
    category: calculation
    applies_to: [ENT-208, ENT-211]
    trigger: UNOWNED participa de uma venda com quantidade registrada (BR4.3, BR4.4)
    logic: a linha "Sem bot" recebe o recebido, a taxa e o realizado da parte, e o motivo UNOWNED_EXCESS
    violation: não se aplica
    source: [Q3, FR3.4, stories.md ponto aberto 5, AC3.5.2]

  - id: BR5.3
    statement: A quantidade vendida além das posições registradas vai para UNOWNED com custo zero e o motivo UNOWNED_NO_COST.
    category: calculation
    applies_to: [ENT-208, ENT-211]
    trigger: venda com quantidade maior que a soma das posições registradas do token na carteira
    logic: >
      A parte excedente tem custo retirado 0, recebe o recebido e a taxa pelo rateio (BR4.5), e o realizado dela é
      igual ao recebido. Ela não cria posição aberta.
    violation: não se aplica
    source: [Q3]

  - id: BR5.4
    statement: Um lançamento cuja ordem foi expurgada vai para a linha "Sem bot" com o motivo ORDER_RECORD_MISSING.
    category: calculation
    applies_to: [ENT-207, ENT-211]
    trigger: referência order:<id> sem ordem em C13
    logic: >
      Compra: titular UNOWNED (BR3.1). Venda: OWN_SALE com UNOWNED como vendedor (BR3.2), com o excedente por BR4.4.
      Taxa: na linha "Sem bot".
    violation: não se aplica
    source: [C13]

  - id: BR5.5
    statement: A linha "Sem bot" acumula todo o resultado do titular UNOWNED e os motivos que o produziram.
    category: calculation
    applies_to: [ENT-211]
    trigger: qualquer parte ou taxa de UNOWNED
    logic: >
      realized, fees e unrealized de UNOWNED seguem as mesmas regras de um bot (BR7). Os motivos formam um conjunto.
      A linha existe quando UNOWNED tem algo desde o início; sem nada, ela fica ausente.
    violation: a conferência (BR8.5) falha se houver valor sem motivo
    source: [FR3.4, FR5.3, C10, AC5.3.1]

  - id: BR5.6
    statement: O motivo SURPLUS_WITHOUT_OWNER do contrato vira UNOWNED_EXCESS, e entra UNOWNED_NO_COST.
    category: policy
    applies_to: [ENT-211]
    trigger: Code Generation de C10 e C4
    logic: >
      Os valores de reasons passam a ser UNOWNED_EXCESS, UNOWNED_NO_COST e ORDER_RECORD_MISSING. O alinhamento entra
      todo no mesmo commit:
        (1) C10: UnownedPnlLine.reasons e o comentário do tipo em pnl.api;
        (2) C4: o enum de unowned.reasons no openapi-guest.yaml;
        (3) ModePnlDtos: allowableValues do esquema;
        (4) o tipo gerado do web (schema.d.ts), regerado do OpenAPI, nunca escrito à mão;
        (5) as chaves de texto pnl.noBotReason.* do design-system-mapping.md, que a U3 consome:
            pnl.noBotReason.UNOWNED_EXCESS ("Motivo: excedente sem dono", no lugar de SURPLUS_WITHOUT_OWNER),
            pnl.noBotReason.UNOWNED_NO_COST (texto novo, proposto: "Motivo: venda sem custo registrado") e
            pnl.noBotReason.ORDER_RECORD_MISSING.
      O contract-summary.md (C4, C10) e o design-system-mapping.md não são editados neste estágio; este registro vale
      para a Code Generation da U2 e para o Functional Design da U3, que mapeia os três motivos. A U1 nunca produziu
      SURPLUS_WITHOUT_OWNER (unowned sempre nulo), então nenhuma resposta publicada muda.
    violation: o teste de contrato do OpenAPI falha
    source: [Q3, C10, C4, design-system-mapping.md, review-01 R-08]

  # ---------------------------------------------------------------- BR6 taxas
  - id: BR6.1
    statement: A taxa de uma compra ou de uma venda própria fica no bot da ordem, menos a parte do excedente.
    category: calculation
    applies_to: [ENT-207, ENT-210]
    trigger: taxa de evento BUY ou OWN_SALE
    logic: >
      BUY: toda a taxa no titular da compra. OWN_SALE: a taxa é rateada pela quantidade vendida por participante
      (BR4.5); sem excedente, ela fica toda no vendedor. A taxa nunca entra no custo nem no realizado.
    violation: não se aplica
    source: [FR3.9, Q5, stories.md ponto aberto 3, AC3.5.1, AC3.6.1]

  - id: BR6.2
    statement: A taxa de uma ordem que falhou na rede fica no bot que enviou a ordem.
    category: calculation
    applies_to: [ENT-207]
    trigger: evento FEE_ONLY (ordem FAILED com transação)
    logic: >
      SE a ordem tem bot ENTÃO a taxa vai inteira para esse bot; quantidade, custo e realizado não mudam. SE é uma
      liquidação que falhou ENTÃO a taxa é rateada (BR4.1) entre os titulares do token, pela quantidade que cada um
      tinha logo antes da tentativa, como parte de origem LIQUIDATION com quantidade e recebido zero. Sem nenhum
      titular, ela vai para "Sem bot" com o motivo UNOWNED_NO_COST. A base é a mesma nos dois modos de pagamento, as
      quantidades de logo antes da tentativa:
        - paga pela carteira do bot: o NETWORK_FEE entra na ordem de rede pela chave dele;
        - paga pela carteira principal (modo Assinada): não há lançamento, e a tentativa entra na ordem de rede pela
          assinatura da ordem. Com o slot gravado (BR1.4), a chave é (slot, assinatura). Sem ele, a tentativa herda o
          slot como na BR1.3, posicionada pelo settledAt da ordem entre os lançamentos da carteira em ordem de
          gravação.
    violation: não se aplica
    source: [FR3.9, AC3.7.1, review-01 R-07]

  - id: BR6.3
    statement: No modo Assinada, a taxa paga pela carteira principal entra no bot que decidiu a ordem.
    category: calculation
    applies_to: [ENT-204, ENT-207]
    trigger: ordem com feePayer MAIN_WALLET
    logic: >
      A taxa vem da ordem (feeLamports), porque ela não está no ledger da carteira do bot. Ela entra no bot da ordem
      pelas mesmas regras (BR6.1, BR6.2) e nunca em outro bot nem em "Sem bot". O instante dela é o instante do
      evento (BR7.6): o createdAt do swap, numa compra ou venda confirmada, e o settledAt da ordem, numa ordem que
      falhou. SE feePayer é BOT_WALLET ENTÃO a taxa vem do NETWORK_FEE do ledger com a referência da ordem, e nunca
      das duas fontes.
    violation: a conferência (BR8.1) falha se a taxa for contada duas vezes ou nenhuma
    source: [FR3.9, AC3.7.2, AC3.7.3]

  - id: BR6.4
    statement: A taxa de uma venda sem bot é rateada junto com o recebido.
    category: calculation
    applies_to: [ENT-208]
    trigger: evento SALE_WITHOUT_BOT
    logic: a taxa da venda (NETWORK_FEE da mesma referência, ou da ordem no modo Assinada) é rateada por BR4.5
    violation: não se aplica
    source: [FR3.3, FR3.9, AC3.3.1, AC3.3.2, AC3.4.1, AC3.7.4]

  - id: BR6.5
    statement: Só os lançamentos de negociação entram no P&L.
    category: constraint
    applies_to: [ENT-203]
    trigger: leitura do ledger
    logic: >
      Entram SWAP_FILL_IN, SWAP_FILL_OUT, NETWORK_FEE e RENT com referência order:<id> ou conditional:<id>. Ficam de
      fora DEPOSIT, WITHDRAWAL, as taxas de depósito e de saque (dep:, wd:), RESERVE, RESERVE_RELEASE e COMPENSATION,
      como no cálculo de posições de hoje.
    violation: não se aplica
    source: [FR3.9, ADR-001]

  - id: BR6.6
    statement: Um RENT fora de um evento BUY vai para o titular da ordem dele, na linha de taxas, nos dois lados.
    category: calculation
    applies_to: [ENT-207, ENT-210, ENT-211]
    trigger: RENT de negociação cuja referência não tem SWAP_FILL_IN (venda, ordem que falhou ou referência de condicional)
    logic: >
      O RENT de uma conta criada fora de uma compra não tem quantidade para receber custo. Ele fica com o titular do
      token da conta, que é o titular da ordem. SE a ordem tem bot ENTÃO entra nas taxas desse bot. SE a ordem não
      tem bot (liquidação ou condicional) ENTÃO entra nas taxas de "Sem bot" com o motivo UNOWNED_NO_COST. SE a ordem
      foi expurgada ENTÃO entra nas taxas de "Sem bot" com o motivo ORDER_RECORD_MISSING. O lado do modo (BR7.5)
      conta esse RENT nas taxas do modo, no instante do evento. Hoje o RealLedgerEntries só grava RENT numa compra
      confirmada; esta regra fecha o caso para a conferência nunca deixar um RENT de fora, e um teste cobre o caso.
    violation: a conferência (BR8.1) falha se o RENT for esquecido
    source: [FR3.10, FR5.3, review-01 R-09]

  # ---------------------------------------------------------------- BR7 resultado
  - id: BR7.1
    statement: "[fundador] O realizado é bruto: o recebido menos o custo retirado, sem descontar taxa."
    category: calculation
    applies_to: [ENT-208, ENT-210, ENT-211]
    trigger: cada parte de venda
    logic: realizado da parte = recebido da parte − custo retirado da parte (BR2.2); o realizado de uma linha é a soma das partes dela no período
    violation: não se aplica
    source: [Q5, FR3.2, AC3.2.2, AC3.3.1]

  - id: BR7.2
    statement: O não realizado é o valor das posições abertas de agora menos o custo delas.
    category: calculation
    applies_to: [ENT-206, ENT-210, ENT-211, ENT-212]
    trigger: cálculo do não realizado
    logic: >
      Para cada carteira e token com preço conhecido p: valor da carteira = chão(Q × p), com Q a quantidade aberta.
      Esse valor é rateado entre os titulares pelo maior resto (BR4.1), com peso igual à quantidade aberta de cada
      um. O não realizado de um titular é a soma de (valor rateado − custo) dos tokens dele. Assim a soma dos
      titulares é exatamente chão(Q × p) − custo da carteira.
    violation: não se aplica
    source: [FR3.5, FR3.10, FR5.3, AC3.2.4, AC3.8.4]

  - id: BR7.3
    statement: Preço desconhecido deixa só o não realizado desconhecido; o realizado, o custo e as taxas continuam exatos.
    category: calculation
    applies_to: [ENT-210, ENT-211, ENT-212]
    trigger: preço unknown de um token com posição aberta
    logic: >
      SE um titular tem posição aberta num token sem preço ENTÃO o não realizado dele é Unknown (PRICE_UNKNOWN, STALE
      ou NO_LIQUIDITY), nunca zero, e o total dele também. O total do modo segue a mesma regra. Realizado, custo e
      taxas não dependem do preço.
    violation: não se aplica
    source: [FR3.10, FR3.5, AC3.8.1, AC3.8.2, AC3.8.3, project.md]

  - id: BR7.4
    statement: "[fundador] O total de uma linha e do modo REAL é realizado + não realizado − taxas."
    category: calculation
    applies_to: [ENT-210, ENT-211, ENT-212]
    trigger: cálculo do total
    logic: >
      total = realized + unrealized − fees, igual ao PAPER. SE unrealized é Unknown ENTÃO total é Unknown. A nota
      "Algumas operações ainda não têm bot dono" e o rótulo "Taxas (já incluídas no realizado e no custo)" da U1 saem
      (BR10.4).
    violation: não se aplica
    source: [Q5, AC4.1.1, AC5.3.1, BR5.5 da U1]

  - id: BR7.5
    statement: O total do modo REAL é calculado do lado do ledger, nunca como soma das linhas.
    category: calculation
    applies_to: [ENT-212]
    trigger: modePnl
    logic: >
      realized do modo = soma do recebido dos SWAP_FILL_OUT de negociação com instante do evento no período − soma do
      custo retirado da carteira nesses eventos (BR2.4). fees do modo = soma dos NETWORK_FEE de negociação, das taxas
      do modo Assinada e dos RENT fora de compra (BR6.6), cada um pelo instante do seu evento (BR7.6). unrealized do modo = soma, por carteira e token, de chão(Q × p) − custo da
      carteira. É o mesmo padrão do bloco PAPER da U1 (total pelo ledger, sem somar as linhas).
    violation: não se aplica
    source: [FR5.3, Q6, ADR-001]

  - id: BR7.6
    statement: O realizado e as taxas contam só o período; o não realizado é sempre o de agora.
    category: calculation
    applies_to: [ENT-210, ENT-211, ENT-212]
    trigger: modePnl ou botPnl com um período
    logic: >
      Período All: tudo desde o início. Período Range [from, to), em UTC. Cada evento tem um único instante, usado
      por todas as partes dele (recebido, custo retirado, realizado, taxa e RENT) e pelos dois lados da conferência:
        - BUY, OWN_SALE e SALE_WITHOUT_BOT: o createdAt do lançamento principal (SWAP_FILL_IN ou SWAP_FILL_OUT),
          inclusive para a taxa do modo Assinada;
        - FEE_ONLY: o createdAt do NETWORK_FEE ou, sem ele (modo Assinada), o settledAt da ordem.
      Como os lançamentos de uma mesma transação podem ter createdAt diferentes, o instante do NETWORK_FEE e do RENT
      nunca decide sozinho. O cálculo sempre percorre o histórico inteiro, porque o custo precisa dele. O não
      realizado é o do fim do histórico, com o preço de agora, em qualquer período.
    violation: um instante diferente entre as partes e o lado do modo daria um falso MISMATCH (BR9.5)
    source: [FR7.2, AC7.2.1, AC5.4.1, AC5.4.2, review-01 R-05]

  # ---------------------------------------------------------------- BR8 conferência
  - id: BR8.1
    statement: "[fundador] Desde o início, a soma dos bots mais a linha \"Sem bot\" é igual ao modo pelo ledger, nas unidades base."
    category: validation
    applies_to: [ENT-213]
    trigger: todo modePnl e botPnl com período All
    logic: >
      Conferir por igualdade exata, sem tolerância: realized (soma das linhas) = realized do modo (BR7.5); fees =
      fees do modo; unrealized = unrealized do modo, quando os dois são conhecidos, ou os dois Unknown; total
      idem.
    violation: BR8.7
    source: [FR5.3, Q6, AC5.3.1, AC5.3.3]

  - id: BR8.2
    statement: Num período, a soma do realizado e das taxas dos bots mais "Sem bot" é igual à do modo pelos lançamentos do período; o não realizado não é conferido.
    category: validation
    applies_to: [ENT-213]
    trigger: modePnl ou botPnl com período Range
    logic: >
      realized e fees por igualdade exata, com os eventos do período. As linhas e o lado do modo usam o mesmo instante
      único do evento (BR7.6). Assim, uma taxa ou um RENT gravados do outro lado da fronteira nunca geram um falso
      MISMATCH (teste na BR9.5). O não realizado só é conferido em All.
    violation: BR8.7
    source: [FR5.3, Q6, AC5.4.1, AC5.4.2, AC5.4.3]

  - id: BR8.3
    statement: Desde o início, o custo e a quantidade se conservam.
    category: validation
    applies_to: [ENT-206, ENT-213]
    trigger: período All
    logic: >
      Por carteira e token: soma do custo das compras (BR2.1) = soma do custo retirado de todas as partes + custo
      aberto de todos os titulares. Soma da quantidade comprada = soma da quantidade vendida da parte registrada +
      quantidade aberta. As partes sem custo de BR5.3 ficam fora da segunda soma.
    violation: BR8.7 (COST_CONSERVATION ou QUANTITY_CONSERVATION)
    source: [FR5.3, NFR2, Q6]

  - id: BR8.4
    statement: Em cada evento, as partes somam o evento.
    category: validation
    applies_to: [ENT-208, ENT-213]
    trigger: cada evento rateado
    logic: soma das quantidades = vendido; soma dos recebidos = recebido; soma das taxas = taxa
    violation: BR8.7 (EVENT_SUMS)
    source: [FR3.7, AC3.3.1, AC3.7.4]

  - id: BR8.5
    statement: A linha "Sem bot" diferente de zero precisa de pelo menos um motivo.
    category: validation
    applies_to: [ENT-211, ENT-213]
    trigger: conferência
    logic: SE realized, fees ou unrealized de "Sem bot" é diferente de zero, ou ela tem posição aberta, E reasons está vazio ENTÃO a conferência falha
    violation: BR8.7 (UNOWNED_WITHOUT_REASON)
    source: [FR5.3, AC5.3.4]

  - id: BR8.6
    statement: A conferência de uma pessoa lê só o ledger, as ordens e os bots daquela pessoa.
    category: authorization
    applies_to: [ENT-213]
    trigger: conferência
    logic: >
      O tenant e a pessoa vêm do token. As carteiras, as ordens (C13), os lançamentos (C12) e os bots (C8) são lidos
      filtrados por tenant e pessoa. Nada de outra pessoa entra no cálculo nem no evento de auditoria.
    violation: 404 para o bot de outra pessoa (BR3.4 da U1); nenhum dado sai
    source: [NFR3, AC5.3.5, project.md]

  - id: BR8.7
    statement: "[fundador] Uma diferença com o ledger é um defeito: o bloco REAL fica UNAVAILABLE, sem números parciais."
    category: policy
    applies_to: [ENT-212, ENT-213, ENT-214]
    trigger: MISMATCH em BR8.1 a BR8.5 ou BR3.4
    logic: >
      O bloco REAL sai com status UNAVAILABLE e cause=RECONCILIATION_MISMATCH. Nenhuma linha, share ou total sai, nem
      em botPnl. O evento REAL_PNL_RECONCILIATION_MISMATCH é auditado com as verificações que falharam e as
      diferenças em lamports, e a métrica pnl_real_reconciliation_mismatches_total (alinhamento A3) é incrementada. O bloco PAPER sai
      normalmente (BR5.3 da U1). O GET /api/v1/real/pnl responde o mesmo erro técnico da indisponibilidade.
    violation: não se aplica
    source: [Q6, FR5.3, AC5.3.3, AC5.3.4]

  # ---------------------------------------------------------------- BR9 testes
  - id: BR9.1
    statement: Um teste de paridade confere que a carteira é a soma dos bots mais a parcela sem dono e que o P&L e a barreira usam os mesmos números.
    category: validation
    applies_to: [ENT-206]
    trigger: CI; escrito antes do código
    logic: >
      Sobre o mesmo ledger e a mesma chave (BR1.1), depois de cada evento e no fim, para cada carteira e token. Como
      o custo da carteira é a soma dos titulares por construção (BR2.4), o teste usa oráculos independentes, escritos
      no próprio teste, sem o RealCostBasis nem o RealProration:
        (1) Quantidade, oráculo bruto: percorrer os SWAP_FILL_IN e SWAP_FILL_OUT crus do ledger na ordem de rede, com
            quantidade corrente += IN e −= min(OUT, corrente). O resultado tem de ser igual à quantidade do caminho só
            de quantidade (BR2.5), à soma das quantidades dos titulares e, quando não há COMPENSATION de token, ao
            tokenBalances do ledger.
        (2) Custo, oráculo de conservação: a soma bruta das pernas SOL dos SWAP_FILL_IN e dos RENT de compra do
            ledger tem de ser igual à soma do custo retirado de todas as partes mais o custo aberto da soma dos
            titulares.
        (3) Custo por titular, oráculo de recálculo: para cada bot, refazer o custo médio só com as compras dele e as
            quantidades vendidas atribuídas a ele, com a regra da Q1 escrita de novo no teste. O resultado tem de ser
            igual ao do RealCostBasis.
        (4) O P&L e a barreira usam os mesmos números: positions() e view() trazem as quantidades de (1), e a
            leitura de custo traz a soma dos titulares.
      Inclui os cenários de AC5.2.1 e AC5.2.2 e vendas próprias de bots com custos médios diferentes.
    violation: o build falha
    source: [ADR-002, C11, Q1, Q4, AC5.2.1, AC5.2.2, review-01 R-03]

  - id: BR9.2
    statement: Um teste de propriedade com sequências aleatórias e semente fixa confere a conferência e o determinismo.
    category: validation
    applies_to: [ENT-208, ENT-213]
    trigger: CI; escrito antes do código
    logic: >
      Gerar sequências de compras de bots, vendas próprias com e sem excedente, liquidações, condicionais inteiras e
      parciais, ordens que falham, taxas no modo Assinada, operações no mesmo slot, linhas SLOT_MISSING, compras sem
      bot, vendas além do registrado e bots removidos. Para cada uma: BR8.1 a BR8.5 dão MATCH em All e em períodos
      aleatórios; BR9.1 vale; duas execuções dão o mesmo resultado; gravar as linhas com slot em outra ordem dá o
      mesmo resultado; nenhuma quantidade ou custo fica negativo; posição zerada tem custo 0. A semente fica no teste
      e aparece na mensagem de falha.
    violation: o build falha
    source: [Q6, FR5.3, NFR2, AC3.6.3]

  - id: BR9.3
    statement: O teste de regressão da venda condicional é escrito antes do código e falha com o cálculo anterior.
    category: validation
    applies_to: [ENT-207]
    trigger: CI
    logic: >
      O cenário de AC5.1.1 roda contra o cálculo por ordens da U1 (RealPnlCalculator) e falha, porque a venda
      conditional:<id> não é uma ordem; com o cálculo novo, lido do ledger, passa.
    violation: o build falha
    source: [FR5.1, AC5.1.1, AC5.1.2, AC5.1.3]

  - id: BR9.4
    statement: Cada exemplo das histórias e cada cenário do FR5.4 é um teste escrito antes do código, com o resultado em unidades base.
    category: validation
    applies_to: [ENT-208, ENT-210]
    trigger: CI
    logic: >
      Os exemplos de AC3.2.1 a AC3.9.1, AC5.1.1, AC5.3.1 e AC5.4.1 a AC5.4.2 entram como estão (seção "Exemplos
      trabalhados"). Os cenários do FR5.4 sem exemplo próprio (venda no mesmo slot com SLOT_MISSING, excedente sem
      custo) usam os exemplos ilustrativos desta seção.
    violation: o build falha
    source: [FR5.4, NFR2, team.md]

  - id: BR9.5
    statement: Um teste confere a fronteira do período sem falso MISMATCH.
    category: validation
    applies_to: [ENT-207, ENT-213]
    trigger: CI; escrito antes do código
    logic: >
      Uma venda com taxa e RENT, cujo SWAP_FILL_OUT tem createdAt 1 ms antes de `to` e cujo NETWORK_FEE e RENT têm
      createdAt 1 ms depois, no mesmo evento. No período [from, to), a venda, a taxa e o RENT entram inteiros dos dois
      lados, e a conferência dá MATCH. No período seguinte, nada desse evento entra. O mesmo cenário vale com `from`, e
      com uma taxa do modo Assinada.
    violation: o build falha
    source: [BR7.6, FR5.3, review-01 R-05]

  # ---------------------------------------------------------------- BR10 leitura e entrega
  - id: BR10.1
    statement: O P&L REAL de uma pessoa é calculado numa passada só, em fluxo, sem carregar o histórico numa lista.
    category: policy
    applies_to: [ENT-203, ENT-204]
    trigger: modePnl e botPnl
    logic: >
      Ler as ordens da pessoa em fluxo (C13) para um índice compacto por id; depois ler os lançamentos de negociação
      das carteiras da pessoa em fluxo, na ordem de rede (C12), e aplicar cada evento. Os preços são lidos uma vez,
      no fim, para os tokens com posição aberta. botPnl faz o mesmo cálculo da pessoa e devolve só a linha do bot,
      porque o rateio depende de todos os titulares. O índice e o p95 ficam com o NFR Design (PQ-10).
    violation: não se aplica
    source: [NFR1, Q7, ADR-004, C12, C13, AC3.11.1]

  - id: BR10.2
    statement: O cálculo é só de leitura e nunca bloqueia a execução de ordens.
    category: policy
    applies_to: [ENT-203]
    trigger: modePnl, botPnl, conferência
    logic: nenhuma trava de escrita; nunca participa da transação do ciclo nem da execução; nenhuma escrita além do evento de auditoria de BR8.7
    violation: não se aplica
    source: [NFR1, AC3.11.3, C10]

  - id: BR10.3
    statement: A U2 troca a implementação de C10 e aposenta o cálculo por ordens.
    category: policy
    applies_to: [ENT-210, ENT-211, ENT-212]
    trigger: Code Generation
    logic: >
      A implementação da U1 (RealBotPnlQueryV1 com RealPnlCalculator) sai. A nova aceita os períodos All e Range,
      preenche shares e unowned e calcula o total por BR7.5. O GET /api/v1/real/pnl passa a ser o total do
      RealBotPnl: a forma da resposta continua, o realizado passa a bruto e as posições vêm de BR2.4.
    violation: não se aplica
    source: [ADR-001, C10, unit-of-work.md U2]

  - id: BR10.4
    statement: A nota fixa e o rótulo das taxas do bloco REAL da U1 saem na mesma entrega da U2.
    category: policy
    applies_to: [ENT-210, ENT-212]
    trigger: Code Generation da U2
    logic: >
      No mesmo commit em que o realizado passa a bruto: o rótulo "Taxas (já incluídas no realizado e no custo)" vira
      "Taxas", como no PAPER; a nota "Algumas operações ainda não têm bot dono; o rateio chega na próxima etapa." sai
      do bloco REAL. Sem isso, a tela diria que as taxas já estão no realizado quando não estão mais.
    violation: não se aplica
    source: [Q5, frontend-components.md da U1]

  - id: BR10.5
    statement: Os tipos novos de resultado REAL entram na lista fechada da regra NEVER_SUM_MODES no mesmo commit.
    category: constraint
    applies_to: [ENT-208, ENT-209, ENT-213]
    trigger: criação de um tipo de resultado REAL
    logic: nenhum tipo desta Unit aparece numa classe que dependa também de um tipo de resultado PAPER, fora do ponto que monta os dois blocos
    violation: a regra de arquitetura falha
    source: [ADR-003, FR4.1, NFR3, project.md]
```

## Resumo das regras

| ID | Regra | Categoria | Fonte |
|---|---|---|---|
| BR1.1 | Ordem de rede pela chave comum | constraint | FR3.8, Q4 |
| BR1.2 | Mesmo slot: assinatura menor primeiro | constraint | FR3.8 |
| BR1.3 | Linha sem slot herda o slot anterior | calculation | ADR-011, PQ-4 |
| BR1.4 | Slot gravado na confirmação, sem sobrescrever | policy | C13, C14 |
| BR1.5 | Sem preenchimento; SLOT_MISSING contado | policy | PQ-4 |
| BR1.6 | Uma chave para todos os leitores | constraint | ADR-011 |
| BR1.7 | ADR-011 alinhado à tabela lateral | policy | PQ-2, PQ-4 |
| BR2.1 | Custo da compra sem a taxa, com o RENT | calculation | FR3.10, Q5 |
| BR2.2 | Custo retirado para cima; a última venda leva o resto | calculation | Q1 **[fundador]** |
| BR2.3 | Titular nunca vende mais do que tem | constraint | FR3.4 |
| BR2.4 | Custo da carteira = soma dos titulares, um só algoritmo | constraint | Q1, D1 **[fundador]** |
| BR2.5 | Caminho só de quantidade, sem atribuição | constraint | D1 **[fundador]** |
| BR2.6 | Custo restante até 1 lamport abaixo, por titular e venda parcial | constraint | Q1 |
| BR2.7 | Leitura de atribuição com orçamento e falha | policy | D1 **[fundador]** |
| BR2.8 | Alinhamento de C11 e do ADR-002 | policy | D1 |
| BR3.1 | Titular da compra: o bot da ordem | calculation | FR3.2 |
| BR3.2 | Classificação das vendas | calculation | FR3.3, FR3.4 |
| BR3.3 | Pausado ou removido continua dono | policy | FR3.11 |
| BR3.4 | Bot desconhecido: só o bloco de P&L REAL indisponível | validation | D3 **[fundador]** |
| BR4.1 | Maior resto em todo rateio | calculation | FR3.7, Q2 **[fundador]** |
| BR4.2 | Desempate: criação, id; sem dono por último | calculation | FR3.7 |
| BR4.3 | Venda sem bot rateada pela quantidade de antes | calculation | FR3.3 |
| BR4.4 | Venda de bot: a própria, depois o excedente para todos os outros titulares | calculation | FR3.4, D2 **[fundador]** |
| BR4.5 | Recebido e taxa rateados pela quantidade vendida | calculation | Q2 **[fundador]** |
| BR4.6 | Parte não executada não gera parte | calculation | FR3.3 |
| BR4.7 | Origem de cada parte | calculation | FR3.3, FR3.4 |
| BR4.8 | Sem quantidade, sem parte | constraint | FR3.3 |
| BR5.1 | Custo sem dono = carteira − bots, pela Q1 | calculation | Q3 **[fundador]** |
| BR5.2 | UNOWNED_EXCESS | calculation | Q3 |
| BR5.3 | UNOWNED_NO_COST | calculation | Q3 |
| BR5.4 | ORDER_RECORD_MISSING | calculation | C13 |
| BR5.5 | Linha "Sem bot" | calculation | FR5.3 |
| BR5.6 | Motivos renomeados no contrato | policy | Q3 |
| BR6.1 | Taxa de compra e venda própria | calculation | FR3.9 |
| BR6.2 | Taxa de ordem que falhou | calculation | FR3.9 |
| BR6.3 | Taxa do modo Assinada | calculation | FR3.9 |
| BR6.4 | Taxa da venda sem bot rateada | calculation | FR3.9 |
| BR6.5 | Só lançamentos de negociação | constraint | FR3.9 |
| BR6.6 | RENT fora de compra: taxas do titular da ordem | calculation | FR3.10 |
| BR7.1 | Realizado bruto | calculation | Q5 **[fundador]** |
| BR7.2 | Não realizado com valor rateado | calculation | FR3.5, FR5.3 |
| BR7.3 | Preço desconhecido | calculation | FR3.10 |
| BR7.4 | Total = realizado + não realizado − taxas | calculation | Q5 **[fundador]** |
| BR7.5 | Total do modo pelo ledger | calculation | FR5.3 |
| BR7.6 | Período: realizado e taxas no período, não realizado de agora | calculation | FR7.2 |
| BR8.1 | Conferência desde o início | validation | Q6 **[fundador]** |
| BR8.2 | Conferência por período | validation | Q6 |
| BR8.3 | Conservação de custo e quantidade | validation | FR5.3 |
| BR8.4 | Partes somam o evento | validation | FR3.7 |
| BR8.5 | "Sem bot" com motivo | validation | FR5.3 |
| BR8.6 | Conferência por tenant | authorization | NFR3 |
| BR8.7 | Diferença: bloco UNAVAILABLE | policy | Q6 **[fundador]** |
| BR9.1 | Teste de paridade | validation | ADR-002 |
| BR9.2 | Teste de propriedade | validation | Q6 |
| BR9.3 | Regressão da condicional | validation | FR5.1 |
| BR9.4 | Exemplos como testes | validation | FR5.4 |
| BR9.5 | Teste da fronteira do período | validation | FR5.3 |
| BR10.1 | Uma passada em fluxo | policy | NFR1, Q7 |
| BR10.2 | Só leitura | policy | NFR1 |
| BR10.3 | Troca da implementação de C10 | policy | ADR-001 |
| BR10.4 | Nota e rótulo da U1 saem | policy | Q5 |
| BR10.5 | NEVER_SUM_MODES | constraint | ADR-003 |

São 62 regras.

## O maior resto em pseudocódigo

```text
rateio(V, participantes):            # participantes com peso > 0, já na ordem de desempate (BR4.2)
  W = soma dos pesos
  para cada p: parte[p] = chão(V * peso[p] / W); resto[p] = (V * peso[p]) mod W
  sobra = V - soma(parte)
  ordenar participantes por resto decrescente; empate mantém a ordem de desempate
  dar 1 unidade aos 'sobra' primeiros
  devolver parte                      # soma(parte) == V sempre
```

## Exemplos trabalhados

Os bots seguem a convenção de `stories.md`: A foi criado em 2026-11-01T10:00Z; B e C, em 2026-11-02T10:00Z, com o id de B menor que o de C. O desempate (BR4.2) fica A, B, C e, por último, a parcela sem dono. Todos os valores são lamports, menos as quantidades, que são `XYZ` em unidades base. Cada exemplo reproduz o critério de aceite exatamente.

### AC3.2.1 a AC3.2.4 — custo médio do próprio bot (BR2.1, BR2.2, BR3.1, BR7.1, BR7.2)

- A compra 100 por 100.000.000 e 100 por 300.000.000: A tem 200 e custo 400.000.000 (BR2.1).
- A vende 50 por 150.000.000. Como 50 < 200, o custo retirado é teto(400.000.000 × 50 ÷ 200) = 100.000.000, exato. O realizado é 150.000.000 − 100.000.000 = +50.000.000 (BR7.1). A fica com 150 e custo 300.000.000.
- B compra 100 por 500.000.000. Ele é outro titular (BR3.1), e nada dele entra na posição de A.
- Preço 2.500.000. A carteira tem 250 (A 150, B 100). O valor da carteira é 625.000.000, e a parte de A pelo maior resto é 625.000.000 × 150 ÷ 250 = 375.000.000, sem sobra. O não realizado de A é 375.000.000 − 300.000.000 = +75.000.000 (BR7.2).

### AC3.3.1 — liquidação com dois bots (BR4.3, BR4.5, BR6.4, BR7.1)

- Antes: A 100 (200.000.000) e B 300 (900.000.000). Vendem 400 por 1.200.000.000, com taxa de 10.000.
- Quantidades: A 100 e B 300, todas. Recebido: A 300.000.000 e B 900.000.000. Taxa: A 2.500 e B 7.500.
- Realizado: A 300.000.000 − 200.000.000 = +100.000.000; B 900.000.000 − 900.000.000 = 0. A soma, +100.000.000, é 1.200.000.000 − 1.100.000.000.

### AC3.3.2 — três bots, com resto (BR4.1, BR4.2, BR4.5)

- Antes: A 50 (400.000.000), B 30 (210.000.000), C 20 (160.000.000). Vendem 100 por 777.777.777, com taxa de 7.000.
- Recebido pelo maior resto: as partes inteiras são A 388.888.888 (resto 50 de 100), B 233.333.333 (resto 10) e C 155.555.555 (resto 40), que somam 777.777.776. O lamport que sobra vai para A, que tem o maior resto: A 388.888.889, B 233.333.333, C 155.555.555.
- Taxa: A 3.500, B 2.100 e C 1.400, sem sobra.
- Realizado: A −11.111.111, B +23.333.333 e C −4.444.445. A soma, +7.777.777, é 777.777.777 − 770.000.000.

### AC3.4.1 — condicional inteira, com empate (BR4.1, BR4.2)

- Antes: A, B e C com 100 cada, a custos de 300.000.000, 450.000.000 e 600.000.000. A condicional vende 300 por 1.000.000.000, com taxa de 5.000.
- Recebido: cada um tem 333.333.333 com o mesmo resto (1 de 3). Sobra 1, que vai para A pelo desempate: A 333.333.334, B 333.333.333, C 333.333.333.
- Taxa: cada um tem 1.666 com o mesmo resto (2 de 3). Sobram 2: A, depois B (mesma data de C, id menor). Fica A 1.667, B 1.667, C 1.666.
- Realizado: A +33.333.334, B −116.666.667, C −266.666.667. A soma, −350.000.000, é 1.000.000.000 − 1.350.000.000. Cada parte tem a origem CONDITIONAL (BR4.7).

### AC3.4.2 — condicional em parte (BR4.3, BR4.6, BR2.2)

- Antes: A 600 (1.200.000.000) e B 400 (1.000.000.000). Executa 500 de 1.000, por 1.500.000.000, com taxa de 5.000.
- Quantidade (BR4.3, peso 600 : 400): A 300 e B 200. Recebido (BR4.5, peso 300 : 200): A 900.000.000 e B 600.000.000. Taxa: A 3.000 e B 2.000.
- Custo retirado (BR2.2): A teto(1.200.000.000 × 300 ÷ 600) = 600.000.000; B teto(1.000.000.000 × 200 ÷ 400) = 500.000.000.
- Realizado: A +300.000.000 e B +100.000.000, soma +400.000.000. Depois: A 300 (600.000.000) e B 200 (500.000.000). As 500 não executadas não geram nada (BR4.6).

### AC3.5.1 — excedente com outros donos (BR4.4, BR4.5, BR6.1)

- A 100 (100.000.000), B 200 (300.000.000), C 100 (100.000.000). O ciclo de A vende 400 por 800.000.000, com taxa de 4.000.
- A vende as próprias 100. O excedente de 300 é rateado entre B e C (200 : 100): B 200 e C 100.
- Recebido (100 : 200 : 100): A 200.000.000, B 400.000.000, C 200.000.000. Taxa: A 1.000, B 2.000, C 1.000.
- Realizado: A +100.000.000, B +100.000.000, C +100.000.000. A soma, +300.000.000, é 800.000.000 − 500.000.000. As partes de B e C têm a origem SOLD_BY_OTHER_BOT.

### AC3.5.2 — excedente sem outro dono (BR4.4, BR5.1, BR5.2)

- A comprou 100 por 100.000.000. As outras 50 vieram de uma compra sem bot resolvido, de titular UNOWNED. O custo das 150 pelo ledger é 175.000.000, então a posição de UNOWNED é 50 com custo 175.000.000 − 100.000.000 = 75.000.000 (BR5.1).
- O ciclo de A vende 150 por 300.000.000, com taxa de 3.000. A vende as próprias 100. Não há outro bot, então o excedente de 50 vai para UNOWNED, da quantidade registrada dele (UNOWNED_EXCESS).
- Recebido (100 : 50): A 200.000.000 e "Sem bot" 100.000.000. Taxa: A 2.000 e "Sem bot" 1.000.
- Realizado: A +100.000.000. "Sem bot": 100.000.000 − 75.000.000 = +25.000.000, porque vende toda a posição dele. A soma, +125.000.000, é 300.000.000 − 175.000.000.

### AC3.5.3 — venda só do próprio (BR4.4)

Se A vende no máximo o que tem, o excedente é 0, e nenhuma parte SOLD_BY_OTHER_BOT ou sem dono aparece.

### AC3.6.1 a AC3.6.3 — mesmo slot (BR1.1, BR1.2, BR4.4, BR6.1)

- A tem 100 (100.000.000). No mesmo slot, a compra de B (100 por 300.000.000, taxa de 5.000) tem a assinatura menor que a da venda do ciclo de A (200 por 400.000.000, taxa de 5.000). A compra vem primeiro (BR1.2).
- Na venda: A vende as próprias 100, e o excedente de 100 vai para B, o único outro bot.
- Recebido: A 200.000.000 e B 200.000.000 (SOLD_BY_OTHER_BOT). Taxa: 2.500 cada.
- Realizado: A +100.000.000 e B 200.000.000 − 300.000.000 = −100.000.000, soma 0. As taxas de B são 5.000 (compra) + 2.500 (venda) = 7.500, e as taxas somam 10.000.
- AC3.6.2: gravar a venda antes da compra não muda a chave, que não depende da ordem de gravação quando as duas têm slot gravado. AC3.6.3: o cálculo é uma função da sequência, então duas execuções dão o mesmo resultado.

### AC3.7.1 a AC3.7.4 — taxas (BR6.1 a BR6.4)

- A compra de A falha na rede com taxa de 5.000: as taxas de A sobem 5.000; quantidade, custo e realizado não mudam (BR6.2).
- No modo Assinada, B compra 100 por 200.000.000, e a carteira principal paga 5.000. B fica com 100 e custo 200.000.000, e as taxas de B sobem 5.000, lidas da ordem (BR6.3). Ninguém mais recebe essa taxa.
- Nas vendas de AC3.3.1, AC3.3.2 e AC3.4.1, a taxa é rateada junto com o recebido, e as partes somam a taxa (BR6.4, BR8.4).

### AC3.8.1 a AC3.8.4 — preço desconhecido (BR7.3)

- A tem 100 com custo 200.000.000, realizado +50.000.000 e taxas de 10.000, e o preço é `unknown`. O realizado, o custo e as taxas continuam exatos. O não realizado e o total de A e do modo ficam Unknown, nunca zero.
- Com o preço de 2.500.000, o não realizado de A é 250.000.000 − 200.000.000 = +50.000.000.

### AC3.9.1 — bot removido (BR3.3)

- A 100 (100.000.000) e C 100 (120.000.000), com C removido. A liquidação vende 200 por 300.000.000, com taxa de 2.000.
- A recebe 150.000.000, taxa 1.000, realizado +50.000.000. C recebe 150.000.000, taxa 1.000, realizado +30.000.000. A soma, +80.000.000, é 300.000.000 − 220.000.000. A linha de C sai com o estado REMOVED.

### AC5.1.1 — condicional no P&L REAL (BR3.2, BR7.1, BR9.3)

- 100 com custo 100.000.000. A condicional vende as 100 por 180.000.000, com taxa de 5.000.
- O realizado inclui 180.000.000 − 100.000.000 = +80.000.000, e as taxas incluem 5.000. A venda é lida do ledger pela referência `conditional:<id>`.

### AC5.3.1 — conferência desde o início (BR7.4, BR8.1)

- As linhas: A (+100.000.000, +20.000.000, 5.000), B (−30.000.000, −4.000.000, 2.000) e "Sem bot" (+25.000.000, 0, 1.000, UNOWNED_EXCESS).
- O modo pelo ledger: realizado +95.000.000, não realizado +16.000.000 e taxas 8.000. É igual à soma das linhas.
- Os totais: A 100.000.000 + 20.000.000 − 5.000 = +119.995.000; B −30.000.000 − 4.000.000 − 2.000 = −34.002.000; "Sem bot" +24.999.000. A soma, +110.992.000, é 95.000.000 + 16.000.000 − 8.000.

### AC5.4.1 e AC5.4.2 — período (BR7.6, BR8.2)

- O período é [2026-11-10T00:00Z, 2026-11-17T00:00Z). As vendas de 11-12, 11-14 e 11-15 entram; a de 11-05 fica de fora.
- A conta +100.000.000, B +30.000.000, C −10.000.000 e "Sem bot" +5.000.000. A soma, +125.000.000, é igual ao realizado do modo no período.
- Desde o início, a soma é +165.000.000: A +140.000.000, B +30.000.000, C −10.000.000 e "Sem bot" +5.000.000.

### Exemplos ilustrativos (não vêm das histórias)

- **Q1, custo não divisível (BR2.2).** Um titular tem 3 unidades com custo de 10. Ele vende 1: retira teto(10 ÷ 3) = 4 e fica com 2 unidades e custo 6. Vende 1: retira teto(6 ÷ 2) = 3 e fica com 1 e custo 3. Vende a última: retira 3, todo o resto. O custo retirado somado é 10.
- **Q2, quantidade de token (BR4.3).** A tem 2 e B tem 1. Uma condicional executa 2. As partes inteiras são A chão(4 ÷ 3) = 1 (resto 1) e B chão(2 ÷ 3) = 0 (resto 2). A sobra de 1 vai para B, que tem o maior resto: A 1 e B 1. O recebido e a taxa são rateados 1 : 1 (BR4.5).
- **Q3, venda sem custo (BR5.3).** A carteira tem só A com 10. O ciclo de A vende 12 por 1.200: A vende 10, e 2 vão para UNOWNED sem custo (UNOWNED_NO_COST). O recebido fica A 1.000 e "Sem bot" 200, com realizado de +200 para "Sem bot".
- **D2, excedente com outro bot e parcela sem dono (BR4.4).** A tem 100, B tem 50 e UNOWNED tem 50 registradas, com custo de 60. O ciclo de A vende 150 por 1.500. A vende as próprias 100. O excedente de 50 é rateado entre B e UNOWNED (50 : 50): B 25 e UNOWNED 25. O recebido fica A 1.000, B 250 e "Sem bot" 250. O custo retirado de UNOWNED é teto(60 × 25 ÷ 50) = 30, e o realizado de "Sem bot" é +220 (UNOWNED_EXCESS).
- **Mesmo slot sem slot gravado (BR1.3).** Duas linhas antigas, SLOT_MISSING, gravadas depois de uma linha com slot 900 herdam o slot 900, ficam depois das linhas gravadas desse slot e seguem a ordem de gravação.

## Sources

- `functional-design-questions.md` desta Unit: Q1 a Q7 e o resumo confirmado.
- `reviews/review-01.md` (R-01 a R-09) e as decisões do fundador de 2026-11-22: D1 (soma dos titulares com o caminho só de quantidade), D2 (excedente entre todos os outros titulares) e D3 (UNKNOWN_BOT só no bloco de P&L).
- `inception/refined-mockups/design-system-mapping.md`: a chave `pnl.noBotReason.SURPLUS_WITHOUT_OWNER`.
- Código lido depois da revisão: `RealGateInputsReader` (dependências LEDGER, WALLET e MARKET), `RealCycle`, `LiquidationJob` e `0016-real-bot-wallet.sql` (`real_ledger_entry` sem coluna de sequência).
- `inception/requirements-analysis/requirements.md`: FR3.2 a FR3.11, FR4.1, FR5.1 a FR5.4, FR7.2, NFR1 a NFR3.
- `inception/user-stories/stories.md`: convenções dos exemplos, US3.2 a US3.9, US3.11, US5.1 a US5.4, US7.2 e os pontos abertos 1 a 5.
- `inception/units-generation/unit-of-work.md` (U2 e o ponto de controle interno) e `unit-of-work-story-map.md`.
- `inception/domain-design/decisions.md`: ADR-001, ADR-002, ADR-003, ADR-004, ADR-007, ADR-011.
- `inception/contract-design/contract-summary.md`: C4, C8, C10 a C14, PQ-2, PQ-4, PQ-10.
- `construction/u1-mode-skeleton/functional-design/rules.md` (BR4.1, BR5.3, BR5.5 e a nota de C10) e `frontend-components.md` (nota e rótulo do bloco REAL).
- `aidlc/spaces/default/memory/project.md` e `team.md`.
- Código lido: `RealPnlCalculator` (`ceilShare` para cima; custo da compra com a taxa e o RENT; realizado líquido), `ledger.domain.RealPositions` (para baixo; só a perna SOL; ordem de gravação), `JdbcRealLedger.positions`, `RealLedgerEntries` e `RealOrderLedger` (lançamentos da ordem com a assinatura; no modo Assinada, a taxa fica só na ordem), `FillPosting` (`conditional:<id>`, com a assinatura do fill), `RealBotPnlQueryV1`, `PaperModeBlockReader`, `UnownedPnlLine` e o `openapi-guest.yaml` (`SURPLUS_WITHOUT_OWNER`, `ORDER_RECORD_MISSING`).

## Assumptions & Open Questions

1. **Decidido (fundador, 2026-11-22, D1).** O custo da carteira é a soma dos custos dos titulares (BR2.4), com a condição de um caminho só de quantidade que nunca depende da atribuição (BR2.5). Também com a leitura de atribuição separada, com orçamento e falha definidos (BR2.7), e com o alinhamento de C11 e do ADR-002 (BR2.8). O motivo está na BR2.4: um custo médio da carteira inteira diverge da soma dos bots quando há venda própria. Exemplo: A tem 100 por 100.000.000, B tem 100 por 300.000.000, e A vende as próprias 100. O custo médio da carteira retiraria 200.000.000, e A retira 100.000.000.
2. **Decidido (fundador, 2026-11-22, D2).** O excedente da venda de um bot é rateado entre todos os outros titulares, inclusive a parcela sem dono, como na venda sem bot (BR4.4).
3. **Decidido (fundador, 2026-11-22, D3).** UNKNOWN_BOT deixa indisponível só o bloco de P&L REAL, com auditoria, e nunca bloqueia venda, liquidação nem a barreira (BR3.4).
4. **Resolvido pela BR2.6 e pela BR2.8.** O texto de C11 e do ADR-002 ("o custo restante nunca fica menor que o real") dá lugar ao arredondamento da Q1. A barreira de compra não depende do custo.
5. [assumption] **RENT no custo (BR2.1) e fora de compra (BR6.6).** O cálculo de hoje do P&L já soma o RENT ao custo da compra; o `RealPositions` não soma. Esta regra mantém o RENT no custo do titular da compra, como "lamports efetivamente pagos" (FR3.10). Os exemplos das histórias não têm RENT, então nenhum resultado muda. A confirmar no gate.
6. [assumption] **Taxa de uma liquidação que falhou (BR6.2).** O FR3.9 só fala da ordem de um bot. A taxa é rateada entre os titulares do token, pelas quantidades de logo antes da tentativa, a mesma base nos dois modos de pagamento. A confirmar no gate.
7. [assumption] **Orçamentos da BR2.7.** Os 250 ms (barreira e RealCycle) e os 500 ms (P&L, dentro do prazo do bloco de 800 ms, D-NFR1; alinhamento A1) são valores iniciais. O NFR Design da U2 mede a latência sob o lock da carteira; esse item é obrigatório. Ele só pode baixar esses valores sem o fundador.
8. [assumption] **Texto da chave `pnl.noBotReason.UNOWNED_NO_COST` (BR5.6).** "Motivo: venda sem custo registrado" é uma proposta; a U3 fixa o texto.
9. [assumption] **Período.** O período conta pelo instante único do evento (BR7.6), não pelo slot. O FR5.3 pede só o realizado no período; a BR8.2 confere também as taxas, porque elas também são exatas.
10. [assumption] **O que não é desta Unit.** As pendências de texto da U1 (o `performance-design.md`, o NFR8.1 e o BR1.9) são da U1 e não mudam aqui.
