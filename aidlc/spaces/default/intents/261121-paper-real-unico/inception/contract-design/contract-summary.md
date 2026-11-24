# Contratos — paper-real-unico

Conversa: português (Brasil). Data: 2026-11-21.

Este documento fixa os contratos do intent: as fronteiras entre as Units `u1-mode-skeleton`, `u2-real-bot-pnl`, `u3-pnl-views` e `u4-own-quantity-cycle`, e a fronteira externa com o `apps/web`. Cada contrato tem a sua especificação num bloco `yaml`:
- **OpenAPI** para as rotas REST do contrato guest;
- **shared-schema** para as interfaces Java públicas entre módulos (correção do `project.md` para o Contract Design deste monólito modular) e para a migração do `slot`.

Nenhum evento novo entra, então o AsyncAPI não muda [Q2].

## Sources

- `contract-design-questions.md`: Q1 a Q6 e o resumo confirmado com "Looks correct". São as decisões que vinculam este documento.
- `inception/units-generation/unit-of-work.md` e `unit-of-work-dependency.md`: as quatro Units, o grafo U1 → U2 → {U3, U4} e a tabela "Pontos de integração".
- `inception/domain-design/components.md` (catálogo e posse das entidades) e `decisions.md` (ADR-001 a ADR-011).
- `inception/requirements-analysis/requirements.md`: FR1 a FR8 e NFR1 a NFR8.
- `inception/user-stories/stories.md`: US1.1 a US8.1 e os critérios AC citados.
- `inception/refined-mockups/interaction-spec.md` e `mockups.md` (OQ1 a OQ7).
- O código atual, lido para os deltas caberem no que existe:
  - `apps/api/openapi/openapi-guest.yaml`: `createBot`, `listBots`, `getBot`, `getPnl`, `getRealPnl` e os esquemas `CreateBotRequest`, `BotSummary`, `BotDetail`, `BotPage`, `Problem`, `PnlAmount`, `PnlKnown`, `PnlUnknown`, `RealPnl` e `RealTokenAmount`;
  - `apps/api/src/test/java/com/memetrade/contract/GuestOpenApiContractTest.java`: o OpenAPI gerado do código precisa ser igual ao arquivo versionado;
  - as interfaces públicas `pnl.api.PortfolioRiskView`, `ledger.api.RealLedger`, `RealEntryRecord`, `RealPosition`, `realexecution.api.RealExecutionPort`, `RealOrderView`, `RealDecision`, `RealOrderState`, `riskcontrol.api.RealRiskGate` e `bots.api.RealBotOwnership`;
  - `bots.application.BotCommandService.create` (hoje recusa o REAL sem carteira ativa com 422 `VALIDATION_FAILED`);
  - `shared.error.ErrorCode` e `ApiException` (o `Problem` já tem `code`, `reason` e `correlationId`);
  - as migrações Liquibase em `apps/api/src/main/resources/db/changelog/changes/`, de `0001` a `0022`, em especial `0016-real-bot-wallet.sql` (`real_ledger_entry`, gatilho append-only e revogação do UPDATE) e `0019-real-execution.sql` (`real_order`);
  - `realexecution.infra.RealExecutionPurgeJob` (expurgo das ordens finais depois de 5 anos).
- `aidlc/spaces/default/memory/project.md`: NEVER somar os modos; ALWAYS separar REAL e PAPER; ALWAYS validar o tenant; ALWAYS ledger append-only; NEVER ponto flutuante; correção do Contract Design (interfaces Java como esquema compartilhado).
- `aidlc/spaces/default/memory/team.md`: Code Style (erros RFC 7807, `unknown` como tipo explícito, valores do REAL em inteiros, contrato gerado), Testing Posture (testes obrigatórios por risco) e Deployment (expand-contract).

## Tabela de contratos

| # | Provider Unit | Consumer | Mechanism | Owner |
|---|---|---|---|---|
| C1 | U1 `u1-mode-skeleton` | External: apps/web | REST/OpenAPI: `POST /api/v1/bots` com `market`, 409 `REAL_NOT_ELIGIBLE`; `mode` obsoleto em `BotSummary` e `BotDetail` | U1 |
| C2 | U1 `u1-mode-skeleton` | External: apps/web | REST/OpenAPI: `GET /api/v1/bots/real-eligibility` | U1 |
| C3 | U1 `u1-mode-skeleton` | External: apps/web | REST/OpenAPI: `GET /api/v1/real/account-limits` | U1 |
| C4 | U1 `u1-mode-skeleton` (U2 preenche o bloco REAL; U3 acrescenta o período) | External: apps/web | REST/OpenAPI: `GET /api/v1/pnl/by-mode?period=` | U1 (forma); U2 (linhas rateadas e "Sem bot"); U3 (parâmetro `period`) |
| C5 | U3 `u3-pnl-views` | External: apps/web | REST/OpenAPI: `GET /api/v1/bots/{botId}/pnl/lines` | U3 |
| C6 | U3 `u3-pnl-views` | External: apps/web | REST/OpenAPI: `GET /api/v1/pnl/compare` | U3 |
| C7 | U4 `u4-own-quantity-cycle` | External: apps/web (pela lista `/bots` da U1) | REST/OpenAPI: campo aditivo em `BotPage` com o estado da venda da quantidade própria (FR6.2) | U4 |
| C8 | U1 `u1-mode-skeleton` (o `bots` implementa) | U2 (RealBotPnl), U4 (RealBotHoldings), U1 (ModePnlView) | Shared schema: porta Java `pnl.api.BotDirectory` | U1 |
| C9 | U1 `u1-mode-skeleton` (`riskcontrol.real`) | U1 (rota do assistente no `bots`) | Shared schema: interface Java `riskcontrol.api.RealAccountLimits` | U1 |
| C10 | U2 `u2-real-bot-pnl` (a U1 entrega a primeira implementação) | U1 (ModePnlView), U3 (linhas, período e comparação) | Shared schema: interface Java `pnl.api.RealBotPnlQuery` | U2 |
| C11 | U2 `u2-real-bot-pnl` (o RealCostBasis implementa) | Código existente: `RealLedger` (posições e `view`), a barreira REAL do 261113; U4 pelo mesmo algoritmo | Shared schema: porta Java `ledger.api.RealCostMethod` | U2 |
| C12 | U2 `u2-real-bot-pnl` (RealLedger) | U2 (RealBotPnl), U4 (RealBotHoldings) | Shared schema: leitura em fluxo `ledger.api.RealLedger.streamInNetworkOrder` com a chave (slot, assinatura) | U2 |
| C13 | U2 `u2-real-bot-pnl` (RealExecution) | U2 (RealBotPnl), U4 (RealBotHoldings) | Shared schema: `realexecution.api` com a gravação do slot na confirmação e a leitura em fluxo das ordens assentadas | U2 |
| C14 | U2 `u2-real-bot-pnl` | Banco (tabela nova do módulo `ledger`) | Shared schema: migração aditiva `0023-real-entry-slot.sql`, tabela lateral `real_entry_slot`, só de inserção | U2 |
| C15 | U4 `u4-own-quantity-cycle` | U4 (RealCycle em `bots.worker`) | Shared schema: interface Java `pnl.api.RealBotHoldings` com falha fechada e orçamento de tempo | U4 |
| C16 | U4 `u4-own-quantity-cycle` (RealExecution) | U4 (RealBotHoldings) | Shared schema: leitura das vendas em aberto `realexecution.api.RealExecutionPort.openSells` | U4 |
| C17 | U1 `u1-mode-skeleton` | U3 (painel e comparação) | Shared schema: props TypeScript de `PnlModeBlock` e `SimulatedBadge` | U1 |

São 17 contratos: 7 com o consumidor externo `apps/web` (C1 a C7), 9 entre módulos ou Units no backend (C8 a C16) e 1 entre componentes web (C17). Todos seguem os donos da Q3 e o mecanismo da Q2.

Regras que valem em todos os contratos:
- O tenant e a pessoa vêm sempre do token, nunca do corpo nem da consulta. Um bot de outra pessoa responde 404 `NOT_FOUND` e nunca devolve dado (NFR3, AC3.10.3, AC8.1.4). Nota da U3 (alinhamento A2, decisão F-2 do NFR Design): a única consulta fora do tenant do token é `BotDirectory.ownedByAnotherTenant` (C8), só booleana e só no caminho dos 404 de C5 e C6, para a auditoria da recusa; ela nunca muda a resposta.
- Nenhuma resposta, interface ou tipo carrega um total que some PAPER e REAL (FR4.1, ADR-003, `project.md`).
- As rotas novas não estão na lista de exceções do novo aceite dos termos. Com uma versão nova dos termos pendente, elas respondem `403 TERMS_REACCEPTANCE_REQUIRED`, como as demais (`team.md`, Testing Posture).
- **Entrada inválida segue a convenção do código (revisão R-04).** A convenção é a de `shared.error.ErrorCode` e do `BotRoutesIT`. Requisição malformada (parâmetro de consulta inválido, período incoerente, campo desconhecido, cabeçalho ausente) responde **400** `VALIDATION_FAILED` (`ErrorCode.VALIDATION_FAILED`). É o que `GET /api/v1/pnl` já faz para `from >= to` e período acima de 366 dias. Uma requisição bem formada cujo valor quebra uma regra de negócio, num campo do corpo ou num par de ids, responde **422** `VALIDATION_FAILED` (`UNPROCESSABLE_FIELDS` ou `BOT_VALIDATION_FAILED`). Por isso C4, C5 e C6 usam 400 para o período, o cursor e o `limit`, e C1 (campo do corpo) e C6 (`TEMPLATE_MISMATCH`) usam 422.
- O `openapi-guest.yaml` é gerado do código e comparado pelo `GuestOpenApiContractTest`. Os blocos abaixo são o alvo: o arquivo gerado depois da mudança precisa conter esses deltas, na ordem e no formato que o gerador produz.

## Formatos comuns

Os esquemas abaixo são usados por C1 a C7. Eles seguem o que o contrato já tem: valores REAL em texto inteiro, como `RealPnl.realizedLamports`, e o `unknown` como união discriminada por `status`, como `PnlAmount` [Q6].

