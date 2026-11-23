# Requisitos de desempenho — U2 `u2-real-bot-pnl`

Conversa: português (Brasil). Data: 2026-11-22.

A U2 troca o cálculo do P&L REAL por bot (C10) por uma leitura em fluxo do ledger na ordem de rede (C12, C13), separa a quantidade da atribuição (BR2.5, BR2.7) e grava o slot na confirmação (C14). As metas abaixo herdam o NFR1 do intent e seguem a resposta Q1 = A [Q1]. A U1 deixou para esta Unit o portão de 1.000 ms de C4 no cenário de carga (NFR1.6 da U1). Esse portão é do workflow **manual** `load-test`, não da CI automática, e o Build and Test da U2 só fecha com uma execução verde dele (NFR1.25).

## Decisões do fundador (2026-11-22)

Tomadas depois da revisão `reviews/review-01.md` (R-01, R-02, R-07). Nenhuma delas pode ser mudada na Code Generation.

- **D-NFR1. Orçamento da atribuição no caminho do P&L:** 500 ms, dentro do prazo do bloco REAL que já existe, `memetrade.pnl.by-mode.block-timeout` (`PnlByModeProperties`: padrão 800 ms, mínimo 100 ms, máximo 900 ms, contado desde o envio ao pool, para o bloco inteiro). O caminho da barreira e do ciclo continua com 250 ms. No portão de carga: 0 `RECONCILIATION_MISMATCH`; bloco REAL `UNAVAILABLE` por `TIMEOUT` em no máximo 0,5% das leituras; p95 de C4 ≤ 1.000 ms. Os 800 ms da BR2.7 ("800 ms dentro do p95 de 1 s de C4, no caminho do P&L") passam a 500 ms por esta decisão. O `rules.md` e o `functional-spec.md` são alinhados na Code Generation, e não neste estágio.
- **D-NFR2. Evento `REAL_SLOT_CONFLICT`:** a assinatura é guardada abreviada e com HMAC (`security-requirements.md`, NFR3.9).
- **D-NFR3. Disparo do portão de carga:** o Build and Test da U2 só fecha com uma execução verde de `pnl-by-mode` do workflow manual `load-test`, disparada pelo fundador depois de aplicar o texto pronto do `load-test.yml` (NFR1.9). Se o runner não aguentar 20 usuários simultâneos, o tamanho da carga é reduzido a partir dos dados medidos e registrado. A meta de 1 s nunca baixa (NFR1.25).

## Metas de tempo de resposta

