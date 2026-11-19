# Resumo da Code Generation — U3 `u3-real-risk` (Bolt B3)

Conversa: português (Brasil). Fontes: `code-generation-plan.md` (aprovado), `unit-test-instructions.md`, `functional-design/` (BR1.1 a BR7.3, S1 a S17), `nfr-requirements/` (U3-NFR*), `nfr-design/`, `infrastructure-design/` e `inception/contract-design/contract-summary.md` (M1, M2, M4, M5).

Testing Contract: `custom`, `comprehensive`. A U3 é núcleo financeiro. Nas etapas 3 a 9 escrevi os testes antes do código, a partir das regras, e rodei cada um vermelho antes de implementar:

| Etapa | Vermelho registrado |
|---|---|
| 3 | `test-compile` falhou: `RealPauses`, `RealStateQuery`, `RealPosition` e `referencePrices` não existiam |
| 4 | `test-compile` falhou: `PolicyFixture` sem `DaySnapshot`, `LamportPrice` e `RealGateInputs` |
| 5 | `RealRiskPrivilegesIT`: 10 de 10 falharam (tabelas inexistentes) |
| 6 | `test-compile` falhou: `JdbcRealRiskStore` e `WalletLock` não existiam |
| 7 | `TransactionalRealRiskGateIT`: 17 de 17 falharam (`NoSuchBeanDefinitionException: RealRiskGate`) |
| 8 | `test-compile` falhou: `RealRiskJobs` não existia |
| 9 | `test-compile` falhou: `RealRiskPurgeJob` não existia |

Configuração, métricas, alertas, Terraform e documentação seguiram a ordem implementar e depois testar.

## O que foi feito, por etapa

