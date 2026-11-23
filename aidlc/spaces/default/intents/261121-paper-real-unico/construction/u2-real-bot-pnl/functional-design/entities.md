# Entidades — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22. Unit `service`: o núcleo financeiro do P&L REAL por bot.

Convenções dos tipos lógicos (independentes de linguagem, as mesmas da U1):
- `lamports`: inteiro com sinal, exato, nunca ponto flutuante;
- `base_units`: inteiro não negativo, a quantidade de token em unidades base;
- `slot`: inteiro não negativo da rede;
- `instant`: instante em UTC;
- `price`: decimal exato, em lamports por unidade base;
- `ratio`: fração exata, numerador e denominador inteiros de precisão arbitrária;
- `ref<X>`: referência a outra entidade.

Os IDs desta Unit começam em `ENT-201`, para não colidir com os da U1 (`ENT-001` a `ENT-0xx`). Nenhuma entidade aqui é gravada como atribuição: tudo, menos `ENT-202`, é calculado na leitura (ADR-004).

```yaml
entities:
  - id: ENT-201
    name: NetworkOrderKey
    description: >
      Chave de ordem de rede comum (C12, ADR-011 alinhado). Ordena os lançamentos REAL de uma carteira do bot.
      Uma só chave para RealPositions, RealCostMethod, RealCostBasis, RealBotPnl e RealBotHoldings.
    kind: value
    attributes:
      - { name: effectiveSlot, type: slot, required: true, constraints: "o slot gravado em real_entry_slot ou, sem ele, o herdado (BR1.3)" }
      - { name: inherited, type: boolean, required: true, default: false, constraints: "true quando a linha não tem slot gravado" }
      - { name: txSignature, type: text, required: false, constraints: "assinatura da transação; ausente em RESERVE e COMPENSATION" }
      - { name: createdAt, type: instant, required: true }
      - { name: reference, type: text, required: true }
      - { name: kindRank, type: integer, required: true, allowed: [0, 1, 2, 3], constraints: "SWAP_FILL_IN/OUT 0, NETWORK_FEE 1, RENT 2, demais 3" }
      - { name: idempotencyKey, type: text, required: true, unique: true, constraints: "montada com identificadores de negócio; é o desempate final, porque o ledger não tem coluna de sequência" }
    constraints:
      - "ordem total: effectiveSlot; depois as linhas com slot gravado antes das herdadas; entre gravadas: txSignature (BR1.2), depois createdAt, reference, kindRank e idempotencyKey; entre herdadas: createdAt, reference, kindRank e idempotencyKey (BR1.1)"
      - "nunca usa entryId (UUID aleatório) como desempate"
      - "determinística: a mesma carteira gera sempre a mesma sequência, qualquer que seja a ordem de gravação das linhas com slot (BR1.1)"
    relationships:
      - { target: ENT-203, cardinality: "1:1", direction: "ENT-203 tem uma chave" }

  - id: ENT-202
    name: EntrySlot
    description: >
      Linha da tabela lateral real_entry_slot (C14), só de inserção e dona do módulo ledger. Liga uma assinatura
      pública da rede ao slot da confirmação. É a única entidade gravada pela U2.
    kind: stored
    attributes:
      - { name: txSignature, type: text, required: true, unique: true, constraints: "até 90 caracteres; chave" }
      - { name: slot, type: slot, required: true, min: 0 }
      - { name: recordedAt, type: instant, required: true }
    constraints:
      - "só inserção: atualizar, apagar ou truncar é recusado (mesma proteção do ledger)"
      - "sem tenant e sem dado de cliente; toda leitura chega pela junção com o lançamento, que filtra o tenant"
      - "nenhuma linha anterior à migração 0023 é preenchida (PQ-4)"
    relationships:
      - { target: ENT-203, cardinality: "1:N", direction: "uma assinatura dá o slot a todos os lançamentos com ela" }

  - id: ENT-203
    name: OrderedLedgerEntry
    description: >
      Um lançamento do ledger REAL entregue em fluxo, já na ordem de rede (C12, NetworkOrderedEntry).
    kind: value
    attributes:
      - { name: entryId, type: uuid, required: true, unique: true }
      - { name: botWalletId, type: "ref<BotWallet>", required: true }
      - { name: kind, type: enum, required: true, allowed: [DEPOSIT, WITHDRAWAL, NETWORK_FEE, RENT, SWAP_FILL_IN, SWAP_FILL_OUT, COMPENSATION, RESERVE, RESERVE_RELEASE] }
      - { name: lamports, type: lamports, required: true, constraints: "sinal do ledger: SWAP_FILL_IN, NETWORK_FEE e RENT negativos; SWAP_FILL_OUT positivo" }
      - { name: mint, type: text, required: false, constraints: "só em lançamento de token" }
      - { name: tokenBaseUnits, type: base_units, required: false, min: 1, constraints: "só em SWAP_FILL_IN e SWAP_FILL_OUT (e COMPENSATION de token)" }
      - { name: txSignature, type: text, required: false }
      - { name: reference, type: text, required: true, constraints: "order:<id>, conditional:<id>, dep:<assinatura>, wd:<id>" }
      - { name: createdAt, type: instant, required: true, constraints: "o instante do lançamento; decide o período (BR7.6)" }
      - { name: key, type: "ref<ENT-201>", required: true }
      - { name: slotStatus, type: enum, required: true, allowed: [RECORDED, SLOT_MISSING, NO_NETWORK_TX] }
    constraints:
      - "o P&L usa só os lançamentos de negociação: SWAP_FILL_IN, SWAP_FILL_OUT, NETWORK_FEE e RENT com referência order:<id> ou conditional:<id> (BR6.5)"
    relationships:
      - { target: ENT-204, cardinality: "N:1", direction: "a referência order:<id> casa com a atribuição da ordem" }

  - id: ENT-204
    name: OrderAttribution
    description: >
      O que o P&L precisa de uma ordem REAL que chegou à rede (C13, streamSettledOrders). É a única ligação entre uma
      operação REAL e um bot (real_order.bot_id).
    kind: value
    attributes:
      - { name: realOrderId, type: uuid, required: true, unique: true }
      - { name: botId, type: "ref<Bot>", required: false, constraints: "presente quando origin = BOT" }
      - { name: liquidationId, type: uuid, required: false, constraints: "presente quando origin = USER_LIQUIDATION" }
      - { name: origin, type: enum, required: true, allowed: [BOT, USER_LIQUIDATION] }
      - { name: side, type: enum, required: true, allowed: [BUY, SELL] }
      - { name: state, type: enum, required: true, allowed: [CONFIRMED, FAILED] }
      - { name: inputBaseUnits, type: base_units, required: false, constraints: "quantidade pedida numa venda; usada só para a taxa de uma liquidação que falhou (BR6.2)" }
      - { name: feeLamports, type: lamports, required: false, min: 0 }
      - { name: feePayer, type: enum, required: false, allowed: [BOT_WALLET, MAIN_WALLET] }
      - { name: txSignature, type: text, required: false }
      - { name: settledAt, type: instant, required: true, constraints: "instante em que a ordem chegou a CONFIRMED ou FAILED" }
    constraints:
      - "nunca traz endereço de carteira"
      - "uma ordem com bot que não é bot REAL da pessoa no diretório é falha de integridade (BR3.4)"
    relationships:
      - { target: ENT-205, cardinality: "N:1", direction: "uma ordem de bot tem o bot como titular" }

  - id: ENT-205
    name: Holder
    description: >
      Titular de uma posição de custo dentro de uma carteira do bot: um bot REAL da pessoa ou a parcela sem dono.
    kind: value
    attributes:
      - { name: kind, type: enum, required: true, allowed: [BOT, UNOWNED] }
      - { name: botId, type: "ref<Bot>", required: false, constraints: "obrigatório quando kind = BOT" }
      - { name: botCreatedAt, type: instant, required: false, constraints: "obrigatório quando kind = BOT; do diretório (C8)" }
      - { name: status, type: enum, required: false, allowed: [ACTIVE, PAUSED, REMOVED], constraints: "do diretório; não muda a posse (BR3.3)" }
    constraints:
      - "ordem de desempate total: bots por botCreatedAt, depois botId na forma textual canônica em minúsculas; UNOWNED sempre por último (BR4.2)"
      - "existe no máximo um UNOWNED por pessoa"

  - id: ENT-206
    name: RealCostPosition
    description: >
      A posição de custo médio de um titular num token, numa carteira do bot (ADR-002: dona única da posição REAL).
      Para a carteira, a quantidade vem do caminho só de quantidade, direto do ledger e sem atribuição (BR2.5). O
      custo é a soma dos custos dos titulares, lido com a atribuição só por quem precisa dele (BR2.4, BR2.7). A
      forma é a de ledger.api.RealPosition, alinhada pela BR2.8.
    kind: value
    attributes:
      - { name: botWalletId, type: "ref<BotWallet>", required: true }
      - { name: holder, type: "ref<ENT-205>", required: true }
      - { name: mint, type: text, required: true }
      - { name: quantity, type: base_units, required: true, min: 0 }
      - { name: costLamports, type: lamports, required: true, min: 0 }
    constraints:
      - "quantity = 0 implica costLamports = 0 (a última venda leva o custo restante, BR2.2)"
      - "chave única: (botWalletId, holder, mint)"
      - "uma posição aberta tem quantity >= 1"
    relationships:
      - { target: ENT-205, cardinality: "N:1", direction: "cada posição tem um titular" }

  - id: ENT-207
    name: TradeEvent
    description: >
      Um evento do cálculo, montado a partir de um ou mais lançamentos com a mesma referência e da atribuição da
      ordem. É a unidade que o cálculo em fluxo aplica, na ordem de rede.
    kind: value
    attributes:
      - { name: type, type: enum, required: true, allowed: [BUY, OWN_SALE, SALE_WITHOUT_BOT, FEE_ONLY] }
      - { name: reference, type: text, required: true }
      - { name: origin, type: enum, required: false, allowed: [BOT, LIQUIDATION, CONDITIONAL, ORDER_RECORD_MISSING] }
      - { name: botWalletId, type: "ref<BotWallet>", required: true }
      - { name: mint, type: text, required: false }
      - { name: tokenBaseUnits, type: base_units, required: false, constraints: "comprada ou vendida (executada)" }
      - { name: swapLamports, type: lamports, required: false, constraints: "pago na compra (perna SOL, positivo) ou recebido na venda" }
      - { name: rentLamports, type: lamports, required: false, min: 0, default: 0 }
      - { name: feeLamports, type: lamports, required: true, min: 0, constraints: "taxa da rede paga pela carteira do bot ou, no modo Assinada, pela carteira principal" }
      - { name: seller, type: "ref<ENT-205>", required: false, constraints: "em OWN_SALE: o bot da ordem; com a ordem ausente: UNOWNED" }
      - { name: at, type: instant, required: true, constraints: "instante único do evento, usado por todas as partes, pela taxa e pelo RENT, nos dois lados da conferência: createdAt do SWAP_FILL_IN ou SWAP_FILL_OUT; em FEE_ONLY, createdAt do NETWORK_FEE ou, sem ele, settledAt da ordem (BR7.6)" }
      - { name: key, type: "ref<ENT-201>", required: true }
    constraints:
      - "agrupamento: os lançamentos de negociação com a mesma (carteira, referência, assinatura) formam um evento, mesmo que outro lançamento fique entre eles na chave; a chave do evento é a do lançamento principal"
      - "um RENT sem SWAP_FILL_IN na mesma referência não é BUY e segue a BR6.6"
      - "BUY: SWAP_FILL_IN (mais RENT) de uma referência; OWN_SALE: SWAP_FILL_OUT de ordem com bot; SALE_WITHOUT_BOT: SWAP_FILL_OUT de liquidação (origem LIQUIDATION) ou de conditional:<id> (origem CONDITIONAL); FEE_ONLY: ordem que falhou na rede"

  - id: ENT-208
    name: ProrationPart
    description: >
      A parte de um participante numa venda (OWN_SALE ou SALE_WITHOUT_BOT) ou numa taxa rateada.
    kind: value
    attributes:
      - { name: participant, type: "ref<ENT-205>", required: true }
      - { name: tokenBaseUnits, type: base_units, required: true, min: 0 }
      - { name: receivedLamports, type: lamports, required: true, min: 0 }
      - { name: feeLamports, type: lamports, required: true, min: 0 }
      - { name: costRemovedLamports, type: lamports, required: true, min: 0 }
      - { name: realizedLamports, type: lamports, required: true, constraints: "receivedLamports - costRemovedLamports (BR7.1)" }
      - { name: origin, type: enum, required: true, allowed: [OWN, LIQUIDATION, CONDITIONAL, SOLD_BY_OTHER_BOT, UNOWNED_EXCESS, UNOWNED_NO_COST] }
    constraints:
      - "num evento: soma de tokenBaseUnits = vendido; soma de receivedLamports = recebido; soma de feeLamports = taxa (BR8.4)"
      - "um participante sem quantidade logo antes da venda não recebe parte (BR4.8)"

  - id: ENT-209
    name: ProratedShare
    description: >
      A forma pública de uma parte rateada de um bot (C10). Só para partes de bots com origem LIQUIDATION,
      CONDITIONAL ou SOLD_BY_OTHER_BOT; a parte de venda própria não vira share.
    kind: value
    attributes:
      - { name: saleRef, type: text, required: true, constraints: "a referência da venda (order:<id> ou conditional:<id>)" }
      - { name: origin, type: enum, required: true, allowed: [LIQUIDATION, CONDITIONAL, SOLD_BY_OTHER_BOT] }
      - { name: mint, type: text, required: true }
      - { name: at, type: instant, required: true }
      - { name: tokenBaseUnits, type: base_units, required: true }
      - { name: receivedLamports, type: lamports, required: true }
      - { name: feeLamports, type: lamports, required: true }
      - { name: realizedLamports, type: lamports, required: true }
    relationships:
      - { target: ENT-210, cardinality: "N:1", direction: "cada share pertence a uma linha de bot" }

  - id: ENT-210
    name: RealBotPnlLine
    description: >
      O P&L REAL de um bot no período (C10). A partir da U2, o realizado é bruto de taxas (Q5).
    kind: value
    attributes:
      - { name: botId, type: "ref<Bot>", required: true }
      - { name: status, type: enum, required: true, allowed: [ACTIVE, PAUSED, REMOVED] }
      - { name: realizedLamports, type: lamports, required: true, constraints: "bruto: soma dos realizedLamports das partes do bot no período" }
      - { name: unrealized, type: "Known(lamports) | Unknown(reason)", required: true, constraints: "sempre o de agora (BR7.6); Unknown quando falta o preço de uma posição aberta do bot" }
      - { name: feesLamports, type: lamports, required: true, min: 0 }
      - { name: total, type: "Known(lamports) | Unknown(reason)", required: true, constraints: "realized + unrealized - fees; Unknown com o não realizado (BR7.4)" }
      - { name: shares, type: "list<ENT-209>", required: true, default: "[]", constraints: "as partes rateadas do bot no período" }
    constraints:
      - "nunca traz endereço de carteira (NFR3)"
      - "uma linha por bot REAL do diretório, pausados e removidos incluídos, em ordem de criação"

  - id: ENT-211
    name: UnownedPnlLine
    description: >
      A linha "Sem bot" do REAL (C10). Vazia (ausente) quando não há nada sem dono.
    kind: value
    attributes:
      - { name: realizedLamports, type: lamports, required: true }
      - { name: unrealized, type: "Known(lamports) | Unknown(reason)", required: true }
      - { name: feesLamports, type: lamports, required: true, min: 0 }
      - { name: reasons, type: "list<enum>", required: true, allowed: [UNOWNED_EXCESS, UNOWNED_NO_COST, ORDER_RECORD_MISSING], constraints: "conjunto, sem repetição" }
    constraints:
      - "presente quando há algo atribuído ao titular UNOWNED desde o início; com qualquer valor diferente de zero, reasons não é vazio (BR8.5)"
      - "o valor SURPLUS_WITHOUT_OWNER do contrato da U1 é renomeado para UNOWNED_EXCESS (BR5.6)"

  - id: ENT-212
    name: RealModeTotal
    description: >
      O total REAL do modo, calculado do lado do ledger e nunca como soma das linhas (BR7.5).
    kind: value
    attributes:
      - { name: realizedLamports, type: lamports, required: true }
      - { name: unrealized, type: "Known(lamports) | Unknown(reason)", required: true }
      - { name: feesLamports, type: lamports, required: true, min: 0 }
      - { name: total, type: "Known(lamports) | Unknown(reason)", required: true, constraints: "realized + unrealized - fees" }
      - { name: currency, type: enum, required: true, allowed: [SOL] }
      - { name: unrealizedAsOf, type: instant, required: true }
    constraints:
      - "só sai depois da conferência exata (BR8.1, BR8.2); com diferença, nada sai (BR8.7)"

  - id: ENT-213
    name: ReconciliationResult
    description: >
      O resultado da conferência entre as linhas (bots mais "Sem bot") e o lado do ledger, para uma pessoa e um
      período (FR5.3).
    kind: value
    attributes:
      - { name: window, type: "All | Range(from, to)", required: true }
      - { name: status, type: enum, required: true, allowed: [MATCH, MISMATCH] }
      - { name: realizedDiffLamports, type: lamports, required: true, default: 0 }
      - { name: feesDiffLamports, type: lamports, required: true, default: 0 }
      - { name: unrealizedDiffLamports, type: lamports, required: false, constraints: "só no período All" }
      - { name: failedChecks, type: "list<enum>", required: true, allowed: [REALIZED, FEES, UNREALIZED, COST_CONSERVATION, QUANTITY_CONSERVATION, EVENT_SUMS, UNOWNED_WITHOUT_REASON, UNKNOWN_BOT] }

  - id: ENT-214
    name: ReconciliationMismatchEvent
    description: >
      Evento de auditoria de uma conferência que não fechou (Q6).
    kind: audit
    attributes:
      - { name: type, type: enum, required: true, allowed: [REAL_PNL_RECONCILIATION_MISMATCH] }
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: window, type: text, required: true }
      - { name: failedChecks, type: "list<enum>", required: true }
      - { name: diffs, type: "map<text, lamports>", required: true }
      - { name: correlationId, type: text, required: true }
      - { name: at, type: instant, required: true }
    constraints:
      - "nunca traz endereço de carteira, assinatura de login, segredo nem mint fora do necessário (o mint da verificação que falhou pode entrar)"

  - id: ENT-215
    name: SlotConflictEvent
    description: >
      Evento de auditoria de um slot recusado para uma assinatura que já tinha outro slot (C13).
    kind: audit
    attributes:
      - { name: type, type: enum, required: true, allowed: [REAL_SLOT_CONFLICT] }
      - { name: txSignature, type: text, required: true }
      - { name: recordedSlot, type: slot, required: true }
      - { name: refusedSlot, type: slot, required: true }
      - { name: writer, type: enum, required: true, allowed: [ORDER_CONFIRMATION, CONDITIONAL_FILL, WALLET_CONFIRMATION] }
      - { name: at, type: instant, required: true }
```

