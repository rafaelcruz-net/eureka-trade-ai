# Regras de negócio — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

O bloco `yaml` abaixo é a fonte de verdade das regras da U1. O resumo em `functional-spec.md` é derivado dele. Cada regra tem um id `BRx.y`, a categoria (`validation`, `authorization`, `constraint`, `calculation` ou `policy`), onde se aplica, o gatilho, a lógica em SE…ENTÃO, o comportamento na violação e a origem.

Grupos: BR1 criação e modo; BR2 elegibilidade do REAL; BR3 limites REAL da conta; BR4 diretório e lista; BR5 visão por modo; BR6 aviso e textos; BR7 formatos; BR8 isolamento; BR9 acessibilidade e telas; BR10 entrega e verificação.

```yaml
rules:
  # ---------------- BR1 — Criação e modo ----------------
  - id: BR1.1
    statement: O modo do bot é escolhido no pedido de criação; sem o campo, o bot nasce PAPER.
    category: validation
    applies_to: [Bot, "POST /api/v1/bots (C1)"]
    trigger: comando de criação de bot
    logic: >
      SE market está ausente ENTÃO market = PAPER. SE market é PAPER ou REAL ENTÃO o bot nasce nesse
      modo. SE market tem outro valor ENTÃO a criação é recusada.
    violation: 422 VALIDATION_FAILED no campo market; nenhum bot é criado.
    source: [FR1.1, FR2.4, Q1 do contract design]

  - id: BR1.2
    statement: O modo de um bot nunca muda depois da criação, e nenhum bot é convertido de um modo para o outro.
    category: constraint
    applies_to: [Bot]
    trigger: qualquer rota de edição ou comando sobre um bot existente
    logic: >
      SE um pedido de edição traz market ENTÃO é recusado: nenhuma rota de edição aceita o campo (o corpo
      recusa campos não declarados). SE qualquer gravação tentar mudar market ENTÃO a defesa do banco
      recusa a gravação.
    violation: 400 VALIDATION_FAILED (campo não declarado); o bot continua no modo original.
    source: [FR1.4]

  - id: BR1.3
    statement: Toda resposta que traz um bot tem mode igual a market; mode continua presente e marcado como obsoleto.
    category: constraint
    applies_to: [Bot, BotSummary, BotDetail, "lista de bots", "canal ao vivo do painel"]
    trigger: qualquer resposta com um bot
    logic: >
      mode = market, nos dois mercados. Nenhum campo lido pelos clientes atuais é removido nem
      renomeado. O teste de contrato confere mode == market para PAPER e REAL e que mode tem a marca de
      obsoleto.
    violation: o teste de contrato falha e o commit não entra.
    source: [FR2.4, ADR-009]

  - id: BR1.4
    statement: Dois pedidos de criação com a mesma chave de idempotência criam no máximo um bot.
    category: constraint
    applies_to: ["POST /api/v1/bots (C1)"]
    trigger: repetição do comando de criação
    logic: >
      SE chega um pedido com uma chave já concluída e o mesmo corpo ENTÃO a resposta guardada é
      devolvida, sem nova criação e sem reavaliar a elegibilidade. SE chega a mesma chave com outro
      corpo ENTÃO 409 IDEMPOTENCY_CONFLICT, como hoje. Uma recusa (409 REAL_NOT_ELIGIBLE, 422) e um
      503 não são guardados como resposta idempotente, porque o comando é desfeito: a repetição com a
      mesma chave reavalia tudo, inclusive a elegibilidade. Usa o mecanismo comum de idempotência do
      módulo de identidade.
    violation: nenhuma criação duplicada.
    source: [FR1.3, AC1.3.5, project.md (ALWAYS deduplicar)]

  - id: BR1.5
    statement: Um bot REAL só é pedido depois da confirmação explícita "Este bot opera com dinheiro real".
    category: validation
    applies_to: [RealConfirmDialog, ModeStep]
    trigger: a pessoa escolhe Real e avança para criar
    logic: >
      SE o modo escolhido é REAL ENTÃO o diálogo de confirmação abre e o envio fica bloqueado até a
      caixa de confirmação ser marcada. SE a caixa não está marcada ENTÃO nenhum pedido é enviado e a
      tela diz que a confirmação falta. SE a caixa está marcada e a pessoa conclui ENTÃO o pedido vai
      com market = REAL. SE o modo é PAPER ENTÃO a confirmação nunca aparece.
    violation: nenhum bot REAL é criado sem a confirmação na tela.
    source: [FR1.3]

  - id: BR1.6
    statement: Depois da criação, as telas mostram o modo só em texto, sem controle de troca nem ação de converter.
    category: constraint
    applies_to: [BotWizard, BotDetail, "diálogo Editar limites"]
    trigger: abrir um bot já criado
    logic: SE o bot existe ENTÃO a tela mostra "Modo: <selo> <modo> · não pode ser trocado depois da criação" e nenhum controle de modo.
    violation: o teste de tela falha.
    source: [FR1.4]

  - id: BR1.7
    statement: Na criação, "Simulado" vem selecionado por padrão.
    category: policy
    applies_to: [ModeStep]
    trigger: a tela de criação carrega
    logic: SE a tela abre ENTÃO o valor inicial é PAPER, qualquer que seja a elegibilidade do REAL.
    violation: o teste de tela falha.
    source: [FR1.1]

  - id: BR1.8
    statement: A etapa de limites de um bot REAL mostra os limites REAL da conta só para leitura e não grava saldo virtual nem limites próprios.
    category: policy
    applies_to: [BotWizard, RealBotLimitsStep, Bot]
    trigger: a etapa 3 do assistente de um bot REAL
    logic: >
      SE o bot é REAL ENTÃO a etapa 3 lê os limites da conta (BR3.1) e "Continuar" salva a etapa sem
      saldo virtual e sem limites próprios; a validação do rascunho REAL não exige saldo virtual nem
      limites próprios e não grava versão de limites do bot (BR1.9). SE o bot é PAPER ENTÃO a etapa
      continua como hoje.
    violation: 422 VALIDATION_FAILED se um rascunho REAL receber saldo virtual ou limites próprios.
    source: [mockups D1, M3b, decisão do fundador 2026-11-21 (revisão R-01)]

  - id: BR1.9
    statement: Um bot REAL não tem limites próprios; ele usa só os limites REAL da conta, que a barreira REAL confere antes de cada ordem.
    category: constraint
    applies_to: [Bot, "validação do rascunho", "ciclo do bot", "comandos pausar, retomar e parar"]
    trigger: validar, ativar, rodar o ciclo, pausar, retomar ou parar um bot REAL
    logic: >
      A leitura da versão de limites do próprio bot passa para o ramo PAPER nos quatro caminhos que
      hoje a fazem para todo bot:
      1. a validação do rascunho: um rascunho REAL é validado sem limites próprios e sem saldo
         virtual, e nenhuma versão de limites do bot é gravada;
      2. o ciclo do bot: a leitura da versão de limites e dos limites do bot acontece só no ramo
         PAPER, depois do desvio entre REAL e PAPER;
      3. o comando parar: só um bot PAPER exige a versão de limites;
      4. o comando retomar: só um bot PAPER lê os limites atuais do bot.
      SE o bot é REAL ENTÃO os limites que valem são os da conta (BR3.1), conferidos pela barreira
      REAL em cada ordem. SE o bot é PAPER ENTÃO os quatro caminhos continuam como hoje.
      Decisão do fundador de 2026-11-21 sobre o DecisionRequest REAL (achado R-02 do NFR Design; alinhamento A8 da
      Code Generation da U2): no ramo REAL, maxOrderSize é o limite de ordem da conta em US$
      (floor(cap × orderMaxBps / 10000) lamports, lidos de C9 RealAccountLimits, convertidos com o solUsd do ciclo,
      arredondamento para baixo); vale 0 sem teto, sem carteira ativa, sem preço do SOL ou com C9 indisponível, e então
      nenhuma compra sai naquele ciclo (as vendas seguem). limitsVersion = 0 é o sentinela "limites da conta", gravado em
      decision.limits_version. O changeLimits de um bot REAL responde 422 BOT_VALIDATION_FAILED no campo limits ("Um
      bot REAL usa os limites REAL da conta."), sem gravar versão.
      Teste obrigatório: validar, ativar, rodar um ciclo, pausar, retomar e parar um bot REAL sem
      saldo virtual nem limites próprios, sem exceção; e o mesmo percurso para um bot PAPER, com o
      comportamento de hoje.
    violation: o teste do percurso falha e o commit não entra.
    source: [decisão do fundador 2026-11-21 (revisão R-01), mockups D1, ADR-008]

  # ---------------- BR2 — Elegibilidade do REAL ----------------
  - id: BR2.1
    statement: A elegibilidade do REAL devolve só o motivo mais forte, nesta ordem, e o primeiro motivo encontrado encerra a avaliação.
    category: authorization
    applies_to: [RealEligibility, RealCreationEligibility]
    trigger: leitura de elegibilidade (C2) ou comando de criação REAL (C1)
    logic: >
      Avalia em ordem e para no primeiro que vale:
      1. SE o kill switch REAL está acionado ENTÃO KILL_SWITCH.
      2. SENÃO SE o REAL do ambiente está desligado ENTÃO REAL_DISABLED.
      3. SENÃO SE a pessoa não tem carteira do bot ativa ENTÃO WALLET_INACTIVE.
      4. SENÃO SE a liberação da pessoa na rede do ambiente é NONE, BLOCKED ou REVOKED ENTÃO NOT_RELEASED.
      5. SENÃO SE a liberação está em QUARANTINE ENTÃO QUARANTINE.
      6. SENÃO available = true.
      A regra é uma função pura, com teste de unidade para cada motivo e para cada par de motivos.
    violation: não se aplica (é a própria decisão).
    source: [FR1.2, ADR-005, Q3, mockups Q7]

  - id: BR2.2
    statement: A quarentena traz o instante em que a liberação passa a valer, e só a carteira inativa traz uma ação.
    category: constraint
    applies_to: [RealEligibility]
    trigger: o resultado de BR2.1
    logic: >
      SE reason = QUARANTINE ENTÃO effectiveAt = o instante UTC de fim da quarentena da liberação. SE
      reason = WALLET_INACTIVE ENTÃO action = ACTIVATE_BOT_WALLET. Nos demais casos, os dois ficam
      ausentes. O motivo continua QUARANTINE até a liberação passar a ACTIVE, mesmo que effectiveAt já
      tenha passado.
    violation: o teste de contrato falha.
    source: [FR1.2, Q4, OQ2]

  - id: BR2.3
    statement: A elegibilidade é conferida na leitura e de novo no comando de criação, com a mesma regra.
    category: authorization
    applies_to: ["GET /api/v1/bots/real-eligibility (C2)", "POST /api/v1/bots (C1)"]
    trigger: leitura da tela e comando de criação com market = REAL
    logic: >
      A tela lê a elegibilidade quando abre. O comando de criação com market = REAL avalia BR2.1 de
      novo, no próprio comando, antes de criar. O resultado da tela nunca é reaproveitado pelo comando.
    violation: um bot REAL nunca nasce de uma elegibilidade que deixou de valer entre a leitura e o envio.
    source: [FR1.2, AC1.2.8, Q3]

  - id: BR2.4
    statement: Um pedido de criação REAL com qualquer um dos cinco motivos é recusado com 409 REAL_NOT_ELIGIBLE e o motivo.
    category: authorization
    applies_to: ["POST /api/v1/bots (C1)"]
    trigger: BR2.1 devolve available = false no comando de criação
    logic: >
      SE market = REAL e a elegibilidade não está disponível ENTÃO a resposta é 409 REAL_NOT_ELIGIBLE
      (RFC 7807, com reason igual ao motivo mais forte e correlationId), nenhum bot é criado e a recusa
      é auditada como erro de domínio. A gravação da auditoria acontece fora da transação do comando,
      que é desfeita, para a auditoria sobreviver ao rollback; um teste confere que a recusa deixa o
      registro de auditoria e nenhum bot. Vale para os cinco motivos, inclusive WALLET_INACTIVE, que
      antes voltava 422. O 422 fica só para valor de campo inválido (BR1.1) e o 400 para corpo
      malformado. Os valores de reason são os do contrato, confirmados pelo fundador em 2026-11-21.
    violation: não se aplica (é a própria recusa).
    source: [FR1.2, AC1.2.8, Q2, PQ-5, ADR-005 (alinhado)]

  - id: BR2.5
    statement: Se a fonte da elegibilidade falha, o REAL fica indisponível; o PAPER segue normal.
    category: policy
    applies_to: [RealCreationEligibility, "C1", "C2"]
    trigger: uma leitura necessária para decidir o motivo falha ou passa do tempo
    logic: >
      SE uma leitura necessária antes de decidir um motivo falha ENTÃO a leitura C2 responde 503
      SERVICE_UNAVAILABLE e o comando de criação REAL responde 503 SERVICE_UNAVAILABLE, sem criar bot
      (falha fechada). SE um motivo já foi decidido antes da leitura que falhou ENTÃO vale o motivo. SE
      market = PAPER ENTÃO a elegibilidade nunca é lida e a criação segue.
    violation: nenhum bot REAL é criado com a fonte indisponível.
    source: [FR1.2, AC1.2.9, Q3, team.md (erros técnicos 5xx)]

  - id: BR2.6
    statement: A elegibilidade só libera a criação de um bot REAL e nunca autoriza operar.
    category: policy
    applies_to: [RealEligibility]
    trigger: qualquer uso da elegibilidade
    logic: >
      A barreira REAL, a liberação com quarentena de 24 h, o kill switch e o TxSigner continuam valendo
      em cada ordem. Nenhum caminho de ordem lê a elegibilidade de criação.
    violation: não se aplica.
    source: [ADR-005, project.md (Forbidden: login não autoriza operar; Mandated: REAL só com liberação)]

  - id: BR2.7
    statement: Cada motivo tem o seu texto, diferente dos outros e não só em cor; "Simulado" continua sempre disponível.
    category: policy
    applies_to: [ModeStep]
    trigger: a elegibilidade diz indisponível
    logic: >
      SE available = false ENTÃO "Real" fica indisponível com o texto do motivo:
      KILL_SWITCH "As operações reais estão suspensas pelo sistema no momento.";
      REAL_DISABLED "O modo real está desligado no momento.";
      WALLET_INACTIVE "A carteira do bot não está ativa." com o link "Ativar a carteira do bot" para /real;
      NOT_RELEASED "Sua conta ainda não foi liberada para o modo real.";
      QUARANTINE "Sua liberação para o modo real está em quarentena e ainda não vale. Vale a partir de dd/mm HH:MM.".
      Só WALLET_INACTIVE tem link. "Simulado" continua selecionável em todos os casos.
    violation: o teste de tela falha.
    source: [FR1.2, AC1.2.2 a AC1.2.7, AC1.2.9, mockups M1.3]

  - id: BR2.8
    statement: Depois de uma recusa de domínio, a tela relê a elegibilidade e volta para "Simulado"; depois de um erro técnico, o diálogo oferece repetir com a mesma chave.
    category: policy
    applies_to: [ModeStep, RealConfirmDialog]
    trigger: resposta 409 ou 5xx/sem conexão ao pedido de criação REAL
    logic: >
      SE 409 REAL_NOT_ELIGIBLE ENTÃO o diálogo fecha, a tela relê C2, volta para PAPER, mostra "O bot
      real não foi criado." com o texto do motivo novo e o código para o suporte, e o foco vai para a
      mensagem; sem "Tentar de novo". SE 5xx, tempo esgotado ou sem conexão ENTÃO o diálogo continua
      aberto com "Tentar de novo", que reenvia com a mesma chave de idempotência.
    violation: o teste de tela falha.
    source: [AC1.2.8, AC1.3.5, mockups M2.4]

  # ---------------- BR3 — Limites REAL da conta ----------------
  - id: BR3.1
    statement: Os limites REAL da conta são quatro e saem das mesmas entradas que a barreira REAL aplica.
    category: calculation
    applies_to: [RealAccountLimits, "GET /api/v1/real/account-limits (C3)", "riskcontrol.api RealAccountLimits (C9)"]
    trigger: leitura dos limites
    logic: >
      cap = teto atual da carteira do bot ativa, em lamports.
      orderMax.lamports = floor(cap x orderMaxBps / 10000); orderMax.percentOfCap = orderMaxBps / 100.
      dailyBuys.count = dailyBuysMax da configuração da barreira, que vale por bot e por dia UTC; a
      configuração sempre tem esse valor, então ele é sempre KNOWN.
      dailyLoss.lamports = floor(base x dailyLossMaxBps / 10000), com base = min(teto no início do dia
      UTC, teto atual), a mesma base da barreira; dailyLoss.percentOfCap = dailyLossMaxBps / 100.
      Aritmética inteira exata; nenhum ponto flutuante. A tela não calcula nada.
    violation: não se aplica.
    source: [Q6, ADR-008, mockups M3b e D1]

  - id: BR3.2
    statement: Um limite sem valor aparece como "não definido", nunca como zero; sem teto, a ordem e a perda do dia também ficam "não definido".
    category: calculation
    applies_to: [RealAccountLimits, RealBotLimitsStep]
    trigger: leitura dos limites
    logic: >
      SE o teto não está definido (teto igual a zero) ENTÃO cap, orderMax e dailyLoss = NOT_DEFINED e a
      tela mostra "não definido (depende do teto)" na ordem e na perda, com o link "Definir o teto".
      As compras por dia nunca ficam "não definido" (BR3.1).
    violation: o teste de contrato e o de tela falham.
    source: [Q6, mockups M3b.2]

  - id: BR3.3
    statement: A leitura dos limites é só leitura; sem carteira do bot ativa ela responde 409 e, com falha de uma dependência, 503.
    category: authorization
    applies_to: ["GET /api/v1/real/account-limits (C3)"]
    trigger: leitura dos limites
    logic: >
      SE a pessoa não tem carteira do bot ativa ENTÃO 409 REAL_NOT_ELIGIBLE com reason WALLET_INACTIVE.
      SE uma dependência falha ENTÃO 503 SERVICE_UNAVAILABLE. A leitura nunca reserva, muda limite ou
      grava auditoria.
    violation: não se aplica.
    source: [ADR-008, C3, C9]

  - id: BR3.4
    statement: Na etapa de limites, "Continuar" fica desabilitado enquanto os limites carregam ou falharam, e disponível com valores ou "não definido".
    category: policy
    applies_to: [RealBotLimitsStep]
    trigger: estado da leitura dos limites
    logic: >
      SE carregando ENTÃO "Continuar" desabilitado com "Espere os limites carregarem.". SE erro ENTÃO
      "Continuar" desabilitado e "Tentar de novo" disponível. SE carregado, com valores ou "não
      definido" ENTÃO "Continuar" disponível. Ao voltar de /real, a etapa relê os limites.
    violation: o teste de tela falha.
    source: [mockups M3b.2]

  # ---------------- BR4 — Diretório e lista ----------------
  - id: BR4.1
    statement: No P&L, os estados do bot se reduzem a ACTIVE, PAUSED e REMOVED, e os rascunhos não entram.
    category: calculation
    applies_to: [BotDirectoryEntry, "pnl.api BotDirectory (C8)"]
    trigger: o P&L pede os bots da pessoa
    logic: >
      ACTIVE e CLOSING_PENDING -> ACTIVE; PAUSED -> PAUSED; STOPPED -> REMOVED. DRAFT e VALIDATED não
      geram entrada, porque um rascunho não opera e não tem P&L. Um bot removido continua com as suas
      linhas e o seu P&L. A lista sai em ordem de criação (createdAt, depois botId), filtrada pelo tenant
      e pela pessoa.
    violation: não se aplica.
    source: [Q1, PQ-7, FR3.11, ADR-007]

  - id: BR4.2
    statement: A lista de bots mostra os bots PAPER e REAL juntos, com o modo em texto, na ordem de criação e sem ordenação por P&L.
    category: policy
    applies_to: [BotList, "GET /api/v1/bots"]
    trigger: abrir /bots
    logic: >
      Todos os bots da pessoa aparecem na mesma lista, cada linha com o selo e o texto do modo,
      SIMULADO ou REAL. A ordem é a da criação, do mais novo para o mais antigo. Não existe ordenação
      por P&L, então nenhum ranking mistura os modos.
    violation: o teste de tela falha.
    source: [FR2.1, AC2.1.1, AC2.1.2, AC2.1.5]

  - id: BR4.3
    statement: O P&L de cada bot da lista sai na moeda do próprio modo, lido da linha do bot na visão por modo.
    category: policy
    applies_to: [BotList]
    trigger: a lista aparece
    logic: >
      Para cada bot não rascunho, a tela procura pelo botId a linha no bloco do modo do bot em C4, sem
      nenhuma conta. Um bot PAPER mostra US$; um bot REAL mostra SOL; nunca o contrário. Um rascunho
      mostra "—". SE o bloco do modo do bot veio UNAVAILABLE ou a leitura de C4 falhou ENTÃO a linha
      mostra "P&L indisponível agora" e a lista continua usável.
    violation: o teste de tela falha.
    source: [FR2.2, NFR4, AC2.2.1 a AC2.2.3]

  - id: BR4.4
    statement: A lista tem estados distintos para carregando, erro, vazio, P&L desconhecido e P&L indisponível.
    category: policy
    applies_to: [BotList]
    trigger: estado da lista e das linhas
    logic: >
      Carregando: "Carregando os bots…" em role=status. Erro: "Não foi possível carregar os bots agora.",
      código para o suporte e "Tentar de novo". Vazio: "Você ainda não tem bots." com "+ Novo bot".
      P&L UNKNOWN: "desconhecido (<motivo>)". P&L indisponível: "P&L indisponível agora". Cada estado
      tem texto próprio.
    violation: o teste de tela falha.
    source: [AC2.1.3, AC2.1.4, NFR6]

  # ---------------- BR5 — Visão por modo ----------------
  - id: BR5.1
    statement: Nenhuma resposta, tela ou tipo soma os modos PAPER e REAL; a visão por modo tem só o período e os dois blocos.
    category: constraint
    applies_to: [ModePnlStatement, "GET /api/v1/pnl/by-mode (C4)", PnlModeBlock, BotList]
    trigger: qualquer leitura ou exibição de P&L
    logic: >
      A resposta de C4 tem só period, paper e real; nenhum campo de total fora de um bloco. Garantias:
      (a) a regra de arquitetura NEVER_SUM_MODES sobre as listas fechadas de tipos PAPER e REAL, com as
      quatro partes do ADR-003; (b) o teste de contrato do esquema; (c) um teste de propriedade com
      valores conhecidos que confere que a resposta traz exatamente o valor PAPER e o valor REAL nos
      seus blocos e nenhum terceiro número; (d) na tela, nenhum texto ou valor depende dos dois modos ao
      mesmo tempo.
    violation: a CI falha (ArchUnit, contrato ou propriedade) e o commit não entra.
    source: [FR4.1, NFR3, ADR-003, project.md (NEVER somar os modos)]

  - id: BR5.2
    statement: Cada bloco tem só valores do próprio modo, na própria moeda.
    category: constraint
    applies_to: [PaperModeBlock, RealModeBlock]
    trigger: cálculo de cada bloco
    logic: >
      O bloco PAPER é calculado só do ledger PAPER, em US$ (currency USD). O bloco REAL é calculado só das
      ordens e do ledger REAL, em lamports (currency SOL). SE um bot é REAL ENTÃO nada dele entra no bloco
      PAPER, e vice-versa.
    violation: o teste obrigatório de separação entre REAL e PAPER falha.
    source: [FR4.1, NFR4, AC4.1.3, AC4.1.4, project.md (ALWAYS separar REAL e PAPER)]

  - id: BR5.3
    statement: Um bloco que falha vem UNAVAILABLE sem derrubar o outro.
    category: policy
    applies_to: [ModePnlStatement, ModeBlockUnavailable, PnlModeBlock]
    trigger: o cálculo de um bloco falha
    logic: >
      SE o cálculo de um modo falha ENTÃO a resposta é 200 com o outro bloco calculado e este com status
      UNAVAILABLE, code e correlationId. SE os dois falham ENTÃO os dois vêm UNAVAILABLE. A rota nunca
      devolve 500 inteiro por causa de um modo. Na tela, o bloco UNAVAILABLE mostra o erro e "Tentar de
      novo", que relê a rota e atualiza só esse bloco.
    violation: não se aplica.
    source: [Q5 do contract design, NFR6]

  - id: BR5.4
    statement: O total de cada bloco é calculado no servidor, dentro do bloco, e fica desconhecido quando o não realizado é desconhecido.
    category: calculation
    applies_to: [PaperModeBlock, PaperBotPnlLine, RealModeBlock, RealBotPnlLine]
    trigger: cálculo de um total
    logic: >
      PAPER: total = realized + unrealized - fees, em US$ exato. SE unrealized é UNKNOWN ENTÃO total é
      UNKNOWN; realized e fees continuam exatos. REAL: ver BR5.5 na U1. A tela nunca soma, subtrai ou
      converte valores.
    violation: o teste de cálculo falha.
    source: [FR3.5, FR3.10, AC4.1.1, NFR2]

  - id: BR5.5
    statement: Na U1, cada linha REAL usa o cálculo por ordens que já existe, só com as ordens do próprio bot, e o total REAL do modo é o total da pessoa de hoje.
    category: calculation
    applies_to: [RealBotPnlLine, RealModeBlock, "pnl.api RealBotPnlQuery (C10)"]
    trigger: cálculo do bloco REAL
    logic: >
      Linha de um bot = o cálculo por custo médio de hoje aplicado só às ordens REAL assentadas com o
      botId desse bot. Total do modo = o cálculo de hoje sobre todas as ordens assentadas da pessoa (o
      mesmo número de GET /api/v1/real/pnl). Nesse cálculo as taxas já estão descontadas: a da compra
      entra no custo, e a da venda e a da ordem que falhou saem do realizado. Por isso, na U1,
      realizedLamports é LÍQUIDO de taxas, total = realized + unrealized, e fees é só informação, sem ser
      subtraído de novo; a tela rotula a linha de taxas do REAL como "já incluídas no realizado e no
      custo". A partir da U2, realizedLamports passa a ser BRUTO de taxas (como no AC3.2.2) e total =
      realized + unrealized - fees, como no PAPER; essa mudança de sentido fica registrada em C10.
      prorated vem vazio e unowned vem null (nulo, como em C4; o "vazio" de C10 é o Optional vazio da
      interface). Na U1, as ordens sem bot (liquidação, condicional) entram no total e em nenhuma linha,
      então a soma das linhas pode diferir do total; por isso, na U1, o bloco REAL não vazio sempre
      mostra a nota fixa "Algumas operações ainda não têm bot dono; o rateio chega na próxima etapa."
      (fixa porque a tela não soma as linhas para achar a diferença; a U2 a retira). SE o preço é
      desconhecido ENTÃO unrealized e total são UNKNOWN. A U2 troca o cálculo e a fórmula, com os testes
      escritos antes do código.
    violation: não se aplica.
    source: [Q7, C10, unit-of-work.md (RealBotPnl compartilhado), RK-1, RK-4]

  - id: BR5.6
    statement: Na U1, a visão por modo responde só ao período "desde o início".
    category: validation
    applies_to: [PnlPeriod, "GET /api/v1/pnl/by-mode (C4)"]
    trigger: leitura de C4
    logic: >
      SE period está ausente ou é ALL ENTÃO o período é ALL. SE period tem outro valor ou chegam from/to
      ENTÃO 400 VALIDATION_FAILED. A U3 acrescenta RANGE de forma aditiva.
    violation: 400 VALIDATION_FAILED.
    source: [C4, mockups D2]

  - id: BR5.7
    statement: As linhas dos bots de cada bloco saem na ordem de criação e com o estado em texto.
    category: policy
    applies_to: [PaperModeBlock, RealModeBlock, PnlModeBlock]
    trigger: montagem de um bloco
    logic: >
      Os bots de cada bloco vêm do diretório (BR4.1), na ordem de criação, nunca por valor, cada um com
      botStatus ACTIVE, PAUSED ou REMOVED, mostrado como "Ativo", "Pausado" ou "Removido".
    violation: o teste de contrato e o de tela falham.
    source: [FR3.11, C4]

  - id: BR5.8
    statement: O P&L REAL sai em lamports inteiros, como texto, e sem nenhum endereço de carteira.
    category: constraint
    applies_to: [RealModeBlock, RealBotPnlLine]
    trigger: resposta de C4
    logic: >
      Realizado, não realizado, taxas e total de cada linha e do bloco REAL saem em lamports como texto
      inteiro com sinal. Nenhum campo traz endereço de carteira, mint de carteira ou chave.
    violation: o teste de contrato falha.
    source: [FR3.5, NFR3, AC3.2.5]

  - id: BR5.9
    statement: O P&L PAPER por bot continua igual ao de hoje.
    category: constraint
    applies_to: [PaperBotPnlLine, "GET /api/v1/pnl?botId="]
    trigger: cálculo do P&L PAPER de um bot
    logic: >
      O realizado, o não realizado e as taxas de um bot PAPER são os mesmos de antes deste intent, em US$
      exato; os testes atuais continuam verdes. O bloco PAPER só reapresenta esses valores.
    violation: os testes atuais do P&L PAPER falham.
    source: [FR3.1]

  - id: BR5.10
    statement: A soma do P&L dos bots PAPER, mais a linha "Sem bot" do PAPER, é exatamente o P&L PAPER do modo calculado pelo ledger PAPER.
    category: calculation
    applies_to: [PaperModeBlock]
    trigger: conferência PAPER (teste)
    logic: >
      Para uma pessoa, a soma das linhas dos bots PAPER, em US$ exato, mais a linha "Sem bot" do PAPER é
      igual ao total do modo calculado a partir do ledger PAPER, por igualdade exata. Na U1 a linha "Sem
      bot" do PAPER vale US$ 0,00, porque toda operação PAPER tem bot. É um teste invariante; não ganha
      rota nova.
    violation: o teste de conferência falha e mostra a diferença.
    source: [FR5.3, AC5.3.2, NFR2]

  # ---------------- BR6 — Aviso e textos ----------------
  - id: BR6.1
    statement: O aviso "O resultado simulado não garante o resultado real." aparece sempre que um P&L simulado aparece, e só aí.
    category: policy
    applies_to: [PaperModeBlock, PnlModeBlock, BotList]
    trigger: exibição de P&L
    logic: >
      SE o bloco PAPER mostra algum valor ENTÃO o aviso aparece nele. SE a lista /bots visível tem pelo
      menos um bot simulado ENTÃO o aviso aparece acima da lista. SE a tela mostra só P&L real ou o bloco
      PAPER está vazio ENTÃO o aviso não aparece. O texto vem do servidor no campo disclaimer do bloco.
    violation: o teste de tela falha.
    source: [FR4.2, AC4.2.1, AC4.2.3, AC4.2.6, C-16]

  - id: BR6.2
    statement: Nenhum texto das telas da U1 promete lucro.
    category: policy
    applies_to: [ModeStep, RealConfirmDialog, RealBotLimitsStep, PnlModeBlock, BotList]
    trigger: qualquer texto mostrado
    logic: SE um texto promete lucro, ganho garantido ou detecção garantida ENTÃO ele não pode entrar; um teste lê os textos das telas.
    violation: o teste de textos falha.
    source: [AC4.2.7, C-16, project.md (NEVER prometer lucro)]

  # ---------------- BR7 — Formatos ----------------
  - id: BR7.1
    statement: Os valores REAL dos blocos novos saem em SOL, exatos, cortando os zeros à direita, sem arredondar e com pelo menos 2 casas.
    category: calculation
    applies_to: [PnlModeBlock, BotList, RealBotLimitsStep]
    trigger: exibição de um valor em lamports
    logic: >
      SOL = lamports / 10^9 em aritmética inteira; corta os zeros à direita da parte decimal, mantendo no
      mínimo 2 casas; nunca arredonda (9 casas no máximo). Separador decimal vírgula e de milhar ponto.
      Sinal "+" no positivo, "−" no negativo e nenhum no zero; o texto "(ganho)" ou "(perda)" acompanha a
      cor. Exemplos: 31.200.000 lamports -> "+0,0312 SOL"; 2.500.000.000 -> "2,50 SOL"; 1 -> "0,000000001 SOL".
    violation: o teste do formatador falha.
    source: [Q5, OQ1, AC2.2.2, AC4.1.1]

  - id: BR7.2
    statement: Os valores PAPER dos blocos novos e da lista saem em "US$", com 2 casas, sobre o decimal exato do ledger PAPER.
    category: calculation
    applies_to: [PnlModeBlock, BotList]
    trigger: exibição de um valor em US$
    logic: >
      "US$" seguido do valor com 2 casas, vírgula decimal e ponto de milhar; sinal como em BR7.1. Um valor
      com mais de 2 casas é cortado em direção a zero, sem arredondar. Exemplos: 12.40 -> "+US$ 12,40";
      -3.1 -> "−US$ 3,10"; 0 -> "US$ 0,00".
    violation: o teste do formatador falha.
    source: [Q5, OQ1, AC2.2.1, AC4.1.1]

  - id: BR7.3
    statement: As telas REAL que já existem continuam com o formato de hoje neste intent.
    category: constraint
    applies_to: ["Visão geral REAL", "Depósito e saque", "Ordens REAL"]
    trigger: exibição nas telas REAL existentes
    logic: SE a tela já existia antes deste intent ENTÃO ela mantém as 9 casas do SOL; só os blocos e a lista novos usam BR7.1.
    violation: o teste existente das telas REAL falha.
    source: [Q5]

  - id: BR7.4
    statement: Instantes trafegam em UTC e são mostrados no fuso da pessoa.
    category: constraint
    applies_to: [RealEligibility, PaperModeBlock, RealModeBlock]
    trigger: exibição de um instante
    logic: >
      A API envia instantes em UTC. A tela converte para o fuso da pessoa: "Vale a partir de dd/mm HH:MM"
      na quarentena e "marcação às HH:MM" no não realizado.
    violation: o teste de tela falha.
    source: [Q4, FR7.2, project.md (ALWAYS UTC)]

  - id: BR7.5
    statement: A tela nunca usa número de ponto flutuante para dinheiro; o SOL só é formatado pelo módulo de dinheiro REAL.
    category: constraint
    applies_to: [PnlModeBlock, BotList, RealBotLimitsStep, RealConfirmDialog]
    trigger: código da tela que manipula dinheiro
    logic: >
      O SOL é formatado só pelo módulo de dinheiro de features/real (o único lugar com inteiros grandes);
      o US$ pelas funções decimais de features/pnl. A proibição de lint do dinheiro (conversão para número,
      fixação de casas e parse de ponto flutuante), que hoje vale para features/real, passa a valer também
      para features/pnl.
    violation: o lint falha na CI.
    source: [ADR-010, C17, project.md (NEVER ponto flutuante)]

  # ---------------- BR8 — Isolamento ----------------
  - id: BR8.1
    statement: O tenant e a pessoa vêm sempre do contexto de autenticação, nunca do corpo nem da consulta.
    category: authorization
    applies_to: ["C1", "C2", "C3", "C4", "C8", "C9", "C10"]
    trigger: toda leitura e escrita
    logic: >
      Toda rota e toda porta recebe tenant e pessoa do contexto de autenticação e filtra por eles. Um
      tenant ou pessoa no corpo ou na consulta é ignorado ou recusado como campo não declarado.
    violation: 400 para campo não declarado; o teste de isolamento entre contas falha se um dado vazar.
    source: [NFR3, project.md (ALWAYS validar o tenant), team.md (Tenant no código)]

  - id: BR8.2
    statement: O bot de outra pessoa responde 404 e nenhum dado dele.
    category: authorization
    applies_to: ["GET /api/v1/pnl?botId=", "C4", "C8 botOf", "C10 botPnl"]
    trigger: pedido de P&L de um bot pelo id
    logic: >
      SE o bot não é da pessoa do token ou não existe ENTÃO 404 NOT_FOUND, sem nenhum dado do bot. Na
      visão por modo, um bot de outra pessoa simplesmente nunca aparece.
    violation: o teste obrigatório de isolamento falha.
    source: [NFR3, AC3.1.2, AC3.2.6]

  - id: BR8.3
    statement: As rotas novas seguem a regra do novo aceite dos termos.
    category: authorization
    applies_to: ["C2", "C3", "C4"]
    trigger: versão nova dos termos pendente de aceite
    logic: SE há uma versão nova dos termos sem aceite ENTÃO as rotas novas respondem 403 TERMS_REACCEPTANCE_REQUIRED, como as demais fora da lista de exceções.
    violation: o teste de termos falha.
    source: [team.md (Testing Posture, termos), contract-summary.md (regras de todos os contratos)]

  # ---------------- BR9 — Acessibilidade e telas ----------------
  - id: BR9.1
    statement: Em /bots e /pnl, todo texto tem contraste mínimo de 4,5:1 no tema escuro, e cor nunca aparece sozinha.
    category: policy
    applies_to: [BotList, PnlModeBlock, SimulatedBadge, ModeStep]
    trigger: exibição
    logic: >
      Todo texto tem contraste de pelo menos 4,5:1 no tema escuro (valores do checklist de
      acessibilidade). Valor positivo ou negativo e selo de modo aparecem com texto ou sinal ("+", "−",
      "(ganho)", "(perda)", SIMULADO, REAL).
    violation: a conferência de acessibilidade falha.
    source: [NFR5, AC4.4.1, AC4.4.2]

  - id: BR9.2
    statement: Em /pnl, há um h1 "Resultado", um h2 por modo, total com rótulo de modo e moeda e foco inicial no primeiro bloco.
    category: policy
    applies_to: [PnlModeBlock, "tela /pnl"]
    trigger: abrir /pnl
    logic: >
      h1 "Resultado"; h2 "Simulado, em dólares" e "Real, em SOL"; o total de cada bloco tem o rótulo
      "Total simulado, em dólares" ou "Total real, em SOL"; o foco começa no h2 do bloco Simulado, passa
      por todos os elementos ativos e fica sempre visível.
    violation: o teste de tela falha.
    source: [NFR5, AC4.4.3, AC4.4.4, AC4.4.5]

  - id: BR9.3
    statement: /bots e /pnl funcionam a partir de 360 px sem rolagem lateral, com cartões empilhados e estados distintos.
    category: policy
    applies_to: [BotList, PnlModeBlock]
    trigger: exibição em tela estreita
    logic: >
      Em 360 px não há rolagem horizontal e nenhum valor é cortado. Em /pnl os blocos viram cartões
      empilhados, Simulado e depois Real, e o foco anda de cartão em cartão. Carregando, erro,
      desconhecido e vazio têm aparência e texto próprios em cada bloco.
    violation: o spec de ponta a ponta em 360 px ou o teste de tela falha.
    source: [NFR6, AC4.5.1, AC4.5.2, AC4.5.3]

  - id: BR9.4
    statement: A escolha do modo funciona só pelo teclado, com foco visível e rótulo em texto; "Real" indisponível continua alcançável e não é marcado.
    category: policy
    applies_to: [ModeStep]
    trigger: navegação pelo teclado na escolha do modo
    logic: >
      Os dois rádios são nativos, num grupo com a legenda "Modo do bot". "Real" indisponível tem a marca
      de indisponível (não o bloqueio nativo), continua na ordem das setas, a seta, o clique e o Espaço
      sobre ele não mudam a escolha, e o motivo é lido pela descrição ligada ao rádio.
    violation: o teste de componente falha.
    source: [NFR5, AC1.1.4, mockups M1.3]

  # ---------------- BR10 — Entrega e verificação ----------------
  - id: BR10.1
    statement: Um spec de ponta a ponta novo cria um bot PAPER e um bot REAL no validador local, faz uma compra em cada um e confere a separação dos blocos.
    category: policy
    applies_to: ["scripts/verify.sh", "spec de ponta a ponta da U1"]
    trigger: toda mudança e o push para main
    logic: >
      O spec cria os dois bots pela tela, faz uma compra simples em cada modo e confere que o bloco
      Simulado mostra só o bot PAPER, o bloco Real só o bot REAL, que nenhum texto ou valor junta os
      modos e que a resposta de C4 tem só period, paper e real. Não confere valores que dependam do
      arredondamento do custo. O responsável acrescenta o spec ao verify.sh.
    violation: verify.sh devolve código diferente de zero.
    source: [NFR7, AC4.1.5, team.md (fatia do intent 261121-paper-real-unico)]

  - id: BR10.2
    statement: A liberação REAL vigente do spec vem de uma semente que só existe no perfil de teste, com um teste de guarda; a quarentena de produção não muda.
    category: policy
    applies_to: ["semente de liberação do perfil de teste", "spec de ponta a ponta da U1"]
    trigger: subida do ambiente de teste do spec
    logic: >
      Só no perfil de teste e só com a chave da semente ligada, a conta do smoke recebe uma liberação já
      no estado ACTIVE, registrada há pelo menos 24 horas pelo relógio do ambiente. Um teste de guarda
      falha se o perfil de teste ou a chave da semente puder ser ativado pela configuração de produção.
      Nenhum caminho de produção encurta ou ignora a quarentena de 24 horas.
    violation: o teste de guarda falha e o commit não entra.
    source: [Q8, RK-2, RK-3]

  - id: BR10.3
    statement: A U1 nunca é implantada num ambiente com usuários antes da U2.
    category: policy
    applies_to: [U1]
    trigger: qualquer implantação
    logic: >
      A U1 é verificada no validador local e na CI, no ponto de controle do esqueleto. Ela só sai para a
      mainnet, ou para a devnet com convidados, junto com a U2 ou depois dela.
    violation: a implantação não é feita.
    source: [Q7, unit-of-work.md (decisão do fundador, 2026-11-21), RK-1]

  - id: BR10.4
    statement: Os dois modos ficam no mesmo app e no mesmo ambiente; a U1 não acrescenta serviço AWS nem abre a devnet aos convidados.
    category: policy
    applies_to: [U1, "infra/terraform"]
    trigger: revisão da U1
    logic: >
      "Simulado" e "Real" aparecem na mesma tela de criação, sem troca de rede nem de endereço. A U1 não
      muda infra/terraform, não acrescenta serviço AWS e não muda o acesso à devnet, que continua fechada
      aos convidados.
    violation: a revisão do ponto de controle recusa a mudança.
    source: [NFR8, AC1.5.1 a AC1.5.3, project.md (NEVER abrir a devnet aos usuários)]

  - id: BR10.5
    statement: O alargamento de mode para REAL entra no mesmo commit que atualiza os leitores do app web.
    category: constraint
    applies_to: ["apps/web: leitor do resumo do bot no painel", "fixtures e testes que fixam mode PAPER"]
    trigger: a mudança de BR1.3
    logic: >
      No mesmo commit da mudança da API e do contrato: o leitor do resumo do bot do painel aceita PAPER e
      REAL (ou passa a ler market); as fixtures e os testes que fixam mode PAPER são atualizados; um teste
      de contrato do app web confere que o resumo de um bot REAL é aceito; o teste de contrato do backend
      de mode == market continua.
    violation: a CI fica vermelha (frontend, contract) e o commit não serve para ponto de controle.
    source: [C1 (revisão R-01), contract-summary.md (regra de posse 2), AC2.4.4]
```

