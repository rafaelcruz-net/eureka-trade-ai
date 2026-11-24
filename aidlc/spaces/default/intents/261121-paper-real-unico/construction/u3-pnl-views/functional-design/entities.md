# Entidades — U3 `u3-pnl-views`

Conversa: português (Brasil). Data: 2026-11-23.

A U3 é uma Unit de interface (`kind: ui`). Ela não cria entidade gravada: tudo aqui é **modelo de tela** (estado e valores que a tela guarda) ou **forma de API** (o que a tela pede e recebe de C4, C5, C6 e da lista de bots). Nenhum valor de dinheiro é calculado na tela; os totais chegam prontos do servidor (U1, U2), e a tela só os escreve. O bloco `yaml` abaixo é a fonte de verdade; o diagrama em `functional-spec.md` é derivado dele.

Convenções do bloco:
- `type` é o tipo lógico: `uuid`, `text`, `boolean`, `integer` (contagem, nunca dinheiro), `instant` (UTC, ISO 8601), `day` (dia de calendário `AAAA-MM-DD` no fuso da pessoa), `lamports` (texto inteiro com sinal), `base_units` (texto inteiro sem sinal), `usd` (texto decimal exato), `enum`, `list<…>`, `ref<…>`, `union<…>`.
- `kind: view_model` é estado de tela; `kind: api_shape` é a forma da requisição ou da resposta, cujos tipos o `apps/web` gera de `openapi-guest.yaml` (nunca à mão, `team.md`).
- `change: existing | additive | new` diz se a forma já existe, ganha um acréscimo nesta Unit ou nasce nesta Unit.
- Valores de enum em inglês, iguais no contrato, no código e na URL quando aparecem nela.