| ID | Rota ou caminho | Meta | Condição de carga | Como é medido | Portão |
|---|---|---|---|---|---|
| NFR1.1 | `GET /api/v1/pnl/by-mode` (C4), com o bloco REAL calculado pela U2 (rateio completo, linha "Sem bot" e conferência) | p95 ≤ 1.000 ms | 20 pessoas lendo ao mesmo tempo, cada uma com 10 bots (5 PAPER e 5 REAL) e 2 anos de histórico (NFR1.6) | cenário `pnl-by-mode` do `load-test` (NFR1.6), p95 do lado do servidor pelo histograma `http_server_requests_seconds` | **Sim**, no workflow manual (NFR1.25). O cenário falha acima de 1.000 ms. |
| NFR1.2 | `GET /api/v1/real/pnl` (total do RealBotPnl, BR10.3) | p95 ≤ 1.000 ms | a mesma de NFR1.1 | o mesmo cenário, o mesmo histograma | Não: só registrado no relatório, porque a Q1 põe o portão só em C4 [Q1]. |
| NFR1.3 | Leitura de atribuição (C13 e C8) no caminho do P&L (`path = PNL`) | p95 ≤ 500 ms, dentro do prazo do bloco REAL de 800 ms (`memetrade.pnl.by-mode.block-timeout`) [fundador D-NFR1] | a mesma de NFR1.1 | o mesmo cenário, pelo histograma do temporizador `pnl_real_attribution_read_seconds{path="PNL"}` (NFR1.23) | **Sim**, no workflow manual. O cenário falha acima de 500 ms. |
| NFR1.4 | Leitura de custo com atribuição das rodadas da barreira e do ciclo (`path = GATE` e `path = CYCLE`), fora da trava da carteira (`WalletLock`): a compra nunca lê a atribuição (alinhamento A14, revisão 02 R-13) | p95 ≤ 250 ms (BR2.7) | 21 carteiras com 10 posições cada, das quais 2 sem preço por carteira (o caso que pede o custo no `value()`), 2 anos de ordens por carteira na densidade de NFR1.14, 4 avaliações por segundo | teste de integração de medida, no padrão do `RealRiskMeasurementIT` (NFR1.7) | Na CI, só registrado no log (`team.md`, "Testes instáveis"). No Build and Test, um p95 acima de 250 ms bloqueia a Unit e volta ao fundador (NFR1.8). |
| NFR1.5 | Barreira REAL no caminho só de quantidade: `evaluate` de compra (sem a espera pela trava) e de venda | p95 de cada lado ≤ o maior entre 1,10 × a linha de base e a linha de base + 2 ms (piso contra o ruído quando a base tem poucos milissegundos); e, em qualquer caso, teto absoluto de 500 ms na compra (decisão do fundador de 2026-11-23, Etapa 14b: substitui os 150 ms herdados do U3-NFR3.1 do 261113, já excedidos antes da U2 nesta máquina, base de 206 ms; o limite relativo é decidido por comparação intercalada com o código da Etapa 0, mediana dentro de +10%) e de 30 ms na venda (U3-NFR3.2 do 261113) | a condição do `RealRiskMeasurementIT` (21 carteiras, 10 posições, 4 avaliações por segundo), todas as posições com preço | teste de integração, comparação com a linha de base (NFR1.7, NFR1.8) | No Build and Test (NFR1.8). |
| NFR1.26 | Barreira REAL: `evaluate` de compra com uma posição da carteira sem preço até a recusa `POSITION_PRICE_UNKNOWN`; essa compra não lê a atribuição e fica na meta da compra de NFR1.5 (alinhamento A14, revisão 02 R-13) | p95 ≤ 400 ms, que continua como teto, sem a espera pela trava; a espera máxima pela trava continua ≤ 1.000 ms (U3-NFR3.3) | a condição de NFR1.4 | o mesmo teste de integração de NFR1.4 (NFR1.7) | No Build and Test (NFR1.8). |

## Método de medição

