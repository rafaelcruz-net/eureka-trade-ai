# Decisões de arquitetura — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21. Os ADRs abaixo são numerados dentro deste intent. Quando um ADR cita "ADR-004 do 261113" ou "ADR-005 do 261113", fala dos ADRs do intent `261113-trading-real-bot`.

## Sources

- `domain-design-questions.md` (Q1 a Q6, resumo confirmado).
- `components.md` desta etapa.
- `inception/requirements-analysis/requirements.md`.
- `inception/user-stories/stories.md`.
- `inception/refined-mockups/mockups.md` (OQ1 a OQ7) e `interaction-spec.md`.
- O mapa do código (`architecture.md`, `component-inventory.md`, `dependencies.md`).
- As regras ArchUnit de `apps/api/src/test/java/com/memetrade/architecture/`, além de `RealExecutionArchitectureTest`, `RealExitsArchitectureTest`, `RealRiskArchitectureTest` e `RealOpsArchitectureTest`.
- `project.md` e `team.md`.

## ADR-001: P&L REAL por bot em `pnl.real`, calculado a partir das interfaces públicas

- **Status:** Accepted [Q1].
- **Context:**
  - Hoje o P&L REAL existe só por pessoa (`RealPnlService` e `RealPnlCalculator`). Ele vem das ordens assentadas e não do ledger REAL, e por isso deixa de fora as vendas das saídas condicionais.
  - O ledger REAL não tem `bot_id`. A única ligação entre uma operação REAL e um bot é `real_order.bot_id`.
  - O `RealExecutionArchitectureTest` obriga `pnl.real` a ser puro (`PURE_DOMAIN`: sem Spring, infra, web nem SQL) e a nunca tocar o PAPER (`NO_PAPER`).
  - O P&L faz parte do núcleo financeiro, então os testes são escritos antes do código (team.md).
- **Decision:**
  - O P&L REAL por bot fica no módulo `pnl`, no componente RealBotPnl.
  - O cálculo puro fica em `pnl.real` (RealBotPnlCalculator, RealCostBasis e RealProration). O serviço que lê os dados fica em `pnl.application`, como o `RealPnlService` de hoje.
  - O serviço lê só pelas interfaces públicas:
    - `ledger.api`: os lançamentos de swap e de taxa, com referência, em ordem de rede;
    - `realexecution.api`: as ordens, com `bot_id`, origem, slot e assinatura;
    - `botwallet.api`: as carteiras do bot da pessoa;
    - `marketdata.api`: os preços;
    - a porta BotDirectory de `pnl.api`: os metadados dos bots.
  - O total REAL da pessoa (`GET /api/v1/real/pnl`) passa a ser a soma dos bots mais a linha "Sem bot", e o cálculo por ordens do `RealPnlCalculator` é aposentado.
- **Consequences:**
  - (+) Nenhum módulo novo e nenhuma tabela nova.
  - (+) As fronteiras ArchUnit que já existem continuam valendo.
  - (+) O cálculo puro pode ser testado com os exemplos das histórias, antes do código.
  - (+) A venda condicional entra no P&L (FR5.1).
  - (−) Os arquivos de `pnl` também pertencem ao intent 261113, então as mudanças são coordenadas (restrição dos requisitos).
  - (−) Leituras novas nas interfaces públicas: os lançamentos em fluxo e na ordem de rede em `ledger.api`; o slot e a assinatura em `realexecution.api`. O slot passa a ser gravado pela migração expand do ADR-011.
- **Alternatives Rejected:**
  - **Um módulo novo `botpnl`** (Q1, opção B). Duplicaria as regras e as fronteiras do `pnl`, e seriam duas fontes de P&L REAL.
  - **Gravar `bot_id` e a atribuição no ledger REAL ao escrever.** Exigiria migração e mudaria a gravação append-only que está em validação no 261113. Ver o ADR-004.
  - **Calcular no `realexecution`.** Misturaria execução e contabilidade, e o `realexecution` não vê o ledger das condicionais.
- **Security/compliance:**
  - O tenant vem do token, e o bot de outra pessoa responde 404 (NFR3).
  - Nenhum endereço de carteira aparece no P&L.
  - Lamports em `long` e preços em `BigDecimal`, nunca em ponto flutuante: a regra de ponto flutuante do `RealExecutionArchitectureTest` já cobre `pnl.real`.
  - A separação REAL/PAPER é garantida pela regra `NO_PAPER`.

## ADR-002: Um único custo médio REAL (RealCostBasis), usado pelo ledger por uma porta