```yaml
entities:
  # ---------------- Filtro da lista de bots (US2.3) ----------------
  - name: ModeFilter
    id: ENT-301
    kind: view_model
    change: new
    description: >
      O filtro por modo da lista /bots. Vive no endereço (?mode=PAPER ou ?mode=REAL); sem o parâmetro,
      vale ALL. Ele escolhe o que a tela pede ao servidor; a tela nunca filtra a lista sozinha [Q2].
    attributes:
      - name: value
        type: enum
        required: true
        allowed: [ALL, PAPER, REAL]
        default: ALL
        constraints: "lido de ?mode; valor ausente ou desconhecido vira ALL (BR1.7)"
      - { name: urlParam, type: text, required: true, default: "mode", constraints: "ALL não escreve o parâmetro" }
    entity_constraints:
      - "combina com o filtro de estado que já existe (BotState); os dois vão ao servidor juntos (BR1.5)"
    relationships:
      - { target: BotListQuery, cardinality: "1:1", direction: "ModeFilter -> BotListQuery", note: "vira o parâmetro market" }

  - name: BotListQuery
    id: ENT-302
    kind: api_shape
    change: additive
    description: >
      A requisição de GET /api/v1/bots (listBots). Ganha o parâmetro opcional market, aditivo e só de
      requisição; sem ele, a rota responde como hoje.
    attributes:
      - { name: state, type: enum, required: false, allowed: [DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING, STOPPED], constraints: "existente" }
      - name: market
        type: enum
        required: false
        allowed: [PAPER, REAL]
        constraints: "novo (U3); ausente = os dois modos; outro valor = 400 VALIDATION_FAILED (BR1.2)"
      - { name: cursor, type: text, required: false, constraints: "existente, opaco" }
      - { name: limit, type: integer, required: false, default: 50, min: 1, max: 100, constraints: "existente" }
    entity_constraints:
      - "o tenant e a pessoa vêm do token, nunca da consulta (BR9.1)"

  - name: BotPageCounts
    id: ENT-303
    kind: api_shape
    change: additive
    description: >
      Dois campos aditivos em BotPage (resposta de listBots), só contagens de bots, nunca dinheiro.
      Servem à linha "Mostrando N de M bots." e a separar a lista vazia pelo filtro da pessoa sem
      nenhum bot (AC2.3.4) sem uma segunda leitura.
    attributes:
      - { name: matchingCount, type: integer, required: true, min: 0, constraints: "bots da pessoa que passam por todos os filtros pedidos (state e market), em todas as páginas" }
      - { name: totalCount, type: integer, required: true, min: 0, constraints: "todos os bots da pessoa, sem filtro; 0 = a pessoa não tem bots" }
    entity_constraints:
      - "matchingCount <= totalCount"
      - "campos de contagem de bots; nenhum campo de valor monetário entra em BotPage por esta Unit (BR6.2)"
    relationships:
      - { target: BotSummary, cardinality: "1:N", direction: "BotPage -> BotSummary", note: "items existentes, com market e templateId" }

  # ---------------- Período (US7.1, US7.2) ----------------
  - name: PeriodChoice
    id: ENT-304
    kind: view_model
    change: new
    description: >
      O período escolhido em dias de calendário no fuso da pessoa. Vive no endereço de /pnl
      (?periodo=hoje | 7d | 30d | intervalo&de=AAAA-MM-DD&ate=AAAA-MM-DD); sem parâmetro vale ALL.
      O par UTC é calculado quando a opção é aplicada e fica fixo até a próxima aplicação (BR2.2).
    attributes:
      - name: kind
        type: enum
        required: true
        allowed: [ALL, TODAY, LAST_7, LAST_30, RANGE]
        default: ALL
      - { name: fromDay, type: day, required: false, constraints: "só em RANGE; dia inicial incluído" }
      - { name: toDay, type: day, required: false, constraints: "só em RANGE; dia final incluído" }
      - { name: timeZone, type: text, required: true, default: "fuso do navegador", constraints: "identificador IANA, por exemplo America/Sao_Paulo" }
    entity_constraints:
      - "RANGE: fromDay <= toDay e no máximo 366 dias entre o início de fromDay e o início do dia seguinte a toDay (BR2.3)"
      - "TODAY = RANGE(hoje, hoje); LAST_7 = RANGE(hoje - 6, hoje); LAST_30 = RANGE(hoje - 29, hoje), com hoje no fuso da pessoa (BR2.2)"
      - "RANGE: fromDay <= hoje; um intervalo inteiro no futuro é recusado (BR2.3)"
      - "o limite de 366 dias é a duração UTC entre from e to; 366 dias de calendário que ganham uma hora no fim do horário de verão são recusados (BR2.3)"
      - "com ALL, a tabela PAPER por token não é lida e a tela mostra a nota (BR2.9)"
    relationships:
      - { target: UtcPeriod, cardinality: "1:0..1", direction: "PeriodChoice -> UtcPeriod", note: "ALL não tem UtcPeriod" }

  - name: UtcPeriod
    id: ENT-305
    kind: view_model
    change: existing
    description: >
      O par de instantes UTC que a API recebe, [from, to). É o tipo que period.ts já produz
      (toUtcPeriod, startOfLocalDay, addDays, todayIn).
    attributes:
      - { name: from, type: instant, required: true, constraints: "00:00 do dia inicial no fuso da pessoa, em UTC; incluído" }
      - { name: to, type: instant, required: true, constraints: "00:00 do dia seguinte ao dia final no fuso da pessoa, em UTC; excluído" }
    entity_constraints:
      - "from < to; to - from <= 366 dias"

  - name: PnlPeriodRequest
    id: ENT-306
    kind: api_shape
    change: additive
    description: >
      Os parâmetros de período de C4 e C6. U3 acrescenta RANGE ao period e os parâmetros from e to (já
      previstos em C4). ALL não envia from nem to.
    attributes:
      - { name: period, type: enum, required: false, allowed: [ALL, RANGE], default: ALL }
      - { name: from, type: instant, required: false, constraints: "obrigatório em RANGE; UTC" }
      - { name: to, type: instant, required: false, constraints: "obrigatório em RANGE; UTC" }
    entity_constraints:
      - "RANGE sem from ou to, from >= to ou mais de 366 dias = 400 VALIDATION_FAILED (C4, C6)"

  - name: PeriodLabel
    id: ENT-307
    kind: view_model
    change: new
    description: >
      O texto "Mostrando: …" escrito na tela, no fuso da pessoa, que lembra que o não realizado é o de
      agora. Exemplo: "Mostrando: de 10/11 00:00 a 16/11 23:59 · realizado no período; não realizado
      de agora".
    attributes:
      - { name: text, type: text, required: true }
      - { name: timeZone, type: text, required: true }

  # ---------------- C4 por modo (US4.1, US4.3, US7.1) ----------------
  - name: ByModeRequest
    id: ENT-308
    kind: api_shape
    change: additive
    description: >
      A requisição de GET /api/v1/pnl/by-mode (C4). Além do período, ganha o parâmetro opcional
      market [Q1, PQ-6], que pede um bloco só.
    attributes:
      - { name: period, type: "ref<PnlPeriodRequest>", required: false }
      - name: market
        type: enum
        required: false
        allowed: [PAPER, REAL]
        constraints: "novo (U3); ausente = os dois blocos, como hoje; outro valor = 400 VALIDATION_FAILED"

  - name: PnlByModeResponse
    id: ENT-309
    kind: api_shape
    change: additive
    description: >
      A resposta de C4. Continua com só três propriedades (period, paper, real) e nenhum campo de total
      fora dos blocos. Com market, só o bloco pedido vem na resposta; sem market, os dois vêm, como
      hoje. Por isso paper e real deixam de ser obrigatórios no esquema, e o teste de contrato confere a
      presença pela regra BR3.1.
    attributes:
      - { name: period, type: "ref<PnlPeriod>", required: true, constraints: "kind ALL ou RANGE, com from e to em UTC no RANGE" }
      - { name: paper, type: "union<PaperModeBlock | ModeBlockUnavailable>", required: false, constraints: "presente sem market ou com market=PAPER; com market=REAL a chave é omitida do JSON, nunca null" }
      - { name: real, type: "union<RealModeBlock | ModeBlockUnavailable>", required: false, constraints: "presente sem market ou com market=REAL; com market=PAPER a chave é omitida do JSON, nunca null" }
    entity_constraints:
      - "exatamente as chaves period, paper e real; nenhuma outra (regra 6 de posse de C4, NEVER_SUM_MODES)"
      - "sem market: paper e real presentes e não nulos; com market=X: só a chave do bloco de X no JSON (BR3.1)"
      - "só o apps/web consome C4; o apps/admin não chama by-mode"
    relationships:
      - { target: BlockLoad, cardinality: "1:2", direction: "PnlByModeResponse -> BlockLoad", note: "cada bloco vira o estado do seu bloco na tela" }

  - name: BlockLoad
    id: ENT-310
    kind: view_model
    change: existing
    description: >
      O estado de UM bloco na tela (tipo que já existe em PnlModeBlock): loading, error com o código
      para o suporte, ou ready com o bloco do modo. ModeBlockUnavailable vira error com o
      correlationId.
    attributes:
      - { name: kind, type: enum, required: true, allowed: [loading, error, ready] }
      - { name: code, type: text, required: false, constraints: "só em error: correlationId, o código da API ou NETWORK" }
      - { name: block, type: "union<PaperModeBlock | RealModeBlock>", required: false, constraints: "só em ready; um modo só" }
      - { name: market, type: enum, required: true, allowed: [PAPER, REAL], constraints: "fixo por bloco; nenhum BlockLoad carrega os dois modos" }

  - name: RealBotLineView
    id: ENT-311
    kind: api_shape
    change: existing
    description: >
      A linha de um bot REAL dentro do bloco REAL de C4 (RealBotPnlLine, dona U2): totais prontos em
      lamports e as parcelas rateadas (prorated). A U3 passa a mostrar prorated.
    attributes:
      - { name: botId, type: uuid, required: true }
      - { name: name, type: text, required: true }
      - { name: botStatus, type: enum, required: true, allowed: [ACTIVE, PAUSED, REMOVED] }
      - { name: totalLamports, type: "union<lamports | UNKNOWN>", required: true }
      - { name: realizedLamports, type: lamports, required: true }
      - { name: unrealizedLamports, type: "union<lamports | UNKNOWN>", required: true }
      - { name: feesLamports, type: lamports, required: true }
      - { name: prorated, type: "ref<ProratedSharesView>", required: true }

  - name: ProratedSharesView
    id: ENT-312
    kind: api_shape
    change: existing
    description: >
      As parcelas rateadas de um bot REAL (ProratedShares de C4, dona U2): o resumo por origem já
      somado pela API, a contagem e as até 10 parcelas mais recentes.
    attributes:
      - { name: summary, type: "list<{origin: ProratedOrigin, realizedLamports: lamports}>", required: true, constraints: "uma linha por origem com parcela; valor calculado no servidor" }
      - { name: count, type: integer, required: true, min: 0, constraints: "número total de parcelas do bot" }
      - { name: lines, type: "list<ProratedLine>", required: true, max: 10, constraints: "as 10 mais recentes; as demais pelo C5" }
    entity_constraints:
      - "count = 0 => summary e lines vazios e o componente não aparece (BR4.7)"
    relationships:
      - { target: ProratedLine, cardinality: "1:0..10", direction: "ProratedSharesView -> ProratedLine" }

  - name: ProratedLine
    id: ENT-313
    kind: api_shape
    change: existing
    description: "Uma parcela rateada (C4, dona U2)."
    attributes:
      - { name: at, type: instant, required: true, constraints: "UTC; mostrado no fuso da pessoa" }
      - { name: mint, type: text, required: true }
      - { name: origin, type: enum, required: true, allowed: [LIQUIDATION, CONDITIONAL, SOLD_BY_OTHER_BOT] }
      - { name: tokenBaseUnits, type: base_units, required: true }
      - { name: receivedLamports, type: lamports, required: true }
      - { name: feeLamports, type: lamports, required: true }
      - { name: realizedLamports, type: lamports, required: true }

  - name: UnownedLineView
    id: ENT-314
    kind: api_shape
    change: existing
    description: >
      A linha "Sem bot" do bloco REAL (UnownedPnlLine de C4, desde a U2 sempre objeto), com os
      motivos. A U3 a mostra com os textos pnl.noBotReason.*.
    attributes:
      - { name: realizedLamports, type: lamports, required: true }
      - { name: unrealizedLamports, type: "union<lamports | UNKNOWN>", required: true }
      - { name: feesLamports, type: lamports, required: true }
      - name: reasons
        type: "list<enum>"
        required: true
        allowed: [UNOWNED_EXCESS, UNOWNED_NO_COST, ORDER_RECORD_MISSING]
    entity_constraints:
      - "a linha aparece só quando algum dos três valores é diferente de zero ou desconhecido (BR4.8)"

  # ---------------- C5 linhas do bot (US3.10) ----------------
  - name: BotPnlLinesRequest
    id: ENT-315
    kind: api_shape
    change: existing
    description: >
      A requisição de GET /api/v1/bots/{botId}/pnl/lines (C5). Sem parâmetro de período: o detalhe do
      bot mostra sempre "desde o início" (BR4.6).
    attributes:
      - { name: botId, type: uuid, required: true, constraints: "na rota; o tenant vem do token" }
      - { name: cursor, type: text, required: false, constraints: "opaco; vem de nextCursor" }
      - { name: limit, type: integer, required: false, default: 50, min: 1, max: 100, constraints: "a tela sempre pede 50 [Q3]" }

  - name: BotPnlLineItem
    id: ENT-316
    kind: api_shape
    change: existing
    description: "Uma linha do P&L de um bot REAL (RealBotPnlLineItem de C5)."
    attributes:
      - { name: at, type: instant, required: true }
      - { name: side, type: enum, required: true, allowed: [BUY, SELL] }
      - { name: mint, type: text, required: true }
      - { name: origin, type: enum, required: true, allowed: [BOT, LIQUIDATION, CONDITIONAL, SOLD_BY_OTHER_BOT] }
      - { name: tokenBaseUnits, type: base_units, required: true }
      - { name: amountLamports, type: lamports, required: true }
      - { name: feeLamports, type: lamports, required: true }
      - { name: realizedLamports, type: lamports, required: true, constraints: "\"0\" numa compra; a tela mostra \"—\" lido como \"sem resultado\" (BR4.4)" }

  - name: BotPnlLinesPage
    id: ENT-317
    kind: api_shape
    change: existing
    description: "Uma página de C5 (RealBotPnlLinePage)."
    attributes:
      - { name: currency, type: enum, required: true, allowed: [SOL] }
      - { name: items, type: "list<BotPnlLineItem>", required: true, constraints: "da mais recente para a mais antiga, na ordem da rede" }
      - { name: nextCursor, type: text, required: false, constraints: "null na última página" }

  - name: BotPnlLinesState
    id: ENT-318
    kind: view_model
    change: new
    description: "O estado da tabela de linhas no detalhe de um bot REAL."
    attributes:
      - { name: phase, type: enum, required: true, allowed: [loading, ready, loadingMore, moreError, error, notFound, empty] }
      - { name: items, type: "list<BotPnlLineItem>", required: true, default: "[]", constraints: "acumula as páginas na ordem recebida; nunca reordena" }
      - { name: nextCursor, type: text, required: false }
      - { name: code, type: text, required: false, constraints: "código para o suporte em error e moreError" }
      - { name: firstNewIndex, type: integer, required: false, constraints: "a primeira linha nova depois de Carregar mais, para o foco" }

  # ---------------- C6 comparação (US8.1) ----------------
  - name: ComparisonSelection
    id: ENT-319
    kind: view_model
    change: new
    description: >
      A escolha de dois bots, um PAPER e um REAL, guardada no endereço (?comparar=<idPaper>,<idReal>).
    attributes:
      - { name: paperBotId, type: uuid, required: false }
      - { name: realBotId, type: uuid, required: false }
      - { name: open, type: boolean, required: true, default: false, constraints: "true quando o endereço traz comparar" }
    entity_constraints:
      - "o select Bot real só lista bots REAL com o mesmo templateId do bot simulado escolhido (BR5.1)"
      - "um valor de comparar que não são dois uuid separados por vírgula é ignorado: a vista abre vazia (BR5.6)"

  - name: CompareRequest
    id: ENT-320
    kind: api_shape
    change: existing
    description: "A requisição de GET /api/v1/pnl/compare (C6)."
    attributes:
      - { name: paperBotId, type: uuid, required: true }
      - { name: realBotId, type: uuid, required: true }
      - { name: period, type: "ref<PnlPeriodRequest>", required: false, constraints: "o mesmo período do PeriodSelector" }

  - name: PnlComparisonResponse
    id: ENT-321
    kind: api_shape
    change: existing
    description: >
      A resposta de C6: só o período e os dois lados, cada um na própria moeda. Nenhum campo de
      diferença, percentual, ranking ou total.
    attributes:
      - { name: period, type: "ref<PnlPeriod>", required: true }
      - { name: paper, type: "union<PaperComparisonSide | ModeBlockUnavailable>", required: true, constraints: "lado PAPER: currency USD, disclaimer, createdAt, templateId e a linha PaperBotPnlLine" }
      - { name: real, type: "union<RealComparisonSide | ModeBlockUnavailable>", required: true, constraints: "lado REAL: currency SOL, createdAt, templateId e a linha RealBotPnlLine; sem disclaimer" }
    entity_constraints:
      - "422 VALIDATION_FAILED com reason TEMPLATE_MISMATCH quando os templateId diferem (BR5.2)"
      - "404 NOT_FOUND com reason PAPER_BOT ou REAL_BOT para bot de outra pessoa, inexistente ou do modo errado (BR5.3)"

  - name: ComparisonSideState
    id: ENT-322
    kind: view_model
    change: new
    description: "O estado de cada lado da comparação; os dois lados são independentes."
    attributes:
      - { name: market, type: enum, required: true, allowed: [PAPER, REAL] }
      - { name: phase, type: enum, required: true, allowed: [idle, loading, ready, error, notFound] }
      - { name: code, type: text, required: false, constraints: "só em error" }
    relationships:
      - { target: ComparisonView, cardinality: "2:1", direction: "ComparisonSideState -> ComparisonView" }

  - name: ComparisonView
    id: ENT-323
    kind: view_model
    change: new
    description: "O estado da vista de comparação inteira."
    attributes:
      - { name: phase, type: enum, required: true, allowed: [closed, noPair, choosing, comparing, mismatch] }
      - { name: selection, type: "ref<ComparisonSelection>", required: true }

  # ---------------- Painel (US4.3) ----------------
  - name: DashboardModeCard
    id: ENT-324
    kind: view_model
    change: new
    description: >
      Um dos dois cartões do painel, "Resultado simulado" ou "Resultado real", no período ALL. É um
      PnlModeBlock na variante compact; não existe terceiro cartão nem campo com os dois modos.
    attributes:
      - { name: market, type: enum, required: true, allowed: [PAPER, REAL] }
      - { name: load, type: "ref<BlockLoad>", required: true }
      - name: realUnavailable
        type: enum
        required: false
        allowed: [ELIGIBLE, WALLET_INACTIVE, KILL_SWITCH, REAL_DISABLED, NOT_RELEASED, QUARANTINE, UNKNOWN]
        constraints: "só no cartão REAL vazio; vem de C2 (GET /api/v1/bots/real-eligibility); UNKNOWN quando C2 falhou"
      - { name: lastAnnouncedAt, type: instant, required: false, constraints: "para o limite de um anúncio aria-live a cada 30 s por cartão" }
      - { name: lastReloadAt, type: instant, required: false, constraints: "última releitura por evento do canal; no máximo uma a cada 30 s (BR7.5)" }
      - { name: reloadPending, type: boolean, required: true, default: false, constraints: "evento recebido dentro da janela; relê no fim dela (BR7.5)" }
      - name: reloadEvents
        type: "list<text>"
        required: true
        constraints: "PAPER: pnl.updated; REAL: real.order.state com state CONFIRMED ou FAILED e real.conditional.state (BR7.5)"

  - name: BotDetailPnl
    id: ENT-325
    kind: view_model
    change: new
    description: >
      O P&L do bot no cabeçalho do detalhe (PnlModeBlock variante bot), desde o início. Vem da linha do
      bot em C4 com market igual ao modo do bot (PaperBotPnlLine ou RealBotPnlLine), procurada pelo
      botId, sem nenhuma conta.
    attributes:
      - { name: market, type: enum, required: true, allowed: [PAPER, REAL] }
      - { name: line, type: "union<PaperBotPnlLine | RealBotPnlLine>", required: false }
      - { name: phase, type: enum, required: true, allowed: [loading, ready, error, absent] }
```