| Etapa | Entregue |
|---|---|
| 1 Estrutura e configuração | Pacotes `riskcontrol.real.domain` e `riskcontrol.real.infra`. A M5 em `riskcontrol.api`: `RealRiskGate`, `OrderIntent` (com fábricas `botBuy`, `botSell` e `userLiquidation`), `OrderOrigin`, `GateDecision`, `RealRejectionCode`, `RealRejectionDetail`, `HandOverResult`, `BlockedToken`, `UnblockResult` e `RealGateUnavailable`. `RealRiskLimits` e `RealRiskProperties` (`memetrade.real.risk.*` no `application.yml`), conferidos na subida por `RealRiskConfig`. Regras ArchUnit ajustadas em `RiskControlArchitectureTest`: aceita `botwallet.api`, `ledger.api` e `riskassessment.api`; a regra do pool `risk_retention` aceita o `RealRiskPurgeJob`; a proibição de `ledger.api` passa a valer só para a barreira PAPER; o `TransactionalRealRiskGate` entra na exceção do nome "Transaction". Regras novas em `RealRiskArchitectureTest`, cada uma com um fixture de teste que comprova a falha: fronteira de `riskcontrol.real`, chamadores de `blockToken`/`unblockToken` só em `administration`, `botwallet` sem `riskcontrol` e domínio puro. |
| 2 Runner | O comando por escopo da Unit rodou com um teste vazio (44 testes, 0 falhas); o teste vazio foi removido depois. |
| 3 Leituras M1, M2 e M4 [testes antes] | **M1:** `RealStateQuery` (`readRealState`, `walletsInForce`, `pausedWallets`) e `RealPauses` (`pauseUser`, `resumeUser`, idempotentes e auditados), implementados por `RealStateService`. A coluna nova `real_preferences.paused_since` é mantida na mesma instrução que muda `paused_reasons`, inclusive pelo `savePreferences` da U1. **M2:** `positions` (custo médio), `flowsSince` (saque com a taxa) e `RealBalance` com as reservas separadas em ordem e saque; `RealEntry` ganha `mint`/`tokenBaseUnits` e a fábrica `swapFill`. **M4:** `referencePrices` e `decimalsOf` numa consulta cada, e `volume24h`. |
| 4 `RealRiskPolicy` [testes antes] | Política pura sobre `RealGateInputs`, sem I/O nem relógio: ordem fixa das conferências, códigos e detalhes, contas em `long` com `Math.*Exact`, pontos-base, arredondamento contra a compra e `unknown` nunca tratado como zero. `LamportPrice` converte a cotação em dólar para lamports por unidade base. |
| 5 Migração 0018 [testes antes] | `0018-real-risk.sql`: `real_risk_evaluation`, `real_risk_reservation`, `real_risk_day`, `real_blocked_token` e `real_risk_purge_run`, com índices, checks, a matriz de privilégios, o gatilho de idade de 13 meses, o de transição da reserva, os que recusam UPDATE e TRUNCATE e um bloco de rollback por changeset. |
| 6 Trava, leitor e gravação [testes antes] | `WalletLock` (`pg_try_advisory_xact_lock` a cada 20 ms por até 1 s, sem abortar a transação), `RealGateInputsReader` (falha fechada com `RealGateUnavailable(dependency)`, filtro por tenant, preços em lote) e `JdbcRealRiskStore` (`ON CONFLICT`). |
| 7 `TransactionalRealRiskGate` [testes antes] | `evaluate` (`MANDATORY`): compra com trava, procura pelo `decision_id` antes de qualquer leitura, estado e fonte, entradas da compra, política, e gravação de avaliação, reserva, foto, auditoria e pausa na transação do ciclo; venda sem trava com `ON CONFLICT`. `handOver` com trava e vencimento. `blockToken`/`unblockToken` em transação própria. `RealRiskAudit` com `REAL_ORDER_REJECTED`, `REAL_TOKEN_BLOCKED` e `REAL_TOKEN_UNBLOCKED`. |
| 8 Trabalhos F3, F4 e F5 [testes antes] | `RealRiskJobs` (nos dois processos, para testes e operação) e `RealRiskSchedule` (só no worker): `day` a cada 5 min no início de cada fatia UTC, `daily-loss` e `reservation-expiry` a cada minuto. Cada um com ShedLock próprio (`real-risk-<trabalho>`), uma transação por carteira e batimento só depois de execução sem falha. |
| 9 `RealRiskPurgeJob` [testes antes] | Na API, às 04:00 UTC, ShedLock `real-risk-purge`, pool `risk_retention`, lotes de 1.000 com seleção de 13 meses e 1 dia (reservas `OPEN` nunca), registro em `real_risk_purge_run` e limpeza do registro com mais de 90 dias. |
| 10 Métricas | `RealRiskMetrics` com `real_risk_evaluations_total`, `real_risk_evaluation_seconds` (buckets 0,01 a 1 s), `real_risk_lock_wait_seconds`, `real_risk_reservations_expired_total`, `real_risk_daily_loss_pauses_total`, `real_risk_errors_total` e, por trabalho, `real_risk_job_runs_total`, `real_risk_job_last_success_seconds` e `real_risk_job_seconds`. Os contadores de erro e de trabalho existem em zero desde a subida. `RealRiskReservationGauge` (`real_risk_reservations{state}`) só no worker, a cada 30 s. |
| 11 Alertas e Terraform | `infra/observability/real-risk-alerts.yml` (6 regras) e `infra/terraform/modules/observability/real-risk.tf` (5 alarmes, namespace `Eureka/RealRisk`, sem o alarme de p95). `RealRiskAlarmParityTest` compara nome, limite, janela e dimensão `job`. |
| 12 Contrato e configuração | Seção "Adendo da U3" no fim do `contract-summary.md` (M1, M2, M4, M5). Chaves `memetrade.real.risk.*` comentadas no `.env.example`. `RealRiskStartupTest` sobe o contexto com cada chave. |
| 13 Medição informativa | `RealRiskMeasurementIT`: 21 carteiras, 10 posições cada e 4 avaliações por segundo, sem portão. |
| 14 Documentação | `docs/runbooks/real-risk.md`, este resumo, `traceability.json` e `source-manifest.json`. |

## Testes e resultados

Todos os comandos rodaram da raiz do repositório, um Maven por vez, sem exportar `MEMETRADE_AUDIT_WALLET_HMAC_KEY`.