- **Status:** Accepted [Q2], com um ajuste de fronteira.
- **Context:**
  - Existem dois cálculos de custo médio REAL:
    - o `ledger.domain.RealPositions` arredonda para baixo o custo tirado numa venda;
    - o `ceilShare` do `pnl.real.RealPnlCalculator` arredonda para cima.
  - O FR5.2 pede um só.
  - A Q2 fez o `pnl.real` dono desse cálculo e pediu que o `RealPositions` passasse a usá-lo.
  - Só que a regra `LedgerArchitectureTest.theLedgerNeverDependsOnThePnl` proíbe qualquer classe do `ledger` de depender de `com.memetrade.pnl..`, e o `pnl` já depende do `ledger.api`.
  - As posições da carteira alimentam a barreira REAL e o ciclo, e são lidas num único instantâneo do banco (revisão R-01 do 261113).
- **Decision:**
  - O RealCostBasis, em `pnl.real`, é o único algoritmo de custo médio REAL. Ele vale por bot e pela carteira inteira.
  - O `ledger.api` declara uma porta de custo (proposta: `RealCostMethod`), que o RealCostBasis implementa. A injeção fica na configuração do `pnl`.
  - A direção das dependências fica assim:
    - o RealLedger chama a própria porta;
    - o RealCostBasis depende de `ledger.api` (a porta e os tipos de lançamento) e a implementa;
    - nada no `ledger` depende do `pnl`.
  - O `JdbcRealLedger` passa a calcular `positions` e `view` por essa porta. O algoritmo próprio do `RealPositions` sai, e a classe vira só um adaptador que ordena os preenchimentos e chama a porta, ou é removida.
  - O `RealPositions`, a porta `RealCostMethod` e o RealCostBasis usam a mesma chave de ordem de rede do ADR-011: primeiro (slot, assinatura), depois o fallback. A porta recebe os lançamentos já ordenados por essa chave, tanto para a carteira quanto para cada bot.
  - A posição REAL tem um dono só: a entidade RealCostPosition, do RealCostBasis. O tipo `ledger.api.RealPosition` é a forma dela quando o titular é a carteira.
  - Um teste de paridade, com a mesma chave de ordem, confere duas coisas em unidades base e lamports exatos:
    - a posição da carteira é igual à soma das posições dos bots mais a parcela sem dono;
    - o P&L e a barreira usam os mesmos números.
- **Consequences:**
  - (+) Uma regra de arredondamento só. A regra `theLedgerNeverDependsOnThePnl` continua verde sem exceção, e não nasce ciclo entre pacotes.
  - (+) A leitura num único instantâneo continua no ledger.
  - (−) A barreira REAL passa a depender, em tempo de execução, de um algoritmo que mora no `pnl`. Uma mudança no RealCostBasis mexe na barreira, e por isso os testes da barreira REAL rodam junto com os do custo.
  - (−) O bean do RealCostBasis precisa existir em todo processo que usa o ledger REAL (API e worker).
  - (−) A troca de arredondamento pode mudar o custo de uma posição aberta em 1 lamport. O Functional Design fixa a regra e o teste de paridade confere.
- **Alternatives Rejected:**
  - **O ledger como dono, com o `pnl` usando o cálculo dele** (Q2, opção B). Rejeitada pelo responsável.
  - **O ledger chamando o `pnl.api` direto, com uma exceção na regra ArchUnit.** Criaria um ciclo entre os pacotes `ledger` e `pnl` e enfraqueceria uma regra que protege a direção ledger → P&L.
  - **Manter os dois cálculos, só com um teste de paridade.** Não cumpre o FR5.2 ("um único cálculo") e deixa duas regras de arredondamento divergirem com o tempo.
- **Security/compliance:**
  - Aritmética inteira e exata (`Math.*Exact`, `BigInteger`).
  - O custo subestimado é evitado no lado da barreira: a regra única de arredondamento precisa manter que o custo restante nunca é menor que o real (o comentário do `RealPositions` de hoje).
  - O Functional Design confere isso contra o ADR-004 do 261113 (teto e saída mínima).

## ADR-003: Visão por modo que nunca soma, garantida por regra de arquitetura

- **Status:** Accepted [Q3].
- **Context:**
  - `project.md` proíbe somar PAPER e REAL num total geral em tela, API ou relatório.
  - O NFR3 pede um teste obrigatório.
  - A regra `NO_PAPER` impede `pnl.real` de ver o PAPER, então o ponto que monta os dois blocos não pode ficar lá.