## Resumo

| Id | Entidade | Tipo | Mudança | Para quê |
|---|---|---|---|---|
| ENT-301 | ModeFilter | tela | nova | filtro Todos, Simulado e Real, no endereço `?mode=` |
| ENT-302 | BotListQuery | API | aditiva | parâmetro `market` em `GET /api/v1/bots` |
| ENT-303 | BotPageCounts | API | aditiva | `matchingCount` e `totalCount` em `BotPage` |
| ENT-304 | PeriodChoice | tela | nova | ALL, TODAY, LAST_7, LAST_30 e RANGE em dias locais |
| ENT-305 | UtcPeriod | tela | existente | `[from, to)` em UTC, de `period.ts` |
| ENT-306 | PnlPeriodRequest | API | aditiva | `period=RANGE`, `from`, `to` em C4 e C6 |
| ENT-307 | PeriodLabel | tela | nova | "Mostrando: …" no fuso da pessoa |
| ENT-308 | ByModeRequest | API | aditiva | parâmetro `market` em C4 |
| ENT-309 | PnlByModeResponse | API | aditiva | só o bloco pedido com `market` |
| ENT-310 | BlockLoad | tela | existente | estado de um bloco |
| ENT-311 a ENT-314 | linhas REAL, parcelas e "Sem bot" | API | existentes | o que a U3 passa a mostrar no bloco REAL |
| ENT-315 a ENT-318 | linhas de C5 e o estado da tabela | API e tela | existentes e nova | detalhe do bot REAL com a origem |
| ENT-319 a ENT-323 | comparação | API e tela | existentes e novas | dois bots, mesmo `templateId`, lado a lado |
| ENT-324 | DashboardModeCard | tela | nova | os dois cartões do painel |
| ENT-325 | BotDetailPnl | tela | nova | P&L do bot no detalhe, de C4 |