## Resumo

- **Ordem e slot (`ENT-201` a `ENT-203`).** A ordem de rede vem de uma tabela lateral só de inserção (`ENT-202`), casada pela assinatura. Nenhuma linha antiga é preenchida; a linha sem slot herda o slot da anterior e fica sinalizada.
- **Atribuição (`ENT-204`, `ENT-205`).** A ordem diz o bot. Quem não tem bot resolvido vira o titular `UNOWNED`, sempre o último no desempate.
- **Custo (`ENT-206`).** Uma posição por carteira, titular e token. A quantidade da carteira vem direto do ledger, sem atribuição. O custo da carteira é a soma dos titulares, e o teste de paridade confere os dois com oráculos independentes (BR9.1).
- **Cálculo (`ENT-207`, `ENT-208`).** Cada evento (compra, venda própria, venda sem bot, taxa sozinha) é aplicado em fluxo, na ordem de rede. Uma venda vira partes por participante, e as partes sempre somam o evento.
- **Saída (`ENT-209` a `ENT-212`).** As formas de C10 já existem desde a U1. A U2 preenche as shares e a linha "Sem bot", passa o realizado a bruto e calcula o total do lado do ledger.
- **Conferência e auditoria (`ENT-213` a `ENT-215`).** A conferência devolve MATCH ou MISMATCH. Os dois eventos de auditoria registram uma diferença com o ledger e um conflito de slot.

