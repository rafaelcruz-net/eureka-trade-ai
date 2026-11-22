# Entidades — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

Modelo de entidades da U1: o modo do bot na criação, a elegibilidade do REAL, os limites REAL da conta, a entrada do diretório de bots para o P&L e a visão do P&L em dois blocos (PAPER e REAL), com a primeira linha do P&L REAL por bot. O bloco `yaml` abaixo é a fonte de verdade; o diagrama ER em `functional-spec.md` é derivado dele.

Convenções do bloco:
- `type` é o tipo lógico, independente de linguagem: `uuid`, `text`, `boolean`, `instant` (UTC), `integer`, `lamports` (inteiro com sinal em lamports, nunca ponto flutuante), `usd` (decimal exato), `percent` (decimal exato), `enum`, `list<…>`, `ref<…>`.
- `computed: true` marca uma entidade calculada na leitura e nunca gravada (ADR-004 do domain design).
- Os valores de enum ficam em inglês, iguais no contrato, no código e no banco (`team.md`, Code Style).

```yaml
entities:
  - name: Bot
    id: ENT-001
    owner: Bots
    computed: false
    description: >
      O bot da pessoa. A U1 não cria entidade nova aqui: ela fixa o modo na criação e faz o campo
      obsoleto mode valer sempre o mesmo que market. Já existe no código (tabela bot, coluna market
      com defesa no banco contra mudança).
    attributes:
      - { name: botId, type: uuid, required: true, unique: true }
      - { name: tenantId, type: uuid, required: true, constraints: "vem do contexto de autenticação, nunca do corpo" }
      - { name: userId, type: uuid, required: true, constraints: "vem do contexto de autenticação" }
      - { name: name, type: text, required: true, min: 1, max: 60, constraints: "aparado; 1 a 60 caracteres" }
      - { name: templateId, type: text, required: true, references: "StrategyTemplate (catálogo existente)" }
      - name: market
        type: enum
        required: true
        allowed: [PAPER, REAL]
        default: PAPER
        constraints: "escolhido na criação; PAPER quando o pedido não traz o campo; nunca muda depois (BR1.1, BR1.2)"
      - name: mode
        type: enum
        required: true
        allowed: [PAPER, REAL]
        constraints: "obsoleto; derivado, sempre igual a market em toda resposta (BR1.3, ADR-009); não é gravado à parte"
      - name: state
        type: enum
        required: true
        allowed: [DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING, STOPPED]
        constraints: "máquina de estados existente (BotState); a U1 não muda as transições"
      - { name: draftStep, type: integer, required: false, min: 1, constraints: "passo do assistente já salvo" }
      - { name: virtualBalance, type: usd, required: false, constraints: "só PAPER; um bot REAL não tem saldo virtual (BR1.8)" }
      - { name: limitsVersion, type: integer, required: false, min: 1, constraints: "só PAPER, a partir de VALIDATED; um bot REAL não tem limites próprios nem versão de limites e usa os limites REAL da conta (BR1.9)" }
      - { name: createdAt, type: instant, required: true }
      - { name: stoppedAt, type: instant, required: false, constraints: "preenchido em STOPPED" }
    entity_constraints:
      - "market é imutável depois da criação (FR1.4)"
      - "mode == market em toda resposta que traz um bot (FR2.4)"
      - "um bot REAL só nasce com a elegibilidade REAL disponível no instante do comando (BR2.3)"
      - "um bot REAL valida, ativa, roda o ciclo, pausa, retoma e para sem saldo virtual nem versão de limites própria (BR1.9, decisão do fundador)"
    relationships:
      - { to: BotDirectoryEntry, cardinality: "1 -> 0..1", direction: "Bot é projetado como entrada do diretório quando não é rascunho" }

  - name: RealEligibility
    id: ENT-002
    owner: RealCreationEligibility
    computed: true
    description: >
      Se a pessoa pode criar um bot REAL agora e, quando não pode, só o motivo mais forte. Calculada a
      cada leitura (C2) e a cada comando de criação REAL (C1), com a mesma regra.
    attributes:
      - { name: tenantId, type: uuid, required: true, constraints: "do contexto de autenticação" }
      - { name: userId, type: uuid, required: true, constraints: "do contexto de autenticação" }
      - { name: available, type: boolean, required: true }
      - name: reason
        type: enum
        required: false
        allowed: [KILL_SWITCH, REAL_DISABLED, WALLET_INACTIVE, NOT_RELEASED, QUARANTINE]
        constraints: "presente se e só se available = false; é o motivo mais forte pela ordem de BR2.1"
      - name: effectiveAt
        type: instant
        required: false
        constraints: "presente se e só se reason = QUARANTINE; instante UTC em que a liberação passa a valer (Q4)"
      - name: action
        type: enum
        required: false
        allowed: [ACTIVATE_BOT_WALLET]
        constraints: "presente se e só se reason = WALLET_INACTIVE"
    entity_constraints:
      - "available = true implica reason, effectiveAt e action ausentes"
      - "nunca autoriza operar; só libera a criação (BR2.6)"
    relationships:
      - { to: BotWallet, cardinality: "1 -> 0..1", direction: "lê a carteira do bot ativa da pessoa (botwallet.api)" }
      - { to: RealRelease, cardinality: "1 -> 0..1", direction: "lê a liberação da pessoa na rede do ambiente e o REAL ligado" }
      - { to: RealKillSwitch, cardinality: "1 -> 1", direction: "lê o estado atual do kill switch REAL" }

  - name: RealAccountLimits
    id: ENT-003
    owner: RealAccountLimitsRead
    computed: true
    description: >
      Os quatro limites REAL da conta, só para leitura, a partir das mesmas entradas da política de
      risco REAL (configuração da barreira e teto da carteira do bot). Substitui a forma de cinco
      campos de C3/C9 pela forma de quatro limites confirmada em Q6 e no mockup M3b.
    attributes:
      - { name: tenantId, type: uuid, required: true }
      - { name: userId, type: uuid, required: true }
      - { name: currency, type: enum, required: true, allowed: [SOL], default: SOL }
      - name: cap
        type: ref<LimitAmount>
        required: true
        constraints: "teto da carteira do bot em lamports; NOT_DEFINED quando não há teto (BR3.2)"
      - name: orderMax
        type: ref<LimitAmount>
        required: true
        constraints: "tamanho máximo da ordem: floor(teto x orderMaxBps / 10000) lamports e percentual do teto; NOT_DEFINED sem teto"
      - name: dailyBuys
        type: ref<LimitCount>
        required: true
        constraints: "compras aprovadas por bot, por dia UTC (contagem); sempre KNOWN, porque a configuração sempre tem o valor"
      - name: dailyLoss
        type: ref<LimitAmount>
        required: true
        constraints: "perda máxima do dia: floor(base x dailyLossMaxBps / 10000) lamports, com base = min(teto no início do dia UTC, teto atual), e o percentual; NOT_DEFINED sem teto"
    entity_constraints:
      - "os quatro limites sempre aparecem, com valor ou NOT_DEFINED; nunca zero no lugar de NOT_DEFINED"
      - "sem carteira do bot ativa não existe instância (a leitura responde 409 REAL_NOT_ELIGIBLE / WALLET_INACTIVE)"
      - "só leitura: não reserva, não muda limite, não grava auditoria"
    relationships:
      - { to: BotWallet, cardinality: "1 -> 1", direction: "o teto vem da carteira do bot ativa da pessoa" }

  - name: LimitAmount
    id: ENT-004
    owner: RealAccountLimitsRead
    computed: true
    description: "Valor de um limite em lamports, com o percentual do teto quando o limite é uma fração dele."
    attributes:
      - { name: status, type: enum, required: true, allowed: [KNOWN, NOT_DEFINED] }
      - { name: lamports, type: lamports, required: false, min: 0, constraints: "só em KNOWN; arredondado para baixo" }
      - { name: percentOfCap, type: percent, required: false, min: 0, max: 100, constraints: "só em KNOWN e só em orderMax e dailyLoss; bps / 100, exato" }
    entity_constraints:
      - "status = NOT_DEFINED implica lamports e percentOfCap ausentes"

  - name: LimitCount
    id: ENT-005
    owner: RealAccountLimitsRead
    computed: true
    description: "Limite em contagem (compras por bot, por dia UTC). Sempre conhecido."
    attributes:
      - { name: status, type: enum, required: true, allowed: [KNOWN] }
      - { name: count, type: integer, required: true, min: 0 }

  - name: BotDirectoryEntry
    id: ENT-006
    owner: Bots
    computed: true
    description: >
      Projeção mínima de um bot para o P&L, entregue pela porta BotDirectory (declarada em pnl.api,
      implementada pelo bots; C8, ADR-007). Rascunhos não aparecem (Q1).
    attributes:
      - { name: botId, type: uuid, required: true, unique: true }
      - { name: name, type: text, required: true }
      - { name: market, type: enum, required: true, allowed: [PAPER, REAL] }
      - { name: templateId, type: text, required: true }
      - name: status
        type: enum
        required: true
        allowed: [ACTIVE, PAUSED, REMOVED]
        constraints: "ACTIVE e CLOSING_PENDING -> ACTIVE; PAUSED -> PAUSED; STOPPED -> REMOVED; DRAFT e VALIDATED não geram entrada (BR4.1)"
      - { name: createdAt, type: instant, required: true }
    entity_constraints:
      - "sempre filtrada pelo tenant e pela pessoa do contexto de autenticação"
      - "ordem: createdAt, depois botId"
      - "campos mínimos: nada de parâmetros, limites ou saldo (ADR-007)"
    relationships:
      - { to: Bot, cardinality: "0..1 -> 1", direction: "projeção de um bot não rascunho" }
      - { to: PaperBotPnlLine, cardinality: "1 -> 0..1", direction: "um bot PAPER do diretório tem uma linha no bloco PAPER" }
      - { to: RealBotPnlLine, cardinality: "1 -> 0..1", direction: "um bot REAL do diretório tem uma linha no bloco REAL" }

  - name: PnlPeriod
    id: ENT-007
    owner: ModePnlView
    computed: true
    description: "Período único dos dois blocos. A U1 publica só ALL; a U3 acrescenta RANGE na requisição."
    attributes:
      - { name: kind, type: enum, required: true, allowed: [ALL, RANGE], default: ALL, constraints: "na U1, sempre ALL" }
      - { name: from, type: instant, required: false, constraints: "só em RANGE (U3)" }
      - { name: to, type: instant, required: false, constraints: "só em RANGE (U3)" }

  - name: ModePnlStatement
    id: ENT-008
    owner: ModePnlView
    computed: true
    description: >
      A visão do P&L por modo (resposta de C4): o período e dois blocos, um PAPER e um REAL. Não tem
      nenhum campo de total fora dos blocos.
    attributes:
      - { name: period, type: ref<PnlPeriod>, required: true }
      - { name: paper, type: "ref<PaperModeBlock> | ref<ModeBlockUnavailable>", required: true }
      - { name: real, type: "ref<RealModeBlock> | ref<ModeBlockUnavailable>", required: true }
    entity_constraints:
      - "tem só period, paper e real; nenhum total geral, ranking misturado ou diferença entre moedas (BR5.1)"
      - "cada bloco é calculado e falha de forma independente (BR5.3)"
    relationships:
      - { to: PnlPeriod, cardinality: "1 -> 1", direction: "um período para os dois blocos" }
      - { to: PaperModeBlock, cardinality: "1 -> 0..1", direction: "o bloco PAPER, ou UNAVAILABLE" }
      - { to: RealModeBlock, cardinality: "1 -> 0..1", direction: "o bloco REAL, ou UNAVAILABLE" }

  - name: PaperModeBlock
    id: ENT-009
    owner: PaperPnl
    computed: true
    description: "Bloco PAPER disponível: total do modo em US$ exato, partes, linhas dos bots PAPER e o aviso fixo."
    attributes:
      - { name: status, type: enum, required: true, allowed: [OK], constraints: "no contrato o valor é AVAILABLE (ver nota da suposição 1)" }
      - { name: market, type: enum, required: true, allowed: [PAPER] }
      - { name: currency, type: enum, required: true, allowed: [USD] }
      - { name: disclaimer, type: text, required: true, constraints: "sempre \"O resultado simulado não garante o resultado real.\" (BR6.1)" }
      - { name: empty, type: boolean, required: true, constraints: "true quando não há bot PAPER no diretório nem lançamento PAPER" }
      - { name: realized, type: usd, required: true }
      - { name: unrealized, type: "usd | UNKNOWN", required: true, constraints: "UNKNOWN com motivo, nunca zero" }
      - { name: unrealizedAsOf, type: instant, required: true }
      - { name: fees, type: usd, required: true, min: 0 }
      - { name: total, type: "usd | UNKNOWN", required: true, constraints: "realized + unrealized - fees; UNKNOWN quando unrealized é UNKNOWN (BR5.4)" }
      - { name: bots, type: list<PaperBotPnlLine>, required: true, constraints: "ordem de criação, nunca por valor" }
    entity_constraints:
      - "só valores PAPER; nada de um bot REAL entra em nenhum campo (BR5.2)"
      - "sem linha \"Sem bot\" na U1: a linha \"Sem bot\" do PAPER vale US$ 0,00 na conferência (BR5.10)"

  - name: PaperBotPnlLine
    id: ENT-010
    owner: PaperPnl
    computed: true
    description: "P&L PAPER de um bot, igual ao de hoje (FR3.1)."
    attributes:
      - { name: botId, type: uuid, required: true }
      - { name: name, type: text, required: true }
      - { name: botStatus, type: enum, required: true, allowed: [ACTIVE, PAUSED, REMOVED] }
      - { name: realized, type: usd, required: true }
      - { name: unrealized, type: "usd | UNKNOWN", required: true }
      - { name: fees, type: usd, required: true, min: 0 }
      - { name: total, type: "usd | UNKNOWN", required: true, constraints: "realized + unrealized - fees" }
    relationships:
      - { to: BotDirectoryEntry, cardinality: "0..1 -> 1", direction: "a linha é de um bot PAPER do diretório" }

  - name: RealModeBlock
    id: ENT-011
    owner: RealBotPnl
    computed: true
    description: >
      Bloco REAL disponível, em lamports. Na U1: as linhas por bot vêm só das ordens de cada bot, o total
      do modo é o total da pessoa de hoje, unowned é nulo e prorated é vazio (Q7).
    attributes:
      - { name: status, type: enum, required: true, allowed: [OK], constraints: "no contrato o valor é AVAILABLE (ver suposição 1)" }
      - { name: market, type: enum, required: true, allowed: [REAL] }
      - { name: currency, type: enum, required: true, allowed: [SOL] }
      - { name: empty, type: boolean, required: true, constraints: "true quando não há bot REAL no diretório nem ordem REAL assentada" }
      - { name: realizedLamports, type: lamports, required: true, constraints: "na U1, líquido de taxas; a partir da U2, bruto (BR5.5, nota de C10)" }
      - { name: unrealizedLamports, type: "lamports | UNKNOWN", required: true, constraints: "UNKNOWN com motivo PRICE_UNKNOWN, STALE ou NO_LIQUIDITY; nunca zero" }
      - { name: unrealizedAsOf, type: instant, required: true }
      - { name: feesLamports, type: lamports, required: true, min: 0, constraints: "na U1, só informação: já incluídas no realizado e no custo" }
      - { name: totalLamports, type: "lamports | UNKNOWN", required: true, constraints: "na U1, realized + unrealized (BR5.5)" }
      - { name: bots, type: list<RealBotPnlLine>, required: true, constraints: "ordem de criação, nunca por valor" }
      - { name: unowned, type: "UnownedPnlLine | null", required: true, constraints: "null na U1 (o Optional vazio de C10); preenchido a partir da U2" }
    entity_constraints:
      - "só valores REAL; nada PAPER (BR5.2)"
      - "nenhum endereço de carteira (BR5.8)"
      - "na U1, a soma das linhas pode diferir do total quando há liquidação, condicional ou excedente (RK-1); por isso o bloco REAL não vazio mostra a nota fixa de BR5.5; a invariante do FR5.3 só vale a partir da U2"

  - name: RealBotPnlLine
    id: ENT-012
    owner: RealBotPnl
    computed: true
    description: >
      P&L REAL de um bot. Na U1 é a primeira implementação atrás de RealBotPnlQuery (C10, dona U2): o
      cálculo por ordens que já existe aplicado só às ordens assentadas cujo bot é este bot.
    attributes:
      - { name: botId, type: uuid, required: true }
      - { name: name, type: text, required: true }
      - { name: botStatus, type: enum, required: true, allowed: [ACTIVE, PAUSED, REMOVED] }
      - { name: realizedLamports, type: lamports, required: true, constraints: "na U1, líquido de taxas; a partir da U2, bruto" }
      - { name: unrealizedLamports, type: "lamports | UNKNOWN", required: true }
      - { name: feesLamports, type: lamports, required: true, min: 0, constraints: "na U1, só informação" }
      - { name: totalLamports, type: "lamports | UNKNOWN", required: true, constraints: "na U1, realized + unrealized (BR5.5)" }
      - { name: prorated, type: list, required: true, constraints: "sempre vazia na U1 (summary e lines vazios, count 0)" }
    entity_constraints:
      - "considera só ordens REAL com o botId deste bot; ordens sem bot (liquidação) ficam fora das linhas na U1"
      - "inteiros em lamports; nenhum ponto flutuante; nenhum endereço de carteira"
    relationships:
      - { to: BotDirectoryEntry, cardinality: "0..1 -> 1", direction: "a linha é de um bot REAL do diretório" }

  - name: ModeBlockUnavailable
    id: ENT-013
    owner: ModePnlView
    computed: true
    description: "Um bloco cujo cálculo falhou; o outro bloco continua na mesma resposta (Q5 do contract design)."
    attributes:
      - { name: status, type: enum, required: true, allowed: [UNAVAILABLE] }
      - { name: market, type: enum, required: true, allowed: [PAPER, REAL] }
      - { name: currency, type: enum, required: true, allowed: [USD, SOL], constraints: "USD com PAPER, SOL com REAL" }
      - { name: code, type: text, required: true, constraints: "código legível por máquina, por exemplo SERVICE_UNAVAILABLE" }
      - { name: correlationId, type: text, required: true }
    entity_constraints:
      - "nunca traz valor monetário"
```

