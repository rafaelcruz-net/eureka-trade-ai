# Regras de negócio — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

O bloco `yaml` abaixo é a fonte de verdade das regras da U3. O resumo em `functional-spec.md` é derivado dele. Cada regra tem o id `BRx.y`, a categoria (`validation`, `authorization`, `constraint`, `calculation` ou `policy`), onde se aplica, o gatilho, a lógica em SE…ENTÃO, o comportamento na violação e a origem.

A U3 é de interface: as regras dizem o que a tela pede, mostra e recusa. Nenhuma regra manda a tela calcular dinheiro; os totais, as parcelas e os resumos por origem chegam prontos do servidor (U1, U2).

Grupos: BR1 filtro por modo; BR2 período e fuso; BR3 releitura de um bloco; BR4 origem das linhas, parcelas e paginação; BR5 comparação; BR6 nunca somar, subtrair nem converter; BR7 painel; BR8 tenant e 404; BR9 acessibilidade; BR10 360 px; BR11 formatos de dinheiro; BR12 verificação de ponta a ponta.

```yaml
rules:
  # ---------------- BR1 — Filtro por modo (US2.3) ----------------
  - id: BR1.1
    statement: O filtro da lista /bots tem três opções, Todos, Simulado e Real, e começa em Todos.
    category: policy
    applies_to: [BotModeFilter, BotList, ModeFilter]
    trigger: abertura de /bots
    logic: >
      SE o endereço não traz mode ENTÃO o filtro está em Todos (ALL) e a lista mostra os bots dos dois
      modos, cada linha com o seu selo (SIMULADO ou REAL) em texto. SE a pessoa escolhe Simulado ENTÃO
      só bots PAPER aparecem; SE escolhe Real ENTÃO só bots REAL aparecem. A opção vale assim que é
      marcada, sem botão.
    violation: o teste de componente e o spec de ponta a ponta falham.
    source: [FR2.3, US2.3, Q2]

  - id: BR1.2
    statement: O filtro vive no endereço como ?mode=PAPER|REAL e é aplicado no servidor pelo parâmetro market de listBots.
    category: policy
    applies_to: [BotList, "GET /api/v1/bots (listBots)"]
    trigger: troca do filtro ou abertura de /bots com ?mode
    logic: >
      SE o filtro é PAPER ou REAL ENTÃO o endereço fica /bots?mode=PAPER ou /bots?mode=REAL (substitui a
      entrada do histórico) e a tela chama listBots com market igual ao filtro, junto do filtro de
      estado. SE o filtro é ALL ENTÃO o parâmetro sai do endereço e listBots vai sem market. A tela não
      filtra a lista recebida. Voltar para /bots com ?mode mantém o filtro. Um pedido anterior ainda
      pendente é descartado quando a pessoa troca o filtro (só a resposta do último pedido aparece).
      No servidor, market fora de PAPER e REAL responde 400 VALIDATION_FAILED. A chave do endereço é
      mode, com os valores do contrato, por decisão da Q2; o ?modo=simulado do mockup M4.2 fica
      superado.
    violation: a tela nunca mostra um bot de outro modo com o filtro marcado; o teste de rota confere o 400.
    source: [FR2.3, Q2]

  - id: BR1.3
    statement: A lista vazia pelo filtro tem texto próprio, diferente da pessoa sem nenhum bot.
    category: policy
    applies_to: [BotList, BotModeFilter]
    trigger: resposta de listBots com items vazio
    logic: >
      SE totalCount = 0 ENTÃO a tela mostra "Você ainda não tem bots." e "+ Novo bot", o filtro de modo
      não aparece e o aviso do simulado não aparece. SENÃO, SE matchingCount = 0 e o filtro é REAL
      ENTÃO "Você não tem bots reais." e o botão "Ver todos os bots"; SE o filtro é PAPER ENTÃO "Você
      não tem bots simulados." e o mesmo botão. "Ver todos os bots" volta o filtro para Todos e leva o
      foco ao rádio Todos.
    violation: o teste de componente falha se os dois textos forem iguais.
    source: [AC2.3.4, mockups M4.4]

  - id: BR1.4
    statement: Ao abrir /bots, o foco começa no filtro, e cada opção é escolhida pelo teclado.
    category: policy
    applies_to: [BotModeFilter, BotList]
    trigger: abertura de /bots
    logic: >
      SE a tela abre ENTÃO o foco vai para o rádio marcado do filtro (fieldset com legend "Modo" e três
      rádios nativos). As setas trocam e aplicam a opção; Tab sai para o filtro de estado e depois
      para a lista. Trocar a opção não move o foco.
    violation: o teste de componente confere o foco inicial e a troca por seta.
    source: [AC2.3.5, interaction-spec BotModeFilter]

  - id: BR1.5
    statement: O filtro de modo combina com o filtro de estado, e a linha "Mostrando N de M bots." anuncia o resultado.
    category: policy
    applies_to: [BotList, BotModeFilter]
    trigger: resposta de listBots com algum filtro
    logic: >
      Os dois filtros vão juntos ao servidor. SE o filtro de modo é PAPER ou REAL ENTÃO a linha
      "Mostrando N de M bots." aparece, com N = matchingCount e M = totalCount, numa região
      aria-live="polite". Com Todos e sem filtro de estado, a linha não aparece. A tela não conta nem
      soma: os dois números chegam da API.
    violation: o teste de componente falha.
    source: [mockups M4.2, interaction-spec BotModeFilter]

  - id: BR1.6
    statement: Com o filtro Real, a lista não mostra o aviso do simulado nem lê o bloco PAPER.
    category: policy
    applies_to: [BotList]
    trigger: filtro em Real
    logic: >
      SE o filtro é REAL ENTÃO a tela lê C4 com market=REAL, só o bloco REAL, e não mostra o aviso do
      simulado. SE o filtro é PAPER ENTÃO lê C4 com market=PAPER e mostra o aviso quando há bot
      simulado visível. SE é Todos ENTÃO lê C4 sem market, como na U1.
    violation: o teste de componente confere a ausência do aviso e o parâmetro pedido.
    source: [AC4.2.6, AC2.3.3, Q1, Q2]

  - id: BR1.7
    statement: Um valor desconhecido de mode no endereço vale como Todos.
    category: validation
    applies_to: [ModeFilter, BotList]
    trigger: abertura de /bots com ?mode inválido
    logic: >
      SE mode não é PAPER nem REAL ENTÃO o filtro fica em Todos e o parâmetro sai do endereço; nada é
      enviado ao servidor com um valor inválido.
    violation: nenhum 400 chega à pessoa por um endereço digitado.
    source: [validação de entrada na fronteira (construction.md)]

  # ---------------- BR2 — Período e fuso (US7.1, US7.2) ----------------
  - id: BR2.1
    statement: O período de /pnl começa em "Desde o início" e tem cinco opções, com "Hoje" confirmado pelo responsável na Q6.
    category: policy
    applies_to: [PeriodSelector, PnlScreen]
    trigger: abertura de /pnl
    logic: >
      SE o endereço não traz periodo ENTÃO vale "Desde o início" (ALL). As opções, nesta ordem, são
      "Desde o início", "Hoje", "Últimos 7 dias", "Últimos 30 dias" e "Intervalo". As quatro primeiras
      valem assim que são marcadas; o intervalo só vale com "Aplicar". As quatro opções do AC7.1.2
      estão todas presentes; "Hoje" é o acréscimo confirmado na Q6.
    violation: o teste de componente confere as cinco opções e o padrão.
    source: [AC7.1.1, AC7.1.2, Q6]

  - id: BR2.2
    statement: Hoje, 7 dias e 30 dias contam dias inteiros no fuso da pessoa, contando hoje, e ficam fixos quando são aplicados.
    category: calculation
    applies_to: [PeriodSelector, PeriodChoice]
    trigger: escolha de "Hoje", "Últimos 7 dias" ou "Últimos 30 dias"
    logic: >
      hoje = o dia de calendário de agora no fuso da pessoa (relógio injetável). "Hoje" = intervalo de
      hoje até hoje, inclusive: from = 00:00 de hoje e to = 00:00 de amanhã, no fuso da pessoa,
      enviados em UTC. "Últimos 7 dias" = de (hoje − 6) até hoje, inclusive; "Últimos 30 dias" = de
      (hoje − 29) até hoje, inclusive. Os três viram RANGE pelo mesmo cálculo do intervalo (BR2.3). O
      par UTC é calculado UMA vez, quando a opção é aplicada (escolha, abertura da tela ou endereço), e
      todas as leituras seguintes da tela usam o mesmo par, inclusive o "Tentar de novo" de um bloco e
      a comparação. Na virada da meia-noite local, a tela aberta continua no dia aplicado, com as datas
      escritas no texto "Mostrando: …"; o dia novo só vale quando a pessoa escolhe a opção de novo ou
      reabre a tela. Assim os dois blocos nunca ficam em dias diferentes. É cálculo de datas, não de
      dinheiro.
    violation: >
      o teste de unidade em period.test.ts com fuso e relógio fixos falha: Hoje em America/Sao_Paulo às
      2026-11-23T02:30Z (dia 22 local) dá from 2026-11-22T03:00Z e to 2026-11-23T03:00Z; o teste de
      componente confere que uma releitura depois da meia-noite usa o mesmo par, e que trocar
      ?comparar= depois da meia-noite lê C6 com o par fixado, sem recalcular, igual ao dos blocos.
    source: [FR7.1, Q6, interaction-spec PeriodSelector]

  - id: BR2.3
    statement: O intervalo inclui o dia inicial e o dia final inteiros, com no máximo 366 dias, e um erro nunca apaga o que a pessoa digitou.
    category: validation
    applies_to: [PeriodSelector, PeriodChoice, UtcPeriod]
    trigger: clique em "Aplicar" no intervalo
    logic: >
      from = 00:00 do dia inicial no fuso da pessoa; to = 00:00 do dia seguinte ao dia final, no mesmo
      fuso (to excluído), pelo toUtcPeriod de period.ts. SE a data final vem antes da inicial ENTÃO "A
      data final vem antes da inicial. Corrija o campo "Até"." no campo Até. SE passa de 366 dias ENTÃO
      "Escolha um período de até 366 dias.". SE uma data está vazia ou inválida ENTÃO "Informe a data
      no formato dd/mm/aaaa." no campo com erro. SE o dia inicial é depois de hoje (intervalo
      inteiro no futuro) ENTÃO "O período começa depois de hoje. Escolha um dia inicial até hoje." no
      campo De; um intervalo que começa até hoje e termina depois de hoje é aceito, porque não existe
      lançamento futuro. O limite de 366 dias é a duração em UTC entre from e to, a mesma conta do
      toUtcPeriod e do servidor: um intervalo de 366 dias de calendário que atravessa o fim de um
      horário de verão ganha uma hora, passa de 366 × 24 h e recebe a mensagem dos 366 dias; um de 365
      dias sempre passa. Em qualquer erro: o campo fica com aria-invalid, as datas digitadas ficam,
      nenhum pedido sai e os blocos continuam com o último período válido.
    violation: >
      nenhum pedido com período inválido sai da tela; o teste de componente confere os quatro textos;
      period.test.ts ganha os casos do intervalo futuro e dos 366 dias com horário de verão (por
      exemplo America/New_York, que atrasa o relógio em novembro).
    source: [AC7.1.3, AC7.1.4, Q6]

  - id: BR2.4
    statement: A tela envia o período à API só em instantes UTC; "Desde o início" não envia instante.
    category: constraint
    applies_to: [PnlScreen, ComparisonView, "C4", "C6"]
    trigger: qualquer leitura de C4 ou C6 com período
    logic: >
      SE o período é ALL ENTÃO o pedido vai com period=ALL (ou sem period), sem from nem to. SENÃO vai
      com period=RANGE, from e to em ISO 8601 UTC (com Z). O fuso da pessoa nunca vai à API. Os
      instantes que a API devolve (at, unrealizedAsOf, createdAt, period.from, period.to) são lidos como
      UTC.
    violation: o teste de componente confere a URL pedida; um 400 da API vira erro do bloco.
    source: [AC7.2.3, FR7.2, project.md (ALWAYS gravar e trafegar instantes em UTC)]

  - id: BR2.5
    statement: Datas e horas aparecem no fuso da pessoa, no formato dd/mm HH:MM.
    category: policy
    applies_to: [BotPnlLines, ProratedLines, ComparisonView, PnlModeBlock, PeriodLabel]
    trigger: exibição de um instante
    logic: >
      SE a tela mostra um instante ENTÃO converte do UTC para o fuso da pessoa (o do navegador, ou o
      passado por prop nos testes) e escreve dd/mm HH:MM. Exemplo: 2026-11-20T02:30Z em
      America/Sao_Paulo é 19/11 23:30. A coluna de data diz "seu fuso".
    violation: o teste de componente com fuso fixo falha.
    source: [AC7.2.2, AC3.10.1]

  - id: BR2.6
    statement: O período escolhido fica no endereço e vale para os dois blocos e para a comparação.
    category: policy
    applies_to: [PnlScreen, PeriodSelector, ComparisonView]
    trigger: troca de período aplicada
    logic: >
      O endereço guarda ?periodo=hoje, 7d, 30d ou intervalo&de=AAAA-MM-DD&ate=AAAA-MM-DD; ALL não escreve o
      parâmetro. Um endereço com período inválido abre em "Desde o início". Um intervalo vindo do
      endereço passa pelas mesmas validações de BR2.3: se começa depois de hoje (ou falha outra
      validação), a tela mostra a mensagem do campo, mantém as datas nos campos e aplica "Desde o
      início". Trocar ?comparar= reusa o par UTC fixado (BR2.2), sem recalcular: depois da meia-noite,
      a comparação e os blocos continuam lendo o mesmo dia. A troca de período relê os
      dois blocos (um pedido de C4 sem market) e, com a comparação aberta, relê C6 com o mesmo
      período. Os dois blocos continuam separados, sem total geral. Uma resposta de um período antigo
      que chega depois da troca é descartada.
    violation: >
      o teste de componente confere que os dois blocos e a comparação usam o mesmo período; um caso abre
      /pnl?periodo=intervalo com o dia inicial depois de hoje e confere a mensagem, o "Desde o início"
      aplicado e nenhum pedido com RANGE; outro troca ?comparar= depois da meia-noite (relógio injetável)
      e confere que C6 recebe o mesmo from e to dos blocos.
    source: [AC7.1.5, Q6]

  - id: BR2.7
    statement: O não realizado é sempre o de agora, e a tela diz isso.
    category: policy
    applies_to: [PeriodSelector, PnlModeBlock, PeriodLabel]
    trigger: qualquer período aplicado
    logic: >
      A tela escreve "Mostrando: <período no fuso da pessoa> · realizado no período; não realizado de
      agora" numa região aria-live="polite", e cada bloco mantém a linha "Não realizado: posições
      abertas agora (marcação às HH:MM)". O valor vem da API (U2); a tela não o recalcula.
    violation: o teste de componente confere o texto.
    source: [AC7.2.1, Q6]

  - id: BR2.8
    statement: O painel e o detalhe do bot mostram sempre "desde o início", escrito na tela.
    category: policy
    applies_to: [DashboardModeCards, BotDetail]
    trigger: abertura do painel ou do detalhe
    logic: >
      O painel lê C4 com period=ALL e escreve "Resultados por modo · desde o início". O detalhe lê o
      P&L do bot e as linhas sempre desde o início ("P&L do bot (desde o início)"). Nenhum dos dois
      tem seletor de período.
    violation: o teste de componente confere o texto e o parâmetro.
    source: [mockups M6.1 (decisão D2), M7.1, Q5]

  - id: BR2.9
    statement: A tabela "Resultado por token" do PAPER só aparece com um período concreto; em "Desde o início" a tela mostra uma nota no lugar dela.
    category: policy
    applies_to: [PnlScreen, PnlModeBlock, "GET /api/v1/pnl (existente)"]
    trigger: período aplicado em /pnl
    logic: >
      Decisão do fundador (2026-11-23). A tabela vem de GET /api/v1/pnl, que exige from e to (no máximo
      366 dias) e não aceita "desde o início". SE o período é Hoje, Últimos 7 dias, Últimos 30 dias ou
      um intervalo válido ENTÃO a tabela "Ver por token" do bloco Simulado lê GET /api/v1/pnl com o
      mesmo par UTC dos blocos e aparece, com o próprio carregando, erro com código e "Tentar de novo".
      SE o período é "Desde o início" ENTÃO nenhum pedido a GET /api/v1/pnl sai e o lugar da tabela
      mostra a nota "Escolha um período para ver o resultado por token". Os totais por modo (os dois
      blocos de C4) aparecem sempre, em qualquer período. A conferência e o ledger PAPER, embaixo,
      continuam como hoje, com o selo SIMULADO e as próprias leituras, sem depender do período. Nenhuma
      rota muda.
    violation: >
      o teste de componente confere: em ALL, a nota e nenhum pedido a GET /api/v1/pnl; com 7 dias, o
      pedido com o mesmo from e to dos blocos; os dois blocos presentes nos dois casos.
    source: [decisão do fundador 2026-11-23, review-01 R-02, mockups M5.1 (suposição 4)]

  # ---------------- BR3 — Releitura de um bloco (PQ-6) ----------------
  - id: BR3.1
    statement: C4 aceita o parâmetro opcional market e, com ele, responde só o bloco pedido.
    category: constraint
    applies_to: ["GET /api/v1/pnl/by-mode (C4)", PnlByModeResponse]
    trigger: leitura de C4
    logic: >
      SE market está ausente ENTÃO a resposta traz period, paper e real, como hoje. SE market = PAPER
      ENTÃO traz period e paper; SE market = REAL ENTÃO traz period e real. Em nenhum caso há outra
      chave além de period, paper e real. O bloco não pedido é OMITIDO do JSON, nunca enviado como null
      (o DTO de resposta só inclui campos não nulos, por exemplo com a inclusão NON_NULL do
      serializador). O período vale igual com ou sem market. SE market tem outro valor ENTÃO 400
      VALIDATION_FAILED. O teste de contrato e o teste de rota cobrem os três casos: sem market, as
      chaves paper e real presentes e não nulas; market=PAPER, a chave real ausente do JSON; market=REAL,
      a chave paper ausente do JSON; e nos três a ausência de qualquer campo de total fora dos blocos.
      Só o apps/web consome C4: o apps/admin não chama by-mode (conferido em apps/admin/src), então o
      check:api do admin não muda.
    violation: o GuestOpenApiContractTest e o teste de rota falham; o commit não entra.
    source: [Q1, PQ-6, ADR-003, regra 6 de posse de C4]

  - id: BR3.2
    statement: O "Tentar de novo" de um bloco relê só aquele bloco, no mesmo período; o outro bloco não muda.
    category: policy
    applies_to: [PnlScreen, PnlModeBlock, DashboardModeCards, BotList]
    trigger: clique em "Tentar de novo" de um bloco em erro
    logic: >
      SE o bloco X está em erro e a pessoa clica em "Tentar de novo" ENTÃO a tela pede C4 com market=X
      e o período em vigor, põe só o bloco X em carregando e, com a resposta, atualiza só o bloco X. O
      outro bloco não volta a carregar, não é relido e não muda de valor. A carga inicial e a troca de
      período continuam com um pedido sem market.
    violation: o teste de componente confere um único pedido com market=X e o outro bloco intacto.
    source: [Q1, mockups M5.6, interaction-spec PnlModeBlock]

  - id: BR3.3
    statement: Um bloco UNAVAILABLE ou uma falha da rota viram o estado de erro com o código para o suporte.
    category: policy
    applies_to: [PnlModeBlock, BlockLoad]
    trigger: resposta de C4
    logic: >
      SE o bloco vem com status UNAVAILABLE ENTÃO o bloco mostra "Não foi possível carregar o resultado
      simulado/real.", "Código para o suporte: <correlationId>" e "Tentar de novo". SE a rota falha
      inteira (rede, 5xx) ENTÃO cada bloco pedido mostra o mesmo erro com o código da falha. SE é 401
      ENTÃO a sessão expira pelo fluxo existente. SE é 403 TERMS_REACCEPTANCE_REQUIRED ENTÃO segue o
      fluxo de novo aceite existente.
    violation: nenhum erro silencioso; o teste de componente confere o código mostrado.
    source: [team.md (Erros da API, sem falha silenciosa), Q5, contract C4 (estado parcial)]

  - id: BR3.4
    statement: Depois de "Tentar de novo", o foco vai para o título do bloco relido.
    category: policy
    applies_to: [PnlModeBlock, DashboardModeCards]
    trigger: resposta da releitura
    logic: >
      SE a releitura termina (com valor ou com erro) ENTÃO o foco vai para o título do bloco relido (h2
      em /pnl, h3 no painel, com tabIndex -1). O foco nunca vai para o outro bloco.
    violation: o teste de componente confere o elemento focado.
    source: [interaction-spec PnlModeBlock (Focus management), AC4.4.4]

  # ---------------- BR4 — Origem das linhas, parcelas e paginação (US3.10, C5) ----------------
  - id: BR4.1
    statement: Cada linha do P&L de um bot REAL mostra a origem em texto.
    category: policy
    applies_to: [BotPnlLines, ProratedLines, BotPnlLineItem, ProratedLine]
    trigger: exibição de uma linha de C5 ou de uma parcela de C4
    logic: >
      origin BOT = "Bot"; LIQUIDATION = "Fechado por liquidação"; CONDITIONAL = "Fechado por
      condicional"; SOLD_BY_OTHER_BOT = "Vendido por outro bot" (chaves pnl.origins.*). O texto é
      inteiro, sem abreviação; na tabela estreita ele quebra a linha. Um valor de origin desconhecido
      aparece como o próprio código, nunca some.
    violation: o teste de componente confere os quatro textos.
    source: [AC3.10.2, AC3.10.4, Q3, design-system-mapping]

  - id: BR4.2
    statement: As linhas do detalhe vêm da mais recente para a mais antiga, na ordem da rede, sem reordenar na tela.
    category: policy
    applies_to: [BotPnlLines, BotPnlLinesState]
    trigger: cada página de C5
    logic: >
      A tela mostra os itens na ordem em que a API os devolve (ordem da rede, decrescente) e anexa cada
      página nova no fim. A tela não ordena por valor nem por data.
    violation: o teste de componente confere a ordem.
    source: [Q3, mockups M7.1]

  - id: BR4.3
    statement: As linhas do detalhe são paginadas por cursor, 50 por página, com "Carregar mais".
    category: policy
    applies_to: [BotPnlLines, "GET /api/v1/bots/{botId}/pnl/lines (C5)"]
    trigger: abertura do detalhe de um bot REAL e clique em "Carregar mais"
    logic: >
      A primeira leitura pede limit=50 sem cursor. SE nextCursor não é nulo ENTÃO aparece "Carregar
      mais"; o clique pede a página seguinte com o cursor, desabilita o botão enquanto carrega e, com
      a resposta, anexa os itens e leva o foco à primeira linha nova. SE a página seguinte falha ENTÃO
      as linhas já mostradas ficam, e o erro com o código e "Tentar de novo" aparece junto do botão,
      repetindo o mesmo cursor. SE nextCursor é nulo ENTÃO o botão some. Um 400 de cursor inválido é
      tratado como erro do bloco de linhas.
    violation: o teste de componente confere o cursor enviado, o foco e a falha da segunda página.
    source: [Q3, contract C5, interaction-spec BotPnlLines]

  - id: BR4.4
    statement: Cada linha mostra a data, o lado, o token, o resultado em SOL, a taxa e a origem; a compra mostra "—" no resultado.
    category: policy
    applies_to: [BotPnlLines, BotPnlLineItem]
    trigger: exibição de uma linha de C5
    logic: >
      Colunas "Data (seu fuso)", "Lado" ("Compra" ou "Venda"), "Token", "Resultado (SOL)", "Taxa (SOL)"
      e "Origem". SE side = BUY ENTÃO o resultado é "—", lido pelo leitor de tela como "sem resultado"
      (o "0" da API numa compra não é dado ausente e não vira "desconhecido"). SE side = SELL ENTÃO o
      resultado é o realizedLamports com sinal e palavra. A taxa aparece como valor pago, com "−".
    violation: o teste de componente confere as seis colunas e a compra.
    source: [AC3.10.1, contract C5 (compra realiza 0), mockups M7.1]

  - id: BR4.5
    statement: As linhas de C5 servem só a bots REAL; o detalhe de um bot PAPER continua com as operações simuladas de hoje.
    category: constraint
    applies_to: [BotDetail, BotPnlLines]
    trigger: abertura de /bots/{id}
    logic: >
      SE o bot é REAL ENTÃO o detalhe mostra o BotPnlLines (C5) e NÃO mostra o aviso do simulado (é uma
      tela só de P&L real). SE o bot é PAPER ENTÃO o detalhe não chama C5 e mantém as seções que já
      existem (operações simuladas, limites, decisões), com o aviso do simulado. Um 404 de C5 para um bot que a tela achava REAL vira "Bot não encontrado." (BR8.2).
    violation: o teste de componente confere que nenhum pedido a C5 sai para um bot PAPER.
    source: [contract C5 (suposição: linhas só REAL), mockups M7.2]

  - id: BR4.6
    statement: As linhas do detalhe e o P&L do bot vêm do mesmo cálculo da U2, desde o início, e a tela não soma as linhas.
    category: constraint
    applies_to: [BotDetail, BotPnlLines, BotDetailPnl, "C5", "C10 botLines"]
    trigger: abertura do detalhe
    logic: >
      O cabeçalho do detalhe lê GET /api/v1/bots/{id} (nome, modo, estado) e o bloco de C4 com market
      igual ao modo do bot e period=ALL; o P&L do bot é a linha desse bloco procurada pelo botId. O
      custo de ler o bloco inteiro (todos os bots do modo e até 10 parcelas por bot) para mostrar uma
      linha é aceito: é a mesma leitura de um bloco de /pnl, dentro do prazo do bloco da U2 (800 ms) e
      da meta do NFR1 (p95 de 1 s; C4 mediu 213 ms na U1), e não pede rota nova. As linhas vêm de C5, que usa o C10
      botLines sobre o mesmo cálculo (RealBotPnl da U2). C5 é sempre "desde o início": é assim que esta
      Unit lê "o mesmo período da tela" da Q3, porque o detalhe não tem seletor (BR2.8), e é o que o
      contrato de C5 já diz (sem parâmetro de período). A tela não soma as linhas para chegar ao total, nem confere uma coisa com a
      outra. SE a linha do bot não está em C4 (bot sem P&L ainda) ENTÃO o cabeçalho mostra "—" e o
      estado vazio das linhas.
    violation: o teste de componente confere que o total mostrado é o da API.
    source: [Q3, contract C10, mockups M7.1]

  - id: BR4.7
    statement: O bloco REAL mostra as parcelas rateadas de cada bot com o resumo por origem e o detalhe que abre e fecha.
    category: policy
    applies_to: [ProratedLines, PnlModeBlock, ProratedSharesView]
    trigger: bloco REAL pronto, variante full
    logic: >
      SE prorated.count = 0 ENTÃO o componente não aparece (nem resumo, nem botão). SENÃO, para cada
      item de summary, uma linha "inclui <valor> <origem em minúsculas>" com o valor da API; o botão
      "Ver N linhas rateadas" (N = count) abre a lista das parcelas recebidas (até 10), cada uma com a
      data no fuso da pessoa, o token, a origem, o recebido, a taxa e o realizado. SE count > 10 ENTÃO
      a lista termina com o link "Ver todas no detalhe do bot" para /bots/{id}. O estado aberto vive
      só na página.
    violation: o teste de componente confere o bot sem parcela e o caso com mais de 10.
    source: [US3.10, FR3.6, mockups M5.3, interaction-spec ProratedLines, contract C4 ProratedShares]

  - id: BR4.8
    statement: A linha "Sem bot" aparece só quando tem valor, sempre com o motivo em texto.
    category: policy
    applies_to: [PnlModeBlock, UnownedLineView]
    trigger: bloco REAL pronto, variante full
    logic: >
      SE algum dos valores de unowned (realizado, taxas, não realizado) é diferente de "0" ou é
      desconhecido ENTÃO aparece a linha "Sem bot" com os valores e, para cada motivo, o texto de
      pnl.noBotReason.<motivo> ("Motivo: excedente sem dono", "Motivo: venda sem custo registrado",
      "Motivo: registro da ordem ausente"). A linha não é link. SE todos são "0" ENTÃO a linha não
      aparece. A comparação com "0" é de texto, sem conta.
    violation: o teste de componente confere os três motivos e a linha ausente quando zerada.
    source: [FR3.4, FR5.3, mockups M5.4, design-system-mapping pnl.noBotReason.*]

  # ---------------- BR5 — Comparação (US8.1, C6, PQ-8) ----------------
  - id: BR5.1
    statement: A expressão "a mesma estratégia" significa o mesmo templateId; o select de bot real só oferece bots REAL com o templateId do bot simulado escolhido.
    category: validation
    applies_to: [ComparisonView, ComparisonSelection]
    trigger: escolha do bot simulado
    logic: >
      As opções vêm da lista de bots da pessoa (listBots, com market e templateId). "Bot simulado" lista
      os bots PAPER; "Bot real" fica desabilitado ("Escolha primeiro o bot simulado.") até haver um
      bot simulado e então lista só os bots REAL com o mesmo templateId. Trocar o bot simulado limpa o
      bot real quando o templateId não bate. Com um único par possível, os dois já vêm escolhidos. SE
      não há nenhum par possível ENTÃO a vista mostra "Para comparar, você precisa de um bot simulado e
      de um bot real da mesma estratégia." e "+ Novo bot".
    violation: o teste de componente confere a lista filtrada e o estado sem par.
    source: [FR8.1, Q4, OQ4]

  - id: BR5.2
    statement: Dois bots com templateId diferente recebem 422 TEMPLATE_MISMATCH, e a tela explica o motivo.
    category: validation
    applies_to: ["GET /api/v1/pnl/compare (C6)", ComparisonView]
    trigger: leitura de C6 com um par de estratégias diferentes (por exemplo, um endereço editado)
    logic: >
      O servidor compara os templateId dos dois bots, já filtrados pelo tenant. SE diferem ENTÃO 422
      VALIDATION_FAILED com reason TEMPLATE_MISMATCH, sem nenhum valor de P&L. A tela mostra "Esses dois
      bots usam estratégias diferentes. Escolha um bot real da mesma estratégia do bot simulado.",
      limpa o bot real, mantém o bot simulado e leva o foco ao select "Bot real". A verificação de dono
      (404) vem antes da de estratégia, para um 422 nunca revelar a estratégia de um bot de outra pessoa.
    violation: o teste de rota confere o 422 e a ausência de dado; o teste de componente confere o texto.
    source: [Q4, PQ-8, contract C6]

  - id: BR5.3
    statement: Um bot de outra pessoa, inexistente ou do modo errado recebe 404, e o lado dele mostra "Bot não encontrado." sem nenhum dado.
    category: authorization
    applies_to: ["GET /api/v1/pnl/compare (C6)", ComparisonView, ComparisonSideState]
    trigger: leitura de C6
    logic: >
      SE um dos ids não é um bot da pessoa no modo esperado (PAPER no paperBotId, REAL no realBotId)
      ENTÃO 404 NOT_FOUND com reason PAPER_BOT ou REAL_BOT e nenhum dado dos dois bots. A tela mostra
      "Bot não encontrado." no lado indicado, limpa o select desse lado e leva o foco a ele; o outro
      lado fica vazio até a nova escolha.
    violation: o teste de isolamento entre tenants confere o 404 e a resposta sem dado.
    source: [AC8.1.4, contract C6, team.md (isolamento entre contas)]

  - id: BR5.4
    statement: Cada lado mostra o próprio bot na própria moeda; o aviso do simulado aparece só no lado simulado.
    category: policy
    applies_to: [ComparisonView]
    trigger: resposta 200 de C6
    logic: >
      O lado PAPER (h3 "<nome>, simulado, em dólares") mostra total, realizado, não realizado e taxas
      em US$, estado e data de criação, e o aviso "O resultado simulado não garante o resultado real."
      vindo de disclaimer. O lado REAL (h3 "<nome>, real, em SOL") mostra os mesmos campos em SOL e não
      mostra o aviso. Abaixo dos dois, a frase fixa "Os valores ficam cada um na sua moeda e não são
      comparados por conta.".
    violation: o teste de componente confere as moedas e o aviso só no lado PAPER.
    source: [AC8.1.1, AC8.1.3, AC4.2.5, Q4]

  - id: BR5.5
    statement: Cada lado tem estado próprio; um lado UNAVAILABLE mostra erro só nele, e "Tentar de novo" atualiza só aquele lado.
    category: policy
    applies_to: [ComparisonView, ComparisonSideState]
    trigger: resposta de C6 com um lado UNAVAILABLE, ou falha da rota
    logic: >
      SE um lado vem UNAVAILABLE ENTÃO esse lado mostra o erro com o correlationId e "Tentar de novo"; o
      outro lado mostra os valores. "Tentar de novo" relê C6 com os mesmos ids e período e atualiza só
      o lado que estava em erro; o outro lado não volta a carregar. SE a rota falha inteira ENTÃO os
      dois lados mostram o erro com o código da falha.
    violation: o teste de componente confere o lado intacto.
    source: [mockups M5.7 (estados), Q5]

  - id: BR5.6
    statement: A escolha da comparação fica no endereço, e quem abre o link vê a comparação aberta.
    category: policy
    applies_to: [ComparisonView, ComparisonSelection, PnlScreen]
    trigger: escolha dos dois bots ou abertura de /pnl?comparar=
    logic: >
      Com os dois bots escolhidos, o endereço fica /pnl?comparar=<paperBotId>,<realBotId> (mais o
      período, se houver). Abrir /pnl com comparar abre a vista e lê C6. SE comparar não são dois uuid
      separados por vírgula ENTÃO a vista abre vazia, sem pedido. Fechar a vista tira o parâmetro.
    violation: o teste de componente confere a ida e a volta do endereço.
    source: [mockups M5.7, interaction-spec ComparisonView]

  # ---------------- BR6 — Nunca somar, subtrair nem converter ----------------
  - id: BR6.1
    statement: Nenhuma tela, componente ou função do apps/web soma, subtrai ou converte valores de dinheiro, entre modos ou dentro de um modo.
    category: constraint
    applies_to: [PnlModeBlock, ProratedLines, BotPnlLines, ComparisonView, DashboardModeCards, BotList, BotDetail]
    trigger: qualquer exibição de P&L
    logic: >
      Todo valor mostrado (totais, partes, linhas, resumos por origem, linha "Sem bot") chega pronto da
      API e só é formatado. A tela nunca soma as linhas de uma página, nunca calcula diferença,
      percentual ou razão entre dois valores, e nunca converte US$ em SOL nem SOL em US$. Comparar um
      texto com "0" para decidir se uma linha aparece é permitido; aritmética não é.
    violation: a regra de lint de dinheiro e o teste de componente falham; o commit não entra.
    source: [ADR-003, C17, interaction-spec (regras comuns), project.md (NEVER somar os modos)]

  - id: BR6.2
    statement: Nenhum campo, linha ou texto da lista, do painel, de /pnl, do detalhe ou da comparação traz um total geral dos dois modos.
    category: constraint
    applies_to: [DashboardModeCards, PnlScreen, ComparisonView, BotList, BotDetail, "C4", "C5", "C6", BotPage]
    trigger: qualquer resposta ou tela da U3
    logic: >
      Não existe "Total geral", nem um valor que dependa ao mesmo tempo de um valor PAPER e de um valor
      REAL, em nenhuma resposta (C4 com e sem market, C5, C6, BotPage) nem em nenhuma tela. Os únicos
      números novos fora dos blocos são as contagens de bots de BotPage. O teste de contrato de C4 e de
      C6 e o teste de propriedade "nenhum terceiro número" continuam verdes; o spec de ponta a ponta
      procura "Total geral" no painel, em /pnl e na comparação.
    violation: os testes de contrato, a regra NEVER_SUM_MODES e o spec falham.
    source: [AC4.1.2, FR4.1, NFR3, project.md (NEVER somar os modos)]

  - id: BR6.3
    statement: Nenhuma lista, ranking ou ordenação mistura os dois modos por valor.
    category: constraint
    applies_to: [BotList, ComparisonView, PnlModeBlock]
    trigger: exibição de listas de bots ou da comparação
    logic: >
      A lista /bots fica na mesma ordem da API em todos os filtros: a ordem e o cursor de listBots que
      já existem (updated_at desc, bot_id desc), nunca por valor nem misturando os modos por valor; a
      tela não reordena (conflito 1 do plano da Code Generation da U3, decisão do responsável na
      aprovação do plano). Os bots de cada bloco ficam na ordem de criação. A comparação não tem
      ordenação, ranking, destaque de melhor nem vencedor.
    violation: o teste de componente confere a ordem e a ausência de destaque.
    source: [AC8.1.2, AC2.1.5, Q4]

  # ---------------- BR7 — Painel (US4.3) ----------------
  - id: BR7.1
    statement: O painel mostra dois cartões, "Resultado simulado" e "Resultado real", lado a lado e sem total geral.
    category: policy
    applies_to: [DashboardModeCards, DashboardScreen]
    trigger: abertura do painel /
    logic: >
      Uma section "Resultados por modo · desde o início" com dois PnlModeBlock compact: "Resultado
      simulado" (US$) e "Resultado real" (SOL), na ordem Simulado e Real. Cada cartão mostra o total, o
      realizado e as taxas e o link "Ver o resultado simulado" ou "Ver o resultado real" para /pnl. O
      PnlCard ("P&L PAPER (simulado)", do dia UTC) e o DashboardRealPnl saem do painel. O BotsCard
      continua.
    violation: o teste de componente e o spec falham.
    source: [AC4.3.1, AC4.3.4, Q5, mockups M6.1]

  - id: BR7.2
    statement: Cada cartão do painel tem estados próprios, e o erro de um nunca esconde o outro.
    category: policy
    applies_to: [DashboardModeCards, DashboardModeCard]
    trigger: leitura de C4 do painel
    logic: >
      A carga lê C4 uma vez, sem market e com period=ALL. Cada cartão tem carregando ("Calculando o
      resultado simulado…"), pronto, desconhecido (total "desconhecido (depende do não realizado)" com
      o realizado e as taxas exatos), vazio e indisponível ("Não foi possível carregar o resultado
      simulado. Os outros quadros continuam funcionando.", código e "Tentar de novo", que relê só
      aquele cartão por BR3.2).
    violation: o teste de componente confere os cinco estados por cartão.
    source: [Q5, mockups M6.3, AC4.5.3]

  - id: BR7.3
    statement: O cartão simulado mostra o aviso fixo sempre que mostra um valor; o cartão real nunca o mostra.
    category: policy
    applies_to: [DashboardModeCards]
    trigger: cartão simulado pronto ou desconhecido
    logic: >
      SE o cartão PAPER mostra algum valor ENTÃO mostra "O resultado simulado não garante o resultado
      real." (role="note"), vindo do disclaimer de C4. SE o cartão PAPER está vazio, carregando ou em
      erro ENTÃO o aviso não aparece. O cartão REAL nunca mostra o aviso.
    violation: o teste de componente confere a presença e a ausência.
    source: [AC4.2.2, FR4.2]

  - id: BR7.4
    statement: O cartão real vazio diz "Sem bots reais." e o link certo para o motivo.
    category: policy
    applies_to: [DashboardModeCards, DashboardModeCard]
    trigger: cartão REAL com empty = true
    logic: >
      O painel lê C2 (GET /api/v1/bots/real-eligibility) junto de C4. SE o REAL está vazio e a
      elegibilidade é available ENTÃO "Sem bots reais." e "→ Criar bot real" (/bots/new). SE o motivo
      é WALLET_INACTIVE ENTÃO "Sem bots reais." e "→ Ativar a carteira do bot" (/real). SE é outro
      motivo (KILL_SWITCH, REAL_DISABLED, NOT_RELEASED, QUARANTINE) ENTÃO "Sem bots reais." e o texto do
      motivo, sem link de criação. SE C2 falhou ENTÃO só "Sem bots reais.", sem link. O cartão PAPER
      vazio diz "Sem bots simulados." e "→ Criar bot simulado".
    violation: o teste de componente confere os quatro casos.
    source: [AC4.3.2, AC4.3.3, mockups M6.2]

  - id: BR7.5
    statement: Cada cartão do painel acompanha os eventos do canal ao vivo do próprio modo, sem encher o leitor de tela.
    category: policy
    applies_to: [DashboardModeCards]
    trigger: evento do canal ao vivo do painel (DashboardMarkPoller)
    logic: >
      O canal ao vivo não muda. Cartão simulado: SE chega pnl.updated (enviado quando muda o
      ledgerMark do ledger PAPER) ENTÃO relê C4 com market=PAPER e period=ALL. Cartão real: SE chega
      real.order.state com state CONFIRMED ou FAILED (ordem REAL que terminou na rede, com venda,
      compra ou taxa) ou real.conditional.state (mudança de uma condicional REAL, que pode vender por
      liquidação ou condicional), os dois enviados por sendRealChanges e sendRealExitChanges, ENTÃO
      relê C4 com market=REAL e period=ALL. Os demais estados de real.order.state (PROPOSED, QUOTED,
      SIGNED, SENT, AMBIGUOUS, CANCELLED) e real.signing.requested não releem. Cada cartão relê no
      máximo uma vez a cada 30 s: um evento que chega dentro da janela marca uma releitura pendente,
      feita no fim da janela, então nenhum evento se perde. Um cartão nunca relê por evento do outro
      modo. A região do total de cada cartão (aria-live="polite", aria-atomic="true") anuncia o valor
      novo no máximo uma vez a cada 30 s por cartão; a carga inicial não é anunciada. O não realizado
      por variação de preço não tem evento nos dois modos: ele muda na abertura, no "Tentar de novo" e
      junto da releitura por evento.
    violation: >
      testes de componente com relógio injetável, um por cartão: pnl.updated relê só o simulado;
      real.order.state CONFIRMED e real.conditional.state releem só o real; real.order.state SENT não
      relê; dois eventos dentro de 30 s fazem uma releitura imediata e uma no fim da janela.
    source: [Q5, review-01 R-01, accessibility-checklist (painel), DashboardMarkPoller (pnl.updated, sendRealChanges, sendRealExitChanges)]

  # ---------------- BR8 — Tenant e 404 ----------------
  - id: BR8.1
    statement: O tenant e a pessoa vêm sempre do token; a tela nunca envia id de tenant ou de pessoa.
    category: authorization
    applies_to: ["C4", "C5", "C6", "listBots", BotDetail, ComparisonView]
    trigger: qualquer pedido da U3
    logic: >
      Os pedidos levam só ids de bot, período, market, cursor e limit. O servidor filtra toda leitura
      pelo tenant e pela pessoa do token (C4, C5 por C10 botLines, C6, listBots com market e as
      contagens de BotPage).
    violation: o teste de isolamento entre tenants falha; o commit não entra.
    source: [project.md (ALWAYS validar o tenant no servidor), team.md (tenant no código), contract (regras comuns)]

  - id: BR8.2
    statement: O detalhe de um bot de outra pessoa ou inexistente devolve 404 e a tela não mostra nenhum dado do bot.
    category: authorization
    applies_to: [BotDetail, BotPnlLines, "GET /api/v1/bots/{botId}", "C5"]
    trigger: abertura de /bots/{id}
    logic: >
      SE GET /api/v1/bots/{id} ou C5 responde 404 ENTÃO a tela mostra só "Bot não encontrado." em
      role="alert" e o link "Voltar para Meus bots": sem nome, modo, P&L, linhas nem código. A tela é a
      mesma para bot de outra pessoa e para bot inexistente. C5 também responde 404 para um bot PAPER.
      O teste de isolamento confere que a resposta 404 não traz nenhum dado.
    violation: o teste de rota entre dois tenants falha.
    source: [AC3.10.3, AC8.1.4, contract C5, team.md (acesso por ID devolve 404 e nunca o dado)]

  # ---------------- BR9 — Acessibilidade ----------------
  - id: BR9.1
    statement: Todo texto do painel, do detalhe, da comparação e do filtro tem contraste mínimo de 4,5:1 no tema escuro, e a cor nunca é o único sinal.
    category: constraint
    applies_to: [DashboardModeCards, BotDetail, BotPnlLines, ProratedLines, ComparisonView, BotModeFilter, PeriodSelector]
    trigger: qualquer componente da U3
    logic: >
      Os componentes usam só os tokens já medidos (text, textMuted, accent, danger, unknown, warning
      sobre surface, todos acima de 4,5:1). Ganho e perda levam "+" ou "−" e a palavra "(ganho)" ou
      "(perda)"; os selos levam SIMULADO ou REAL em texto; "desconhecido" leva a palavra; o aviso do
      simulado (⚠, warning) e o "◐ Dado parcial" (unknown) têm símbolo, rótulo e borda diferentes.
    violation: o teste de componente confere os textos; a revisão confere os tokens.
    source: [AC4.4.1, AC4.4.2, NFR5, Q7]

  - id: BR9.2
    statement: O total de cada modo tem rótulo com o modo e a moeda, e a mudança é anunciada por aria-live.
    category: policy
    applies_to: [DashboardModeCards, ComparisonView, PnlModeBlock]
    trigger: exibição de um total
    logic: >
      Painel: "Total simulado, em dólares" e "Total real, em SOL". Comparação: "Total do bot simulado,
      em dólares" e "Total do bot real, em SOL". Detalhe: "Total do bot, em dólares" ou "Total do bot,
      em SOL". A região do total é aria-live="polite" e aria-atomic="true", anunciada só depois da
      carga inicial (troca de período, de escolha, "Tentar de novo" ou canal ao vivo) e no máximo uma
      vez a cada 30 s por bloco.
    violation: o teste de componente confere o nome acessível.
    source: [AC4.4.3, Q5, Q7]

  - id: BR9.3
    statement: As telas têm cabeçalhos na ordem certa, rótulos visíveis e foco visível, e cada tabela tem caption e cabeçalho por coluna.
    category: policy
    applies_to: [BotDetail, BotPnlLines, ProratedLines, ComparisonView, DashboardModeCards, PeriodSelector, BotModeFilter]
    trigger: qualquer tela da U3
    logic: >
      Detalhe: h1 com o nome do bot e o selo, h2 "P&L do bot" e h2 "Linhas do P&L"; a tabela tem
      caption "Linhas do P&L de <bot>" e th scope="col" em cada coluna; a origem é lida em texto.
      Painel: section "Resultados por modo" com h3 por cartão; o foco inicial continua no primeiro
      cartão. Comparação: h2 "Comparação" e h3 por lado; ao abrir, o foco vai ao select "Bot
      simulado"; ao fechar, volta ao botão. Os controles são nativos (rádios, select, botão) com
      rótulos visíveis, e o foco usa o :focus-visible existente. Os botões que abrem e fecham têm
      aria-expanded e aria-controls.
    violation: o teste de componente confere a estrutura.
    source: [AC3.10.4, AC4.4.1, AC4.4.5, NFR5, Q7, accessibility-checklist]

  # ---------------- BR10 — 360 px ----------------
  - id: BR10.1
    statement: Em 360 px nenhuma tela da U3 rola para o lado; tabelas largas rolam dentro de um TableScroll.
    category: constraint
    applies_to: [BotDetail, BotPnlLines, ProratedLines, ComparisonView, DashboardModeCards, BotList, PnlScreen]
    trigger: qualquer largura de 360 a 767 px
    logic: >
      Um único ponto de troca para todos os componentes da U3: 768 px. Abaixo de 768 px, as linhas de
      C5 (BotPnlLines) e as parcelas rateadas (ProratedLines) viram cartões com rótulo visível em cada
      valor ("Data:", "Lado:", "Recebido:"…), os blocos e os lados da comparação ficam empilhados
      (Simulado e depois Real), os filtros e o período ficam em coluna e os alvos de toque têm 44 × 44
      px. A partir de 768 px, as duas viram tabela, e toda tabela fica dentro de um TableScroll
      (role="region", aria-label, tabIndex=0), porque a coluna do bloco REAL é estreita até 1.199 px.
      Nomes, mints e valores longos quebram a linha. O documento nunca é mais largo que a janela.
    violation: >
      o spec de ponta a ponta mede scrollWidth e clientWidth em 360 px e falha, inclusive no caso com
      dados montados que tem parcelas rateadas abertas e a segunda página de linhas (BR12.1); os
      testes de componente conferem a troca de cartões para tabela em 768 px.
    source: [AC4.5.1, NFR6, Q7, project.md (TableScroll em 360 px)]

  - id: BR10.2
    statement: Cada tela com P&L tem os estados carregando, erro, desconhecido e vazio, cada um com aparência e texto próprios.
    category: policy
    applies_to: [DashboardModeCards, BotDetail, BotPnlLines, ComparisonView]
    trigger: qualquer leitura de P&L
    logic: >
      Carregando usa role="status" e aria-busy e nunca mostra zero; erro usa role="alert", o código e
      "Tentar de novo"; desconhecido usa a palavra "desconhecido" com o motivo e o aviso "◐ Dado
      parcial"; vazio tem texto próprio ("Nenhuma operação real até agora.", "Sem bots reais.", etc.).
      "P&L indisponível agora" (falha da leitura) é diferente de "desconhecido" (falta de preço).
    violation: o teste de componente confere os quatro estados por tela.
    source: [AC4.5.3, team.md (unknown como tipo explícito), Q7]

  # ---------------- BR11 — Formatos de dinheiro ----------------
  - id: BR11.1
    statement: Dinheiro só é escrito pelas funções de features/pnl/money-format.ts; bigint fica só em features/real/money.ts, e nenhum código da U3 faz conta com o texto de um valor.
    category: constraint
    applies_to: [PnlModeBlock, ProratedLines, BotPnlLines, ComparisonView, DashboardModeCards, BotList, BotDetail]
    trigger: qualquer valor monetário na tela
    logic: >
      SOL (lamports em texto inteiro) sai por formatSol de money-format.ts, que usa o
      formatSignedSolCompact de features/real/money.ts, o único lugar com bigint: corta os zeros à
      direita sem arredondar, com pelo menos 2 casas ("+0,0312 SOL", "−0,0000025 SOL"). US$ (texto
      decimal) sai por formatUsd: 2 casas, cortado sem arredondar ("+US$ 12,40"). O sinal sai por
      solSign e usdSign. Onde os componentes ficam, e o que o lint cobre de fato: todos os componentes
      novos que escrevem dinheiro (PnlModeBlock, ProratedLines, BotPnlLines, ComparisonView,
      PeriodSelector) ficam em features/pnl, onde o eslint já proíbe bigint, BigInt(, Number.parseFloat
      e o toFixed; DashboardModeCards (features/dashboard, onde o eslint já proíbe Number( e parseFloat)
      só compõe PnlModeBlock e não escreve valor; BotList e BotDetail (features/bots, onde o eslint NÃO
      proíbe bigint, porque features/bots/decimal.ts o usa) só chamam as funções de money-format.ts e
      não ganham bigint, Number( nem toFixed novos. Nenhum código da U3 faz aritmética sobre o texto
      de um valor (somar dígitos, cortar casas para calcular, comparar grandezas); comparar o texto com
      "0" para decidir se uma linha aparece (BR4.8) é a única leitura permitida. Essa proibição de
      aritmética sobre texto não tem regra de lint: a garantia é o teste de componente (cada valor
      mostrado é igual ao da fixture da API) e a revisão.
    violation: o lint falha na CI nos casos que ele cobre; o teste de componente e a revisão barram o resto.
    source: [project.md (NEVER ponto flutuante), C17, ADR-010, team.md (valores do REAL em inteiros), review-01 R-05]

  - id: BR11.2
    statement: Cada modo aparece na própria unidade, PAPER em US$ e REAL em SOL, em todas as telas da U3.
    category: constraint
    applies_to: [PnlModeBlock, ComparisonView, DashboardModeCards, BotDetail, BotPnlLines, ProratedLines]
    trigger: qualquer valor monetário
    logic: >
      SE o valor é de um bot, bloco ou lado PAPER ENTÃO sai em US$. SE é REAL ENTÃO sai em SOL. Nenhum
      valor PAPER aparece em lamports ou SOL, e nenhum valor REAL aparece em US$. Um valor desconhecido
      aparece como "desconhecido (<motivo>)", nunca como zero.
    violation: o spec confere que o bloco PAPER não tem SOL e o REAL não tem US$.
    source: [FR2.2, AC8.1.1, project.md (ALWAYS unknown, nunca zero)]

  # ---------------- BR12 — Verificação de ponta a ponta ----------------
  - id: BR12.1
    statement: O spec zz-paper-real-unico passa a conferir o painel, o detalhe, o filtro, o período e a comparação, inclusive em 360 px.
    category: policy
    applies_to: ["e2e/zz-paper-real-unico"]
    trigger: toda execução do scripts/verify.sh (jobs smoke e e2e)
    logic: >
      O spec continua o último da suíte (prefixo zz-) e usa os dois bots que já cria. Ele acrescenta os
      casos listados em frontend-components.md (painel com dois cartões e sem "Total geral"; filtro
      Simulado e Real em /bots; detalhe do bot REAL com a coluna Origem e "Bot"; detalhe do bot
      PAPER com o aviso; período "Últimos 7 dias" com os dois blocos separados; comparação com
      templateId igual; 360 px sem rolagem lateral em /, /bots/{id} dos dois bots e /pnl com a
      comparação aberta). Como o cenário do spec só tem compras simples (decisão do fundador da U1),
      um caso em 360 px usa dados montados: o Playwright intercepta C4 (bloco REAL com um bot com 12
      parcelas rateadas e uma linha "Sem bot") e C5 (uma página de 50 linhas com nextCursor e a
      página seguinte), com fixtures sintéticas, sem carteira nem mint real; o caso abre as parcelas e
      clica em "Carregar mais" e confere a largura antes e depois. Nenhum caso novo muda a conta do
      smoke antes dos specs anteriores.
    violation: o verify.sh devolve código diferente de zero.
    source: [Q7, team.md (fatia do intent 261121), project.md (spec zz- por último)]
```

