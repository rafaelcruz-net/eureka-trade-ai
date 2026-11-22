# Resumo do código — U1 `u1-mode-skeleton`

Conversa: português (Brasil). Data: 2026-11-21.

A U1 entrega a criação de bot no modo PAPER ou REAL (elegibilidade com o motivo mais forte, 409 e 503 dentro do comando idempotente), o bot REAL sem limites próprios (BR1.9), os limites REAL da conta (C3, C9), o P&L por modo em dois blocos que nunca são somados (C4, C8, primeira implementação de C10), a lista com o modo e o spec de ponta a ponta do esqueleto. Nenhuma migração, nenhum serviço novo.

## Arquivos

A lista completa, com caminho, está em `source-manifest.json`.

### Backend (`apps/api`)

- **Elegibilidade REAL (C1, C2):**
  - `bots/domain/RealIneligibilityReason.java`, `RealEligibility.java` e `RealCreationEligibility.java` (regra pura, fontes lidas em ordem e só até o primeiro motivo);
  - `bots/application/RealEligibilityReader.java` (prazo único de `real-eligibility-timeout`, `set local statement_timeout` antes de cada leitura, ponto de salvamento na criação), `EligibilitySourceUnavailable.java` e `RealNotEligibleException.java`.
- **Criação no comando idempotente:** `BotCommandService.create` lê a elegibilidade dentro do comando, depois da reivindicação da chave. A recusa desfaz o comando e é auditada depois, em `REQUIRES_NEW` (`bots/infra/RealCreationRefusalAudit.java`), e contada (`bots/infra/BotCreationMetrics.java`). Também mudaram `BotWebErrors` (409 `REAL_NOT_ELIGIBLE` e 503), `AuditActions` (`BOT_REAL_CREATION_REFUSED`) e `ErrorCode` (`REAL_NOT_ELIGIBLE`).
- **BR1.9 (bot REAL sem limites próprios):**
  - `CycleRunner`: a versão de limites só é lida no ramo PAPER; a decisão REAL grava `limitsVersion = 0`;
  - `RealCycle.maxOrderSize`: limite de ordem da conta em US$, num ponto de salvamento, 0 sem teto ou com falha;
  - `BotCommandService`: `validate`, `saveStepTwo`, `stop`, `resume` e `changeLimits` (422) no REAL;
  - `BotStateMachine.ResumeCheck.withoutBotLimits`.
- **Limites da conta (C3, C9):** `riskcontrol/api/RealAccountLimits.java`, `RealAccountLimitsView.java`, `RealLimitsUnavailable.java`, `riskcontrol/real/infra/RealAccountLimitsReader.java`, `riskcontrol/web/RealAccountLimitsController.java` e `RealAccountLimitsDtos.java`.
- **P&L por modo (C4, C8, C10):**
  - `pnl/api/` (tipos de C8 e C10);
  - `bots/application/BotDirectoryImpl.java` e `JdbcBotRepository.directory`;
  - `pnl/application/PaperModeBlockReader.java`, `PaperModeBlock.java`, `PaperBotPnlLine.java` e `RealBotPnlQueryV1.java`;
  - `PnlQueryService.reportSince` (o mesmo cálculo sem o limite de 366 dias) e `RealPnlService` (conversão e preços compartilhados);
  - `pnl/modeview/` (`ModePnlView`, `ModeBlockExecutor`, `BlockReads`, `JdbcBlockReads`, `BlockResult`, `ModePnlStatement`, `PnlModeMetrics`);
  - `pnl/web/PaperBlockSource.java`, `RealBlockSource.java`, `ModePnlDtos.java` e a rota em `PnlController`;
  - `RealExecutionPort.settledOrders(..., max)` (método aditivo) com o `limit` na consulta.
