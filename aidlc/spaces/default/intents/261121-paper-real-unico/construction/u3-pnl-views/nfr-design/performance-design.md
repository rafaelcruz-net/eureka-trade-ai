# Desenho de desempenho — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23. Revisado depois de `reviews/review-01.md` e das decisões do fundador de 2026-11-23.

## Decisões do fundador (2026-11-23, depois da revisão 01)

- **F-1 (fila da auditoria).** A verificação `ownedByAnotherTenant` roda na thread do pedido nos dois 404; ver `security-design.md`.
- **F-3 (reducer novo de `/pnl`, R-06).** A resposta Q3 partiu de uma premissa errada: o `PnlScreen` não tem reducer, usa `useState`. O fundador aceitou a reescrita maior: o estado de `/pnl` vai para `features/pnl/pnl-screen-state.ts`, com `useReducer` e nenhuma biblioteca nova. O `PnlModeBlock.test.tsx` continua verde sem edição; os casos do `PnlScreen.test.tsx` da U1 que esperavam o formulário antigo e a tabela por token na abertura foram ajustados, um a um, ao "Desde o início" por padrão (BR2.1, BR2.9), sem apagar nenhum (conflito 2 do plano da Code Generation; alinhamento A4).

A U3 não cria cálculo novo. C4 com `market` ou com `RANGE`, C5 e C6 reutilizam a leitura em fluxo da U2 (`RealBotPnl`) e o cálculo PAPER da U1 (`PaperModeBlockReader`). Não há cache, tabela nova nem biblioteca nova [Q1] [Q3]. Este arquivo fixa onde cada leitura roda, o prazo de cada uma, a forma do cursor de C5, o comportamento da tela e a ampliação do cenário de carga `pnl-by-mode` [fundador D-NFR2].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| C4 com `market` | um único bloco enviado ao pool `pnl-mode-block`, com o prazo de 800 ms; a chave do outro bloco sai do JSON | NFR1.1, NFR4.2 |
| C4 com `RANGE` | os dois blocos com a janela `PnlWindow.Range`; PAPER pelo mesmo cálculo de `GET /api/v1/pnl`; REAL pela mesma passada da U2 com a janela | NFR1.1 |
| `listBots` | filtro `market` na consulta de página que já existe; `matchingCount` e `totalCount` por duas contagens na mesma transação só de leitura | NFR1.2 |
| C5 | 1 tarefa REAL no pool `pnl-mode-block` (mesmo prazo de 800 ms); a passada inteira da pessoa, as linhas do bot coletadas e a página cortada pelo cursor | NFR1.3, NFR1.11 |
| Cursor de C5 | âncora opaca de 44 caracteres: versão, `botId` e o resumo SHA-256 da identidade da linha; a ordem é a chave de ordem de rede decrescente | NFR1.3, NFR2.3 |
| C6 | os dois lados em paralelo no pool `pnl-mode-block`, cada um com o próprio prazo, pela `ModePnlView` | NFR1.4, NFR4.9 |
| Auditoria das recusas | a verificação de outro tenant roda na thread do pedido nos dois 404 (1 consulta pela chave primária); só o caso positivo vai a um executor próprio, sem espera; a resposta nunca aguarda a gravação | NFR1.10, NFR3.14 |
| Repetição do cliente | C5 e a tabela por token (`GET /api/v1/pnl`) com `retry: false`: um 503 vira erro na hora, sem nova tentativa | NFR1.8 |
| Tela | temporizador de 30 s por cartão (1 imediato e 1 pendente); `AbortController` por pedido; "Tentar de novo" com `market` | NFR1.7, NFR1.8, NFR1.9 |
| Medida | `*IT` no método da U1 (11 casos, p95 só registrado) e o cenário `pnl-by-mode` ampliado com 7 leituras novas e portão de 1 s | NFR1.5, NFR1.6, NFR1.16 |

## Onde cada leitura roda