```yaml
# OpenAPI 3.1 — components/schemas novos e comuns (contrato guest)
components:
  schemas:
    Lamports:
      type: string
      description: "Lamports em texto inteiro com sinal; nunca número JSON nem ponto flutuante [Q6]"
      pattern: "^-?[0-9]{1,20}$"
    BaseUnits:
      type: string
      description: "Quantidade de token em unidades base, texto inteiro sem sinal [Q6]"
      pattern: "^[0-9]{1,30}$"
    LamportsKnown:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [KNOWN]
        value:
          $ref: "#/components/schemas/Lamports"
      required: [status, value]
    LamportsUnknown:
      type: object
      additionalProperties: false
      description: "Valor desconhecido; nunca zero (FR3.5, FR3.10)"
      properties:
        status:
          type: string
          enum: [UNKNOWN]
        reason:
          type: string
          enum: [PRICE_UNKNOWN, STALE, NO_LIQUIDITY]
      required: [status, reason]
    LamportsAmount:
      discriminator:
        propertyName: status
        mapping:
          KNOWN: "#/components/schemas/LamportsKnown"
          UNKNOWN: "#/components/schemas/LamportsUnknown"
      oneOf:
        - $ref: "#/components/schemas/LamportsKnown"
        - $ref: "#/components/schemas/LamportsUnknown"
    # PnlAmount, PnlKnown e PnlUnknown (US$ em decimal exato) já existem e são reaproveitados sem mudança.
    PnlPeriod:
      type: object
      additionalProperties: false
      description: "Período do P&L: ALL é desde o início; RANGE é [from, to) em UTC (FR7.1, FR7.2)"
      properties:
        kind:
          type: string
          enum: [ALL, RANGE]
        from:
          type: string
          format: date-time
          description: "Só em RANGE"
        to:
          type: string
          format: date-time
          description: "Só em RANGE"
      required: [kind]
    BotPnlStatus:
      type: string
      description: "Estado do bot no P&L (FR3.11)"
      enum: [ACTIVE, PAUSED, REMOVED]
    ModeBlockUnavailable:
      type: object
      additionalProperties: false
      description: "Um modo que falhou; o outro bloco continua na mesma resposta [Q5]"
      properties:
        status:
          type: string
          enum: [UNAVAILABLE]
        market:
          type: string
          enum: [PAPER, REAL]
        currency:
          type: string
          enum: [USD, SOL]
        code:
          type: string
          description: "Código legível por máquina, por exemplo SERVICE_UNAVAILABLE"
        correlationId:
          type: string
      required: [status, market, currency, code, correlationId]
```

**Discriminador `status` (decisão do fundador, 2026-11-21, PQ-1).** A Q6 escreveu o exemplo `{ "kind": "UNKNOWN" }`, mas o contrato atual já discrimina o `unknown` por `status` (`PnlAmount`, `MarketValue`), e a especificação de interação lê `PnlAmount.status = 'UNKNOWN'`. O fundador aprovou `status`, para o contrato ter uma só convenção. A decisão da Q6 (união discriminada, nunca zero) continua valendo.

## C1 — Criação no modo e `mode` obsoleto

Fontes: [Q1], [Q4], [Q5], FR1.1 a FR1.4, FR2.4, US1.1 a US1.4, US2.4 (AC2.4.1 a AC2.4.4), ADR-005, ADR-009.

O que já existe: `CreateBotRequest.market` (`PAPER` | `REAL`, `PAPER` quando ausente) e o cabeçalho `Idempotency-Key` obrigatório. O delta é:
- a recusa REAL passa a cobrir os cinco motivos, com 409 `REAL_NOT_ELIGIBLE` e o motivo mais forte em `reason`;
- a falha fechada vira 503;
- `mode` passa a valer o mesmo que `market` e fica `deprecated`.

```yaml
# OpenAPI 3.1 — delta em paths./api/v1/bots.post e nos esquemas do bot
paths:
  /api/v1/bots:
    post:
      operationId: createBot
      # parâmetros e requestBody sem mudança (Idempotency-Key uuid obrigatório; CreateBotRequest)
      responses:
        "201":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/BotSummary"
          description: Rascunho criado no modo escolhido
        "409":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "REAL_NOT_ELIGIBLE: market = REAL e a pessoa não pode criar bot REAL; reason traz o motivo mais forte (KILL_SWITCH, REAL_DISABLED, WALLET_INACTIVE, NOT_RELEASED ou QUARANTINE). Também IDEMPOTENCY_CONFLICT, como hoje"
        "422":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "VALIDATION_FAILED: nome, templateId ou market inválido"
        "503":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "SERVICE_UNAVAILABLE: a elegibilidade do REAL não pôde ser lida; falha fechada, nenhum bot é criado"
components:
  schemas:
    CreateBotRequest:
      properties:
        market:
          type: string
          enum: [PAPER, REAL]
          description: "Modo do bot, fixo depois da criação (FR1.4). O app novo sempre envia; ausente vale PAPER, para os clientes antigos [Q1]"
    BotSummary:
      properties:
        mode:
          type: string
          enum: [PAPER, REAL]
          deprecated: true
          description: "Obsoleto: sempre igual a market (FR2.4, ADR-009). Leia market"
    BotDetail:
      properties:
        mode:
          type: string
          enum: [PAPER, REAL]
          deprecated: true
          description: "Obsoleto: sempre igual a market (FR2.4, ADR-009). Leia market"
    Problem:
      properties:
        reason:
          type: string
          description: "Motivo legível por máquina. Em REAL_NOT_ELIGIBLE: KILL_SWITCH, REAL_DISABLED, WALLET_INACTIVE, NOT_RELEASED ou QUARANTINE"
```

Comportamento:
- **Precedência do motivo:** KILL_SWITCH, depois REAL_DISABLED, WALLET_INACTIVE, NOT_RELEASED e QUARANTINE (ADR-005, mockups Q7). `NOT_RELEASED` cobre a liberação `NONE`, `BLOCKED` e `REVOKED`. É a mesma função pura usada por C2.
- **409 para todo motivo (decisão do fundador, 2026-11-21, PQ-5):** os cinco motivos respondem 409 `REAL_NOT_ELIGIBLE`, inclusive a carteira inativa, que hoje responde 422 `VALIDATION_FAILED` no campo `market`. O 422 fica só para campo inválido. O ADR-005 (que fala em 422) é alinhado no Functional Design da U1; o `decisions.md` não muda nesta etapa.
- **Erro de domínio, sem repetição:** depois de um 409 `REAL_NOT_ELIGIBLE`, a tela relê a elegibilidade (C2) [Q5]. A recusa é auditada, como todo erro de domínio (`team.md`, Erros da API).
- **Repetição segura:** repetir com a mesma `Idempotency-Key` e o mesmo corpo devolve a mesma resposta e nunca cria um segundo bot (mecanismo comum de idempotência do módulo de identidade).
- **`mode == market`:** um teste de contrato confere a igualdade nos dois mercados, em toda resposta que traz um bot (AC2.4.1 a AC2.4.3).

**Mudança de resposta que quebra o parser atual (revisão R-01).** Alargar `BotSummary.mode` de `[PAPER]` para `[PAPER, REAL]`, e dar o enum a `BotDetail.mode`, **não é só aditivo**. É uma mudança de resposta conhecida: hoje o painel lê o resumo do bot em `apps/web/src/features/dashboard/envelopes.ts` com `oneOf(raw.mode, ['PAPER'])`, que lança `InvalidEnvelope` para `REAL`. Assim que um bot REAL devolver `mode: REAL`, o parser atual recusa o envelope inteiro. O único cliente é o `apps/web`, do mesmo repositório, e a mudança é aceita pelo ADR-009 sob as obrigações abaixo.

Obrigações da U1, no **mesmo commit** da mudança do contrato e da API:
1. atualizar `apps/web/src/features/dashboard/envelopes.ts` para `oneOf(raw.mode, ['PAPER', 'REAL'])` (ou parar de ler `mode` e ler `market`, conforme o ADR-009);
2. atualizar todo parser, fixture e teste do `apps/web` que fixa `mode: 'PAPER'` (entre eles as fixtures de `features/bots`, `features/pnl` e `features/dashboard`);
3. acrescentar um teste de contrato no `apps/web` que confere que o resumo de um bot REAL (`market: REAL`, `mode: REAL`) é aceito pelo parser do painel e pelos tipos gerados;
4. manter o teste de contrato do backend de `mode == market`.

Sem essas quatro mudanças no mesmo commit, a CI fica vermelha (`frontend`, `contract`) e o commit não serve para ponto de controle (`project.md`, ALWAYS só commit com a CI verde).

## C2 — Elegibilidade do REAL

Fontes: [Q1], [Q5], FR1.2, US1.2, ADR-005, OQ2, OQ3.

```yaml
# OpenAPI 3.1 — rota nova
paths:
  /api/v1/bots/real-eligibility:
    get:
      operationId: getRealEligibility
      summary: "Se a pessoa pode criar um bot REAL agora, com o motivo mais forte quando não pode (FR1.2, ADR-005)"
      tags: [bot-controller]
      security:
        - accessToken: []
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/RealEligibility"
          description: A elegibilidade
        "503":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "SERVICE_UNAVAILABLE: o estado do REAL não pôde ser lido; a tela mantém Real desabilitada (falha fechada)"
components:
  schemas:
    RealEligibility:
      type: object
      additionalProperties: false
      properties:
        available:
          type: boolean
        reason:
          type: string
          description: "Só quando available = false: o motivo mais forte"
          enum: [KILL_SWITCH, REAL_DISABLED, WALLET_INACTIVE, NOT_RELEASED, QUARANTINE]
        effectiveAt:
          type: string
          format: date-time
          description: "Só em QUARANTINE: o instante UTC em que a liberação passa a valer (OQ2)"
        action:
          type: string
          description: "Só em WALLET_INACTIVE: a ação que a tela oferece"
          enum: [ACTIVATE_BOT_WALLET]
      required: [available]
```