- **Contrato e modo:** `BotSummaryView` e `BotDetailDto` com `mode == market`, obsoleto e com o enum `[PAPER, REAL]`; o sentinela `limitsVersion = 0` documentado; rota C2 em `BotController`; `openapi-guest.yaml` regenerado; `route-registry.yml` com as três rotas novas.
- **Configuração:** `pnl/infra/PnlByModeProperties.java`, o campo `realEligibilityTimeout` em `BotProperties` e os padrões no `application.yml`.
- **Semente de teste:** `RealTestSeeder.requireLocalnet`.

### Frontend (`apps/web`)

- **Telas de bots:**
  - `ModeStep.tsx`, `RealConfirmDialog.tsx`, `RealBotLimitsStep.tsx` e `mode-messages.ts`;
  - `BotCreate.tsx` (etapa 1 de 5);
  - `BotWizard.tsx` (etapas 2 a 5, linha do modo e limites da conta no REAL);
  - `BotList.tsx` (modo, P&L por C4 e aviso);
  - `BotsShell.tsx` (rótulo opcional).
- **P&L:** `PnlModeBlock.tsx`, `by-mode.ts`, `money-format.ts`, `mode-messages.ts` e `PnlScreen.tsx` (h1 "Resultado", dois blocos e a seção PAPER por período embaixo).
- **Comuns:**
  - `components/SimulatedBadge.tsx`;
  - `formatSolCompact` e `formatSignedSolCompact` em `features/real/money.ts`;
  - `guest-api.ts` (três chamadas novas e `createBot` com chave);
  - `schema.d.ts` regenerado;
  - `envelopes.ts` (`mode` PAPER ou REAL);
  - `eslint.config.mjs` (sem `bigint` em `features/pnl`);
  - `globals.css`.
- **Ponta a ponta:**
  - spec novo `e2e/zz-paper-real-unico/paper-real-unico.spec.ts`, com uma compra em cada modo (revisão R-01);
  - `e2e/u5-bots-decision` e `e2e/u7-ledger-pnl` com os textos novos;
  - `e2e/real-swap` com a etapa 2 do bot REAL sem saldo nem limites;
  - `e2e/real-v2-exits`: só o comentário de abertura (deixa de ser o último spec; o `zz-paper-real-unico` lê o `AUTO_FULL` que ele devolve).

### Correções da revisão 1 (`reviews/review-01.md`)

- **R-01 (Major), compra em cada modo no spec.** Os dois bots são criados pelas telas com o mint gravado como único token, e as duas compras são determinísticas, sem esperar o ciclo de 5 minutos:
  - REAL: a mesma estratégia gravada do `e2e/real-swap` (`RecordedSwapStrategy`), uma compra de exatamente `RECORDED_BUY_LAMPORTS`;
  - PAPER: a mesma estratégia, estendida ao PAPER, compra uma vez os tokens da cotação gravada pela barreira da U6 e pela execução PAPER da U7, ao preço que o `sim-a` publica para o mint. Não havia gancho PAPER de teste; este é o único acréscimo;
  - `RecordedSwapFirstCycle` (novo): roda uma vez, no slot corrente, o primeiro ciclo de um bot de teste novo (seleção exatamente o mint gravado). Ele chama o mesmo `CycleRunner.run` do despachante; o ciclo único por bot e slot impede repetição;
  - o primeiro ciclo de teste é um método agendado a mais no worker do REAL local. Para manter uma thread e uma conexão por método agendado, o `infra/docker-compose.real.yml` sobe o worker com `SCHEDULER_POOL_SIZE: '29'` e `DB_POOL_MAX_SIZE: '37'` (dentro da guarda, 28 a 32 e 36 a 48). O `RuntimeSettingsGuardWorkerCountTest` passou a contar à parte os métodos que só existem no perfil `test`: a contagem de produção continua com o limite 28, e um teste novo confere que a composição REAL tem uma thread por método, os de teste incluídos. O `route-registry.yml` classifica o `RecordedSwapFirstCycle` como CLIENT, com o mesmo teste entre dois tenants do `BotSchedules`;
  - os ganchos existem só no perfil `test`, só no worker e só com `RECORDED_MINT` definido (composição REAL). O `RecordedSwapProfileGuardTest` (novo) cobre isso como o `RealSeedProfileGuardTest` cobre a semente: `@Profile("test")` exato, a variável só no `application-test.yml` e no `docker-compose.real.yml`, nunca no Terraform, nos Dockerfiles, nos workflows nem no `.env.example`, e nenhum bean sem o perfil;
  - o spec confere: a ordem REAL `CONFIRMED` do bot REAL; a taxa da compra PAPER (US$) na linha do bot PAPER e a taxa em lamports na linha do bot REAL; cada bot só no bloco do próprio modo; nenhum valor em lamports no bloco PAPER e nenhum valor em dólar no bloco REAL; nenhum total fora dos blocos; o mesmo na tela `/pnl`.