| Leitura | Thread que lê | Conexões do Hikari principal | Prazo |
|---|---|---|---|
| C4 sem `market` (`ALL` ou `RANGE`) | 2 tarefas do pool `pnl-mode-block` | até 2, uma por bloco | 800 ms por bloco, contados do envio (`memetrade.pnl.by-mode.block-timeout`) |
| C4 com `market` | 1 tarefa do pool | 1 | 800 ms |
| `listBots` | a thread do pedido | 1, transação só de leitura | sem prazo novo (a consulta conta no máximo os bots da pessoa) |
| C5 | 1 tarefa do pool (`PnlMarket.REAL`) | 1 | 800 ms |
| C6 | 2 tarefas do pool | até 2 | 800 ms por lado |
| Verificação "existe em outro tenant" (só nos 404) | a thread do pedido | 1 do principal, na mesma transação curta do 404, pela chave primária | sem prazo novo |
| Gravação da auditoria (só casos positivos e 422) | o executor `pnl-read-audit` (1 thread) | 1 do pool `pnl-mismatch-audit`, nenhuma do principal | 200 ms da tarefa, contados do envio |

A thread do pedido nunca segura conexão enquanto espera o pool. C5 passa pelo pool `pnl-mode-block` como o `GET /api/v1/real/pnl` da U2: assim o pool continua sendo a antepara de todas as leituras REAL (no máximo 8 ao mesmo tempo).

## C4 com `market` e com `RANGE` (NFR1.1)

- **Janela.** O controlador converte `period`, `from` e `to` em `PnlWindow`. `ALL` é `PnlWindow.all()`. `RANGE` exige `from` e `to` em ISO 8601 UTC, `from < to` e no máximo 366 dias de duração em UTC; fora disso, 400 `VALIDATION_FAILED` antes de qualquer leitura. A trava da U1 ("na U1, só `ALL`") sai.
- **`ModePnlView` ganha a janela e o modo único.** `statement(window, paper, real, sink)` envia os dois blocos como hoje. `single(market, window, block, sink)` envia um só. O `sink` é quem conta um bloco `UNAVAILABLE`: C4 passa `PnlModeMetrics::unavailable`, C6 passa `PnlCompareMetrics::sideUnavailable` (ver `observability-design.md`). A view continua genérica e nunca toca num valor (NEVER_SUM_MODES).
- **Bloco PAPER com `RANGE`.** O `PaperModeBlockReader` aceita `Range`: o realizado e as taxas do intervalo `[from, to)` e o não realizado de agora, pelo mesmo `pnl.reportSince(person, from, to, bot)` de `GET /api/v1/pnl`. Cada linha de bot sai de um método `lineOf(person, bot, window)`, que C6 também usa (NFR2.3).
- **Bloco REAL com `RANGE`.** A passada da U2 já recebe `PnlWindow`. Ela continua lendo a história inteira em ordem de rede, porque o custo de abertura depende do que veio antes da janela; o filtro é só sobre o que entra no realizado.
- **Resposta parcial.** O DTO de C4 tem `paper` e `real` como opcionais, serializados com `@JsonInclude(NON_ABSENT)`: com `market=PAPER` a chave `real` não aparece no JSON, e nunca vale `null`.

## `listBots` com `market` e contagens (NFR1.2)

- O parâmetro `market` (só `PAPER` ou `REAL`, senão 400) entra como mais um filtro da consulta paginada que já existe (`tenant_id`, `owner_user_id`, `state`, ordem de criação e cursor da página).
- `totalCount`: `count(*)` dos bots da pessoa, sem `state` nem `market`. `matchingCount`: `count(*)` com os mesmos filtros da página. As duas contagens e a página rodam na mesma transação só de leitura (`READ COMMITTED` basta: a tela só mostra "N de M"). Com no máximo 10 bots por pessoa (NFR1.12), as contagens não crescem com o histórico de ordens.

## C5 — linhas do bot pela passada da U2 (NFR1.3)

### Leitura

1. Validação sem banco (400): `botId` uuid, `limit` de 1 a 100 (padrão 50), `cursor` com a forma abaixo e com o mesmo `botId` do caminho.
2. `directory.botOf(tenant, pessoa, botId)` filtrado por `REAL`. Vazio → 404 pelo caminho comum dos 404 (`security-design.md`).
3. 1 tarefa no pool `pnl-mode-block` chama `RealBotPnlQuery.botLines(tenant, pessoa, botId, cursor, limit)`. A implementação faz a mesma passada da pessoa que C4 faz (a atribuição, a leitura em fluxo do ledger em ordem de rede, o rateio, a conferência) e passa ao `RealPnlEngine` um coletor de linhas do bot pedido. Nenhum cálculo novo: as linhas são o que a passada já produz para aquele bot, inclusive as parcelas rateadas de outro bot, da liquidação e da condicional (C10).
4. As linhas do bot ficam numa lista na ordem crescente de rede. A página é lida do fim para o começo (mais recente primeiro, BR4.2).