## Resumo

| ID | Regra | Categoria | Origem |
|---|---|---|---|
| BR1.1 | `market` na criação; PAPER quando ausente; valor inválido 422 | validation | FR1.1 |
| BR1.2 | Modo imutável depois da criação; edição com `market` é recusada | constraint | FR1.4 |
| BR1.3 | `mode == market` em toda resposta; `mode` obsoleto | constraint | FR2.4, ADR-009 |
| BR1.4 | Mesma chave de idempotência cria um bot só | constraint | AC1.3.5 |
| BR1.5 | Confirmação "Este bot opera com dinheiro real" antes de um rascunho REAL | validation | FR1.3 |
| BR1.6 | Modo só em texto depois da criação | constraint | FR1.4 |
| BR1.7 | "Simulado" por padrão | policy | FR1.1 |
| BR1.8 | Etapa de limites REAL só leitura, sem saldo virtual | policy | D1, M3b |
| BR1.9 | Bot REAL sem limites próprios: a versão de limites só no ramo PAPER (validar, ciclo, parar, retomar), com o teste do percurso | constraint | fundador (R-01) |
| BR2.1 | Ordem dos motivos e só o mais forte | authorization | FR1.2, ADR-005, Q3 |
| BR2.2 | `effectiveAt` só na quarentena; ação só na carteira inativa | constraint | Q4 |
| BR2.3 | Conferida na leitura e de novo na criação | authorization | Q3 |
| BR2.4 | 409 `REAL_NOT_ELIGIBLE` para os cinco motivos | authorization | Q2, PQ-5 |
| BR2.5 | Fonte falha: REAL 503; PAPER não é afetado | policy | Q3 |
| BR2.6 | Elegibilidade nunca autoriza operar | policy | ADR-005 |
| BR2.7 | Um texto por motivo; link só na carteira inativa; Simulado sempre disponível | policy | FR1.2, M1.3 |
| BR2.8 | Depois de 409, relê e volta para Simulado; depois de 5xx, repete com a mesma chave | policy | M2.4 |
| BR3.1 | Quatro limites das mesmas entradas da barreira | calculation | Q6, ADR-008 |
| BR3.2 | "não definido", nunca zero; sem teto, ordem e perda também | calculation | Q6 |
| BR3.3 | Só leitura; 409 sem carteira ativa; 503 com falha | authorization | C3 |
| BR3.4 | "Continuar" só com os limites lidos | policy | M3b.2 |
| BR4.1 | Estados no P&L: ACTIVE, PAUSED, REMOVED; rascunhos fora | calculation | Q1 |
| BR4.2 | Lista única, modo em texto, ordem de criação | policy | FR2.1 |
| BR4.3 | P&L de cada bot na moeda do modo, pela linha de C4 | policy | FR2.2 |
| BR4.4 | Estados distintos na lista | policy | NFR6 |
| BR5.1 | Nunca somar os modos; só período e dois blocos | constraint | FR4.1, ADR-003 |
| BR5.2 | Cada bloco só com o próprio modo e moeda | constraint | NFR4 |
| BR5.3 | Bloco que falha vem UNAVAILABLE sem derrubar o outro | policy | Q5 (contrato) |
| BR5.4 | Total dentro do bloco; UNKNOWN com não realizado UNKNOWN | calculation | FR3.5, FR3.10 |
| BR5.5 | Linha REAL da U1 pelas ordens do bot; total REAL de hoje | calculation | Q7 |
| BR5.6 | Só "desde o início" na U1 | validation | C4 |
| BR5.7 | Linhas em ordem de criação, com o estado | policy | FR3.11 |
| BR5.8 | REAL em lamports inteiros, sem endereço | constraint | AC3.2.5 |
| BR5.9 | P&L PAPER por bot igual ao de hoje | constraint | FR3.1 |
| BR5.10 | Conferência PAPER exata com o ledger | calculation | FR5.3 |
| BR6.1 | Aviso do simulado sempre que há P&L simulado, e só aí | policy | FR4.2 |
| BR6.2 | Nenhum texto promete lucro | policy | C-16 |
| BR7.1 | SOL exato, sem zeros à direita, mínimo 2 casas | calculation | Q5 |
| BR7.2 | US$ com 2 casas | calculation | Q5 |
| BR7.3 | Telas REAL existentes não mudam | constraint | Q5 |
| BR7.4 | UTC na API, fuso da pessoa na tela | constraint | Q4 |
| BR7.5 | Sem ponto flutuante na tela; lint do dinheiro em `features/pnl` | constraint | ADR-010 |
| BR8.1 | Tenant do contexto de autenticação | authorization | NFR3 |
| BR8.2 | 404 para o bot de outra pessoa | authorization | NFR3 |
| BR8.3 | Rotas novas seguem o novo aceite dos termos | authorization | team.md |
| BR9.1 | Contraste 4,5:1 e cor com texto | policy | NFR5 |
| BR9.2 | h1, h2 por modo, rótulo do total, foco | policy | NFR5 |
| BR9.3 | 360 px, cartões empilhados, estados distintos | policy | NFR6 |
| BR9.4 | Teclado na escolha do modo | policy | NFR5 |
| BR10.1 | Spec de ponta a ponta da fatia | policy | NFR7 |
| BR10.2 | Semente de liberação só no perfil de teste, com guarda | policy | Q8 |
| BR10.3 | U1 nunca implantada sem a U2 | policy | Q7 |
| BR10.4 | Mesmo app, sem serviço AWS novo, devnet fechada | policy | NFR8 |
| BR10.5 | Alargamento de `mode` com os leitores do web no mesmo commit | constraint | C1 (R-01) |

