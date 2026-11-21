# Plano de teste de carga do REAL — intent 261113-trading-real-bot

Conversa: português (Brasil). Estágio: Performance Validation, passos 3 e 4. Decisões confirmadas: Q1 a Q5, todas na opção A (`performance-validation-questions.md`).

> **Ambiente de CI, não produção** [Q1]. A primeira camada roda no GitHub Actions, com Docker Compose, o validador Solana local, o `kms-sim` e a Jupiter simulada, a mesma pilha dos specs e2e do REAL. Nada aqui fala com devnet, mainnet ou KMS real. A segunda camada (devnet AWS) e a medição de 30 dias estão descritas nas seções 9 e 10 e ainda não podem rodar, porque não existe conta AWS (`environment-provisioning/environment-inventory.md`: NÃO PROVISIONADO).

## 1. Resumo

| Item | Valor |
|---|---|
| Cenário | `real`, em `scripts/load-test.sh` e `scripts/load/real.mjs` |
| Tráfego | piloto x2 [Q2]: estável por 15 min, pico de "vender tudo e sacar" por 5 min, rajada de saques por 5 min |
| Metas | as dos requisitos de cada Unit, sem afrouxar nenhuma [Q3]. São 17 metas de latência e capacidade, mais o ciclo do worker. O relatório mostra p50, p95 e p99 de cada uma |
| Gargalos | pool de conexões, trava por pessoa, TxSigner, assinaturas por carteira e minuto (no máximo 60), filas e planos das consultas quentes [Q4] |
| Reprovação | qualquer meta não atingida ou sem amostra, qualquer erro, qualquer transação duplicada, fila crescendo, mais de 60 assinaturas por carteira e minuto, pool com conexões esperando em duas leituras seguidas, consulta quente sem caminho por índice |
| Quem dispara | o assistente, com `gh workflow run load-test -f scenario=real`, **depois** de o responsável aplicar o texto da seção 8.1 [Q5]. O workflow ainda não foi disparado |
| Relatórios | `load-test-report.json` (artefato do workflow) e `docs/perf/real-trading-<data>.md` |

## 2. Ambiente

| Peça | No cenário `real` (CI) | Observação |
|---|---|---|
| Executor | `ubuntu-24.04` do GitHub Actions (repositório público, minutos sem cobrança) | ambiente de CI, não produção |
| Composição | `docker-compose.yml` + `infra/docker-compose.test.yml` + `infra/docker-compose.real.yml` + `infra/docker-compose.loadtest-real.yml` | o último arquivo é novo e só o cenário `real` o usa |
| Perfis | `test,loadtest` na api e no worker; `kms-sim` no TxSigner | o `LoadtestProfileGuard` recusa `loadtest` sem `test` |
| Rede Solana | validador local `anzaxyz/agave:v2.2.20` com as contas do swap gravadas | `infra/solana-fixtures/` |
| KMS | `kms-sim` com semente fixa (`KMS_SIM_SEED`) | a primeira chave criada é a carteira do swap gravado |
| Jupiter | WireMock com as respostas gravadas | só a carteira gravada consegue trocar (ver seção 7) |
| Segredos | todos efêmeros, gerados pelo `scripts/load-test.sh`, como no `scripts/verify.sh` | nenhum segredo de produção; a chave HMAC da auditoria é a efêmera que o script já gera |

O `infra/docker-compose.loadtest-real.yml` muda só a medição e a cadência, nunca um limite:

- o worker reconcilia **a cada minuto**. O e2e usa 15 s e a produção 15 min. Assim os 25 minutos têm cerca de 25 rodadas de 21 carteiras, mais pressão que a produção;
- o worker liga o cronômetro do ciclo do perfil `loadtest` (`CycleStopwatch`, timers `loadtest.bots.*`), sem a semente de bots da api;
- o TxSigner publica os buckets de `txsigner_latency` e `txsigner_kms_sign`. O cenário lê o atuador dentro do contêiner do TxSigner, que continua sem porta publicada (U1-NFR1.2).

O `application-loadtest.yml` passa a publicar os buckets de `real_ops_job` e `real_risk_job`. Assim o relatório mostra p50, p95 e p99 dessas durações. O julgamento continua pelo máximo, como pedem U5-NFR3.2, U5-NFR3.3 e U3-NFR3.4. Fora do perfil `loadtest` nada muda (`LoadtestRealHistogramsTest`).

## 3. Cenário `real`: tráfego [Q2]

### 3.1 Preparação (antes do relógio da carga)

