# Componentes de interface — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

A U3 cria seis componentes no `apps/web` (BotModeFilter, PeriodSelector, ProratedLines, BotPnlLines, ComparisonView e DashboardModeCards) e acrescenta ao PnlModeBlock da U1 as variantes `compact` e `bot`, as parcelas rateadas e a linha "Sem bot". Ela também muda a lista `/bots`, o resultado `/pnl`, o detalhe `/bots/{id}` e o painel `/`. Os textos de interface ficam em português do Brasil; nomes de componentes, props e valores de enum ficam em inglês (`team.md`, Code Style). Nenhum componente soma, subtrai ou converte dinheiro (BR6.1).

## Hierarquia

```
/bots  (BotsShell > BotList)
  BotModeFilter                                   fieldset "Modo": Todos | Simulado | Real  (foco inicial)
  filtro de estado (existente)
  "Mostrando N de M bots." (aria-live)
  aviso do simulado (quando há bot simulado visível e o filtro não é Real)
  tabela >= 768 px | cartões < 768 px (U1)

/pnl  (PnlScreen)
  h1 "Resultado"
  PeriodSelector                                  fieldset "Período" + "Mostrando: …" (aria-live)
  PnlModeBlock market=PAPER variant=full          + "Ver por token": tabela PAPER existente com período concreto;
                                                  nota "Escolha um período para ver o resultado por token" em ALL
  PnlModeBlock market=REAL  variant=full
    por bot REAL: ProratedLines
    linha "Sem bot" com os motivos
  ComparisonView                                  botão que abre e fecha
    select "Bot simulado", select "Bot real"
    lado PAPER: PnlModeBlock variant=bot (+ aviso) | lado REAL: PnlModeBlock variant=bot
  ReconciliationPanel e LedgerList (existentes, PAPER, com o selo)

/bots/{id}  (BotsShell > BotDetail)
  h1 nome + selo; "Modo: … · não pode ser trocado"
  PnlModeBlock variant=bot (desde o início)       aviso no PAPER
  REAL: BotPnlLines                               tabela >= 768 px em TableScroll | cartões < 768 px
  PAPER: seções existentes (operações simuladas, limites, decisões)

/  (DashboardScreen)
  DashboardModeCards                              section "Resultados por modo · desde o início"
    PnlModeBlock market=PAPER variant=compact
    PnlModeBlock market=REAL  variant=compact
  BotsCard (existente)
```

## Componentes novos

### BotModeFilter

Lugar: `apps/web/src/features/bots`. Fonte: interaction-spec "BotModeFilter", mockups M4.2 e M4.4. Regras: BR1.1 a BR1.5.

| Prop | Tipo lógico | Obrigatória | Padrão | Descrição |
|---|---|---|---|---|
| `value` | `'ALL' \| 'PAPER' \| 'REAL'` | sim | `'ALL'` | o filtro, lido do endereço pelo pai |
| `onChange` | função(valor) | sim | — | o pai troca o endereço e relê a lista |
| `matchingCount` | inteiro | não | — | N de "Mostrando N de M bots.", de `BotPage` |
| `totalCount` | inteiro | não | — | M, de `BotPage` |
| `autoFocus` | booleano | não | `true` em `/bots` | foco inicial no rádio marcado (AC2.3.5) |

- **Estado interno:** nenhum; o valor vive no endereço.
- **Interação:** três rádios nativos (`name="bot-mode-filter"`) num `fieldset` com `legend` "Modo"; as setas trocam e aplicam; a troca não move o foco.
- **Contagem:** com `PAPER` ou `REAL`, a linha "Mostrando N de M bots." fica numa região `aria-live="polite"`.
- **Sem bots:** com `totalCount = 0`, o pai não monta o filtro.
- **Responsivo:** opções empilhadas, com 44 px de altura, abaixo de 768 px; numa linha acima.

### PeriodSelector