Comportamento: leitura idempotente, pode ser repetida. A elegibilidade só libera a criação e nunca autoriza operar; a barreira REAL, a quarentena de 24 horas e o TxSigner continuam valendo em cada ordem (ADR-005).

## C3 — Limites REAL da conta

Fontes: [Q1], [Q6], ADR-008, OQ6, US1.1, US1.3.

```yaml
# OpenAPI 3.1 — rota nova
paths:
  /api/v1/real/account-limits:
    get:
      operationId: getRealAccountLimits
      summary: "Limites REAL da conta, só leitura, em lamports arredondados para baixo, iguais aos da barreira (ADR-008)"
      tags: [bot-controller]
      security:
        - accessToken: []
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/RealAccountLimitsView"
          description: Os limites
        "409":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "REAL_NOT_ELIGIBLE com reason WALLET_INACTIVE: sem carteira do bot ativa não há limite a mostrar"
        "503":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "SERVICE_UNAVAILABLE: a leitura falhou"
components:
  schemas:
    LimitLamports:
      discriminator:
        propertyName: status
        mapping:
          KNOWN: "#/components/schemas/LimitLamportsKnown"
          UNKNOWN: "#/components/schemas/LimitUnknown"
      oneOf:
        - $ref: "#/components/schemas/LimitLamportsKnown"
        - $ref: "#/components/schemas/LimitUnknown"
    LimitLamportsKnown:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [KNOWN]
        value:
          type: string
          pattern: "^[0-9]{1,20}$"
      required: [status, value]
    LimitPercent:
      discriminator:
        propertyName: status
        mapping:
          KNOWN: "#/components/schemas/LimitPercentKnown"
          UNKNOWN: "#/components/schemas/LimitUnknown"
      oneOf:
        - $ref: "#/components/schemas/LimitPercentKnown"
        - $ref: "#/components/schemas/LimitUnknown"
    LimitPercentKnown:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [KNOWN]
        value:
          type: string
          description: "Percentual em decimal exato"
          pattern: "^\\d+(\\.\\d+)?$"
      required: [status, value]
    LimitUnknown:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [UNKNOWN]
        reason:
          type: string
          enum: [NOT_DEFINED]
      required: [status, reason]
    RealAccountLimitsView:
      type: object
      additionalProperties: false
      properties:
        currency:
          type: string
          enum: [SOL]
        orderLimitLamports:
          $ref: "#/components/schemas/LimitLamports"
        tokenExposureLimitLamports:
          $ref: "#/components/schemas/LimitLamports"
        totalExposureLimitLamports:
          $ref: "#/components/schemas/LimitLamports"
        operatingCapLamports:
          $ref: "#/components/schemas/LimitLamports"
        dailyLossPercent:
          $ref: "#/components/schemas/LimitPercent"
      required: [currency, orderLimitLamports, tokenExposureLimitLamports, totalExposureLimitLamports, operatingCapLamports, dailyLossPercent]
```

Comportamento: só leitura; nenhum limite muda por esta rota. Os números vêm de C9, que usa as mesmas entradas da `RealRiskPolicy`.

## C4 — P&L por modo (`by-mode`)

Fontes: [Q1], [Q3], [Q4], [Q5], [Q6], FR2.2, FR3.1 a FR3.11, FR4.1 a FR4.3, FR5.3, FR7.1, FR7.2, US3.1 a US3.9, US4.1 a US4.4, US7.1, US7.2, NFR1, NFR3, ADR-003, ADR-004, ADR-007.

A forma desta resposta é a definitiva desde a U1 [Q4]:
- **U1:** entrega a rota, o período `ALL`, o bloco PAPER completo e o bloco REAL com as linhas por bot. Até a U2, `prorated` vem vazio em cada bot e `unowned` vem `null`.
- **U2:** preenche `prorated` e `unowned` e passa o total REAL a ser o do RealBotPnl. É dona do formato das linhas rateadas [Q3].
- **U3:** acrescenta `RANGE` ao parâmetro `period` e os parâmetros `from` e `to` [Q3]. São mudanças aditivas na requisição. A resposta já traz `PnlPeriod` com `ALL | RANGE` desde a U1.
- **U3, alinhamento A2 (BR3.1; PQ-6 fechada pela Q1 do Functional Design da U3):** acrescenta o parâmetro opcional `market` (`PAPER` ou `REAL`; outro valor dá 400 `VALIDATION_FAILED`). Com `market`, a resposta traz só o bloco pedido e a chave do outro fica **ausente** (nunca `null`); por isso `paper` e `real` saíram de `required` no esquema. Sem `market`, os dois blocos vêm como antes. O "Tentar de novo" de um bloco relê C4 com o `market` desse bloco. O `apps/admin` não consome C4. Implementado no `PnlController.byMode` e no `openapi-guest.yaml` gerado.

```yaml
# OpenAPI 3.1 — rota nova
paths:
  /api/v1/pnl/by-mode:
    get:
      operationId: getPnlByMode
      summary: "P&L em dois blocos separados, PAPER (US$) e REAL (SOL), no mesmo período; nunca somados (FR4.1, ADR-003)"
      tags: [pnl-controller]
      security:
        - accessToken: []
      parameters:
        - in: query
          name: period
          required: false
          schema:
            type: string
            enum: [ALL, RANGE]   # U1 publica só ALL; U3 acrescenta RANGE
            default: ALL
        - in: query
          name: from               # U3
          required: false
          description: "Obrigatório em RANGE; instante UTC inicial, incluído"
          schema:
            type: string
            format: date-time
        - in: query
          name: to                 # U3
          required: false
          description: "Obrigatório em RANGE; instante UTC final, excluído"
          schema:
            type: string
            format: date-time
        - in: query
          name: market             # U3 (alinhamento A2, BR3.1)
          required: false
          description: "Só o bloco deste modo; a chave do outro fica ausente"
          schema:
            type: string
            enum: [PAPER, REAL]
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/PnlByMode"
          description: "Os dois blocos (ou só o pedido com market); um modo que falhou vem com status UNAVAILABLE sem derrubar o outro [Q5]"
        "400":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "VALIDATION_FAILED: RANGE sem from ou to, from >= to ou período acima de 366 dias"
components:
  schemas:
    PnlByMode:
      type: object
      additionalProperties: false
      description: "Só o período e os blocos. Sem market, os dois blocos; com market, só o bloco pedido, e a chave do outro fica ausente (nunca null). Não existe campo de total fora dos blocos (FR4.1, NFR3)"
      properties:
        period:
          $ref: "#/components/schemas/PnlPeriod"
        paper:
          $ref: "#/components/schemas/PaperModeBlockResult"
        real:
          $ref: "#/components/schemas/RealModeBlockResult"
      required: [period]   # U3 (A2): paper e real fora de required, por causa de market
    PaperModeBlockResult:
      discriminator:
        propertyName: status
        mapping:
          AVAILABLE: "#/components/schemas/PaperModeBlock"
          UNAVAILABLE: "#/components/schemas/ModeBlockUnavailable"
      oneOf:
        - $ref: "#/components/schemas/PaperModeBlock"
        - $ref: "#/components/schemas/ModeBlockUnavailable"
    RealModeBlockResult:
      discriminator:
        propertyName: status
        mapping:
          AVAILABLE: "#/components/schemas/RealModeBlock"
          UNAVAILABLE: "#/components/schemas/ModeBlockUnavailable"
      oneOf:
        - $ref: "#/components/schemas/RealModeBlock"
        - $ref: "#/components/schemas/ModeBlockUnavailable"
    PaperModeBlock:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [AVAILABLE]
        market:
          type: string
          enum: [PAPER]
        currency:
          type: string
          enum: [USD]
        disclaimer:
          type: string
          description: "Aviso fixo do simulado: \"O resultado simulado não garante o resultado real.\" (FR4.2, C-16)"
        empty:
          type: boolean
        total:
          $ref: "#/components/schemas/PnlAmount"
        realized:
          type: string
          pattern: "^-?\\d+(\\.\\d+)?$"
        unrealized:
          $ref: "#/components/schemas/PnlAmount"
        unrealizedAsOf:
          type: string
          format: date-time
        fees:
          type: string
          pattern: "^-?\\d+(\\.\\d+)?$"
        bots:
          type: array
          description: "Ordem de criação, nunca por valor"
          items:
            $ref: "#/components/schemas/PaperBotPnlLine"
      required: [status, market, currency, disclaimer, empty, total, realized, unrealized, unrealizedAsOf, fees, bots]
    PaperBotPnlLine:
      type: object
      additionalProperties: false
      properties:
        botId:
          type: string
          format: uuid
        name:
          type: string
        botStatus:
          $ref: "#/components/schemas/BotPnlStatus"
        total:
          $ref: "#/components/schemas/PnlAmount"
        realized:
          type: string
          pattern: "^-?\\d+(\\.\\d+)?$"
        unrealized:
          $ref: "#/components/schemas/PnlAmount"
        fees:
          type: string
          pattern: "^-?\\d+(\\.\\d+)?$"
      required: [botId, name, botStatus, total, realized, unrealized, fees]
    RealModeBlock:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [AVAILABLE]
        market:
          type: string
          enum: [REAL]
        currency:
          type: string
          enum: [SOL]
        empty:
          type: boolean
        totalLamports:
          $ref: "#/components/schemas/LamportsAmount"
        realizedLamports:
          $ref: "#/components/schemas/Lamports"
        unrealizedLamports:
          $ref: "#/components/schemas/LamportsAmount"
        unrealizedAsOf:
          type: string
          format: date-time
        feesLamports:
          $ref: "#/components/schemas/Lamports"
        bots:
          type: array
          description: "Ordem de criação, nunca por valor"
          items:
            $ref: "#/components/schemas/RealBotPnlLine"
        unowned:
          description: "Linha \"Sem bot\" (FR3.4, FR5.3). null até a U2; desde a U2, sempre um objeto"
          oneOf:
            - $ref: "#/components/schemas/UnownedPnlLine"
            - type: "null"
      required: [status, market, currency, empty, totalLamports, realizedLamports, unrealizedLamports, unrealizedAsOf, feesLamports, bots, unowned]
    RealBotPnlLine:
      type: object
      additionalProperties: false
      properties:
        botId:
          type: string
          format: uuid
        name:
          type: string
        botStatus:
          $ref: "#/components/schemas/BotPnlStatus"
        totalLamports:
          $ref: "#/components/schemas/LamportsAmount"
        realizedLamports:
          $ref: "#/components/schemas/Lamports"
        unrealizedLamports:
          $ref: "#/components/schemas/LamportsAmount"
        feesLamports:
          $ref: "#/components/schemas/Lamports"
        prorated:
          $ref: "#/components/schemas/ProratedShares"
      required: [botId, name, botStatus, totalLamports, realizedLamports, unrealizedLamports, feesLamports, prorated]
    ProratedShares:
      type: object
      additionalProperties: false
      description: "Dono do formato: U2 [Q3]. Vazio até a U2"
      properties:
        summary:
          type: array
          description: "Uma linha por origem com parcela; o realizado já vem calculado pela API"
          items:
            type: object
            additionalProperties: false
            properties:
              origin:
                $ref: "#/components/schemas/ProratedOrigin"
              realizedLamports:
                $ref: "#/components/schemas/Lamports"
            required: [origin, realizedLamports]
        count:
          type: integer
          format: int32
          minimum: 0
        lines:
          type: array
          description: "As 10 parcelas mais recentes; as demais pelo C5"
          maxItems: 10
          items:
            $ref: "#/components/schemas/ProratedLine"
      required: [summary, count, lines]
    ProratedOrigin:
      type: string
      enum: [LIQUIDATION, CONDITIONAL, SOLD_BY_OTHER_BOT]
    ProratedLine:
      type: object
      additionalProperties: false
      properties:
        at:
          type: string
          format: date-time
        mint:
          type: string
        origin:
          $ref: "#/components/schemas/ProratedOrigin"
        tokenBaseUnits:
          $ref: "#/components/schemas/BaseUnits"
        receivedLamports:
          $ref: "#/components/schemas/Lamports"
        feeLamports:
          $ref: "#/components/schemas/Lamports"
        realizedLamports:
          $ref: "#/components/schemas/Lamports"
      required: [at, mint, origin, tokenBaseUnits, receivedLamports, feeLamports, realizedLamports]
    UnownedPnlLine:
      type: object
      additionalProperties: false
      description: "Diferente de zero só com pelo menos um motivo (FR5.3)"
      properties:
        realizedLamports:
          $ref: "#/components/schemas/Lamports"
        unrealizedLamports:
          $ref: "#/components/schemas/LamportsAmount"
        feesLamports:
          $ref: "#/components/schemas/Lamports"
        reasons:
          type: array
          items:
            type: string
            enum: [UNOWNED_EXCESS, UNOWNED_NO_COST, ORDER_RECORD_MISSING]  # BR5.6 da U2
      required: [realizedLamports, unrealizedLamports, feesLamports, reasons]
```