- **R-02 (Major), `NEVER_SUM_MODES` nos DTOs.** As duas listas fechadas ganharam os DTOs de cada modo (`PaperModeBlockDto`, `PaperBotPnlLineDto`, `PnlAmountDto` e as duas variantes do PAPER; `RealModeBlockDto`, `RealBotPnlLineDto`, `UnownedPnlLineDto`, as parcelas e `LamportsAmountDto` com as duas variantes do REAL). Os três montadores fora de `pnl.modeview` (`PnlController`, `PnlByModeDto`, `ModeBlockUnavailableDto`) formam uma lista fechada: podem ver os dois modos, mas as partes 2 e 3 valem para eles como para a visão. Um teste confere que todo tipo de `ModePnlDtos` está numa das três listas (PAPER, REAL ou neutro). Duas amostras novas (`MixedModeDtosSample`, `AddingModeDtosSample`) fazem cada parte falhar nos DTOs.
- **R-03 (Minor), estouro da aritmética exata.** `RealAccountLimitsReader.read` converte `ArithmeticException` em `RealLimitsUnavailable` (503 em C3), e `RealCycle.maxOrderSize` também captura `ArithmeticException`: o teto vira `NO_BUY`, com `WARN` que só nomeia o tipo da falha. Testes: `RealAccountLimitsReaderTest` (teto perto de `Long.MAX_VALUE`) e `RealCycleOrderCapTest` (novo).
- **R-04 (Minor), venda com teto zero.** `BotModePathIT` ganhou o caso: bot REAL sem teto, com posição aberta e queda abaixo do stop; a decisão é SELL, `maxOrderSize` é 0 e a ordem chega ao passo REAL.
- **R-05 (Minor):** continua como pendência (abaixo); os arquivos de design não foram editados.

### Infraestrutura

- `infra/terraform/main.tf`: as três variáveis no `api_environment`, com os valores padrão.
- `.env.example`: removida a linha `# MEMETRADE_REAL_SEED_TEST_RELEASE=`.

## Decisões de implementação

1. **`ModePnlView` genérico.** A visão recebe as duas fontes como `Callable<P>` e `Callable<R>` e devolve `ModePnlStatement<P, R>`. Ela nunca vê um valor de nenhum modo. Cada modo é montado em uma classe própria (`PaperBlockSource`, `RealBlockSource`). Fora da visão, só os três montadores da C4 (`PnlController`, `PnlByModeDto`, `ModeBlockUnavailableDto`) veem os dois modos, e eles não fazem conta nem leem valor de nenhum modo. Assim as quatro partes de `NEVER_SUM_MODES` valem por construção, nos tipos de domínio e nos DTOs, e a regra prova cada parte com amostras de teste.
2. **Transação e prazo por bloco.** `ModeBlockExecutor` confere o prazo antes de abrir a transação e de novo logo depois. Ele aplica o `statement_timeout` com o tempo que resta, copia o MDC e limpa o MDC nulo. A fila cheia vira `Rejected` na hora, e o bloco vem `UNAVAILABLE` com 200. O PAPER lê em REPEATABLE READ e o REAL em READ COMMITTED, cada um em `REQUIRES_NEW`.
3. **Elegibilidade num ponto de salvamento.** `PROPAGATION_NESTED` no comando de criação: uma falha da leitura, inclusive um `statement_timeout` real (`pg_sleep`), dá 503 e nunca 500 (25P02). O IT confirma a suposição do NFR Design sobre o `DataSourceTransactionManager`.
4. **Auditoria depois do rollback.** `RealCreationRefusalAudit` grava em `REQUIRES_NEW` depois que o comando foi desfeito. Se a gravação falha, a resposta é 503 e o contador não sobe.
5. **REAL no ciclo.** C9 é lido num ponto de salvamento dentro da transação do ciclo. Uma falha, inclusive o estouro da aritmética exata, registra `WARN` e o teto vira `NO_BUY` (0), sem derrubar o ciclo. O valor `NO_BUY` evita `BigDecimal.ZERO`, que a regra do `bots` proíbe.
6. **C3 em quatro limites** (teto, ordem com o percentual, compras por dia e perda do dia), conforme o ajuste do Functional Design. O sem valor segue o estilo do contrato: `status UNKNOWN` com `reason NOT_DEFINED`.
7. **Tempo das leituras de C3.** O `statement_timeout` de C3 é a constante de 500 ms do controlador. Não há chave nova de configuração, porque só as três chaves aprovadas entram na configuração.