## Resumo

São 13 entidades, todas com dono no catálogo de componentes:

| ID | Entidade | Dono | Gravada? | Papel na U1 |
|---|---|---|---|---|
| ENT-001 | Bot | Bots | sim (já existe) | `market` fixo na criação, PAPER quando ausente; `mode` obsoleto igual a `market` |
| ENT-002 | RealEligibility | RealCreationEligibility | não | disponibilidade do REAL com só o motivo mais forte e o `effectiveAt` da quarentena |
| ENT-003 | RealAccountLimits | RealAccountLimitsRead | não | teto, ordem, compras por dia e perda do dia, só para leitura |
| ENT-004 | LimitAmount | RealAccountLimitsRead | não | valor em lamports e percentual do teto, ou `NOT_DEFINED` |
| ENT-005 | LimitCount | RealAccountLimitsRead | não | contagem, ou `NOT_DEFINED` |
| ENT-006 | BotDirectoryEntry | Bots | não | projeção mínima do bot para o P&L, com o estado reduzido a ACTIVE, PAUSED ou REMOVED |
| ENT-007 | PnlPeriod | ModePnlView | não | período único; só `ALL` na U1 |
| ENT-008 | ModePnlStatement | ModePnlView | não | resposta de C4: período e dois blocos, sem total geral |
| ENT-009 | PaperModeBlock | PaperPnl | não | bloco PAPER em US$, com o aviso fixo |
| ENT-010 | PaperBotPnlLine | PaperPnl | não | P&L PAPER de um bot, como hoje |
| ENT-011 | RealModeBlock | RealBotPnl | não | bloco REAL em lamports; `unowned` nulo na U1 |
| ENT-012 | RealBotPnlLine | RealBotPnl | não | P&L REAL de um bot pelas ordens do próprio bot (primeira fatia) |
| ENT-013 | ModeBlockUnavailable | ModePnlView | não | bloco que falhou, sem valor, com código e `correlationId` |