| Comando | Resultado |
|---|---|
| Comando da Unit (`sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.riskcontrol.real.**.*Test,com.memetrade.riskcontrol.real.**.*IT,com.memetrade.botwallet.**.RealState*Test,com.memetrade.ledger.**.RealLedger*IT,com.memetrade.marketdata.**.ReferencePrices*IT,com.memetrade.architecture.ArchitectureTest'`) | **195 testes, 0 falhas** |
| `... test -Dtest='...RealStateQueryIT,...BotWalletRoutesIT,...RiskControlArchitectureTest'` (vizinhos fora do padrão da Unit) | 13 + 3 + 9 testes, 0 falhas |
| `... test -Dtest='com.memetrade.tenancy.RouteRegistryTest,com.memetrade.config.ProcessRoleContextIT,com.memetrade.config.WorkerProcessRoleContextIT,com.memetrade.db.*IT,com.memetrade.privacy.AnonymizationNoWalletLeftIT'` | 86 testes; 1 falha (`AnonymizationMigrationIT`, conta de changesets), corrigida e repetida: 5 testes, 0 falhas |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify`, 1ª execução | Fase de unidade: 1633 testes, **1 falha** (`RouteRegistryTest.everyScheduledJobIsClassified`: os 3 trabalhos novos não estavam classificados). Corrigida em `route-registry.yml` e confirmada pelo comando acima. Os testes de integração não chegaram a rodar. |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify`, 2ª execução | **Interrompida pelo Claude Code por falta de memória na máquina**, sem resultado. Não foi repetida. |
| `docker run --rm -v "$PWD/infra/observability:/rules" --entrypoint promtool prom/prometheus:v3.5.0 check rules /rules/real-risk-alerts.yml /rules/real-alerts.yml /rules/marketdata-alerts.yml` | SUCCESS: 6, 12 e 7 regras |
| `terraform fmt -check -recursive`, `terraform init -backend=false` e `terraform validate` (imagem `hashicorp/terraform:1.9.8`, cópia dentro do contêiner) | sem diferença; "The configuration is valid." |

Testes novos por componente: leituras M1, M2 e M4, 18 (`RealStateServiceTest` 6, `RealStateQueryIT` 3, `RealLedgerPositionsIT` 5, `ReferencePricesIT` 4); `RealRiskPolicyTest`, 55; `RealRiskPrivilegesIT`, 10; trava, leitor e gravação, 23 (`WalletLockIT` 4, `JdbcRealRiskStoreIT` 7, `RealGateInputsReaderIT` 6, `RealGateInputsReaderTest` 6); `TransactionalRealRiskGateIT`, 17; `RealRiskJobsIT`, 9; `RealRiskPurgeJobIT`, 6; limites e subida, 23 (`RealRiskLimitsTest` 10, `RealRiskStartupTest` 13); `RealRiskMetricsTest`, 7; `RealRiskArchitectureTest`, 7; `RealRiskAlarmParityTest`, 4; `RealRiskMeasurementIT`, 2. Os mints são sintéticos (`SyntheticIds`), e nenhum teste usa rede.

**Medição informativa** (`RealRiskMeasurementIT`, sem portão):
- p95 da compra: 215 ms, medido de ponta a ponta com transação e trava (meta informativa de 150 ms, contada depois da trava);
- p95 da venda: 13 ms;
- trabalhos F3 e F4 com 21 carteiras: 340 ms cada.

**Cobertura de linhas: não medida.** O comando da Unit roda sem o agente do JaCoCo, e o `verify` completo não terminou. O piso de 80% continua no `pom.xml`, sem nenhuma exclusão nova. O `verify` completo precisa rodar de novo, com memória livre, antes do Build and Test.

## Decisões de implementação que mudam comportamento