| ID | Requisito | Valor |
|---|---|---|
| NFR1.6 | Cenário de carga `pnl-by-mode`, novo, no `scripts/load-test.sh` e no `scripts/load/`, com os perfis `test,loadtest`. | **Semente** (só nos perfis `test,loadtest`, por um driver de carga do `pnl`, nunca em produção, com teste de guarda como o `RealSeedProfileGuardTest`): 20 pessoas; cada uma com 5 bots PAPER e 5 bots REAL e 730 dias de ordens do próprio bot, 4 ordens por bot por dia (2 compras e 2 vendas), ou seja, 2.920 ordens por bot e 29.200 por pessoa (14.600 REAL), com os lançamentos de ledger correspondentes. A mistura REAL de cada pessoa cobre as regras da U2: pelo menos 5% de vendas com excedente (BR4.4), 2% de liquidações e 2% de vendas condicionais, das quais metade parcial (BR4.3, BR4.6), 1% de ordens `FAILED` com transação (BR6.2), 10% das ordens no modo Assinada (BR6.3), 1% de compras sem bot resolvido (BR3.1), pelo menos 10 pares de ordens no mesmo slot (BR1.2) e os primeiros 365 dias sem linha em `real_entry_slot` (`SLOT_MISSING`, BR1.5). Mints e carteiras sintéticos; preços do adaptador simulado. **Tráfego:** 20 usuários virtuais, um por pessoa, cada um com uma leitura a cada 2 s, alternando `GET /api/v1/pnl/by-mode` e `GET /api/v1/real/pnl`, durante 600 s, depois de 60 s de aquecimento descartados. **Portões** [fundador D-NFR1]: NFR1.1 e NFR1.3; 0 respostas diferentes de 200; 0 divergências (`pnl_real_reconciliation_mismatches_total` não sobe, NFR2.11); blocos REAL `UNAVAILABLE` por `TIMEOUT` (prazo do bloco ou orçamento da atribuição) em no máximo 0,5% das leituras de C4, e 0 `UNAVAILABLE` por qualquer outra causa; `hikaricp_connections_pending` > 0 por no máximo 10 s seguidos; memória dos contêineres `api` e `postgres` ≤ 900 MB (os limites que o `load-test.mjs` já usa); o `EXPLAIN` da consulta principal, a leitura do ledger REAL de uma pessoa na ordem de rede (`streamInNetworkOrder`), usa o índice da `0023` em `real_ledger_entry` (`Index Scan`, `Index Only Scan` ou `Bitmap Index Scan` com o nome do índice) e não tem `Seq Scan` em `real_ledger_entry`. Um `Hash Join` com `Seq Scan` em tabela pequena, como `real_entry_slot` ou `bot`, é aceito (NFR1.18). A conferência é código novo no `load-test.mjs`: ela procura o nome do índice no plano da consulta principal, e não usa a regex fixa de `ledger_entry` e `fill` que existe hoje. **Saída:** `load-test-report.json` e `docs/perf/u2-real-bot-pnl-<data>.md`, com p50, p95 e p99 de cada rota e de cada caminho da atribuição. Nada fala com devnet, mainnet nem KMS real. |
| NFR1.7 | Medidas por teste de integração (NFR1.4 e NFR1.5). | `*IT` com Failsafe e Testcontainers (PostgreSQL 17 com as migrações da aplicação), no padrão do `RealRiskMeasurementIT`. Por caso: 10 avaliações de aquecimento descartadas e 100 medidas. p95 pelo posto mais próximo (a 95ª menor das 100). **Linha de base de NFR1.5:** o `RealRiskMeasurementIT` roda 3 vezes no último commit antes do código da U2, na mesma máquina em que depois roda a medida da U2. A mediana dos 3 p95 de cada lado vai para `docs/perf/u2-barrier-baseline.md`, com o SHA e a máquina. A medida da U2 também roda 3 vezes, e vale a mediana. O arquivo da linha de base entra no repositório **antes** do primeiro commit da U2 que muda a barreira (`RealGateInputsReader`, `JdbcRealLedger.positions` ou `view`); sem ele, o Build and Test não fecha. |
| NFR1.8 | Onde as metas de NFR1.4 e NFR1.5 são decididas. | Na CI, os dois testes de medida registram o p95 ao lado da meta e falham só quando uma avaliação falha, como os testes de medida que já existem: um portão de ±10% num runner compartilhado seria instável, e teste instável é defeito (`team.md`). No Build and Test da U2, as medianas são comparadas com as metas. Acima da meta, a etapa falha. Os orçamentos de 250 ms (NFR1.4) e de 500 ms (NFR1.3) só podem baixar sem o fundador; subir é decisão dele (BR2.7, D-NFR1). |

## Texto pronto para o `.github/workflows/load-test.yml`

| ID | Requisito | Valor |
|---|---|---|
| NFR1.9 | O workflow manual `load-test` ganha a opção `pnl-by-mode`. O arquivo é do responsável (`team.md`, Way of Working). O texto abaixo é aplicado por ele na Code Generation da U2, no mesmo commit em que o cenário entra no `scripts/load-test.sh` e no `scripts/load/load-test.mjs`. Sem o texto aplicado, o portão não pode rodar e a U2 fica pendente no Build and Test (NFR1.25). | O texto substitui o arquivo inteiro e mantém todas as ações fixadas pelo SHA. Ele muda quatro coisas: (1) a opção `pnl-by-mode` entra na lista; (2) a opção `real`, que o texto pronto da seção 8.1 do `load-test-plan.md` do intent `261113-trading-real-bot` pediu e que ainda não está no arquivo, entra junto; (3) o comentário do topo descreve os dois cenários; (4) `timeout-minutes` é 75 para `real`, 90 para `pnl-by-mode` e 360 (o padrão do GitHub) para os demais. Se o responsável aplicar antes a seção 8.1 do 261113, a única diferença que resta é a do `pnl-by-mode`. Nenhuma variável nova entra no workflow: o `scripts/load-test.sh` define a semente e a duração do cenário. |

Estimativa do `pnl-by-mode`: build das imagens, semente das 20 pessoas (cerca de 584 mil ordens nos dois modos), 60 s de aquecimento, 600 s de carga e a leitura dos planos; de 45 a 70 min. O limite de 90 min deixa a margem.