Lugar: `apps/web/src/features/pnl`. Reusa `period.ts` (`toUtcPeriod`, `startOfLocalDay`, `addDays`, `todayIn`) sem mudar as funções. Fonte: interaction-spec "PeriodSelector", mockups M5.5. Regras: BR2.1 a BR2.7.

| Prop | Tipo lógico | Obrigatória | Padrão | Descrição |
|---|---|---|---|---|
| `value` | `PeriodChoice` (`ALL`, `TODAY`, `LAST_7`, `LAST_30`, `RANGE` com `fromDay` e `toDay`) | sim | `ALL` | o período aplicado, lido do endereço |
| `onChange` | função(período, `UtcPeriod` ou nulo) | sim | — | nulo em `ALL`; o pai troca o endereço e relê |
| `timeZone` | texto | não | fuso do navegador | o fuso dos dias |
| `now` | função que devolve o instante | não | relógio do app | relógio injetável para os testes |

- **Estado interno:** o rascunho do intervalo (`draftFrom`, `draftTo`) e os erros por campo. O rascunho fica mesmo com erro.
- **Opções:** "Desde o início", "Hoje", "Últimos 7 dias", "Últimos 30 dias" e "Intervalo" (Q6, decisão do fundador de 2026-11-23); `?periodo=hoje|7d|30d|intervalo`.
- **Cálculo:** Hoje = `toUtcPeriod(hoje, hoje, fuso)`; 7 dias = `toUtcPeriod(addDays(hoje, -6), hoje, fuso)`; 30 dias = `toUtcPeriod(addDays(hoje, -29), hoje, fuso)`; intervalo = `toUtcPeriod(de, ate, fuso)`. Um `null` de `toUtcPeriod` vira a mensagem do campo (BR2.3). O par UTC é calculado quando a opção é aplicada e fica fixo até a próxima aplicação, mesmo depois da meia-noite local (BR2.2).
- **Validação ao aplicar:**

| Caso | Mensagem | Campo |
|---|---|---|
| data vazia ou fora de `AAAA-MM-DD` | "Informe a data no formato dd/mm/aaaa." | o campo com erro |
| fim antes do início | "A data final vem antes da inicial. Corrija o campo "Até"." | Até |
| início depois de hoje (intervalo inteiro no futuro) | "O período começa depois de hoje. Escolha um dia inicial até hoje." | De |
| mais de 366 dias de duração em UTC (inclusive 366 dias de calendário que ganham uma hora no fim do horário de verão) | "Escolha um período de até 366 dias." | Até |

- **Texto aplicado:** "Mostrando: <período no fuso> · realizado no período; não realizado de agora", numa região `aria-live="polite"` (BR2.7). Em ALL: "Mostrando: desde o início · datas no seu fuso (<fuso>)".
- **Acessibilidade:** `fieldset` com `legend` "Período", cinco rádios nativos (Desde o início, Hoje, Últimos 7 dias, Últimos 30 dias, Intervalo) e dois `input type="date"` com `label`; a ajuda "Datas no seu fuso (<fuso>)." ligada por `aria-describedby`; o erro com `aria-invalid` e `aria-describedby`, anunciado por `role="alert"`; depois de "Aplicar" com erro, o foco vai ao primeiro campo com erro.

### ProratedLines

Lugar: `apps/web/src/features/pnl`, dentro do `PnlModeBlock` REAL `full`. Fonte: interaction-spec "ProratedLines", mockups M5.3. Regras: BR4.1, BR4.7, BR2.5, BR11.1.

| Prop | Tipo lógico | Obrigatória | Descrição |
|---|---|---|---|
| `botId` | uuid | sim | para o link "Ver todas no detalhe do bot" |
| `botName` | texto | sim | para o nome acessível do botão |
| `prorated` | `ProratedSharesView` (C4) | sim | `summary`, `count`, `lines` (até 10) |
| `timeZone` | texto | não | para as datas |