1. **Unidade do preço.** A cotação da M4 é em dólar por token inteiro (CoinGecko), e a BR5.2 pede lamports por unidade base. `LamportPrice` faz `dólar do token x 10^9 / (dólar do SOL x 10^casas)`, com 18 casas arredondadas para baixo. A cotação do SOL vem do mint embrulhado `So11111111111111111111111111111111111111112`, lido no mesmo lote, e o instante usado é o mais antigo das duas cotações. Se faltar o token, o SOL ou as casas decimais, o preço fica `unknown`: a posição vale o custo e bloqueia novas compras (BR2.4). Por isso a M4 ganhou `decimalsOf` e `volume24h`, além do `referencePrices` do plano.
2. **Trava por pessoa, não por carteira.** A chave é `hashtext(userId)` no espaço de chaves da U3. Uma pessoa tem uma só carteira ATIVA, então a trava é a mesma. Assim ela vem antes de qualquer leitura, como pede o F1, porque o id da carteira só é lido depois da trava.
3. **`RealGateUnavailable` não marca a transação para desfazer no estouro da trava** (`noRollbackFor`). Ela só é lançada antes de qualquer escrita, e o U3-NFR4.8 diz que a U4 decide desfazer o ciclo. Depois de uma falha de SQL (`transactionUsable() = false`, revisão R-02), a transação já foi abortada pelo PostgreSQL e é marcada para desfazer. Qualquer outra falha marca a transação, como na barreira PAPER.
4. **Ordem de verificação dentro de BR1.1:** sem carteira, REAL desligado, sem liberação e só então a pausa. Com várias pausas, o detalhe segue a gravidade: `KILL_SWITCH`, `DIVERGENCE`, `ADMIN`, `DAILY_LOSS`, `UPSTREAM` e `USER`. A liquidação também é recusada com o REAL desligado ou sem liberação: a exceção da BR1.1 vale só para as pausas.
5. **`handOver(tenantId, approvalId)`.** O tenant entra porque toda consulta filtra por ele. Respostas: `HANDED_OVER`, `ALREADY_HANDED_OVER`, `RESERVATION_EXPIRED` e `NOT_FOUND`. Uma reserva `OPEN` já vencida vira `EXPIRED` no próprio `handOver`.
6. **`blockToken` e `unblockToken` recebem a `chain`**, porque a chave da lista é (rede, mint).
7. **Foto do dia na primeira compra.** A política devolve a foto a gravar quando chega à BR4.3 sem foto; uma recusa posterior (teto, saldo livre) também grava a foto. Medida contra si mesma, a perda é zero.
8. **Convenção dos preenchimentos para a U4** (`RealEntryRules`): `SWAP_FILL_IN` com lamports negativos e `SWAP_FILL_OUT` com lamports positivos, quantidade sempre positiva e mint obrigatório. No mesmo instante, as compras são processadas antes das vendas. O custo da posição não inclui as taxas de `NETWORK_FEE`, porque o ledger não liga a taxa ao mint.
9. **`detailMint`.** O mint da posição sem preço volta em `GateDecision.Rejected.detailMint` e fica na coluna `real_risk_evaluation.detail_mint`. Ele não vai para os detalhes livres da auditoria, que recusam endereço completo.
10. **Trabalhos.** Uma carteira com falha não para as outras, mas a execução não conta como sucesso: o batimento não anda e o alerta de trabalho parado continua.
11. **Forma do alerta U3-NFR5.12.** Troquei a forma do requisito (`time() - real_risk_job_last_success_seconds` com `absent()`) pela do `monitoring-design.md`: `increase(real_risk_job_runs_total{job}[janela]) < 1 or absent(...)`, um alerta por trabalho. Assim o alarme do CloudWatch tem a mesma forma (Sum do contador `< 1`, `treat_missing_data = breaching`) e o trabalho que nunca publicou também alerta. O medidor de batimento continua existindo para o painel.
12. **Namespace `Eureka/RealRisk`** com a dimensão `job`, como no `monitoring-design.md`. O caminho das métricas do Micrometer até o CloudWatch continua pendente (decisão única de U1, U2 e U3).

## Testes existentes ajustados

- `RiskControlArchitectureTest`: as cinco mudanças da etapa 1 (lista de módulos permitidos, `ledger.api` só para o PAPER, `riskcontrol.real` entre os pacotes internos, pool de retenção e exceção do nome).
- `ProcessRoleContextIT`: `RealRiskPurgeJob` entra na lista de trabalhos da API. O medidor de reservas e o agendamento dos trabalhos só existem no worker com o agendamento ligado, então a lista do worker no teste não muda.
- `AnonymizationMigrationIT`: o rollback conta os changesets que vêm depois da 0015; a 0018 acrescenta 8 (`6 + 13 + 8`). O teste exercita também os blocos de rollback da 0018.
- `tenancy/route-registry.yml` (não é teste, mas o `RouteRegistryTest` exige): `RealRiskSchedule`, `RealRiskReservationGauge` e `RealRiskPurgeJob` classificados como `GLOBAL`, com o motivo.

## Correções da revisão (READY com 7 Minor)

Revisão: `reviews/review-01.md`. Nas correções que tocam a lógica financeira (R-01, R-03, R-06), escrevi os testes antes e os rodei vermelhos: a compilação falhou porque `transactionUsable` não existia, e `RealRiskJobsTest`, os casos novos do `LamportPrice` e o teste de corrida do `savePreferences` falhavam contra o código anterior.