- **Decision:**
  - Um componente próprio, o ModePnlView, num pacote próprio do `pnl` (proposta: `com.memetrade.pnl.modeview`). Ele monta a resposta com o bloco PAPER (PaperPnl, US$) e o bloco REAL (RealBotPnl, lamports), com um período único.
  - O bloco PAPER traz o aviso do simulado. A resposta não tem nenhum campo de total fora dos blocos.
  - A garantia vem de uma regra ArchUnit nova (proposta: `NEVER_SUM_MODES`) sobre duas listas fechadas de tipos:
    - tipos de resultado PAPER: `PaperBotPnl`, `PaperModeTotal` e os tipos de resultado do `PnlQueryService`;
    - tipos de resultado REAL: `RealBotPnlLine`, `UnownedPnlLine`, `RealModeTotal`, `RealCostPosition` e `BotHolding`.
    Um tipo novo de resultado entra numa das listas no mesmo commit em que é criado.
  - A regra tem quatro partes:
    1. nenhuma classe fora de `pnl.modeview` depende ao mesmo tempo de um tipo de cada lista; a regra vale por classe, então `PnlQueryService` (PAPER) e `RealPnlService` (REAL) podem continuar juntos em `pnl.application`;
    2. as classes de `pnl.modeview` nunca chamam aritmética (`BigDecimal.add` e `subtract`, `Math.addExact` e `subtractExact`, `BigInteger.add`, `Long.sum`);
    3. as classes de `pnl.modeview` não chamam os acessores de valor dos tipos das duas listas; só passam os objetos inteiros para a resposta;
    4. cada parte tem um teste que falha com uma classe de amostra.
  - **Limite declarado.** O ArchUnit não vê a soma com `+` de `long` primitivo, porque ela não é uma chamada de método. A parte 3 fecha esse furo dentro de `pnl.modeview`, onde nenhum valor primitivo chega a ser extraído. Fora de `pnl.modeview`, uma classe que não dependa das duas listas não tem como somar um valor de cada modo.
  - **Rede de segurança contra a soma de `long`:**
    - o teste de contrato do OpenAPI confere que o esquema da visão só tem os blocos e o período;
    - um teste de propriedade com valores conhecidos confere que, para todo par de um valor PAPER e um valor REAL, a resposta traz exatamente esses números nos seus blocos e nenhum terceiro número.
  - No web, o PnlModeBlock aceita um modo só, e o ComparisonView não calcula diferença. O teste de unidade de cada um confere isso.
- **Consequences:**
  - (+) A proibição vira um portão automático da CI, não só uma convenção.
  - (+) A tela não soma porque nunca recebe um valor somável num mesmo tipo.
  - (−) Uma regra ArchUnit a mais para manter. Os totais por modo precisam vir prontos do PaperPnl e do RealBotPnl.
- **Alternatives Rejected:**
  - **A tela chama as duas APIs e junta os resultados** (Q3, opção B). A proibição ficaria só no frontend, sem regra no servidor, e o período poderia divergir entre os blocos.
  - **Uma resposta única com um total geral escondido ou opcional.** Contradiz o NEVER de `project.md`, porque o total existiria na API.
  - **Só testes de contrato, sem regra ArchUnit.** Não pegaria um total calculado no servidor e usado num relatório ou log.
  - **Regra ArchUnit sem lista fechada de tipos.** Uma classe de `pnl.application` poderia depender dos dois modos sem que a regra percebesse.
- **Security/compliance:**
  - Cumpre o NEVER de `project.md` (somar os modos) e o ALWAYS de separação REAL/PAPER.
  - O aviso do simulado atende a C-16, sem promessa de lucro.
  - O tenant vem do token, e o bot de outra pessoa responde 404.

## ADR-004: Rateio e P&L por bot calculados na leitura, sem gravar atribuição

- **Status:** Accepted (requisitos FR3.2 e FR3.3; feasibility Q1 e Q8; suposição dos requisitos). Corrigido depois da revisão de arquitetura (R-01, R-06), com aprovação do responsável em 2026-11-21.
- **Context:**
  - O FR3.2 manda calcular o P&L REAL por bot na leitura.
  - As ordens REAL antigas já têm o bot, e as vendas sem bot (liquidação e condicional) não têm.
  - O ledger REAL é append-only e está em validação no 261113.
  - O FR3.8 exige a ordem de rede (slot, depois assinatura), mas hoje o slot de confirmação não é gravado em `real_order` nem em `real_ledger_entry` (ADR-011).
  - O NFR1 pede p95 ≤ 1 s com 10 bots e 2 anos de histórico, e o cálculo nunca pode travar a execução. O custo médio de um período curto ainda precisa do histórico anterior.
  - Os índices de hoje em `real_ledger_entry` não têm slot.