1. O fundador (a conta smoke, já com a liberação e os termos do REAL semeados pelo `RealTestSeeder`) entra. As outras 20 pessoas se cadastram com convites efêmeros e carteiras geradas no processo.
2. As 20 pessoas recebem, por SQL do próprio teste, a mesma liberação `ACTIVE` e os mesmos aceites de termos que a semente de teste deu ao fundador. É o mesmo caminho SQL que o cenário `reacceptance` já usa.
3. O fundador ativa a carteira do bot primeiro. A chave 0 do `kms-sim` é a carteira do swap gravado, e o cenário confere isso. Depois as outras 20 pessoas ativam ao mesmo tempo. Cada ativação é uma amostra de U1:NFR3.4.
4. As 21 pessoas sobem a carteira para `AUTO_FULL` com o consentimento assinado pela carteira principal.
5. Cada carteira principal recebe um airdrop de 2 SOL do validador e deposita 0,5 SOL na carteira do bot. O tempo de cada depósito, de finalizado até aparecer no saldo, é uma amostra de U1:NFR3.5.
6. Cada pessoa cria 3 bots REAL pelo assistente (rotas C4). O bot 1 do fundador seleciona o mint gravado; a estratégia de teste compra uma vez. Os outros 62 bots selecionam por regra de liquidez.
7. Um administrador descartável entra pela entrada administrativa.

### 3.2 Fases da carga (25 minutos)

| Fase | Duração | O que acontece | Metas que alimenta |
|---|---|---|---|
| Estável | 15 min | 20 pessoas na tela, uma ação a cada 2 s, em rodízio: carteira, depósitos, ordens, saques, P&L, desafio de consentimento (`RAISE_CAP`, nunca assinado) e liberação. Um administrador lê a página de 50 pessoas e a visão da primeira a cada 2 s. Os 63 bots REAL fazem um ciclo a cada 5 min. A reconciliação roda a cada minuto e os portões a cada 5 min | U1:NFR3.1, U4-NFR3.4, U5-NFR3.1 a NFR3.4, U3-NFR3.1 a NFR3.4, U4-NFR3.1, U4-NFR3.2, ciclo do worker |
| Pico | 5 min | "Vender tudo e sacar" em 5 carteiras ao mesmo tempo (o fundador, com a posição comprada, e mais 4). Em seguida, cada uma recebe um novo depósito de 0,5 SOL. As pessoas na tela continuam | U3-NFR3.2 (venda), U4-NFR3.1, U1:NFR3.5 |
| Rajada | 5 min | A cada minuto, 21 saques parciais de 0,001 SOL, um por carteira, espalhados em 10 s numa ordem sorteada com semente fixa (`seed = 261113`). Em cada rajada, um pedido é repetido com a mesma `Idempotency-Key` e precisa devolver o mesmo saque. São 105 saques. Cada saque é acompanhado a cada 250 ms até o envio e depois até `CONFIRMED` | U1:NFR3.2, U1:NFR3.3, U1:NFR3.8 |
| Escoamento | até 2 min | sem tráfego novo; as filas precisam voltar ao valor do início | regra de fila |

O limite de saque parcial (10 por hora por pessoa) comporta as 5 rajadas, e a repetição com a mesma chave não consome o limite (`BotWalletController`). O limite de desafios (10 por minuto por pessoa) comporta o rodízio da tela, que dá cerca de 4 desafios por minuto.

## 4. Metas medidas [Q3]

Os limites estão em `scripts/load/real-stats.mjs` (`REAL_TARGETS`), copiados dos requisitos sem mudança. As medidas do cliente são exatas (posto mais próximo). As do servidor saem dos histogramas e usam o limite superior do bucket; assim nunca ficam abaixo do valor real, e um quantil no bucket `+Inf` reprova.