## Ordem dos testes (Testing Contract `custom`)

- **Antes do código, no núcleo financeiro e na lógica REAL:** elegibilidade (Etapa 3), criação REAL no comando idempotente e a concorrência (Etapa 5), bot REAL sem limites próprios (Etapa 7) e P&L por modo, linha REAL por bot e falhas por bloco (Etapa 11).
- **Revisão 1, R-03:** os testes do estouro (`RealAccountLimitsReaderTest` e `RealCycleOrderCapTest`) foram escritos antes da correção e falharam primeiro: a leitura lançou `ArithmeticException` em vez de `RealLimitsUnavailable`, e o ciclo deixou a exceção escapar.
- **Revisão 1, depois da camada:** o caso de venda do `BotModePathIT` (o código já estava certo), os ganchos de teste do swap gravado e a regra de arquitetura.
- **Vermelho registrado:**
  - Etapa 3: `mvnw test -Dtest=RealCreationEligibilityTest` falhou na compilação (`package com.memetrade.bots.domain.RealCreationEligibility does not exist`) antes de as classes existirem.
  - Etapas 5, 7 e 11: os testes foram escritos antes das classes de produção. O vermelho foi a mesma falha de compilação do módulo de testes, e não houve uma execução separada por arquivo.
- **Depois de cada camada:** configuração, rotas de leitura (C2, C3), regras de arquitetura, contrato, telas e o spec de ponta a ponta.

## Resultados dos testes

| Parte | Comando | Resultado |
|---|---|---|
| Backend, unidade da U1 | `mvnw test -Dtest=RealCreationEligibilityTest,ModePnlViewTest,RealBotPnlQueryTest,RealAccountLimitsReaderTest,BotSummaryModeTest,NeverSumModesArchitectureTest,ModeBoundaryArchitectureTest,RealSeedProfileGuardTest,RealTestSeederNetworkTest` | 79 testes, 0 falhas (26 + 9 + 8 + 7 + 6 + 5 + 4 + 10 + 4) |
| Backend, integração da U1 | `mvnw verify -Dit.test=BotCreateRealIT,...,ByModePropertiesBindingIT,BotRoutesIT,RealRoutesIT,RecordedSwapCycleIT` | 88 testes, 0 falhas (BotCreateRealIT 15, BotCreateConcurrencyIT 2, BotModePathIT 6, BotLimitsRealRefusedIT 3, RealEligibilityRouteIT 9, RealAccountLimitsRouteIT 7, ModePnlFailureIT 10, ModePnlLatencyIT 1, ByModePropertiesBindingIT 5, BotRoutesIT 15, RealRoutesIT 10, RecordedSwapCycleIT 5) |
| Backend, contrato | `GuestOpenApiContractTest` (com `-Dopenapi.write=true`, depois sem) | verde; o arquivo gerado é igual ao versionado |
| Backend, suíte completa | `mvnw verify` (Spotless, unidade, integração, JaCoCo, enforcer, ArchUnit) | BUILD SUCCESS: 2.179 testes de unidade e 1.454 de integração, 0 falhas; JaCoCo mesclado com 92,63% de linhas (26.295 de 28.386; piso 80%) e "All coverage checks have been met" |
| Frontend | `jest --coverage` | 74 suítes, 866 testes, 0 falhas; linhas 94,63% (piso 80%) |
| Frontend | `lint`, `typecheck`, `check:api` | verdes |
| Frontend | `format:check` | verde nos arquivos do app; o único aviso é `apps/web/aidlc/.aidlc-sessions/*.binding.json`, estado de sessão do AI-DLC não versionado, que já existia antes desta geração e não entra no commit nem na CI |