- **Decision:**
  - O RealBotPnl recalcula a cada leitura.
  - O RealProration rateia cada venda sem bot pelo maior resto, com desempate pela data de criação do bot e, depois, pelo id (FR3.7).
  - A **atribuição** não é gravada: nenhuma tabela de atribuição por bot e nenhum `bot_id` no ledger REAL.
  - A única mudança de esquema é a migração expand do ADR-011: a coluna `slot`, o preenchimento das linhas antigas e o índice com slot. Ela não remove nada nem altera o valor de nenhum lançamento.
  - Todos os cálculos usam a chave de ordem de rede comum do ADR-011.
  - A leitura nova de `ledger.api` é **em fluxo** (streaming, como `streamEntries`) e por carteira, já na chave de ordem de rede. Ela é apoiada num índice que inclui o slot, por exemplo `(bot_wallet_id, slot, tx_signature)`, e nunca carrega a lista inteira na memória.
  - O NFR Design entrega o índice final e o resultado do cenário de carga do NFR1 antes da Code Generation. O p95 exato fica com ele.
  - As leituras não pedem trava de escrita.
- **Consequences:**
  - (+) A regra de rateio pode ser corrigida sem migrar a atribuição.
  - (+) O histórico antigo entra pela mesma regra, com a ordem do ADR-011: slot preenchido onde já existe e o fallback sinalizado onde não existe.
  - (+) O ledger continua append-only: a migração só acrescenta e preenche uma coluna nova.
  - (−) Uma migração expand é necessária, e algumas linhas antigas podem ficar sem slot e ordenadas pelo fallback.
  - (−) O custo de leitura cresce com o histórico. O NFR Design precisa confirmar o p95 no cenário de carga e, se for preciso, propor um cache por pessoa invalidado pela marca de mudança do ledger REAL. Esse cache seria só um acelerador, nunca a fonte.
  - (−) Uma mudança na regra muda o P&L passado mostrado, sem trilha própria. Isso é aceitável porque o ledger continua sendo a fonte e a conferência do FR5.3 é exata.
- **Alternatives Rejected:**
  - **Materializar a atribuição ao gravar** (tabela de parcelas por bot). Exige uma tabela nova e mexe na gravação do assentamento e das condicionais. Também congelaria uma regra que ainda vai ser ajustada no Functional Design.
  - **Ler os lançamentos como lista.** Com 2 anos de histórico, a memória e o tempo crescem sem limite; a leitura em fluxo mantém o custo previsível.
  - **Instantâneo noturno ou visão materializada.** Os dados ficariam velhos para o painel ao vivo, e seria mais uma rotina para operar sem nenhum serviço novo.
- **Security/compliance:**
  - Leitura apenas, filtrada pelo tenant. Nenhuma escrita nova no ledger.
  - A soma exata das parcelas mantém a conferência com o ledger (FR5.3), que é o controle financeiro do beta.

## ADR-005: Elegibilidade do REAL na criação, com o motivo mais forte decidido no servidor

- **Status:** Accepted [Q4], OQ2 e OQ3 dos mockups.
- **Context:**
  - O FR1.2 tem cinco motivos para "Real" ficar indisponível.
  - Os mockups (Q7) fixaram uma precedência: kill switch, REAL desligado, carteira inativa, liberação ausente, quarentena.
  - Hoje o comando de criação só recusa a carteira inativa.
  - Os dados vêm de leituras que já existem em `botwallet.api`: `readRealState`, `readRelease` e `RealKillSwitchState.current`.
- **Decision:**
  - O RealCreationEligibility, no `bots`, decide a elegibilidade com a precedência numa função pura com teste de unidade. Ele devolve só o motivo mais forte e, na quarentena, o `effectiveAt`.
  - A mesma regra vale para a tela (rota do assistente) e para o comando de criação. O `POST` com `market = REAL` é recusado com 422 e o código do motivo por qualquer um dos cinco motivos.
  - A falha é fechada: sem resposta do `botwallet`, o REAL fica indisponível.
- **Consequences:**
  - (+) Uma regra só. A tela nunca libera o que o servidor recusa.
  - (+) A tela fica simples.
  - (−) O comando de criação passa a ler o kill switch e a liberação. Esse é o comportamento esperado, mas é uma mudança visível: criar bot REAL durante uma quarentena passa a ser recusado.
- **Alternatives Rejected:**
  - **Devolver a lista de motivos e resolver a precedência no cliente.** Seriam duas implementações da regra e uma chance de divergência entre web e servidor.
  - **Checar só na tela.** Uma chamada direta à API criaria um bot REAL inelegível.
  - **Ler os motivos em várias rotas separadas.** Daria mais pedidos e estados parciais na tela.
- **Security/compliance:**
  - A elegibilidade só libera a criação e nunca autoriza operar. A barreira REAL, a liberação com quarentena de 24 h e o TxSigner continuam valendo para cada ordem (project.md, ADR-005 do 261113).
  - Um bot nunca é convertido entre modos (FR1.4).
  - A confirmação explícita "Este bot opera com dinheiro real" é exigida na tela.