```yaml
# Manual load test (cicd-pipeline.md Q4): profiles test,loadtest raise the login limit and, since U3, the invitation
# lookup limit ONLY here; 20 simulated users, one action every 2 s. Scenario u1 fails when p95 of challenge, verify or
# list exceeds 300 ms; u4 adds the filtered list and the assessment; u3 adds the verification with an invitation
# (500 ms) and the invitation lookup and Account screen (300 ms); growth only records. U7: u7 runs the load cycle
# driver of the loadtest profile (p95 execute 250 ms, C5 routes 1 s, query plans) and u7-jobs the 30-minute rounds of
# the marking and the pending close (10 s each). U6: u6 runs the load risk driver (p95 evaluate 250 ms in each window,
# C21 1 s, purge batches 100 ms, query plans). U5: u5 runs the worker cycles with the stopwatch of the loadtest profile
# (p95 cycle 2 s, barrier plus execution 500 ms, C4 routes 1 s, S5 screen 2 s, live channel 4 s, no stale-data
# decision, worker pool at most 14 connections).
# 261113-trading-real-bot (operation/performance-validation): real runs the REAL stack of the e2e (TxSigner, kms-sim,
# local Solana validator, recorded Jupiter) with the pilot times 2: 21 bot wallets with 3 REAL bots each, 20 people on
# the screen and an administrator for 15 minutes, "vender tudo e sacar" in 5 wallets for 5 minutes and a burst of 21
# withdrawals in 10 s every minute for 5 minutes. It fails on any missed target of the requirements, any error, any
# duplicate transaction, any growing queue or more than 60 signatures per wallet and minute. CI environment, never
# devnet, mainnet nor a real KMS; every secret is ephemeral (scripts/load-test.sh).
# 261121-paper-real-unico U2 (nfr-requirements NFR1.1 to NFR1.9): pnl-by-mode seeds 20 people with 5 PAPER and 5 REAL
# bots and 2 years of orders each (4 per bot per day, synthetic, test,loadtest profiles only) and runs 20 users reading
# the P&L by mode and the REAL P&L every 2 s for 10 minutes. It fails when p95 of GET /api/v1/pnl/by-mode exceeds 1 s,
# when p95 of the REAL attribution read on the P&L path exceeds 500 ms, on any error, any
# reconciliation mismatch, REAL blocks UNAVAILABLE by timeout above 0.5% of the reads, any other REAL block UNAVAILABLE,
# a pending pool, more than 900 MB in a container or the main REAL ledger read without its index.
name: load-test

on:
  workflow_dispatch:
    inputs:
      scenario:
        description: Load scenario (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal, reacceptance, data-requests, real or pnl-by-mode)
        type: choice
        options:
          - u1
          - u4
          - u3
          - growth
          - u7
          - u7-jobs
          - u6
          - u5
          - legal
          - reacceptance
          - data-requests
          - real
          - pnl-by-mode
        default: u1

permissions:
  contents: read

jobs:
  load:
    runs-on: ubuntu-24.04
    # real: about 25 minutes of load plus the image builds, the validator, the TxSigner and the setup of the 21 wallets
    # (45 to 55 minutes expected). pnl-by-mode: the seed of 20 people with 2 years of orders plus 11 minutes of load
    # (45 to 70 minutes expected). The other scenarios keep the GitHub default of 360 minutes, as before.
    timeout-minutes: ${{ inputs.scenario == 'real' && 75 || inputs.scenario == 'pnl-by-mode' && 90 || 360 }}
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 # v4.7.1
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0
        with:
          node-version-file: .nvmrc
      - name: Start the environment with test,loadtest and run the load
        env:
          SPRING_PROFILES_ACTIVE: test,loadtest
          LOGIN_RATE_LIMIT_PER_MINUTE: '100000'
          INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE: '100000'
          LOAD_SCENARIO: ${{ inputs.scenario }}
          IMAGE_TAG: ${{ github.sha }}
        run: bash scripts/load-test.sh
      - uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        if: always()
        with:
          name: load-test-report
          path: |
            load-test-report.json
            docs/perf/*.md
          retention-days: 30
```

Disparo, depois de o responsável aplicar o texto: `gh workflow run load-test -f scenario=pnl-by-mode`. A repetição na devnet que o NFR1 pede é feita pelo fundador depois da implantação na devnet, com a mesma leitura de C4 e o mesmo limite de 1.000 ms, e o resultado vai para o mesmo `docs/perf/` (NFR1.24).

## Orçamentos e restrições de recursos

