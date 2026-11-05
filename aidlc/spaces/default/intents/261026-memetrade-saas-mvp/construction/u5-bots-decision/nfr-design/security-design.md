# Design de segurança e integridade — U5 Bots e decisão

Como a Unit `u5-bots-decision` atende NFR4.1 a NFR4.7, NFR5.1 a NFR5.3, NFR6.1 a NFR6.4, NFR7.1 e NFR8.1 de [security-requirements.md](../nfr-requirements/security-requirements.md).

## Integridade (NFR4)

- **Catálogos D1 a D18 e L1 a L14.** São testes de unidade sobre `DecisionEngine` (função pura) e sobre `BotStateMachine` (tabela de transições e motivos de pausa), escritos antes do código.
- **Comandos idempotentes.** Usam o `IdempotencyGuard` da U1, com operações novas `BOT_CREATE`, `BOT_DRAFT_SAVE`, `BOT_START`, `BOT_PAUSE`, `BOT_RESUME`, `BOT_STOP` e `BOT_LIMITS`. A mesma chave dá a mesma resposta e não gera evento novo.
- **Máximo de 3 ativos.** A contagem é feita com `BotOwner` travado (`SELECT ... FOR UPDATE`) antes do bot. A ordem segue a BR2.11.
- **Pausa.** O ciclo trava o bot e confere o estado e os motivos antes de qualquer chamada. Pausa e kill switch gravados antes vencem; gravados depois esperam a trava.
- **Dado ausente.** Os valores `MarketValue.Unknown` viram motivo de NO_TRADE ou tiram o token do filtro. O ArchUnit proíbe `BigDecimal.ZERO` no lugar de `Unknown` nos pacotes `bots` e `decision`.
- **Uma ordem por fatia.** `bot_cycle` tem restrição única (bot_id, slot_start). A execução usa a chave `order:<decisionId>`.
- **Sem ponto flutuante.** O ArchUnit cobre o backend, e uma regra de lint cobre o frontend (`DecimalString` nunca vira `number`).

## Isolamento e modo (NFR5)

- Todo repositório da U5 recebe `TenantScope` (tenant e pessoa do contexto) e filtra por ele. Não existe consulta por `botId` sem o escopo.
- As tarefas do worker montam o Principal do dono a partir da linha do bot. Ele é o registro público `identity.api.Principal`, com tenant, pessoa e papel `BETA_USER`.
- O Principal é ligado por um `CycleAuthContext` de produção em `bots.infra`, que implementa a interface pública `identity.api.AuthContext`. É o mesmo padrão do `LoadtestCycleAuthContext` da U7, mas sem perfil de teste.
- O worker registra esse contexto como o `AuthContext` principal, e só ele. A API mantém o contexto das requisições. O ArchUnit permite `bots.infra` → `identity.api`.
- A única exceção ao `TenantScope` é a consulta de marcas do canal ao vivo (performance-design.md).
- A migração `0010` tem `CHECK (mode = 'PAPER')`. O filtro comum da U1 já recusa `mode` diferente de `PAPER` com 403 auditado.

## REAL desligado e sem IA (NFR6.1, NFR6.4)

O ArchUnit confere que os pacotes `bots` e `decision`:
- não dependem de cliente HTTP externo, `org.sol4k`, assinatura, roteador nem de qualquer biblioteca de modelo;
- só dependem das APIs públicas `riskcontrol.api`, `ledger.api`, `pnl.api`, `marketdata.api`, `riskassessment.api`, `identity.api` e `audit.api`.

## Entrada (NFR6.2, NFR6.3)

- A validação no servidor é feita por `BotConfigValidator`, a mesma regra que o frontend repete.
- O JSON do rascunho recusa campos desconhecidos.
- Os erros saem em `ProblemDetail`, com `LIMIT_INCOHERENT`, `VIRTUAL_BALANCE_ABOVE_CAP` e `VALIDATION_FAILED`.
- Não há limite de requisições (Q4 dos requisitos).

## Logs e reprodução (NFR7.1, NFR8.1)

- Os logs levam só identificadores. O `LogScanIT` cobre um ciclo completo e os comandos.
- Cada Decision guarda `parameters`, `limitsVersion`, `inputs` e `context`. O `DecisionReplayTest` recalcula cada decisão do catálogo só com o que foi gravado.

## Ameaças

As ameaças são as de security-requirements.md. Cada uma tem o controle nomeado acima.