### Ponto de controle do esqueleto: correção do NFR6.1 (360 px)

O `verify-checkpoint.sh` deu 49 aprovados e 1 reprovado: o spec `e2e/zz-paper-real-unico`, no teste de 360 px, com a página `/pnl` rolando para o lado. A página `/bots` passou.

- **Causa provável:** a tabela "Resultado por token" (`pnl-lines`) do relatório PAPER tem cinco colunas e não tinha regra para telas estreitas. Ela só aparece quando há operação PAPER, e a compra PAPER que o spec passou a fazer (revisão R-01) é a primeira operação PAPER da conta do smoke. Por isso o defeito só apareceu agora. Somam-se dois pontos:
  - as tabelas dos blocos usavam `display: block` com `overflow-x: hidden`, o que cortava colunas sem dar acesso a elas;
  - os itens da grade `.pnl-modes` tinham `min-width: auto`, que deixa um filho largo esticar a coluna.
- **Correção acessível:**
  - `components/TableScroll.tsx` (novo): `div` com `role="region"`, `aria-label` e `tabIndex=0`, rolando na horizontal (`overflow-x: auto`) por conta própria, com contorno no foco. Envolve a tabela por token, as duas tabelas de bots dos blocos e a tabela de operações do bot (`BotTradesTable`, na tela do bot);
  - CSS: o corte com `overflow-x: hidden` saiu; `.pnl-modes > *` ganhou `min-width: 0`; os rótulos e valores de `.totals` e `.hypotheses` quebram linha (`overflow-wrap: anywhere`) e, no celular, ficam empilhados, como os cartões dos mockups; as entradas do ledger quebram linha;
  - o mint continua abreviado, com o valor completo no `title`.
- **Guardas no Jest:** o jsdom não tem layout, então os testes conferem a estrutura. Em `PnlModeBlock.test.tsx`, cada tabela de bots fica numa região nomeada e focável; em `PnlScreen.test.tsx`, a tabela por token também, e o mint completo continua no `title`.
- **Resultados:** `lint`, `typecheck`, `check:api` e `prettier --check` verdes; `jest --coverage` com 74 suítes, 868 testes, 0 falhas e 94,64% de linhas.
- **Não executado:** o spec de ponta a ponta. Ele exige a composição REAL inteira, pesada demais para esta máquina, que já interrompeu o Maven por falta de memória. A prova é o próximo `verify-checkpoint.sh` ou o run do `ci`.

### Revisão 1: resultados

