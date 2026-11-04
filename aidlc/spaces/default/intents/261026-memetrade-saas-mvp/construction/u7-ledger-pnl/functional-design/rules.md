# Regras de negócio — U7 Ledger e P&L

Regras do PaperTradingLedger e do PortfolioPnL. As fontes são:
- os requisitos FR8.4, FR9.1, FR9.2, FR9.3, FR10.1 a FR10.6, FR11.2 e NFR4 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- as histórias US4.6 (idempotência), US4.11, US4.12 (fechamento), US5.1 e US5.2 de [stories.md](../../../inception/user-stories/stories.md);
- os contratos C5, C9, C11, C13 e C15 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as respostas Q1 a Q12 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. "Arredondar" sem mais nada quer dizer: valor em USDC com 6 casas, meio para o par (`HALF_EVEN`, Q5). "Arredondar o preço" quer dizer: preço unitário em USDC com 12 casas, meio para o par, para que spread e impacto não desapareçam em tokens de preço muito baixo. "Truncar" quer dizer: quantidade de token para baixo, nas casas do mint. Percentuais são pontos com 4 casas, arredondados `HALF_EVEN`.

```yaml
rules:
  # BR1 - Simulação do preenchimento
  - id: BR1.1
    statement: A política de simulação v1 tem spread de 0,5000%, impacto de 1,0000 ponto por ponto de liquidez consumida, taxa de 0,3000% e teto de consumo de 10,0000% da liquidez.
    category: policy
    applies_to: SimulationPolicy
    trigger: semeadura da migração; todo preenchimento
    logic: "A versão v1 é semeada com spreadPct 0.5000, impactPerConsumedPct 1.0000, feePct 0.3000, maxLiquidityConsumptionPct 10.0000, moneyScale 6, HALF_EVEN, quantidade DOWN. Todo Fill grava a versão usada. Uma versão nova vale só para preenchimentos futuros."
    violation: não se aplica
    source: FR9.1, AC4.11.1, Q3, Q5, Q12

  - id: BR1.2
    statement: Só há preenchimento com preço confiável e liquidez maior que zero.
    category: validation
    applies_to: PaperExecution.execute
    trigger: toda ordem
    logic: "Ler preço de referência e liquidez pela interface C11 da U4. SE o preço não é KNOWN OU está desatualizado (mais de 5 minutos) ENTÃO NotFilled(PRICE_UNAVAILABLE). SE a liquidez não é KNOWN OU é zero ENTÃO NotFilled(NO_LIQUIDITY). A ordem é gravada com status NOT_FILLED e o motivo; nenhum lançamento nem posição muda."
    violation: resultado NotFilled com o motivo
    source: FR9.1, FR10.4, AC4.12.3

  - id: BR1.3
    statement: O preço de preenchimento é o preço de referência deslocado pelo spread e pelo impacto, contra quem opera.
    category: calculation
    applies_to: Fill
    trigger: preenchimento aprovado pela BR1.2
    logic: "consumo% = (quantidade preenchida × preço de referência) ÷ liquidez × 100, arredondado em 4 casas. impacto% = consumo% × 1.0000. slippage% = 0.5000 + impacto%. Compra: preço de preenchimento = referência × (1 + slippage% ÷ 100). Venda: referência × (1 − slippage% ÷ 100). Preço cotado = referência × (1 ± 0.5000 ÷ 100). Preços com arredondar o preço (12 casas). Valor bruto = quantidade preenchida × preço de preenchimento, arredondado (6 casas)."
    violation: não se aplica (cálculo)
    source: FR9.1, AC4.11.1, Q3

  - id: BR1.4
    statement: Uma ordem consome no máximo 10% da liquidez do pool; o que passar disso não é preenchido.
    category: constraint
    applies_to: Fill
    trigger: ordem cujo valor a preço de referência passa de 10% da liquidez
    logic: "valor máximo = liquidez × 10.0000 ÷ 100. SE quantidade pedida × referência > valor máximo ENTÃO quantidade preenchida = truncar(valor máximo ÷ referência) e status PARTIALLY_FILLED; o pedido e o preenchido ficam gravados. SE a quantidade preenchida truncada é zero ENTÃO NotFilled(QUANTITY_BELOW_MINIMUM)."
    violation: preenchimento parcial ou NotFilled
    source: AC4.11.4, Q6, Q12

  - id: BR1.5
    statement: Uma venda é limitada à quantidade da posição.
    category: constraint
    applies_to: PaperExecution.execute (SELL)
    trigger: venda maior que a posição
    logic: "quantidade considerada = mínimo(pedida, posição). SE a posição é zero ENTÃO NotFilled(NO_POSITION). O limite aplicado fica registrado (requestedQuantity e filledQuantity diferentes). Depois vale a BR1.4."
    violation: venda limitada ou NotFilled
    source: AC4.11.4, Q6

  - id: BR1.6
    statement: A taxa é 0,3000% do valor bruto preenchido, em compra e em venda, e nunca entra no custo médio.
    category: calculation
    applies_to: Fill, LedgerEntry
    trigger: todo preenchimento e fechamento
    logic: "taxa = arredondar(valor bruto × 0.3000 ÷ 100). Lançamento FEE com o negativo da taxa."
    violation: não se aplica (cálculo)
    source: FR10.2, AC4.11.1, Q3, Q4

  - id: BR1.7
    statement: Quantidade com mais casas que o mint permite é truncada para baixo.
    category: calculation
    applies_to: PaperOrder, Fill, Position
    trigger: toda quantidade recebida ou calculada
    logic: "quantidade = truncar(quantidade, decimals do token lido pela C11). SE decimals é unknown ENTÃO NotFilled(PRICE_UNAVAILABLE). O truncamento sozinho não torna a ordem PARTIALLY_FILLED: esse status é só para preenchida menor que a pedida já truncada (BR1.4, BR1.5). A pedida original fica gravada em requestedQuantity."
    violation: não se aplica
    source: AC4.11.3, Q5

  - id: BR1.8
    statement: Cada valor em USDC é arredondado uma única vez, na escala 6 e meio para o par.
    category: calculation
    applies_to: Fill, LedgerEntry, Position, P&L
    trigger: todo valor monetário gravado
    logic: "Preços unitários (referência recebida, cotado, preenchimento, saída) com 12 casas; valor bruto, taxa, custo removido, realizado e lançamentos com 6 casas; cada um arredondado no momento em que é calculado, a partir de valores exatos; somas de valores já arredondados não são arredondadas de novo."
    violation: não se aplica
    source: FR10.2, AC4.11.3, AC5.1.2, Q1, Q5

  - id: BR1.10
    statement: Preço, liquidez e casas do token vêm da interface de dados de mercado da U4, em USDC.
    category: policy
    applies_to: PaperExecution, PortfolioPnL
    trigger: todo preenchimento e marcação
    logic: "A U7 lê referência e liquidez em USDC e decimals do token pela C11 (decimals é acréscimo ao C11 registrado nesta Unit). Depois da primeira compra, Position.tokenDecimals gravado vale; se a C11 passar a informar outro valor, a U7 usa o gravado e registra um aviso."
    violation: não se aplica
    source: C11, tabela Escalas, AC4.11.3

  - id: BR1.9
    statement: O modo PAPER nunca constrói, assina nem envia transação.
    category: policy
    applies_to: PaperTradingLedger
    trigger: sempre
    logic: "Nenhum código da U7 depende de assinatura, de cliente de rede Solana ou de roteador de swap; teste de arquitetura."
    violation: não se aplica (proibição de projeto)
    source: FR9.2

  # BR2 - Registro da operação
  - id: BR2.1
    statement: Ordem, preenchimento, lançamentos e posição são gravados juntos, dentro da transação do ciclo da U5, ou nada é gravado.
    category: constraint
    applies_to: PaperExecution
    trigger: toda chamada de execute, fund e closePositions
    logic: "A operação exige transação aberta (propagação MANDATORY); fora de uma, lança erro técnico. Trava as linhas na ordem da BR2.8. Erros de domínio (sem preço, sem liquidez, saldo insuficiente, conta encerrada, conta de outra pessoa) são resultados explícitos (NotFilled) gravados e auditados. Só falha técnica lança exceção, e a U5 desfaz o ciclo inteiro, sem nova tentativa dentro do ciclo."
    violation: falha técnica desfaz tudo
    source: AC4.11.2, C15, ADR-005

  - id: BR2.2
    statement: Cada preenchimento gera lançamentos que decompõem o custo.
    category: calculation
    applies_to: LedgerEntry
    trigger: preenchimento
    logic: "Compra: TRADE_COST = −arredondar(quantidade × referência); SLIPPAGE = −(valor bruto − |TRADE_COST|); FEE = −taxa. Venda: TRADE_PROCEEDS = +arredondar(quantidade × referência); SLIPPAGE = −(|TRADE_PROCEEDS| − valor bruto); FEE = −taxa. Lançamento de valor zero não é gravado. A soma dos lançamentos de uma compra é −(valor bruto + taxa); de uma venda, valor bruto − taxa."
    violation: não se aplica (cálculo)
    source: FR9.1, FR10.1, AC5.2.1

  - id: BR2.3
    statement: A posição usa custo médio ponderado guardado como custo total.
    category: calculation
    applies_to: Position
    trigger: preenchimento ou fechamento
    logic: "Compra: quantity += preenchida; costBasis += valor bruto (taxa fora, BR1.6). Venda: SE preenchida = quantity ENTÃO custo removido = costBasis; SENÃO custo removido = arredondar(costBasis × preenchida ÷ quantity). quantity −= preenchida; costBasis −= custo removido. Realizado = valor recebido bruto − custo removido."
    violation: não se aplica (cálculo)
    source: FR10.2, AC4.11.2, AC5.1.2, Q4

  - id: BR2.4
    statement: Uma posição zerada reinicia o custo.
    category: calculation
    applies_to: Position
    trigger: quantity chega a zero
    logic: "quantity = 0 implica costBasis = 0; a próxima compra começa do zero."
    violation: não se aplica
    source: FR10.2, AC5.1.2

  - id: BR2.5
    statement: O saldo virtual disponível nunca fica negativo.
    category: constraint
    applies_to: PaperAccount, LedgerEntry
    trigger: compra
    logic: "Saldo disponível = soma de todos os lançamentos USDC do bot. A barreira da U6 recusa antes a ordem cujo valor a preço de referência passa do saldo (AC4.6.2), mas o custo real (valor bruto + taxa) pode ser maior. SE valor bruto + taxa > saldo disponível ENTÃO a ordem é gravada NOT_FILLED com motivo INSUFFICIENT_BALANCE, o evento PAPER_ORDER_NOT_FILLED é gravado e nenhum lançamento nem posição muda; não há preenchimento reduzido. Uma soma de lançamentos negativa encontrada numa leitura é inconsistência do ledger: falha técnica."
    violation: NotFilled(INSUFFICIENT_BALANCE)
    source: AC4.6.2, NFR4, C15 (erro de domínio é resultado explícito)

  - id: BR2.6
    statement: O saldo virtual inicial entra por um lançamento FUNDING, uma única vez por bot.
    category: policy
    applies_to: PaperExecution.fund
    trigger: ativação do bot pela U5
    logic: "fund(botId, amount, idempotencyKey) roda pelo mecanismo comum de idempotência do módulo de identidade (IdempotencyGuard, operação PAPER_FUND, escopo tenant + pessoa), dentro da transação da U5: mesma chave e mesmo conteúdo devolvem o resultado guardado; conteúdo diferente é Conflict. A ação cria a PaperAccount (tenant e pessoa do contexto de autenticação), grava FUNDING = +amount com referenceId = idempotencyKey e o EquityPeak inicial = amount. Índice único parcial: no máximo um FUNDING por bot; um segundo fund com outra chave é Conflict. amount > 0 com escala 6."
    violation: Conflict
    source: Q2, C13

  - id: BR2.7
    statement: Uma ordem de um bot em encerramento ou encerrado não é executada.
    category: validation
    applies_to: PaperExecution.execute
    trigger: execute com PaperAccount em CLOSING ou CLOSED, depois da checagem de idempotência
    logic: "A idempotência é conferida antes (BR3.2): a repetição de uma ordem já gravada devolve Duplicate mesmo depois do encerramento. Uma ordem nova é gravada NOT_FILLED com motivo ACCOUNT_CLOSING e evento PAPER_ORDER_NOT_FILLED; lançamentos e posições não mudam."
    violation: NotFilled(ACCOUNT_CLOSING)
    source: AC4.12.3, AC4.12.5

  - id: BR2.8
    statement: Todas as operações travam as linhas na mesma ordem.
    category: constraint
    applies_to: PaperExecution, tarefas de fechamento pendente e de marcação
    trigger: toda transação que muda conta, posição ou maior patrimônio
    logic: "Ordem única: PaperAccount, depois as Positions do bot por (chain, tokenMint) em ordem crescente, depois EquityPeak. Execute, closePositions, a tarefa de fechamento pendente (F4) e a marcação (F5) seguem essa ordem; nenhuma trava uma posição antes da conta."
    violation: não se aplica
    source: AC4.12.4, revisão do design

  # BR3 - Idempotência
  - id: BR3.1
    statement: Existe no máximo uma ordem por tenant, modo e chave de idempotência, garantida pelo banco.
    category: constraint
    applies_to: PaperOrder
    trigger: toda gravação de ordem
    logic: "Restrição única (tenantId, mode, idempotencyKey). A inserção usa INSERT ... ON CONFLICT DO NOTHING, que não aborta a transação da U5 numa colisão; uma segunda inserção concorrente espera a primeira terminar e não cria linha; depois a linha existente é lida para decidir Duplicate ou Conflict. As ordens CLOSE usam a chave derivada '<chave do closePositions>:<positionId>', uma por posição."
    violation: a segunda vira Duplicate ou Conflict (BR3.2, BR3.3)
    source: FR8.4, AC4.6.3, AC4.6.4

  - id: BR3.2
    statement: A mesma chave com o mesmo conteúdo devolve o resultado original.
    category: policy
    applies_to: PaperExecution
    trigger: chave já usada e contentDigest igual
    logic: "Devolver Duplicate com o resultado gravado da primeira execução; nenhuma linha nova, nenhum evento novo. Vale em sequência, em paralelo e depois de falha no meio (a falha desfez tudo, então a nova tentativa é a primeira)."
    violation: não se aplica
    source: AC4.6.3

  - id: BR3.3
    statement: A mesma chave com conteúdo diferente é conflito e nada é registrado.
    category: validation
    applies_to: PaperExecution
    trigger: chave já usada e contentDigest diferente
    logic: "Devolver Conflict; nenhuma linha nova."
    violation: Conflict
    source: AC4.6.4

  - id: BR3.4
    statement: Toda ordem persistida tem chave de idempotência e versão dos limites.
    category: validation
    applies_to: PaperOrder
    trigger: gravação
    logic: "NOT NULL no banco para idempotencyKey e limitsVersion. decisionId é obrigatório para kind TRADE e vazio para kind CLOSE (CHECK no banco). closePositions recebe a versão dos limites vigente da U5 (acréscimo ao C15) e a grava em cada ordem CLOSE. contentDigest de TRADE: (botId, decisionId, side, chain, mint, requestedQuantity, limitsVersion); de CLOSE: (botId, CLOSE, positionId, quantity, limitsVersion). A API C5 sempre devolve chave e versão."
    violation: a gravação falha
    source: AC4.6.5

  # BR4 - Ledger imutável
  - id: BR4.1
    statement: O banco recusa UPDATE e DELETE em lançamentos.
    category: constraint
    applies_to: LedgerEntry
    trigger: qualquer tentativa de alterar ou apagar
    logic: "O papel da aplicação só tem INSERT e SELECT na tabela; um gatilho recusa UPDATE e DELETE de qualquer papel, inclusive TRUNCATE pelo papel da aplicação."
    violation: erro do banco
    source: FR10.1, AC5.2.2

  - id: BR4.2
    statement: Correção só por lançamento compensatório que referencia o original.
    category: policy
    applies_to: LedgerEntry
    trigger: mecanismo de domínio de compensação (só usado em teste no beta)
    logic: "compensate(entryId, motivo) grava COMPENSATION com amount = −amount do original, compensatesEntryId = original e referenceId = original; um lançamento é compensado no máximo uma vez; um COMPENSATION não é compensado. Grava LEDGER_ENTRY_COMPENSATED na trilha."
    violation: segunda compensação recusada
    source: FR10.1, AC5.2.3, Q15 das histórias

  - id: BR4.3
    statement: Nenhuma rota nem tela altera, apaga ou corrige lançamentos.
    category: policy
    applies_to: C5, telas
    trigger: sempre
    logic: "C5 só tem GET (e o POST de verificar agora, que só lê); a tela do ledger não tem ação de edição."
    violation: não se aplica
    source: FR10.1, AC5.2.2, Q15 das histórias

  # BR5 - Fechamento no encerramento
  - id: BR5.1
    statement: No encerramento com preço confiável, cada posição é fechada ao preço de referência vigente, com a taxa da política.
    category: calculation
    applies_to: PaperExecution.closePositions
    trigger: encerramento confirmado na U5
    logic: "closePositions(botId, limitsVersion, idempotencyKey) roda pelo mecanismo comum de idempotência (operação PAPER_CLOSE_POSITIONS) e trava na ordem da BR2.8. PaperAccount para CLOSING. Para cada posição aberta com preço confiável (BR1.2): ordem CLOSE com a chave derivada (BR3.1), valor = arredondar(quantidade × referência), sem spread nem impacto nem teto, como pede AC4.12.2; lançamentos CLOSE = +valor e FEE = −taxa sobre o valor; realizado = valor − costBasis; quantity e costBasis zero; status CLOSED. Com todas fechadas, PaperAccount para CLOSED. Depois recalcula o maior patrimônio (BR7.3). Decisão consciente: fechar ao preço de referência dá patrimônio maior que a marcação pelo preço de saída (viés otimista do PAPER), por exigência do AC4.12.2."
    violation: não se aplica
    source: FR11.2, AC4.12.2

  - id: BR5.2
    statement: Sem preço confiável, a posição fica em encerramento pendente.
    category: policy
    applies_to: Position
    trigger: closePositions sem preço confiável para a posição
    logic: "Ordem CLOSE com status CLOSE_PENDING; posição em CLOSE_PENDING, quantidade e custo mantidos; PaperAccount fica CLOSING; o não realizado dela aparece unknown (BR6.3)."
    violation: não se aplica
    source: AC4.12.3, Q6 das histórias

  - id: BR5.3
    statement: O fechamento pendente é concluído uma única vez quando o preço volta a ser confiável.
    category: constraint
    applies_to: Position
    trigger: tarefa após cada coleta de mercado; também na partida do processo
    logic: "Para cada posição CLOSE_PENDING com preço confiável, numa transação: travar a PaperAccount e depois a posição (BR2.8), conferir que ainda é CLOSE_PENDING, fechar como na BR5.1, mudar a ordem CLOSE para FILLED e recalcular o maior patrimônio. Tentativas paralelas ou depois de reinício veem CLOSED e não fazem nada. Grava POSITION_CLOSE_COMPLETED com ator system."
    violation: não se aplica
    source: AC4.12.4

  # BR6 - P&L
  - id: BR6.1
    statement: Realizado e taxas de um período somam os preenchimentos com horário dentro de [início, fim).
    category: calculation
    applies_to: GET /api/v1/pnl
    trigger: consulta com from e to em UTC
    logic: "realizado = soma de Fill.realized dos preenchimentos no período; taxas = soma, com sinal invertido, dos lançamentos FEE dessas ordens e das COMPENSATION que compensam esses FEE (lidos do ledger); por token e no total; filtro opcional por bot. O P&L lê o ledger e os preenchimentos, que são o detalhe gravado das ordens do ledger; nunca grava nada."
    violation: from >= to ou período maior que 366 dias: 400 VALIDATION_FAILED
    source: FR10.3, AC5.1.1, Q8

  - id: BR6.2
    statement: O não realizado é das posições abertas agora, pelo preço estimado de saída.
    category: calculation
    applies_to: PnlReport, equity
    trigger: consulta ou marcação
    logic: "Para cada posição com quantity > 0 e preço confiável: consumo% = arredondar4(quantidade × referência ÷ liquidez × 100), limitado a 100; preço de saída = arredondar o preço(referência × (1 − (0.5000 + consumo%) ÷ 100)), nunca abaixo de zero; valor de saída bruto = arredondar(quantidade × preço de saída); taxa estimada = arredondar(valor bruto × 0.3000 ÷ 100); valor líquido = bruto − taxa estimada; não realizado = líquido − costBasis. A taxa estimada não entra na coluna de taxas (que só soma taxas pagas). A tela diz que o não realizado é do momento atual."
    violation: não se aplica (cálculo)
    source: FR10.3, Q7, Q8

  - id: BR6.3
    statement: Posição sem preço confiável tem não realizado unknown, e o total fica parcial.
    category: policy
    applies_to: PnlReport
    trigger: posição aberta sem preço confiável ou em CLOSE_PENDING sem preço
    logic: "unrealized da linha = UNKNOWN com motivo (PRICE_UNKNOWN, STALE, NO_LIQUIDITY); total unrealized = UNKNOWN; partial = true. Nunca somar como zero."
    violation: não se aplica
    source: FR10.3, AC5.1.3, AC4.12.3

  - id: BR6.4
    statement: Token sem liquidez é marcado "sem liquidez", com fonte e horário da última marcação.
    category: policy
    applies_to: PnlTokenLine
    trigger: liquidez KNOWN igual a zero
    logic: "noLiquidity = true; mark traz o último preço KNOWN com fonte e observedAt; unrealized UNKNOWN com motivo NO_LIQUIDITY (BR6.3)."
    violation: não se aplica
    source: FR10.4, AC5.1.4

  - id: BR6.5
    statement: A soma das linhas por token é exatamente o total.
    category: calculation
    applies_to: PnlReport
    trigger: toda resposta
    logic: "Os totais são a soma das linhas já arredondadas, sem novo arredondamento; o teste confere igualdade exata."
    violation: não se aplica
    source: FR10.3, AC5.1.1

  - id: BR6.6
    statement: Período sem operações ou falha de cálculo é explicado, e nada parcial aparece como completo.
    category: policy
    applies_to: tela de P&L, GET /api/v1/pnl
    trigger: período vazio ou erro
    logic: "Sem preenchimentos e sem posições: totais 0.000000 com empty = true e a frase 'Nenhuma operação neste período'. Falha técnica: 503 e a tela mostra 'Não foi possível calcular o P&L agora' sem números."
    violation: não se aplica
    source: AC5.1.5

  - id: BR6.7
    statement: O catálogo de cenários de resultado conhecido fixa os valores esperados.
    category: calculation
    applies_to: PaperExecution, PortfolioPnL
    trigger: testes escritos antes do código (núcleo financeiro)
    logic: "Os cenários C1 a C15 da functional-spec.md, com a política v1, são a fonte dos testes; cada número da tabela é conferido por igualdade exata."
    violation: o teste falha
    source: AC5.1.2, AC4.11.1, AC4.11.3, AC4.11.4

  # BR7 - Visão de risco para a U6 (C13)
  - id: BR7.1
    statement: A exposição é medida pelo custo das posições abertas.
    category: calculation
    applies_to: PortfolioRiskView.snapshot
    trigger: avaliação de ordem na U6
    logic: "exposição do token = costBasis da posição do token; exposição total = soma dos costBasis do bot. Usa custo e não preço, então é sempre conhecida."
    violation: não se aplica
    source: C13, FR8.3

  - id: BR7.2
    statement: A perda realizada do dia UTC soma realizado e taxas dos preenchimentos do dia.
    category: calculation
    applies_to: PortfolioRiskView.snapshot, DayUsage
    trigger: consulta
    logic: "resultado do dia = soma de Fill.realized − taxas (como na BR6.1, já com as compensações) dos preenchimentos com filledAt no dia UTC; perda do dia = máximo(0, −resultado do dia)."
    violation: não se aplica
    source: C13, C5

  - id: BR7.3
    statement: O maior patrimônio é atualizado depois de cada preenchimento e de cada marcação; com preço não confiável, não muda.
    category: calculation
    applies_to: EquityPeak
    trigger: preenchimento; tarefa de marcação após cada coleta
    logic: "Numa transação que trava a PaperAccount (BR2.8), ler saldo e posições juntos: patrimônio = saldo disponível + soma dos valores líquidos de saída (BR6.2). SE alguma posição aberta não tem preço confiável ENTÃO patrimônio unknown, lastEquity vazio e peakEquity mantido. SENÃO lastEquity = patrimônio e peakEquity = máximo(peakEquity, patrimônio), gravado com atualização condicional (só sobe se o novo valor é maior que o gravado). Como saldo e posições vêm do mesmo instante travado, um preenchimento nunca é visto pela metade."
    violation: não se aplica
    source: C13, Q10

  - id: BR7.4
    statement: A visão de risco devolve exposição, perda do dia, patrimônio, maior patrimônio, saldo disponível e o horário da última operação.
    category: policy
    applies_to: PortfolioRiskView
    trigger: snapshot(botId, chain, tokenMint, utcDay)
    logic: "Recebe o token avaliado (acréscimo ao C13). equity é Optional: vazio quando unknown (BR7.3); a U6 decide o que fazer com patrimônio ausente no seu próprio design (a U7 nunca devolve zero no lugar)."
    violation: não se aplica
    source: C13

  - id: BR7.5
    statement: A rota de uso do dia devolve só exposição, perda do dia e o dia UTC.
    category: policy
    applies_to: GET /api/v1/bots/{botId}/day-usage
    trigger: consulta
    logic: "DayUsage = { exposure, dayLoss, utcDay }; os limites vêm da U5 na tela (correção do C5)."
    violation: não se aplica
    source: C5, Q11

  # BR8 - Reconciliação
  - id: BR8.1
    statement: A conferência roda a cada 5 minutos por pessoa e guarda o resultado.
    category: policy
    applies_to: ReconciliationCheck
    trigger: agendamento com execução única
    logic: "Para cada pessoa com PaperAccount, conferir e gravar um ReconciliationCheck SCHEDULED; manter os últimos 100 por pessoa."
    violation: não se aplica
    source: FR10.5, Q9

  - id: BR8.2
    statement: A conferência compara cada ordem preenchida com seus lançamentos.
    category: validation
    applies_to: ReconciliationCheck
    trigger: cada conferência
    logic: "Para cada ordem FILLED ou PARTIALLY_FILLED: esperado = soma dos valores do Fill pela BR2.2 ou BR5.1 mais as COMPENSATION de lançamentos dessa ordem; encontrado = soma dos lançamentos que referenciam a ordem mais essas mesmas COMPENSATION. Uma compensação válida não gera divergência. Sem lançamento: MISSING_ENTRY. Soma diferente: AMOUNT_MISMATCH com esperado e encontrado. Lançamento cujo referenceId não é ordem, nem a chave de um FUNDING, nem (para COMPENSATION) um entryId existente: ORPHAN_ENTRY. Posição cuja quantidade ou custo não é a soma dos preenchimentos do bot e token: POSITION_MISMATCH (acréscimo ao enum do C5)."
    violation: DIVERGENT
    source: FR10.5, AC5.2.4

  - id: BR8.3
    statement: "Verificar agora" roda a conferência da pessoa na hora.
    category: policy
    applies_to: POST /api/v1/reconciliation/run
    trigger: pedido da pessoa
    logic: "Com Idempotency-Key; grava um ReconciliationCheck ON_DEMAND e devolve o resultado; no máximo 6 por minuto por pessoa (429 acima)."
    violation: 429 RATE_LIMITED
    source: FR10.5, Q9

  - id: BR8.4
    statement: Divergência nunca é escondida.
    category: policy
    applies_to: tela de P&L e ledger
    trigger: último resultado DIVERGENT
    logic: "Aviso fixo com a quantidade e a lista das operações divergentes; sem divergência, 'Reconciliado' com o horário da verificação."
    violation: não se aplica
    source: FR10.5, AC5.2.4

  # BR9 - Isolamento
  - id: BR9.1
    statement: Toda leitura e escrita filtra por tenant e pessoa do contexto de autenticação; bot de outra pessoa responde 404.
    category: authorization
    applies_to: C5, C13, C15
    trigger: toda chamada
    logic: "Tenant e pessoa vêm do token (rotas) ou do contexto do ciclo (interfaces). Rotas: SE o botId tem PaperAccount de outra pessoa ENTÃO 404 NOT_FOUND, sem dado; SE não tem PaperAccount nenhuma (bot ainda não ativado) ENTÃO resposta vazia (sem operações, zeros e empty = true). Interfaces: execute, closePositions ou snapshot para um bot com PaperAccount de outra pessoa ou de outro tenant é recusa de domínio NotFilled(TENANT_MISMATCH) (ou, no snapshot, exceção de domínio TenantMismatch), auditada com REQUEST_FORBIDDEN; execute sem PaperAccount é erro técnico (a U5 não chamou fund). As tarefas (marcação, fechamento pendente, reconciliação) leem tenant e pessoa da própria linha e nunca misturam pessoas numa consulta sem filtro."
    violation: 404
    source: FR10.6, NFR5, PRD §11

  - id: BR9.2
    statement: Toda linha é PAPER e toda consulta filtra por PAPER.
    category: constraint
    applies_to: todas as entidades
    trigger: sempre
    logic: "CHECK mode = 'PAPER' no banco; consultas com mode = 'PAPER'; uma requisição com mode diferente de PAPER responde 403 REAL_MODE_UNAVAILABLE (regra comum)."
    violation: 403 REAL_MODE_UNAVAILABLE
    source: FR10.6, C-03

  # BR10 - Telas
  - id: BR10.1
    statement: As telas de P&L, ledger e operações mostram "MODO PAPER (simulado)" no título.
    category: policy
    applies_to: telas da U7
    trigger: renderização
    logic: "Rótulo em texto no título, sempre."
    violation: não se aplica
    source: FR9.3

  - id: BR10.2
    statement: Cada lançamento mostra operação, custo, taxa, slippage, horário e versão; "ver hipóteses" mostra as premissas.
    category: policy
    applies_to: tela do ledger
    trigger: abertura do ledger e de "ver hipóteses"
    logic: "Agrupar os lançamentos por operação; 'ver hipóteses' mostra referência, cotação, preço de preenchimento, spread, impacto, consumo de liquidez, taxa, quantidade pedida e preenchida, fonte e horário do dado de mercado e a versão da política."
    violation: não se aplica
    source: FR9.1, AC5.2.1
```

