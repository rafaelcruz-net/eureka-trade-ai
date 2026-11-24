# Desenho de confiabilidade — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` (R-01, R-02, R-04, R-06) e das decisões do fundador de 2026-11-23 (F-1 e F-3).

Reúne o comportamento sob falha, a exatidão do que a tela mostra, a acessibilidade, as telas e a verificação da U3, na mesma divisão do `reliability-requirements.md`. A regra geral: uma falha de um bloco, de um lado ou de uma página fica naquele bloco, lado ou página; o resto da tela continua e nenhuma resposta antiga substitui uma nova [Q1] [Q3].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Disponibilidade | sem SLO novo; nenhuma rota da U3 na prontidão | NFR4.6 |
| Bloco de C4 com `market` | falha vira o bloco `UNAVAILABLE` com resposta 200 | NFR4.7 |
| "Tentar de novo" | 1 pedido só do bloco ou do lado em erro; o outro não muda | NFR4.8, NFR4.9 |
| Página seguinte de C5 | falha mantém as linhas; repetir usa o mesmo cursor; cursor recusado oferece "Recarregar do início"; sem repetição automática do cliente | NFR4.10 |
| Resposta antiga | `AbortController` por vaga e `requestId` no reducer | NFR4.11 |
| Canal ao vivo | temporizador de 30 s com 1 releitura pendente; nenhum evento perdido | NFR4.12 |
| Leituras | idempotentes; a única gravação é a auditoria limitada | NFR4.13 |
| Exatidão | o mesmo cálculo da U2 e da U1; soma exata das páginas de C5; cursor estável; período em UTC | NFR2.1 a NFR2.4 |
| Acessibilidade e telas | regras da U1 nas telas novas, com o foco definido por caso | NFR5.1 a NFR5.4, NFR6.1, NFR6.2 |
| Verificação | Test Strategy `Standard`, piso de 80%, testes por risco, contrato, período e o spec `zz-` ampliado | NFR7.1 a NFR7.8 |

## Disponibilidade (NFR4.6)

0 SLOs novos. O grupo de prontidão (`readinessState`, `db`, `migrations`) não muda e nenhuma rota da U3 entra nele: uma falha de leitura de P&L nunca tira a API do balanceador. O executor `pnl-read-audit` e o pool `pnl-mismatch-audit` também ficam fora da prontidão, como na U2.

## Tolerância a falhas e degradação

### C4 com `market` (NFR4.7)

`ModePnlView.single(market, window, bloco, sink)` envia um só bloco. Qualquer exceção do bloco, prazo passado ou fila cheia vira `BlockResult.Unavailable` com `code` e o `correlationId` lido na thread do pedido; a rota responde 200. Nenhuma exceção do bloco sai da view. Teste: `PnlByModeMarketFailureIT` injeta falha no bloco pedido, nos dois valores de `market`, e confere 200 em todos os casos.

### "Tentar de novo" de um bloco (NFR4.8)

Na tela, o "Tentar de novo" de um bloco abre a vaga `retry:<market>`, pede C4 com o `market` do bloco e o par UTC em vigor, e despacha `BLOCK_RETRY_REQUESTED {market}`. O reducer só põe aquele bloco em carregando, e só aquele bloco recebe o resultado. Depois da resposta, o foco vai ao título do bloco relido. Vale em `/pnl`, no painel e em `/bots`. Teste de componente: 1 pedido com o `market`, 0 para o outro, e o outro bloco com o mesmo texto antes e depois.

### C6 (NFR4.9)

No servidor, cada lado é um bloco do pool com o próprio prazo; um lado que falha sai `UNAVAILABLE`, e o outro mostra os valores. A resposta é 200 nos 3 cenários (falha no PAPER, no REAL, nos dois); o `PnlCompareSideFailureIT` confere. Na tela, "Tentar de novo" de um lado pede C6 de novo (os mesmos ids e período) e despacha `COMPARE_SIDE_RETRIED {side}`; o reducer só aplica a resposta ao lado que estava em erro, e o lado sem erro não volta a carregar.

### Página seguinte de C5 (NFR4.10)

O estado `BotPnlLinesState` guarda `items`, `nextCursor` e a fase. "Carregar mais" passa a `loadingMore` com o cursor usado; uma falha vai para `moreError` mantendo os `items`; "Tentar de novo" repete o mesmo cursor (1 pedido). Um 404 vira `notFound` e troca a tela inteira por "Bot não encontrado.".