| Parte | Comando | Resultado |
|---|---|---|
| Backend, testes novos e alterados | `mvnw verify -Dtest=RealCycleOrderCapTest,RealAccountLimitsReaderTest,NeverSumModesArchitectureTest -Dit.test=BotModePathIT` | 18 de unidade (4 + 8 + 6) e 7 de integração, 0 falhas |
| Backend, ganchos do swap gravado | `mvnw verify -Dtest=RecordedSwap*Test,RecordedSwapProfileGuardTest,NeverSumModesArchitectureTest,BotsArchitectureTest -Dit.test=RecordedSwapCycleIT,WorkerProcessRoleContextIT` | 46 de unidade e 11 de integração (RecordedSwapCycleIT 7, WorkerProcessRoleContextIT 4), 0 falhas depois da correção do contexto do guarda |
| Backend, guardas tocadas | `mvnw test -Dtest=RouteRegistryTest,RuntimeSettingsGuardWorkerCountTest,ComposeFilesTest,RecordedSwapProfileGuardTest,RealSeedProfileGuardTest` | 56 testes, 0 falhas |
| Backend, suíte completa (1ª tentativa) | `mvnw verify` | 2.194 testes de unidade com 2 falhas: `RuntimeSettingsGuardWorkerCountTest` (29 métodos agendados para o limite 28) e `RouteRegistryTest` (o job novo sem classificação). As duas foram corrigidas e passam na execução dirigida acima; a integração não chegou a rodar |
| Backend, suíte completa (2ª tentativa) | `mvnw verify` | **interrompida pelo Claude Code por falta de memória na máquina**, sem resultado. Não foi relançada. A cobertura JaCoCo mesclada desta rodada não foi medida; a última medida é a de antes da revisão (92,63%) |
| Frontend | `jest --coverage` | 74 suítes, 866 testes, 0 falhas; linhas 94,63% (sem mudança: a revisão só tocou nos specs de ponta a ponta) |
| Frontend | `lint`, `typecheck`, `check:api` | verdes |

A medição de desempenho (NFR1.5), registrada no log do `ModePnlLatencyIT` com a semente de referência, deu estes p95, todos abaixo da meta de 1.000 ms:

| Rota | p95 |
|---|---|
| C4 | 213 ms |
| C2 | 16 ms |
| C3 | 12 ms |
| C1 | 25 ms |

O p95 de C4 também ficou abaixo do gatilho de 700 ms que antecipa a leitura em fluxo da U2.

## Testes escritos e não executados nesta etapa

- **`e2e/zz-paper-real-unico/paper-real-unico.spec.ts`.** Ele roda só pelo `scripts/verify.sh`, que precisa da composição REAL inteira e da linha que o responsável acrescenta (texto abaixo). A execução local do `verify.sh` morre por falta de memória nesta máquina (`team.md`, verificação dos pontos de controle). Por isso AC4.1.5, BR10.1, NFR6.1, NFR7.7 e NFR7.12 estão como `Deferred` no `traceability.json`, com a nota do que falta.
  - Já verificado sem o e2e: a compra PAPER do bot do mint gravado e o primeiro ciclo de teste dos dois modos (`RecordedSwapCycleIT`, com as migrações reais e a barreira real).
  - Risco ainda aberto: o spec repete a compra REAL gravada depois que o `e2e/real-v2-exits` vendeu a posição do `e2e/real-swap`. O estado do pool no validador mudou com a compra e a venda; se a cotação gravada sair da derrapagem, a ordem REAL fica `FAILED` e o spec falha (não passa em silêncio).
- **`bash scripts/verify-checkpoint.sh`.** Não foi executado. O caminho alternativo exige o commit no remoto e a árvore sem mudanças fora dos registros do AI-DLC, e o commit é do coordenador. Ele fica para depois do commit, com a linha nova no `verify.sh`.

## Desvios do plano

1. **Pasta do spec:** ficou `e2e/zz-paper-real-unico/` em vez de `e2e/paper-real-unico/`.
   - O Playwright (`workers: 1`) roda os arquivos em ordem alfabética. O spec precisa da carteira do bot que o `e2e/real-bot-wallet` ativa e do `AUTO_FULL` que o `e2e/real-v2-exits` devolve. A compra PAPER dele também precisa vir depois do `e2e/u7-ledger-pnl`, que espera a conta do smoke sem operações PAPER. Por isso ele é o último spec.
   - O spec para os dois bots no fim. A posição REAL comprada fica na carteira do bot do validador local, que o `verify.sh` desmonta.
   - Sem `RECORDED_FIXTURES`, o spec é pulado, como o `e2e/real-swap`.
   - `unit-test-instructions.md` foi atualizado.
