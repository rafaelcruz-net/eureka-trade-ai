# Componentes lógicos — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes:
- `construction/u7-exits-and-fiscal/functional-design/` (BR0 a BR9, S1 a S50);
- `construction/u7-exits-and-fiscal/nfr-requirements/` (U7-NFR*);
- `nfr-design-questions.md` (Q1 a Q7);
- o código atual de `realexecution`, `botwallet`, `riskcontrol`, `ledger` e do TxSigner.

## Componentes novos

### `apps/api`, módulo `realexits`

| Componente | Onde | Processo | Papel |
|---|---|---|---|
| `ConditionalOrder` e `ConditionalStateMachine` | `realexits.domain` | os dois | estados e transições (`entities.md`), compare-and-set |
| `TriggerValidation` | `realexits.domain` | API | regras de BR3 e limites de BR2.6, sem I/O |
| `ConditionalService` | `realexits.application` | API | criação: flag, barreira M5, posição pela M2, preço pela U2, Jupiter, sessão de assinatura; recebimento da assinatura; `DELETE` |
| `TriggerPort` | `realexits.domain` | os dois | porta X1: criar, cancelar, ler ordens da carteira |
| `SimulatedTriggerAdapter` | `realexits.infra` | os dois | rede local e testes; não envia transação (BR0.3) |
| `JupiterTriggerAdapter` | `realexits.infra.jupiter` | os dois | Trigger API v2 com o `RestClient` que já existe; ativo só depois do portão BR0 |
| `ConditionalReadJob` | `realexits.infra` | worker | a cada 30 s: lê a Jupiter, confere na rede (`finalized`) e lança pela M2 (BR5) |
| `ConditionalCancelJob` | `realexits.infra` | worker | a cada 1 min: kill switch (M7), troca de modo, revogação e tentativas novas de `CANCELLING` (BR6) |
| `FillPosting` | `realexits.application` | worker | um `SWAP_FILL_OUT` e, quando houver, um `NETWORK_FEE` pela M2, com chaves `cond:<sig>:fill` e `cond:<sig>:fee`, na mesma transação do `FILLED` (BR5.3) |
| `ConditionalController` | `realexits.web` | API | rotas R5 do adendo |
| `FiscalExportController` e `FiscalCsvWriter` | `realexits.fiscal` | API | exportação em fluxo (BR7) |
| `RealExitsMetrics` e `RealExitsGaugesJob` | `realexits.infra` | worker | métricas e medidores (U7-NFR5) |
| `RealExitsPurgeJob` | `realexits.infra` | API | retenção de 5 anos pelo pool `risk_retention` |

### TxSigner

| Componente | Onde | Papel |
|---|---|---|
| `TriggerPolicy` | pacote `policy` do TxSigner | BR2, registrada só com o `trigger-program.json` válido (Q4) |
| `trigger-program.json` | recursos do TxSigner | gerado pela gravação real e conferido por hash |

### Navegador

| Componente | Onde | Papel |
|---|---|---|
| Tela | `apps/web` `features/real` | diálogo "Definir stop e alvo", aba "Condicionais", botão "Exportar histórico (CSV)", modo habilitado por `exitsEnabled` (BR8) |

## Interfaces de extensão (Q2, sem ciclo)

| Interface | Declarada em | Implementada por | Uso |
|---|---|---|---|
| `PositionExitGuard` | `realexecution.api` | `realexits` | antes de toda venda a mercado e da liquidação: cancela a condicional e devolve a posição relida, ou "aguarde" (BR4.3) |
| `EscrowBalances` | `botwallet.api` | `realexits` | saldo das custódias abertas por mint, para a reconciliação (BR5.3b) |
| `OpenExitsCheck` | `botwallet.api` | `realexits` | recusa `OPEN_CONDITIONALS` na anonimização (BR6.9) |
| `ExitsAvailability` | `botwallet.api` | `realexits` | flag para a troca de modo e para o `exitsEnabled` de `/real/release` (BR8.1, BR9.3) |

Cada interface tem uma implementação vazia (nada aberto, flag desligado), usada quando o módulo `realexits` não está no contexto.

## Mudanças em outras Units

| Unit e código | Mudança |
|---|---|
| U1 M2 | `streamEntries(tenant, userId, from, to, consumer)`, leitura em fluxo com o índice que já existe |
| U4 `DecisionConsumer`, `LiquidationJob` e venda da pessoa | chamam o `PositionExitGuard` antes de cotar uma venda; com posição zero, `NOTHING_TO_SELL` |
| U4 troca de modo | `ExitsAvailability` para subir a `AUTO_EXITS`; ao sair de `AUTO_EXITS`, avisa a U7 pelo evento de troca de modo que já existe |
| U5 `ReconciliationReader` e `ReconciliationPolicy` | somam as custódias (`EscrowBalances`); uma leitura de custódia que falha dá `UNAVAILABLE` |
| U5 `closeForAnonymization` | consulta o `OpenExitsCheck` |
| U6 `/real/release` | campo `exitsEnabled` |
| U3 M5 | sem mudança; a U7 usa `evaluate` com uma operação de venda condicional, e `withPersonLock` |