Comportamento:
- **Estado parcial:** quando um modo falha, a resposta é 200 com o bloco que funcionou e, no outro, `status: UNAVAILABLE` com `code` e `correlationId`. Quando os dois falham, os dois vêm `UNAVAILABLE`. A rota nunca devolve 500 inteiro por causa de um modo [Q5].
- **Moeda por bloco:** cada bloco traz a sua `currency` (`USD` ou `SOL`) e só valores do próprio modo [Q6].
- **Totais dentro do bloco:** `total` e `totalLamports` somam só realizado, não realizado e taxas do mesmo modo, calculados no servidor (PaperPnl e RealBotPnl). Com o não realizado `UNKNOWN`, o total também é `UNKNOWN`; o realizado e as taxas continuam exatos (FR3.10, AC3.8.3).
- **Período:** em `RANGE`, o realizado conta as vendas com data no período, e o não realizado é sempre o de agora, com `unrealizedAsOf` (FR7.2). "Últimos 7 dias" e "Últimos 30 dias" são calculados no navegador, no fuso da pessoa, e chegam como `RANGE` em UTC. Os limites estão na especificação de interação do PeriodSelector.
- **Lista de bots:** a lista `/bots` mostra o P&L de cada bot na moeda do modo (FR2.2) lendo as linhas deste contrato pelo `botId`. A tela só procura a linha, sem nenhuma conta.
- **Repetição:** leitura idempotente. O "Tentar de novo" de um bloco relê a rota (ver PQ-6).
- **Sem endereço:** nenhum endereço de carteira aparece na resposta (NFR3).

## C5 — Linhas do P&L de um bot REAL

Fontes: [Q1], [Q3], FR3.6, US3.10 (AC3.10.1 a AC3.10.3), interaction-spec "BotPnlLines".

```yaml
# OpenAPI 3.1 — rota nova (U3)
paths:
  /api/v1/bots/{botId}/pnl/lines:
    get:
      operationId: listBotPnlLines
      summary: "As linhas que formam o P&L de um bot REAL, com a origem de cada uma, da mais recente para a mais antiga (FR3.6)"
      tags: [pnl-controller]
      security:
        - accessToken: []
      parameters:
        - in: path
          name: botId
          required: true
          schema:
            type: string
            format: uuid
        - in: query
          name: cursor
          required: false
          schema:
            type: string
        - in: query
          name: limit
          required: false
          schema:
            type: integer
            default: 50
            minimum: 1
            maximum: 100
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/RealBotPnlLinePage"
          description: Uma página das linhas
        "400":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "VALIDATION_FAILED: cursor ou limit inválido"
        "404":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "NOT_FOUND: bot de outra pessoa, inexistente ou PAPER"
        "503":                     # U3 (alinhamento A7, citado no A2)
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "SERVICE_UNAVAILABLE com correlationId: a passada REAL falhou (prazo, fila cheia, TOO_MANY_ORDERS ou divergência); a tela não repete sozinha"
components:
  schemas:
    RealBotPnlLinePage:
      type: object
      additionalProperties: false
      properties:
        currency:
          type: string
          enum: [SOL]
        items:
          type: array
          items:
            $ref: "#/components/schemas/RealBotPnlLineItem"
        nextCursor:
          type: ["string", "null"]
      required: [currency, items, nextCursor]
    RealBotPnlLineItem:
      type: object
      additionalProperties: false
      properties:
        at:
          type: string
          format: date-time
        side:
          type: string
          enum: [BUY, SELL]
        mint:
          type: string
        origin:
          type: string
          description: "BOT para as ordens do próprio bot; as demais são parcelas rateadas"
          enum: [BOT, LIQUIDATION, CONDITIONAL, SOLD_BY_OTHER_BOT]
        tokenBaseUnits:
          $ref: "#/components/schemas/BaseUnits"
        amountLamports:
          $ref: "#/components/schemas/Lamports"
        feeLamports:
          $ref: "#/components/schemas/Lamports"
        realizedLamports:
          $ref: "#/components/schemas/Lamports"
      required: [at, side, mint, origin, tokenBaseUnits, amountLamports, feeLamports, realizedLamports]
```

Comportamento: o `realizedLamports` de uma compra é `"0"`, porque a compra não realiza resultado; isso não é dado ausente. A paginação é por cursor opaco, como as outras páginas do contrato.

## C6 — Comparação lado a lado

Fontes: [Q1], [Q3], FR8.1, US8.1 (AC8.1.2 a AC8.1.4), interaction-spec "ComparisonView", OQ4.

```yaml
# OpenAPI 3.1 — rota nova (U3)
paths:
  /api/v1/pnl/compare:
    get:
      operationId: comparePaperAndRealBots
      summary: "Um bot PAPER e um bot REAL da mesma estratégia, cada um na sua moeda, sem nenhuma diferença calculada (FR8.1)"
      tags: [pnl-controller]
      security:
        - accessToken: []
      parameters:
        - in: query
          name: paperBotId
          required: true
          schema:
            type: string
            format: uuid
        - in: query
          name: realBotId
          required: true
          schema:
            type: string
            format: uuid
        - in: query
          name: period
          required: false
          schema:
            type: string
            enum: [ALL, RANGE]
            default: ALL
        - in: query
          name: from
          required: false
          schema:
            type: string
            format: date-time
        - in: query
          name: to
          required: false
          schema:
            type: string
            format: date-time
      responses:
        "200":
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/PnlComparison"
          description: "Os dois lados; um lado que falhou vem UNAVAILABLE"
        "400":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "VALIDATION_FAILED: período inválido"
        "404":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "NOT_FOUND: um dos bots é de outra pessoa, não existe ou é do modo errado; reason diz qual lado (PAPER_BOT ou REAL_BOT)"
        "422":
          content:
            application/problem+json:
              schema:
                $ref: "#/components/schemas/Problem"
          description: "VALIDATION_FAILED com reason TEMPLATE_MISMATCH: os dois bots não têm o mesmo templateId (OQ4)"
components:
  schemas:
    PnlComparison:
      type: object
      additionalProperties: false
      description: "Só o período e os dois lados; nenhum campo de diferença, percentual, ranking ou total"
      properties:
        period:
          $ref: "#/components/schemas/PnlPeriod"
        paper:
          discriminator:
            propertyName: status
            mapping:
              AVAILABLE: "#/components/schemas/PaperComparisonSide"
              UNAVAILABLE: "#/components/schemas/ModeBlockUnavailable"
          oneOf:
            - $ref: "#/components/schemas/PaperComparisonSide"
            - $ref: "#/components/schemas/ModeBlockUnavailable"
        real:
          discriminator:
            propertyName: status
            mapping:
              AVAILABLE: "#/components/schemas/RealComparisonSide"
              UNAVAILABLE: "#/components/schemas/ModeBlockUnavailable"
          oneOf:
            - $ref: "#/components/schemas/RealComparisonSide"
            - $ref: "#/components/schemas/ModeBlockUnavailable"
      required: [period, paper, real]
    PaperComparisonSide:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [AVAILABLE]
        market:
          type: string
          enum: [PAPER]
        currency:
          type: string
          enum: [USD]
        disclaimer:
          type: string
        createdAt:
          type: string
          format: date-time
        templateId:
          type: string
        line:
          $ref: "#/components/schemas/PaperBotPnlLine"
      required: [status, market, currency, disclaimer, createdAt, templateId, line]
    RealComparisonSide:
      type: object
      additionalProperties: false
      properties:
        status:
          type: string
          enum: [AVAILABLE]
        market:
          type: string
          enum: [REAL]
        currency:
          type: string
          enum: [SOL]
        createdAt:
          type: string
          format: date-time
        templateId:
          type: string
        line:
          $ref: "#/components/schemas/RealBotPnlLine"
      required: [status, market, currency, createdAt, templateId, line]
```