## Resumo

| Regra | Categoria | Em uma linha |
|---|---|---|
| BR1.1 | policy | filtro Todos, Simulado e Real, começa em Todos |
| BR1.2 | policy | filtro no endereço `?mode=`, aplicado no servidor por `market` |
| BR1.3 | policy | lista vazia pelo filtro com texto diferente da pessoa sem bots |
| BR1.4 | policy | foco inicial no filtro, troca pelo teclado |
| BR1.5 | policy | combina com o filtro de estado; "Mostrando N de M bots." da API |
| BR1.6 | policy | filtro Real lê só o bloco REAL e não mostra o aviso |
| BR1.7 | validation | `?mode` inválido vale Todos |
| BR2.1 | policy | período começa em "Desde o início"; cinco opções, com "Hoje" (Q6) |
| BR2.2 | calculation | Hoje, 7 e 30 dias contam dias inteiros locais; par UTC fixo ao aplicar |
| BR2.3 | validation | intervalo inclusivo, até 366 dias em UTC (horário de verão), sem início futuro; erro mantém o digitado |
| BR2.4 | constraint | período só em UTC na API; ALL sem instante |
| BR2.5 | policy | datas no fuso da pessoa, dd/mm HH:MM |
| BR2.6 | policy | período no endereço, vale para os dois blocos e a comparação |
| BR2.7 | policy | não realizado sempre o de agora, dito na tela |
| BR2.8 | policy | painel e detalhe sempre "desde o início" |
| BR2.9 | policy | tabela por token do PAPER só com período concreto; nota em "Desde o início" (decisão do fundador) |
| BR3.1 | constraint | C4 com `market` responde só o bloco pedido |
| BR3.2 | policy | "Tentar de novo" relê só o bloco, mesmo período |
| BR3.3 | policy | `UNAVAILABLE` e falha viram erro com código |
| BR3.4 | policy | foco no título do bloco relido |
| BR4.1 | policy | origem em texto: Bot, liquidação, condicional, outro bot |
| BR4.2 | policy | ordem da rede, mais recente primeiro, sem reordenar |
| BR4.3 | policy | cursor, 50 por página, "Carregar mais" |
| BR4.4 | policy | seis colunas; compra com "—" |
| BR4.5 | constraint | C5 só para bot REAL |
| BR4.6 | constraint | mesmo cálculo da U2, desde o início, sem soma na tela |
| BR4.7 | policy | parcelas rateadas com resumo e detalhe |
| BR4.8 | policy | "Sem bot" só com valor, sempre com motivo |
| BR5.1 | validation | mesmo `templateId`; select real filtrado |
| BR5.2 | validation | 422 `TEMPLATE_MISMATCH` explicado na tela |
| BR5.3 | authorization | 404 por lado, sem dado |
| BR5.4 | policy | cada lado na própria moeda; aviso só no simulado |
| BR5.5 | policy | lados independentes; releitura só do lado em erro |
| BR5.6 | policy | escolha no endereço `?comparar=` |
| BR6.1 | constraint | nenhuma soma, subtração ou conversão no `apps/web` |
| BR6.2 | constraint | nenhum total geral em resposta ou tela |
| BR6.3 | constraint | nenhuma ordenação que misture os modos |
| BR7.1 | policy | painel com dois cartões, sem total geral |
| BR7.2 | policy | estados próprios por cartão |
| BR7.3 | policy | aviso só no cartão simulado com valor |
| BR7.4 | policy | cartão real vazio com o link do motivo |
| BR7.5 | policy | `pnl.updated` relê o simulado; `real.order.state` final e `real.conditional.state` releem o real; 30 s |
| BR8.1 | authorization | tenant do token |
| BR8.2 | authorization | 404 no detalhe, sem dado |
| BR9.1 | constraint | 4,5:1 e cor com texto |
| BR9.2 | policy | rótulo com modo e moeda, `aria-live` |
| BR9.3 | policy | cabeçalhos, tabelas, rótulos e foco |
| BR10.1 | constraint | 360 px sem rolagem lateral; troca única em 768 px; `TableScroll` |
| BR10.2 | policy | quatro estados distintos |
| BR11.1 | constraint | dinheiro só por `money-format.ts`; `bigint` só em `money.ts`; nenhuma conta sobre texto |
| BR11.2 | constraint | PAPER em US$, REAL em SOL |
| BR12.1 | policy | spec `zz-paper-real-unico` ampliado |