2. **Compras determinísticas com ganchos só de teste (revisão R-01):** a estratégia gravada passou a valer também para um bot PAPER do mint gravado, e o primeiro ciclo de teste (`RecordedSwapFirstCycle`) é novo. Os dois estão fora do plano aprovado, mas atendem ao BR10.1 e à fatia do `team.md` sem esperar o ciclo de 5 minutos. Os dois só existem no perfil `test` e só com `RECORDED_MINT` (`RecordedSwapProfileGuardTest`).
3. **409 sem `effectiveAt`:** o problema 409 traz `reason`, mas não `effectiveAt`.
   - O C1 não declara o campo, e o `Problem` é compartilhado com o contrato de administração.
   - Depois de um 409, a tela relê C2, que traz o `effectiveAt` da quarentena (BR2.8).
4. **`botLines` de C10 (C5):** não foi implementado. É da U3 e entra de forma aditiva.
5. **Nome `BlockReads`:** a interface de transação dos blocos se chama `BlockReads` (e `JdbcBlockReads`). A regra do `LedgerArchitectureTest` proíbe tipos com "Transaction" no nome dentro de `pnl`.
6. **`pool-size` e `queue-capacity`:** as duas chaves entraram em `PnlByModeProperties`, só no `application.yml`, como pede o `performance-design.md`. Elas são necessárias ao teste do pool saturado.
7. **Tela do bot (`BotDetail`, S5):** continua com o rótulo "MODO PAPER (simulado)" de antes. O BR1.6 está coberto no assistente; a tela do bot é da U3. Os rótulos do menu ("Bots PAPER", "P&L PAPER") não mudaram.
8. **Proibição de `bigint`:** vale para `features/pnl` e não para `features/bots`. O `features/bots/decimal.ts` já usa `bigint` para o decimal exato do USDC.
9. **Testes existentes ajustados, nunca apagados:**
   - `RealRoutesIT`: carteira inativa passa de 422 para 409;
   - `RecordedSwapCycleIT` e `e2e/real-swap`: bot REAL sem saldo nem limites;
   - `BotRoutesIT`, `BotScreens.test.tsx` e `PnlScreen.test.tsx`;
   - `RouteRegistryTest` e `U7ContractsTest`: rotas novas;
   - `GuestOpenApiSlice`: beans novos;
   - `GuestOpenApiContractTest`: a exceção do REAL fora de `/api/v1/real/` vale para as rotas da visão única;
   - `e2e/u5-bots-decision` e `e2e/u7-ledger-pnl`: textos.
10. **C3 em `riskcontrol.web`:** a rota C3 ficou nesse pacote, com a tag `bot-controller` do contrato.

## Pendências registradas

- **Alinhar dois textos de design**, sem editar aqui:
  - o `nfr-design/performance-design.md` diz que os valores ficam "fixos e nunca lidos do ambiente";
  - o critério do NFR8.1 em `nfr-requirements/scalability-requirements.md` diz "0 mudanças nas definições de tarefa do ECS".

  O código segue o `infrastructure-specification.md`: as três chaves são lidas do ambiente por `@ConfigurationProperties`, e a definição `api-worker` ganha três variáveis sem segredo.
- **Completar o BR1.9** do `functional-design/rules.md` com dois detalhes que estão só no `reliability-design.md`: a decisão do fundador sobre o `DecisionRequest` REAL (teto da conta e `limitsVersion = 0`) e o 422 de `changeLimits`.
- **Linha do spec no `scripts/verify.sh`**, que é do responsável (texto abaixo).

## Texto pronto para o responsável: `scripts/verify.sh`

Na linha 162, depois de `e2e/real-v2-exits`, acrescentar um espaço e `e2e/zz-paper-real-unico`. A linha fica assim:

```bash
    e2e/reacceptance e2e/data-requests e2e/anonymization e2e/real-bot-wallet e2e/real-swap e2e/real-ops e2e/real-ui e2e/real-v1-exports e2e/real-v2-exits e2e/zz-paper-real-unico
```

Nenhum arquivo de `.github/workflows/` foi alterado. Os jobs `smoke` e `e2e` passam a rodar o spec novo por meio do `verify.sh`.