| Meta | Fonte | Limite | Estatística | Como o cenário mede |
|---|---|---|---|---|
| U1:NFR3.1 | `u1-bot-wallet/nfr-requirements/performance-requirements.md` | 300 ms | p95 | cliente: `GET /real/bot-wallet`, depósitos, saques (lista e item) e `POST /real/consents/challenge` |
| U1:NFR3.2 | idem | 500 ms | p95 | cliente: `POST /real/bot-wallet/withdrawals` |
| U1:NFR3.3 | idem | 5 s | p95 | cliente: do instante antes do POST até a primeira leitura `SENT` ou `CONFIRMED`, lida a cada 250 ms (fica no máximo 250 ms acima do real, nunca abaixo) |
| U1:NFR3.4 | idem | 3 s | p95 | cliente: o `POST /real/bot-wallet` da ativação, sem o tempo de assinar |
| U1:NFR3.5 | idem | 45 s | máximo | cliente: da transferência finalizada até o saldo mostrar o valor exato |
| U1:NFR3.8 | `u1-bot-wallet/nfr-requirements/scalability-requirements.md` | 5 s | máximo | cliente: o saque mais lento de cada rajada de 21 carteiras chega ao envio em até 5 s. Se a trava fosse global, os últimos da rajada esperariam pelos primeiros |
| U3-NFR3.1 | `u3-real-risk/nfr-requirements/performance-requirements.md` | 150 ms | p95 | servidor: `real_risk_evaluation_seconds{side="BUY"}` |
| U3-NFR3.2 | idem | 30 ms | p95 | servidor: `real_risk_evaluation_seconds{side="SELL"}` |
| U3-NFR3.3 | idem | 1 s | máximo | servidor: `real_risk_lock_wait_seconds_max`, lido a cada 10 s |
| U3-NFR3.4 | idem | 5 s | máximo | servidor: `real_risk_job_seconds_max{job=day, daily-loss, reservation-expiry}` |
| U4-NFR3.1 | `u4-real-execution/nfr-requirements/performance-requirements.md` | 3 s | p95 | servidor: `real_order_decision_to_send_seconds{mode="AUTO_FULL"}` |
| U4-NFR3.2 | idem | 500 ms | p95 | servidor: `real_decision_queue_wait_seconds` |
| U4-NFR3.4 | idem | 300 ms | p95 | cliente: `GET /real/orders` e `GET /real/pnl` |
| U5-NFR3.1 | `u5-real-operations/nfr-requirements/performance-requirements.md` | 5 s | p95 | servidor: `real_reconciliation_seconds` |
| U5-NFR3.2 | idem | 60 s | máximo | servidor: `real_ops_job_seconds_max{job="reconciliation"}` |
| U5-NFR3.3 | idem | 10 s | máximo | servidor: `real_ops_job_seconds_max{job="gates"}` |
| U5-NFR3.4 | idem | 500 ms | p95 | cliente: `GET /admin/real/users?limit=50` e `/users/{userId}` |
| Ciclo do worker | intent `261026-memetrade-saas-mvp`, U5 NFR2.1, citado em [Q3] | 2 s | p95 | servidor: `loadtest_bots_cycle_seconds` (cronômetro do perfil `loadtest`) |

As medidas do servidor usam a janela da carga: os buckets lidos no fim menos os lidos antes do relógio. A api e o worker são somados. Os máximos dos trabalhos saem do `_max` lido a cada 10 s, e o número de execuções do `_count`; um trabalho que não rodou na janela fica "sem amostra" e reprova. O relatório também traz p50, p95 e p99 de cada métrica por fase (estável, pico e rajada).

## 5. Sondas de gargalo [Q4]

| Gargalo | Sonda | Regra |
|---|---|---|
| Pools de conexão | `hikaricp_connections_active`, `_pending` e `_max` da api (`memetrade-pool`, 20), do worker (`memetrade-worker-pool`, 36: 28 agendados, 4 de ciclo e 4 de reserva) e do TxSigner (`txsigner-pool`, 5), a cada 10 s | reprova com conexões esperando em duas leituras seguidas; pool cheio sem espera é registrado (`full`) |
| Trava por pessoa da barreira | histograma e `_max` de `real_risk_lock_wait_seconds`; contador `real_risk_errors_total` | máximo abaixo de 1 s (U3-NFR3.3) e nenhum erro técnico |
| TxSigner único | `txsigner_latency_seconds` por tipo (`WITHDRAWAL`, `SWAP_BUY`, `SWAP_SELL`): p50, p95, p99 e máximo; `txsigner_kms_errors_total` | registrado; o tempo limite de 2 s (U4-NFR3.3) não é meta, mas uma chamada acima dele vira erro |
| Limite por carteira | `txsigner.signing_record` assinados, agrupados por carteira e minuto | no máximo 60 em qualquer carteira e minuto; o relatório guarda só o máximo e o número de carteiras, nunca o endereço |
| RPC | duração da reconciliação por carteira (até 3 chamadas) e da rodada | U5-NFR3.1 e U5-NFR3.2 |
| Confirmações de 2 s | fila de saques `REQUESTED`/`SIGNED`, ordens não finais e comandos pendentes do TxSigner | regra de fila |
| Filas | a cada 10 s, por SQL: decisões `PENDING`, saques em andamento, ordens não finais, comandos do TxSigner não confirmados; `audit_outbox_pending` da api | reprova se, no estado estável, o menor valor do último terço ficar acima do maior do primeiro terço, ou se depois do escoamento a fila terminar acima do início |
| Planos das consultas quentes | `EXPLAIN (ANALYZE, BUFFERS)` registrado e `EXPLAIN` com `enable_seqscan = off`, com os parâmetros de uma carteira da rodada: saques prontos do worker, saques da carteira, depósitos da carteira, saldo pelo ledger REAL, fila de decisões, ordens da pessoa, última reconciliação | reprova se, mesmo sem varredura sequencial, a consulta não tiver caminho por índice. Com 21 carteiras as tabelas são pequenas, e o plano natural pode ser sequencial; por isso o plano natural é registrado e não reprova |
| Memória | não medida neste cenário | os cenários u5 e u7 já medem a memória da api e do PostgreSQL |