| Achado | Correção | Testes |
|---|---|---|
| R-01 (janela da leitura da perda do dia) | **Leitura única do ledger REAL.** A ordem das leituras não bastava: um saque entre as leituras do saldo e dos fluxos subestima a perda numa ordem, e um depósito a subestima na outra. A M2 ganhou `RealLedger.view(tenantId, botWalletId, Optional<Instant> flowsSince) -> RealLedgerView{ balance, positions, flows }`, uma só instrução SQL (CTE com `union all`) e, portanto, um só snapshot do PostgreSQL mesmo em READ COMMITTED. A compra (`RealGateInputsReader.buy`) e os trabalhos (`value(tenant, wallet, flowsSince)`) usam só essa leitura; o `flowsSince` separado do leitor saiu. **Foto do dia:** `takenAt` é o instante lido logo antes da leitura daquela carteira, não o início da rodada, e o dia da foto é o dia UTC desse instante. Um fluxo gravado nessa janela de milissegundos entra no valor inicial e também nos fluxos, o que só aumenta a perda (lado seguro). | `RealLedgerPositionsIT.theViewReadsBalancePositionsAndFlowsInOneStatement` (igual às três leituras separadas, sem fluxos sem foto, nada de outro tenant); `RealGateInputsReaderTest.theBuyReadsTheLedgerOnceWithTheInstantOfThePhoto` e `theValueOfAJobIsOneReadToo` (uma só chamada ao ledger, `verifyNoMoreInteractions`); `RealRiskJobsTest.eachPhotoTakesTheInstantOfItsOwnWalletRead` (relógio que anda 1 s por leitura: cada foto com o seu próprio instante) |
| R-02 (`noRollbackFor` e falha de SQL) | `RealGateUnavailable.transactionUsable()`: `false` quando há `SQLException` ou exceção de acesso a dados do Spring nas causas, `true` no estouro da trava. Com `false`, a barreira marca a transação para desfazer (`TransactionAspectSupport...setRollbackOnly()`); com `true` (trava), continua como antes e a U4 decide. O log de falha traz `transactionUsable`. Javadoc da M5, da exceção e da barreira e o Adendo da U3 dizem que, depois de uma falha de SQL, a U4 precisa desfazer. | `RealGateInputsReaderTest.theFailureSaysWhetherTheTransactionOfTheCallerIsStillUsable`; `RealGateInputsReaderIT.aSqlFailureInAReadAbortsTheTransactionOfTheCallerAndTheFailureSaysSo` (PostgreSQL real: depois da falha, a instrução seguinte responde `25P02`); `TransactionalRealRiskGateIT` (a trava presa deixa `transactionUsable() = true` e a transação utilizável) |
| R-03 (`LamportPrice`) | Uma cotação positiva que arredonda para 0 em 18 casas vira `Unknown`; só a cotação exatamente zero é um zero conhecido. Casas decimais fora de 0 a 18, fracionárias ou enormes viram `Unknown`, sem lançar exceção (`6.000` vale 6). | `RealRiskPolicyTest$ValuationBR52AndBR72.aPositiveQuoteThatRoundsToZeroIsUnknownNeverAKnownZero` e `decimalsOutsideTheRangeOrFractionalGiveAnUnknownPriceWithoutThrowing` |
| R-04 (posse do `botId`, BR6.3) | Pré-condição explícita no javadoc de `RealRiskGate.evaluate` e no Adendo da U3 ("Posse do bot"): a U4 confere que o `botId` pertence ao `tenantId` e ao `userId` antes de chamar a M5. A U3 não enxerga os bots. | documentação; nenhuma mudança de comportamento |
| R-05 (registro de retenção) | Não mexi: decisão do responsável (ver Pendências). | — |
| R-06 (`savePreferences` sobrescrevendo uma pausa `DAILY_LOSS`) | Barato, então feito: o `savePreferences` da U1 só acrescenta ou retira o motivo `USER`, numa instrução sobre a linha atual (`array(select … where r <> 'USER' union select 'USER' …)`), e mantém todos os outros motivos e o seu início. `pauseUser` e `resumeUser` já eram atômicos (`array_append`/`array_remove`). O helper `arrayLiteral` saiu. | `RealStateQueryIT.savingThePreferencesOfThePersonNeverDropsAPauseOfTheBarrier` (preferências lidas antes da pausa: salvar com `USER` mantém `DAILY_LOSS` e o seu início; retomar `USER` também a mantém); `BotWalletRoutesIT` (13 testes da U1) continua verde |
| R-07 (`verify` e cobertura) | Não rodado, por instrução: a máquina fica sem memória. Continua pendente. | — |