Comportamento: o aviso do simulado vem só no lado PAPER (AC8.1.3). A tela mostra "Bot não encontrado." no lado indicado por `reason` (AC8.1.4).

## C7 — Estado da venda da quantidade própria na lista de bots

Fontes: FR6.2, US6.2 (AC6.2.2), ADR-006, `unit-of-work-dependency.md` ("U4 → U1: lista `/bots` e estado da configuração exposto pela API").

A Q1 não lista essa exposição, mas o grafo das Units a exige. O contrato é o menor possível: um campo opcional e aditivo em `BotPage`, aceito pelo fundador em 2026-11-21 (PQ-3).

```yaml
# OpenAPI 3.1 — delta aditivo (U4) em components.schemas.BotPage
components:
  schemas:
    BotPage:
      properties:
        realOwnQuantitySell:
          type: boolean
          description: "true quando cada bot REAL vende só a quantidade que ele comprou (FR6.1); a tela mostra o aviso do FR6.2. Ausente ou false com a configuração padrão"
```

## C8 — `pnl.api.BotDirectory`

Fontes: [Q2], [Q3], ADR-007, FR3.7, FR3.11, FR8.1.

```yaml
# shared-schema — interface Java pública (declarada em pnl.api, implementada em bots)
contract: pnl.api.BotDirectory
owner: u1-mode-skeleton
implementedBy: com.memetrade.bots (adaptador do módulo bots)
consumers: [pnl.modeview.ModePnlView, pnl.application RealBotPnl, pnl.application RealBotHoldings]
methods:
  - signature: "List<BotEntry> botsOf(UUID tenantId, UUID userId, PnlMarket market)"
    returns: "os bots da pessoa nesse modo, inclusive pausados e removidos, em ordem de criação (createdAt, botId)"
    tenant: "filtra por tenant e pessoa; um tenant de outra pessoa devolve lista vazia"
  - signature: "Optional<BotEntry> botOf(UUID tenantId, UUID userId, UUID botId)"
    returns: "vazio quando o bot não é da pessoa ou não existe; o chamador responde 404"
  - signature: "boolean ownedByAnotherTenant(UUID tenantId, UUID botId)"   # U3 (alinhamento A2, decisão F-2)
    returns: "true só quando o bot existe num tenant diferente do informado; nunca devolve dado do bot"
    tenant: "A ÚNICA consulta fora do tenant do token, só booleana, pela chave primária (select exists ... tenant_id <> :tenant), na transação só de leitura do pedido"
    caller: "só o caminho dos 404 de C5 e C6 (PnlController.refuseNotFound), na thread do pedido, nos dois 404 (uuid inexistente e outro tenant), para decidir a auditoria TENANT_MISMATCH; a resposta é a mesma; regra ArchUnit ONLY_THE_404_PATH_ASKS_OTHER_TENANTS"
types:
  PnlMarket: { kind: enum, values: [PAPER, REAL] }
  BotPnlStatus: { kind: enum, values: [ACTIVE, PAUSED, REMOVED] }
  BotEntry:
    kind: record
    fields:
      botId: UUID
      name: String
      market: PnlMarket
      templateId: String
      status: BotPnlStatus
      createdAt: Instant
rules:
  - "campos mínimos; nada de parâmetros, limites ou saldo do bot (ADR-007)"
  - "o pnl nunca depende de bots.api; a dependência fica bots -> pnl.api"
  - "transação: só leitura; participa da transação do chamador quando existir"
```

## C9 — `riskcontrol.api.RealAccountLimits`

Fontes: [Q2], [Q3], ADR-008, OQ6.

```yaml
# shared-schema — interface Java pública (declarada em riskcontrol.api, implementada em riskcontrol.real)
contract: riskcontrol.api.RealAccountLimits
owner: u1-mode-skeleton
consumers: [bots (rota GET /api/v1/real/account-limits, C3)]
methods:
  - signature: "Optional<AccountLimits> read(UUID tenantId, UUID userId)"
    returns: "vazio sem carteira do bot ativa (C3 responde 409 REAL_NOT_ELIGIBLE / WALLET_INACTIVE)"
    throws: "RealLimitsUnavailable quando uma dependência falhou (C3 responde 503)"
types:
  AccountLimits:
    kind: record
    fields:
      orderLimitLamports: LimitValue
      tokenExposureLimitLamports: LimitValue
      totalExposureLimitLamports: LimitValue
      operatingCapLamports: LimitValue
      dailyLossPercent: PercentValue
  LimitValue: { kind: sealed, cases: { Known: "long lamports (>= 0, arredondado para baixo)", Unknown: "reason NOT_DEFINED" } }
  PercentValue: { kind: sealed, cases: { Known: "BigDecimal percent", Unknown: "reason NOT_DEFINED" } }
rules:
  - "mesmas entradas da RealRiskPolicy: configuração da barreira, teto e reserva de taxa da carteira do bot"
  - "só leitura: não reserva, não muda limite, não grava auditoria"
  - "nenhum double ou float"
```

## C10 — `pnl.api.RealBotPnlQuery`

Fontes: [Q2], [Q3], ADR-001, ADR-003, ADR-004, FR3.2 a FR3.11, FR5.3, FR7.2, NFR1.

**Posse (revisão R-06).** A dona da interface é a U2 [Q3]. A U1 entrega a primeira implementação atrás dessa interface, com o cálculo por ordens do `RealPnlCalculator` filtrado por `bot_id`, e já com a assinatura definitiva. A U2 troca a implementação pelo RealCostBasis e pelo RealProration. Se a U1 precisar de um ajuste na assinatura, ela o propõe como mudança de um contrato da U2: entra só aditiva, registrada aqui antes do fim da U1, e a U2 a herda.

```yaml
# shared-schema — interface Java pública (pnl.api), implementada em pnl.application
contract: pnl.api.RealBotPnlQuery
owner: u2-real-bot-pnl
firstImplementation: u1-mode-skeleton
consumers: [pnl.modeview.ModePnlView (C4, C6), pnl.web (C5)]
methods:
  - signature: "RealModePnl modePnl(UUID tenantId, UUID userId, PnlWindow window)"
    returns: "linhas por bot, linha Sem bot (vazia na U1) e total REAL do modo no período"
    throws: "RealPnlUnavailable quando uma leitura falhou (o bloco REAL vira UNAVAILABLE)"
  - signature: "Optional<RealBotPnlLine> botPnl(UUID tenantId, UUID userId, UUID botId, PnlWindow window)"
    returns: "vazio quando o bot não é REAL da pessoa (404)"
  - signature: "Optional<RealPnlLinePage> botLines(UUID tenantId, UUID userId, UUID botId, Optional<String> cursor, int limit)"
    returns: "página das linhas (C5); vazio quando o bot não é REAL da pessoa (404); cursor cuja âncora sumiu lança RealPnlLineCursorRejected (400 no campo cursor)"
    status: "implementado na U3 (alinhamento A2): a mesma passada de C4 com um RealPnlEngine.LineCollector, sem conta nova; o retorno Optional é o desvio aditivo registrado no code-summary da U3"
types:
  PnlWindow: { kind: sealed, cases: { All: "desde o início", Range: "Instant from (incluído), Instant to (excluído), no máximo 366 dias" } }
  LamportsValue: { kind: sealed, cases: { Known: "long lamports", Unknown: "UnknownReason reason" } }
  UnknownReason: { kind: enum, values: [PRICE_UNKNOWN, STALE, NO_LIQUIDITY] }
  RealModePnl:
    kind: record
    fields:
      lines: "List<RealBotPnlLine> (ordem de criação)"
      unowned: "Optional<UnownedPnlLine> (vazio na U1; presente desde a U2)"
      total: RealModeTotal
  RealBotPnlLine:
    kind: record
    fields:
      botId: UUID
      status: BotPnlStatus
      realizedLamports: long
      unrealized: LamportsValue
      feesLamports: long
      total: LamportsValue
      shares: "List<ProratedShare> (vazia na U1)"
  ProratedShare:
    kind: record
    fields: { saleRef: String, origin: "LIQUIDATION | CONDITIONAL | SOLD_BY_OTHER_BOT", mint: String, at: Instant, tokenBaseUnits: long, receivedLamports: long, feeLamports: long, realizedLamports: long }
  UnownedPnlLine:
    kind: record
    fields: { realizedLamports: long, unrealized: LamportsValue, feesLamports: long, reasons: "Set<UnownedReason> (UNOWNED_EXCESS, UNOWNED_NO_COST, ORDER_RECORD_MISSING; BR5.6 da U2)" }
  RealModeTotal:
    kind: record
    fields: { realizedLamports: long, unrealized: LamportsValue, feesLamports: long, total: LamportsValue, currency: "SOL", unrealizedAsOf: Instant }
invariants:
  - "desde a U2: soma das linhas + unowned == total do modo calculado do ledger, por igualdade exata em lamports (FR5.3)"
  - "unowned diferente de zero só com pelo menos um motivo (FR5.3)"
  - "aritmética em long com Math.*Exact e frações em BigInteger; nenhum double ou float"
  - "nenhum tipo desta interface aparece numa classe que também dependa de um tipo de resultado PAPER, fora de pnl.modeview (NEVER_SUM_MODES, ADR-003); os tipos entram na lista fechada REAL da regra no mesmo commit"
  - "só leitura, sem trava de escrita; nunca bloqueia a execução de ordens (NFR1)"
```

## C11 — `ledger.api.RealCostMethod`

Fontes: [Q2], [Q3], ADR-002, ADR-011, FR5.2, FR3.8.