## 6. Regras de aprovação e reprovação

O cenário sai com código diferente de zero (o job fica vermelho) quando qualquer item abaixo falha. As regras estão em `evaluateReal` (`scripts/load/real-stats.mjs`):

1. **Meta:** qualquer meta da seção 4 sem amostra ou com a estatística acima do limite. Exatamente no limite, aprova.
2. **Zero erros:**
   - resposta HTTP diferente da esperada, inclusive 429 e 5xx;
   - falha de leitura das sondas;
   - ordem REAL `FAILED`, `AMBIGUOUS` ou `CANCELLED` criada na rodada;
   - saque `FAILED` ou `AMBIGUOUS`, ou que não chega ao envio ou à confirmação em 150 s;
   - assinatura recusada pelo TxSigner;
   - ciclo de bot `FAILED`;
   - divergência aberta;
   - liberação `BLOCKED`;
   - saque pedido que não aparece no banco;
   - aumento de `real_risk_errors_total`, `txsigner_kms_errors_total`, `real_upstream_failures_total` ou `bots_cycle_errors_total`;
   - carteira que não esvazia em 4 min depois do "vender tudo e sacar".
3. **Nenhuma transação duplicada:**
   - assinatura repetida no TxSigner;
   - `tx_signature` repetida em saques ou ordens;
   - duas ordens para a mesma decisão;
   - mais saques no banco que pedidos distintos;
   - repetição com a mesma `Idempotency-Key` que devolve outro saque.
4. **Nenhuma fila crescendo:** regra da seção 5.
5. **No máximo 60 assinaturas por carteira e minuto.**
6. **Nenhum pool com espera sustentada.**
7. **Toda consulta quente com caminho por índice.**
8. **Preparação:** a carteira do bot do fundador precisa ser a do swap gravado; se não for, a compra e a venda gravadas não rodam e o run reprova.

Uma meta não atingida volta ao NFR Design da Unit dona, como manda o Build and Test [Q3]. Nenhum limite é afrouxado para o run passar.

## 7. O que o cenário não cobre e metas inalcançáveis no desenho

- **Swaps só na carteira gravada.** As respostas da Jupiter simulada e as contas do pool foram gravadas para uma carteira (`RECORDED_BOT_WALLET`). Na CI, só o fundador compra e vende de verdade: uma compra e uma venda por rodada. Os outros 62 bots fazem ciclos, passam pela barreira com a trava por pessoa e são recusados por dado de mercado que não é REAL (recusa de domínio, não erro). Por isso U4-NFR3.1 e U3-NFR3.2 terão poucas amostras (cerca de 2). Elas já estão `Met` com 21 pessoas no `RealExecutionMeasureIT` e no `RealRiskMeasurementIT`. Mais swaps exigiriam uma gravação por carteira (`record.sh` com 21 carteiras), fora deste estágio.
- **1.000 carteiras (U1:NFR3.6, U1:NFR3.9 e o método de U1:NFR3.1).** O Q2 fixou 21 carteiras x2. A rodada de capacidade com 1.000 carteiras, 10 leituras/s e 1 saque/s por 10 min ainda não existe. Na CI ela é possível com o `kms-sim`. **Na devnet e na produção ela é inalcançável pelo desenho de custo:** cada carteira é uma chave KMS (US$ 1 por mês cada), e 1.000 chaves custariam cerca de US$ 1.000 por mês, contra o orçamento de US$ 350 por conta (`project.md`, Corrections de 2026-11-20).
- **Portões (U5-NFR3.3).** Na CI o resultado da CI é fixo em `GREEN` (`MEMETRADE_REAL_OPS_CI`), então a leitura de até 5 s do GitHub não acontece. O run da CI mede o trabalho sem essa chamada; a meta só fecha na devnet.
- **CoinGecko (U2:NFR3.1 a NFR3.3).** A pilha da CI usa a fonte simulada. A medição sintética que o requisito pede antes da produção (WireMock com 300 ms por chamada) entrou no backend: `CoinGeckoRoundMeasurementTest`, verde localmente. A medição com a API real só acontece em produção, porque a CoinGecko não tem devnet.
- **Scanner (U2:NFR3.4).** "Sem piora" pede o cenário `u4` que já existe no `load-test.yml`. Ele não precisa de mudança no workflow e fica para depois da rodada `real` (seção 8.3).
- **Saídas condicionais e exportação fiscal (U7).** Não fazem parte do tráfego do Q2. U7-NFR3.1, NFR3.3 e NFR3.4 já estão `Met` por testes de integração.
- **Margem do depósito (U1:NFR3.5).** O B&T mediu 41 s contra 45 s. Aqui a medida começa no instante finalizado, o que é mais exato, mas a margem pode ser curta com 21 depósitos ao mesmo tempo.

