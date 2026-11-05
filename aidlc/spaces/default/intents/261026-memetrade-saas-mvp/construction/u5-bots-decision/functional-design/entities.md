# Entidades — U5 Bots e decisão

Modelo de dados do BotManagement e do DecisionEngine. As fontes são:
- [unit-of-work.md](../../../inception/units-generation/unit-of-work.md) e [components.md](../../../inception/domain-design/components.md);
- os contratos C4, C7, C14, C15, C16, C18 e C20 de [contract-summary.md](../../../inception/contract-design/contract-summary.md);
- as respostas Q1 a Q13 de [functional-design-questions.md](functional-design-questions.md).

O bloco YAML é a fonte da verdade. Os nomes de atributos seguem o código, em inglês, e as descrições ficam em português. As escalas vêm da tabela "Escalas":
- valores em USDC: 6 casas;
- preços: 12 casas;
- percentuais: pontos com 4 casas;
- quantidades: as casas do mint.

Toda linha de dado de cliente carrega `tenantId`, `ownerUserId` e `mode`, e `mode` só aceita `PAPER`. A U5 não guarda ordens, lançamentos, posições nem recusas: esses dados são da U7 e da U6, lidos só pelas interfaces públicas.

```yaml
entities:
  - name: StrategyTemplate
    owner: BotManagement
    description: Estratégia modelo versionada (FR7.1). Semeada pela migração; o beta tem MOMENTUM_VOLUME e RANGE_BREAKOUT, versão v1.
    attributes:
      - { name: templateId, type: enum, required: true, unique: true, allowed: [MOMENTUM_VOLUME, RANGE_BREAKOUT] }
      - { name: version, type: text, required: true, constraints: "v1; uma versão nova vale só para bots criados depois" }
      - { name: name, type: text, required: true, constraints: "pt-BR" }
      - { name: description, type: text, required: true, constraints: "frase simples em pt-BR" }
      - { name: parameters, type: list, required: true, constraints: "ParameterSpec: key, label, unit, default, min, max, step (BR1.2)" }
    relationships:
      - { to: Bot, cardinality: "1:N", direction: "StrategyTemplate -> Bot" }

  - name: Bot
    owner: BotManagement
    description: Bot PAPER de uma pessoa, do rascunho ao encerramento (FR7.5).
    attributes:
      - { name: botId, type: identifier, required: true, unique: true }
      - { name: tenantId, type: reference, required: true, references: Tenant, constraints: "do contexto de autenticação" }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: name, type: text, required: true, max: 60 }
      - { name: templateId, type: reference, required: true, references: StrategyTemplate }
      - { name: templateVersion, type: text, required: true }
      - { name: parameters, type: map, required: false, constraints: "chave do ParameterSpec -> decimal; obrigatório a partir de VALIDATED" }
      - { name: tokenSelection, type: object, required: false, constraints: "TokenSelection; obrigatório a partir de VALIDATED" }
      - { name: virtualBalance, type: decimal, required: false, min: 100, max: 10000, constraints: "USDC 6 casas; obrigatório a partir de VALIDATED; teto e mínimo da plataforma (BR1.6)" }
      - { name: state, type: enum, required: true, allowed: [DRAFT, VALIDATED, ACTIVE, PAUSED, CLOSING_PENDING, STOPPED], default: DRAFT }
      - { name: pauseReasons, type: list, required: true, allowed: [PERSON, DAILY_LOSS, DRAWDOWN, SYSTEM, ADMIN_RELEASED, WAITING_ACTIVE_SLOT], default: "[]", constraints: "conjunto; vazio fora de PAUSED; ADMIN_RELEASED (BR7.2) e WAITING_ACTIVE_SLOT (BR2.6) são acréscimos à C4" }
      - { name: changeSeq, type: integer, required: true, constraints: "valor de uma sequência global do banco, renovado a cada mudança de estado, motivo de pausa ou limites; a marca da C18 é o máximo por pessoa (BR8.1)" }
      - { name: draftStep, type: integer, required: true, min: 1, max: 4, default: 1 }
      - { name: currentLimitsVersion, type: integer, required: false, constraints: "versão vigente; existe a partir de VALIDATED" }
      - { name: fundedAt, type: instant, required: false, constraints: "instante do FUNDING na U7 (primeira ativação)" }
      - { name: activatedAt, type: instant, required: false }
      - { name: pausedAt, type: instant, required: false }
      - { name: stoppedAt, type: instant, required: false }
      - { name: createdAt, type: instant, required: true }
      - { name: updatedAt, type: instant, required: true }
      - { name: version, type: integer, required: true, constraints: "controle de concorrência otimista nos comandos da API" }
    constraints:
      - "state = PAUSED se e somente se pauseReasons não é vazio."
      - "No máximo 3 bots ACTIVE por (tenantId, ownerUserId), garantido na transação de ativação (BR2.2)."
      - "STOPPED é terminal."
    relationships:
      - { to: BotLimitsVersion, cardinality: "1:N", direction: "Bot -> BotLimitsVersion" }
      - { to: BotCycle, cardinality: "1:N", direction: "Bot -> BotCycle" }
      - { to: Decision, cardinality: "1:N", direction: "Bot -> Decision" }
      - { to: BotHolding, cardinality: "1:N", direction: "Bot -> BotHolding" }

  - name: TokenSelection
    owner: BotManagement
    description: Valor embutido no bot com a lista explícita e os filtros (Q10).
    attributes:
      - { name: tokens, type: list, required: false, max: 10, constraints: "TokenId (chain, mint), sem repetição" }
      - { name: rules, type: list, required: false, max: 5, constraints: "field em [LIQUIDITY, VOLUME_24H, HOLDERS, AGE_MINUTES, RISK_SCORE], operator em [GTE, LTE], value decimal" }
    constraints:
      - "Pelo menos um token ou uma regra (AC4.1.5)."

  - name: BotLimitsVersion
    owner: BotManagement
    description: Versão imutável dos limites de um bot (FR7.3, FR7.8). Os valores vão para a U6 a cada ordem (BotLimitsVersionRef da C14).
    attributes:
      - { name: botId, type: reference, required: true, references: Bot }
      - { name: version, type: integer, required: true, min: 1 }
      - { name: maxOrderSize, type: decimal, required: true, constraints: "USDC, > 0" }
      - { name: maxTokenExposure, type: decimal, required: true, constraints: "USDC, > 0" }
      - { name: maxTotalExposure, type: decimal, required: true, constraints: "USDC, > 0" }
      - { name: maxDailyLoss, type: decimal, required: true, constraints: "USDC, > 0" }
      - { name: maxDrawdownPct, type: decimal, required: true, constraints: "pontos, > 0 e <= 100" }
      - { name: maxSlippagePct, type: decimal, required: true, constraints: "pontos, > 0 e <= 100" }
      - { name: minLiquidity, type: decimal, required: true, constraints: "USDC, >= 0" }
      - { name: minIntervalSeconds, type: integer, required: true, min: 1 }
      - { name: createdAt, type: instant, required: true }
      - { name: createdBy, type: reference, required: true, references: User }
    constraints:
      - "Única (botId, version); imutável depois de gravada."
      - "maxOrderSize <= maxTokenExposure <= maxTotalExposure (BR1.5)."

  - name: BotCycle
    owner: BotManagement
    description: Um ciclo de um bot ativo numa fatia de 5 minutos (Q6). Garante no máximo uma decisão por fatia e nenhuma repetição depois de reinício (AC4.5.4).
    attributes:
      - { name: cycleId, type: identifier, required: true, unique: true }
      - { name: botId, type: reference, required: true, references: Bot }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: slotStart, type: instant, required: true, constraints: "início da fatia de 5 min UTC" }
      - { name: status, type: enum, required: true, allowed: [RUNNING, COMPLETED, SKIPPED, FAILED], constraints: "RUNNING só dentro da transação do ciclo; nunca fica gravado depois do commit" }
      - { name: outcome, type: enum, required: false, allowed: [NO_ORDER, FILLED, NOT_FILLED, REJECTED, DUPLICATE, CONFLICT] }
      - { name: skipReason, type: enum, required: false, allowed: [NOT_ACTIVE, PAUSED, SLOT_EXPIRED] }
      - { name: failureCause, type: text, required: false, max: 200, constraints: "causa curta, sem dado pessoal" }
      - { name: decisionId, type: reference, required: false, references: Decision }
      - { name: startedAt, type: instant, required: true }
      - { name: finishedAt, type: instant, required: false, constraints: "preenchido ao final do ciclo, na mesma transação" }
    constraints:
      - "Única (botId, slotStart): a fatia nunca é processada duas vezes (BR3.2)."
      - "Inserida no início da transação do ciclo com RUNNING e completada no fim; num rollback, uma transação separada grava a fatia como FAILED (BR3.5)."

  - name: Decision
    owner: DecisionEngine
    description: Decisão explicada e reproduzível de um ciclo (FR8.1, AC4.5.1).
    attributes:
      - { name: decisionId, type: identifier, required: true, unique: true }
      - { name: botId, type: reference, required: true, references: Bot }
      - { name: cycleId, type: reference, required: true, unique: true, references: BotCycle }
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: mode, type: enum, required: true, allowed: [PAPER] }
      - { name: chain, type: text, required: false }
      - { name: tokenMint, type: text, required: false, constraints: "vazio só quando a seleção não tem token (NO_TOKENS)" }
      - { name: action, type: enum, required: true, allowed: [BUY, SELL, HOLD, NO_TRADE] }
      - { name: noTradeReason, type: enum, required: false, allowed: [UNKNOWN_DATA, STALE_DATA, INSUFFICIENT_HISTORY, ASSESSMENT_UNAVAILABLE, NO_POSITION_TO_SELL, RISK_BAND_HIGH, NO_SIGNAL, NO_TOKENS, QUANTITY_BELOW_MINIMUM] }
      - { name: exitReason, type: enum, required: false, allowed: [TAKE_PROFIT, STOP_LOSS, MAX_HOLD, RANGE_EXIT] }
      - { name: requestedQuantity, type: decimal, required: false, constraints: "só BUY e SELL; nas casas do mint" }
      - { name: strategyTemplateId, type: enum, required: true, allowed: [MOMENTUM_VOLUME, RANGE_BREAKOUT] }
      - { name: strategyVersion, type: text, required: true }
      - { name: parameters, type: map, required: true, constraints: "cópia dos parâmetros usados" }
      - { name: limitsVersion, type: integer, required: true }
      - { name: factors, type: list, required: true, constraints: "Factor: token, name, value (decimal ou UNKNOWN), threshold, satisfied" }
      - { name: inputs, type: list, required: true, constraints: "MarketValue com fonte e horário de cada dado usado" }
      - { name: context, type: object, required: true, constraints: "o que mais a decisão usou: saldo disponível, posições (token, quantidade, costBasis, openedAt) e faixa de risco por token; com inputs, permite recalcular a decisão (BR4.2)" }
      - { name: summary, type: text, required: true, max: 300, constraints: "frase simples pt-BR com ação e motivo" }
      - { name: validUntil, type: instant, required: true, constraints: "slotStart + 5 min" }
      - { name: createdAt, type: instant, required: true }
    constraints:
      - "action = NO_TRADE exige noTradeReason; action = SELL exige exitReason; BUY e SELL exigem token e requestedQuantity > 0."
      - "Imutável depois de gravada."

  - name: BotHolding
    owner: BotManagement
    description: Quando a U5 abriu a posição de um token, para a saída por tempo máximo (Q3). Mantida pela própria U5 a partir dos resultados da execução.
    attributes:
      - { name: botId, type: reference, required: true, references: Bot }
      - { name: chain, type: text, required: true }
      - { name: tokenMint, type: text, required: true }
      - { name: openedAt, type: instant, required: true, constraints: "instante do primeiro preenchimento BUY com a posição zerada" }
    constraints:
      - "Única (botId, chain, tokenMint); apagada quando a posição da U7 fica zerada."

  - name: BotOwner
    owner: BotManagement
    description: Linha de controle por pessoa, travada antes do bot em toda operação que conta ativos (BR2.11). Não guarda contador; a contagem é feita sob a trava.
    attributes:
      - { name: tenantId, type: reference, required: true, references: Tenant }
      - { name: ownerUserId, type: reference, required: true, references: User }
      - { name: createdAt, type: instant, required: true }
    constraints:
      - "Única (tenantId, ownerUserId); criada no primeiro POST /bots da pessoa (inserção que não aborta em conflito)."

  - name: SystemPauseState
    owner: BotManagement
    description: Cópia própria da U5 do estado do kill switch, gravada por applySystemPause e liftSystemPause (C16). Linha única.
    attributes:
      - { name: active, type: boolean, required: true, default: false }
      - { name: changedAt, type: instant, required: true }
      - { name: correlationId, type: text, required: true, max: 100 }
    constraints:
      - "Uma linha só. Gravada com a linha travada antes dos lotes (BR7.1); lida com trava compartilhada em start e resume (BR2.2, BR2.4)."
```

## Resumo

| Entidade | O que é |
|---|---|
| `StrategyTemplate` | As duas estratégias modelo v1 com parâmetros, padrões e faixas |
| `Bot` | O bot PAPER da pessoa: rascunho, configuração, estado e motivos de pausa |
| `TokenSelection` | Lista explícita de até 10 tokens e filtros do scanner |
| `BotLimitsVersion` | Versão imutável dos limites |
| `BotCycle` | Uma fatia de 5 minutos processada uma única vez |
| `Decision` | Decisão explicada e reproduzível |
| `BotHolding` | Horário de abertura da posição de um token, para a saída por tempo |
| `BotOwner` | Linha de controle por pessoa, travada antes do bot |
| `SystemPauseState` | Estado do kill switch guardado pela própria U5 |
