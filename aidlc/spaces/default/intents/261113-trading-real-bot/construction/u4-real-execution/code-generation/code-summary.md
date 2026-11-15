# Resumo da geração de código — U4 `u4-real-execution` (Bolt B4)

Intent `261113-trading-real-bot`. Plano aprovado: `code-generation-plan.md` (18 etapas, todas marcadas). Contrato de testes: `custom`, estratégia `Comprehensive`; testes antes do código nas etapas marcadas `[testes antes]` (máquina de estados, idempotência, política de swap, ledger, P&L, banco e expurgo).

Nada foi enviado ao git (sem commit, sem push). Nenhum arquivo de estado, auditoria ou hook do AI-DLC foi alterado. `scripts/verify.sh` e `.github/workflows/*` não foram tocados: o texto para o responsável está no fim deste resumo.

## Etapas

Os números "vermelho" e "verde" são das execuções com o comando da Unit (escopo `-Dtest`), uma build Maven por vez.

| Etapa | O que foi feito | Vermelho (antes do código) | Verde |
|---|---|---|---|
| 1 — Esqueleto do módulo | Pacotes `realexecution` (`api`, `domain`, `domain.solana`, `infra`, `infra.jupiter`, `web`) e `pnl.real`; M6 `RealExecutionPort` e tipos; `RealRiskGate.withPersonLock`; `Base58Codec` movido para `shared.solana`; `RealExecutionProperties`, `RealExecutionSettings` e o bloco `memetrade.real.execution` do `application.yml`; worker com 19 threads e 27 conexões (`RuntimeSettingsGuard`, `application-worker.yml`, `docker-compose.yml`). | — (depois) | 30 testes: `ArchitectureTest` 11, `RealExecutionSettingsTest` 6, `RealExecutionArchitectureTest` 10, `RuntimeSettingsGuardWorkerCountTest` 3; `U5SettingsTest` 6 e `ComposeFilesTest` 28 |
| 2 — Regras de arquitetura | Rede só em `infra.jupiter`, nada de PAPER, só interfaces públicas, domínio puro, sem ponto flutuante, cada regra com fixture que falha. | — (depois) | incluído na etapa 1 |
| 3 — Mensagem e vetores [antes] | `MessageBuilder` v0 com chaves estáticas (ordem do Solana), `MessagePlan`, `ShortVec`, `WireTransaction`, `BuiltMessage`, `Ed25519Check`, `ComputeBudget`; vetores comuns gerados pelo `solanaj` 1.28.0 só em teste (`src/test/resources/solana-vectors/vectors.json`, igual nos dois apps). | compilação falhou (`AccountRef`, `MessageBuilder`, `WireTransaction`, `Ed25519Check`) | 19: `MessageBuilderTest` 12, conformidade 4, arquivos 2, geração 1 |
| 4 — Política de swap no TxSigner [antes] | `SwapPolicy`, `SwapContext`, `SwapCheck`, `SwapMode`, `SwapSide`, `ProgramAddress` (PDA e ATA próprios), 13 motivos novos em `PolicyRefusal`, `OperationKind.SWAP`, `SigningService.signSwap`, `SignatureContext` com `mode`, `side` e `inputBaseUnits`, teto `max-swap-priority-fee-lamports`, produção recusa `KMS_SIM_SEED` e rede errada, métrica `txsigner_swap_decisions_total`. | compilação do TxSigner falhou (`ProgramAddress`, `SwapPolicy`, `SwapContext`) | 48: `SwapPolicyTest` 29, `SigningServiceSwapTest` 9, conformidade 6, arquivos 1, `ProductionChecksSwapTest` 3; e `SigningServiceTest` 10, `ProductionChecksTest` 5 |
| 5 — Máquina de estados [antes] | `RealOrderStateMachine` (arestas, cancelamento, reenvio, prazo, rede) e `QuoteMath`. | compilação falhou | 29: `RealOrderStateMachineTest` 21, `QuoteMathTest` 8 |
| 6 — Migração 0019 [antes] | `0019-real-execution.sql` (10 changesets, rollback em cada um): `bot.market`, `real_decision_request`, `real_order`, `real_order_attempt`, `real_liquidation`, `real_upstream_health`, `real_execution_purge_run`, gatilhos de transição, de estado final e de idade, privilégios por coluna, únicos parciais. | 12 de 12 falharam na execução | 12: `RealExecutionPrivilegesIT` |
| 7 e 8 — Fila e condutor [antes] | `RealDecisionStep` e `RealCycle` no bot; `DecisionConsumer`, `OrderDriver`, `OrderTransitions`, `JdbcRealExecutionStore`, `RealOrderLedger`, `RealExecutionEvents`, `RealExecutionMetrics`, `JupiterClient`; M1 `RealExecutionWallet`, `RealChain`; TxSigner HTTP `signSwap`, `lowerMode`, `reachable`; `bots.market`. | compilação falhou | 35: `RealBotDecisionIT` 9, `RealBotSizingTest` 4, `DecisionConsumerIT` 8, `OrderDriverIT` 14 |
| 9 — Modo Assinada [antes] | `SigningSessionService`, `SigningDeadlineJob`, códigos `SIGNING_DEADLINE_EXPIRED`, `SIGNED_MESSAGE_MISMATCH`, `ORDER_ALREADY_IN_PROGRESS`. | compilação falhou | 11: `SigningSessionIT` |
| 10 — Confirmação e ambíguas [antes] | `ConfirmationJob`, `AmbiguousJob`, `UpstreamTracker`, `UpstreamHealthJob`. | 7 de 14 falharam na execução | 14: `ConfirmationAndAmbiguousIT` 9, `UpstreamFailuresIT` 5 |
| 11 — Ledger e P&L [antes] | `RealLedgerEntries`, `OrderSettlement`, `RealPnlCalculator`. | compilação falhou | 17: `RealLedgerEntriesTest` 6, `RealPnlCalculatorTest` 7, `RealSettlementIT` 4 (mais 9 de regressão verdes) |
| 12 — Modo e liquidação [antes] | `RealModeService`, `LiquidationService`, `LiquidationJob`, saque da liquidação pela M1. | compilação falhou | 11: `RealModeIT` 5, `LiquidationIT` 6 |
| 13 — Rotas e canal [depois] | `RealOrderController` (6 rotas), `RealPnlController`, `RealPnlService`, eventos `RealOrderStateChanged` e `RealSigningSessionOpened` no canal, `RealExecutionSchedule` (7 trabalhos do worker), 8 rotas CLIENT no `route-registry.yml`, OpenAPI e tipos do app web gerados de novo. | — | `RealRoutesIT` 6, `GuestOpenApiContractTest` 13, `RouteRegistryTest` 9, `U9OpenApiContractTest` 3, `DashboardStreamIT` 8 |
| 14 — Expurgo [antes] | `RealExecutionPurgeJob` (04:30 UTC, ShedLock, pool `risk_retention`, 5 anos e 13 meses, registro em `real_execution_purge_run`). | compilação falhou (`RealExecutionPurgeJob`) | 6: `RealExecutionPurgeIT` |
| 15 — Métricas, alertas e Terraform [depois] | `infra/observability/real-execution-alerts.yml` (14 regras), `infra/terraform/modules/observability/real-execution.tf` (namespace `Eureka/RealExecution`, sem o p95), `RealExecutionAlarmParityTest`, `RealExecutionMetricsTest`. | — | 11: `RealExecutionMetricsTest` 6, `RealExecutionAlarmParityTest` 5; promtool "SUCCESS: 14 rules found"; Terraform `fmt -check` sem diferença e "The configuration is valid." |
| 16 — Infraestrutura do swap [depois] | `KMS_SIM_SEED` no `kms-sim` (só com `KMS_SIM_PROFILE` test ou local); `infra/solana-fixtures/record.sh` (não executado); `validator.sh` com `--bpf-program` e `--account` para o que estiver gravado; `jupiter-stub` (WireMock 3.9.1 fixado por digest) no `infra/docker-compose.real.yml`; worker com `MEMETRADE_JUPITER_BASE_URL=http://jupiter-stub:8080`; spec `apps/web/e2e/real-swap/swap.spec.ts` (pulado até a gravação). | — | validador subiu pelo `validator.sh` sem gravações (saudável na 2ª tentativa); stub respondeu `/__stub/health`; chave do `kms-sim` determinística (jshell); `docker compose config --no-interpolate` válido; lint e tipos do spec ok |
| 17 — Medição informativa [depois] | `RealExecutionMeasureIT`: 21 pessoas, 4 decisões por segundo, 42 ordens. | — | 1; p95: gravação 19 ms, fila 14 ms, condutor 26 ms, confirmação 12 ms, decisão ao envio 43 ms (sem portão) |
| 18 — Documentação | Runbook `docs/runbooks/real-execution.md`, adendo da U4 no `contract-summary.md`, este resumo, `traceability.json`, `source-manifest.json`; `JupiterClientTest` (WireMock). | — | `JupiterClientTest` 7 |