## 8. Como rodar

### 8.1 Texto pronto para o `.github/workflows/load-test.yml`

O `load-test.yml` é um arquivo do responsável [Q5]. O texto abaixo substitui o arquivo inteiro. Ele muda três coisas e mantém todas as ações fixadas pelo SHA:

- a opção `real` entra na lista;
- o comentário do topo passa a descrever o cenário;
- `timeout-minutes` é de 75 só para `real` e mantém 360 (o padrão do GitHub) nos demais.

O cenário precisa de cerca de 25 min de carga. Somam-se o build das imagens (api, web, admin, TxSigner e `kms-sim`), a subida do validador e do TxSigner, a preparação das 21 carteiras e até 2 min de escoamento: estimativa de 45 a 55 min.

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
name: load-test

on:
  workflow_dispatch:
    inputs:
      scenario:
        description: Load scenario (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal, reacceptance, data-requests or real)
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
        default: u1

permissions:
  contents: read

jobs:
  load:
    runs-on: ubuntu-24.04
    # real: about 25 minutes of load plus the image builds, the validator, the TxSigner and the setup of the 21 wallets
    # (45 to 55 minutes expected). The other scenarios keep the GitHub default of 360 minutes, as before.
    timeout-minutes: ${{ inputs.scenario == 'real' && 75 || 360 }}
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

Nenhuma variável nova é necessária no workflow. O `scripts/load-test.sh` gera tudo o que o cenário `real` usa: os segredos efêmeros do TxSigner, a imagem do TxSigner, os 20 convites, a carteira do administrador e o caminho das gravações. As fases ficam em 900, 300 e 300 s por padrão; `REAL_STEADY_S`, `REAL_PEAK_S` e `REAL_BURST_S` existem só para depuração local e não entram no workflow.

### 8.2 Disparo e coleta (depois de o responsável aplicar o texto)

```bash
gh workflow run load-test -f scenario=real
gh run list --workflow load-test --limit 1                 # pega o id do run
gh run watch <id> --exit-status
gh run download <id> --name load-test-report --dir /tmp/load-real
```

O resultado vai para `test-results.md` (este estágio) e para a matriz `nfr-validation-matrix.md`, com o id do run, o `load-test-report.json` e o `docs/perf/real-trading-<data>.md`.

### 8.3 Rodada do cenário `u4` (U2:NFR3.4)

Depois do `real`, `gh workflow run load-test -f scenario=u4`. O p95 da lista com filtros (1 s) e do detalhe (500 ms) é comparado ao relatório de referência, `docs/perf/u4-discovery-2026-11-06.md`.

## 9. Fase 2 — devnet AWS (depois do primeiro `apply`)

**Pré-requisitos:**

- a conta devnet aplicada (`environment-provisioning`);
- a imagem com o REAL ligado na rede `DEVNET`;
- 21 pessoas de teste com convites e carteiras de teste (nunca a carteira real do fundador);
- uma carteira de teste com SOL da devnet para financiar as outras, porque o airdrop da devnet tem limite;
- o modo `REAL_TARGET=devnet` do executor, **ainda não construído**. Hoje o `real.mjs` recusa qualquer endereço que não seja localhost (`assertLocal`), de propósito. O modo novo:
  - aceita só o domínio da devnet em `REAL_ALLOWED_HOST`;
  - não cria bots, porque a Jupiter não atende a devnet, então não há swap;
  - financia pela carteira de teste em vez do airdrop;
  - troca as sondas por SQL e Docker pelas evidências do CloudWatch abaixo.

**O que fecha na devnet:**

- U1:NFR3.3 e U1:NFR3.4 com o KMS real, como os próprios requisitos pedem;
- U1:NFR3.1, U1:NFR3.2, U4-NFR3.4 e U5-NFR3.4 através do balanceador e do RDS;
- U5-NFR3.1 e U5-NFR3.2 com o RPC da devnet;
- U5-NFR3.3 com a leitura real da CI no GitHub.

**Custo:** 21 chaves KMS a mais por cerca de US$ 1 por mês cada, rateado. Depois da rodada, as chaves das carteiras de teste são agendadas para exclusão (mínimo de 7 dias).

**Comando** (com o modo devnet construído; os valores entre `<>` saem do `terraform output` da devnet):

