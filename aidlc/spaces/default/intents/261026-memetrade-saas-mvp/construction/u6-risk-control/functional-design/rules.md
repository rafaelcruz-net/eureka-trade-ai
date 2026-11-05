# Regras de negócio — U6 Barreira de limites

Regras do RiskControl. As fontes são:
- os requisitos FR7.3, FR8.3, FR8.4, FR8.5 e NFR4 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- as histórias US4.6 e US4.7 de [stories.md](../../../inception/user-stories/stories.md);
- os contratos C9, C11, C13, C14 e C21 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as respostas Q1 a Q11 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. Convenções usadas nas regras:
- "Arredondar" quer dizer valor em USDC com 6 casas, meio para o par (`HALF_EVEN`).
- Percentuais são pontos com 4 casas, também `HALF_EVEN`.
- As comparações com os limites usam os valores já arredondados. Nenhuma conta usa ponto flutuante.
- "Agora" é o instante do relógio injetável no início da avaliação, e o dia UTC é a data desse instante em UTC.

```yaml
rules:
  # BR1 - Entrada da avaliação
  - id: BR1.1
    statement: A barreira só avalia dentro da transação do ciclo aberta pela U5, com o tenant e a pessoa do contexto de autenticação.
    category: authorization
    applies_to: OrderGate.evaluate
    trigger: toda chamada
    logic: "SE não há transação aberta ENTÃO falha técnica (programação), sem gravar nada. tenantId e ownerUserId vêm do contexto de autenticação do ciclo (C10), que a U5 liga com o Principal do dono do bot; nunca vêm da ordem nem dos limites. mode é sempre PAPER. Um bot de outra pessoa no contexto (TenantMismatchException da C13) é, para a U6, erro de programação da U5: a exceção sobe e desfaz o ciclo, e a U6 não conta com o evento de auditoria da U7, que some junto no rollback."
    violation: exceção técnica que desfaz a transação do ciclo
    source: FR8.3, C14, C15 (regras), ADR-005

  - id: BR1.2
    statement: A ordem candidata e os limites chegam completos e coerentes na chamada.
    category: validation
    applies_to: CandidateOrder, LimitsSnapshot
    trigger: toda chamada
    logic: "SE requestedQuantity <= 0 OU falta campo obrigatório OU algum limite está fora do seu domínio (entities.md) OU maxTokenExposure > maxTotalExposure ENTÃO falha técnica (programação). A coerência dos limites é validada pela U5 na criação (AC4.2.3); aqui é só uma defesa."
    violation: exceção técnica que desfaz a transação do ciclo
    source: FR7.3, AC4.2.3

  - id: BR1.3
    statement: A barreira lê preço de referência e liquidez do token pela C11 e a visão de risco do bot pela C13, uma vez por avaliação.
    category: policy
    applies_to: OrderGate.evaluate
    trigger: toda chamada, inclusive a que termina como duplicada (BR2.8)
    logic: "Ler MarketDataQuery.referencePrice(token) e liquidity(token). Ler PortfolioRiskView.snapshot(botId, chain, tokenMint, dia UTC de agora). Os valores lidos são os únicos usados por todas as verificações da mesma chamada e vão para MarketInputs quando há recusa. Na chamada duplicada, os dados lidos servem só para o registro da recusa; nenhuma verificação roda sobre eles. TenantMismatchException sobe sem ser capturada (BR1.1)."
    violation: não se aplica
    source: C11, C13

  - id: BR1.4
    statement: O valor da ordem é a quantidade vezes o preço de referência, antes do slippage.
    category: calculation
    applies_to: OrderGate.evaluate
    trigger: preço de referência utilizável (BR2.6)
    logic: "valor = arredondar(requestedQuantity × referencePrice). É o valor usado no tamanho por ordem e na exposição."
    violation: não se aplica (cálculo)
    source: Q3

  - id: BR1.5
    statement: O slippage estimado usa uma cópia dos parâmetros da política de simulação v1 da U7.
    category: calculation
    applies_to: OrderGate.evaluate
    trigger: preço e liquidez utilizáveis
    logic: "consumo% = arredondar4(valor ÷ liquidez × 100). slippage estimado% = 0.5000 + consumo% × 1.0000. Não aplica o teto de consumo de 10% da U7: a barreira estima a ordem inteira, o que é conservador quando a U7 preencher só uma parte. A cópia acompanha a política vigente da U7: um teste de conferência lê a política vigente semeada pela U7 (não uma constante v1 fixa) e falha quando spread, impacto ou taxa da cópia deixam de coincidir; para a mesma quantidade, preço e liquidez, também compara o slippage estimado com o slippagePct do preenchimento da U7 enquanto o consumo fica até 10%. Toda versão nova da política exige atualizar a cópia."
    violation: não se aplica (cálculo)
    source: Q5, BR1.1 e BR1.3 da U7

  - id: BR1.6
    statement: O custo estimado da compra soma o slippage e a taxa de 0,3000%, e a queda estimada do patrimônio segue a medida de patrimônio da U7 (valor líquido de saída).
    category: calculation
    applies_to: OrderGate.evaluate
    trigger: preço e liquidez utilizáveis
    logic: "Compra: valor com slippage = arredondar(valor × (1 + slippage estimado% ÷ 100)); taxa estimada = arredondar(valor com slippage × 0.3000 ÷ 100); custo da compra = valor com slippage + taxa estimada. Valor líquido de saída da quantidade comprada, pela mesma conta da BR6.2 da U7: preço de saída = arredondar o preço(referência × (1 − slippage estimado% ÷ 100)), bruto de saída = arredondar(quantidade × preço de saída), líquido de saída = bruto − arredondar(bruto × 0.3000 ÷ 100). Queda do patrimônio na compra = custo da compra − líquido de saída. Venda: queda do patrimônio = 0. A venda converte em saldo um valor que a C13 já mede pela saída, e com consumo da parte vendida, nunca maior que o da posição inteira, o líquido recebido não fica abaixo da parte que ela valia no patrimônio. Aproximação registrada: a compra é estimada como se não houvesse posição anterior no mesmo token. A U7 marca a posição inteira, com consumo maior, então a queda real pode passar da estimada; o excedente aparece no patrimônio do ciclo seguinte e é barrado por equity <= piso (BR2.11). Um teste compara, para um bot sem posição no token, a queda estimada com a diferença real do patrimônio da U7 antes e depois do preenchimento."
    violation: não se aplica (cálculo)
    source: Q4, Q5, Q7, BR1.6 e BR6.2 da U7

  # BR2 - Verificações
  - id: BR2.1
    statement: Na compra, o valor da ordem não passa do tamanho máximo por ordem.
    category: constraint
    applies_to: OrderGate.evaluate (BUY)
    trigger: compra com preço utilizável
    logic: "SE valor > maxOrderSize ENTÃO motivo ORDER_SIZE (medido = valor, limite = maxOrderSize, USDC)."
    violation: motivo ORDER_SIZE
    source: FR8.3, AC4.6.1

  - id: BR2.2
    statement: Na compra, a exposição no token depois da ordem não passa da exposição máxima por token.
    category: constraint
    applies_to: OrderGate.evaluate (BUY)
    trigger: compra com preço utilizável
    logic: "exposição depois = tokenExposure (C13) + valor. SE exposição depois > maxTokenExposure ENTÃO motivo TOKEN_EXPOSURE (medido = exposição depois)."
    violation: motivo TOKEN_EXPOSURE
    source: FR8.3, AC4.6.1

  - id: BR2.3
    statement: Na compra, a exposição total depois da ordem não passa da exposição total máxima.
    category: constraint
    applies_to: OrderGate.evaluate (BUY)
    trigger: compra com preço utilizável
    logic: "total depois = totalExposure (C13) + valor. SE total depois > maxTotalExposure ENTÃO motivo TOTAL_EXPOSURE (medido = total depois)."
    violation: motivo TOTAL_EXPOSURE
    source: FR8.3, AC4.6.1

  - id: BR2.4
    statement: O slippage estimado não passa do slippage máximo.
    category: constraint
    applies_to: OrderGate.evaluate (BUY e SELL)
    trigger: preço e liquidez utilizáveis
    logic: "SE slippage estimado% > maxSlippagePct ENTÃO motivo SLIPPAGE (medido = slippage estimado%, PERCENT)."
    violation: motivo SLIPPAGE
    source: FR8.3, AC4.6.1

  - id: BR2.5
    statement: A liquidez do token é conhecida e pelo menos a liquidez mínima.
    category: constraint
    applies_to: OrderGate.evaluate (BUY e SELL)
    trigger: toda avaliação não duplicada
    logic: "SE liquidity é UNKNOWN ENTÃO motivo MIN_LIQUIDITY (medido = UNKNOWN). SENÃO SE liquidez < minLiquidity ENTÃO motivo MIN_LIQUIDITY (medido = liquidez). Liquidez desconhecida nunca vira zero."
    violation: motivo MIN_LIQUIDITY
    source: FR8.3, AC4.6.1, C-05

  - id: BR2.6
    statement: O preço de referência é conhecido e tem no máximo 60 segundos.
    category: constraint
    applies_to: OrderGate.evaluate (BUY e SELL)
    trigger: toda avaliação não duplicada
    logic: "SE referencePrice é UNKNOWN ENTÃO motivo STALE_DATA (medido = UNKNOWN). SENÃO idade = agora − observedAt, em segundos inteiros truncados; SE idade > 60 ENTÃO motivo STALE_DATA (medido = idade, limite = 60, SECONDS). O mesmo vale para a liquidez KNOWN: SE a idade dela > 60 ENTÃO STALE_DATA (medido = idade da liquidez). STALE_DATA aparece uma vez na lista, com um detalhe só: o do preço quando ele falhou, senão o da liquidez. Idade negativa (horário de observação no futuro) conta como 0. Utilizável quer dizer KNOWN e com até 60 s: o preço só é utilizável sem falha de frescor no preço, e a liquidez só é utilizável KNOWN, com até 60 s e sem falha de frescor nela. O limite de 5 minutos do MarketData não basta: a barreira aplica os 60 s."
    violation: motivo STALE_DATA
    source: FR8.3, AC4.6.1 (Q10 das histórias), C11

  - id: BR2.7
    statement: Entre duas operações do bot passa pelo menos o intervalo mínimo.
    category: constraint
    applies_to: OrderGate.evaluate (BUY e SELL)
    trigger: toda avaliação não duplicada com lastTradeAt presente
    logic: "SE lastTradeAt (C13) está presente E agora < lastTradeAt + minIntervalSeconds ENTÃO motivo INTERVAL (medido = segundos desde lastTradeAt, limite = minIntervalSeconds). Agora = T + I é permitido; T + I − 1 s é recusado. Sem operação anterior não há restrição."
    violation: motivo INTERVAL
    source: FR8.3, AC4.6.1

  - id: BR2.8
    statement: Cada decisão é avaliada uma única vez; a segunda avaliação da mesma decisão é recusada por duplicidade.
    category: constraint
    applies_to: OrderGate.evaluate
    trigger: já existe OrderEvaluation para (tenant, PAPER, botId, decisionId), vista antes das verificações ou na gravação
    logic: "Passo 1: depois das leituras da BR1.3, procurar a avaliação da decisão. SE existe ENTÃO caminho de duplicidade. Passo 2: senão, rodar as verificações e gravar a OrderEvaluation com uma inserção que, em conflito na unicidade (tenantId, mode, botId, decisionId), não faz nada e informa que não inseriu, sem abortar a transação (no PostgreSQL, INSERT ... ON CONFLICT DO NOTHING; uma transação concorrente com a mesma decisão ainda não confirmada faz esta esperar). SE não inseriu ENTÃO ler a avaliação vencedora, já confirmada, descartar as verificações calculadas e seguir o caminho de duplicidade. Caminho de duplicidade: gravar OrderRejection com evaluationId = avaliação original, reasons = [DUPLICATE], um detalhe (medido = decisionId, limite = evaluationId original, REFERENCE), pauseTriggers vazio, a versão de limites recebida e os MarketInputs lidos; publicar um ORDER_REJECTED; devolver Rejected. Nada chega à execução. Uma falha técnica que desfaz o ciclo desfaz também a primeira avaliação, e a decisão é avaliada de novo como primeira. Por que não a chave comum de idempotência da identidade: a regra do projeto pede o mecanismo comum para requisições que mudam estado; a C14 é uma chamada interna do ciclo, sem requisição nem Idempotency-Key, e a chave natural da barreira é a decisão. A rota da U6 (C21) é só GET. Uma DUPLICATE de decisão cuja ordem já foi executada não é uma operação recusada: a U5 não reprocessa decisões já confirmadas (AC4.5.4), e se isso acontecer por defeito, a tela mostra o motivo como 'decisão já avaliada; vale o resultado original', não como limite violado."
    violation: Rejected com DUPLICATE e um evento ORDER_REJECTED
    source: FR8.4, AC4.6.1 (linha Duplicidade), Q9

  - id: BR2.9
    statement: Na compra, o custo estimado total cabe no saldo PAPER disponível.
    category: constraint
    applies_to: OrderGate.evaluate (BUY)
    trigger: compra com preço e liquidez utilizáveis
    logic: "SE custo da compra (BR1.6) > availableVirtualBalance (C13) ENTÃO motivo INSUFFICIENT_PAPER_BALANCE (medido = custo, limite = saldo disponível). Com a liquidez inutilizável, o custo não é calculado e vale só o motivo da liquidez."
    violation: motivo INSUFFICIENT_PAPER_BALANCE
    source: AC4.6.2, Q4

  - id: BR2.10
    statement: Atingir a perda diária máxima recusa a ordem e indica pausa por perda diária.
    category: constraint
    applies_to: OrderGate.evaluate (BUY e SELL)
    trigger: toda avaliação não duplicada
    logic: "SE realizedDayLossWithFees (C13, dia UTC de agora) >= maxDailyLoss ENTÃO motivo DAILY_LOSS (medido = perda do dia) e gatilho de pausa DAILY_LOSS. P − 0,000001 ainda permite; P recusa. A perda já inclui as taxas (Q9 das histórias)."
    violation: motivo DAILY_LOSS e gatilho DAILY_LOSS
    source: FR8.5, AC4.7.1

  - id: BR2.11
    statement: O patrimônio depois da ordem não fica abaixo do piso do drawdown, e atingir o drawdown máximo indica pausa.
    category: constraint
    applies_to: OrderGate.evaluate (BUY e SELL)
    trigger: toda avaliação não duplicada
    logic: "piso = arredondar(peakEquity × (1 − maxDrawdownPct ÷ 100)). SE equity (C13) está vazio ENTÃO motivo DRAWDOWN (medido = UNKNOWN), sem gatilho de pausa (Q6). SENÃO: SE equity <= piso ENTÃO motivo DRAWDOWN e gatilho de pausa DRAWDOWN (o drawdown atual já atingiu D). SENÃO SE o preço e a liquidez são utilizáveis E equity − queda do patrimônio (BR1.6) < piso ENTÃO motivo DRAWDOWN, sem gatilho (Q7). Na venda a queda é 0, então vale só a comparação do patrimônio atual. medido = drawdown% = arredondar4((peakEquity − patrimônio considerado) ÷ peakEquity × 100), limite = maxDrawdownPct. A comparação é feita em USDC contra o piso, para que o arredondamento do percentual nunca deixe passar o menor incremento."
    violation: motivo DRAWDOWN, com ou sem gatilho
    source: FR8.3, AC4.7.2, Q6, Q7

  - id: BR2.12
    statement: Na venda não se aplicam tamanho por ordem, exposição e saldo; as demais verificações valem.
    category: policy
    applies_to: OrderGate.evaluate (SELL)
    trigger: side = SELL
    logic: "Para SELL, BR2.1, BR2.2, BR2.3 e BR2.9 não rodam. BR2.4 a BR2.8, BR2.10 e BR2.11 rodam como na compra. Se a venda é maior que a posição, quem limita é a U7 (BR1.5 da U7)."
    violation: não se aplica
    source: Q2

  # BR3 - Resultado
  - id: BR3.1
    statement: Todas as verificações aplicáveis rodam, e os motivos que falharam são gravados numa ordem fixa, com o primeiro como principal.
    category: policy
    applies_to: GateResult
    trigger: avaliação não duplicada
    logic: "Ordem fixa: ORDER_SIZE, TOKEN_EXPOSURE, TOTAL_EXPOSURE, SLIPPAGE, MIN_LIQUIDITY, STALE_DATA, INTERVAL, DUPLICATE, INSUFFICIENT_PAPER_BALANCE, DAILY_LOSS, DRAWDOWN (a mesma do enum da C21). SE nenhum motivo ENTÃO Approved(order). SENÃO Rejected(reasons nessa ordem, limitsVersion, pauseTriggers). O primeiro de reasons é o motivo principal mostrado na tela."
    violation: não se aplica
    source: AC4.6.1, Q1

  - id: BR3.2
    statement: Uma verificação que depende de um dado ausente não roda e não inventa valor; o motivo do dado ausente fica registrado.
    category: validation
    applies_to: OrderGate.evaluate
    trigger: preço ou liquidez inutilizáveis (BR2.6)
    logic: "Sem preço utilizável (UNKNOWN ou com mais de 60 s): BR2.1, BR2.2, BR2.3, BR2.4 e BR2.9 não rodam, e BR2.11 só compara o patrimônio atual. Sem liquidez utilizável (UNKNOWN, que gera MIN_LIQUIDITY, ou com mais de 60 s, que gera STALE_DATA): BR2.4 e BR2.9 não rodam, BR2.5 só roda quando a liquidez é KNOWN, e BR2.11 só compara o patrimônio atual. Nesses casos estimatedSlippagePct é UNKNOWN, e orderValue também quando o preço não é utilizável. Nenhuma conta usa zero no lugar de UNKNOWN. A recusa já está garantida pelo motivo do dado ausente."
    violation: não se aplica
    source: C-05, FR4.4, AC4.6.1

  - id: BR3.3
    statement: Os gatilhos de pausa são um conjunto e voltam com a recusa.
    category: policy
    applies_to: GateResult.Rejected
    trigger: BR2.10 ou BR2.11 com gatilho
    logic: "pauseTriggers = conjunto de {DAILY_LOSS se BR2.10 falhou} e {DRAWDOWN se BR2.11 falhou por equity <= piso}. Pode ter os dois. A U6 não pausa o bot: a U5 aplica cada motivo de pausa correspondente na mesma transação do ciclo (US4.7)."
    violation: não se aplica
    source: FR8.5, AC4.7.1, AC4.7.2, Q8

  - id: BR3.4
    statement: Toda avaliação não duplicada grava a OrderEvaluation da decisão com o resultado e a versão dos limites.
    category: policy
    applies_to: OrderEvaluation
    trigger: avaliação não duplicada
    logic: "Gravar OrderEvaluation(outcome, limitsVersion, evaluatedAt = agora) na transação do ciclo. Approved não gera evento de auditoria: o evento da operação é da U7. A versão dos limites que a U5 passar à execução é a mesma avaliada."
    violation: não se aplica
    source: AC4.6.5, Q9

  - id: BR3.5
    statement: Toda recusa grava a OrderRejection com os detalhes de cada motivo e um único evento ORDER_REJECTED, na mesma transação.
    category: policy
    applies_to: OrderRejection
    trigger: GateResult.Rejected
    logic: "Depois da OrderEvaluation (BR3.4, BR2.8), gravar OrderRejection com evaluationId, reasons, pauseTriggers, limitsVersion, MarketInputs e um RejectionReasonDetail por motivo (Q10). Publicar pela C9 um evento ORDER_REJECTED com ator system, tenant e pessoa do contexto, targetRef = botId e detalhes {rejectionId, decisionId, reasons, pauseTriggers, limitsVersion}. Os detalhes nunca trazem segredo nem endereço de carteira."
    violation: não se aplica
    source: AC4.6.1, DP-2, C9, Q10

  # BR4 - Leitura e guarda
  - id: BR4.1
    statement: A rota de recusas de um bot só mostra recusas do tenant e da pessoa do token.
    category: authorization
    applies_to: GET /api/v1/bots/{botId}/rejections
    trigger: toda leitura
    logic: "Toda consulta filtra por tenantId, ownerUserId e mode = PAPER do contexto de autenticação e pelo botId do caminho; nenhuma lê linhas de outro tenant e nenhuma consulta a U5. SE não existe OrderEvaluation desse botId para a pessoa do token ENTÃO 404 NOT_FOUND. SENÃO devolver a página, vazia quando todas as avaliações foram aprovadas. Assim, um bot de outra pessoa responde 404 sempre, avaliado ou não, com a mesma resposta de um bot inexistente, e nada revela a existência do bot alheio. Desvio registrado da C21: um bot da própria pessoa que ainda nunca passou pela barreira também responde 404, porque para a U6 esse recurso ainda não existe; a tela do bot (U5) trata 404 desta rota como 'nenhuma recusa'."
    violation: 404 NOT_FOUND, sem dado de outro tenant
    source: C21, DP-1, FR2.1

  - id: BR4.2
    statement: A lista de recusas é paginada por cursor, da mais recente para a mais antiga, com os detalhes de cada motivo.
    category: policy
    applies_to: GET /api/v1/bots/{botId}/rejections
    trigger: toda leitura
    logic: "Ordem evaluatedAt decrescente e rejectionId como desempate; cursor opaco; limit de 1 a 100. Cada item traz rejectionId, decisionId, reasons, limitsVersion, evaluatedAt e, de forma aditiva (Q10), pauseTriggers, details[{reason, measured, limit, unit}] e market{referencePrice, liquidity como MarketValue, estimatedSlippagePct, orderValue}."
    violation: 400 VALIDATION_FAILED para cursor ou limit inválidos
    source: C21, Q10

  - id: BR4.3
    statement: Avaliações e recusas são imutáveis.
    category: constraint
    applies_to: OrderEvaluation, OrderRejection, RejectionReasonDetail
    trigger: qualquer tentativa de UPDATE ou DELETE
    logic: "O papel da aplicação só tem INSERT e SELECT nessas tabelas; o banco recusa UPDATE, DELETE e TRUNCATE. No beta não há expurgo: são registros de decisões de risco ligados ao ledger e à trilha."
    violation: erro do banco; nada muda
    source: NFR4, FR2.3
```

## Resumo das regras

| Grupo | Regras | Do que trata |
|---|---|---|
| BR1 Entrada | BR1.1 a BR1.6 | Transação e tenant, validação da entrada, leituras da U4 e da U7, valor, slippage e custo estimados |
| BR2 Verificações | BR2.1 a BR2.12 | Tamanho, exposição por token e total, slippage, liquidez, frescor, intervalo, duplicidade, saldo, perda diária, drawdown e o que vale na venda |
| BR3 Resultado | BR3.1 a BR3.5 | Ordem fixa dos motivos, dado ausente, gatilhos de pausa, gravação da avaliação e da recusa com o evento |
| BR4 Leitura | BR4.1 a BR4.3 | Rota C21 por tenant e pessoa, paginação e detalhes, imutabilidade |