**Comando depois das correções** (um Maven por vez, da raiz): o comando da Unit mais os testes diretamente afetados (`RealStateQueryIT`, `BotWalletRoutesIT`, `RiskControlArchitectureTest`, `BotWalletArchitectureTest`, `RealBalanceTest`):

`sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest='com.memetrade.riskcontrol.real.**.*Test,com.memetrade.riskcontrol.real.**.*IT,com.memetrade.botwallet.**.RealState*Test,com.memetrade.ledger.**.RealLedger*IT,com.memetrade.marketdata.**.ReferencePrices*IT,com.memetrade.architecture.ArchitectureTest,com.memetrade.botwallet.RealStateQueryIT,com.memetrade.botwallet.BotWalletRoutesIT,com.memetrade.architecture.RiskControlArchitectureTest,com.memetrade.architecture.BotWalletArchitectureTest,com.memetrade.ledger.domain.RealBalanceTest'`: **246 testes, 0 falhas**.

## Pendências

- **Registro de retenção (U3-NFR3.7, parcial; resolvido na Volta 1 do Build and Test, ver abaixo).** O expurgo existe e é conferido pelo gatilho, mas não acrescentei a declaração `RetentionEnforcerDeclaration` nem a linha no `RetentionCatalog`. O catálogo é a tabela da política de privacidade v2 e precisa bater com o `retention.tsv` da versão legal (`RetentionConformanceTest`). Acrescentar a linha "Avaliações da barreira REAL, 13 meses" exige uma nova versão do texto legal, que é decisão do responsável do projeto.
- **Posse do bot (U4, R-04).** Antes de chamar `evaluate`, a U4 confere que o `botId` pertence ao `tenantId` e ao `userId` da intenção. A pré-condição está no javadoc da M5 e no Adendo da U3; a BR6.3 do design pode ser ajustada para dizer que essa garantia é da U4.
- **Falha de SQL na barreira (U4, R-02).** Com `RealGateUnavailable.transactionUsable() = false`, a U4 precisa desfazer o ciclo (a transação já foi abortada e está marcada para desfazer).
- **`verify` completo (R-07).** Continua sem rodar por falta de memória. A cobertura de linhas não foi medida.
- **WatchedTokens (U4).** O mint do SOL embrulhado e os mints das posições REAL precisam estar em `WatchedTokens`; sem a cotação do SOL, nenhuma compra com posição aberta passa.
- **Pool de agendamento do worker.** O worker ganhou quatro tarefas agendadas (três trabalhos e o medidor). O `SCHEDULER_POOL_SIZE` do worker é 4 por padrão; as tarefas esperam a vez, o que é aceitável para o piloto, mas o valor deve ser revisto no Infrastructure Design da U4.
- **Medição.** O p95 da compra medido de ponta a ponta (transação, trava e primeiras chamadas ainda sem JIT aquecido) ficou acima da meta informativa de 150 ms. O histograma de produção conta só depois da trava. Não há portão (`team.md`).
- **Caminho das métricas até o CloudWatch** e o alarme de p95 (U3-NFR5.11), adiados como no `monitoring-design.md`.
- **Retomada manual da pausa `DAILY_LOSS`** fica para a área do administrador (U5).

## Texto para o responsável do projeto aplicar (CI)

O `ci.yml` não muda nesta Unit (`cicd-pipeline.md`). A etapa do `promtool` sugerida no code-summary da U2 deve passar a conferir também o arquivo da U3 (troque a tag pelo digest SHA da imagem, como pede o `team.md`):

```yaml
      - name: Validate alert rules (promtool)
        run: >
          docker run --rm -v "$PWD/infra/observability:/rules:ro" --entrypoint promtool
          prom/prometheus:v3.5.0@sha256:<digest> check rules
          /rules/real-alerts.yml /rules/marketdata-alerts.yml /rules/real-risk-alerts.yml
```

