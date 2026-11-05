# Regras de negócio — U5 Bots e decisão

Regras do BotManagement e do DecisionEngine. As fontes são:
- os requisitos FR3.2, FR7.1 a FR7.8, FR8.1, FR8.2, FR8.5, FR8.6, FR11.1 a FR11.3 e NFR4, NFR5 e NFR8 de [requirements.md](../../../inception/requirements-analysis/requirements.md);
- as histórias US4.1 a US4.5, US4.7 a US4.10, US4.12 e US7.2 de [stories.md](../../../inception/user-stories/stories.md), com o anexo "Transições do bot";
- os contratos C4, C7, C11, C12, C14, C15, C16, C18 e C20 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as respostas Q1 a Q13 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. Convenções:
- "Arredondar" é USDC com 6 casas, `HALF_EVEN`.
- Percentuais são pontos com 4 casas, `HALF_EVEN`.
- "Truncar" é a quantidade para baixo nas casas do mint.
- "Agora" é o relógio injetável.
- "Balde" é o balde de 5 minutos UTC da janela da C11.
- As fronteiras dos limites são inclusivas. Para os sinais, "pelo menos" quer dizer `>=`.

```yaml
rules:
  # BR1 - Assistente, rascunho e configuração
  - id: BR1.1
    statement: A pessoa cria um rascunho a partir de uma estratégia modelo; o rascunho é salvo no servidor a cada etapa.
    category: policy
    applies_to: POST /api/v1/bots, PUT /api/v1/bots/{botId}/draft
    trigger: criação e cada etapa aceita
    logic: "Criar Bot em DRAFT com templateId, versão vigente da estratégia, parâmetros padrão e draftStep 1. Cada PUT da etapa N valida só os campos da etapa e grava draftStep = máximo(draftStep, N). Voltar etapas não apaga dados. Com Idempotency-Key pelo mecanismo comum da U1."
    violation: 422 VALIDATION_FAILED com os campos
    source: FR7.2, AC4.1.3, AC4.4.1

  - id: BR1.2
    statement: Cada parâmetro da estratégia respeita a faixa e o passo da estratégia modelo.
    category: validation
    applies_to: Bot.parameters
    trigger: etapa 1 e criação
    logic: "SE o valor não é decimal OU < min OU > max OU (valor - min) não é múltiplo do passo ENTÃO recusa no campo. min e max aceitos. Os parâmetros são os de BR4.3 a BR4.6."
    violation: 422 VALIDATION_FAILED com o campo
    source: FR7.1, AC4.1.2

  - id: BR1.3
    statement: A seleção de tokens tem pelo menos um token ou uma regra; a lista tem até 10 tokens e até 5 regras.
    category: validation
    applies_to: TokenSelection
    trigger: etapa 1
    logic: "SE tokens e rules vazios ENTÃO a etapa não avança (Continuar indisponível com o motivo). Tokens sem repetição, por (chain, mint) com mint em Base58 válido. Regras com campo em [LIQUIDITY, VOLUME_24H, HOLDERS, AGE_MINUTES, RISK_SCORE], operador GTE ou LTE e valor decimal não negativo. Vindo do scanner com 'Usar em bot PAPER', o token entra na lista."
    violation: 422 VALIDATION_FAILED
    source: FR7.2, AC4.1.4, AC4.1.5, Q10

  - id: BR1.4
    statement: O saldo virtual fica entre 100 e 10.000 USDC, com padrão de 1.000.
    category: validation
    applies_to: Bot.virtualBalance
    trigger: etapa 2
    logic: "SE saldo > 10000.000000 ENTÃO 422 VIRTUAL_BALANCE_ABOVE_CAP com o teto exibido. SE saldo < 100.000000 ou <= 0 ENTÃO 422 VALIDATION_FAILED. 10000.000000 é aceito. Teto e mínimo são parâmetros da plataforma (A4) com esses valores no beta."
    violation: 422 com o teto exibido
    source: FR7.4, AC4.2.4, Q7

  - id: BR1.5
    statement: Os limites são obrigatórios, positivos e coerentes.
    category: validation
    applies_to: BotLimitsVersion
    trigger: etapa 2 e edição de limites
    logic: "Todos obrigatórios. maxOrderSize, maxTokenExposure, maxTotalExposure e maxDailyLoss > 0. maxDrawdownPct e maxSlippagePct > 0 e <= 100. minLiquidity >= 0. minIntervalSeconds >= 1. SE maxTokenExposure > maxTotalExposure OU maxOrderSize > maxTokenExposure ENTÃO 422 LIMIT_INCOHERENT no campo. maxTokenExposure = maxTotalExposure é aceito; maxTotalExposure + 0,000001 recusa."
    violation: 422 LIMIT_INCOHERENT ou VALIDATION_FAILED com o campo
    source: FR7.3, AC4.2.2, AC4.2.3, AC4.9.3

  - id: BR1.6
    statement: Os limites padrão do assistente são proporcionais ao saldo virtual e editáveis.
    category: calculation
    applies_to: etapa 2 do assistente
    trigger: abertura da etapa 2 e mudança do saldo antes de a pessoa editar o limite
    logic: "maxOrderSize = arredondar(saldo × 10%); maxTokenExposure = arredondar(saldo × 20%); maxTotalExposure = arredondar(saldo × 50%); maxDailyLoss = arredondar(saldo × 5%); maxDrawdownPct = 10.0000; maxSlippagePct = 2.0000; minLiquidity = 10000.000000; minIntervalSeconds = 240. Um limite que a pessoa editou não é recalculado. Cada limite aparece com rótulo, unidade, padrão e uma frase sobre o que impede."
    violation: não se aplica
    source: AC4.2.1, Q8, Q13

  - id: BR1.7
    statement: O rascunho vira VALIDATED quando as etapas 1 e 2 estão válidas; a etapa 3 mostra a revisão completa.
    category: policy
    applies_to: Bot
    trigger: PUT da etapa 3
    logic: "SE parâmetros, seleção, saldo e limites válidos ENTÃO grava a versão 1 dos limites e o bot passa a VALIDATED. A revisão mostra estratégia, parâmetros, limites, saldo e as hipóteses da simulação (política vigente da U7) antes de 'Iniciar bot PAPER'. Editar um VALIDATED volta-o a DRAFT."
    violation: 422 com os campos pendentes
    source: FR7.2, FR7.5, AC4.3.1

  # BR2 - Ciclo de vida
  - id: BR2.1
    statement: Só as transições do anexo são aceitas.
    category: constraint
    applies_to: Bot.state
    trigger: todo comando e toda mudança automática
    logic: "DRAFT->VALIDATED (etapas válidas); VALIDATED->ACTIVE (start); ACTIVE->PAUSED (pause, perda diária, drawdown, sistema); PAUSED->ACTIVE (resume ou meia-noite, sem motivo restante); ACTIVE ou PAUSED->STOPPED ou CLOSING_PENDING (stop); CLOSING_PENDING->STOPPED (fechamento concluído pela U7). Toda outra combinação é 409 INVALID_TRANSITION sem mudança nem evento. Teste percorre todas as combinações."
    violation: 409 INVALID_TRANSITION
    source: FR7.5, AC4.4.3, AC4.12.5

  - id: BR2.2
    statement: Iniciar exige menos de 3 bots ativos da pessoa, inclusive com ativações simultâneas, e fundeia o saldo uma única vez.
    category: constraint
    applies_to: command start
    trigger: start de um VALIDATED
    logic: "Numa transação, na ordem de travas da BR2.11: travar BotOwner da pessoa, ler SystemPauseState com trava compartilhada, travar o bot, contar ACTIVE da pessoa. SE SystemPauseState.active ENTÃO 409 PAUSED_BY_SYSTEM. SE já há 3 ENTÃO 409 ACTIVE_BOT_LIMIT com '3 de 3 bots ativos' e o rascunho continua. SENÃO bot ACTIVE, activatedAt = agora e, na primeira ativação, PaperExecution.fund(botId, virtualBalance, chave 'fund:<botId>') na mesma transação; Funded ou Duplicate segue, Conflict é falha técnica. Mesma Idempotency-Key devolve o mesmo resultado (uma transição, um evento BOT_STARTED)."
    violation: 409 ACTIVE_BOT_LIMIT ou PAUSED_BY_SYSTEM
    source: FR7.6, AC4.3.2, AC4.3.3, AC7.2.3, C15

  - id: BR2.3
    statement: Pausar pela pessoa é um comando único, sem confirmação, que impede novas operações.
    category: policy
    applies_to: command pause
    trigger: pause de um ACTIVE ou PAUSED
    logic: "Trava a linha do bot (espera um ciclo em andamento terminar), acrescenta PERSON a pauseReasons, estado PAUSED, pausedAt = agora, evento BOT_PAUSED com o motivo. Pausar um bot já pausado pela pessoa é idempotente (sem segundo evento). A mesma Idempotency-Key gera uma transição e um evento."
    violation: 409 INVALID_TRANSITION fora de ACTIVE ou PAUSED
    source: FR11.1, AC4.8.1, AC4.8.3

  - id: BR2.4
    statement: Retomar exige que não reste motivo de pausa, sem kill switch, até 3 ativos e, depois da perda diária, limite maior que a perda acumulada.
    category: constraint
    applies_to: command resume
    trigger: resume de um PAUSED
    logic: "Na ordem da BR2.11: BotOwner, SystemPauseState (compartilhada), bot. SE SYSTEM em pauseReasons ou SystemPauseState.active ENTÃO 409 PAUSED_BY_SYSTEM. SE DAILY_LOSS em pauseReasons E maxDailyLoss da versão vigente <= perda realizada do dia UTC ENTÃO 409 com o motivo e o valor da perda. A perda vem de PortfolioRiskView.snapshot (C13), com o Principal da própria pessoa que pede (o dono) e um token qualquer do bot, porque a perda do dia não depende do token: o primeiro da lista explícita, senão o primeiro com posição, senão o token neutro solana:11111111111111111111111111111111, que nunca tem posição. SE 3 ativos ENTÃO 409 ACTIVE_BOT_LIMIT. SENÃO remove PERSON, ADMIN_RELEASED, WAITING_ACTIVE_SLOT, DAILY_LOSS (quando o limite novo o permite) e DRAWDOWN (Q9: livre; se o drawdown continuar acima do limite, a U6 indica a pausa de novo na primeira ordem) e, sem motivo restante, ACTIVE com evento BOT_RESUMED."
    violation: 409 PAUSED_BY_SYSTEM, ACTIVE_BOT_LIMIT ou motivo de perda diária
    source: FR7.6, AC4.7.5, AC4.8.4, AC7.2.3, AC7.2.4, Q9

  - id: BR2.5
    statement: Os motivos de pausa se acumulam e cada um é removido só pela sua regra.
    category: policy
    applies_to: Bot.pauseReasons
    trigger: toda pausa e retomada
    logic: "PERSON: entra por pause, sai por resume. DAILY_LOSS e DRAWDOWN: entram pelos gatilhos da U6 no ciclo; DAILY_LOSS sai à meia-noite UTC (BR2.6) ou por resume com limite maior (BR2.4); DRAWDOWN sai por resume (BR2.4). SYSTEM: entra pelo kill switch e, ao desligá-lo, vira ADMIN_RELEASED (BR7.2), que sai por resume. WAITING_ACTIVE_SLOT: entra à meia-noite quando não há vaga (BR2.6), sai por resume. O bot é ACTIVE só sem nenhum motivo."
    violation: não se aplica
    source: FR8.5, AC4.7.4, US7.2

  - id: BR2.6
    statement: À meia-noite UTC sai só o motivo perda diária; o bot volta a ACTIVE se nada mais o prende e há vaga.
    category: policy
    applies_to: tarefa da meia-noite UTC (worker)
    trigger: 00:00:00 UTC de cada dia
    logic: "Para cada bot com DAILY_LOSS, numa transação por bot e na ordem da BR2.11 (BotOwner, depois o bot), contando os ativos sob a trava: remover DAILY_LOSS (evento BOT_DAILY_LOSS_CLEARED, ator system). SE não resta motivo E a pessoa tem menos de 3 ativos ENTÃO ACTIVE (BOT_RESUMED, ator system). SE restam outros motivos ENTÃO continua PAUSED com eles. SE não resta motivo mas já há 3 ativos ENTÃO acrescenta WAITING_ACTIVE_SLOT (acréscimo à C4) e continua PAUSED; a tela mostra 'não voltou a operar porque já há 3 bots ativos; retome quando houver vaga'. A tarefa não tenta de novo depois: a pessoa retoma (BR2.4). Às 23:59:59 nada muda. Execução única pelo ShedLock; repetível sem efeito duplo."
    violation: não se aplica
    source: FR8.5, AC4.7.3, AC4.7.4, Q11 das histórias

  - id: BR2.7
    statement: Encerrar pede confirmação, fecha as posições pela U7 e termina em STOPPED ou CLOSING_PENDING.
    category: policy
    applies_to: command stop
    trigger: stop de ACTIVE ou PAUSED, depois da confirmação na tela
    logic: "Trava o bot. Chama PaperExecution.closePositions(botId, versão vigente, chave 'close:<botId>') na transação. Closed sem pending: STOPPED, stoppedAt, evento BOT_STOPPED. Closed com pending: CLOSING_PENDING, evento BOT_CLOSING_PENDING; nenhuma decisão nova. Refused ou Conflict: falha técnica (desfaz, mensagem 'tentar de novo'). Os motivos de pausa são apagados ao sair de PAUSED."
    violation: 409 INVALID_TRANSITION fora de ACTIVE ou PAUSED
    source: FR11.2, AC4.12.1, AC4.12.2, AC4.12.3

  - id: BR2.8
    statement: Um bot CLOSING_PENDING passa a STOPPED quando a U7 conclui o fechamento.
    category: policy
    applies_to: tarefa de conferência do encerramento (worker)
    trigger: a cada fatia de 5 minutos, depois dos ciclos
    logic: "Para cada CLOSING_PENDING, numa transação com o bot travado: ler o estado da conta PAPER pela interface pública da U7, LedgerReadModel.account(botId).status() (só leitura; não repetir closePositions, cuja resposta fica guardada pela idempotência e nunca muda). SE status = CLOSED ENTÃO STOPPED e evento BOT_STOPPED (ator system); senão nada muda. Conferir que tenant e pessoa da conta são os do bot. Uma vez só, mesmo com execuções paralelas (trava do bot)."
    violation: não se aplica
    source: AC4.12.3, AC4.12.4, C15

  - id: BR2.9
    statement: Não existe transferência de saldo, mudança de modo nem ordem REAL.
    category: authorization
    applies_to: rotas C4
    trigger: qualquer requisição
    logic: "Nenhuma rota transfere saldo nem muda modo. Corpo ou query com mode diferente de PAPER: 403 REAL_MODE_UNAVAILABLE auditado (regra comum). Nenhum código da U5 assina ou envia transação."
    violation: 403 REAL_MODE_UNAVAILABLE, auditado
    source: AC4.2.5, AC4.3.4, AC4.3.5, FR15.2

  - id: BR2.11
    statement: Todas as operações travam na mesma ordem global, para não haver deadlock.
    category: constraint
    applies_to: comandos, ciclo, meia-noite, pausa de sistema, conferência do encerramento
    trigger: toda transação que trava mais de uma linha
    logic: "Ordem única: 1) SystemPauseState (exclusiva só em applySystemPause e liftSystemPause; compartilhada em start e resume); 2) BotOwner da pessoa (só quem conta ativos: start, resume, meia-noite); 3) a linha do bot. O ciclo, pause, stop, edição de limites, pausa de sistema por bot e conferência do encerramento travam só o bot. Ninguém trava BotOwner depois do bot. A marca da C18 não trava linha da pessoa (BR8.1). Teste com ciclo e resume do mesmo bot em paralelo, 50 repetições, sem deadlock."
    violation: não se aplica
    source: C14, C15, revisão do Functional Design

  - id: BR2.10
    statement: O bot é da pessoa dona; nenhum outro membro do tenant o vê.
    category: authorization
    applies_to: rotas C4, C7, tarefas
    trigger: toda leitura e escrita
    logic: "Filtrar por tenantId e ownerUserId do token. Bot de outra pessoa ou de outro tenant: 404 NOT_FOUND, sem dado. As tarefas (ciclo, meia-noite, encerramento) leem tenant e pessoa da linha do bot e ligam o Principal do dono antes de chamar U6 e U7."
    violation: 404 NOT_FOUND
    source: DP-1, NFR5, C15

  # BR3 - Ciclo
  - id: BR3.1
    statement: Cada bot ativo tem um ciclo por fatia de 5 minutos, logo depois da coleta de mercado.
    category: policy
    applies_to: agendador do ciclo (worker)
    trigger: início de cada fatia de 5 minutos UTC mais 60 s
    logic: "slotStart = início da fatia. Para cada bot ACTIVE, o agendador abre o ciclo. Os bots são processados em paralelo limitado, cada um na sua transação. A fatia vale até slotStart + 5 min. Os 60 s de atraso deixam o balde que fechou em slotStart com a coleta da U4 feita (a coleta roda a cada 30 s, padrão MARKET_COLLECTION_INTERVAL). O preço de referência que a U6 confere tem então no máximo cerca de 30 s, dentro dos 60 s da barreira, e a idade é sempre medida do horário de observação do valor, nunca do início do balde."
    violation: não se aplica
    source: AC4.5.1, Q6

  - id: BR3.2
    statement: Uma fatia é processada no máximo uma vez por bot e nunca em atraso.
    category: constraint
    applies_to: BotCycle
    trigger: abertura do ciclo
    logic: "No início da transação do ciclo, inserir BotCycle RUNNING com (botId, slotStart) único (inserção que não aborta em conflito; espera um concorrente terminar); SE já existe ENTÃO não processa. No fim, a mesma linha recebe o status final e finishedAt. SE agora > slotStart + 5 min ENTÃO grava SKIPPED/SLOT_EXPIRED e não decide. Depois de reinício, o worker processa só a fatia corrente; fatias perdidas não são executadas."
    violation: ciclo pulado
    source: AC4.5.4, C20

  - id: BR3.3
    statement: O ciclo trava o bot, confere ACTIVE sem motivo de pausa e só então decide.
    category: constraint
    applies_to: transação do ciclo
    trigger: cada ciclo
    logic: "Transação READ COMMITTED; SET LOCAL lock_timeout = '2s' e statement_timeout = '5s' no início (para valerem também na barreira da U6); SELECT ... FOR UPDATE só da linha do bot (BR2.11); SE state != ACTIVE OU pauseReasons não vazio OU SystemPauseState.active (leitura sem trava, defesa extra) ENTÃO BotCycle SKIPPED (NOT_ACTIVE ou PAUSED), nenhuma chamada a U6 ou U7. Ligar o Principal do dono (C10)."
    violation: ciclo pulado sem operação
    source: AC4.8.2, AC7.2.2, C14, C15

  - id: BR3.4
    statement: HOLD e NO_TRADE terminam o ciclo sem ordem; BUY e SELL passam pela barreira e, aprovados, pela execução.
    category: policy
    applies_to: transação do ciclo
    trigger: decisão gravada
    logic: "Gravar a Decision. HOLD/NO_TRADE: outcome NO_ORDER. BUY/SELL: OrderGate.evaluate(CandidateOrder(botId, decisionId, side, chain, mint, requestedQuantity), limites vigentes). Rejected: outcome REJECTED; para cada pauseTrigger, acrescentar DAILY_LOSS ou DRAWDOWN e estado PAUSED (evento BOT_PAUSED, ator system). Approved: PaperExecution.execute(PaperOrderRequest(..., limitsVersion), chave 'order:<decisionId>'); Filled: outcome FILLED e BotHolding atualizada; NotFilled: outcome NOT_FILLED, sem repetir a decisão; Duplicate: DUPLICATE; Conflict: falha técnica."
    violation: não se aplica
    source: FR8.3, FR8.4, AC4.6.1, AC4.7.1, AC4.7.2, C14, C15

  - id: BR3.5
    statement: Uma falha técnica desfaz o ciclo inteiro, sem nova tentativa na fatia.
    category: policy
    applies_to: transação do ciclo
    trigger: exceção de C11, C12, C13, C14, C15 ou do banco
    logic: "Rollback da transação do ciclo (decisão, recusa, ordem e mudanças de estado). Numa transação separada, gravar BotCycle FAILED com a causa curta. A fatia não é repetida; a próxima fatia decide de novo."
    violation: ciclo FAILED
    source: C15 (regras), NFR10

  - id: BR3.6
    statement: O bot roda no servidor sem depender da sessão da pessoa.
    category: policy
    applies_to: agendador do ciclo
    trigger: sessão expirada ou encerrada
    logic: "O ciclo usa o Principal do dono montado a partir da linha do bot, nunca a sessão. Sessão expirada não pausa o bot."
    violation: não se aplica
    source: FR11.3, AC4.5.4

  - id: BR3.7
    statement: A posição aberta de cada token tem o horário de abertura guardado pela U5.
    category: policy
    applies_to: BotHolding
    trigger: resultado Filled no ciclo e leitura das posições
    logic: "BUY Filled (inclusive PARTIALLY_FILLED) com BotHolding inexistente: criar com openedAt = agora. Depois de um SELL Filled, ler a posição do token (LedgerReadModel.positions) e apagar BotHolding só se a quantidade restante é zero; num SELL parcial, a abertura fica. A cada ciclo, uma BotHolding cujo token não tem posição na U7 é apagada. Uma posição da U7 sem BotHolding usa updatedAt da posição."
    violation: não se aplica
    source: Q3

  # BR4 - Decisão
  - id: BR4.1
    statement: A decisão usa dados conhecidos e frescos e registra cada entrada com fonte e horário.
    category: validation
    applies_to: DecisionEngine
    trigger: cada token avaliado
    logic: "Ler a janela da C11 do tamanho exigido pela estratégia e o preço de referência atual. Posições (quantidade e costBasis) e saldo disponível vêm da interface pública da U7 LedgerReadModel (positions(botId) e balance(botId)), dentro da transação do ciclo; a U5 passa a ser consumidora registrada dessa interface (mudança de contrato). As saídas TP, SL e MAX_HOLD precisam só do preço de referência fresco; o sinal de compra e RANGE_EXIT precisam da janela. SE algum balde necessário é UNKNOWN ENTÃO motivo UNKNOWN_DATA. SE a janela não cobre o tamanho pedido (menos baldes que o necessário) ENTÃO INSUFFICIENT_HISTORY. SE o último balde ou o preço atual tem mais de 5 minutos ENTÃO STALE_DATA. SE a avaliação da C12 está indisponível ENTÃO ASSESSMENT_UNAVAILABLE. O token com motivo não gera sinal."
    violation: token sem sinal, com o motivo
    source: FR8.1, FR8.2, AC4.5.3

  - id: BR4.2
    statement: A decisão é determinística, versionada e não usa IA.
    category: constraint
    applies_to: DecisionEngine
    trigger: toda decisão
    logic: "Função pura de (parâmetros, versão da estratégia, limites, entradas registradas, posições, saldo, instante). Recalcular com as entradas gravadas dá a mesma decisão. Nenhum componente de modelo de IA é carregado."
    violation: não se aplica
    source: FR8.1, FR8.6, AC4.5.2, AC4.5.5, NFR8

  - id: BR4.3
    statement: Momentum de volume compra quando o volume de 24 h e o preço crescem pelo menos o mínimo na janela.
    category: calculation
    applies_to: estratégia MOMENTUM_VOLUME v1
    trigger: token sem posição e sem motivo de BR4.1
    logic: "k = janela ÷ 5 baldes; b_n = último balde fechado, b_0 = b_{n-k}. crescimentoVolume% = arredondar4((vol(b_n) - vol(b_0)) ÷ vol(b_0) × 100); altaPreço% = arredondar4((preço(b_n) - preço(b_0)) ÷ preço(b_0) × 100). Sinal BUY SE crescimentoVolume% >= volumeGrowthMinPct E altaPreço% >= priceRiseMinPct. Força = mínimo(crescimentoVolume% ÷ volumeGrowthMinPct, altaPreço% ÷ priceRiseMinPct). vol(b_0) ou preço(b_0) zero: UNKNOWN_DATA. Leitura do sinal (registrada por pedido da revisão): cada balde traz o volume móvel de 24 h, então o crescimento mede quanto a soma de 24 h aumentou na janela (volume novo da janela menos o que saiu da soma). Com o padrão de 20%, o volume da janela precisa ser da ordem de um quinto do dia; é um filtro de aceleração forte e o bot compra raramente. A descrição da estratégia na tela explica isso, e os dados simulados da U4 têm um cenário que dispara o sinal para os testes de ponta a ponta. Parâmetros: windowMinutes 15 (10..60, passo 5); volumeGrowthMinPct 20.0000 (5..200, passo 1); priceRiseMinPct 2.0000 (0,5..20, passo 0,5)."
    violation: não se aplica (cálculo)
    source: FR7.1, Q1

  - id: BR4.4
    statement: Rompimento de faixa compra quando o último preço passa da máxima da faixa mais a margem.
    category: calculation
    applies_to: estratégia RANGE_BREAKOUT v1
    trigger: token sem posição e sem motivo de BR4.1
    logic: "m = janela ÷ 5; faixa = máxima e mínima dos preços de b_{n-m} a b_{n-1}. Sinal BUY SE preço(b_n) >= máxima × (1 + breakoutMarginPct ÷ 100). Força = preço(b_n) ÷ (máxima × (1 + margem ÷ 100)). Parâmetros: windowMinutes 120 (30..720, passo 30); breakoutMarginPct 1.0000 (0,1..10, passo 0,1)."
    violation: não se aplica (cálculo)
    source: FR7.1, Q2

  - id: BR4.5
    statement: A posição de um token é vendida inteira ao atingir o alvo, o stop ou o tempo máximo; no rompimento, também ao voltar abaixo da faixa.
    category: calculation
    applies_to: as duas estratégias
    trigger: token com posição aberta (quantidade da U7 > 0)
    logic: "preçoMédio = costBasis ÷ quantity (12 casas). variação% = arredondar4((referência - preçoMédio) ÷ preçoMédio × 100). SELL TAKE_PROFIT SE variação% >= takeProfitPct; STOP_LOSS SE variação% <= -stopLossPct; MAX_HOLD SE agora - openedAt >= maxHoldMinutes; RANGE_EXIT (só RANGE_BREAKOUT) SE preço(b_n) < mínima da faixa. Nesta ordem de prioridade. requestedQuantity = quantidade inteira da posição. Sem preço de referência fresco: NO_TRADE STALE_DATA para o token (a posição fica). Com a janela UNKNOWN ou insuficiente, TP, SL e MAX_HOLD ainda são avaliados; só RANGE_EXIT fica de fora. Parâmetros comuns: takeProfitPct 10.0000 (1..100, passo 0,5); stopLossPct 5.0000 (1..50, passo 0,5); maxHoldMinutes 240 (15..1440, passo 15)."
    violation: não se aplica (cálculo)
    source: FR7.1, Q3

  - id: BR4.6
    statement: A compra usa uma fração do saldo disponível, limitada ao tamanho máximo por ordem.
    category: calculation
    applies_to: decisão BUY
    trigger: sinal BUY escolhido
    logic: "valor = mínimo(arredondar(saldo disponível (C13) × buyFractionPct ÷ 100), maxOrderSize). requestedQuantity = truncar(valor ÷ preço de referência). SE requestedQuantity = 0 ENTÃO NO_TRADE QUANTITY_BELOW_MINIMUM. Parâmetro buyFractionPct 10.0000 (1..100, passo 1). A barreira da U6 continua conferindo custo, saldo e limites."
    violation: NO_TRADE com motivo
    source: Q4

  - id: BR4.7
    statement: A compra exige avaliação de risco na faixa LOW ou MEDIUM.
    category: constraint
    applies_to: decisão BUY
    trigger: sinal BUY
    logic: "SE a avaliação da C12 é HIGH ENTÃO o token não gera BUY e o motivo é RISK_BAND_HIGH. Indisponível já é ASSESSMENT_UNAVAILABLE (BR4.1). A faixa entra nos fatores."
    violation: token sem compra, com o motivo
    source: Q11, FR6.2

  - id: BR4.8
    statement: Com vários tokens, a decisão segue a prioridade fixa: saída, depois a compra mais forte, depois HOLD ou NO_TRADE.
    category: policy
    applies_to: DecisionEngine
    trigger: cada ciclo
    logic: "Tokens = lista explícita mais os aprovados pelos filtros (BR4.9), sem repetição, mais os tokens com posição aberta. 1) SE algum token tem saída (BR4.5) ENTÃO SELL do de posição mais antiga (openedAt). 2) SENÃO SE algum tem sinal BUY ENTÃO BUY do de maior força; empate pelo mint em ordem alfabética. 3) SENÃO HOLD se há posição aberta, NO_TRADE se não há, com o motivo do primeiro token avaliado em ordem da lista (NO_SIGNAL quando nenhum tem motivo de dado). Sem token: NO_TRADE NO_TOKENS. Todos os tokens avaliados entram nos fatores."
    violation: não se aplica
    source: AC4.5.1, Q5

  - id: BR4.9
    statement: Os filtros da seleção são avaliados a cada ciclo; token com campo filtrado unknown fica fora; no máximo 10 tokens.
    category: policy
    applies_to: TokenSelection
    trigger: cada ciclo
    logic: "Aplicar as regras sobre os dados do scanner (C2, pela interface da U4), fronteiras inclusivas. Campo UNKNOWN: fora. Resultado ordenado por liquidez decrescente e limitado a 10, somado à lista explícita (lista primeiro, total até 10)."
    violation: não se aplica
    source: Q10, AC2.2.1

  - id: BR4.10
    statement: A decisão grava fatores, entradas, validade e uma frase simples.
    category: policy
    applies_to: Decision
    trigger: toda decisão
    logic: "validUntil = slotStart + 5 min. inputs guarda os MarketValue usados; context guarda saldo disponível, posições (quantidade, costBasis, openedAt) e faixa de risco por token, para o recálculo da BR4.2. Frase em pt-BR com a ação e o motivo principal (por exemplo 'Comprar BONK: volume +32% e preço +2,4% em 15 min'). SELL sem posição nunca é emitido (vira NO_TRADE NO_POSITION_TO_SELL). Evento DECISION_RECORDED não é publicado na trilha (decisões têm tabela própria)."
    violation: não se aplica
    source: FR8.1, AC4.5.1, AC4.5.3

  # BR5 - Limites
  - id: BR5.1
    statement: Editar limites cria uma nova versão que vale só para decisões futuras.
    category: policy
    applies_to: POST /api/v1/bots/{botId}/limits
    trigger: bot ACTIVE ou PAUSED
    logic: "Validar por BR1.5. Gravar BotLimitsVersion versão+1 e currentLimitsVersion na transação, com o bot travado (um ciclo em andamento termina com a versão antiga). Evento BOT_LIMITS_CHANGED. Ordens já registradas mantêm a versão delas (a U7 grava limitsVersion)."
    violation: 422 LIMIT_INCOHERENT; 409 em outro estado
    source: FR7.8, AC4.9.1, AC4.9.2, AC4.9.3, AC4.7.5

  # BR6 - Leituras e telas
  - id: BR6.1
    statement: A lista mostra todos os bots da pessoa com estado em texto, ações válidas e o contador de ativos.
    category: policy
    applies_to: GET /api/v1/bots
    trigger: leitura
    logic: "Filtrar por pessoa; opcional por estado; cursor. activeCount e activeLimit 3. Cada item com estado, motivos de pausa, resumesAt (meia-noite UTC seguinte quando DAILY_LOSS é o único motivo) e draftStep."
    violation: 400 VALIDATION_FAILED
    source: FR7.7, AC4.4.2, AC4.7.6

  - id: BR6.2
    statement: A tela do bot mostra estado, limites, uso do dia, operações, recusas e decisões; o estado exibido é sempre o último confirmado.
    category: policy
    applies_to: tela S5
    trigger: abertura e atualização
    logic: "Uso do dia = exposição total de maxTotalExposure e perda do dia de maxDailyLoss (C5 day-usage da U7). Operações pela C5 (cada uma uma vez). Recusas pela C21: 404 vira 'nenhuma recusa'; DUPLICATE aparece como 'decisão já avaliada'. Decisões pela C4. Sem operações: 'Nenhuma operação simulada até agora' e as regras em uso. Falha ao pausar ou encerrar: mensagem com 'Tentar de novo' e o estado do servidor. Bot pausado com posições abertas: as posições aparecem marcadas a mercado (C5) com o aviso 'as posições não serão fechadas enquanto o bot estiver pausado' (AC4.8.5). Falha ao iniciar: o bot continua em rascunho ou VALIDATED, a mensagem diz como tentar de novo e, durante o pedido, a tela mostra 'Iniciando bot PAPER' sem aceitar novo envio (AC4.3.6)."
    violation: não se aplica
    source: AC4.10.1 a AC4.10.4, AC4.10.6

  - id: BR6.3
    statement: Com dados de mercado desatualizados, a tela avisa que o bot pode ficar sem operar.
    category: policy
    applies_to: tela S5
    trigger: estado das fontes DEGRADED ou FAILED ou última decisão STALE_DATA
    logic: "Mostrar o aviso em texto, com o horário da última atualização da fonte."
    violation: não se aplica
    source: AC4.10.5

  - id: BR6.4
    statement: Toda tela de bot mostra 'MODO PAPER (simulado)' no título e motivos de pausa em texto, com o horário de reinício no fuso da pessoa.
    category: policy
    applies_to: telas S4, S5 e lista
    trigger: exibição
    logic: "Rótulo no título. Motivo em texto (pessoa, perda diária, drawdown, pausado pela administração, sem vaga de bot ativo). Com DAILY_LOSS, 'volta a operar às HH:MM, <fuso>' calculado de resumesAt."
    violation: não se aplica
    source: DP-4, AC4.7.6, AC7.2.4

  # BR7 - Administração (C16)
  - id: BR7.1
    statement: A pausa de sistema acrescenta SYSTEM a todos os bots ativos e pausados, de forma repetível.
    category: policy
    applies_to: BotAdministration.applySystemPause
    trigger: kill switch ligado pela U8
    logic: "Primeiro, numa transação própria, travar SystemPauseState e gravar active = true (a partir daí start e resume são recusados e o ciclo pula). Depois, em lotes que selecionam os bots ACTIVE e PAUSED já com o estado confirmado (um start que confirmou antes entra no lote), cada bot na sua transação com a trava do bot: acrescentar SYSTEM (ACTIVE vira PAUSED). Bot que já tem SYSTEM: sem mudança nem evento. Devolver quantos pausou e quais falharam; a U8 repete até não haver falha. Um ciclo em andamento termina antes; o seguinte vê o motivo (AC7.2.2). Evento BOT_PAUSED com motivo SYSTEM e ator system por bot."
    violation: não se aplica
    source: FR3.2, AC7.2.2, ADR-007

  - id: BR7.2
    statement: Desligar o kill switch tira SYSTEM sem retomar bots.
    category: policy
    applies_to: BotAdministration.liftSystemPause
    trigger: kill switch desligado
    logic: "Primeiro gravar SystemPauseState.active = false (transação própria, linha travada). Depois, em lotes, cada bot na sua transação com a trava: trocar SYSTEM por ADMIN_RELEASED, um motivo novo (acréscimo à enum PauseReason da C4). Ele significa 'pausado pela administração; o kill switch foi desligado'. O bot continua PAUSED. ADMIN_RELEASED não impede a retomada e sai no resume do dono (BR2.4), e a tela o mostra em texto. Nenhum bot é retomado automaticamente. Repetível: um bot sem SYSTEM não muda. Evento BOT_SYSTEM_PAUSE_LIFTED por bot, ator system."
    violation: não se aplica
    source: AC7.2.4, ADR-007

  - id: BR7.3
    statement: As contagens e o resumo para o painel e a administração não expõem ledger, operações nem carteira.
    category: authorization
    applies_to: BotAdministration.countByState, summaryFor
    trigger: chamada
    logic: "countByState: contagem por estado de todos os tenants, sem identificador. summaryFor(principal): só os bots da pessoa (ativos, pausados, última decisão)."
    violation: não se aplica
    source: AC7.4.1, AC7.4.2, C16

  # BR8 - Canal ao vivo e marcas
  - id: BR8.1
    statement: Toda mudança de estado, motivo de pausa ou limites renova a marca do bot na mesma transação, sem travar linha da pessoa.
    category: policy
    applies_to: Bot.changeSeq, BotChangeMarks
    trigger: comandos, ciclo, meia-noite, pausa de sistema
    logic: "Bot.changeSeq = nextval da sequência global, na mesma gravação do bot. BotChangeMarks.markFor(principal) = máximo de changeSeq dos bots da pessoa (índice por tenant, pessoa, changeSeq). Cresce sempre porque a sequência só cresce e bots nunca são apagados."
    violation: não se aplica
    source: C18

  - id: BR8.2
    statement: O canal C7 compara as marcas a cada 2 s e envia os eventos da pessoa do token.
    category: policy
    applies_to: GET /api/v1/stream/dashboard
    trigger: canal aberto
    logic: "A cada 2 s, ler BotChangeMarks, LedgerChangeMarks (U7) e SourceStatusMarks (U4) da pessoa. Quando uma muda, buscar os detalhes pelas interfaces públicas e enviar bot.state, trade.recorded, pnl.updated ou data.status. heartbeat a cada 15 s. Token expirado: auth.expired e fecha. Last-Event-ID retoma sem repetir. Sempre filtrado por tenant e pessoa."
    violation: 401 UNAUTHENTICATED
    source: C7, FR12.2

  # BR9 - Guarda
  - id: BR9.1
    statement: HOLD e NO_TRADE com mais de 30 dias são apagados; BUY e SELL ficam durante todo o beta.
    category: policy
    applies_to: Decision, BotCycle
    trigger: tarefa diária
    logic: "Apagar Decision com action em [HOLD, NO_TRADE] e createdAt anterior a agora - 30 dias, e os BotCycle com slotStart também anterior a agora - 30 dias que não têm decisão ou têm essas decisões, em lotes. A fatia corrente nunca é tocada. HOLD e NO_TRADE nunca são citadas por recusa da U6 nem por ordem da U7 (só BUY e SELL viram ordem candidata), então nenhuma referência se perde. Execução única pelo ShedLock."
    violation: não se aplica
    source: Q12
```