| ID | Requisito | Valor |
|---|---|---|
| NFR1.10 | O cálculo do P&L REAL é só de leitura e nunca trava a execução de ordens (BR10.2). | 0 `SELECT ... FOR UPDATE`, 0 travas consultivas e 0 escritas nas leituras C4, C10 e `GET /api/v1/real/pnl`, fora o evento de auditoria de BR8.7. Teste de integração: C4 em laço, em paralelo com avaliações da barreira REAL e um `RealCycle`; a espera pela trava da barreira continua ≤ 1.000 ms (U3-NFR3.3 do 261113) e o ciclo termina sem `RealGateUnavailable` por trava. |
| NFR1.11 | A quantidade de token nunca depende da atribuição (BR2.5). | 0 leituras de C13 (`realexecution.api`) e de C8 (`BotDirectory`) em `JdbcRealLedger.positions`, `view`, nas entradas de venda do `RealCycle`, nos candidatos do `LiquidationJob` e na quantidade da carteira do `RealBotHoldings`. Conferido por regra ArchUnit (`LedgerArchitectureTest.theLedgerNeverDependsOnThePnl` sem exceção) e por teste de integração com a leitura de atribuição falhando (NFR3.11). |
| NFR1.12 | A leitura de atribuição tem orçamento duro, e o bloco REAL de C4 continua com o prazo que já existe [fundador D-NFR1]. | Tempo máximo da atribuição: 250 ms nos caminhos `GATE` e `CYCLE` e 500 ms no caminho `PNL`. O bloco REAL inteiro (fluxo do ledger, rateio, atribuição, preços e conferência) continua limitado por `memetrade.pnl.by-mode.block-timeout`, padrão 800 ms e máximo 900 ms, sem mudança no `PnlByModeProperties`. Os 500 ms da atribuição ficam dentro desses 800 ms e deixam 300 ms para o resto do bloco. Passado o tempo da atribuição, a leitura é cancelada e vale o mapeamento de falha da BR2.7 (`outcome = TIMEOUT`, NFR1.23). Na carga de referência (NFR1.6), os blocos REAL `UNAVAILABLE` por `TIMEOUT` ficam em no máximo 0,5% das leituras. O mecanismo de cancelamento (tempo da consulta, tempo do comando) fica com o NFR Design. |
| NFR1.13 | Os preços são lidos uma vez por leitura, no fim, só para os tokens com posição aberta (BR10.1). | Número de consultas de preço ao `marketdata.api` por leitura = número de mints distintos com posição aberta na pessoa; 0 consultas de preço dentro do fluxo de lançamentos. Teste de unidade com um duplo que conta as consultas. |
| NFR1.25 | O portão de carga é do workflow manual `load-test` e fecha o Build and Test da U2 [fundador D-NFR3]. | O Build and Test da U2 só fecha com 1 execução verde de `pnl-by-mode` no SHA candidato, disparada pelo fundador depois de aplicar o texto de NFR1.9, com o `load-test-report.json` e o `docs/perf/u2-real-bot-pnl-<data>.md` anexados ao registro da etapa. Sem o texto aplicado ou sem a execução verde, a Unit fica pendente. O runner do repositório privado (`ubuntu-24.04`, 2 vCPU) roda a API, o worker e o PostgreSQL no mesmo host. Se ele não aguentar 20 usuários simultâneos (falha por pool, memória ou CPU, e não por defeito do cálculo), o número de pessoas simultâneas é reduzido a partir dos números medidos, nunca abaixo de 5, e a redução fica registrada no relatório com os dados que a justificam. O volume de cada pessoa (NFR1.14) e a meta de 1.000 ms nunca baixam. Se C4 passar de 1.000 ms com a carga reduzida e com o índice da `0023`, isso dispara o plano B de NFR8.11, que volta ao fundador. |

O limite de ordens da U1 (`real-max-orders`, causa `TOO_MANY_ORDERS`; padrão 50.000, intervalo de 15.000 a 200.000 no `PnlByModeProperties`) não pode deixar indisponível o volume de referência: com 14.600 ordens REAL por pessoa (NFR1.14), 0 blocos `TOO_MANY_ORDERS` no cenário de NFR1.6. Decidido (F-2; alinhamento A10): o limite fica como proteção acima do volume de referência, contado no próprio fluxo de `streamSettledOrders`, com o comportamento da U1 (sem número parcial).

## Sources