## ADR-006: Ciclo REAL vende só a quantidade própria, por uma leitura própria, depois da Performance Validation

- **Status:** Accepted [Q5]. Corrigido depois da revisão de arquitetura (R-02), com aprovação do responsável em 2026-11-21.
- **Context:**
  - Hoje o RealCycle vende a quantidade inteira do mint na carteira, e isso fecha a posição de outros bots. Por isso existe a parcela "vendido por outro bot" (FR3.4).
  - Hoje `RealCycle.inputs` entrega ao motor de decisão as posições e o custo da carteira inteira, então o bot decide sobre a posição de outros bots.
  - O FR6 muda esse comportamento, mas só depois que o intent 261113 terminar a Performance Validation.
  - O ciclo roda numa transação curta (trava de 2 s, limite de 5 s). Rodar o P&L completo do RealBotPnl ali reprocessaria 2 anos de ledger e de ordens da pessoa.
  - Uma venda do mesmo bot ainda não assentada (na fila ou em voo) não aparece no ledger. Sem desconto, dois ciclos seguidos poderiam pedir a mesma quantidade duas vezes, e a segunda ordem venderia a parte de outro bot. O `realexecution` só limita ao saldo da carteira.
  - O `BotsArchitectureTest` permite que o `bots` dependa de `pnl.api` e `realexecution.api`.
- **Decision:**
  - **Leitura própria, sem P&L.** Uma porta própria em `pnl.api`, `RealBotHoldings`, é implementada por um serviço separado do RealBotPnl (componente RealBotHoldings). Ela devolve, por mint do bot, a quantidade própria, o custo próprio e a quantidade disponível. Usa só o caminho de quantidade do RealCostBasis e do RealProration: sem preço, taxas, período, realizado nem linha "Sem bot". Lê os lançamentos em fluxo na chave de ordem de rede do ADR-011.
  - **Desconto das vendas em aberto.** A quantidade disponível é a própria menos as vendas do mesmo bot ainda não assentadas. Elas são lidas por uma leitura nova de `realexecution.api`: ordens SELL do bot em `PROPOSED`, `QUOTED`, `SIGNED`, `SENT` ou `AMBIGUOUS`. Uma ordem `AMBIGUOUS` conta como em aberto, o que é o lado conservador.
  - **Orçamento de tempo e falha fechada.** A leitura tem um orçamento de tempo próprio dentro da transação curta do ciclo; o número fica para o NFR Design. Se ela passa do orçamento ou falha, o bot não vende naquele ciclo. O motivo fica registrado no log e numa métrica, e a posição da carteira inteira nunca é usada no lugar.
  - **Entradas de decisão próprias.** `RealCycle.inputs` passa a dar ao motor de decisão a posição e o custo próprios do bot, e não os da carteira inteira. O saldo de SOL operável para compras continua sendo o da carteira, sob a barreira REAL e o teto, como hoje.
  - **Venda.** O RealCycle pede ao `realexecution` a venda só da quantidade disponível do bot. O `realexecution` continua dono da ordem, da fila e da idempotência.
  - **Entrega.** A mudança fica atrás de uma configuração desligada por padrão e entra por último na entrega. O mesmo estado liga o aviso da tela (FR6.2).
- **Consequences:**
  - (+) Um bot não fecha mais a posição de outro, nem decide sobre ela.
  - (+) Dois ciclos seguidos não vendem duas vezes a mesma quantidade.
  - (+) Depois da mudança, a parcela "vendido por outro bot" deixa de aparecer nas vendas novas.
  - (+) A Performance Validation do 261113 não é afetada.
  - (−) Um caminho de leitura a mais no `pnl` para manter. Ele usa os mesmos algoritmos puros, então um teste confere que a quantidade do RealBotHoldings é igual à quantidade do RealBotPnl para o mesmo ledger, sem vendas em aberto.
  - (−) Uma falha ou lentidão da leitura deixa o bot sem vender naquele ciclo. O motivo fica visível no log e na métrica.
  - (−) O RealBotHoldings precisa existir no processo worker.
- **Alternatives Rejected:**
  - **O `realexecution` calcula a quantidade do bot** (Q5, opção B). Levaria contabilidade para a execução e criaria um segundo cálculo de custo e quantidade.
  - **Ler a quantidade pelo P&L completo do RealBotPnl.** Reprocessa preço, taxas e rateio em lamports dentro da transação curta do ciclo, e não trata as vendas em aberto.
  - **Só o `realexecution` recusar uma venda acima da quantidade própria menos as vendas em aberto.** Ainda deixaria o motor decidir sobre a posição da carteira inteira e colocaria contabilidade na execução.
  - **Mudar o ciclo já no começo do intent.** Mexeria no comportamento que o 261113 está validando.
  - **Gravar o `bot_id` nas posições do ledger REAL.** Contradiz o ADR-004.
