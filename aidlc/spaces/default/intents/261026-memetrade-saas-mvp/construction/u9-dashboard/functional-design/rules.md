# Regras — U9 Painel e navegação

Fonte de verdade das regras da Unit `u9-dashboard` (kind `ui`). A Unit não tem entidades próprias: os dados vêm das rotas de C1 a C5 e do canal C7 de [contract-summary.md](../../../inception/contract-design/contract-summary.md). Os fluxos e as máquinas de estados estão em [functional-spec.md](functional-spec.md); os componentes, em [frontend-components.md](frontend-components.md). "Agora" vem do relógio do app, injetável nos testes; todo instante recebido é UTC e só a tela converte para o fuso do navegador.

```yaml
rules:
  # BR1 — Abertura e quadros (FR12.1, AC5.3.1, AC5.3.3, Q4, Q9)
  - id: BR1.1
    statement: O painel é a primeira tela depois da entrada sem origem pendente e é a rota /.
    category: policy
    applies_to: rota / e destino padrão depois da entrada
    trigger: entrada concluída ou abertura de /
    logic: "SE a entrada não tem tela de origem pendente ENTÃO abrir o painel. A rota / mostra o painel, não redireciona mais para o scanner. Com origem pendente (sessão expirada), o fluxo da U3 volta para a origem."
    violation: teste de rota falha
    source: FR12.1, AC5.3.1
  - id: BR1.2
    statement: Cada quadro carrega pela própria rota e tem o próprio carregando, erro e "Tentar de novo".
    category: policy
    applies_to: quadros Bots PAPER, P&L PAPER e avisos de dados
    trigger: abertura do painel, "Tentar de novo" ou nova busca
    logic: "Cada quadro chama as próprias rotas em paralelo. SE uma rota falha e o quadro não tem dados ENTÃO só esse quadro mostra o que aconteceu e 'Tentar de novo', que repete só as chamadas dele; os outros quadros seguem. SE a falha acontece numa nova busca de um quadro que já tem dados ENTÃO os valores ficam e a faixa de avisos diz que podem estar desatualizados."
    violation: o erro aparece só no quadro afetado
    source: AC5.3.3, CT-UX-2, CT-UX-3
  - id: BR1.3
    statement: O quadro de bots está vazio quando nenhum bot está em uso.
    category: validation
    applies_to: quadro Bots PAPER
    trigger: lista de bots recebida
    logic: "SE nenhum bot está em ACTIVE, PAUSED ou CLOSING_PENDING ENTÃO mostrar 'Nenhum bot PAPER em uso', 'Abrir scanner' e 'Criar bot PAPER'. SE existe rascunho (DRAFT ou VALIDATED) ENTÃO acrescentar 'Você tem N rascunhos' (N contado pela carga completa de BR1.5) e 'Continuar rascunho' para o primeiro item da primeira página de GET /api/v1/bots?state=DRAFT, senão de state=VALIDATED. A rota da U5 ordena pela última alteração, mais recente primeiro; a geração de código registra essa ordem em C4 no contract-summary.md, porque o BotSummary não traz data. SE existe bot STOPPED ENTÃO acrescentar 'N encerrados' com link para a lista."
    violation: nenhuma
    source: AC5.3.1, Q4
  - id: BR1.4
    statement: Um evento do canal que chega antes da resposta REST do mesmo quadro é aplicado depois dela.
    category: constraint
    applies_to: quadros enquanto carregam
    trigger: evento do canal com o quadro em carregando
    logic: "Guardar o evento e aplicá-lo depois da resposta REST. Para bots vale o último bot.state recebido; para a última operação vale a ordem de maior createdAt; para fontes vale o último data.status."
    violation: nenhuma
    source: Q9, AC5.4.1
  - id: BR1.5
    statement: A lista e as contagens do quadro de bots vêm só da carga completa por estado.
    category: calculation
    applies_to: GET /api/v1/bots no painel e evento bot.state
    trigger: abertura, "Tentar de novo" ou bot.state
    logic: "Para cada estado (ACTIVE, PAUSED, CLOSING_PENDING, DRAFT, VALIDATED, STOPPED), chamar GET /api/v1/bots?state=<estado>&limit=100 e seguir nextCursor até null, porque a rota não garante total. activeLimit vem dessas respostas. Um bot.state aplica na hora o estado e os motivos dos bots já mostrados que vêm no evento e dispara uma nova carga completa; só uma carga fica em andamento, e eventos durante ela geram uma única carga depois. As contagens nunca são recalculadas a partir dos itens do evento, que tem no máximo 100 bots e não traz activeLimit."
    violation: nenhuma
    source: AC5.3.2, C4, C7
  # BR2 — Quadro de bots (AC5.3.2, Q1, Q2)
  - id: BR2.1
    statement: O quadro mostra as contagens e uma linha por bot em uso, com "Pausar" só nos ativos.
    category: policy
    applies_to: quadro Bots PAPER
    trigger: lista de bots recebida ou bot.state
    logic: "Mostrar 'Ativos: N de 3' (activeCount e activeLimit) e 'Pausados: N'. Uma linha por bot em ACTIVE, PAUSED ou CLOSING_PENDING, nessa ordem de grupo e por nome dentro do grupo, com nome (link para a tela do bot), estado em texto, motivos de pausa em texto e retomada automática (resumesAt) quando houver. SE o estado é ACTIVE ENTÃO a linha tem 'Pausar'. Rascunhos e encerrados aparecem só como contagem com link para a lista."
    violation: nenhuma
    source: AC5.3.2, Q2
  - id: BR2.2
    statement: '"Pausar" manda o comando pause de C4 sem confirmação e sem criar transição repetida.'
    category: policy
    applies_to: POST /api/v1/bots/{botId}/commands/pause
    trigger: acionamento de "Pausar"
    logic: "Gerar uma Idempotency-Key nova por acionamento e desabilitar o botão até a resposta. 200: mostrar o estado da resposta e recalcular as contagens. Falha técnica (503, rede, tempo esgotado): 'Não foi possível pausar agora.' com 'Tentar de novo', que repete com a MESMA chave. 409 INVALID_TRANSITION: 'O bot já não estava ativo.' e buscar a lista de novo. 404: tirar o bot da lista e buscar a lista de novo."
    violation: um clique repetido nunca gera segunda transição
    source: AC5.3.2, AC4.8.1
  - id: BR2.3
    statement: A última operação é a ordem simulada mais recente entre todos os bots da pessoa.
    category: calculation
    applies_to: linha "Última operação"
    trigger: abertura do painel ou trade.recorded
    logic: "Ao abrir, buscar trades?limit=1 de cada bot que não é rascunho (no máximo 3 chamadas ao mesmo tempo) e escolher a de maior createdAt. A cada trade.recorded, trocar SE a ordem mais nova do evento tiver createdAt maior que a atual; como o evento não traz notFilledReason, buscar trades?limit=1 daquele bot e completar a linha com a PaperTrade inteira (até a resposta, a situação aparece sem o motivo). Mostrar horário local HH:MM, bot, Compra ou Venda, token abreviado e situação em texto. SE não há ordem ENTÃO 'Nenhuma operação ainda'."
    violation: nenhuma
    source: AC5.3.2, Q1
  - id: BR2.4
    statement: Estado, motivo de pausa, lado e situação da ordem aparecem sempre em texto.
    category: constraint
    applies_to: quadro Bots PAPER
    trigger: exibição
    logic: "Nunca usar só cor ou ícone. Os textos dos estados e motivos são os mesmos da tela do bot (U5)."
    violation: nenhuma
    source: CT-UX-7
  # BR3 — Quadro de P&L (FR12.1, AC5.4.1, Q3, Q5)
  - id: BR3.1
    statement: O P&L do painel é o do dia UTC corrente.
    category: calculation
    applies_to: GET /api/v1/pnl no painel
    trigger: abertura, pnl.updated ou virada do dia UTC
    logic: "from = 00:00 UTC do dia corrente; to = agora. O rótulo é 'Hoje (dia UTC, desde HH:MM no seu horário)', com 00:00 UTC no fuso do navegador. SE o dia UTC muda com o painel aberto ENTÃO recalcular o período e buscar de novo."
    violation: nenhuma
    source: FR12.1, Q3
  - id: BR3.2
    statement: O não realizado desconhecido aparece como unknown, nunca como zero.
    category: validation
    applies_to: quadro P&L PAPER
    trigger: PnlReport recebido
    logic: "SE unrealized.status é UNKNOWN ENTÃO mostrar 'unknown' com o motivo em texto. SE partial é true ENTÃO dizer que parte das posições está sem preço confiável. SE empty é true ENTÃO 'Nenhuma operação hoje'."
    violation: teste 'unknown nunca zero' falha
    source: FR4.4, AC5.1.3, AC5.4.1
  - id: BR3.3
    statement: Cada aviso de P&L atualizado gera no máximo uma busca em andamento.
    category: policy
    applies_to: P&L e reconciliação do painel
    trigger: pnl.updated
    logic: "SE não há busca em andamento ENTÃO buscar P&L e reconciliação. SE há ENTÃO marcar que outra é devida e fazer uma única busca depois dela, por mais avisos que cheguem."
    violation: nenhuma
    source: AC5.4.1
  - id: BR3.4
    statement: O quadro de P&L mostra só o resumo da reconciliação.
    category: policy
    applies_to: GET /api/v1/reconciliation no painel
    trigger: abertura ou pnl.updated
    logic: "RECONCILED: 'Reconciliado às HH:MM' (checkedAt). DIVERGENT: 'N divergências' com link para S6. PENDING: 'Ainda não conferido'. SE a leitura falha ENTÃO só a linha da reconciliação mostra 'Reconciliação indisponível agora' com 'Tentar de novo'."
    violation: nenhuma
    source: Q5
  - id: BR3.5
    statement: Valores decimais são mostrados sem conversão para ponto flutuante.
    category: constraint
    applies_to: valores de P&L e quantidades
    trigger: exibição
    logic: "Formatar o texto decimal recebido com sinal escrito (+ ou −), vírgula decimal e unidade, pelas funções decimais já usadas na tela S6."
    violation: teste de formatação com muitas casas falha
    source: project.md (Forbidden), CT-UX-7
  # BR4 — Avisos de dados (AC5.4.2, AC5.4.3, NFR1)
  - id: BR4.1
    statement: Fonte atrasada ou com falha aparece com o nome e o horário da última atualização.
    category: validation
    applies_to: faixa de avisos do painel
    trigger: data.status, resposta de fontes ou conferência a cada 30 s
    logic: "Para cada fonte, SE status é DEGRADED ou FAILED, OU agora menos lastUpdatedAt passa de 5 minutos, ENTÃO mostrar o nome da fonte e o horário da última atualização. SE todas estão em dia ENTÃO 'Dados: OK HH:MM'."
    violation: nenhuma
    source: AC5.4.2, NFR1
  - id: BR4.2
    statement: O painel avisa que os valores podem estar desatualizados quando a atualização automática falha.
    category: policy
    applies_to: faixa de avisos do painel
    trigger: canal em STALE ou WAITING, ou em CONNECTING depois de uma queda
    logic: "Mostrar 'Os valores podem estar desatualizados; última atualização às HH:MM', com HH:MM do último evento ou resposta recebida com sucesso, e 'Tentar de novo'. O aviso some no primeiro evento depois da volta."
    violation: nenhuma
    source: AC5.4.3
  # BR5 — Canal ao vivo (FR12.2, AC5.4.1, AC5.4.3, C7, Q8)
  - id: BR5.1
    statement: O canal abre com fetch em leitura contínua e o cabeçalho Authorization.
    category: constraint
    applies_to: GET /api/v1/stream/dashboard
    trigger: abertura e reabertura do canal
    logic: "Usar fetch com Authorization do token em memória e Last-Event-ID na reabertura. Nunca EventSource, nunca o token na URL nem em armazenamento do navegador."
    violation: revisão de código e teste do cliente
    source: C7, project.md (Corrections)
  - id: BR5.2
    statement: Cada evento do canal atualiza o quadro correspondente e o horário da última atualização.
    category: policy
    applies_to: eventos de C7
    trigger: evento recebido
    logic: "Todo evento atualiza 'Atualizado às HH:MM'. bot.state aplica BR1.5. trade.recorded aplica BR2.3. pnl.updated aplica BR3.3. data.status substitui as fontes e aplica BR4.1. heartbeat só confirma o canal. auth.expired aplica BR5.5. Os envelopes são os da seção 'Formato dos eventos do canal' de functional-spec.md. Evento com formato inválido é descartado com registro de aviso, sem fechar o canal."
    violation: nenhuma
    source: AC5.4.1, FR12.2
  - id: BR5.3
    statement: O canal reabre sozinho com espera crescente e sem limite de tentativas.
    category: policy
    applies_to: cliente do canal
    trigger: canal fechado ou com falha
    logic: "Esperar 2, 4, 8, 16 e depois 30 s entre tentativas, sempre com Last-Event-ID. O canal conta como aberto na resposta 200 da abertura (com marcas iguais, o primeiro evento pode levar até 15 s); nesse momento a espera volta a 2 s, o temporizador de 60 s recomeça e o aviso de BR4.2 some. 'Tentar de novo' reabre na hora e busca de novo os três quadros."
    violation: nenhuma
    source: Q8, AC5.4.3
  - id: BR5.4
    statement: Sessenta segundos sem evento deixam o canal em STALE.
    category: validation
    applies_to: cliente do canal
    trigger: temporizador do canal
    logic: "SE passam 60 s desde a abertura ou desde o último evento, heartbeat incluído, ENTÃO o canal fica em STALE e BR4.2 vale. O próximo evento volta para OPEN."
    violation: nenhuma
    source: AC5.4.3, C7
  - id: BR5.5
    statement: Token vencido renova o token e reabre o canal.
    category: policy
    applies_to: cliente do canal
    trigger: evento auth.expired, ou resposta 401 na abertura ou numa reabertura
    logic: "Renovar o token por POST /api/v1/auth/refresh e reabrir com Last-Event-ID; sem aviso se reabrir em até 10 s. SE a renovação falha, OU a reabertura logo depois da renovação responde 401 de novo, ENTÃO seguir o fluxo de sessão expirada da U3, com retorno ao painel, e fechar o canal sem novas tentativas."
    violation: nenhuma
    source: C7, US1.3
  - id: BR5.6
    statement: Sair do painel fecha o canal.
    category: constraint
    applies_to: cliente do canal
    trigger: saída do painel ou "Sair"
    logic: "Fechar a leitura, cancelar tentativas agendadas e temporizadores. Nenhuma reabertura acontece fora do painel."
    violation: nenhuma
    source: C7
  # BR6 — Casca e navegação (CT-UX-4, CT-UX-9, DP-4, Q6)
  - id: BR6.1
    statement: Toda tela logada S2 a S8 usa a casca única com o menu geral.
    category: policy
    applies_to: telas do app do convidado depois da entrada
    trigger: exibição de qualquer tela logada
    logic: "Menu na ordem Painel, Scanner, Bots PAPER, P&L PAPER e Conta, mais 'Feedback', a carteira abreviada e 'Sair'. As telas da U3, U4, U5 e U7 deixam de montar o próprio cabeçalho. S1 não usa a casca."
    violation: nenhuma
    source: unit-of-work.md (U9), mockups (navegação principal), Q6
  - id: BR6.2
    statement: O item da tela atual é marcado e o h1 é igual ao item de menu.
    category: constraint
    applies_to: menu geral
    trigger: exibição
    logic: "aria-current='page' no item atual; S4 e S5 marcam 'Bots PAPER'. Telas que são um item do menu têm h1 igual ao texto do item."
    violation: nenhuma
    source: CT-UX-9
  - id: BR6.3
    statement: A casca tem regiões nomeadas e uma região de avisos que não move o foco.
    category: constraint
    applies_to: casca
    trigger: exibição
    logic: "Regiões 'Navegação principal', conteúdo principal (#conteudo) e 'Avisos' (aria-live polite)."
    violation: nenhuma
    source: CT-UX-8, CT-UX-9
  - id: BR6.4
    statement: A carteira da casca aparece só abreviada.
    category: constraint
    applies_to: casca
    trigger: exibição
    logic: "Usar walletAddressMasked de GET /api/v1/account, lido uma vez por sessão. SE a leitura falha ENTÃO mostrar 'Carteira' sem endereço. O endereço completo nunca aparece."
    violation: nenhuma
    source: NFR7, AC7.3.2
  - id: BR6.5
    statement: O foco inicial do painel vai para o título do primeiro quadro.
    category: policy
    applies_to: painel
    trigger: abertura do painel
    logic: "Focar o título 'Bots PAPER' ao abrir."
    violation: nenhuma
    source: CT-UX-4, wireframe S2
  - id: BR6.6
    statement: Os títulos dos quadros de bots e de P&L trazem "MODO PAPER (simulado)".
    category: constraint
    applies_to: quadros do painel
    trigger: exibição
    logic: "O texto aparece no título, não só num ícone."
    violation: nenhuma
    source: DP-4
  # BR7 — Anúncios e foco (CT-UX-4, CT-UX-8, Q7)
  - id: BR7.1
    statement: A região de avisos anuncia só as mudanças que importam.
    category: policy
    applies_to: região de avisos
    trigger: evento do canal ou resultado de "Pausar"
    logic: "Anunciar: bot que mudou de estado (nome, estado novo e motivo), ordem nova, fonte que ficou atrasada ou voltou, perda e volta da atualização automática e o resultado de 'Pausar'. Não anunciar heartbeat, leitura completa sem mudança nem 'Atualizado às HH:MM'."
    violation: nenhuma
    source: CT-UX-8, Q7
  - id: BR7.2
    statement: Mudanças próximas viram um único anúncio.
    category: constraint
    applies_to: região de avisos
    trigger: várias mudanças em sequência
    logic: "Juntar num anúncio as mudanças recebidas em até 2 s uma da outra."
    violation: nenhuma
    source: CT-UX-8
  - id: BR7.3
    statement: Nenhuma atualização automática move o foco.
    category: constraint
    applies_to: painel
    trigger: atualização da tela
    logic: "Atualizações não mexem no foco. SE o botão focado some porque o bot foi pausado ENTÃO o foco vai para o texto do estado daquela linha."
    violation: nenhuma
    source: CT-UX-4, CT-UX-8
  # BR8 — Isolamento e textos (DP-1, C-16)
  - id: BR8.1
    statement: O painel nunca manda identificador de tenant ou de pessoa.
    category: authorization
    applies_to: todas as chamadas do painel
    trigger: qualquer chamada
    logic: "Tenant e pessoa vêm só do token. Bot de outra pessoa responde 404 e é tratado como em BR2.2."
    violation: teste negativo entre tenants das rotas lidas
    source: DP-1, FR2.1
  - id: BR8.2
    statement: Nenhum texto do painel promete lucro.
    category: constraint
    applies_to: textos do painel
    trigger: exibição
    logic: "Os valores são sempre chamados de simulados; nenhum texto sugere ganho garantido."
    violation: nenhuma
    source: project.md (Forbidden), C-16
```

## Resumo

| Grupo | Regras | Assunto |
|---|---|---|
| BR1 | BR1.1 a BR1.5 | Abertura do painel, carga independente dos quadros e carga completa dos bots |
| BR2 | BR2.1 a BR2.4 | Quadro de bots, "Pausar" e última operação |
| BR3 | BR3.1 a BR3.5 | P&L do dia UTC, `unknown` e reconciliação |
| BR4 | BR4.1, BR4.2 | Avisos de fonte e de valores desatualizados |
| BR5 | BR5.1 a BR5.6 | Canal ao vivo |
| BR6 | BR6.1 a BR6.6 | Casca e navegação |
| BR7 | BR7.1 a BR7.3 | Anúncios e foco |
| BR8 | BR8.1, BR8.2 | Isolamento e textos |