```bash
LOAD_SCENARIO=real REAL_TARGET=devnet REAL_ALLOWED_HOST=<domínio do api na devnet> \
API_BASE_URL=https://<domínio do api na devnet> SOLANA_RPC_URL=https://api.devnet.solana.com \
SMOKE_WALLET_FILE=<carteira de teste da pessoa 1, fora do repositório> \
SMOKE_ADMIN_WALLET_FILE=<carteira de teste do administrador, fora do repositório> \
REAL_FUNDING_WALLET_FILE=<carteira de teste com SOL da devnet, fora do repositório> \
SMOKE_INVITE_CODES=<20 convites de teste> \
node scripts/load/load-test.mjs
```

**Evidências do CloudWatch** (janela = início e fim da rodada, em UTC). O CloudWatch Agent descarta histogramas (`observability-setup/alarms.md`, OBS-G1 e OBS-G2), então os p95 do servidor não existem na nuvem. As amostras do cliente continuam como medida principal, e o CloudWatch confirma:

```bash
LB=<arn_suffix do balanceador>; TG=<arn_suffix do grupo-alvo do api>; T0=<início>; T1=<fim>

# Latência do api no balanceador (p50, p95, p99 por minuto) e erros
aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name TargetResponseTime \
  --dimensions Name=LoadBalancer,Value=$LB Name=TargetGroup,Value=$TG \
  --start-time $T0 --end-time $T1 --period 60 --extended-statistics p50 p95 p99
for m in HTTPCode_Target_5XX_Count HTTPCode_ELB_5XX_Count RequestCount; do
  aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB --metric-name $m \
    --dimensions Name=LoadBalancer,Value=$LB Name=TargetGroup,Value=$TG \
    --start-time $T0 --end-time $T1 --period 60 --statistics Sum
done

# Máximos do TxSigner, do KMS, dos trabalhos e da trava (namespace Eureka/App)
for m in txsigner_latency_seconds_max txsigner_kms_sign_seconds_max real_ops_job_seconds_max \
         real_risk_lock_wait_seconds_max real_risk_job_seconds_max hikaricp_connections_pending \
         real_pending_txsigner_commands realwallet_withdrawal_oldest_pending_seconds; do
  aws cloudwatch list-metrics --namespace Eureka/App --metric-name $m --query 'Metrics[].Dimensions' --output json
done   # depois, get-metric-statistics --statistics Maximum --period 60 para cada combinação de dimensões listada

# Erros na janela (precisam somar zero)
for m in real_risk_errors_total txsigner_kms_errors_total real_upstream_failures_total; do
  aws cloudwatch get-metric-statistics --namespace Eureka/App --metric-name $m \
    --start-time $T0 --end-time $T1 --period 60 --statistics Maximum Minimum
done

# Assinaturas por chave KMS e minuto (no máximo 60), pela trilha do CloudTrail
aws logs start-query --log-group-name /eureka-real/cloudtrail --start-time $(date -d $T0 +%s) --end-time $(date -d $T1 +%s) \
  --query-string 'filter eventSource = "kms.amazonaws.com" and eventName = "Sign"
    | stats count(*) as assinaturas by requestParameters.keyId, bin(1m)
    | sort assinaturas desc | limit 5'
aws logs get-query-results --query-id <id devolvido>

# Saturação: RDS e ECS
aws cloudwatch get-metric-statistics --namespace AWS/RDS --metric-name DatabaseConnections \
  --dimensions Name=DBInstanceIdentifier,Value=<id do RDS da aplicação> --start-time $T0 --end-time $T1 --period 60 --statistics Maximum
aws cloudwatch get-metric-statistics --namespace AWS/ECS --metric-name CPUUtilization \
  --dimensions Name=ClusterName,Value=<cluster> Name=ServiceName,Value=<serviço> --start-time $T0 --end-time $T1 --period 60 --statistics Maximum
```

O painel `eureka-real` (`observability-setup/dashboards.md`), nas faixas Serviços, Visão REAL e Alarmes do REAL, recebe um print da janela da rodada como evidência.

## 10. Disponibilidade de 30 dias (U1:NFR4.1 e U2:NFR4.1)

As duas metas só se medem com tráfego real, ao longo de 30 dias corridos [Q1]. A medição usa a mesma matemática dos alarmes de consumo (`observability-setup/slo-config.md`), em 30 dias.

**U1:NFR4.1, 99,5%.** No balanceador, a fração de pedidos ao api sem 5xx. É mais rígida que o desenho: cobre todas as rotas e soma os 5xx do próprio balanceador.