- **Estado interno:** `expanded` (booleano), só na página.
- **Exibição:** com `count = 0`, nada é montado. Senão, uma linha "inclui <valor> <origem>" por item de `summary` (valor da API), o botão "Ver N linhas rateadas de <bot>" (parte do texto visualmente oculta) com `aria-expanded` e `aria-controls`, e a lista: data no fuso, token, origem em texto, recebido, taxa e realizado, em SOL. Com `count > 10`, o link "Ver todas no detalhe do bot".
- **Responsivo:** o mesmo ponto de troca de todas as telas da U3, 768 px (BR10.1): cartões com rótulos abaixo de 768 px; tabela com `caption` dentro de um `TableScroll` a partir de 768 px, porque a coluna do bloco REAL é estreita até 1.199 px.

### BotPnlLines

Lugar: `apps/web/src/features/pnl`, usado pelo `BotDetail` de um bot REAL. Fonte: interaction-spec "BotPnlLines", mockups M7.1 e M7.3. Regras: BR4.1 a BR4.6, BR2.5, BR8.2, BR10.1, BR10.2.

| Prop | Tipo lógico | Obrigatória | Descrição |
|---|---|---|---|
| `botId` | uuid | sim | o bot; o tenant vem do token, no servidor |
| `botName` | texto | sim | para o `caption` "Linhas do P&L de <bot>" |
| `timeZone` | texto | não | para as datas (AC7.2.2) |
| `onNotFound` | função | sim | o `BotDetail` troca a tela por "Bot não encontrado." |

- **Estado interno:** `BotPnlLinesState` (`phase`, `items`, `nextCursor`, `code`, `firstNewIndex`).
- **Leitura:** C5 com `limit=50`; "Carregar mais" com `cursor = nextCursor`. Os itens entram no fim, na ordem recebida.
- **Colunas:** "Data (seu fuso)", "Lado", "Token", "Resultado (SOL)", "Taxa (SOL)", "Origem". Uma compra mostra "—" com `aria-label` "sem resultado".
- **Estados:** carregando ("Carregando as linhas do P&L…", `role="status"`); vazio ("Nenhuma operação real até agora."); erro (texto, código e "Tentar de novo"); erro da página seguinte (linhas mantidas e erro junto do botão); 404 → `onNotFound`.
- **Foco:** depois de "Carregar mais", a primeira linha nova (`tabIndex=-1`) recebe o foco.
- **Responsivo:** abaixo de 768 px, cada linha vira um cartão com rótulos (padrão `real-orders-cards`); a partir de 768 px, a tabela fica dentro de um `TableScroll` com `aria-label` "Linhas do P&L de <bot>".

### ComparisonView

Lugar: `apps/web/src/features/pnl`, dentro de `/pnl`. Fonte: interaction-spec "ComparisonView", mockups M5.7. Regras: BR5.1 a BR5.6, BR2.6, BR6.1 a BR6.3, BR9.2.

| Prop | Tipo lógico | Obrigatória | Descrição |
|---|---|---|---|
| `bots` | lista de `BotSummary` (com `market` e `templateId`) | sim | os bots da pessoa, de `listBots` |
| `selection` | `ComparisonSelection` | sim | lida do endereço `?comparar=` |
| `onSelect` | função(seleção ou nula) | sim | o pai troca o endereço; nula fecha |
| `period` | `UtcPeriod` ou nulo | sim | o mesmo do `PeriodSelector` |
| `timeZone` | texto | não | para as datas de criação |