A U1 não grava nenhum dado novo. A única entidade gravada é o Bot, que já existe com `market` e a defesa no banco contra mudança de modo. Todas as outras são calculadas na leitura.

## Sources

- `functional-design-questions.md`: Q1 (estados no P&L), Q2 (409 para os cinco motivos), Q3 (ordem dos motivos e conferência dupla), Q4 (`effectiveAt`), Q5 (formatos), Q6 (quatro limites), Q7 (bloco REAL na U1), Q8 (semente de teste) e o resumo confirmado.
- `inception/units-generation/unit-of-work.md`: seção U1, "Componentes mudados por mais de uma Unit" (RealBotPnl, ModePnlView, PnlModeBlock) e RK-1.
- `inception/domain-design/components.md`: Bots, RealCreationEligibility, RealAccountLimitsRead, ModePnlView, PaperPnl, RealBotPnl, BotWallet e a tabela "Entity Ownership".
- `inception/domain-design/decisions.md`: ADR-003, ADR-004, ADR-005, ADR-007, ADR-008, ADR-009.
- `inception/contract-design/contract-summary.md`: formatos comuns, C1, C2, C3, C4, C8, C9, C10.
- `inception/refined-mockups/mockups.md`: M1.3 (motivos), M3b (quatro limites), decisão D1.
- Código lido: `bots.api.BotState`, `bots.domain.BotMarket`, `bots.web.BotDtos.CreateBotRequest`, `bots.application.BotSummaryView` (`mode` fixo em `PAPER`), `botwallet.api.RealReleaseInfo` (estados `NONE`, `QUARANTINE`, `ACTIVE`, `BLOCKED`, `REVOKED`), `botwallet.api.RealStateView`, `botwallet.api.RealKillSwitchState`, `riskcontrol.real.domain.RealRiskLimits` (`orderMaxBps`, `dailyLossMaxBps`, `dailyBuysMax`), `pnl.real.RealPnlCalculator`, a migração `0019-real-execution.sql` (`bot_market_immutable`).