- **Cursor recusado (revisão R-04).** Um 400 com o campo `cursor` na página seguinte leva à fase `cursorStale`: a tela mostra "A lista mudou desde a última página." e "Recarregar do início", que pede a 1ª página sem cursor e troca todos os itens. Ela nunca repete o cursor recusado. Isso cobre o cursor que o próprio servidor deu quando uma linha derivada do rateio mudou entre duas páginas.
- **Sem repetição automática (revisão R-02).** C5 e a tabela por token chamam o cliente com `retry: false`: um 503 vira erro na primeira tentativa, e cada clique faz 1 pedido. Teste do cliente: com 503, exatamente 1 `fetch` em cada uma das duas chamadas.
- **Fases.** `loading`, `ready`, `empty`, `error`, `loadingMore`, `moreError`, `cursorStale` e `notFound`; a tabela de estados de `functional-spec.md` ganha a transição `loadingMore` → `cursorStale` (400 de `cursor`) → `loading` ("Recarregar do início").

### Resposta antiga (NFR4.11)

Duas defesas, as duas na tela:

1. Abrir um pedido numa vaga aborta o anterior da mesma vaga (`AbortController`). O cliente lança `RequestCancelled`, que a tela ignora, sem estado de erro.
2. Cada `*_LOADED` leva o `requestId` da vaga; o reducer ignora um `requestId` que não é o atual. Isso cobre uma resposta já lida antes do cancelamento.

Teste de componente com 2 pedidos em sequência e a 1ª resposta chegando depois da 2ª: a tela mostra só a 2ª, no filtro de modo, no período e na comparação.

### Canal ao vivo (NFR4.12)

O `windowedRefresh` (`performance-design.md`) faz 1 releitura imediata e marca 1 pendente para o fim da janela de 30 s. Testes de componente com relógio e temporizador injetáveis, um por cartão:

| Caso | Pedidos |
|---|---|
| 5 eventos do mesmo modo dentro de 30 s | 2: 1 imediato e 1 no fim da janela |
| 1 evento logo depois da releitura pendente | espera o fim da janela seguinte, 30 s depois dela |
| evento do outro modo | 0 |
| `real.order.state` `SENT` ou `real.signing.requested` | 0 |
| valor final | o da última resposta |

### Idempotência (NFR4.13)

C4, `listBots`, C5 e C6 só leem. O `PnlViewsReadOnlyIT` conta as linhas das tabelas tocadas antes e depois de 3 leituras seguidas de cada rota: diferença 0. A única gravação é o evento de auditoria de uma recusa, no executor `pnl-read-audit`, limitado a 1 por hora por pessoa e por ação; 3 recusas iguais seguidas dão 3 respostas iguais.

### Falha da auditoria

Fila cheia, prazo, conexão ou erro na gravação nunca mudam a resposta: log `ERROR` (no máximo 1 por minuto por pessoa e ação) e `pnl_read_audit_failures_total{action}` (`security-design.md`). Só um 404 confirmado de outro tenant ou um 422 chegam ao executor (decisão F-1 do fundador), então uma rajada de uuid inexistente não enche a fila. É uma falha recuperável: uma falha ou recusa não marca o filtro em memória, e a próxima recusa da mesma pessoa e ação grava o evento.

## Exatidão (NFR2.1 a NFR2.4)

### Sem ponto flutuante (NFR2.1)

Backend: a regra ArchUnit de ponto flutuante cobre o código de C5 e C6; valores REAL em `long` com `Math.*Exact`. `apps/web`: `bigint` só em `features/real/money.ts`; os lints de dinheiro continuam.

### A tela só formata (NFR2.2)

`PnlModeBlock`, `ProratedLines`, `BotPnlLines` e `ComparisonView` escrevem cada valor a partir do texto da API por `money-format.ts`. O total do detalhe é o da API, nunca a soma das linhas. Teste de componente por componente: cada valor mostrado é o texto da fixture formatado.

### Mesmo cálculo e soma exata (NFR2.3)