O custo de uma página é o custo de 1 bloco REAL. O pior caso de memória é a lista das linhas de 1 bot: na referência de 2.920 ordens, cerca de 3 mil itens pequenos, menos de 1 MB por pedido.

### Ordem e identidade da linha

- **Ordem.** A chave de uma linha é a `NetworkOrderKey` da primeira linha do ledger do evento que gera a linha (a menor chave entre as linhas daquele evento), com desempate pela ordem do enum `origin` (`BOT`, `LIQUIDATION`, `CONDITIONAL`, `SOLD_BY_OTHER_BOT`). A `NetworkOrderKey` é uma ordem total (slot efetivo, gravado antes de herdado, assinatura por bytes, `createdAt`, `reference`, `kindRank`, `idempotencyKey`), igual ao SQL de `streamInNetworkOrder`.
- **Identidade.** A identidade de uma linha é `idempotencyKey` da linha âncora do ledger mais `origin`. A `idempotencyKey` é única, feita só de identificadores de negócio, e o ledger é só de inserção, então a identidade de uma linha nunca muda nem some.

### Forma do cursor

```text
cursor = base64url_sem_preenchimento( 0x01 || botId[16 bytes] || SHA-256("pnl-line:" + idempotencyKey + "|" + origin)[0..16) )
       = 33 bytes = 44 caracteres
```

- **Opaco e sem dado sensível.** O cursor não carrega assinatura de transação, slot, mint nem endereço. Uma assinatura completa na URL de um pedido iria para logs de acesso e leva, por um explorador público, à carteira; por isso a âncora é um resumo.
- **Validação estrita (400 `VALIDATION_FAILED`, campo `cursor`).** Comprimento diferente de 44, caractere fora do alfabeto base64url, versão diferente de `0x01` ou `botId` diferente do caminho. Tudo isso é conferido antes de qualquer leitura.
- **Busca da âncora.** Na lista da passada, a página começa na linha seguinte (mais antiga) à linha cujo resumo é igual ao do cursor. A busca é linear sobre as linhas do bot, já em memória. Uma âncora não encontrada responde 400 `VALIDATION_FAILED` com o campo `cursor`. Isso pode acontecer com um cursor que o próprio servidor deu: as linhas derivadas do rateio (parcela vendida por outro bot, liquidação, condicional) podem mudar ou sumir entre duas páginas quando uma confirmação atrasada entra com slot mais antigo (revisão R-04).
- **"Recarregar do início" (R-04).** Um 400 de C5 com o campo `cursor` na página seguinte não oferece "Tentar de novo", que repetiria o mesmo cursor. O `BotPnlLines` vai para a fase `cursorStale` e mostra "A lista mudou desde a última página." com o botão "Recarregar do início", que pede a 1ª página sem cursor e troca todos os itens. O teste de componente confere que esse botão pede C5 sem `cursor` e que nenhum pedido repete o cursor recusado.
- **Páginas não são um instantâneo.** Cada página é uma passada nova. Com uma escrita entre duas páginas, os valores das linhas mais antigas podem vir de estados diferentes do ledger (o custo médio muda com uma linha inserida no meio). A soma exata de NFR2.3 vale para uma leitura de todas as páginas sem escrita no meio, que é o cenário do teste; a estabilidade do cursor (NFR2.3 b) fala de linhas repetidas e perdidas, não de valores.
- **`nextCursor`.** É o cursor da última linha devolvida quando ainda há linhas mais antigas, ou `null`.

### Estabilidade (NFR2.3 b)

