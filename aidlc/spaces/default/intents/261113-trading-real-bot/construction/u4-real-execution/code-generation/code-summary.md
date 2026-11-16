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

## Swap gravado

O responsável do projeto gravou duas vezes. A primeira gravação (mint BONK, pool Scorch, slot 469925246) não roda no validador local (ver "Primeira gravação, descartada"). A gravação em uso é a segunda: mint `Dz9mQ9NzkBcCsuGPFJ3r1bS4wgqKMHBPiVuniW8Mbonk` (USELESS, 6 casas), pool Raydium CP `Q2sPHPdUWFMg7M7wwrQKLrn619cAucfRsmhVJffodSp`, compra de 0,05 SOL, slot 469939807. Esta seção fecha o que faltava da Etapa 16.

### O que mudou

| Parte | Mudança |
|---|---|
| Gravação | `RecordedSwapFixture` (em `shared.config`): mint, casas decimais, lamports da compra e saída esperada. Só existe no perfil `test` e só com `RECORDED_MINT` preenchido (`RecordedSwapFixtureConfig`). Valores inválidos impedem a subida e o erro cita a variável. |
| Variáveis | O `infra/docker-compose.real.yml` passa o `recorded.env` à API e ao worker por `env_file` opcional (`required: false`). Sem o arquivo, as variáveis não existem, o `application-test.yml` as lê vazias e nada muda. |
| `recorded.env` | Ganhou `RECORDED_DECIMALS`, lido do byte 44 da conta do mint gravada (6 na gravação em uso). O `record.sh` passou a escrever essa linha. |
| Dados de mercado | No perfil `test` com a gravação, o `sim-a` publica também o SOL embrulhado (US$ 72,558, a cotação de prova da primeira gravação; a segunda dá US$ 71,797, diferença que não muda nenhuma regra, porque a barreira compara lamports) e o mint gravado (preço que bate com a cotação gravada). Liquidez, volume e holders são fixos e frescos em cada coleta. Os tokens fixos ficam depois dos gerados, então as regras por posição não mudam. O `sim-b` e os outros perfis não mudam. A avaliação do scanner fica disponível: liquidez, holders e volume conhecidos dão 80% de cobertura, o mínimo da versão 1 dos pesos. |
| Barreira (BR1.2) | Com o `sim-a` ativo, a barreira da U3 recusa toda ordem REAL (`SIMULATED_SOURCE_ACTIVE`). `RealGateInputsReader.source(intent)` passa a ler a fonte como REAL **só** no perfil `test` com a gravação e **só** para ordens do mint gravado (compra do bot e venda da liquidação). Qualquer outro mint e qualquer outro perfil continuam recusados. Ver "Desvio" abaixo. |
| Estratégia de teste | `RecordedSwapStrategy` (worker, perfil `test`). Só age no bot REAL cuja seleção é exatamente o mint gravado. Na primeira vez, compra exatamente `RECORDED_BUY_LAMPORTS`, pela mesma barreira e pela mesma M6 de qualquer bot REAL (`RealCycle.buyExactly`). Com a posição aberta, mantém e nunca vende. Depois de um ciclo `REAL_RECORDED` (`JdbcCycleRepository.hasOutcome`), não compra de novo. Se a barreira recusar, por exemplo antes do depósito ser creditado, nada é gravado e o próximo slot tenta de novo. |
| Spec `real-swap` | O spec agora: (1) sobe o modo para `AUTO_FULL` com o consentimento `RAISE_MODE` assinado pela carteira principal de teste; (2) deposita 1 SOL e espera o crédito; (3) cria e inicia, pelas rotas do assistente, o bot REAL do mint gravado; (4) espera a compra `CONFIRMED` (até 7 min, porque o ciclo roda uma vez por slot de 5 min), confere uma única compra de `RECORDED_BUY_LAMPORTS` e a posição de `RECORDED_SELL_UNITS` com custo e taxa; (5) pede "vender tudo e sacar" e confere a venda `CONFIRMED`, o saldo devolvido e a posição zerada. Faz login de novo antes da liquidação, porque o token dura 15 min. Continua pulado sem `RECORDED_FIXTURES`. |
| `validator.sh` | Os programas gravados sobem com `--upgradeable-program <endereço> <arquivo> none`, o mesmo carregador da mainnet (conta do programa e `ProgramData`), sem autoridade de upgrade. Antes subiam com `--bpf-program`, o carregador antigo, sem `ProgramData`. |
| Mapeamentos da Jupiter | **Defeito da gravação:** nos três mapeamentos de cotação, o `urlPath` saiu `C:/Program Files/Git/quote`, porque o Git Bash converte argumentos que parecem caminho antes de chamar o `jq.exe`. Com isso, o `jupiter-stub` nunca responderia à cotação. Os três foram corrigidos para `/quote`, e o `record.sh` desliga a conversão (`MSYS_NO_PATHCONV=1`). |
| `record.sh` | Ganhou a opção `--dexes`, com padrão `Raydium CP` (pool de produto constante, como pede o design). Mostra o pool escolhido. Avisa quando um programa gravado usa SBPF v3 ou mais novo. |