- **Lados de C6 iguais às linhas de C4.** O lado PAPER usa `PaperModeBlockReader.lineOf(person, bot, window)`, o mesmo método que monta a linha do bot no bloco PAPER. O lado REAL usa `botPnl`, que filtra a linha do bot da mesma passada de `modePnl`. Teste escrito antes do código (`PnlCompareEqualsByModeIT`): com um cenário de resultado conhecido e o mesmo período, total, realizado, não realizado e taxas do lado PAPER iguais à linha do bot no bloco PAPER de C4 por `compareTo == 0`, e os do lado REAL iguais em `long`.
- **Soma exata das páginas de C5 (a).** Teste escrito antes do código (`BotPnlLinesSumIT`): cenário com compras, vendas, uma liquidação, uma venda condicional e uma parcela vendida por outro bot, mais de 100 linhas; percorrer todas as páginas com `limit=50` até `nextCursor` nulo; a soma em `long` (`Math.addExact`) de `realizedLamports` e de `feesLamports` é igual ao realizado e às taxas da linha do bot no bloco REAL de C4 com `period=ALL`; diferença 0; o número de linhas lidas é igual ao número de linhas do bot no cenário. Isso funciona porque C5 e C4 saem da mesma passada. As páginas não são um instantâneo: a soma exata vale para uma leitura sem escrita entre as páginas, que é o cenário do teste (revisão R-04).
- **Estabilidade do cursor (b).** Teste escrito antes do código (`BotPnlLinesCursorStabilityIT`): ler a 1ª página, gravar uma ordem nova confirmada do mesmo bot, ler as páginas seguintes com o cursor recebido; 0 linhas repetidas (pela identidade `idempotencyKey` mais `origin`), 0 linhas perdidas, e a linha nova fora das páginas seguintes e presente numa 1ª página lida de novo. O desenho do cursor está em `performance-design.md`.
- **Limites do `RANGE`.** Teste escrito antes do código: um lançamento em `from` entra, um em `to` fica de fora, um 1 ms antes de `to` entra; o mesmo par de instantes dá o mesmo realizado em C4 e no lado de C6.

### Período em UTC (NFR2.4)

As funções de `period.ts` (`toUtcPeriod`, `startOfLocalDay`, `addDays`, `todayIn`) não mudam. O `PeriodSelector` calcula o par UTC na hora da aplicação e o guarda no reducer, fixo até a próxima aplicação; o relógio e o fuso são injetáveis (`now`, `timeZone`). Duas regras novas ficam no `PeriodSelector`, fora de `period.ts`: o intervalo que começa depois de hoje é recusado, e a duração acima de 366 dias em UTC é recusada pelo próprio `toUtcPeriod`, que já mede em milissegundos (o caso de 366 dias de calendário que ganham 1 hora no fim do horário de verão). Os casos de `period.test.ts`: "Hoje" em America/Sao_Paulo às 2026-11-23T02:30Z dá `from` 2026-11-22T03:00Z e `to` 2026-11-23T03:00Z; intervalo inclusivo; início futuro recusado; 366 dias em America/New_York atravessando o fim do horário de verão recusados e 365 aceitos; o par fixado igual depois da meia-noite local.

## Acessibilidade (NFR5.1 a NFR5.4)

| NFR | Desenho |
|---|---|
| NFR5.1 | controles nativos (rádios em `fieldset`, `select`, `button`, `input type="date"`); foco pelo `:focus-visible` que já existe; o foco programático usa `ref.focus()` depois do `*_LOADED` aplicado: rádio marcado do filtro na abertura de `/bots`, título do bloco relido, primeira linha nova (`tabIndex=-1`), select "Bot simulado" ao abrir a comparação, botão ao fechar, select do lado afetado depois de 404 ou 422 |
| NFR5.2 | só os tokens de cor já medidos (≥ 4,5:1 no tema escuro); ganho e perda com sinal e palavra; selos SIMULADO e REAL em texto; "desconhecido" em palavra |
| NFR5.3 | região do total com `aria-live="polite"` e `aria-atomic="true"`; o cartão guarda `lastAnnouncedAt` e só troca o texto anunciado quando o total mudou e passaram 30 s; 0 anúncios na carga inicial; os nomes acessíveis da lista de NFR5.3 |
| NFR5.4 | hierarquia de cabeçalhos por `headingLevel` no `PnlModeBlock`; `caption` e `th scope="col"` em cada tabela; `aria-expanded` e `aria-controls` nos botões de abrir e fechar |

## Telas (NFR6.1, NFR6.2)

- **360 px.** Toda tabela larga fica num `TableScroll` (`role="region"`, `aria-label`, `tabIndex=0`) a partir de 768 px; abaixo disso, linhas e parcelas viram cartões com rótulo em cada valor e alvos de 44 × 44 px. A troca é única, em 768 px, por media query CSS (sem medida de janela em JavaScript).
- **Quatro estados.** `BlockLoad` e os estados de linhas e de lado têm `loading`, `error`, `unknown` e `empty` distintos, cada um com texto próprio; carregando nunca mostra zero; "P&L indisponível agora" é diferente de "desconhecido". Uma releitura por evento mantém o valor anterior (sem voltar a carregando).