- Uma ordem nova confirmada tem slot maior que as linhas já lidas e entra no topo. A página seguinte começa depois da âncora, então a linha nova não aparece nela e não desloca nada: 0 linhas repetidas e 0 perdidas. Uma 1ª página lida de novo mostra a linha nova.
- Uma linha que entra no meio da história (uma confirmação atrasada com slot mais antigo que a âncora) aparece nas páginas seguintes, uma vez só. A âncora é encontrada pela identidade, que não depende da posição.
- Sem segredo nem HMAC: o cursor só escolhe uma posição dentro das linhas da própria pessoa, e o dono do bot é conferido em todo pedido. Alternativas rejeitadas: deslocamento numérico (instável quando entra linha no meio); chave completa em texto (expõe a assinatura); cursor cifrado (exigiria chave nova e configuração nova, contra NFR8.1).

### Falha

Uma falha da passada (prazo, fila cheia, `TOO_MANY_ORDERS`, divergência na conferência) responde 503 `SERVICE_UNAVAILABLE` (RFC 7807, com `correlationId`), com o log `WARN` de NFR3.10. A divergência segue o portão da U2: o evento é gravado depois que a transação do bloco terminou (`AfterBlockRelease`).

O cliente nunca repete esse 503 sozinho (revisão R-02): a chamada de C5 usa `retry: false` (ver "Cliente da API"). Sem isso, 1 clique faria até 3 passadas REAL justamente com o pool cheio, e `TOO_MANY_ORDERS` e a divergência seriam repetidos sem chance de sucesso. A nova tentativa é só a da pessoa, pelo "Tentar de novo".

## C6 — dois lados em paralelo (NFR1.4)

1. Validação sem banco (400): os dois ids uuid e o período, pela mesma conversão de C4.
2. As duas consultas de posse filtradas pelo tenant do token, sempre as duas (`botOf(paperBotId)` e `botOf(realBotId)`), sem atalho: o tempo é o mesmo qualquer que seja o lado recusado (NFR3.17). 404 com `reason` `PAPER_BOT` se o lado PAPER não é bot PAPER da pessoa; senão 404 `REAL_BOT` se o lado REAL não é bot REAL da pessoa; senão 422 `TEMPLATE_MISMATCH` se os `templateId` diferem.
3. `ModePnlView.statement(window, paperSide, realSide, compareSink)`: as 2 tarefas vão ao pool ao mesmo tempo, cada uma com o prazo de 800 ms contado do envio. O pior caso de um pedido é 1 prazo, nunca a soma.
   - lado PAPER: `PaperModeBlockReader.lineOf(person, bot, window)`, na transação `REPEATABLE READ` do bloco;
   - lado REAL: `RealBotPnlQuery.botPnl(tenant, pessoa, botId, window)`, que faz a passada inteira da pessoa e devolve só a linha do bot (o rateio depende de todos os titulares).
4. Um lado que falha, passa do prazo ou é recusado pela fila cheia vira `UNAVAILABLE` com `code` e `correlationId`; o outro lado segue. A resposta é sempre 200 nesse caso.

## Auditoria fora do caminho da resposta (NFR1.10, NFR3.14)

Nos dois 404, a thread do pedido faz a mesma consulta `ownedByAnotherTenant` pela chave primária (F-1). Só um resultado positivo, ou um 422, passa pelo filtro em memória e entrega uma tarefa ao executor `pnl-read-audit` (`ThreadPoolExecutor.execute`, que nunca bloqueia: com a fila cheia ele recusa na hora); a resposta sai em seguida. A gravação acontece depois, fora da transação de leitura. O desenho completo, os tamanhos e o teste de paridade de tempo estão em `security-design.md`.

## Nenhuma trava (NFR1.10)

0 `SELECT ... FOR UPDATE` e 0 travas consultivas em C4, `listBots`, C5 e C6. A única trava é a consultiva da gravação da auditoria, no pool `pnl-mismatch-audit`, que não é o pool principal. O `PnlReadsDoNotBlockCycleIT` roda C5 e C6 em laço, em 4 threads, durante um ciclo de bot e confere que o ciclo termina dentro da trava de 2 s que já existe.

## Tamanhos fixos (NFR1.11)

| Leitura | Tamanho |
|---|---|
| C5 | `limit` padrão 50, de 1 a 100; a tela pede sempre 50 |
| Parcelas rateadas em C4 | até 10 por bot (vem da U2) |
| `listBots` | paginação que já existe, sem mudança |
| C6 | só os 2 bots pedidos |