São 54 regras.

## Alinhamento do ADR-005

O ADR-005 do domain design (`inception/domain-design/decisions.md`) diz que o `POST` com `market = REAL` "é recusado com 422 e o código do motivo por qualquer um dos cinco motivos". Por decisão do fundador (PQ-5, 2026-11-21) e pela Q2 deste estágio, a regra válida passa a ser:

- os cinco motivos (`KILL_SWITCH`, `REAL_DISABLED`, `WALLET_INACTIVE`, `NOT_RELEASED`, `QUARANTINE`) respondem **409 `REAL_NOT_ELIGIBLE`**, com `reason` igual ao motivo mais forte (BR2.4);
- a carteira inativa, que hoje responde 422 `VALIDATION_FAILED` no campo `market`, passa a usar o mesmo 409; um teste de regressão cobre a mudança;
- o 422 fica só para valor de campo inválido (BR1.1), e o 400 para corpo malformado ou campo não declarado (BR1.2);
- a falha fechada, quando a fonte da elegibilidade não responde, é **503 `SERVICE_UNAVAILABLE`**, um erro técnico separado do erro de domínio (BR2.5).

Este alinhamento substitui o texto "422" do ADR-005 e da responsabilidade da U1 em `unit-of-work.md` ("recusado com 422 por qualquer um dos cinco motivos"). O `decisions.md` e o `unit-of-work.md` não são editados neste estágio; esta seção é o registro que vale para a Code Generation da U1.