## Assumptions & Open Questions

1. [assumption] O status de um bloco disponível aparece aqui como `OK`, como pediu o resumo da tarefa, mas o valor no contrato C4 é `AVAILABLE`. O valor que vale no fio é o do contrato (`AVAILABLE`); `OK` é só o nome lógico. Nenhuma mudança de contrato.
2. **Decidido (fundador, 2026-11-21):** os valores de `reason` são os do contrato C1/C2, `NOT_RELEASED` e `QUARANTINE`, no lugar dos rótulos `RELEASE_MISSING` e `RELEASE_QUARANTINE` do texto da Q2 e da Q4. `NOT_RELEASED` cobre a liberação `NONE`, `BLOCKED` e `REVOKED`. Questão fechada.
5. **Decidido (fundador, 2026-11-21, revisão R-01):** um bot REAL não tem limites próprios nem versão de limites e usa só os limites REAL da conta, que a barreira REAL confere antes de cada ordem (BR1.9).
3. [assumption] RealAccountLimits tem a forma de quatro limites da Q6 e do M3b. O C3/C9 do contract design tem cinco campos (`orderLimitLamports`, `tokenExposureLimitLamports`, `totalExposureLimitLamports`, `operatingCapLamports`, `dailyLossPercent`), dois dos quais não existem na política de risco REAL do código. Como a U1 é a dona de C3 e C9 e a rota é nova (nada publicado), a U1 ajusta os dois contratos a esta forma no mesmo commit da rota.
4. [assumption] O teto da carteira do bot é gravado como inteiro não negativo e nunca é nulo no código. Um teto igual a zero é tratado como "não definido", porque com ele a política recusa toda compra e o M3b pede "não definido" sem teto.