## Tela (NFR1.7, NFR1.8, NFR1.9)

### Cliente da API

O cliente (`apps/web/src/lib/api/client.ts`) ganha a opção `signal?: AbortSignal`:

- a tentativa liga o sinal externo ao `AbortController` do tempo limite de 10 s (ouvinte `abort` com `once`, removido no `finally`);
- se o sinal externo foi abortado, o cliente lança `RequestCancelled`, **sem** repetir e **sem** esperar o recuo. Hoje um `AbortError` é tratado como tempo esgotado e repetido; o cancelamento pedido pela tela não pode cair nessa regra;
- `RequestOptions` ganha `retry?: boolean` (padrão `true`, que mantém o comportamento de hoje). Com `retry: false`, um 503 ou um tempo esgotado viram erro na primeira tentativa. Usam `retry: false`: `listBotPnlLines` (C5) e `getPnl` (a tabela por token de `/pnl`). Teste do cliente: com o `fetch` simulado respondendo 503, cada uma dessas duas chamadas faz exatamente 1 `fetch` e lança o erro com o `correlationId`; `getPnlByMode` continua repetindo como hoje;
- as funções de `guest-api.ts` usadas pela U3 (`getPnlByMode({ market?, period })`, `listBots({ state?, market?, cursor?, limit? })`, `listBotPnlLines(botId, { cursor?, limit })`, `comparePnl({ paperBotId, realBotId, period })`, `getPnl(period)`) recebem `{ signal }`;
- toda tela ignora `RequestCancelled`: nenhum estado de erro, nenhum anúncio.

### Pedidos por troca

Cada pedido da tela tem uma "vaga" com o próprio `AbortController` (guardado num `useRef`) e um `requestId` crescente. Abrir um pedido numa vaga aborta o pedido anterior da mesma vaga. O reducer também descarta um resultado com `requestId` antigo, como defesa contra uma resposta que chegou antes do cancelamento (NFR4.11).

| Tela | Vagas | Troca e efeito |
|---|---|---|
| `/bots` | `list`, `byMode` | trocar o filtro: aborta as duas; 1 `listBots` com `market` e 1 C4 com o mesmo `market` (ou sem ele em Todos) |
| `/pnl` | `byMode`, `retry:PAPER`, `retry:REAL`, `compare`, `tokens` | trocar o período: aborta todas; 1 C4 sem `market`; mais 1 C6 se a comparação está aberta; mais 1 `GET /api/v1/pnl` se o período é concreto |
| `/pnl`, painel, `/bots` | `retry:<market>` | "Tentar de novo" de um bloco: 1 C4 com o `market` do bloco e o par UTC em vigor; o outro bloco não volta a carregar |
| comparação | `compare` | "Tentar de novo" de um lado: 1 C6; o reducer aplica o resultado só ao lado que estava em erro |
| detalhe do bot REAL | `bot`, `byMode`, `lines` | abrir: 1 `GET /api/v1/bots/{id}`, 1 C4 com o `market` do bot e `period=ALL`, 1 C5 com `limit=50`; "Carregar mais": 1 C5 com o cursor |

Sair da tela aborta todas as vagas.

### Estado de `/pnl`

Hoje o `PnlScreen` guarda o estado em vários `useState`. A U3 leva esse estado para um reducer puro, `features/pnl/pnl-screen-state.ts`, com `useReducer` (nenhuma biblioteca nova), como o fundador aceitou (F-3). Os testes da U1 de `/pnl` ficam verdes; os casos do `PnlScreen.test.tsx` presos ao formulário antigo foram ajustados ao comportamento novo, sem apagar nenhum (conflito 2 do plano). O reducer guarda os dois `BlockLoad`, o período aplicado (com o par UTC fixo), a comparação com os dois lados e a tabela por token. Cada `*_LOADED` traz o `requestId` e é ignorado se não for o da vaga.

### Temporizador de 30 s por cartão do painel

Um utilitário puro em `features/dashboard/concurrency.ts`, ao lado do `singleFlight` que já existe, com o relógio e o temporizador injetáveis:

```text
windowedRefresh(run, { windowMs: 30_000, setTimer, clearTimer })
  estado: open = false, pending = false, timer = nenhum
  trigger():
    se open: pending = true; fim
    open = true; run(); timer = setTimer(closeWindow, windowMs)
  closeWindow():
    se pending: pending = false; run(); timer = setTimer(closeWindow, windowMs)
    senão: open = false
  dispose(): clearTimer(timer); open = false; pending = false
```

- Um por cartão: `PAPER` e `REAL`. Cada `run` abre a vaga `card:<market>` (aborta a releitura anterior daquele cartão) e pede C4 com o `market` do cartão e `period=ALL`.
- Em qualquer intervalo de 30 s, no máximo 2 pedidos; com eventos contínuos, 1 a cada 30 s. Nenhum evento se perde: o que chega dentro da janela vira a releitura do fim dela (NFR4.12).
- Eventos (BR7.5): `pnl.updated` dispara o cartão PAPER; `real.order.state` com `CONFIRMED` ou `FAILED` e `real.conditional.state` disparam o cartão REAL; os demais não disparam. O `DashboardProvider` passa a assinar `real.order.state` e `real.conditional.state`, que o canal ao vivo já envia; o canal não muda.
- A releitura por evento não volta o cartão para carregando: o valor anterior continua até a resposta (como o `PNL_REQUESTED` com `showLoading: false` de hoje). Só a carga inicial e o "Tentar de novo" mostram carregando.
- "Tentar de novo" de um cartão é ação da pessoa: roda na hora, aborta a releitura em curso daquele cartão e não abre nem fecha a janela.
- O estado dos cartões fica no `dashboard-reducer.ts` que já existe: fatia `modeCards` com `PAPER` e `REAL`, cada um com `load`, `requestId`, `lastAnnouncedAt` e `realUnavailable`; ações `MODE_CARDS_REQUESTED`, `MODE_CARD_REQUESTED`, `MODE_CARD_LOADED` e `MODE_CARD_FAILED`.
- O `dispose` roda ao desmontar o painel.

### Custo do detalhe do bot (NFR1.9)

O detalhe lê o bloco inteiro de C4 do modo do bot para achar a linha (custo aceito, BR4.6), dentro do prazo de 800 ms do bloco. Não há rota nova.

## Medida (NFR1.5, NFR1.16)

`PnlViewsLatencyIT`, no padrão de `AbstractU9MeasurementIT` e do `DashboardOpenLatencyIT`: a semente da U1 (1 pessoa, 10 bots, 730 dias, 2.920 ordens por bot), 10 pedidos de aquecimento descartados e 100 medidos em sequência por caso, p95 pelo posto mais próximo. Os 11 casos:

| Rota | Casos |
|---|---|
| C4 | `market=PAPER` e `market=REAL`, cada um com `ALL` e com `RANGE` de 30 dias (4) |
| `listBots` | `market=PAPER`, `market=REAL` e sem `market` (3) |
| C5 | 1ª página e página seguinte (2) |
| C6 | `ALL` e `RANGE` de 30 dias (2) |

O teste escreve no log o p95 de cada caso ao lado da meta de 1.000 ms. Um p95 acima da meta não falha o build; um pedido medido que não responde 200 falha. O SLI é o histograma `http_server_requests_seconds` por `uri`, que já existe (`observability-design.md`).

## Portão de carga: cenário `pnl-by-mode` ampliado (NFR1.6, D-NFR2)

### O que a leitura nova custa

Cada uma das 9 leituras do ciclo faz exatamente 1 passada REAL da pessoa (C4 e C6 também fazem uma leitura PAPER). O ciclo da U2 tinha 2 leituras e 2 passadas REAL. Com o mesmo ritmo (1 leitura a cada 2 s por pessoa), o número de passadas REAL por segundo não muda: o cenário continua a cerca de 10 leituras por segundo.

### Arquivos que mudam na Code Generation da U3