## Ajustes de contrato que a U1 faz como dona

A U1 é a dona de C1, C2, C3, C8, C9 e C17 e entrega a primeira implementação de C10. Este Functional Design pede estes ajustes, todos em rotas e interfaces ainda não publicadas:

- **C3 e C9 (limites).** A forma passa a ser a de quatro limites de BR3.1 (`cap`, `orderMax` com o percentual, `dailyBuys` em contagem, por bot e por dia UTC, sempre conhecido, e `dailyLoss` com o percentual, sobre a base `min(teto no início do dia, teto atual)`), no lugar dos cinco campos do contrato (`orderLimitLamports`, `tokenExposureLimitLamports`, `totalExposureLimitLamports`, `operatingCapLamports`, `dailyLossPercent`). Motivo: Q6 e a decisão D1 dos mockups; os limites de exposição por token e total não existem na política de risco REAL do código.
- **C10 (P&L REAL por bot).** A assinatura não muda. Nota que entra em C10 e no OpenAPI e que a U2 herda: **o significado de `realizedLamports` muda na U2.**
  - Na U1, `realizedLamports` é líquido de taxas: a taxa da venda e a da ordem que falhou já saem dele, e a da compra está no custo. O total é `realized + unrealized`, e `feesLamports` é só informação.
  - A partir da U2, `realizedLamports` é bruto de taxas, e o total é `realized + unrealized − fees`, como no PAPER.
  - `unowned` é nulo na U1. Na resposta de C4 ele vem `null`; na interface de C10 ele é o `Optional` vazio. São a mesma coisa.