- **Security/compliance:**
  - Uma única transação por ordem REAL, mantida pela `Idempotency-Key` e pela fila (AC6.1.4, team.md).
  - A falha fechada nunca troca a posição própria pela da carteira.
  - A venda continua passando pela barreira REAL e pela política de conteúdo do TxSigner (ADR-004 do 261113).
  - O kill switch e a pausa continuam impedindo novas ordens.
  - A leitura confere o tenant e que o bot é da pessoa.

## ADR-007: Metadados dos bots para o P&L por porta em `pnl.api`, sem ciclo `bots ↔ pnl`

- **Status:** Accepted. É um ajuste de fronteira desta etapa e não muda nenhuma resposta.
- **Context:**
  - O RealBotPnl precisa da data de criação e do id de cada bot, para o desempate do FR3.7, e do estado pausado ou removido (FR3.11).
  - O ModePnlView precisa do nome, do `market` e do `templateId`.
  - O `bots` já depende de `pnl.api` (`PortfolioRiskView`) e vai depender mais com o RealCycle (ADR-006).
  - Se o `pnl` passasse a depender de `bots.api`, os dois módulos ficariam num ciclo. Nenhuma regra ArchUnit proíbe ciclos hoje, mas o ciclo deixa os módulos acoplados.
- **Decision:**
  - O `pnl.api` declara a porta BotDirectory, que lê os bots da pessoa filtrados pelo tenant.
  - O `bots` implementa essa porta. É o mesmo padrão em que o `realexecution` e o `botwallet` declaram interfaces que o `realexits` implementa.
- **Consequences:**
  - (+) A dependência entre os módulos continua numa só direção (bots → pnl.api).
  - (−) Uma interface a mais. Os campos dela precisam ficar mínimos para não virar uma cópia do modelo do bot.
- **Alternatives Rejected:**
  - **`pnl` → `bots.api` direto.** Cria um ciclo entre os módulos.
  - **O chamador passa os metadados para o `pnl`.** Espalha a regra por cada rota e pelo ciclo, e cada um poderia esquecer o desempate.
- **Security/compliance:** a porta sempre filtra por tenant e pessoa (`project.md`, ALWAYS validar o tenant).

## ADR-008: Limites REAL da conta por uma leitura nova em `riskcontrol.api`

- **Status:** Accepted, com uma correção da premissa da Q4 (OQ6). O responsável confirma no gate que aceita a interface nova.
- **Context:**
  - O passo M3b mostra os limites REAL da conta, só para leitura.
  - O resumo confirmado da Q4 diz que os limites vêm de uma "interface de leitura que já existe em `riskcontrol.real`". Essa premissa está errada: o código não tem essa leitura, e o `riskcontrol.api` só tem o `RealRiskGate`, que avalia ordens. `RealAccountLimits` é uma interface de leitura **nova**.
  - A barreira usa a `RealRiskPolicy` com a configuração da barreira mais o teto e a reserva da carteira do bot.
- **Decision:**
  - O `riskcontrol.api` ganha uma interface nova (proposta: `RealAccountLimits`), implementada em `riskcontrol.real`.
  - Ela devolve os quatro limites em lamports, arredondados para baixo, e o percentual, com `unknown` explícito.
  - Ela usa as mesmas entradas da barreira.
  - A rota do assistente do `bots` expõe essa leitura.
- **Consequences:**
  - (+) A tela mostra exatamente o que a barreira aplica.
  - (+) O dono da regra continua sendo o `riskcontrol`.
  - (−) Uma interface de leitura nova em `riskcontrol.api` para manter.
  - (=) Nenhuma regra ArchUnit muda:
    - o `RealRiskArchitectureTest.REAL_BOUNDARY` já deixa o `riskcontrol.real` ler o `botwallet.api`;
    - a lista `otherModuleInternals` do `BotsArchitectureTest` já deixa o `bots` usar o `riskcontrol.api`.
- **Alternatives Rejected:**
  - **Ler pela API de preferências da carteira do bot** (`botwallet`). Ela não conhece os limites da barreira, então os números poderiam divergir.
  - **Calcular os limites no frontend.** Duplica a regra e expõe a configuração da barreira.
- **Security/compliance:**
  - Só leitura. Nenhum limite muda por esta rota, e alterar o teto continua na área REAL, com as suas próprias proteções.
  - Valores em lamports `long`. No web, `bigint` aparece só em `features/real/money.ts`.