### Testes

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.shared.config.RecordedSwapFixtureTest,com.memetrade.marketdata.infra.RecordedSwapTokensTest,com.memetrade.marketdata.infra.simulated.*Test,com.memetrade.riskcontrol.real.infra.RecordedSwapSourceKindTest,com.memetrade.riskcontrol.real.infra.RealGateInputsReaderTest,com.memetrade.bots.worker.*Test,com.memetrade.architecture.*Test' -Djacoco.skip=true` | 124 testes, 0 falhas (21 novos) |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.bots.application.RealBotDecisionIT,com.memetrade.bots.worker.CycleIT,com.memetrade.config.ProcessRoleContextIT,com.memetrade.config.U4ContextIT,com.memetrade.riskcontrol.real.infra.TransactionalRealRiskGateIT,com.memetrade.riskcontrol.real.infra.RealGateInputsReaderIT,com.memetrade.marketdata.MarketCollectorIT' -Djacoco.skip=true` | 64 testes, 0 falhas |
| `apps/web`: `prettier` e `eslint` de `e2e/real-swap` e `e2e/real-bot-wallet`, `tsc --noEmit` e `playwright test --list` dos dois specs | sem erros; 7 testes listados |
| `docker compose ... config --no-interpolate` com as três sobreposições | válido; o `env_file` aponta para `infra/solana-fixtures/recorded.env` |
| Primeira gravação (Scorch) no validador local (agave v2.2.20), compra simulada sem assinatura | A compra **falha** dentro do Scorch: `Program ojh19ojaKduoJZuaJADhcVGp4xt1TcdAvZmpVsCorch ... Program is not deployed` (`UnsupportedProgramId`). |
| Gravação em uso (Raydium CP) no mesmo validador, compra e venda simuladas (ver abaixo) | compra e venda passam; a compra rende exatamente `RECORDED_SELL_UNITS` |

O `scripts/verify.sh` não foi rodado aqui (falta memória; quem conduz roda).

### Prova da gravação em uso no validador local

Um validador descartável agave v2.2.20 subiu pelo `validator.sh` com as gravações. Jupiter v6 e `CPMMoo8L3F4NbTegBCKVNunggL7H1ZpdTHKxQB5qKP1C` são SBPF v0 e subiram pelo carregador upgradeable. A carteira do bot `2Nk2WvdpeY9iu7eHm9QTCDDqkfQK5eeEE7qUYyK6TGaQ` recebeu 1 SOL por airdrop. As transações foram montadas como o servidor monta: só chaves estáticas, com a ordem das instruções da Jupiter. Rodaram em `simulateTransaction` sem assinatura (`sigVerify: false`). O contêiner e o script de rascunho foram removidos depois.

| Simulação | Resultado |
|---|---|
| Compra gravada (18 contas, 808 bytes) | sem erro, 99.268 unidades de computação; a conta de token da carteira do bot (`EiodpHwZ…`) termina com **35.728.672**, igual a `RECORDED_SELL_UNITS` e ao `outAmount` da cotação; a carteira paga 52.044.423 lamports (50.000.000 da troca, 2.039.280 de aluguel da conta de token e 5.143 de taxa, dos quais 143 são de prioridade) |
| Compra seguida da venda gravada de exatamente 35.728.672 unidades, numa só transação | sem erro, 168.393 unidades; a conta do token termina em 0 |
| A mesma, sem a instrução final que fecha a conta de SOL embrulhado | a venda rende **49.750.311** lamports, acima da cotação de venda (49.750.051) e do mínimo (48.755.050) |

A saída da compra bate exatamente com a cotação: o pool de produto constante só depende das reservas gravadas. Então a cotação de venda gravada (entrada de 35.728.672) serve, e nenhuma regra de produção precisa ficar mais solta. O `JupiterClient` continua recusando cotação de outro valor. A venda rende um pouco mais que a cotação de venda porque, na simulação, ela vem logo depois da compra, que pôs SOL no pool. No e2e acontece o mesmo.

Nenhuma conta falta. As contas não gravadas são nativas do validador (System, Token, Associated Token, Compute Budget) ou são criadas pela troca (`Cs1cNst6…` de SOL embrulhado e `EiodpHwZ…` do token, ambas da carteira do bot). A instrução da Jupiter é `route` (o discriminador que a política do TxSigner aceita), com derrapagem de 200 bps e sem taxa de plataforma. O teto de unidades é 1.400.000, a 102 microlamports, o que dá 143 lamports de prioridade, abaixo do teto de 1.000.000.

### Primeira gravação, descartada

1. **Programa SBPF v3.** O `ojh19ojaKduoJZuaJADhcVGp4xt1TcdAvZmpVsCorch.so`, que o Scorch chama, é um ELF SBPF v3 (`e_flags` 3). O validador do Compose (agave v2.2.20) não executa SBPF v3: o programa carrega, mas toda chamada falha. Jupiter v6 e Scorch são SBPF v0 e rodam.
2. **Pool de preço por oráculo.** O Scorch não é um pool de produto constante. O preço vem de contas que o formador de mercado atualiza a cada slot (`7s5B…`, `85Et…` e `HLix…`, do `ojh19…`) e da conta de instruções do sistema. Um pool assim costuma recusar preço vencido pelo slot ou pelo horário. No validador, o relógio é o de agora e o slot recomeça do zero, então a troca tende a falhar mesmo com um validador mais novo.
3. **Saída diferente da cotação.** As contas foram lidas alguns slots depois da cotação (cotação no slot 469925239, gravação no 469925246). Num pool de oráculo, a saída da compra pode não ser exatamente `154230000000`. Se não for, a venda gravada, que vale exatamente essa quantidade, não serve. O `JupiterClient` recusa a cotação de outro valor.
4. **Simulação da Jupiter.** As instruções gravadas vieram com `simulationError` "Attempt to debit an account but found no record of a prior credit", porque a carteira do bot não tinha SOL na mainnet. A conferência do passo 4 do `record.sh` nunca aconteceu de fato.

Nenhuma conta citada falta. Todas estão gravadas, são nativas do validador (System, Token, Associated Token, Compute Budget, Memo, `Sysvar1nstructions`) ou são criadas pela troca (as contas de token da carteira do bot `Cs1cNst6…` e `6qJBgsM7…`). A exceção é `9mYEd71VzXZoa8a3B3Zw5TG6C3N7qLRGnw2JdtocsrrS`: só leitura, também não existe na mainnet (o `record.sh` a pulou por `AccountNotFound`), e o programa aceita a conta vazia. A tabela de endereços `3soZX…` não é usada, porque o servidor monta a mensagem só com chaves estáticas (23 contas, 989 bytes).

Por isso o responsável gravou de novo com `--dexes "Raydium CP"`, o novo padrão do `record.sh`. Os arquivos da primeira gravação foram substituídos.

### Teste instável do depósito

**Sintoma.** Na CI (job "e2e", execução 43777057925, commit `4240ba9`), o teste `e2e/real-bot-wallet/bot-wallet.spec.ts` "a deposit of 1 SOL from the main wallet appears in the balance" não achou `real-wallet-total` por 2 minutos depois de cada `page.reload()`. O worker, porém, já tinha registrado o depósito cerca de 26 s após o início do teste. O mesmo `verify.sh` passou no job "smoke".

**Causa.** O token de acesso fica só na memória. Cada carga da página renova a sessão uma vez pelo cookie de renovação (`SessionProvider` → `refreshSession`). O servidor gira o cookie a cada renovação e detecta reuso (BR1.15, `SessionService.refresh`): um token de renovação já trocado **revoga a sessão inteira** (`REUSED`), sem tolerância. O teste fazia `page.goto` e logo em seguida `page.reload()`, e depois recarregava a cada tentativa com só 2 s de espera, sem esperar a tela chegar a um estado final.

Quando uma recarga cai com a renovação ainda em andamento, o servidor já trocou o token, mas o navegador descarta a resposta que trazia o cookie novo. A carga seguinte manda o cookie antigo, o servidor detecta reuso e revoga a sessão, e a página passa a ir sempre para `/entrar`. Por isso o elemento "não existe" pelos 2 minutos. O job "smoke" passou porque, ali, a renovação terminou antes da recarga: é uma corrida de tempo.

**Correção (só no teste; nenhuma regra de produção mudou).** O spec ganhou `settleWallet()`, usado por `openWallet()` e pelo novo `reloadWallet()`. Depois de cada navegação, ele espera até 30 s por um estado final da tela (saldo, ativação, erro ou a tela de entrada). Só então confere que a URL continua `/real/carteira` e que não há erro. Assim nenhuma navegação começa com a renovação anterior em andamento. Se a sessão se perder, o teste falha na hora com "the session was lost on the navigation", em vez de parecer saldo ausente. O laço de saldo do saque usa a mesma recarga. Essa conferência é a asserção de regressão.

**Observação de produto, sem mudança.** Uma pessoa que recarregue a página enquanto a renovação está em andamento também perde a sessão e volta para a entrada. É o efeito da detecção de reuso sem tolerância (BR1.15). Dar tolerância afrouxaria uma regra de segurança, então fica para o responsável decidir.

### Desvio

- **Leitura da fonte como REAL para o mint gravado (BR1.2 da U3).** O design dizia para colocar o mint gravado no `sim-a`. Só que, com qualquer fonte simulada ativa, a barreira recusa toda ordem REAL, então a compra do e2e nunca passaria. A exceção vale só no perfil `test`, só com a gravação e só para o mint gravado. Ela está coberta por `RecordedSwapSourceKindTest` (outro mint e sem gravação continuam `SIMULATED`) e por `RecordedSwapFixtureTest` (nenhum outro perfil cria a gravação). O `MarketDataConfig` também se recusa a subir com a gravação fora do perfil `test`.
- **Estratégia no worker, bot criado pelo spec.** O brief pedia a estratégia no `RealTestSeeder`. Só que o seeder roda na API, ao subir, e a decisão roda no worker. Um bot REAL também só pode ser criado com a carteira do bot ativa, o que acontece só durante o spec da carteira. Criar o bot antes faria ele comprar durante aquele spec, que deposita e saca tudo. Por isso a estratégia ficou no worker (`RecordedSwapStrategy`), e o spec do swap cria o bot pelas rotas do assistente, depois do depósito. O `RealTestSeeder` não mudou.

## Correções do ponta a ponta

A execução da CI do commit `605a8fb` (job "smoke") e o `verify.sh` local do mesmo commit falharam no spec `real-swap` (nenhuma compra em 7 min). Na CI também falharam `u8-admin-feedback` e `u9-dashboard`, ambos por tempo esgotado.

| Defeito | Causa | Correção | Teste de regressão |
|---|---|---|---|
| **Todo ciclo de bot REAL era desfeito** ("Bot cycle rolled back … cause=DATABASE, error=DataIntegrityViolationException" a cada slot) | O caminho REAL do ciclo grava `bot_cycle.outcome` como `REAL_<resultado>` e pula com `REAL_UNAVAILABLE`. Só que a tabela `bot_cycle`, da migração 0010, tem `outcome varchar(12)` e um CHECK que só aceita os valores do PAPER. `REAL_RECORDED` e `REAL_REJECTED` têm 13 caracteres. `skip_reason` também não aceitava `REAL_UNAVAILABLE`. Nenhum teste rodava o ciclo REAL contra as migrações reais: os testes do caminho REAL chamavam as etapas abaixo do ciclo. | Migração nova `0020-real-bot-cycle.sql`, só de expansão: `outcome` passa a `varchar(32)` (alargar não reescreve a tabela no PostgreSQL), e os CHECKs aceitam `REAL_RECORDED`, `REAL_ORDER_IN_PROGRESS`, `REAL_LIQUIDATION_ACTIVE`, `REAL_BOT_NOT_OWNED`, `REAL_REJECTED`, `REAL_UNAVAILABLE` e o pulo `REAL_UNAVAILABLE`, ao lado dos valores do PAPER. Tem rollback por changeset. | `RecordedSwapCycleIT`, com o worker do Compose REAL: perfis `test,worker`, REAL ligado, `RECORDED_*` de um mint sintético e migrações reais. Barreira, ledger, consulta de mercado e avaliação são reais, e a estratégia do swap gravado é ligada pelo Spring. O ciclo roda por `CycleRunner.run`. **Vermelho antes da migração:** os dois testes de ciclo terminaram com `status=FAILED`, `failure_cause=DATABASE` e o log "rolled back … DataIntegrityViolationException", o mesmo da CI. **Verde depois:** compra única de 50.000.000 lamports gravada (`REAL_RECORDED`); no slot seguinte, `NO_ORDER` sem segunda compra; a recusa da barreira sem depósito fica `REAL_REJECTED` e, depois do depósito, o slot seguinte grava a compra; todos os valores REAL de resultado e de pulo cabem no esquema. |
| **Parar um bot REAL falhava** | `BotCommandService.stop` chamava `paper.closePositions`. Um bot REAL não tem conta PAPER, então a chamada estourava com "No PAPER account for bot". O bot seguia ativo e ciclando. | Para um bot REAL, o `stop` não toca no ledger PAPER (U4-NFR2.1). As posições REAL ficam na carteira do bot e são vendidas por "vender tudo e sacar". | `RecordedSwapCycleIT.aRealBotStopsWithoutThePaperLedger`: o bot fica `STOPPED` e não existe linha em `paper_account`. |
| **Bot REAL do spec deixado ativo** | Quando o spec do swap falhava, o bot REAL da conta de teste continuava ativo e ciclando durante os specs seguintes. | O spec `real-swap` guarda o bot depois do início e, no `afterAll`, para o bot e exige 200. | o próprio `afterAll` |
| **`u8-admin-feedback` instável e `u9-dashboard` em cascata** | Não vêm do swap. No passo 4 do u8, o teste clicava em "Feedback" e só esperava o aviso do kill switch, que aparece em toda tela; logo clicava em "Kill switch". A navegação para `/feedback` ainda estava pendente e terminou por último. O instantâneo da página na CI mostra a tela de Feedback, e o botão "Desligar kill switch" "was detached from the DOM" até o tempo esgotar. Com isso o kill switch ficou ligado, e o u9 não conseguiu iniciar o bot: o instantâneo mostra "Pausado pelo sistema: o início está indisponível agora". No `verify.sh` local passou porque a navegação terminou a tempo. | O u8 agora espera a URL e a tela de cada navegação (`/feedback` com `feedback-page`, `/kill-switch` com `kill-switch-page`) antes do clique seguinte. O u9 volta a passar porque não herda mais o kill switch ligado. | as esperas novas do u8; o u9 continua sem mudança |

**Resultado.** Na CI do commit `6d1d849` (execução 43794459747), o swap gravado passou de ponta a ponta: o modo subiu para `AUTO_FULL`, a compra chegou a `CONFIRMED` em 2,7 min e o "vender tudo e sacar" levou 35 s. Sobraram quatro falhas, tratadas abaixo.

| Falha | Causa | Correção | Teste |
|---|---|---|---|
| **`u1-walking-skeleton/smoke.spec.ts`**: a repetição do nonce recebeu 429 em vez de 401 | O login é limitado a 10 tentativas por IP numa janela deslizante de 1 minuto (`LoginRateLimiter`). O limite é de propósito e não mudou. Os specs `real-*` rodam antes do `u1` em ordem alfabética. O spec da carteira já esperava uma janela inteira no `afterAll`, mas o `real-swap` entrava duas vezes (antes dos testes e antes da liquidação), mais uma vez no `afterAll` para parar o bot, e não esperava nada. | O `afterAll` do `real-swap` não faz mais login: usa o último token, que dura 15 min, enquanto o spec leva menos. Depois de parar o bot, espera uma janela de login inteira (65 s), como o spec da carteira. | o próprio spec; o efeito só aparece no `verify.sh` |
| **`u2-audit-trail/smoke.spec.ts`**: tempo esgotado (1 min) | É a mesma cascata. O u2 entra pela tela de login e espera ir para `/scanner` (`waitForURL` e `waitForResponse` de `/auth/verify`). Com 429, a tela não sai do login e o tempo esgota. | a mesma | — |
| **`AnonymizationMigrationIT`**: o rollback de 0015 deixou `wallet.address` anulável ("YES") | O teste desfazia um número fixo de changesets (`6 + 13 + 8 + 10`). A conta já ficava um changeset curta desde a 0017 (0016 tem 13 changesets e a 0017 tem 1), mas sobrava justamente um changeset de 0015 que não afetava as asserções. A 0020 somou mais um, e o rollback deixou de desfazer o changeset da coluna. | A contagem passou a ser lida dos arquivos, na ordem do `db.changelog-master.yaml`, de 0015 até a última migração (`changesetsFrom`). Um teste novo garante que a contagem cobre pelo menos os 39 changesets de hoje. | **Vermelho:** na CI. **Verde:** 6 testes, 0 falhas. |
| **`LoadtestRiskSeedIT.aSeedBelowTheAutovacuumThresholdReturnsWithoutWaiting`**: 57 s, contra o limite de 30 s | O seed espera até cada tabela semeada ter no máximo as modificações que levariam o autovacuum a analisá-la de novo, **seja quem for que as escreveu**. A base de teste é compartilhada. As suítes de ciclo, de risco e de carga que rodam antes, e a preparação dos bots do próprio teste, deixam `order_evaluation` e `order_rejection` acima desse limite até o autovacuum passar, uma vez por `autovacuum_naptime` (60 s). O seed de 3 linhas esperou essa passada: daí os 57 s. Não é efeito das tabelas nem dos gatilhos novos (as ordens REAL não escrevem nessas tabelas); é uma dependência de tempo que já existia. A regra do seed está certa para a carga real e não mudou. | Antes de medir, o teste espera, sem contar o tempo e com limite de 3 min, que as tabelas compartilhadas se assentem (duas leituras seguidas abaixo do limite). Depois mede só o seed pequeno, com o limite de 30 s mantido. | Verde: 2 testes, 0 falhas; o seed pequeno esperou 5 ms. Não houve vermelho local: a falha depende de em que ponto do ciclo de 60 s do autovacuum o teste cai. |

### Testes desta rodada

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.db.AnonymizationMigrationIT,com.memetrade.riskcontrol.LoadtestRiskSeedIT,com.memetrade.riskcontrol.loadtest.LoadtestRiskSeed*Test' -Djacoco.skip=true` | 17 testes, 0 falhas |
| `apps/web`: `prettier`, `eslint` de `e2e/real-swap` e `tsc --noEmit` | sem erros |