```yaml
# shared-schema — porta Java declarada em ledger.api, implementada pelo RealCostBasis (pnl.real)
contract: ledger.api.RealCostMethod
owner: u2-real-bot-pnl
implementedBy: pnl.real.RealCostBasis (injetado pela configuração do pnl)
consumers: [ledger.infra.JdbcRealLedger (positions, view), pnl.application RealBotPnl, pnl.application RealBotHoldings]
methods:
  - signature: "Accumulator start()"
    returns: "um acumulador vazio de um titular (a carteira ou um bot)"
types:
  Accumulator:
    kind: interface
    methods:
      - "void apply(CostFill fill)  // IllegalArgumentException quando o fill chega fora da ordem de NetworkOrderKey"
      - "List<RealPosition> openPositions()  // posições abertas, quantidade >= 1, custo >= 0"
  CostFill:
    kind: record
    fields:
      key: NetworkOrderKey
      kind: "RealEntryKind (SWAP_FILL_IN | SWAP_FILL_OUT)"
      mint: String
      tokenBaseUnits: "long (> 0)"
      lamports: "long (sinal de RealEntry: IN negativo, OUT positivo)"
  RealPosition: "ledger.api.RealPosition: (mint, tokenBaseUnits, RealCost cost), com RealCost selado Known(long) ou Unknown(motivo); RealWalletCost devolve WalletCost selado; um custo desconhecido nunca vira 0 (decisão F-3; alinhamento A11)"
rules:
  - "um único algoritmo de custo médio REAL; o algoritmo próprio do RealPositions sai (FR5.2)"
  - "o custo retirado numa venda parcial é arredondado para cima e a última venda leva o restante; o custo restante pode ficar até 1 lamport abaixo da fração exata por titular e por venda parcial, e volta a ser exato ao zerar (BR2.6 da U2; alinhamento A11)"
  - "puro: sem I/O, sem Spring, sem relógio, sem ponto flutuante; aplicação incremental, sem carregar o histórico numa lista"
  - "o ledger nunca depende do pnl (LedgerArchitectureTest.theLedgerNeverDependsOnThePnl continua verde sem exceção)"
  - "o bean existe em todo processo que usa o ledger REAL (API e worker)"
  - "teste de paridade: posição da carteira == soma das posições dos bots + parcela sem dono, com a mesma chave de ordem (ADR-002)"
```

## C12 — Leitura em fluxo do ledger REAL na ordem de rede

Fontes: [Q2], ADR-004, ADR-011, FR3.8, NFR1.

```yaml
# shared-schema — método novo em ledger.api.RealLedger e tipos novos em ledger.api
contract: ledger.api.RealLedger.streamInNetworkOrder
owner: u2-real-bot-pnl
consumers: [pnl.application RealBotPnl, pnl.application RealBotHoldings]
methods:
  - signature: "void streamInNetworkOrder(UUID tenantId, Collection<UUID> botWalletIds, Set<RealEntryKind> kinds, Consumer<NetworkOrderedEntry> consumer)"
    behaviour: "entrega um a um, nunca como lista; agrupado por carteira e, dentro dela, na ordem de NetworkOrderKey; nada para carteira de outro tenant; nunca PAPER"
    transaction: "só leitura; participa da transação só de leitura do chamador quando ela existe (a do RealBotHoldings, C15, em REPEATABLE READ), senão abre a sua; nunca participa de uma transação que escreve, e em especial nunca da transação do ciclo"
types:
  NetworkOrderKey:
    kind: record
    implements: "Comparable<NetworkOrderKey>"
    fields:
      effectiveSlot: "long (o slot gravado ou, sem ele, o herdado)"
      inherited: "boolean (true quando a linha não tem slot gravado)"
      txSignature: "Optional<String>"
      createdAt: Instant
      reference: "String (alinhamento A9)"
      kindRank: "int (SWAP_FILL_IN/OUT 0, NETWORK_FEE 1, RENT 2, demais 3; alinhamento A9)"
      idempotencyKey: "String (alinhamento A9)"
    order: "effectiveSlot; depois as linhas com slot gravado antes das herdadas; com slot: txSignature, depois createdAt, reference, kindRank e idempotencyKey; herdadas: createdAt, reference, kindRank e idempotencyKey; nunca entryId; todo desempate de texto (tx_signature, reference, idempotency_key) com COLLATE \"C\" (decisão F-1; alinhamento A9)"
    fallback: "linha sem slot herda o slot da última linha com slot gravada antes dela na mesma carteira, pela ordem de gravação (created_at, reference, kind_rank, idempotency_key) (ADR-011; decisão F-1; alinhamento A9)"
    slotSource: "o slot vem só da tabela lateral real_entry_slot, casada pela tx_signature do lançamento (C14); real_ledger_entry não ganha coluna"
  SlotStatus: { kind: enum, values: [RECORDED, SLOT_MISSING, NO_NETWORK_TX] }
  NetworkOrderedEntry:
    kind: record
    fields:
      entryId: UUID
      botWalletId: UUID
      kind: RealEntryKind
      lamports: long
      mint: "Optional<String>"
      tokenBaseUnits: "Optional<Long>"
      txSignature: "Optional<String>"
      reference: "String (order:<id>, dep:<assinatura>, wd:<id> e as referências das condicionais)"
      createdAt: Instant
      key: NetworkOrderKey
      slotStatus: SlotStatus
rules:
  - "a chave é calculada pelo provedor (SQL, com junção em real_entry_slot), uma só para RealPositions, RealCostMethod, RealBotPnl e RealBotHoldings (ADR-011)"
  - "slotStatus: RECORDED quando real_entry_slot tem a assinatura; NO_NETWORK_TX quando o lançamento não tem assinatura (RESERVE, RESERVE_RELEASE, COMPENSATION); SLOT_MISSING nos demais casos, inclusive todas as linhas anteriores à migração 0023, que não são preenchidas (decisão do fundador, 2026-11-21)"
  - "o motivo SLOT_MISSING é registrado na própria leitura (slotStatus) e contado numa métrica; nenhuma linha antiga é alterada"
  - "o índice final para a leitura em fluxo com a junção e o p95 ficam com o NFR Design (NFR1)"
  - "streamEntries e RealEntryRecord continuam sem mudança, para a exportação fiscal"
  - "sem trava de escrita"
```

## C13 — Slot das ordens REAL e leitura em fluxo das assentadas

Fontes: [Q2], ADR-001, ADR-011, FR3.3, FR3.8, FR3.9; decisão do fundador de 2026-11-21 (PQ-2).

A ordem REAL não ganha coluna `slot`. A confirmação grava o slot da transação na tabela lateral `real_entry_slot` (C14), pela assinatura, e toda ordenação lê o slot só por essa tabela. `RealOrderView` não muda.

```yaml
# shared-schema — deltas em realexecution.api e em ledger.api
contract: realexecution.api (RealExecutionPort.streamSettledOrders) e ledger.api.RealLedger.recordSlot
owner: u2-real-bot-pnl
consumers: [pnl.application RealBotPnl, pnl.application RealBotHoldings]
changes:
  - type: ledger.api.RealLedger
    add: "RecordSlotResult recordSlot(String txSignature, long slot)"
    behaviour: "insere (assinatura, slot) em real_entry_slot na transação do chamador, com INSERT ... ON CONFLICT DO NOTHING; RECORDED quando gravou; ALREADY_RECORDED quando a assinatura já tem o mesmo slot (idempotente); CONFLICT_REFUSED quando a assinatura já tem outro slot"
    conflict: "nunca lança para fora da transação de confirmação (revisão R-03): o primeiro slot fica; o slot novo é recusado e registrado como evento de auditoria REAL_SLOT_CONFLICT com o motivo (assinatura, slot gravado, slot recusado) e na métrica ledger_real_slot_conflicts_total (alinhamento A3); o lançamento do ledger e a mudança de estado da ordem seguem na mesma transação. O Functional Design da U2 detalha o evento e o alerta"
    resultType: "RecordSlotResult: enum RECORDED | ALREADY_RECORDED | CONFLICT_REFUSED"
    callers: "a confirmação da ordem (realexecution), o FillPosting da condicional (realexits) e a confirmação de depósito, saque e taxa (botwallet)"
  - type: realexecution.api.RealOrderView
    change: "nenhuma; o slot não entra no tipo"
  - type: realexecution.api.RealExecutionPort
    add: "void streamSettledOrders(UUID tenantId, UUID userId, Consumer<RealOrderView> consumer)"
    behaviour: "as ordens da pessoa que chegaram à rede (CONFIRMED, ou FAILED com transação), com botId, liquidationId, origem, lado, taxa, quem pagou e assinatura; uma a uma, na transação só de leitura do chamador quando ela existe (a do RealBotHoldings, C15), senão numa própria, nunca na transação do ciclo; filtradas por tenant e pessoa; a posição de cada ordem na ordem de rede vem dos lançamentos de C12, casados pela referência order:<id>"
    note: "settledOrders (lista) continua para os chamadores atuais"
rules:
  - "real_order.bot_id continua a única ligação entre uma operação REAL e um bot (ADR-004)"
  - "o RealBotPnl casa o lançamento do ledger com a ordem pela referência order:<id>; uma ordem expurgada depois de 5 anos deixa o lançamento na linha Sem bot com o motivo ORDER_RECORD_MISSING"
```

## C14 — Tabela lateral `real_entry_slot`, só de inserção

Fontes: [Q2], ADR-004, ADR-011, `team.md` (Deployment: expand-contract; Testing Posture: ledger sem edição), `project.md` (ALWAYS ledger append-only); decisão do fundador de 2026-11-21 (PQ-2 e PQ-4).

**Decisão do fundador (2026-11-21).**
- O slot fica numa tabela lateral nova, `real_entry_slot`, só de inserção e dona do módulo `ledger`. Ela tem a mesma proteção append-only das tabelas do ledger: um gatilho recusa UPDATE e DELETE.
- `real_ledger_entry` e `real_order` não ganham coluna, e o gatilho de `real_ledger_entry` não muda.
- Não há preenchimento das linhas antigas. Elas ficam `SLOT_MISSING`, com o motivo registrado, e são ordenadas pelo fallback do ADR-011.
- A ordenação de lançamentos e de ordens lê o slot só por esta tabela, casando pela assinatura.
- **A seguir no Functional Design:** o ADR-011 (domain design) fala em coluna `slot` nas duas tabelas e em preenchimento das linhas antigas. O Functional Design da U2 alinha o ADR-011 a esta decisão; o `decisions.md` não muda nesta etapa.