## Resumo das regras

| Grupo | Regras | Do que trata |
|---|---|---|
| BR1 Assistente | BR1.1 a BR1.7 | Rascunho, parâmetros, seleção de tokens, saldo, limites coerentes e padrões, validação |
| BR2 Ciclo de vida | BR2.1 a BR2.11 | Transições, iniciar com limite de 3, pausar, retomar, motivos, meia-noite, encerrar, fechamento pendente, PAPER, dono |
| BR3 Ciclo | BR3.1 a BR3.7 | Fatias de 5 min, sem repetição, trava, barreira e execução, falha técnica, servidor, abertura da posição |
| BR4 Decisão | BR4.1 a BR4.10 | Dados frescos, determinismo, as duas estratégias, saídas, tamanho, risco, prioridade, filtros, registro |
| BR5 Limites | BR5.1 | Nova versão para decisões futuras |
| BR6 Telas | BR6.1 a BR6.4 | Lista, tela do bot, aviso de dados, rótulo PAPER e motivos |
| BR7 Administração | BR7.1 a BR7.3 | Pausa e liberação do sistema, contagens |
| BR8 Canal ao vivo | BR8.1, BR8.2 | Marcas e canal C7 |
| BR9 Guarda | BR9.1 | Expurgo de HOLD e NO_TRADE com mais de 30 dias |