Os testes da U3 rodam no `mvnw verify` que já existe. O `scripts/verify.sh` não muda.

## Volta 1 do Build and Test (2026-11-18)

O Build and Test (Loop-back 1 em `construction/build-and-test/test-results.md`) marcou o U3-NFR3.7 como não cumprido: o expurgo de 13 meses funcionava, mas a retenção das avaliações do REAL não estava no catálogo nem na política de privacidade. Também pediu o `traceability.json` no formato do sensor. As etapas 1 a 14 não mudaram.

### Step 15 — Retenção das avaliações do REAL no catálogo e na política [testes antes]

**Testes primeiro.** Mudei e acrescentei os testes antes do código:
- `RetentionCatalogTest`: o catálogo tem 13 linhas, a 13ª é `REAL_RISK_EVALUATIONS`, com 13 meses a partir da gravação (`CREATED`), fim `Apagar` e a limpeza `real-risk-purge`; ela não se confunde com a linha da barreira PAPER (30 dias).
- `RetentionConformanceTest`: a linha do REAL bate com a declaração `real-risk-purge` (13 meses, `CREATED`) e está em `terms/v2/retention.tsv` com as mesmas palavras; sem a declaração, ou com 12 meses, a conferência acusa a divergência. O teste da tabela divergente passou a tirar a última linha (12 linhas contra 13).
- `RetentionEnforcerDeclarationTest`: a lista das limpezas declaradas inclui `real-risk-purge`.
- `RealRiskRetentionDeclarationConfigTest` (novo): a declaração usa o nome da trava do `RealRiskPurgeJob`, 13 meses e `CREATED`, existe em qualquer processo e a seleção do trabalho continua "13 months 1 day".
- `privacy/infra/RetentionTableSourceTest` e `LegalDocumentsControllerIT`: a tabela servida tem 13 linhas, e a 13ª é a do REAL, com "13 meses" e "Apagar".

**Vermelho registrado** (um Maven, `-Dtest` com `RetentionCatalogTest`, `RetentionConformanceTest`, `privacy.infra.RetentionTableSourceTest` e `RetentionEnforcerDeclarationTest`): **35 testes, 8 falhas, 2 erros**. Falharam a contagem de 13 linhas, a lista de limpezas, a linha do REAL (erro: `find` vazio), a declaração `real-risk-purge` ausente, a linha ausente no `retention.tsv`, a contagem 12/13 da tabela divergente e as duas contagens da fonte da tabela. Com o `RealRiskRetentionDeclarationConfigTest` e o `RetentionDeclarations` de apoio, a compilação falhou porque a classe `RealRiskRetentionDeclarationConfig` ainda não existia.

**Depois o código:**
- `RetentionCatalog`: a 13ª linha, `REAL_RISK_EVALUATIONS`, ao fim da tabela, para não mudar a ordem das 12 linhas anteriores.
- `RealRiskRetentionDeclarationConfig` (novo, em `riskcontrol.real.infra`, sem `@ProcessRole`): declara `real-risk-purge` com `RealRiskPurgeJob.RETENTION` (13 meses) e `CREATED`, a idade que o gatilho da migração 0018 confere.
- `RealRiskPurgeJob`: só a constante `RETENTION`; a seleção e o comportamento não mudaram.
- `RetentionDeclarations` (apoio de teste): passa a carregar a configuração nova.
- `terms/v2/retention.tsv`: a mesma linha, em português.
- `terms/v2/privacy.txt`: uma frase em "Dados que tratamos e finalidade", porque a tabela passa a citar um dado que a lista não citava.

Linha acrescentada (dado, finalidade, prazo, fim):

| Dado | Finalidade | Prazo | Fim |
|---|---|---|---|
| Avaliações, reservas e totais do dia da barreira de risco do modo REAL | Provar o controle de risco aplicado às ordens com dinheiro real | 13 meses | Apagar |

Frase acrescentada ao `privacy.txt` da v2: "Avaliações da barreira de risco das ordens do modo REAL, com os limites, os valores e o token de cada ordem: para provar o controle de risco aplicado às ordens com dinheiro real."