A próxima migração é a `0023`, no Liquibase (`db/changelog/changes/`, incluída em `db.changelog-master.yaml`). O caminho `db/migration` não existe neste projeto.

```yaml
# shared-schema — migração Liquibase só de expansão
contract: db/changelog/changes/0023-real-entry-slot.sql
owner: u2-real-bot-pnl
tableOwner: ledger
changesets:
  - id: 0023-real-entry-slot
    ddl: |
      CREATE TABLE real_entry_slot (
          tx_signature varchar(90) PRIMARY KEY,
          slot         bigint      NOT NULL,
          recorded_at  timestamptz NOT NULL,
          CONSTRAINT real_entry_slot_slot_ck CHECK (slot >= 0)
      )
    rollback: "DROP TABLE real_entry_slot (só vazia; com linhas, a imagem volta e a tabela fica)"
  - id: 0023-real-entry-slot-append-only
    ddl: |
      CREATE TRIGGER real_entry_slot_no_update_delete BEFORE UPDATE OR DELETE ON real_entry_slot
          FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();
      CREATE TRIGGER real_entry_slot_no_truncate BEFORE TRUNCATE ON real_entry_slot
          FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
    note: "mesma função ledger_reject_change da 0008 e da 0016"
  - id: 0023-real-entry-slot-grants
    behaviour: "changeset explícito, porque real_entry_slot não está na lista fixa do bloco de 0016-real-grants (real_ledger_entry, observed_deposit, wallet_consent): para o papel da aplicação, REVOKE ALL ON real_entry_slot e depois GRANT SELECT, INSERT ON real_entry_slot; REVOKE TRUNCATE do dono da migração, como em 0019 (revisão R-05)"
    test: "teste de integração, ao lado do teste dos gatilhos, que confere que o login da aplicação consegue SELECT e INSERT e não consegue UPDATE, DELETE nem TRUNCATE em real_entry_slot"
writers:
  - "a confirmação da ordem (realexecution), pela porta ledger.api.RealLedger.recordSlot (C13)"
  - "o FillPosting da venda condicional executada (realexits)"
  - "a confirmação de depósito, saque e taxa (botwallet)"
readers:
  - "ledger.infra: a leitura em fluxo de C12 e as posições por RealCostMethod (C11), por junção pela tx_signature"
untouched:
  - "real_ledger_entry: nenhuma coluna nova; o gatilho real_ledger_entry_no_update_delete fica como está"
  - "real_order: nenhuma coluna nova; o slot da ordem é o de real_entry_slot pela tx_signature da ordem"
derived:
  slotStatus: "RECORDED quando real_entry_slot tem a tx_signature; NO_NETWORK_TX quando a linha não tem tx_signature; SLOT_MISSING nos demais casos. Calculado na leitura, sem coluna nova"
rules:
  - "nenhum preenchimento das linhas antigas: toda linha anterior à 0023 com assinatura fica SLOT_MISSING e usa o fallback do ADR-011"
  - "o mesmo slot para a mesma assinatura é idempotente; um slot diferente é recusado e registrado (evento REAL_SLOT_CONFLICT e métrica), nunca sobrescrito, e não desfaz o lançamento do ledger (C13)"
  - "sem tenant_id: a tabela só liga uma assinatura pública da rede a um slot, sem dado de cliente; toda leitura chega a ela pela junção com real_ledger_entry, que filtra pelo tenant"
  - "o teste obrigatório 'UPDATE ou DELETE em lançamento falha' passa a cobrir também real_entry_slot"
  - "as imagens anteriores continuam funcionando com a tabela nova (expand-contract); em produção a migração não é revertida, só as imagens voltam"
  - "o índice que a junção precisa em real_ledger_entry (por exemplo bot_wallet_id, tx_signature) fica com o NFR Design (PQ-10)"
```

## C15 — `pnl.api.RealBotHoldings`

Fontes: [Q2], [Q3], [Q5], ADR-006, FR6.1, US6.1 (AC6.1.4); revisão R-02.

**Desenho conservador (revisão R-02).** O RealBotHoldings lê num único instantâneo, numa transação **própria**, só de leitura, em `REPEATABLE READ`. Essa transação abre e fecha **antes** da transação de escrita do ciclo e fora dela. Nesse instantâneo ele lê a quantidade própria do bot no ledger (C12 e C13) e as vendas em aberto do bot (C16), e calcula `disponível = própria no ledger − vendas em aberto`, com `AMBIGUOUS` contado como em aberto. O resultado só pode subestimar a quantidade, nunca superestimar.

```yaml
# shared-schema — interface Java pública (pnl.api), implementada em pnl.application, separada do RealBotPnl
contract: pnl.api.RealBotHoldings
owner: u4-own-quantity-cycle
consumers: [bots.worker RealCycle]
methods:
  - signature: "HoldingsResult holdings(UUID tenantId, UUID userId, UUID botId, Duration budget)"
    behaviour: "quantidade e custo próprios do bot por mint, menos as vendas do bot em aberto; usa só o caminho de quantidade do RealCostMethod e do RealProration, sobre C12, C13 e C16, todos lidos no mesmo instantâneo"
    transaction: "transação própria, readOnly, isolamento REPEATABLE READ (um instantâneo para as três leituras), com statement_timeout igual a budget; chamada pelo RealCycle ANTES de abrir a transação de escrita do ciclo, nunca dentro dela (sem participação, sem REQUIRES_NEW aninhado)"
types:
  HoldingsResult:
    kind: sealed
    cases:
      Available: "List<BotHolding> holdings, Instant readAt"
      Unavailable: "UnavailableReason reason"
  UnavailableReason: { kind: enum, values: [BUDGET_EXCEEDED, READ_FAILED, BOT_NOT_OWNED] }
  BotHolding:
    kind: record
    fields:
      mint: String
      ownTokenBaseUnits: "long (>= 0), a quantidade própria no ledger do instantâneo"
      ownCostLamports: "long (>= 0)"
      openSellBaseUnits: "long (>= 0), as vendas do bot em aberto no mesmo instantâneo"
      availableTokenBaseUnits: "long = max(0, ownTokenBaseUnits - openSellBaseUnits)"
failure:
  - "toda exceção (SQL, statement_timeout, budget, bot de outra pessoa) é capturada DENTRO da transação própria, que é desfeita, e vira Unavailable; nada lança para o RealCycle e a transação do ciclo nunca fica abortada"
  - "com Unavailable, o ciclo não vende naquele ciclo; o motivo vai para o log e para a métrica real_own_holdings_unavailable_total{reason}"
  - "nunca devolve a posição da carteira inteira no lugar da própria"
concurrency:
  - "entre a leitura e a ordem, uma segunda venda do mesmo bot é impedida pelo que já existe: a trava do ciclo por bot e pessoa (RealRiskGate.withPersonLock, que a fila de decisões usa) e o índice único uq_real_order_bot_in_progress (no máximo uma ordem em andamento por bot); a decisão de venda é recusada se outra ordem do bot já está em andamento"
  - "uma venda do bot que assenta depois do instantâneo ainda estava em aberto dentro dele (SENT ou AMBIGUOUS, sem lançamento no ledger), então já foi descontada"
  - "uma venda que assentou antes do instantâneo está no ledger e não está em aberto; ela é contada uma vez só"
  - "precondição, conferida no Functional Design da U2/U4: o assentamento grava os lançamentos do ledger e muda a ordem para CONFIRMED na mesma transação; se não for assim, C16 passa a contar também a ordem SELL CONFIRMED cujo lançamento ainda não está no ledger do instantâneo"
  - "por construção, o disponível só pode ficar menor que o real (venda em voo descontada), nunca maior"
rules:
  - "o número do orçamento fica com o NFR Design (PQ-9); a implementação existe no processo worker"
  - "sem preço, taxas, período, realizado nem linha Sem bot"
  - "teste: sem vendas em aberto, a quantidade de RealBotHoldings == a quantidade de RealBotPnl para o mesmo ledger (ADR-006)"
  - "teste de concorrência obrigatório, escrito antes do código (team.md, Testing Posture): um assentamento em paralelo com um ciclo nunca faz o ciclo vender mais que a quantidade própria do bot"
  - "teste: uma falha de SQL e um statement_timeout no RealBotHoldings viram Unavailable e a transação do ciclo segue utilizável"
  - "a venda continua por RealExecutionPort.recordDecision com RealDecision.tokenBaseUnits <= availableTokenBaseUnits; RealDecision já aceita a quantidade de uma venda e não muda; a idempotência e a fila continuam do realexecution (AC6.1.4)"
```

## C16 — Vendas em aberto de um bot

Fontes: [Q2], [Q3], ADR-006; revisão R-02.

```yaml
# shared-schema — método novo em realexecution.api.RealExecutionPort
contract: realexecution.api.RealExecutionPort.openSells
owner: u4-own-quantity-cycle
consumers: [pnl.application RealBotHoldings]
methods:
  - signature: "List<OpenSell> openSells(UUID tenantId, UUID botId)"
    behaviour: "as ordens SELL do bot em PROPOSED, QUOTED, SIGNED, SENT ou AMBIGUOUS (RealOrderState.IN_PROGRESS), filtradas por tenant; AMBIGUOUS conta como em aberto, o lado conservador"
    transaction: "só leitura; participa da transação só de leitura do RealBotHoldings (REPEATABLE READ), para ver o mesmo instantâneo do ledger; nunca da transação do ciclo"
types:
  OpenSell:
    kind: record
    fields:
      realOrderId: UUID
      mint: String
      inputBaseUnits: "long (> 0)"
      state: RealOrderState
rules:
  - "só leitura; nenhuma mudança de estado"
  - "o realexecution continua dono da ordem, da fila e da idempotência"
```