- **Estado interno:** `ComparisonView.phase` e um `ComparisonSideState` por lado.
- **Seleção:** "Bot simulado" com os PAPER; "Bot real" desabilitado até haver bot simulado e então só com os REAL de mesmo `templateId`; um único par vem escolhido.
- **Leitura:** C6 com `paperBotId`, `realBotId` e o período. Releitura de um lado em erro: C6 de novo, e só o lado em erro recebe o resultado.
- **Lados:** `PnlModeBlock` variante `bot` com estado e "Criado em dd/mm"; h3 "<nome>, simulado, em dólares" e "<nome>, real, em SOL"; o aviso só no PAPER; a frase "Os valores ficam cada um na sua moeda e não são comparados por conta.".
- **Respostas:** 422 `TEMPLATE_MISMATCH` → "Esses dois bots usam estratégias diferentes. Escolha um bot real da mesma estratégia do bot simulado.", bot real limpo, foco no select real; 404 com `reason` → "Bot não encontrado." no lado, select limpo e com foco.
- **Acessibilidade:** botão com `aria-expanded` e `aria-controls`; região com h2 "Comparação"; ao abrir, o foco vai ao select "Bot simulado"; ao fechar, volta ao botão; totais com "Total do bot simulado, em dólares" e "Total do bot real, em SOL".
- **Responsivo:** `select` e lados empilhados abaixo de 768 px, Simulado e depois Real; duas colunas acima.

### DashboardModeCards

Lugar: `apps/web/src/features/dashboard`. Fonte: mockups M6, accessibility-checklist (painel), Q5. Regras: BR7.1 a BR7.5, BR3.2, BR9.2.

| Prop | Tipo lógico | Obrigatória | Padrão | Descrição |
|---|---|---|---|---|
| `now` | função que devolve o instante | não | relógio do app | para o limite de 30 s |
| `timeZone` | texto | não | fuso do navegador | para "marcação às HH:MM" |

- **Estado interno:** um `DashboardModeCard` por modo (`load`, `realUnavailable`, `lastAnnouncedAt`, `lastReloadAt`, `reloadPending`).
- **Leitura:** C4 sem `market` e `period=ALL`, e C2, em paralelo; o "Tentar de novo" de um cartão relê C4 com `market`.
- **Canal ao vivo (BR7.5):** o componente assina os eventos que o `DashboardProvider` já recebe do `DashboardMarkPoller`, sem mudar o canal:

| Evento | Quem envia | Cartão que relê | Pedido |
|---|---|---|---|
| `pnl.updated` | `DashboardMarkPoller`, quando muda o `ledgerMark` do ledger PAPER | Simulado | C4 `market=PAPER`, `period=ALL` |
| `real.order.state` com `state` `CONFIRMED` ou `FAILED` | `sendRealChanges` | Real | C4 `market=REAL`, `period=ALL` |
| `real.conditional.state` | `sendRealExitChanges` | Real | C4 `market=REAL`, `period=ALL` |
| `real.order.state` em outro estado, `real.signing.requested`, `bot.state`, `trade.recorded`, `data.status`, `heartbeat` | — | nenhum | — |

Cada cartão relê no máximo uma vez a cada 30 s; um evento dentro da janela marca `reloadPending` e relê no fim dela. Testes de componente, um por cartão, com relógio injetável: cada evento relê só o cartão do seu modo; `real.order.state` `SENT` não relê; dois eventos em 30 s dão uma releitura imediata e uma no fim da janela.
- **Exibição:** `section` com `aria-labelledby` para "Resultados por modo · desde o início"; dois `PnlModeBlock` `compact` com h3 "Resultado simulado" e "Resultado real".
- **Compõe e não formata:** o componente não escreve nenhum valor; quem escreve é o `PnlModeBlock` (BR11.1).
- **Substitui:** o `PnlCard` e o `DashboardRealPnl` saem do `DashboardScreen`; o foco inicial continua no título do primeiro cartão.

## Componentes que mudam