- **C1 e C2.** Sem mudança de forma. Os valores de `reason` continuam `NOT_RELEASED` e `QUARANTINE`, como no contrato; o fundador confirmou esses nomes em 2026-11-21, e eles substituem os rótulos `RELEASE_MISSING` e `RELEASE_QUARANTINE` do texto da Q2 e da Q4.

## Sources

- `functional-design-questions.md`: Q1 a Q8 e o resumo confirmado.
- `inception/units-generation/unit-of-work.md`: seção U1, notas de implementação, RK-1 a RK-4.
- `inception/units-generation/unit-of-work-story-map.md`: linhas da U1 e "Histórias que atravessam Units".
- `inception/user-stories/stories.md`: US1.1 a US1.5, US2.1, US2.2, US2.4, US3.1, US3.2, US4.1, US4.2, US4.4, US4.5, US5.3.
- `inception/requirements-analysis/requirements.md`: FR1, FR2, FR3.1, FR3.5, FR3.10, FR3.11, FR4, FR5.3, NFR2 a NFR8.
- `inception/domain-design/components.md` e `decisions.md`: ADR-003, ADR-005, ADR-007, ADR-008, ADR-009, ADR-010.
- `inception/contract-design/contract-summary.md`: regras de todos os contratos, C1 a C4, C8, C9, C10, C17, PQ-5, PQ-7.
- `inception/refined-mockups/mockups.md` (M1 a M5, D1, D2, OQ1, OQ2, OQ6), `interaction-spec.md` e `accessibility-checklist.md`.
- `aidlc/spaces/default/memory/project.md` e `team.md`: Forbidden, Mandated, Testing Posture, Code Style.
- Código lido: `BotCommandService.create` (hoje recusa só a carteira inativa, com 422, fora do comando idempotente), `RealStateQuery`, `RealReleaseInfo`, `RealKillSwitchState`, `RealRiskPolicy` e `RealRiskLimits`, `RealPnlCalculator` e `RealPnlService`, `PnlCalculator` e `LedgerQueries` (PAPER com taxas separadas do realizado), `RealTestSeeder`, `0019-real-execution.sql`.