| Arquivo | Mudança |
|---|---|
| `scripts/load/pnl-by-mode.mjs` | `PNL_LIMITS.readP95Seconds = 1.0`; `CYCLE` com as 9 leituras rotuladas (`byModeAll`, `realPnl`, `byModeRange30`, `byModeRange366`, `linesFirst`, `linesNext`, `compareAll`, `compareRange30`, `compareRange366`) e `NEW_READS` com as 7 novas; `requestOf(label, person, now)` monta a rota e a consulta (`RANGE` de 30 dias e de 366 dias terminando agora, em UTC, em segundos inteiros); `pnlByModeTraffic` mede o tempo de cada leitura no cliente (`io.now()` antes e depois), só depois do aquecimento, guarda as amostras por rótulo e encadeia `linesNext` no `nextCursor` da última `linesFirst` da mesma pessoa; `nearestRankP95(amostras)`; `pnlByModeFailures` acrescenta, para cada rótulo de `NEW_READS`, `p95 <rótulo>` acima de 1 s, `no read of <rótulo>` sem amostra, e `missing nextCursor` quando a 1ª página não traz cursor. As respostas diferentes de 200 contam nas 9 leituras. Os portões da U2 continuam iguais. |
| `scripts/load/load-test.mjs` | `runPnlByModeScenario`: antes do aquecimento, para cada pessoa, `GET /api/v1/bots?market=REAL` e `?market=PAPER` escolhem o bot REAL de C5 e um par PAPER e REAL de mesmo `templateId` para C6 (falha na hora se faltar); o relatório ganha `reads` com `count`, p50, p95 e p99 de cada rótulo e os lados `UNAVAILABLE` de C6 por causa (`pnl_compare_side_unavailable_total`, antes e depois); grava `load-test-report.json`, o `docs/perf/u2-real-bot-pnl-<data>.md` de hoje e o novo `docs/perf/u3-pnl-views-<data>.md` (função `pnlViewsMarkdown`). |
| `scripts/load/load-test-pnl-by-mode.test.mjs` | casos novos com `io` e relógio falsos: o ciclo de 9 leituras, o encadeamento do cursor, o p95 pelo posto mais próximo, cada portão novo reprovando sozinho e os portões da U2 sem mudança. |
| `apps/api/src/main/java/com/memetrade/pnl/loadtest/LoadtestPnlSeed.java` | sem mudança: todos os bots da semente usam o `templateId` `MOMENTUM_VOLUME`, então cada pessoa já tem pares para C6. O `load-test.mjs` confere isso no começo. |
| `scripts/load-test.sh` | sem mudança: o cenário `pnl-by-mode` já existe. |

### O portão da U2 e o histograma

O portão da U2 de C4 (p95 ≤ 1 s pelo histograma `http_server_requests_seconds{uri="/api/v1/pnl/by-mode"}`) continua. O histograma agora mistura `ALL` e `RANGE`; como as 3 leituras de C4 têm a mesma meta, o portão fica no mínimo tão exigente quanto antes. Os p95 por período vêm dos rótulos medidos no cliente, porque o histograma por `uri` não separa os períodos. A conta de blocos REAL `UNAVAILABLE` das respostas contra o contador continua, somando as 3 leituras de C4.

### Texto pronto do `load-test.yml`

O cenário segue os números do NFR1.6 aprovado: 9 leituras por ciclo (as 2 da U2 e 7 novas) e 7 portões novos de p95 ≤ 1 s, um por leitura nova, além dos portões da U2. A nota de alinhamento do NFR1.6 está em `logical-components.md`.

A Code Generation da U3 escreve no próprio `code-summary.md` o texto pronto inteiro do `.github/workflows/load-test.yml`, a partir do texto da U2, com uma única mudança: o parágrafo do topo sobre `pnl-by-mode` ganha a frase "Since 261121-paper-real-unico U3 (NFR1.6): it also reads C4 with RANGE of 30 and 366 days, the first and the next page of C5 and C6 with ALL, RANGE of 30 days and RANGE of 366 days, and fails when the p95 of any of these 7 reads, timed by the client, exceeds 1 s." A lista de opções, a pendência `real` do 261113 e `timeout-minutes: 90` ficam iguais. A Code Generation da U3 edita o `code-summary.md` da U2 **só** para acrescentar uma linha de ponteiro no fim da seção do texto pronto: "Substituído pelo texto pronto de `construction/u3-pnl-views/code-generation/code-summary.md`". Nada mais muda naquele arquivo. O responsável aplica só o texto da U3. O arquivo do workflow continua do responsável (`team.md`).

### Fechamento