## Sources

- `functional-design-questions.md` desta Unit: Q1 a Q7 e o resumo confirmado.
- `reviews/review-01.md` (R-01, R-05, R-06, R-09) e as decisões do fundador de 2026-11-22 (D1 a D3).
- `inception/units-generation/unit-of-work.md` (seção U2) e `unit-of-work-story-map.md` (linhas da U2).
- `inception/requirements-analysis/requirements.md`: FR3.2 a FR3.11, FR5.1 a FR5.4, FR7.2, NFR1 a NFR4.
- `inception/user-stories/stories.md`: US3.2 a US3.9, US3.11, US5.1 a US5.4, US7.2 e as convenções dos exemplos.
- `inception/domain-design/decisions.md`: ADR-001, ADR-002, ADR-004, ADR-007, ADR-011.
- `inception/contract-design/contract-summary.md`: C8, C10 a C14, PQ-2, PQ-4.
- `construction/u1-mode-skeleton/functional-design/` (`entities.md`, `rules.md`): ENT-011, ENT-012, BR5.5 e a nota de C10.
- Código lido: `pnl.api` (`RealModePnl`, `RealBotPnlLine`, `UnownedPnlLine`, `ProratedShare`, `RealModeTotal`, `PnlWindow`, `BotEntry`, `BotDirectory`, `RealPnlUnavailable`), `RealBotPnlQueryV1`, `RealPnlCalculator`, `ledger.domain.RealPositions`, `ledger.api` (`RealEntry`, `RealEntryKind`, `RealEntryRecord`, `RealLedger`, `RealPosition`), `realexecution.infra.RealOrderLedger`, `realexecution.domain.RealLedgerEntries`, `realexecution.api.RealOrderView`, `realexits.application.FillPosting`, `0019-real-execution.sql`, `db.changelog-master.yaml` (última migração: 0022).

## Assumptions & Open Questions

- [assumption] O titular `UNOWNED` existe por pessoa e por carteira. Ele recebe as compras sem bot resolvido e a quantidade vendida além das posições registradas (BR3.1, BR5.3).
- [assumption] `ENT-207.origin = ORDER_RECORD_MISSING` cobre só lançamentos cuja ordem foi expurgada (C13). Hoje nenhuma ordem é expurgada antes de 5 anos.
- [assumption] Os nomes `TradeEvent`, `ProrationPart`, `Holder`, `ReconciliationResult`, `REAL_PNL_RECONCILIATION_MISMATCH` e `ORDER_CONFIRMATION`/`CONDITIONAL_FILL`/`WALLET_CONFIRMATION` são internos e podem mudar na Code Generation, sem mudar C10 a C14.