## Defeitos achados e corrigidos (com teste de regressão)

1. **Transação idêntica de outra ordem (achado pela medição).** Duas compras seguidas com a mesma cotação e o mesmo blockhash davam, byte a byte, a mesma transação (Ed25519 é determinístico). A gravação de `SIGNED` batia no único de `tx_signature` e a ordem ficava parada em `QUOTED` para sempre. Agora a transação repetida é descartada, nunca enviada, e a ordem falha `BLOCKHASH_EXPIRED` com a reserva liberada (`OrderDriver.askSignatureOrThrow`). Regressão: `OrderDriverIT.anIdenticalTransactionOfAnotherOrderIsNeverSentAndTheOrderFailsBlockhashExpired` (vermelho antes da correção). A `FakeRealChain` passou a dar um blockhash novo a cada pedido, como a rede; `sameBlockhash = true` reproduz o caso.
2. **Transição sem transação perdia o evento de auditoria.** `OrderTransitions.move` era chamada fora de transação em alguns caminhos (cancelamento antes do envio, falhas antes de assinar): o estado era gravado sozinho e a publicação da auditoria (`MANDATORY`) falhava com `IllegalTransactionStateException`. `move` agora é `@Transactional` (entra na transação de quem chama ou abre uma). Regressão: `OrderDriverIT.theKillSwitchBetweenSigningAndSendingCancelsTheSignedOrder` confere o evento `REAL_ORDER_STATE_CHANGED` do cancelamento (vermelho: 0 eventos).
3. **Isolamento dos testes.** Com outros contextos Spring em cache, uma fonte de dados `u3-real` marcada `FAILED` deixava os sinais `unknown` e a barreira recusava (`STALE_DATA SIGNAL_UNKNOWN`). `AbstractRealRiskIT` restaura a fonte a cada semeadura; `AbstractRealExecutionIT` volta o relógio de teste ao início. `LiquidationIT` deixou de depender da ordem das posições (mints aleatórios).
4. **Princípio criado fora da autenticação.** `BotWalletService.raiseMode` montava um `Principal`, o que a regra da U2 proíbe. A M1 `applyModeConsent` passou a receber o `Principal` da sessão.
5. **Filtro do teste de contrato da U7.** `U7ContractsTest` filtrava as rotas por `/pnl` e passou a pegar `GET /api/v1/real/pnl`; o filtro agora deixa de fora `/api/v1/real/` (a C5 é PAPER). Achado no primeiro `verify` completo (1741 testes, 1 falha).
6. **`.env.example`** de outra Unit citava a palavra `NEXT_PUBLIC_` num comentário e quebrava o teste do app web que proíbe a palavra; o texto foi trocado.