## Resumo das regras

| Grupo | Regras | O que decide |
|---|---|---|
| BR1 Simulação | BR1.1 a BR1.10 | política v1, preço confiável, cálculo do preço (12 casas no preço unitário), teto de 10% da liquidez, venda limitada, taxa, truncamento, arredondamento único, nunca assinar, origem de preço e casas |
| BR2 Registro | BR2.1 a BR2.8 | gravação junta, decomposição em lançamentos, custo médio, reinício do custo, saldo insuficiente como recusa, FUNDING pelo mecanismo comum, bot em encerramento, ordem das travas |
| BR3 Idempotência | BR3.1 a BR3.4 | unicidade no banco, Duplicate, Conflict, chave e versão sempre presentes |
| BR4 Ledger imutável | BR4.1 a BR4.3 | banco recusa alteração, compensação, sem rota de correção |
| BR5 Fechamento | BR5.1 a BR5.3 | fechamento ao preço de referência, pendente, conclusão única |
| BR6 P&L | BR6.1 a BR6.7 | período, preço de saída, `unknown` e parcial, sem liquidez, soma exata, vazio e falha, catálogo de cenários |
| BR7 Risco para a U6 | BR7.1 a BR7.5 | exposição pelo custo, perda do dia, maior patrimônio, visão de risco, uso do dia |
| BR8 Reconciliação | BR8.1 a BR8.4 | tarefa de 5 minutos, conferência, verificar agora, divergência visível |
| BR9 Isolamento | BR9.1 e BR9.2 | tenant e pessoa, só PAPER |
| BR10 Telas | BR10.1 e BR10.2 | rótulo PAPER, ver hipóteses |