Nenhuma entidade tem um atributo que guarde ou calcule um valor dos dois modos juntos. As únicas contagens novas (`matchingCount`, `totalCount`) contam bots, não dinheiro.

## Sources

- `functional-design-questions.md` desta pasta: Q1 a Q7 e o resumo confirmado (fonte que manda).
- `inception/contract-design/contract-summary.md`: formatos comuns, C4 (PnlByMode, ProratedShares, UnownedPnlLine), C5, C6, C10, C17, PQ-6 e PQ-8.
- `inception/refined-mockups/interaction-spec.md`: PnlModeBlock, ProratedLines, PeriodSelector, BotModeFilter, ComparisonView, BotPnlLines; `mockups.md` M4 a M7.
- `inception/refined-mockups/design-system-mapping.md`: chaves `pnl.noBotReason.*`, `pnl.origins.*`, `pnl.period.*`, `dashboard.*`, `compare.*`.
- `construction/u1-mode-skeleton/` (código e design): `BlockLoad`, `by-mode.ts`, `PnlModeBlock`, `TableScroll`, `money-format.ts`.
- `construction/u2-real-bot-pnl/code-generation/code-summary.md`: `prorated` e `unowned` preenchidos, motivos novos.
- Código lido: `apps/web/src/features/pnl/{period.ts,by-mode.ts,PnlModeBlock.tsx,PnlScreen.tsx,money-format.ts}`, `features/bots/BotList.tsx`, `apps/api/openapi/openapi-guest.yaml` (`listBots` com `state`, `cursor`, `limit`; `BotPage` com `activeCount`, `activeLimit`, `items`, `nextCursor`).