```bash
cat > /tmp/sli-api-30d.json <<'JSON'
[
  {"Id":"req","MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"RequestCount","Dimensions":[{"Name":"LoadBalancer","Value":"<arn_suffix do balanceador>"},{"Name":"TargetGroup","Value":"<arn_suffix do grupo-alvo do api>"}]},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
  {"Id":"t5","MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"HTTPCode_Target_5XX_Count","Dimensions":[{"Name":"LoadBalancer","Value":"<arn_suffix do balanceador>"},{"Name":"TargetGroup","Value":"<arn_suffix do grupo-alvo do api>"}]},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
  {"Id":"e5","MetricStat":{"Metric":{"Namespace":"AWS/ApplicationELB","MetricName":"HTTPCode_ELB_5XX_Count","Dimensions":[{"Name":"LoadBalancer","Value":"<arn_suffix do balanceador>"}]},"Period":2592000,"Stat":"Sum"},"ReturnData":false},
  {"Id":"sli","Expression":"1 - (FILL(t5,0) + FILL(e5,0)) / req","ReturnData":true}
]
JSON
aws cloudwatch get-metric-data --metric-data-queries file:///tmp/sli-api-30d.json \
  --start-time "$(date -u -d '-30 days' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)"
# Aprova com sli >= 0.995
```

A parte do TxSigner (batimento em intervalos de 240 s, no máximo 54 intervalos ruins em 30 dias) usa a mesma expressão do alarme `slo-txsigner-availability`, somada em 30 dias:

```bash
aws cloudwatch get-metric-data --start-time "$(date -u -d '-30 days' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" \
  --metric-data-queries '[{"Id":"up","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"process_uptime_seconds","Dimensions":[{"Name":"job","Value":"txsigner"}]},"Period":240,"Stat":"SampleCount"},"ReturnData":false},{"Id":"bad","Expression":"IF(FILL(up,0) == 0, 1, 0)","ReturnData":true}]' \
  --query 'sum(MetricDataResults[0].Values)'
# Aprova com até 54 intervalos ruins (0,5% de 10.800)
```

**U2:NFR4.1, 99% dos minutos.** Intervalos de 240 s com o preço mais velho acima de 60 s, ou sem dado:

```bash
aws cloudwatch get-metric-data --start-time "$(date -u -d '-30 days' +%FT%TZ)" --end-time "$(date -u +%FT%TZ)" \
  --metric-data-queries '[{"Id":"age","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"marketdata_oldest_price_age_seconds"},"Period":240,"Stat":"Maximum"},"ReturnData":false},{"Id":"bad","Expression":"IF(FILL(age,3600) > 60, 1, 0)","ReturnData":true}]' \
  --query 'sum(MetricDataResults[0].Values)'
# Aprova com até 108 intervalos ruins (1% de 10.800)
```

O intervalo de 240 s é mais grosso que o minuto do requisito: um valor acima de 60 s marca o intervalo inteiro como ruim. Isso é conservador. A consulta precisa da conta com tráfego do piloto (mainnet) e de 30 dias corridos. Uma leitura antecipada na devnet vale como ensaio, não fecha a meta. O `GetMetricData` devolve no máximo 100.800 pontos por chamada, e os 10.800 intervalos cabem.

## 11. Planejamento de capacidade (piloto: 21 carteiras, fator 2)

Hipóteses do Q2 e do desenho. Os números são estimativas para orientar a leitura do relatório, não medições.

| Recurso | Carga do piloto x2 (cenário) | Capacidade do desenho | Leitura |
|---|---|---|---|
| Ciclos de bot | 63 bots, um ciclo a cada 5 min: cerca de 0,21 ciclo/s | 4 ciclos em paralelo no worker | folga grande; o ciclo p95 tem meta de 2 s |
| Leituras do api | 20 pessoas x 0,5 ação/s = 10 pedidos/s, mais cerca de 84/s nos 10 s de cada rajada (acompanhamento a cada 250 ms) | pool de 20 conexões | pela lei de Little, com cerca de 20 ms por consulta, menos de 2 conexões ocupadas em média |
| Saques e TxSigner | 21 saques em 10 s: cerca de 2,1 assinaturas/s no TxSigner e 1 por carteira por minuto | 60 assinaturas por carteira e minuto; um TxSigner com pool de 5 | o limite por carteira não chega perto; o TxSigner único é o ponto a observar no p99 |
| Reconciliação | 21 carteiras por rodada, até 4 em paralelo, até 3 chamadas de RPC cada | 60 s por rodada (6 levas de até 5 s dão 30 s) | na CI o RPC é local e rápido; o RPC real só aparece na devnet |
| Banco da aplicação (nuvem) | api com 20 conexões e worker com 36, uma tarefa de cada: 56 | `db.t4g.micro` (1 GiB): `max_connections` padrão de cerca de 100 | **gargalo ao escalar:** com 2 tarefas de api e de worker seriam 112 conexões; antes disso é preciso reduzir os pools ou subir a classe do RDS |
| KMS (nuvem) | 2,1 `Sign`/s no pico de saques | cota de `Sign` assimétrico por conta e região | confirmar a cota no Service Quotas na fase 2 |
| Carteiras | 21 no piloto | o desenho considera 1.000 (U1:NFR3.6) | 1.000 chaves KMS custam cerca de US$ 1.000 por mês, acima do orçamento de US$ 350 por conta: a escala de 1.000 é inalcançável no KMS real sem rever o orçamento |