São 52 regras em 12 grupos.

## Mudanças de API que estas regras pedem

| Rota | Mudança | Regra | Tipo |
|---|---|---|---|
| `GET /api/v1/pnl/by-mode` (C4) | parâmetro opcional `market=PAPER\|REAL`; com ele, só o bloco pedido e a chave do outro omitida (nunca `null`); `paper` e `real` deixam de ser obrigatórios no esquema; teste de contrato com os três casos; o `apps/admin` não consome C4 | BR3.1 | aditiva na requisição; relaxa o `required` da resposta |
| `GET /api/v1/pnl/by-mode` (C4) | `period=RANGE`, `from`, `to` | BR2.4 | já prevista em C4 para a U3 |
| `GET /api/v1/bots` (listBots) | parâmetro opcional `market=PAPER\|REAL` | BR1.2 | aditiva, fora de C4 [Q2] |
| `GET /api/v1/bots` (listBots) | campos `matchingCount` e `totalCount` em `BotPage` | BR1.3, BR1.5 | aditiva de resposta, fora de C4 |
| `GET /api/v1/bots/{botId}/pnl/lines` (C5) | rota nova, como no contrato; sem período | BR4.3, BR4.6 | já prevista |
| `GET /api/v1/pnl/compare` (C6) | rota nova, como no contrato; 422 `TEMPLATE_MISMATCH` confirmado (PQ-8) | BR5.2 | já prevista |

