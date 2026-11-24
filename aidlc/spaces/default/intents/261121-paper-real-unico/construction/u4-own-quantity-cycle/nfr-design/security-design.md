# Desenho de segurança — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24.

A U4 mexe em dinheiro REAL: o ciclo de um bot passa a vender só a quantidade que ele mesmo comprou. Ela não cria rota, não cria caminho de assinatura ou de envio, não muda o saque e não muda o PAPER. Este arquivo diz como o desenho garante isso (NFR3.1 a NFR3.10, NFR4.1 a NFR4.3), seguindo as respostas confirmadas Q2 e Q3 [Q2] [Q3].

## Resumo das decisões

| Tema | Decisão | NFR |
|---|---|---|
| Tenant e posse | Tenant e pessoa vêm do `BotRef` (linha do bot, lida pelo despachante) e do `auth.as(owner)`. O RealBotHoldings só aceita o bot se ele está em `BotDirectory.botsOf(tenant, user, REAL)` no mesmo instantâneo; senão `Unavailable(BOT_NOT_OWNED)`, sem dado. | NFR3.1, NFR3.2 |
| Venda nunca acima da própria | Três barreiras em série: o mint sem disponível sai de `request.tokens()`; a marca do bot (`change_seq`) lida antes do instantâneo e de novo sob a trava; o corte `min(pedido, disponível)` aplicado **antes** de a decisão ir para a fila. | NFR3.4 |
| Nenhum caminho novo de envio | A venda cortada segue pelo `RealDecisionStep.submit` e pelo `RealExecutionPort.recordDecision` de hoje; o consumidor, a idempotência e o TxSigner não mudam. | NFR3.3 |
| Saídas do dinheiro | Saque, "Vender tudo e sacar", condicionais, liquidação e compras não dependem do RealBotHoldings (regra ArchUnit). | NFR3.5 |
| Flag | `@ConfigurationProperties`, padrão `false`, variável de ambiente separada nos mapas da API e do worker no Terraform; nenhuma rota, actuator gravável ou tabela. | NFR3.6 |
| Logs e métricas | Etiquetas só com enum; logs com uuid do bot e `correlationId`, nunca carteira, assinatura ou segredo. | NFR3.7, NFR3.9 |
| Migrações | `0026` só troca a regra de `bot_cycle.outcome`; `0027` só cria um índice parcial. Nenhum `GRANT`, `REVOKE` ou mudança de dado. | NFR3.8 |
| Modos | O ramo PAPER não lê a flag nem o RealBotHoldings; os tipos novos ficam no lado REAL. | NFR4.1, NFR4.2 |

## Isolamento entre contas (NFR3.1, NFR3.2)

**De onde vem o tenant.** O `SlotDispatcher` lê `tenant_id` e `owner_user_id` da própria linha do bot (como hoje, BR2.10 do intent anterior). O `CycleRunner` chama o RealBotHoldings com `bot.scope().tenantId()`, `bot.scope().ownerUserId()` e `bot.botId()`, dentro de `auth.as(bot.owner())`. Nenhum parâmetro vem de fora do worker.

**Conferência dentro do instantâneo.** O passo S1 (`performance-design.md`) lê `botsOf(tenant, user, REAL)`. Se o bot pedido não está nessa lista, a leitura para e devolve `Unavailable(BOT_NOT_OWNED)`: 0 quantidades, 0 custos, 0 vendas em aberto. O ciclo não vende (BR2.3). O log desse caso é `ERROR`, porque o despachante só entrega bots ativos do dono: é defeito, não ataque vindo de fora.

**Toda consulta filtra o tenant.**

| Leitura | Filtro |
|---|---|
| P1 (bot e M0) | `bot_id`, `tenant_id`, `owner_user_id` |
| S1 `botsOf` | `tenant_id`, `owner_user_id` (C8) |
| S2 carteira | `tenant_id`, `user_id` |
| S3 `view` | `tenant_id`, `bot_wallet_id` (C12) |
| S4 ordens | `tenant_id`, `user_id` e, por id, a mesma pessoa (C13) |
| S5 `openSells` | `bot_id` e `tenant_id` nas duas partes do `UNION ALL` |