## Verificação (NFR7.1 a NFR7.8)

| NFR | Desenho |
|---|---|
| NFR7.1 | Test Strategy `Standard` do estado do intent; cada regra BR1.1 a BR12.1 com caminho feliz e pelo menos 2 casos de erro ou borda por componente |
| NFR7.2 | testes por risco: isolamento e 404 antes do 422 (`PnlCompareTenantIT`, `BotPnlLinesTenantIT`, `BotNotFoundTimingParityIT`); nunca somar os modos (contrato e propriedade "nenhum terceiro número"); `unknown` visível; REAL desligado por padrão; novo aceite nas 4 rotas; soma das páginas e estabilidade do cursor; auditoria com o limite por hora (`ReadRefusalAuditLimitIT`) |
| NFR7.3 | piso de 80% de linhas: JaCoCo no backend (unitários e integração mesclados), Jest no `apps/web`; `apps/admin` e `apps/txsigner` sem mudança; o piso nunca é reduzido |
| NFR7.4 | `GuestOpenApiContractTest` e teste de rota com os 3 casos de C4 (sem `market`, `PAPER`, `REAL`); `paper` e `real` opcionais no esquema; `check:api` do `apps/web` verde |
| NFR7.5 | os casos de NFR2.4 e mais: endereço com intervalo futuro aplica "Desde o início" sem pedido com `RANGE`; `?comparar=` depois da meia-noite usa o mesmo par dos blocos; em "Desde o início", 0 pedidos a `GET /api/v1/pnl` |
| NFR7.6 | o spec `e2e/zz-paper-real-unico` ganha os 10 casos de `frontend-components.md`, continua o último da suíte e não muda a conta do smoke antes dos specs anteriores; os casos montados usam `page.route` com fixtures sintéticas sem carteira nem mint real; `bash scripts/verify.sh` devolve 0 |
| NFR7.7 | antes do código (núcleo financeiro): a igualdade dos lados de C6 com C4, a soma exata das páginas de C5, a estabilidade do cursor, os limites do `RANGE` e a propriedade "nenhum terceiro número" de C6; depois de cada camada: telas, filtro `market`, contagens, auditoria, período na tela |
| NFR7.8 | no mesmo commit: `openapi-guest.yaml` (`market`, `paper` e `real` opcionais, C5 e C6, o 503 de C5), `schema.d.ts` regenerado, `by-mode.ts` aceitando a chave ausente e os testes de contrato dos dois lados |

## Sources

- `nfr-design-questions.md` desta pasta: Q1, Q3 e o resumo confirmado [Q1] [Q3].
- `construction/u3-pnl-views/nfr-requirements/reliability-requirements.md`: NFR4.6 a NFR4.13, NFR2.1 a NFR2.4, NFR5.1 a NFR5.4, NFR6.1, NFR6.2, NFR7.1 a NFR7.8.
- `construction/u3-pnl-views/functional-design/functional-spec.md` (máquinas de estado), `frontend-components.md`, `rules.md` (BR2.2 a BR2.6, BR3.2 a BR3.4, BR4.3, BR4.6, BR5.5, BR7.5, BR9.1 a BR9.3, BR10.1, BR10.2).
- `construction/u2-real-bot-pnl/nfr-design/reliability-design.md`: leitura sem efeito colateral, testes do núcleo financeiro antes do código.
- Código lido: `apps/api/.../pnl/modeview/ModePnlView.java`, `pnl/application/RealBotPnl.java`, `pnl/application/PaperModeBlockReader.java`; `apps/web/src/features/pnl/period.ts`, `PnlScreen.tsx`, `by-mode.ts`, `features/dashboard/concurrency.ts`, `dashboard-reducer.ts`, `lib/api/client.ts`.
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Ordering, Volume, Piso, testes obrigatórios por risco, Determinismo); `project.md`: spec `zz-` por último, `TableScroll` em 360 px, acessibilidade do beta.

## Assumptions & Open Questions

1. [assumption] A recusa do intervalo que começa depois de hoje fica no `PeriodSelector` e não em `period.ts`, para não mudar as funções que a U1 já testa.
2. [assumption] A releitura por evento mantém o valor anterior à vista até a resposta, como o painel já faz com o PAPER; só a carga inicial e o "Tentar de novo" mostram carregando.