## C17 — Componentes web comuns

Fontes: [Q3], ADR-003, ADR-010, interaction-spec "PnlModeBlock" e "SimulatedBadge", FR4.2, FR4.3, FR8.1.

```yaml
# shared-schema — props TypeScript (apps/web), donos na U1, usados pela U3
contract: apps/web components PnlModeBlock and SimulatedBadge
owner: u1-mode-skeleton
consumers: [u3-pnl-views (DashboardScreen compact, ComparisonView bot, ProratedLines dentro do full)]
components:
  PnlModeBlock:
    path: apps/web/src/features/pnl
    props:
      market: "'PAPER' | 'REAL'  // um modo só; não existe prop que receba os dois"
      variant: "'full' | 'compact' | 'bot'  // U1 entrega full; U3 acrescenta compact e bot"
      load: "Load<ModePnl>  // os tipos vêm do contrato gerado de openapi-guest.yaml (C4)"
      onRetry: "() => void"
      period: "PeriodLabel (opcional)"
      realUnavailable: "RealUnavailableReason (opcional, só no REAL vazio)"
      timeZone: "string (opcional)"
  SimulatedBadge:
    path: apps/web/src/components
    props: "sem valores monetários; só o selo SIMULADO em texto"
rules:
  - "nenhum componente soma, subtrai ou converte valores entre modos (ADR-003)"
  - "SOL formatado só por features/real/money.ts; US$ pelas funções de features/pnl/decimal.ts"
  - "a proibição de lint do dinheiro (Number(, toFixed, parseFloat) passa a valer para features/pnl (ADR-010)"
  - "acréscimos só aditivos: variante nova ou prop opcional; os testes da U1 sobre o bloco continuam verdes"
```

## Regras de posse dos contratos

Fontes: [Q3], [Q4], ADR-003, ADR-009, `team.md` (Code Style: contrato gerado; Deployment: expand-contract).

1. **Dono.** A Unit provedora da tabela é a dona da especificação: ela muda o contrato e os testes de contrato no mesmo commit [Q3].
2. **Só aditivo em `/api/v1/`.** Os campos novos de resposta são opcionais ou entram junto com a rota nova; os valores de enum novos de requisição são aceitos pelo servidor antes de o cliente enviá-los. Nada é removido nem renomeado neste intent [Q4]. **Exceção registrada:** o alargamento do enum de resposta de `mode` (C1) quebra o parser atual do painel. Ele só entra com as obrigações da U1 descritas em C1, no mesmo commit.
3. **Consumidores ignoram campos desconhecidos.** O `apps/web` e qualquer cliente gerado ignoram campos que não conhecem. No servidor, `additionalProperties: false` continua valendo para os esquemas de resposta, porque descreve o que o servidor envia.
4. **Quebra exige `/v2`.** Remover ou renomear um campo, mudar um tipo ou o significado de um valor exige uma rota `/v2` aprovada pelo responsável do projeto. A CI já falha numa quebra: `GuestOpenApiContractTest`, os testes de contrato e o `check:api` do `apps/web` [Q4].
5. **`mode == market`.** Um teste de contrato confere a igualdade nos dois mercados em toda resposta que traz um bot, e que `mode` continua presente e marcado `deprecated`. `mode` não é removido neste intent; a remoção vai num intent futuro, com expand-contract (ADR-009, AC2.4.3).
6. **Forma do `by-mode` definitiva desde a U1.** A U2 e a U3 só preenchem valores e acrescentam parâmetros de requisição. Um teste de contrato confere que `PnlByMode` só tem `period`, `paper` e `real`, e um teste de propriedade confere que nenhum terceiro número aparece (ADR-003, NFR3) [Q4].
7. **Nenhum total geral.** Nenhum esquema, interface Java ou componente ganha um campo que some os modos. A regra ArchUnit `NEVER_SUM_MODES` e o teste de contrato barram isso na CI (ADR-003).
8. **Interfaces Java.** Os tipos novos de resultado (C10, C12, C15) entram na lista fechada da regra `NEVER_SUM_MODES` no mesmo commit em que são criados. Uma mudança que quebre a assinatura de C8 a C16 só entra com a mudança de todos os consumidores no mesmo commit, porque é um só artefato (monólito modular).
9. **Esquema.** A migração `0023` só acrescenta a tabela lateral `real_entry_slot`, só de inserção. Nenhuma tabela do ledger é alterada. Uma mudança de esquema que remova algo fica para uma implantação separada, depois de nenhuma imagem usar o que sai (`team.md`, Deployment).

## Perguntas em aberto dos contratos

Ficam abertas só as perguntas abaixo, cada uma com o estágio dono.

| Contract | Question | Blocks |
|---|---|---|
| C8 (PQ-7) | Como os estados do bot (`DRAFT`, `VALIDATED`, `ACTIVE`, `PAUSED`, `CLOSING_PENDING`, `STOPPED`) viram `ACTIVE`, `PAUSED` ou `REMOVED` no P&L, e se os rascunhos aparecem nas linhas? | U1 (Functional Design da U1) |
| C15 (PQ-9) | O número do orçamento de tempo do RealBotHoldings dentro da transação do ciclo (trava de 2 s, limite de 5 s). | U4 (NFR Design da U4) |
| C12, C14 (PQ-10) | O índice final da leitura em fluxo com a junção em `real_entry_slot` e o resultado do cenário de carga do NFR1 (p95 de até 1 s, 10 bots, 2 anos). | U2 (NFR Design da U2) |

### Perguntas fechadas pelo fundador (2026-11-21)

| Pergunta | Contrato | Decisão do fundador | Ação no Functional Design |
|---|---|---|---|
| PQ-1 | C4, C5, C6 | O discriminador do `unknown` é `status`, como no contrato atual. | Nenhuma. |
| PQ-2 | C12, C13, C14 | O slot fica na tabela lateral nova `real_entry_slot` (`tx_signature`, `slot`, `recorded_at`), só de inserção e dona do `ledger`, com gatilho que recusa UPDATE e DELETE. O gatilho de `real_ledger_entry` não muda. `real_order` não ganha coluna: a ordenação lê o slot só pela tabela lateral, pela assinatura. | O Functional Design da U2 alinha o ADR-011 (coluna e preenchimento) a esta decisão. |
| PQ-3 | C7 | Campo aditivo `BotPage.realOwnQuantitySell` aceito. | Nenhuma. |
| PQ-4 | C14 | Nenhum preenchimento das linhas antigas: elas ficam `SLOT_MISSING`, com o motivo registrado, e usam o fallback do ADR-011. | O Functional Design da U2 alinha o ADR-011. |
| PQ-5 | C1, C3 | 409 `REAL_NOT_ELIGIBLE` para todo motivo de elegibilidade, inclusive a carteira inativa (hoje 422). | O Functional Design da U1 alinha o ADR-005. |

### Perguntas fechadas no Functional Design da U3 (2026-11-23, alinhamento A2)

| Pergunta | Contrato | Decisão | Onde |
|---|---|---|---|
| PQ-6 | C4 | Entra o parâmetro aditivo `market` (Q1 do Functional Design da U3): com ele, só o bloco pedido, e a chave do outro fica ausente; o "Tentar de novo" relê só aquele bloco (BR3.1, BR3.2). | C4 acima; `PnlController.byMode`. |
| PQ-8 | C6 | "Mesma estratégia" é o mesmo `templateId` (Q4 do Functional Design da U3, OQ4); bots da pessoa de `templateId` diferente dão 422 `TEMPLATE_MISMATCH` sem valor de P&L, sempre depois da posse dos dois (404 antes de 422) (BR5.1 a BR5.3). | C6; `PnlController.compare`. |

## Assumptions & Open Questions

- [assumption] O `period` aceita só `ALL` e `RANGE`. "Últimos 7 dias" e "Últimos 30 dias" são calculados no navegador, no fuso da pessoa, e enviados como `RANGE` em UTC, porque o servidor não conhece o fuso (FR7.2, interaction-spec "PeriodSelector").
- [assumption] Até a U2, `unowned` vem `null` e `prorated` vem vazio. A U1 não é implantada num ambiente com usuários antes da U2 (decisão do fundador, 2026-11-21), então nenhum usuário lê esse estado intermediário.
- [assumption] Sem carteira do bot ativa, a rota de limites (C3) responde 409 `REAL_NOT_ELIGIBLE` com `reason: WALLET_INACTIVE`, com o mesmo código da criação.
- [assumption] A falha fechada da elegibilidade (C2) e da criação REAL (C1) aparece como 503 `SERVICE_UNAVAILABLE`, um erro técnico repetível, separado do erro de domínio 409.
- [assumption] A conferência do FR5.3 (soma dos bots mais "Sem bot" igual ao ledger) é uma invariante testada de C10 e não ganha rota nova neste intent.
- [assumption] As linhas de C5 servem só aos bots REAL; o detalhe de um bot PAPER continua com `GET /api/v1/bots/{botId}/trades`.
- [assumption] `GET /api/v1/real/pnl` mantém a forma de hoje (`RealPnl`); só a fonte do número muda na U2 (ADR-001).
- [assumption] Os nomes `getRealEligibility`, `getRealAccountLimits`, `getPnlByMode`, `listBotPnlLines`, `comparePaperAndRealBots`, `streamInNetworkOrder`, `streamSettledOrders`, `recordSlot`, `openSells`, `NetworkOrderKey`, `real_entry_slot` e `0023-real-entry-slot.sql` são fixados aqui. O Functional Design pode ajustar detalhes internos, sem mudar a forma pública.
- O ADR-011 e o ADR-005 do domain design ficam desalinhados deste contrato até o Functional Design (U2 e U1), que os alinha às decisões do fundador de 2026-11-21. O `decisions.md` não foi editado nesta etapa.
- As perguntas abertas são PQ-7, PQ-9 e PQ-10, na tabela "Perguntas em aberto dos contratos". PQ-1 a PQ-5 foram fechadas pelo fundador em 2026-11-21; PQ-6 e PQ-8, no Functional Design da U3 (alinhamento A2, 2026-11-23).