Teste de unidade `OwnHoldingsSqlTenantTest`: as constantes SQL novas (`openSells`, P1) contêm o filtro `tenant_id = :tenant`. Teste de integração `RealBotHoldingsTenantIT`: duas pessoas de tenants diferentes, com o mesmo mint e ordens no mesmo slot; a quantidade, o custo e as vendas em aberto de cada uma não mudam quando se acrescentam ordens, lançamentos ou decisões `PENDING` da outra. T6: o id de um bot de outra pessoa e de outro tenant dá `BOT_NOT_OWNED` e nenhum dado. A leitura roda no worker com o tenant do bot, o que cumpre o teste obrigatório de tarefas em segundo plano do `team.md`.

## Venda nunca acima da quantidade própria (NFR3.4)

### Barreira 1: o mint sem disponível sai dos tokens do pedido (heldNotSellable)

No `CycleRunner.request`, ramo REAL com a flag ligada e o resultado `Available`:

1. `positions` recebe só as posições próprias com `available > 0` (quantidade = disponível, custo = `piso(ownCost × available ÷ own)` em lamports, convertido em US$ como hoje).
2. `heldNotSellable` = mints com quantidade na carteira (o `view` do ciclo) e sem disponível. Com `Unavailable`, todos os mints da carteira.
3. A lista de tokens é montada como hoje (`TokenSelectionResolver.resolve` com a seleção do bot e os tokens das posições) e, **antes** de `market.read`, os mints de `heldNotSellable` são tirados dela. O resultado é `request.tokens()`, que o motor avalia e que o `RealCycle.order` recebe.
4. O laço que fecha registros de abertura pula os mints de `heldNotSellable` (BR1.8).

Assim o motor nunca avalia saída nem compra desses mints, e o conjunto que impede compra continua igual ao da carteira (BR4.2): o motor de decisão e o `DecisionRequest` não mudam (NFR4.1).

### Barreira 2: a marca do bot (BR3.1, BR3.2)

- **M0:** a coluna `change_seq` da linha do bot, lida em P1, em autocommit, antes de o instantâneo abrir (`performance-design.md`).
- **M1:** o `change_seq` da linha devolvida por `LockOrder.lockBot` (`select ... for update` na transação do ciclo, READ COMMITTED). O `for update` devolve a versão confirmada mais nova, então nenhuma consulta a mais é preciso.
- **Comparação:** logo depois de `lockBot`, `stale = (M1 != M0)`. Só uma decisão SELL usa o resultado; uma compra segue como hoje.
- **Por que basta:** toda decisão do bot é gravada por um ciclo que tem a trava da linha e renova a marca na mesma transação (`bots.renewMark` no fim de `cycle`, BR3.2), e uma edição do bot (`save`) também renova. Uma decisão gravada antes de M0 já estava confirmada quando o instantâneo começou, então foi descontada (C16) ou está no ledger. Uma decisão gravada entre M0 e a trava muda a marca.
- **Teste da BR3.2:** `RenewMarkOnSellIT` confere que todo ciclo que termina `REAL_RECORDED` com SELL deixa `change_seq` maior que o M1 lido sob a trava.

### Barreira 3: o corte antes da fila (BR1.4, BR1.5)

`bots.domain.OwnSellCap` (nome proposto), função pura, avaliada **depois** de `engine.decide` e **antes** de `decisions.insert`, para que a nota entre no contexto da decisão (ver o alinhamento A10 em `logical-components.md`):

```text
verdict(outcome, holdings, m0, m1, decimals):
  if outcome is not SELL:                return NotApplicable
  if m1 != m0:                           return Unavailable(STALE_SNAPSHOT)
  if holdings is Unavailable(r):         return Unavailable(r)      # defesa; F2 já tirou os mints
  requested = floor(quantity * 10^decimals)   # BigDecimal.movePointRight, RoundingMode.DOWN, longValueExact
  available = holdings[mint].available, 0 when absent
  sell = min(requested, available)
  if sell == 0:                          return NoOwnQuantity
  return Sell(sell, capped = sell < requested)
```