- `nfr-requirements-questions.md`: Q1 e o resumo confirmado [Q1].
- `inception/requirements-analysis/requirements.md`: NFR1 (p95 de 1 s, 10 bots, 2 anos; carga da CI e devnet; leitura nunca trava a execução).
- `construction/u2-real-bot-pnl/functional-design/rules.md`: BR1.2, BR1.5, BR2.5, BR2.7, BR3.1, BR4.3, BR4.4, BR4.6, BR6.2, BR6.3, BR8.7, BR10.1, BR10.2, BR10.3.
- `construction/u2-real-bot-pnl/functional-design/functional-spec.md`: F1, F6, F8; suposição 3 (`real-max-orders`).
- `construction/u1-mode-skeleton/nfr-requirements/performance-requirements.md`: NFR1.5 (densidade da semente, método do p95) e NFR1.6 (o portão de C4 fica com a U2).
- `inception/contract-design/contract-summary.md`: C4, C10, C12, C13, C14, PQ-10.
- `aidlc/spaces/default/intents/261113-trading-real-bot/`: `operation/performance-validation/load-test-plan.md`, seção 8.1 (texto pronto ainda não aplicado com a opção `real`); `construction/u3-real-risk/nfr-requirements/performance-requirements.md` (U3-NFR3.1 a U3-NFR3.3, como citados em `scripts/load/real-stats.mjs`).
- Código lido: `.github/workflows/load-test.yml`, `scripts/load-test.sh`, `scripts/load/load-test.mjs`, `scripts/load/real.mjs`, `scripts/load/real-stats.mjs`, `riskcontrol/real/infra/RealRiskMeasurementIT.java`, `TransactionalRealRiskGate.java` (`WalletLock`), `RealGateInputsReader.java`, `pnl/application/RealBotPnlQueryV1.java` e `pnl/api/RealPnlUnavailable.java` (`TOO_MANY_ORDERS`), `pnl/infra/PnlByModeProperties.java` e `application.yml` (`memetrade.pnl.by-mode.block-timeout`: 800 ms, de 100 a 900 ms; `real-max-orders`: 50.000).
- `reviews/review-01.md` (R-01, R-02, R-05, R-06) e as decisões do fundador de 2026-11-22 (D-NFR1, D-NFR3).
- `aidlc/spaces/default/memory/team.md`: Way of Working (o `load-test` é manual e só o responsável altera `.github/workflows/`), Testing Posture (Desempenho, Testes instáveis, Determinismo, Ferramentas).

## Assumptions & Open Questions

1. [assumption] A densidade da semente (4 ordens por bot por dia) é a mesma da U1 (NFR1.5 da U1), porque o NFR1 diz "2 anos de histórico" sem fixar a quantidade. A mistura de casos (excedente, liquidação, condicional, Assinada, `SLOT_MISSING`) é deste estágio, para que a carga passe pelo rateio completo que a Q1 pede. O NFR Design pode mudar as proporções; a meta de 1.000 ms e o volume por pessoa não mudam.
2. As 20 pessoas simultâneas repetem o ritmo de todos os cenários do `load-test` (20 usuários, uma ação a cada 2 s). Se o runner não aguentar, a redução segue o NFR1.25 [fundador D-NFR3].
3. [assumption] A linha de base de NFR1.5 é medida antes de o código da U2 mudar a barreira (e entra no repositório antes, NFR1.7), porque o algoritmo atual de quantidade sai com a U2 (BR2.5) e não pode rodar ao lado do novo. A comparação com ±10% fica no Build and Test, com medianas de 3 execuções na mesma máquina, e não como portão da CI, pelo ruído do runner compartilhado.
4. **Para o gate do responsável:** o texto pronto do `load-test.yml` inclui também a opção `real` do intent `261113-trading-real-bot`, que ainda não foi aplicada. Se o responsável preferir aplicar só a parte desta Unit, basta tirar a linha `- real`, a menção a `real` na descrição e o trecho `inputs.scenario == 'real' && 75 ||`.
5. O caminho de 250 ms (NFR1.4) só acontece quando uma posição da carteira está sem preço e o `value()` precisa do custo (BR2.7). Esse caminho tem meta própria, 400 ms (NFR1.26); o p95 de 150 ms da compra (NFR1.5) vale para as compras sem posição sem preço. Os 250 ms sob a trava ficam dentro da espera máxima de 1.000 ms pela trava (U3-NFR3.3).
6. [assumption] O piso de +2 ms de NFR1.5 é escolha deste estágio, para a revisão R-05: com uma base de venda de poucos milissegundos, 10% fica abaixo do ruído da máquina. Os tetos absolutos de 150 ms e de 30 ms continuam valendo.