## Comandos e resultados

| Comando (sempre na raiz do repositório) | Resultado |
|---|---|
| comando da Unit (`-Dtest='com.memetrade.realexecution.**.*Test,…,com.memetrade.architecture.ArchitectureTest'`) | 203 testes, 0 falhas, 0 erros (com a medição) |
| conjunto amplo (arquitetura, contratos, config, tenancy, U4, U3 REAL, botwallet, painel, novo aceite) | 454 testes, 0 falhas, 0 erros |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` (1ª) | 1741 testes, 1 falha (`U7ContractsTest`, defeito 5); a build parou nos testes, antes do JaCoCo |
| o mesmo `verify` (2ª, depois da correção) | **interrompido pelo sistema por falta de memória** depois de 397 classes sem falha; não foi repetido (regra do brief). `U7ContractsTest` sozinho: 6 de 6 |
| `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | **não executado**: a memória estava curta logo depois da interrupção acima. Escopo do TxSigner verde (48 + 15 testes nas etapas 4) |
| `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-execution-alerts.yml` | SUCCESS: 14 rules found |
| Terraform `fmt -check -recursive`, `init -backend=false`, `validate` (imagem `hashicorp/terraform:1.9.8`, cópia dentro do contêiner) | sem diferença; "The configuration is valid." |
| `corepack pnpm@10.18.3 -C apps/web gen:api`, `typecheck`, `check:api` | tipos gerados; sem erro; "schema.d.ts matches openapi-guest.yaml" |
| `corepack pnpm@10.18.3 -C apps/web test` | 550 testes: 549 verdes e 1 falha (`shell.test.tsx`, defeito 6); depois da correção, `shell.test.tsx` 20 de 20 |