**Observação, sem mudança.** As decisões de um bot REAL continuam gravadas em `decision.mode = 'PAPER'`. O insert usa um valor fixo, e o CHECK da 0010 só aceita `PAPER`. Isso não quebra nada, mas a coluna não diz a verdade para bots REAL. Fica para decidir se vale outra migração de expansão.

### Testes

| Comando | Resultado |
|---|---|
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.bots.worker.RecordedSwapCycleIT' -Djacoco.skip=true` antes da migração | 3 testes, 2 falhas (`FAILED`/`DATABASE`, `DataIntegrityViolationException`) |
| Os mesmos depois da migração, mais `CycleIT`, `CycleFailureIT`, `BotCommandsIT`, `RealBotDecisionIT`, `RealExecutionPrivilegesIT`, `bots.db.*IT`, `architecture.*Test` e `bots.worker.*Test` | 152 testes, 0 falhas (`RecordedSwapCycleIT` com 5) |
| `apps/web`: `prettier`, `eslint` de `e2e/real-swap` e `e2e/u8-admin-feedback`, `tsc --noEmit` e `playwright test --list` | sem erros |

## Pendências

- **Swap no `verify.sh`:** a gravação em uso foi provada no validador local por simulação. A execução completa do spec `real-swap` (decisão do bot, TxSigner, envio e liquidação) depende da próxima rodada do `verify.sh`, que não foi rodado aqui.
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

O mint no `sim-a` e a estratégia de teste já leem o `recorded.env` sozinhos (seção "Swap gravado"). Grave com o padrão `--dexes "Raydium CP"`.