## ADR-009: `mode` igual a `market` e obsoleto no contrato

- **Status:** Accepted (FR2.4, requisitos Q6 e Q14).
- **Context:**
  - Hoje o `BotSummary` tem `mode` fixo em `PAPER` e o `market` verdadeiro.
  - Os clientes atuais leem `mode`.
  - A lista única passa a mostrar bots REAL.
- **Decision:**
  - O Bots devolve `mode == market` para os dois mercados.
  - `mode` fica marcado como obsoleto no OpenAPI.
  - Um teste de contrato confere a igualdade.
  - O web passa a ler só `market`.
- **Consequences:**
  - (+) Nenhum cliente quebra.
  - (+) Um bot REAL nunca aparece como PAPER.
  - (−) Dois campos com o mesmo valor até a remoção de `mode`, que vai num intent futuro, com expand-contract.
- **Alternatives Rejected:**
  - **Remover `mode` agora.** Quebra os clientes gerados a partir do contrato atual.
  - **Manter `mode = PAPER` fixo.** Um bot REAL apareceria como PAPER, o que viola a separação dos modos.
- **Security/compliance:** evita mostrar um bot REAL como simulado, um risco de a pessoa operar dinheiro real por engano (C-16, separação REAL/PAPER).

## ADR-010: Organização da interface por feature e selo SIMULADO comum

- **Status:** Accepted [Q6].
- **Context:**
  - A interface nova toca a criação de bots, a lista, o P&L e o painel.
  - `features/real` tem regras de lint próprias (`eslint.config.mjs`): sem biblioteca de transação, sem `Number(`, sem `Math.round` e com `bigint` só em `money.ts`.
  - `features/pnl` já existe (PnlScreen, BotTradesTable, LedgerList, `period.ts`, `decimal.ts`). A Q6 a chamou de "pasta nova", o que não muda a decisão.
  - O `eslint.config.mjs` aplica as proibições do dinheiro (`Number(`, `toFixed`, `parseFloat`) só a `features/real` e `app/real`.
- **Decision:**
  - `features/bots`: ModeStep, RealConfirmDialog, BotModeFilter e RealBotLimitsStep.
  - `features/pnl` (pasta que já existe): PnlModeBlock, ProratedLines, BotPnlLines, PeriodSelector e ComparisonView.
  - `features/real`: fica com o RealBadge e os componentes REAL.
  - `components/`: o SimulatedBadge, que passa a ser comum.
  - A formatação em SOL usa sempre `features/real/money.ts`.
  - A proibição de lint do dinheiro (`Number(`, `toFixed`, `parseFloat`) passa a valer também para `features/pnl`. O Functional Design escreve a mudança no `eslint.config.mjs` e o teste de lint que a prova.
  - O PeriodSelector reaproveita o `features/pnl/period.ts` que já existe, sem duplicar o cálculo do período. Só o padrão muda, de 7 dias para "Desde o início".
  - Os tipos vêm do contrato gerado (`openapi-guest.yaml`).
- **Consequences:**
  - (+) Cada tela fica junto da sua feature.
  - (+) As regras de lint do REAL continuam valendo onde já valem, e passam a cobrir os valores em SOL de `features/pnl`.
  - (−) Os componentes de `features/pnl` que mostram SOL dependem de `features/real/money.ts`.
  - (−) Código PAPER de `features/pnl` que use `Number(` ou `toFixed` precisa passar para os utilitários de decimal (`decimal.ts`).
- **Alternatives Rejected:**
  - **Tudo dentro de `features/real`** (Q6, opção B). Misturaria o PAPER dentro da pasta REAL e aplicaria as regras de lint do REAL a telas PAPER.
  - **Um selo SIMULADO duplicado em cada feature.** Os textos e a acessibilidade divergiriam.
- **Security/compliance:**
  - Nenhum segredo em `NEXT_PUBLIC_*`.
  - O navegador nunca monta nem envia transação (project.md).
  - O modo aparece em texto e não só em cor (NFR5).
  - O aviso do simulado aparece em toda tela com P&L simulado (C-16).

## ADR-011: Slot de confirmação gravado e chave de ordem de rede comum

- **Status:** Accepted. Decidido depois da revisão de arquitetura (R-01) e aprovado pelo responsável em 2026-11-21.
- **Context:**
  - O FR3.8 exige que as operações REAL sejam aplicadas na ordem da confirmação na rede: primeiro o slot, depois a assinatura.
  - Hoje o slot não é gravado nas tabelas do P&L:
    - `real_ledger_entry` (migração 0016) só tem `tx_signature` e `created_at`;
    - `real_order` (0019) só tem `tx_signature`, `sent_at` e `created_at`;
    - `RealOrderView` e `RealEntryRecord` não trazem slot;
    - o único slot gravado é o da tabela de reconciliação (0021).
  - O `RealPositions` aplica as vendas na ordem de gravação. Quando duas ordens se cruzam, a posição da carteira (ordem de gravação) e a soma das posições dos bots (ordem de rede) divergem, e o teste de paridade do ADR-002 falharia.
  - As migrações seguem o padrão expand-contract e nunca removem colunas na mesma implantação (team.md). O ledger é append-only (project.md).