## Cobertura

**Não medida nesta geração.** O piso de 80% (JaCoCo `check`) roda só no fim do `verify`, que não chegou lá (1ª: falha de teste; 2ª: interrompida por memória). Nenhum piso foi baixado e nenhuma exclusão foi acrescentada. O responsável precisa rodar, um de cada vez, com a máquina livre:

```bash
sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify
sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify
```

## Desvios do plano e decisões que mudam comportamento

- **Q2 (registrada no design):** a API monta a mensagem v0 com as chaves estáticas e o TxSigner só lê e confere. Os vetores comuns são gerados pelo `solanaj` em teste.
- **Queda do worker:** emulada por exceções nos pontos de queda (`CrashPoints`, `FakeCrashPoints`) e pelo vencimento do lease, sem fechar e reabrir contextos Spring.
- **Jupiter nos testes de integração:** `FakeSwapRouter`; o WireMock cobre o adaptador em `JupiterClientTest`. **TxSigner "em processo":** `FakeTxSigner`, que assina com chaves de teste; a política real é coberta no próprio TxSigner.
- **Cliente Jupiter:** `java.net.http.HttpClient` com Resilience4j, como o RPC da U1, em vez do `RestClient` do Spring. Só 5xx, 429, tempo esgotado e IO contam como indisponível; outro 4xx falha a ordem com `NO_ROUTE` (correção da revisão).
- **P&L REAL:** calculado a partir das ordens liquidadas da M6 (os valores lidos da rede gravados na ordem) e das cotações da M4, não dos lançamentos crus da M2; o aluguel pago pela carteira do bot entra no custo (correção da revisão); uma ordem que falhou na rede conta só a taxa.
- **Saque da liquidação:** pela M1, `BotWalletService.requestWithdrawalForLiquidation`, idempotente pela `liquidationId`.
- **Medidores do worker:** atualizados pelo `AmbiguousJob`, sem um oitavo trabalho agendado.
- **Cossinatura no modo Assinada com o TxSigner fora:** a ordem fica `QUOTED` e reservada (a rota responde 503) e o trabalho de prazo repete o mesmo pedido depois de 15 s.
- **`Idempotency-Key`:** obrigatória nas quatro rotas de escrita de R3, pelo mecanismo comum (correção da revisão).
- **DTO da ordem:** `RealTokenAmount{ mint, baseUnits, decimals? }`, com as casas decimais da M4 (ausentes quando desconhecidas, nunca zero).
- **Bot:** a coluna `mode` continua `PAPER`; a coluna nova `market` diz se é REAL. O bot REAL não recebe o saldo PAPER inicial e exige a carteira do bot ativa. `BotSummary` tem `market`; `BotDetail` ainda não.
- **Interfaces estendidas:** M6 ganhou `liquidationActive`, `settledOrders`, `changeMark` e `changedSince`; M1 ganhou `RealExecutionWallet`, `RealChain` e exceções; `SignerSettings`, `SignCommand` e `ProductionChecks` do TxSigner ganharam campos; o evento do canal se chama `RealSigningSessionOpened` (o `type` é o do contrato).
- **Banco:** coluna `requoted` entre as mutáveis; `signing_mode` (`SIGNED` também para as ordens a mercado de Automática nas saídas).
- **Expurgo na API:** a U4 recebe o pool `risk_retention` da U6 como exceção nomeada das regras de arquitetura (`RealExecutionArchitectureTest`, `RiskControlArchitectureTest`), como a U3. O pool de agendamento da API não mudou (21): o expurgo é diário, como o da U3.
- **Transação repetida:** falha `BLOCKHASH_EXPIRED` (defeito 1).

## Correções da revisão

Revisão da geração de código: READY, com 2 achados Major e 4 Minor. Os testes vieram antes do código onde o achado toca a lógica financeira ou a idempotência. Rodaram só comandos com escopo `-Dtest`, sem `verify` completo, uma build Maven por vez, todos a partir da raiz.