Como na U2: o Build and Test da U3 só fecha com 1 execução verde do `pnl-by-mode` ampliado no SHA candidato, disparada pelo responsável depois de aplicar o texto pronto (`gh workflow run load-test -f scenario=pnl-by-mode`). A repetição na devnet fica com o responsável, nos estágios de operação.

## Sources

- `nfr-design-questions.md` desta pasta: Q1, Q2, Q3 e o resumo confirmado [Q1] [Q2] [Q3].
- `reviews/review-01.md` (R-02, R-04, R-06, R-07) e as decisões do fundador de 2026-11-23 (F-1, F-3).
- `construction/u3-pnl-views/nfr-requirements/performance-requirements.md`: NFR1.1 a NFR1.11, decisão D-NFR2.
- `construction/u3-pnl-views/nfr-requirements/observability-requirements.md`: NFR1.16.
- `construction/u3-pnl-views/functional-design/functional-spec.md` (F1 a F6), `frontend-components.md` ("Integração com a API"), `rules.md` (BR2.6, BR3.1, BR3.2, BR4.2, BR4.3, BR4.6, BR5.2 a BR5.5, BR7.5).
- `inception/contract-design/contract-summary.md`: C4, C5 (cursor opaco, `limit` de 1 a 100), C6, C10 (`botLines`).
- `construction/u1-mode-skeleton/nfr-design/performance-design.md` e `scalability-design.md`: pool `pnl-mode-block` (8 threads, fila de 16, prazo contado do envio).
- `construction/u2-real-bot-pnl/nfr-design/performance-design.md` e `scalability-design.md`: leitura em fluxo, `GET /api/v1/real/pnl` pelo pool, cenário `pnl-by-mode`.
- `construction/u2-real-bot-pnl/code-generation/code-summary.md`: texto pronto do `load-test.yml`.
- Código lido: `apps/api/.../ledger/api/NetworkOrderKey.java`, `pnl/application/RealBotPnl.java`, `pnl/application/PaperModeBlockReader.java`, `pnl/modeview/ModeBlockExecutor.java`, `pnl/modeview/ModePnlView.java`, `pnl/web/PnlController.java`, `bots/web/BotController.java` (`list`), `pnl/loadtest/LoadtestPnlSeed.java`; `apps/web/src/lib/api/client.ts`, `features/pnl/PnlScreen.tsx`, `features/pnl/period.ts`, `features/pnl/by-mode.ts`, `features/dashboard/concurrency.ts`, `dashboard-reducer.ts`, `DashboardProvider.tsx`; `scripts/load/pnl-by-mode.mjs`, `scripts/load/load-test.mjs`.
- `aidlc/spaces/default/memory/team.md`: Testing Posture (Desempenho, Determinismo), Way of Working (workflows do responsável).

## Assumptions & Open Questions

1. [assumption] A chave de ordem de uma linha de C5 é a da primeira linha do ledger do evento; o desempate por `origin` só importa se um mesmo evento gerar duas linhas para o mesmo bot, o que o cálculo da U2 não faz hoje.
2. [assumption] Uma âncora não encontrada responde 400 com o campo `cursor`, que é a resposta do contrato para cursor inválido. Isso acontece com um cursor de outra versão do esquema e também com um cursor que o próprio servidor deu, quando uma linha derivada do rateio mudou entre duas páginas; a tela oferece "Recarregar do início" nesse caso (R-04).
3. [assumption] O 503 de C5 (falha técnica da passada) não está listado no contrato C5, que só lista 200, 400 e 404. Ele segue a regra de erros do `team.md` (erros técnicos voltam como 503). A Code Generation acrescenta o 503 ao `openapi-guest.yaml` de C5 no mesmo commit do leitor do `apps/web` (NFR7.8).
4. [assumption] Os lados `UNAVAILABLE` de C6 no cenário de carga ficam só no relatório, sem portão próprio: NFR1.6 pede o p95 e 0 respostas diferentes de 200, e um lado `UNAVAILABLE` responde 200.
5. A resposta Q3 falava do "reducer que já existe na tela de P&L"; a premissa estava errada (o `PnlScreen` usa `useState`). O fundador aceitou a reescrita maior (F-3): o reducer puro `pnl-screen-state.ts` com `useReducer`, sem biblioteca nova. Os cartões do painel usam o `dashboard-reducer.ts` que já existe.