| Componente | Mudança na U3 | Regras |
|---|---|---|
| `PnlModeBlock` (C17, dono U1) | variante `compact` (total, realizado, taxas, aviso no PAPER, link "Ver o resultado…" para `/pnl`; títulos h3); variante `bot` (total, realizado, não realizado e taxas de uma linha de bot, rótulo "Total do bot, em …"); no REAL `full`, o `ProratedLines` por bot e a linha "Sem bot" com `pnl.noBotReason.*`; prop opcional `realUnavailable` para o vazio REAL; prop opcional `headingLevel`. Só acréscimos: os testes da U1 continuam verdes | BR3.4, BR4.7, BR4.8, BR7.1 a BR7.4, BR9.2 |
| `PnlScreen` | `PeriodSelector` no lugar do formulário antigo, com "Desde o início" por padrão; C4 com o período; "Tentar de novo" com `market`; `ComparisonView`; a tabela por token do PAPER dentro do bloco Simulado ("Ver por token"), lida de `GET /api/v1/pnl` só com período concreto e trocada pela nota "Escolha um período para ver o resultado por token" em "Desde o início" (decisão do fundador) | BR2.1 a BR2.9, BR3.1, BR3.2, BR5.6 |
| `BotList` | `BotModeFilter`; `listBots` com `market`; C4 com o mesmo `market`; textos de vazio pelo filtro; "Mostrando N de M bots." | BR1.1 a BR1.7 |
| `BotDetail` | cabeçalho com o selo e o modo em texto (sai o rótulo "MODO PAPER (simulado)"); `PnlModeBlock` `bot` a partir de C4; `BotPnlLines` no REAL; aviso no PAPER; "Bot não encontrado." para 404 | BR4.5, BR4.6, BR8.2, BR9.3 |
| `DashboardScreen` | `DashboardModeCards` no lugar do `PnlCard` e do `DashboardRealPnl` | BR7.1 |
| `mode-messages.ts` | chaves novas: `pnl.origins.*`, `pnl.noBot`, `pnl.noBotReason.*`, `pnl.period.*`, `dashboard.*`, `compare.*` (inclusive `compare.templateMismatch`), `list.filter.*`, `list.shown`, `list.emptyPaper`, `list.emptyReal` | BR1.3, BR4.1, BR4.8, BR5.2 |
| `by-mode.ts` | `paperLoad` e `realLoad` aceitam a chave ausente (pedido com `market` do outro modo; a API omite a chave, nunca envia `null`) sem virar erro | BR3.1 |

## Validação de formulário

| Campo | Regra | Mensagem | Fonte |
|---|---|---|---|
| Filtro de modo | só `ALL`, `PAPER` ou `REAL`; inválido no endereço vale Todos | — | BR1.7 |
| Período "De" e "Até" | `AAAA-MM-DD` válido | "Informe a data no formato dd/mm/aaaa." | BR2.3 |
| Período "Até" | não antes de "De" | "A data final vem antes da inicial. Corrija o campo "Até"." | AC7.1.4, BR2.3 |
| Período | no máximo 366 dias | "Escolha um período de até 366 dias." | BR2.3 |
| "Bot real" da comparação | só com bot simulado escolhido e mesmo `templateId` | "Escolha primeiro o bot simulado." | BR5.1 |

## Integração com a API

Os tipos vêm do contrato gerado de `openapi-guest.yaml`, nunca escritos à mão. O `check:api` do `apps/web` falha numa quebra.

