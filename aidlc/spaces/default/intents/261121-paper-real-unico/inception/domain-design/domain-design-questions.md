# Perguntas do Domain Design — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes: `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md`, `inception/refined-mockups/` e os documentos `architecture.md`, `component-inventory.md` e `dependencies.md` do mapa do código.

## Q1. Onde fica o P&L REAL por bot

Em que módulo fica o P&L REAL por bot?

A. No módulo `pnl` que já existe, em `pnl.real`, como um componente novo, o RealBotPnl. Ele lê as ordens REAL, com o bot de cada uma, pela interface pública de `realexecution` e os lançamentos pela interface pública do ledger REAL, e nunca acessa as tabelas de outro módulo. O cálculo de rateio fica num componente de domínio puro, o RealProration: sem I/O e sem relógio, testável com os exemplos das histórias. (recomendado)
B. Um módulo novo `botpnl`
X. Other (please specify)

[Answer]: A

## Q2. Dono do custo médio REAL único

Que componente é dono do custo médio REAL?

A. O cálculo de custo médio REAL fica no `pnl.real`, como RealCostBasis, e é o único. O `RealPositions` do ledger passa a usar os números desse cálculo por uma interface pública. Os dois não podem divergir, e um teste de paridade confere isso (FR5.2). (recomendado)
B. O ledger é o dono e o pnl usa o cálculo dele
X. Other (please specify)

[Answer]: A

## Q3. Visão unificada por modo

Quem monta a visão por modo?

A. Um componente novo de aplicação, o ModePnlView, monta a resposta por modo: o bloco PAPER, com o P&L PAPER por bot que já existe, e o bloco REAL, com o RealBotPnl. Ele nunca soma os dois blocos e tem um teste de arquitetura que proíbe somá-los. O período (FR7) é um parâmetro único para os dois blocos. (recomendado)
B. A tela chama as duas APIs e junta os resultados
X. Other (please specify)

[Answer]: A

## Q4. Criação de bot em qualquer modo

Que componentes cuidam da criação do bot em qualquer modo?

A. O `bots` continua dono do bot e do `market`. A elegibilidade do REAL na criação usa a leitura que já existe em `botwallet`: estado da carteira, liberação e REAL ligado. O kill switch vem de `realops`, e o servidor devolve só o motivo mais forte (OQ3 dos mockups). Os limites REAL da conta mostrados no passo do bot REAL vêm da interface de leitura que já existe em `riskcontrol.real`. (recomendado)
B. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. Mudança do ciclo (FR6)

Quem fica com a mudança do ciclo do bot REAL?

A. O `bots` (o RealCycle no worker) passa a pedir a venda só da quantidade do próprio bot. Essa quantidade é lida no RealBotPnl, pela interface do `pnl.real`. O `realexecution` continua dono da ordem. A mudança entra por último, depois da Performance Validation do intent 261113. (recomendado)
B. O `realexecution` calcula a quantidade do bot
X. Other (please specify)

[Answer]: A

## Q6. Estrutura da interface

Como a interface se organiza?

A. Em `apps/web/src/features/`:
   - `bots`: o passo ModeStep, o RealConfirmDialog, o BotModeFilter e o passo de limites do bot REAL;
   - `pnl`: uma pasta nova com PnlModeBlock, ProratedLines, PeriodSelector e ComparisonView;
   - `real`: continua com o RealBadge e os componentes do REAL;
   - `components/`: o selo SimulatedBadge, que passa a ser comum.

   Os tipos vêm sempre do contrato da API gerado. (recomendado)
B. Tudo dentro de `features/real`
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **P&L REAL por bot:** fica em `pnl.real`, no componente RealBotPnl. Ele lê as ordens e o ledger só pelas interfaces públicas. O rateio fica num componente de domínio puro, o RealProration [Q1].
- **Custo médio REAL:** existe um único cálculo, o RealCostBasis, em `pnl.real`. O `RealPositions` passa a usá-lo, e um teste de paridade confere [Q2].
- **Montagem por modo:** o ModePnlView monta os blocos PAPER e REAL, com um período único para os dois. Um teste de arquitetura proíbe somar os modos [Q3].
- **Bot e mercado:** o `bots` continua dono do bot e do `market`. A elegibilidade do REAL vem de `botwallet` e `realops`, com o motivo mais forte decidido pelo servidor. Os limites da conta vêm de `riskcontrol.real` [Q4].
- **Mudança do ciclo:** o ciclo REAL vende só a quantidade do próprio bot, lida no RealBotPnl. Essa mudança entra por último, depois da Performance Validation do REAL [Q5].
- **Interface:** `features/bots`, `features/pnl` (nova), `features/real` e o SimulatedBadge comum. Os tipos vêm do contrato gerado [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
