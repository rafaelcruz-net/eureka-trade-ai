# Perguntas do desenho funcional — U9 Painel e navegação

Unit `u9-dashboard` (kind `ui`). Histórias: US5.3 (painel com o resumo dos bots) e US5.4 (P&L e avisos sem recarregar). Requisitos: FR12.1, FR12.2 e NFR1. Contratos lidos: C4 (bots e comando `pause`, U5), C5 (P&L e reconciliação, U7), C2 (estado das fontes, U4), C7 (canal ao vivo do painel, já implementado pela U5) e C16 (`BotDashboardSummary`, U5). Telas: S2 de [mockups.md](../../../inception/refined-mockups/mockups.md) e do wireframe da Ideação.

Já decidido nas etapas anteriores, sem pergunta:
- A U9 não cria rota nem tabela no backend e não calcula P&L: só lê C4, C5, C2 e C7 e manda o comando `pause` de C4 (fronteira da Unit).
- O canal C7 já existe (`/api/v1/stream/dashboard`): ao abrir manda `bot.state`, as operações recentes em `trade.recorded`, `pnl.updated` e `data.status`; depois manda o que mudou (leitura das marcas a cada 2 s), `heartbeat` a cada 15 s e `auth.expired` no fim do token. O app abre o canal com `fetch` em leitura contínua e cabeçalho `Authorization`, não com `EventSource` (project.md, Corrections).
- "Pausar" no painel tem o mesmo efeito de AC4.8.1: um comando, sem confirmação, com chave de idempotência.
- Horários gravados e recebidos em UTC; o painel mostra "Atualizado às HH:MM" no fuso do navegador.
- Carregando, erro e `unknown` são estados visíveis distintos; falha num quadro não esconde os demais (AC5.3.3, CT-UX-2, CT-UX-3).
- Todo quadro de bot e de P&L mostra "MODO PAPER (simulado)" (DP-4).

## Q1. [Produto] O que o quadro de bots mostra como "última operação"?

O AC5.3.2 pede "a última operação". Existem duas coisas que podem ser chamadas assim: a última ordem simulada gravada no ledger (`PaperTrade` de C5, que o canal manda em `trade.recorded`) e a última decisão do ciclo, que inclui `NO_TRADE` e `HOLD` (`lastDecision` de `BotDashboardSummary`).

A. A última ordem simulada de qualquer bot da pessoa: horário, bot, compra ou venda, token e situação (preenchida, parcial, não preenchida, fechamento pendente). Sem ordem ainda, "Nenhuma operação ainda".
B. A última decisão do ciclo, inclusive `NO_TRADE` e `HOLD`, com a frase simples da decisão.
C. As duas: a última ordem em destaque e, abaixo, a última decisão.
X. Other (please specify)

[Answer]: A

## Q2. [Produto] Como fica o atalho "Pausar" quando a pessoa tem mais de um bot ativo?

A pessoa pode ter até 3 bots ativos. O wireframe da Ideação mostra um único "Pausar"; o mockup refinado diz "Pausar" por bot.

A. Uma linha por bot ativo ou pausado (no máximo 3 ativos, mais os pausados), com nome, estado em texto, motivos de pausa e um "Pausar" em cada bot ativo. Rascunhos e encerrados ficam fora da lista e aparecem só como contagem com link para a lista de bots.
B. Só os números de ativos e pausados e um único "Pausar todos", que manda o comando para cada bot ativo.
C. Igual à A, mais um "Pausar todos" quando houver dois ou mais ativos.
X. Other (please specify)

[Answer]: A

## Q3. [Produto] De qual período é o P&L simulado do painel?

`GET /api/v1/pnl` exige `from` e `to` (até 366 dias). A perda diária dos limites é contada no dia UTC, que no horário de Brasília começa às 21:00 do dia anterior.

A. O dia UTC corrente, o mesmo dia da perda diária, com o rótulo "Hoje (dia UTC, desde 21:00 no seu horário)" calculado do fuso do navegador, e um link "Ver P&L" para a tela S6.
B. As últimas 24 horas, contadas a partir de agora.
C. Desde a ativação do primeiro bot da pessoa (até o teto de 366 dias).
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Quando o painel mostra o estado vazio?

O AC5.3.1 diz "usuário sem bots". Uma pessoa pode ter só rascunhos ou só bots encerrados.

A. Estado vazio (com "Abrir scanner" e "Criar bot PAPER") quando não há bot ativo, pausado nem em encerramento pendente. Se houver rascunho, o quadro acrescenta "Você tem N rascunhos" com "Continuar rascunho" para o mais recente; bots encerrados só aparecem como contagem.
B. Estado vazio só quando não existe bot nenhum, em nenhum estado.
X. Other (please specify)

[Answer]: A

## Q5. [Produto] O quadro de P&L mostra o estado da reconciliação?

O wireframe da Ideação mostra "Divergências: 0 [Ver P&L]" no quadro de P&L. Nenhum critério de aceite do painel pede isso; a tela S6 (U7) já mostra a reconciliação completa.

A. Sim, só o resumo: "Reconciliado às HH:MM", "N divergências" (com link para S6) ou "Ainda não conferido", lido de `GET /api/v1/reconciliation` ao abrir e a cada `pnl.updated`.
B. Não; a reconciliação fica só na tela S6.
X. Other (please specify)