| Componente ou tela | Chamada | Respostas tratadas |
|---|---|---|
| BotList / BotModeFilter | `GET /api/v1/bots?state=&market=` (listBots, `market` novo) | 200 com `matchingCount` e `totalCount` (novos); 400; 401; 403 termos |
| BotList | `GET /api/v1/pnl/by-mode?market=` (C4) | 200 com o bloco pedido; bloco `UNAVAILABLE` → "P&L indisponível agora" |
| PnlScreen / PeriodSelector | `GET /api/v1/pnl/by-mode?period=&from=&to=` (C4, sem `market`) | 200 com os dois blocos; 400 (vira erro dos dois blocos) |
| PnlModeBlock "Tentar de novo" | `GET /api/v1/pnl/by-mode?market=&period=&from=&to=` (C4) | 200 com só o bloco pedido |
| ComparisonView | `GET /api/v1/bots` (listBots, sem filtro) e `GET /api/v1/pnl/compare?paperBotId=&realBotId=&period=&from=&to=` (C6) | 200 com lados `AVAILABLE` ou `UNAVAILABLE`; 400; 404 com `reason`; 422 `TEMPLATE_MISMATCH` |
| BotDetail (cabeçalho) | `GET /api/v1/bots/{id}` (nome, modo, estado) e `GET /api/v1/pnl/by-mode?market=<modo do bot>&period=ALL` (C4, o bloco inteiro; a linha do bot pelo `botId`; custo aceito, BR4.6) | 200; 404 → "Bot não encontrado." |
| PnlScreen ("Ver por token") | `GET /api/v1/pnl?from=&to=` (existente), só com período concreto | 200; 400; erro com código e "Tentar de novo" só da tabela |
| BotPnlLines | `GET /api/v1/bots/{id}/pnl/lines?limit=50&cursor=` (C5) | 200; 400 (cursor); 404 → `onNotFound` |
| DashboardModeCards | `GET /api/v1/pnl/by-mode?period=ALL` (C4) e `GET /api/v1/bots/real-eligibility` (C2) | 200; bloco `UNAVAILABLE`; C2 503 → cartão real vazio sem link |

Em todas as chamadas: 401 expira a sessão pelo fluxo existente; 403 `TERMS_REACCEPTANCE_REQUIRED` segue o fluxo de novo aceite; nenhuma chamada leva id de tenant ou de pessoa (BR8.1).

## Acréscimos ao spec de ponta a ponta `e2e/zz-paper-real-unico`

O spec continua o último da suíte (prefixo `zz-`, `project.md`) e usa os dois bots e as duas compras que já faz (um PAPER e um REAL, da mesma estratégia gravada). Os casos novos só leem telas e rotas; nenhum muda a conta do smoke antes dos specs anteriores. Regras: BR12.1, BR10.1, BR6.2.

| Caso novo | Confere | Critérios |
|---|---|---|
| painel com os dois cartões | "Resultado simulado" em US$ com o aviso; "Resultado real" em SOL sem o aviso; nenhum "Total geral" no painel; "Ver o resultado real" leva a `/pnl` | AC4.3.1, AC4.3.4, AC4.2.2, AC4.1.2 |
| filtro em `/bots` | o foco inicial no filtro; "Simulado" mostra só o bot PAPER; "Real" mostra só o bot REAL e não mostra o aviso; o endereço tem `?mode=` | AC2.3.1 a AC2.3.3, AC2.3.5 |
| detalhe do bot REAL | a tabela com a coluna "Origem" e a linha da compra com "Bot" e "—" no resultado; a data no formato dd/mm HH:MM | AC3.10.1, AC3.10.2, AC3.10.4 |
| detalhe do bot PAPER | o aviso do simulado | AC4.2.4 |
| detalhe de um id inexistente | "Bot não encontrado." sem dado | AC3.10.3 |
| período "Últimos 7 dias" em `/pnl` | o endereço com `?periodo=7d`; o pedido de C4 com `period=RANGE` e `from`/`to` terminando em `Z`; os dois blocos separados e sem "Total geral" | AC7.1.5, AC7.2.3 |
| comparação | os dois bots de mesma estratégia lado a lado, cada um na sua moeda, o aviso só no lado simulado e nenhum "Total geral" ou diferença | AC8.1.1 a AC8.1.3 |
| 360 px | `scrollWidth` do documento igual a `clientWidth` em `/`, `/bots?mode=REAL`, `/bots/{id}` dos dois bots e `/pnl` com a comparação aberta | AC4.5.1 |
| 360 px com dados montados | o Playwright intercepta C4 (bloco REAL com um bot com 12 parcelas rateadas e uma linha "Sem bot") e C5 (50 linhas com `nextCursor` e a página seguinte), com fixtures sintéticas sem carteira nem mint real; em `/pnl`, abre "Ver 12 linhas rateadas"; em `/bots/{id}`, clica em "Carregar mais"; confere `scrollWidth` igual a `clientWidth` antes e depois e que parcelas e linhas viram cartões | AC4.5.1, AC3.10.1 |
| período "Desde o início" e "Hoje" em `/pnl` | em "Desde o início", a nota "Escolha um período para ver o resultado por token" no lugar da tabela; com "Hoje", a tabela aparece e o endereço tem `?periodo=hoje` | AC7.1.2, BR2.9 |