## Sources

- `functional-design-questions.md` desta pasta: Q1 a Q7 e o resumo confirmado.
- `inception/user-stories/stories.md`: US2.3, US3.10, US4.1 a US4.5, US7.1, US7.2, US8.1 e os seus critérios.
- `inception/units-generation/unit-of-work.md` (U3, PnlModeBlock → ProratedLines) e `unit-of-work-story-map.md` (linhas da U3 e divisão de US4.1, US4.2, US4.4, US4.5 e US7.2).
- `inception/refined-mockups/`: `mockups.md` M4 a M7 e D2, `interaction-spec.md`, `design-system-mapping.md` (`pnl.noBotReason.*`, `pnl.origins.*`), `accessibility-checklist.md`.
- `inception/contract-design/contract-summary.md`: regras comuns, C4, C5, C6, C10, C17, regras de posse, PQ-6 e PQ-8.
- `construction/u1-mode-skeleton/` (`rules.md`, `frontend-components.md`, `code-summary.md`) e `construction/u2-real-bot-pnl/code-generation/code-summary.md`.
- `aidlc/spaces/default/memory/project.md` (TableScroll em 360 px; spec `zz-` por último; NEVER somar os modos; ALWAYS UTC; NEVER ponto flutuante) e `team.md` (Testing Posture, Code Style).
- Código lido: `apps/web/src/features/pnl/*`, `features/bots/BotList.tsx`, `features/bots/BotDetail.tsx`, `features/dashboard/DashboardScreen.tsx`, `components/TableScroll.tsx`, `apps/api/openapi/openapi-guest.yaml`.