Recomendação: antes de abrir a escala para mais de 21 carteiras, rodar a rodada de capacidade de 1.000 carteiras na CI (`kms-sim`) e rever o orçamento do KMS e a classe do RDS.

## 12. Arquivos do cenário

| Arquivo | O que é |
|---|---|
| `scripts/load/real.mjs` | executor do cenário: preparação, tráfego das três fases, sondas, verificações no banco e planos |
| `scripts/load/real-stats.mjs` | lógica pura: metas, percentis, histogramas, regras de gargalo, avaliação e relatório em pt-BR |
| `scripts/load/load-test-real.test.mjs` | 34 testes `node:test` sem rede, inclusive o cenário inteiro contra uma pilha simulada |
| `scripts/load/load-test.mjs` | despacho do `LOAD_SCENARIO=real` e gravação do `load-test-report.json` e do `docs/perf/real-trading-<data>.md` |
| `scripts/load-test.sh` | caso `real`: overlays, segredos efêmeros do TxSigner, imagem do TxSigner, convites, administrador e contêiner do TxSigner |
| `infra/docker-compose.loadtest-real.yml` | overlay de medição do cenário (seção 2) |
| `apps/api/src/main/resources/application-loadtest.yml` | buckets de `real_ops_job` e `real_risk_job` só no perfil `loadtest` |
| `apps/api/src/test/java/com/memetrade/config/LoadtestRealHistogramsTest.java` | prova que só o perfil `loadtest` publica esses buckets |
| `apps/api/src/test/java/com/memetrade/marketdata/infra/coingecko/CoinGeckoRoundMeasurementTest.java` | medição sintética de U2:NFR3.1 a NFR3.3 (300 ms por chamada) |

## Sources

- `performance-validation-questions.md`: Q1 a Q5, todas A, e a confirmação do resumo.
- `construction/build-and-test/build-and-test-summary.md`: as 19 metas com dono `performance-validation`.
- `construction/build-and-test/performance-test-instructions.md`.
- `construction/u1-bot-wallet/nfr-requirements/performance-requirements.md`, `scalability-requirements.md` e `reliability-requirements.md`: NFR3.1 a NFR3.9 e NFR4.1.
- `construction/u2-real-market-data/nfr-requirements/performance-requirements.md` e `reliability-requirements.md`: NFR3.1 a NFR3.4 e NFR4.1.
- `construction/u3-real-risk/`, `u4-real-execution/` e `u5-real-operations/nfr-requirements/performance-requirements.md`: NFR3.x.
- `construction/u*/nfr-design/performance-design.md` e `scalability-design.md`: pools de 20, 36 e 5; trava com `pg_try_advisory_xact_lock`; executor de 4 carteiras da reconciliação.
- `operation/observability-setup/slo-config.md`, `alarms.md` e `dashboards.md`: SLIs, burn rate, namespace `Eureka/App`, lacunas OBS-G1 e OBS-G2 e o painel `eureka-real`.
- `operation/environment-provisioning/environment-inventory.md`: recursos NÃO PROVISIONADOS, RDS `db.t4g.micro`, trilha `/eureka-real/cloudtrail` e custo do KMS.
- `.github/workflows/load-test.yml`, `scripts/load-test.sh`, `scripts/load/*.mjs`, `infra/docker-compose.real.yml` e `infra/solana-fixtures/recorded.env`.
- `aidlc/spaces/default/memory/team.md` ("Desempenho", "Determinismo") e `project.md` (orçamento de US$ 350 por conta, leitura do agente a cada 120 s).

## Assumptions & Open Questions

- **Ciclo do worker:** o Q3 cita "p95 do ciclo do worker" sem número próprio do REAL. O cenário usa o limite de 2 s do ciclo do bot da intent anterior (U5 NFR2.1). Se o responsável quiser outro limite, ele entra nos requisitos primeiro.
- **"Rotas do REAL até 1 s"** (Q3): não há meta de 1 s nos requisitos das U1 a U7 para rotas além das listadas. A `GET /real/release` é medida e registrada sem limite próprio.
- **Bots fora do mint gravado:** o cenário assume que a barreira recusa, como recusa de domínio, as decisões em mints sem dado REAL. Se alguma ordem REAL for criada para esses mints, ela falhará na Jupiter simulada e o run reprova. Isso seria um achado verdadeiro, não um ruído.
- **Cota de `Sign` do KMS e `max_connections` do RDS:** conferir na fase 2.
- **Modo devnet do executor:** a construir antes da fase 2; seção 9.