Os casos de C6 com bot de outra pessoa (404) e com `templateId` diferente (422) ficam nos testes de rota do backend, que já criam dois tenants; o spec não cria outra conta. O teste de contrato de C4 cobre os três casos de `market` (sem ele, `PAPER`, `REAL`) com a chave do bloco não pedido ausente do JSON.

## Sources

- `functional-design-questions.md` desta pasta: Q1 a Q7.
- `entities.md`, `rules.md` e `functional-spec.md` desta pasta.
- `inception/refined-mockups/interaction-spec.md` (PnlModeBlock, ProratedLines, PeriodSelector, BotModeFilter, ComparisonView, BotPnlLines, "Componentes que já existem e mudam"), `mockups.md` (M4 a M7), `accessibility-checklist.md`, `design-system-mapping.md`.
- `inception/contract-design/contract-summary.md`: C2, C4, C5, C6, C17.
- `construction/u1-mode-skeleton/functional-design/frontend-components.md` e `code-generation/code-summary.md` (`TableScroll`, `money-format.ts`, `by-mode.ts`, spec `zz-paper-real-unico`).
- `aidlc/spaces/default/memory/project.md`: `TableScroll` em 360 px e spec `zz-` por último.
- Código lido: `apps/web/src/features/pnl/{PnlModeBlock.tsx,PnlScreen.tsx,period.ts,by-mode.ts,money-format.ts,mode-messages.ts}`, `features/bots/{BotList.tsx,BotDetail.tsx}`, `features/dashboard/{DashboardScreen.tsx,PnlCard.tsx}`, `components/TableScroll.tsx`, `e2e/zz-paper-real-unico/paper-real-unico.spec.ts`.

## Assumptions & Open Questions

1. [assumption] **Lugar dos componentes e lint (BR11.1).** Os componentes que escrevem dinheiro ficam em `features/pnl`, onde o eslint já proíbe `bigint`, `parseFloat` e `toFixed`. `DashboardModeCards` fica em `features/dashboard` (que já proíbe `Number(` e `parseFloat`) e só compõe dois `PnlModeBlock`. `BotList` e `BotDetail` ficam em `features/bots`, onde o `bigint` não é proibido por causa de `decimal.ts`; eles só chamam `money-format.ts` e não ganham `bigint` novo. O `eslint.config.mjs` não muda. Nenhuma conta sobre o texto de um valor é permitida, e isso não tem lint: a garantia é o teste de componente e a revisão.
2. [assumption] **`headingLevel` no `PnlModeBlock`.** A variante `compact` usa h3 (painel) e a `bot` usa h3 na comparação e h2 no detalhe; a prop opcional evita um segundo componente.
3. [assumption] **Mesma estratégia no spec.** Os dois bots do spec usam a estratégia gravada, então têm o mesmo `templateId`, e a comparação aparece com um único par já escolhido.
4. [assumption] **Linha do `verify.sh`.** Os casos novos entram no arquivo que já está no `verify.sh`; nenhuma linha nova é pedida ao responsável.
5. **Decisão: chave do filtro.** O endereço usa `?mode=PAPER|REAL` (Q2); o `?modo=simulado` do mockup M4.2 fica superado.
6. **Decisões do fundador (2026-11-23).** "Hoje" entra no `PeriodSelector`; a tabela PAPER por token só aparece com período concreto, e em "Desde o início" a tela mostra "Escolha um período para ver o resultado por token". Os totais por modo aparecem sempre.