`RealCycle.order` usa o veredito: `Sell(units)` vira `RealDecisionStep.Candidate.sell(..., units, ...)`, nunca `position.tokenBaseUnits()` da carteira. `Unavailable` termina com `REAL_OWN_QUANTITY_UNAVAILABLE` e `NoOwnQuantity` com `REAL_NO_OWN_QUANTITY`; nos dois casos 0 chamadas de `step.submit` e, portanto, 0 `recordDecision`. O consumidor da fila continua cortando por `min(decisão, posição da carteira)` (BR4.3).

**Testes escritos antes do código:** T1, T2, T4, T9 e T10 (`functional-spec.md`), mais `OwnSellCapTest` com os exemplos E3, E4, E5, E6 e E10 e a aritmética exata (`ArithmeticException` num estouro vira falha técnica do ciclo, nunca um valor truncado). A única exceção aceita é a do D2 (condicional entre a leitura e o processamento), documentada pelo T18.

### Corrida entre a conferência prévia e a trava (BR2.5)

Se a conferência prévia disse "não roda" (por isso nem M0 nem o RealBotHoldings foram lidos) e, sob a trava, o bot REAL roda, o ciclo usa `HoldingsSnapshot.NotRead`: tratado como `Unavailable(READ_FAILED)` na nota e no corte, sem venda. Ele **não** sobe `real_own_holdings_reads_total` nem `real_own_holdings_unavailable_total`, porque não houve leitura, e a razão do alarme continua ≤ 1 (`observability-design.md`). O log é `WARN` com `reason=READ_FAILED precheck=NOT_READ`.

## Nenhum caminho novo de envio (NFR3.3)

- A U4 não cria classe que chame o TxSigner, o RPC ou o KMS. A venda cortada passa pelas mesmas peças de hoje: `RealDecisionStep` (ORDER_IN_PROGRESS, LIQUIDATION_ACTIVE, posse, barreira, `recordDecision`), a fila, o `DecisionConsumer` com `withPersonLock` e a `Idempotency-Key` da ordem.
- Os índices únicos `uq_real_decision_request_decision`, `uq_real_order_bot_in_progress` e `uq_real_order_sale_in_progress` continuam como estão.
- T11 (uma transação só por decisão reprocessada ou repetida) e o teste obrigatório "REAL desligado por padrão" continuam verdes com a configuração padrão.

## Fronteiras verificadas por ArchUnit (T13)

Classe nova `apps/api/src/test/java/com/memetrade/architecture/OwnQuantityArchitectureTest.java`:

| Regra | O que proíbe |
|---|---|
| R1 | Só `com.memetrade.bots.worker..` depende de `pnl.api.RealBotHoldings` e de `pnl.api.HoldingsResult`. |
| R2 | `realexecution..`, `realexits..`, `botwallet..` (saque e "Vender tudo e sacar") e `riskcontrol..` não dependem de `pnl..` nem de `RealBotHoldings` (BR4.1, BR4.3). |
| R3 | As classes do RealBotHoldings (`pnl.application.RealBotHoldings*`) não chamam `LockOrder`, `WalletLock` nem `RealRiskGate.withPersonLock`, e não dependem de `bots.infra.JdbcCycleRepository` (NFR1.4). |
| R4 | As classes novas não usam `float`, `double`, `Float` nem `Double` (NFR2.6). |
| R5 | As classes novas não dependem de `ledger.api.PaperExecution`, `ledger.api.LedgerReadModel` nem dos tipos do P&L PAPER (NFR4.2). |
| R6 | `bots.domain.OwnSellCap` não depende de infraestrutura nem de Spring (pureza do domínio do `team.md`). |

Teste de unidade `OwnHoldingsSqlNoLockTest`: nenhuma constante SQL nova contém `for update`, `for share` nem `pg_advisory`.

## Saídas do dinheiro (NFR3.5)

Além de R2, o `OwnQuantityExitsIT` liga a flag e troca o RealBotHoldings por um que sempre lança erro de banco. O saque para a carteira principal, "Vender tudo e sacar" e a liquidação são aceitos (3 de 3). O teste obrigatório "saque só para a carteira principal registrada" continua verde.

## Flag (NFR3.6, NFR8.4)

```java
// bots.infra: a única fonte do valor da flag nos dois processos
@ConfigurationProperties(prefix = "memetrade.real")
public record OwnQuantitySellProperties(boolean ownQuantitySell) implements OwnQuantitySell {
    @Override public boolean enabled() { return ownQuantitySell; }
}
```