## Decisões do fundador (2026-11-23)

- **Tabela "Resultado por token" do PAPER (BR2.9).** A tabela só aparece com um período concreto: Hoje, 7 dias, 30 dias ou um intervalo de até 366 dias. Em "Desde o início", a tela mostra a nota "Escolha um período para ver o resultado por token". Os totais por modo aparecem sempre. Fecha a R-02 da revisão 1.
- **"Hoje" no período (BR2.1, BR2.2).** A opção "Hoje", confirmada na Q6, volta à lista: o dia local inteiro da pessoa, inclusive, enviado em instantes UTC. As quatro opções do AC7.1.2 continuam todas presentes. Fecha a R-03 da revisão 1.
- **Painel sem "P&L do dia UTC".** Já decidido na D2 dos mockups (2026-11-21): o painel mostra "desde o início" e perde o cartão do dia UTC. O seletor padrão de 7 dias de `/pnl` dá lugar a "Desde o início" (AC7.1.1).

## Assumptions & Open Questions

1. [assumption] **Contagens em `BotPage` (BR1.3, BR1.5).** O filtro no servidor [Q2] exige saber se a pessoa tem algum bot e quantos passam pelo filtro. Os dois campos são aditivos e contam bots, não dinheiro. Se o responsável preferir não mexer em `BotPage`, a tela faz uma segunda leitura sem filtro só quando a lista filtrada vem vazia, e a linha vira "Mostrando N bots.".
2. [assumption] **Texto do 422 `TEMPLATE_MISMATCH` (BR5.2).** O texto "Esses dois bots usam estratégias diferentes. Escolha um bot real da mesma estratégia do bot simulado." é proposta desta etapa; a chave nova é `compare.templateMismatch`.
3. [assumption] **Ordem 404 antes de 422 em C6 (BR5.2).** A posse dos dois bots é verificada antes da estratégia, para o 422 nunca confirmar a existência ou a estratégia de um bot de outra pessoa.
4. [assumption] **Canal ao vivo do painel (BR7.5, revisão R-01).** O `pnl.updated` do `DashboardMarkPoller` só sai quando muda o `ledgerMark` do ledger PAPER, e por isso só relê o cartão simulado. O cartão real relê com os eventos REAL que o canal já envia: `real.order.state` nos estados finais `CONFIRMED` e `FAILED` (`sendRealChanges`) e `real.conditional.state` (`sendRealExitChanges`). O canal não muda. Suposição: uma ordem REAL confirmada já está no ledger REAL quando o evento chega; se a liquidação atrasar, a releitura pendente do fim da janela de 30 s a pega.
5. [assumption] **Lugar dos componentes e lint (BR11.1, revisão R-05).** Vale a versão sem mudar o `eslint.config.mjs`: os componentes que escrevem dinheiro ficam em `features/pnl` (sem `bigint`, sem `parseFloat`, sem `toFixed`); `features/dashboard` já proíbe `Number(` e `parseFloat`; `features/bots` não proíbe `bigint` (por `decimal.ts`), e a `BotList` e o `BotDetail` só chamam `money-format.ts`. A proibição de conta sobre o texto de um valor não tem lint: a garantia é o teste de componente e a revisão.
6. [assumption] **Detalhe do bot PAPER.** O P&L do bot PAPER no detalhe vem da linha do bot em C4 com `market=PAPER`; o histórico continua em `GET /api/v1/bots/{botId}/trades` (suposição de C5 no contrato).
7. [assumption] **Chaves do endereço (revisão R-08).** É decisão desta etapa: o filtro usa `?mode=PAPER|REAL`, como a Q2 escreveu, com os valores do contrato; o período e a comparação usam as chaves da especificação de interação, em português (`periodo`, `de`, `ate`, `comparar`). A mistura fica registrada. O `?modo=simulado` do mockup M4.2 fica superado; o mockup não é editado nesta etapa.
8. [assumption] **Intervalo futuro e horário de verão (BR2.3, revisão R-07).** O intervalo que começa depois de hoje é recusado na tela; o que termina depois de hoje é aceito. O limite de 366 dias continua sendo a duração em UTC, igual no `toUtcPeriod` e no servidor; um intervalo de 366 dias de calendário que ganha uma hora no fim do horário de verão é recusado com a mensagem dos 366 dias. O fuso America/Sao_Paulo não tem horário de verão desde 2019, então o caso só aparece em outros fusos.
9. Nenhuma pergunta aberta bloqueia esta etapa. PQ-6 fica fechada pela Q1 (parâmetro `market`) e PQ-8 pela Q4 (mesmo `templateId`, 422 `TEMPLATE_MISMATCH`).