| Achado | Correção | Testes (vermelho, depois verde) |
|---|---|---|
| **Major 1 — 4xx da Jupiter** | Só 429, 5xx, tempo esgotado e IO são `RouterUnavailableException`: contam no circuito, no `UpstreamTracker` e na métrica. Qualquer outro 4xx vira `NoRouteException`, ignorada pelo circuito (`ignoreExceptions`). A ordem falha com o código novo `NO_ROUTE` (enum e restrição da 0019), sem contar falha nem pausar. O teste de saúde trata `NO_ROUTE` como fornecedor que respondeu. Nenhum alerta novo foi necessário: o rótulo `code` de `real_orders_total` já é aberto aos códigos do enum. | `JupiterClientTest.anyOther4xxIsARefusalOfTheRouteAndNeverOpensTheCircuit`: 10 respostas 400 e o circuito continua fechado. `UpstreamFailuresIT.aRefusedRouteFailsTheOrderNoRouteAndNeverCountsNorPauses`. Vermelho: compilação (`NoRouteException`). |
| **Major 2 — `Idempotency-Key` em R3** | `PUT /mode`, `POST signing-session`, `POST reject` e `POST /liquidations` passam pelo `IdempotencyGuard`. Operações novas: `REAL_MODE`, `REAL_SIGNING_SUBMIT`, `REAL_SIGNING_REJECT` e `REAL_LIQUIDATION`. O filtro comum já recusava a falta da chave com 400. A mesma chave devolve a mesma resposta; a mesma chave com outro conteúdo devolve `409 IDEMPOTENCY_CONFLICT`. `reject` e `liquidations` rodam numa transação com o registro da chave. `mode` e `signing-session` chamam o TxSigner pela rede e por isso ficam fora de transação: o registro grava primeiro e é liberado se houver erro. O adendo da U4 perdeu a exceção. OpenAPI e tipos web foram gerados de novo (`check:api` ok). | `RealRoutesIT`: mesma chave na liquidação, mesma chave no modo e conflito com outro conteúdo, mesma chave no `reject`, falta da chave nas quatro rotas. Escritos antes do código; só rodaram depois. |
| **Minor 3 — lease** | O condutor passa o próprio lease em `SIGNED`, `SENT`, `CANCELLED` antes do envio e nas falhas (`SIGNING_REFUSED`, `UPSTREAM_UNAVAILABLE`, `BLOCKHASH_EXPIRED`). O trabalho de prazo passa o lease dele. A sessão da pessoa e os cancelamentos de fora continuam sem lease. | `OrderDriverIT`: lease perdido durante a assinatura, lease perdido depois de `SIGNED`, recusa com o lease perdido. Escritos antes; só rodaram depois. |
| **Minor 4 — origem da rota** | O contexto R1 ganha `mint`, obrigatório na API, no `HttpTxSignerClient` e no `SignatureContext`. Na compra, a rota gasta a conta de SOL embrulhado da carteira do bot e recebe o mint da ordem. Na venda, gasta a conta da carteira do bot no mint da ordem. No `shared_accounts_route`, o mint de origem também é conferido. O valor gasto continua igual ao do contexto (`AMOUNT_MISMATCH`). Motivo novo: `SOURCE_NOT_ALLOWED`. | `SwapPolicyTest`, 6 testes novos de ataque: venda com valor maior, compra gastando outra conta, venda gastando outro mint, rota compartilhada com outro mint de origem, compra de outro mint, contexto sem mint. Vermelho: compilação. Verde: 35 de 35. |
| **Minor 5 — reserva com a carteira vazia** | `real_order.bot_wallet_id` é gravado na criação, na fila e na liquidação. A liberação (`RESERVE_RELEASE`) vai para essa carteira mesmo que ela não esteja mais ATIVA. Uma linha sem a carteira e sem carteira ativa falha fechada: o estado não muda, o log fica em ERROR e o trabalho repete. | `RealSettlementIT.aFinalOrderReleasesItsReserveEvenWhenTheWalletIsNoLongerActive`. Escrito antes; só rodou depois. |
| **Minor 6 — aluguel no P&L** | Coluna `real_order.rent_lamports`, gravada na confirmação com a mesma conta do lançamento `RENT` (`RealLedgerEntries.rentOf`). Entra no custo da posição e em `Result.rentLamports`. O Javadoc do arredondamento foi corrigido: o custo da parte vendida é arredondado para cima, então o realizado nunca é superestimado. | `RealPnlCalculatorTest`: o realizado de 97.950.720 lamports é igual ao ledger; a posição aberta carrega o aluguel. `RealSettlementIT`: o aluguel fica na ordem e no custo. Vermelho: compilação. |