- `application.yml`: `memetrade.real.own-quantity-sell: ${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}`. O registro fica ao lado de `RealProperties` com o mesmo prefixo; o `RealProperties` não muda (o Spring liga cada registro só aos campos dele).
- `bots.api.OwnQuantitySell` (`boolean enabled()`) é lido pelo `CycleRunner` (worker) e pela montagem do `BotPage` (API, C7). Nenhum outro módulo lê a flag.
- Lida uma vez na subida; mudar o valor exige nova definição de tarefa.
- Terraform (`infra/terraform/main.tf`): `MEMETRADE_REAL_OWN_QUANTITY_SELL = "false"` em `api_environment` **e** em `worker_environment`, nunca em `app_environment`, porque os dois contêineres viram em implantações diferentes (D4, NFR2.8). Teste offline do Terraform: o valor é `"false"` nos dois mapas e a chave não existe em `app_environment`.
- 0 rotas, 0 endpoints de actuator graváveis (o actuator do worker não expõe `env` nem `refresh` com escrita) e 0 tabelas para a flag.
- Teste de contexto `OwnQuantitySellDefaultIT`: sem a variável, `enabled()` é `false` no perfil da API e no perfil `worker`; com `MEMETRADE_REAL_OWN_QUANTITY_SELL=true`, é `true`.

## Logs e métricas sem dado sensível (NFR3.7, NFR3.9)

- Etiquetas: `reason` ∈ {`BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`} e `outcome` ∈ {`CAPPED`, `NO_OWN_QUANTITY`, `STALE_SNAPSHOT`}; a métrica de leituras e o gauge `real_own_holdings_stuck_bots` (F3) não têm etiqueta. O mapa em memória do gauge é chaveado pelo uuid do bot, mas o id nunca sai numa etiqueta; ele só aparece no log `WARN` com o campo `streak`. Nenhuma etiqueta com tenant, pessoa, bot, mint ou carteira.
- Logs: `correlationId` (o `cycleId` no MDC, como hoje), `botId` (uuid), o enum e, no corte, as unidades base do pedido e da venda. O mint aparece só no `INFO` do corte (é um endereço de token público, não de carteira). Nunca endereço de carteira, assinatura de transação, token, `Authorization`, nonce ou SQL. A causa técnica vai como `exceptionClass` (`getClass().getSimpleName()`), como no `AttributedCostReader`.
- Teste de captura de log `OwnQuantityLogRedactionTest`: nos 5 eventos (indisponível, `STALE_SNAPSHOT`, `NO_OWN_QUANTITY`, `CAPPED`, `BOT_NOT_OWNED`), 0 registros com o endereço semeado da carteira do bot ou da carteira principal e 0 com a assinatura semeada.

## Migrações sem efeito em permissões nem dados (NFR3.8)

- `0026-bot-cycle-own-quantity-outcomes.sql`: só `ALTER TABLE bot_cycle ... DROP CONSTRAINT`, `ADD CONSTRAINT ... NOT VALID` e `VALIDATE CONSTRAINT` (desenho em `reliability-design.md`).
- `0027-real-decision-request-bot-pending-sell-idx.sql`: só `DROP INDEX CONCURRENTLY IF EXISTS` e `CREATE INDEX CONCURRENTLY` (`performance-design.md`).
- Nas duas: 0 `GRANT`, `REVOKE`, `INSERT`, `UPDATE` e `DELETE`; 0 mudanças em `real_ledger_entry`, `real_order`, `real_entry_slot` e `real_position_epoch`. O papel da aplicação já tem `SELECT` em `real_decision_request` e `real_order` (`0019`).
- `Migration0026IT`: conta as linhas de `bot_cycle` antes e depois (diferença 0) e confere, por teste de texto, que os dois arquivos só têm os comandos permitidos. O teste obrigatório "ledger sem edição" continua verde.

## Separação dos modos (NFR4.1 a NFR4.3)