## Assumptions & Open Questions

1. [assumption] **Contagens em `BotPage`.** O filtro no servidor [Q2] tira da tela a lista inteira; para escrever "Mostrando N de M bots." e separar "Você não tem bots reais." de "Você ainda não tem bots." (AC2.3.4) sem uma segunda leitura, `BotPage` ganha `matchingCount` e `totalCount`. É uma mudança aditiva de resposta em `listBots`, fora de C4, e fica registrada para o dono do contrato. A alternativa sem mudança é uma segunda leitura sem filtro quando a lista filtrada vem vazia, e a linha perderia o "de M".
2. [assumption] **`paper` e `real` opcionais em C4.** Com `market`, a resposta traz só o bloco pedido [Q1]. Para isso, as duas propriedades deixam de ser obrigatórias no esquema; o teste de contrato passa a conferir a regra de presença (BR3.1). Os consumidores do `apps/web` (`PnlScreen`, `BotList`, painel, detalhe) são ajustados no mesmo commit.
3. [assumption] **C5 sem período.** O detalhe do bot (M7) não tem seletor e mostra "desde o início"; "o mesmo período da tela" [Q3] é esse. C5 fica sem parâmetro de período, como no contrato.
4. **Decisão: nome do parâmetro do filtro.** O endereço usa `?mode=PAPER|REAL`, como diz a Q2, com os valores do contrato. O `?modo=simulado` do mockup M4.2 fica superado. O período e a comparação seguem as chaves da especificação de interação, em português (`periodo`, `de`, `ate`, `comparar`); a mistura fica registrada como decisão (revisão R-08).
5. **Decisão do fundador (2026-11-23): "Hoje" no período.** `PeriodChoice` ganha `TODAY`, o dia local inteiro da pessoa, inclusive, enviado em UTC (Q6). Ver BR2.1 e BR2.2.
6. **Decisão do fundador (2026-11-23): tabela por token do PAPER.** Ela só é lida e mostrada com um período concreto; em ALL aparece a nota "Escolha um período para ver o resultado por token" (BR2.9). Nenhuma entidade nova.
7. [assumption] **Cabeçalho do detalhe.** `BotDetailPnl` lê o bloco inteiro de C4 para achar uma linha; o custo é aceito (BR4.6).