## Ajustes da revisão (iteração 1)

### Venda atrás do `PositionExitGuard` (sem rede dentro da trava)

O `DecisionConsumer` só fecha uma decisão como `PROCESSED` ou `DISCARDED`. Não existe espera nem nova tentativa. Por isso:

- **O guard não chama rede.** Dentro do `withPersonLock` e da transação da decisão, o `releaseForSale` só lê o estado da condicional e responde de uma de três formas:
  - sem condicional não terminal: `READY` com a posição do ledger;
  - condicional `PENDING_SIGNATURE`: passa para `CANCELLED` direto (BR6.5) e responde `READY`;
  - condicional `OPEN` ou `CANCELLING`: marca `CANCELLING` com `cancel_reason = MARKET_SELL` (só gravação no banco) e responde `WAIT`.

  Quem monta e envia o cancelamento é o `ConditionalCancelJob`, fora da trava.
- **Desfecho novo na U4, `DEFERRED`.** A decisão grava `not_before = now + 30 s` e `attempts + 1` e é liberada. O consumidor ignora decisões com `not_before` no futuro.
  - Com 10 tentativas (cerca de 5 min), a decisão vira `DISCARDED` com o motivo `EXIT_CANCEL_TIMEOUT`, e o alerta U7-NFR5.7 já cobre esse caso.
  - A migração 0022 acrescenta `not_before` e `attempts` em `real_decision_request` (expand-only, com valor padrão).
- **Liquidação (U4).** Uma posição com `WAIT` fica para a próxima rodada do `LiquidationJob`. A liquidação só termina `DONE` ou `PARTIAL` quando todas as posições tiverem resposta final, no limite que a U4 já usa.
- **Venda pedida pela pessoa.** Responde `409 EXIT_CANCELLING`, com o texto "Cancelando a saída automática; tente em instantes".
- **Trava.** O `withPersonLock` não é reentrante, e o guard nunca pega trava: ele roda dentro da trava de quem chama.
- **Ajustes da iteração 2:**
  - As marcações `CANCELLING` e `CANCELLED` feitas pelo guard passam pela `ConditionalStateMachine`, com compare-and-set, como qualquer outra transição.
  - Um teste do `LiquidationJob` confere que uma posição em `WAIT` não bloqueia a venda das outras posições na mesma rodada.

### Limite da exportação

O `PersonRouteRateLimiter` tem janela fixa de 60 s. A U7 passa a janela e o limite como parâmetros do construtor. Os usos atuais continuam com o padrão de 60 s, e o despejo de chaves ociosas usa a janela de cada limitador. O `FiscalExportLimiter` usa janela de 1 h e limite de 10, com um teste da 11ª exportação e do despejo.

### Evento `real.conditional.state`

O canal ao vivo (`DashboardMarkPoller`, em `bots.web`) já puxa as mudanças REAL pela `RealExecutionPort` (`real.order.state`). Do mesmo jeito:
- **Interface:** `realexecution.api.RealExitChanges`, com `changedSince(tenant, userId, since)`, implementada pela `realexits` (inversão de dependência, como em Q2).
- **Envio:** o poller envia `real.conditional.state` com o DTO `DashboardEvents.RealConditionalStateChanged` (`conditionalOrderId`, `positionMint`, `state`, `filledSide`, `at`).
- **Contrato:** adendo da U7 no AsyncAPI R6.
- **Web:** o `warn.ts` e o `envelopes.ts` aceitam o evento novo.
- **Sem a U7:** a implementação vazia não devolve nada.

### Ajustes menores

- **Implementação vazia das quatro interfaces de extensão e da `RealExitChanges`:** um `@Bean` com `@ConditionalOnMissingBean` na configuração do módulo dono, que é o padrão do `identity`.
- **`streamEntries`:**
  - roda numa transação somente leitura aberta durante o fluxo, com `fetchSize` 500;
  - usa o índice (tenant, `created_at`), e o filtro por pessoa é aplicado depois. Isso basta, porque o período é de no máximo 1 ano e o piloto é pequeno;
  - a coluna `reference` e o tamanho da chave de idempotência são conferidos contra a migração 0016 na geração de código; se `cond:<sig>:fill` não couber, usa-se o hash SHA-256 da assinatura.
- **Números da API:** o mínimo do scheduler vai de 22 para 23, por causa do expurgo novo.
- **Hash do `trigger-program.json`:** fica no arquivo `trigger-program.json.sha256`, versionado ao lado. Em produção, também fica na configuração `txsigner.trigger.program-sha256`, conferida na subida.
- **ArchUnit:** `realexits` só pode depender de `botwallet.api`, `ledger.api`, `riskcontrol.api`, `realexecution.api`, `marketdata.api` e `shared`, numa regra nova com teste que comprova a falha.

## Fronteiras

- `realexits` não importa PAPER nem classes internas de outros módulos.
- `realexecution` e `botwallet` não importam `realexits` (ArchUnit).
- Rede só em `realexits.infra.jupiter` e no RPC que já existe.
- Assinatura só pelo TxSigner.