- **Decision:**
  - **Migração expand.** A coluna `slot` (inteiro, aceita nulo) entra em `real_order` e em `real_ledger_entry`. A mesma migração cria o índice com slot da leitura em fluxo (ADR-004). Nenhuma coluna é removida e nenhum valor de lançamento é alterado.
  - **Gravação nova.** A confirmação da ordem (`realexecution`) grava o slot da transação confirmada em `real_order`. O assentamento leva esse slot a cada lançamento do ledger REAL. O `FillPosting` do `realexits` grava o slot da venda condicional executada. Outros lançamentos com assinatura de rede (depósito, saque, taxa) também gravam o slot que a confirmação já conhece.
  - **Linhas antigas.** São preenchidas, na mesma migração ou num job de preenchimento idempotente, a partir do slot de confirmação que já está gravado: a tabela de reconciliação (0021) ou os dados de confirmação, casando pela assinatura. As colunas exatas ficam para o Functional Design.
  - **Linha sem slot.** Uma linha que fica sem slot depois do preenchimento é sinalizada, com o motivo registrado (proposta: `SLOT_MISSING`), e ordenada pelo fallback.
  - **Chave de ordem de rede.** Uma única chave vale para o `RealPositions`, a porta `RealCostMethod`, o RealCostBasis, o RealBotPnl e o RealBotHoldings, tanto para a carteira quanto para cada bot:
    - com slot: `(slot, tx_signature)`;
    - sem slot (fallback): a linha herda o slot efetivo da última linha com slot gravada antes dela na mesma carteira, pela ordem de gravação `(created_at, entry_id)`, e fica depois das linhas desse slot. Entre linhas sem slot, vale a ordem de gravação.
  - O Functional Design pode ajustar o fallback, desde que ele continue único, determinístico e sinalizado.
  - O teste de paridade do ADR-002 usa essa mesma chave.
- **Consequences:**
  - (+) A ordem do FR3.8 é cumprida nas operações novas, e a posição da carteira bate com a soma das posições dos bots.
  - (+) A mudança só acrescenta: o ledger continua append-only, e o rollback volta só as imagens (team.md).
  - (−) Um dado novo a gravar em três pontos (confirmação, assentamento e `FillPosting`), com testes para cada um.
  - (−) Linhas antigas sem slot ficam numa ordem aproximada, mas declarada e visível pelo motivo.
- **Alternatives Rejected:**
  - **Não gravar o slot e usar sempre a ordem de gravação.** Não cumpre o FR3.8, e a paridade falha quando duas ordens se cruzam.
  - **Buscar o slot na rede a cada leitura.** Põe o RPC no caminho do P&L e do ciclo, com custo, latência e dependência de rede. Também quebra o determinismo dos testes.
  - **Ler o slot só da tabela de reconciliação.** Ela não cobre todo lançamento e pertence a outro fluxo; o slot passaria a depender da reconciliação ter rodado.
- **Security/compliance:**
  - Expand-contract, sem remover colunas.
  - Nenhum UPDATE do valor de um lançamento: só o preenchimento de uma coluna nova de metadado, feito pela migração ou por um job auditado. Isso preserva o "ledger sem edição" de team.md.
  - Os testes de integração usam o validador local e nunca a rede real.

## Assumptions & Open Questions

- [assumption] Os nomes `RealCostMethod`, `RealBotHoldings`, `BotDirectory`, `RealAccountLimits`, `pnl.modeview`, `NEVER_SUM_MODES`, `streamEntries` e `SLOT_MISSING` são propostas. O Contract Design e o Functional Design fixam os nomes e as assinaturas.
- O Functional Design fixa:
  - as colunas exatas da origem do preenchimento do slot nas linhas antigas e o detalhe do fallback (ADR-011);
  - o arredondamento da quantidade rateada e do custo de uma venda parcial, num só lugar: o RealCostBasis.
- O NFR Design entrega, antes da Code Generation:
  - o índice final com slot e o resultado do cenário de carga do NFR1;
  - o número do orçamento de tempo do RealBotHoldings dentro da transação do ciclo (ADR-006).
- O responsável confirma no gate que aceita a interface de leitura nova `RealAccountLimits` (ADR-008).