- O ramo PAPER do `CycleRunner` (o `else` de `bot.real()`) não chama `OwnQuantitySell.enabled()`, as conferências prévias nem o RealBotHoldings. A fase prévia só começa com `market == REAL`.
- T14, escrito antes do código: o `DecisionRequest` do PAPER e o JSON gravado (`DecisionJson.write(request, outcome)`, sem notas) são iguais, campo a campo, com a flag ligada e desligada; 0 chamadas do RealBotHoldings e 0 leituras da flag no PAPER. `decision/domain` não muda.
- Os tipos novos (`HoldingsResult`, `BotHolding`, `OpenSell` com `source`) ficam em `pnl.api` e `realexecution.api`, no lado REAL (R5). `NeverSumModesArchitectureTest` e o teste da U1 continuam verdes.
- O aviso de `/bots` é desenhado em `logical-components.md` (componente web); T15 e T16 conferem que ele nunca aparece com o filtro "Simulado" nem com a flag desligada.

## Ameaças consideradas

| Ameaça | Controle |
|---|---|
| Ciclo atrasado vende de novo a mesma quantidade | Marca do bot (barreira 2), T10 |
| Decisão SELL ainda na fila vendida de novo | C16 estendido com `PENDING`, T4 |
| Ordem `AMBIGUOUS` esquecida | Conta como em aberto, T4 e T8 |
| Bot de outra pessoa ou tenant | `botsOf` no instantâneo, `BOT_NOT_OWNED`, T6 |
| Falha da leitura vira "vende a carteira inteira" | Falha fechada: `Unavailable` tira todos os mints dos tokens; nunca há caminho de volta para a posição da carteira com a flag ligada (BR2.3) |
| Flag ligada por fora da implantação | Sem rota, sem tabela, sem actuator gravável; só a variável versionada |
| Vazamento por log ou etiqueta | Etiquetas só enum, teste de captura de log |

A U4 não muda o modelo de ameaças do ADR-005: a política de conteúdo do TxSigner continua como a última barreira [assumption 2 do `security-requirements.md`].

## Sources

- `nfr-design-questions.md`: Q1, Q2 e Q3 e o resumo confirmado [Q1] [Q2] [Q3].
- `construction/u4-own-quantity-cycle/nfr-requirements/security-requirements.md` (NFR3.1 a NFR3.8, NFR4.1 a NFR4.3), `observability-requirements.md` (NFR3.9, NFR3.10), `scalability-requirements.md` (NFR8.4), `tech-stack-decisions.md` (NFR8.7).
- `construction/u4-own-quantity-cycle/functional-design/rules.md` (BR1.4, BR1.5, BR1.6, BR1.8, BR2.3, BR2.4, BR2.5, BR3.1, BR3.2, BR3.3, BR4.1 a BR4.4, BR5.2), `functional-spec.md` (F2, F3, T1 a T14), `entities.md` (ENT-406, ENT-407, ENT-409).
- `inception/contract-design/contract-summary.md`: C7, C8, C15, C16.
- `aidlc/spaces/default/memory/project.md` (Forbidden: logs de segredo, soma dos modos, ponto flutuante; Mandated: tenant, separação, REAL desligado, saque sempre disponível) e `team.md` (Testing Posture, Code Style: tenant, ArchUnit, Deployment: Flags).
- Código lido: `bots/worker/CycleRunner.java`, `RealCycle.java`, `SlotDispatcher.java`; `bots/application/RealDecisionStep.java`; `bots/infra/JdbcBotRepository.java` (`renewMark`, `save` com `change_seq`), `LockOrder.java` (`lockBot`); `botwallet/infra/RealProperties.java`; `apps/api/src/main/resources/application.yml`; `infra/terraform/main.tf`; `decision/infra/DecisionJson.java`; migrações `0019` e `0020`.

## Assumptions & Open Questions

1. [assumption] Dois registros `@ConfigurationProperties` com o prefixo `memetrade.real` convivem: o `RealProperties` ignora o campo novo, que é o comportamento padrão (`ignoreUnknownFields = true`). O `OwnQuantitySellDefaultIT` confere.
2. [assumption] O mint de token no log `INFO` do corte é dado público e não é dado pessoal nem endereço de carteira; se a revisão de privacidade pedir, ele sai e fica só o uuid do bot.
3. A corrida da BR2.5 (o bot roda sem a leitura) não sobe os contadores; isso é refinamento deste estágio sobre o texto da BR2.5 e está no alinhamento A12 de `logical-components.md`.