## Assumptions & Open Questions

1. **Decidido (fundador, 2026-11-21):** os valores de `reason` são os do contrato C1/C2, `NOT_RELEASED` e `QUARANTINE`. Eles substituem os rótulos `RELEASE_MISSING` e `RELEASE_QUARANTINE` do texto da Q2 e da Q4. Questão fechada.
2. [assumption] Na U1, a fórmula do total REAL é `realized + unrealized`, porque o cálculo de hoje (`RealPnlCalculator`) já desconta a taxa da compra no custo e a da venda e da ordem que falhou no realizado; subtrair `fees` de novo contaria a taxa duas vezes. A tela rotula as taxas do REAL como "já incluídas no realizado e no custo", e C10 registra que o sentido de `realizedLamports` muda na U2 (BR5.5). A fórmula do AC4.1.1 vale para o PAPER desde a U1 e para o REAL a partir da U2. Como a U1 não é implantada sem a U2 (BR10.3) e o spec da U1 não confere valores, nenhum usuário vê a diferença.
3. [assumption] Na repetição de um pedido de criação já concluído (mesma chave e mesmo corpo), a resposta guardada é devolvida sem reavaliar a elegibilidade; uma recusa ou um 503 não ficam guardados, e a repetição reavalia (BR1.4). O código de hoje confere a carteira antes do comando idempotente; a Code Generation move a conferência para dentro do comando e grava a auditoria da recusa fora da transação desfeita (BR2.4).
4. **Decidido (fundador, 2026-11-21, revisão R-01):** um bot REAL não tem limites próprios e usa só os limites REAL da conta, que a barreira REAL confere antes de cada ordem. Hoje a leitura da versão de limites do bot acontece para todo bot em quatro caminhos. Esses quatro caminhos passam a fazer a leitura só no ramo PAPER:
   - o ciclo (`CycleRunner`), onde a leitura vem antes do desvio `if (bot.real())`;
   - `BotCommandService.stop`;
   - `BotCommandService.resume`;
   - `validate`, que valida um rascunho REAL sem `draftLimits` e sem saldo virtual.

   A regra é o BR1.9, com o teste obrigatório do percurso validar, ativar, rodar o ciclo, pausar, retomar e parar, para um bot REAL e para um bot PAPER.
5. [assumption] A semente do BR10.2 é a que já existe no perfil de teste (`RealTestSeeder`, liberação ACTIVE registrada há 25 horas, ligada por `memetrade.real.seed-test-release`). O teste de guarda é novo.
6. [assumption] O valor US$ com mais de 2 casas é cortado em direção a zero na exibição (BR7.2), como o SOL não arredonda. A conferência e os cálculos continuam no decimal exato.
7. [assumption] Um teto igual a zero é "não definido" (BR3.2).