**A v2 continua rascunho.** Não criei v3 e não mexi na v1 nem no `version.properties`: a v2 não tem `legalReviewedAt`, nunca fica ativa e não pede novo aceite. A revisão jurídica da v2, já pendente, passa a cobrir também esta linha e esta frase. Nenhum teste guarda hash ou cópia dos textos da v2; os testes que leem os textos (`LegalTextContentTest`, `TermsCatalogActivationTest`, `CurrentTermsActivationTest` e o `prohibited-terms.test.ts` do `apps/web`) continuam verdes.

**Ponto para a revisão jurídica.** A seção "Tudo é simulado" da v2 ainda diz que todas as operações são simuladas e que não movimentamos fundos. Com o REAL, ela deixa de ser verdadeira. Não mudei essa seção, porque o pedido era de no máximo uma frase. A seção precisa ser reescrita na revisão jurídica da v2, junto com o resumo.

**Verde:**
- `-Dtest` com as 13 classes (os 4 testes do vermelho, `RealRiskRetentionDeclarationConfigTest`, `LegalTextContentTest`, `TermsCatalogActivationTest`, `CurrentTermsActivationTest`, `identity.infra.RetentionTableSourceTest`, `PrivacyArchitectureTest`, `RiskControlArchitectureTest`, `RealRiskArchitectureTest` e `ArchitectureTest`): **104 testes, 0 falhas**.
- `-Dit.test` com `LegalDocumentsControllerIT` e `RealRiskPurgeJobIT`: **18 testes, 0 falhas**.
- `apps/web`, `jest src/content/prohibited-terms.test.ts`: **5 testes, 0 falhas**.
- `spotless:apply` e `spotless:check` sem pendências.

### Step 16 — Rastreabilidade no formato do sensor

- O `traceability.json` foi reescrito no formato que o `aidlc engine sensor-traceability` lê, com 80 IDs: os 16 critérios de aceite das histórias da Unit (AC2.1.1 a AC2.1.4, AC6.1.1 a AC6.1.6, AC6.2.1 a AC6.2.3, AC6.3.1 a AC6.3.3), as 23 regras do `rules.md` (BR1.1 a BR7.3) e os 41 NFRs da Unit.
- **IDs dos NFRs.** Os requisitos chamam os NFRs de `U3-NFRx.y`. O sensor extrai deles a parte `NFRx.y` e cobra esse ID; por isso a tabela usa `NFR3.7` para o `U3-NFR3.7`, como fizeram a U1 e a U2.
- As linhas das histórias (US2.1, US6.1 a US6.3) saíram; as histórias são rastreadas pelos critérios de aceite.
- Cada linha `OK` aponta para um único arquivo existente que implementa ou testa o ID.
- **Implementado, medição pendente.** O sensor só aceita um caminho de arquivo no alvo `OK`, então o aviso vai num campo `note` da linha:
  - NFR3.1, NFR3.2, NFR3.4 e NFR3.5 apontam para o `RealRiskMeasurementIT`: a medição é sintética, local, na CI e sem portão (compra 10 ms, venda 5 ms, F3 213 ms, F4 371 ms); a medição na produção, pelas métricas, está pendente;
  - NFR3.7: o `RealRiskPurgeJob`, com a nota da linha do catálogo e da v2 ainda em rascunho;
  - NFR5.9, NFR5.10 e NFR5.12: a regra do Prometheus, com a nota do alarme correspondente no `real-risk.tf` e da forma do NFR5.12;
  - AC6.1.5, AC6.3.3, NFR8.8 e NFR8.9: notas sobre o que fica na U4 ou onde está a evidência.
- Linhas que não são `OK`, cada uma com o motivo:
  - **AC2.1.1** e **AC2.1.2** `N/A`: o campo do teto e a recusa acima de 10 SOL são da U6 (mapa de Units: "US2.1: regra na U3, campo do teto na U6");
  - **AC6.3.1** `N/A`: os dados reais da fonte paga são da U2;
  - **NFR5.11** `Deferred`: o alarme de p95 na nuvem espera a decisão do caminho das métricas (observability-setup). A regra `RealRiskSlow` do Prometheus existe e passou no `promtool`.
- O gerador fica fora do repositório, como os da U1 e da U2.
- Sensor: `aidlc engine sensor-traceability --stage code-generation` sobre uma cópia em `construction/u3-real-risk/code-generation/` → `pass: true`, 0 achados.