[Answer]: A

## Q6. [Design] Como fica o menu geral do app do convidado?

Hoje cada tela monta o próprio cabeçalho, e o menu (`AccountMenu`) tem Bots, P&L, Conta, Feedback e Sair, sem Painel nem Scanner. O mockup refinado pede o menu Painel, Scanner, Bots PAPER, P&L PAPER, Conta, "Feedback" e a carteira abreviada, com h1 igual ao item de menu (CT-UX-9).

A. Uma casca única para todas as telas logadas (S2 a S8): cabeçalho com o menu na ordem do mockup, item da tela atual marcado com `aria-current="page"`, carteira abreviada (4 primeiros e 4 últimos caracteres), "Feedback" e "Sair"; regiões nomeadas navegação, conteúdo principal e avisos. As telas da U3, U4, U5 e U7 passam a usar essa casca, e o `/` passa a abrir o painel em vez do scanner.
B. Só acrescentar Painel, Scanner e a carteira ao menu atual, mantendo os cabeçalhos de cada tela.
X. Other (please specify)

[Answer]: A

## Q7. [Design] O que é anunciado aos leitores de tela quando o painel se atualiza sozinho?

O CT-UX-8 pede que atualizações automáticas sejam anunciadas sem mover o foco. O canal pode mandar mudanças a cada 2 s e uma leitura completa a cada 60 s.

A. Uma região de avisos `aria-live="polite"` anuncia só mudanças que importam: bot que mudou de estado ("Bot X pausado: perda diária"), operação nova, fonte que ficou atrasada ou voltou, e a perda da atualização automática. A leitura completa sem mudança e o `heartbeat` não são anunciados. O "Atualizado às HH:MM" muda na tela sem anúncio.
B. Anunciar toda atualização recebida, inclusive "Atualizado às HH:MM".
X. Other (please specify)

[Answer]: A

## Q8. [Design] O que o painel faz quando o canal ao vivo cai?

O AC5.4.3 pede o aviso de que os valores podem estar desatualizados, com o horário da última atualização bem-sucedida. O C7 diz que, sem evento em 60 s, o app mostra esse aviso.

A. Reconectar sozinho com espera crescente (2 s, 4 s, 8 s, até 30 s, sem limite de tentativas), usando `Last-Event-ID`. O aviso "Os valores podem estar desatualizados; última atualização às HH:MM" aparece quando o canal cai ou fica 60 s sem evento (nem `heartbeat`), com "Tentar de novo" para reconectar na hora. Com `auth.expired`, renova o token e reabre; se a renovação falhar, segue o fluxo de sessão expirada da U3.
B. Três tentativas automáticas e depois só o botão "Tentar de novo".
X. Other (please specify)

[Answer]: A

## Q9. [Desenvolvimento] Como o painel carrega os dados ao abrir?

O canal C7 manda um retrato inicial de bots, operações recentes e fontes, mas o P&L só chega como aviso `pnl.updated`, e o painel precisa de algo a mostrar se o canal falhar.

A. Cada quadro carrega pela própria rota REST ao abrir (`GET /api/v1/bots` para bots, `GET /api/v1/pnl` para P&L, `GET /api/v1/reconciliation` se Q5 for A, e o estado das fontes do primeiro `data.status`, ou de `GET /api/v1/scanner/tokens?limit=1` quando o canal não abre), com carregando, erro e "Tentar de novo" por quadro. O canal abre em paralelo e só atualiza: `bot.state` substitui a lista, `trade.recorded` troca a última operação, `pnl.updated` busca de novo o P&L (no máximo uma busca por vez) e `data.status` atualiza os avisos.
B. Tudo pelo canal: o painel espera o retrato inicial; só o P&L usa REST.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Q1: a "última operação" é a última ordem simulada de qualquer bot da pessoa (horário, bot, compra ou venda, token e situação); sem ordem, "Nenhuma operação ainda".
- Q2: uma linha por bot ativo ou pausado, com nome, estado em texto, motivos de pausa e "Pausar" em cada ativo; rascunhos e encerrados só como contagem com link para a lista.
- Q3: o P&L do painel é do dia UTC corrente, rotulado com o início do dia no fuso do navegador, com link "Ver P&L".
- Q4: estado vazio quando não há bot ativo, pausado nem em encerramento pendente; com rascunho, "Você tem N rascunhos" e "Continuar rascunho".
- Q5: o quadro de P&L mostra só o resumo da reconciliação (reconciliado com horário, N divergências com link ou ainda não conferido).
- Q6: uma casca única com o menu do mockup para todas as telas logadas (S2 a S8); as telas das outras Units passam a usá-la e `/` abre o painel.
- Q7: a região de avisos anuncia só mudanças que importam (estado de bot, operação nova, fonte atrasada ou de volta, perda da atualização); "Atualizado às" muda sem anúncio.
- Q8: o canal reconecta sozinho com espera crescente até 30 s, sem limite; aviso de valores desatualizados após queda ou 60 s sem evento, com "Tentar de novo"; `auth.expired` renova o token e reabre.
- Q9: cada quadro carrega pela própria rota REST com carregando, erro e "Tentar de novo"; o canal abre em paralelo e só atualiza.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