**Defeito achado na revisão: o TxSigner gravava `SWAP` numa coluna que só aceitava `SWAP_BUY` ou `SWAP_SELL`.**
- Efeito: toda decisão de swap respondia 500, inclusive a recusa `REAL_DISABLED`. Achado pelo `RealOffByDefaultIT`, que até então só rodava no `verify` completo.
- Correção: migração nova `apps/txsigner/src/main/resources/db/changelog/changes/0002-swap-kind.sql`, com rollback, que aceita `SWAP`.

**Comandos desta rodada** (todos a partir da raiz):

| Comando | Resultado |
|---|---|
| `sh apps/txsigner/mvnw -B -ntp -q -f apps/txsigner/pom.xml test -Dtest='*Swap*Test,*SolanaVector*Test,*IT'` | 83 testes, 0 falhas |
| API: comando da Unit mais arquitetura, contratos, tenancy e botwallet (`-Dopenapi.write=true`) | 413 testes, 1 erro: o próprio teste novo fechava a carteira sem `closed_at` |
| `RealSettlementIT` depois de corrigir o teste | 6 de 6 |
| `corepack pnpm@10.18.3 -C apps/web gen:api`, `check:api` e `typecheck` | ok |

A cobertura continua sem medição: o `verify` completo não rodou, por causa da memória.

## Correções da CI

A execução 43769144828 do GitHub Actions, no commit f394d48 (U1 a U4 em `main`), falhou em dois jobs. Os dois eram defeitos reais. Cada correção tem um teste de regressão, que vi falhar antes de corrigir.

| Job | Causa raiz | Correção | Teste de regressão |
|---|---|---|---|
| **smoke** (`scripts/verify.sh`): o worker não subia e, sem o leitor de depósitos, o spec da carteira falhava. | Com as duas URLs de RPC configuradas, o bean `chainPort` é um `RpcChainWatcher`, que é ao mesmo tempo `ChainPort` e `SwapChain`. O bean `swapChain` devolvia a mesma instância. O Spring via então dois `SwapChain` (e, com um qualificador, passaria a ver dois `ChainPort`). Nenhum teste de contexto subia o worker com RPC e sem dublês, por isso o erro só aparecia no smoke. | O bean `swapChain` saiu do `BotWalletConfig`. `RealChainAdapter` recebe só o `ChainPort` e deriva as leituras de swap com `SwapChain.of(chain)`: o próprio watcher, ou leituras sempre indisponíveis quando não há RPC. Agora há um único bean de cada tipo por processo. | `RealWorkerWiringIT` sobe o worker como no Compose: perfis `test,worker`, `MEMETRADE_PROCESS=worker`, REAL ligado, TxSigner, as duas URLs de RPC e o `jupiter-stub`, sem nenhum bean de teste. **Vermelho:** "expected single matching bean but found 2: chainPort,swapChain", a mesma mensagem da CI. **Verde** depois da correção. `RealApiWiringIT` faz o mesmo para a API, com perfil `test`, REAL ligado, `seed-test-release` e a carteira do smoke sintética; passou. |
| **backend**: `AuditOutboxIT.aRepeatedEventIdIsDiscardedWithoutError` (linha 82). | O teste estava errado: assumia que o seu evento entra no primeiro lote do relay. O relay entrega do mais antigo para o mais novo, um lote por vez, e o banco é compartilhado entre as classes de teste. U3 e U4 publicam muitos eventos de auditoria sem passar o relay. Quando essas classes rodam antes (a ordem na CI Linux não é a do Windows), o evento do teste fica atrás de mais de um lote. O código está correto. | `AuditOutboxIT` agora entrega lote após lote até o evento sair da outbox (`relayUntilDelivered`), também no teste de entrega única e no de reinício. Em `AuditPublisherIT`, a deduplicação passou a esvaziar todos os lotes pendentes. | `AuditOutboxIT` cria antes um acúmulo explícito de mais de um lote de eventos mais antigos (`olderBacklogOfMoreThanOneBatch`), o que torna o teste independente da ordem. **Vermelho:** "Expecting value to be false but was true", a mesma mensagem da CI. **Verde** depois da correção. |

**Papéis conferidos**
- Worker e API: pelos dois testes novos de contexto.
- TxSigner: `InternalApiIT` (REAL ligado) e `RealOffByDefaultIT` passaram na rodada da revisão (83 testes). O TxSigner não tem a ambiguidade, porque não tem o `ChainWatcher`.

**Comandos** (todos a partir da raiz, uma build Maven por vez, só com escopo):

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -q -f apps/api/pom.xml test -Dtest='com.memetrade.config.RealWorkerWiringIT,com.memetrade.config.RealApiWiringIT'` antes da correção | `RealWorkerWiringIT` com 1 erro (a ambiguidade da CI) |
| `sh apps/api/mvnw -B -ntp -q -f apps/api/pom.xml test -Dtest='com.memetrade.audit.AuditOutboxIT'` com o acúmulo e antes da correção | 8 testes, 1 falha (a mesma da CI) |
| Depois das correções: `AuditOutboxIT`, `AuditPublisherIT`, `AuditRelayMeasureIT`, os dois testes de contexto, `config.*IT`, `botwallet.**.*IT`, `realexecution.infra.*IT` e `architecture.*Test` | 218 testes, 0 falhas |

## Pendências

- **Gravação do swap (mainnet):** `infra/solana-fixtures/record.sh` foi escrito e **não** foi executado. O spec `apps/web/e2e/real-swap/swap.spec.ts` fica pulado até existir `RECORDED_FIXTURES`. Nenhum outro teste depende dele.
- **Mint gravado no `sim-a` e estratégia de teste no `RealTestSeeder`:** dependem do mint gravado e não foram feitos nesta Unit. Sem eles o bot REAL do e2e não decide comprar; o spec continua pulado.
- **`BotDetail.market`** e a tela do app web para ordens, modo e P&L REAL (só os tipos foram gerados).
- **Caminho das métricas até o CloudWatch** (pendência comum de U1 a U4) e o alarme do p95 (adiado).

## O que o responsável do projeto precisa aplicar ou rodar

### 1. Job do TxSigner na CI (`.github/workflows/ci.yml`)

Se a CI ainda não roda o `verify` do TxSigner com o piso de 80%, acrescente um job (ações fixadas pelo SHA já usado no arquivo, permissões só de leitura):

```yaml
  txsigner:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@<SHA já fixado no ci.yml>
      - uses: actions/setup-java@<SHA já fixado no ci.yml>
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: TxSigner verify (testes, JaCoCo 80%, Spotless)
        run: sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify
```

### 2. Linha do `promtool` na CI

Na etapa que já confere `real-risk-alerts.yml`, acrescente:

```bash
docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-execution-alerts.yml
```

### 3. Mudanças no `scripts/verify.sh`

1. Esperar o `jupiter-stub` saudável junto com o validador, na sobreposição do REAL:
   ```bash
   wait_healthy jupiter-stub   # mesmo laço já usado para solana-validator e txsigner
   ```
2. Incluir o serviço novo na varredura de log por segredos e dados sensíveis (`jupiter-stub` ao lado de `txsigner`, `kms-sim` e `solana-validator`).
3. Rodar o spec do swap depois do spec da carteira, passando as gravações quando existirem:
   ```bash
   if [ -f infra/solana-fixtures/recorded.env ]; then
     export RECORDED_FIXTURES="$PWD/infra/solana-fixtures/recorded.env"
   fi
   pnpm --dir apps/web exec playwright test e2e/real-bot-wallet e2e/real-swap
   ```

### 4. Gravação, uma vez, com rede (fora dos testes)

```bash
# 1) rode uma vez o spec da carteira (bash scripts/verify.sh) e leia o endereço da carteira do bot
#    (GET /api/v1/real/bot-wallet, campo address); com KMS_SIM_SEED ele é sempre o mesmo
# 2) grave (somente leitura na mainnet; não assina nem envia nada)
bash infra/solana-fixtures/record.sh --mint <mint de um pool CPMM de um salto contra SOL> --bot-wallet <address>
# 3) confira que as duas pernas executam: RECORDED_FIXTURES=infra/solana-fixtures/recorded.env bash scripts/verify.sh
```

Depois da gravação faltam o mint no `sim-a` e a estratégia de teste no `RealTestSeeder` (pendência acima).
