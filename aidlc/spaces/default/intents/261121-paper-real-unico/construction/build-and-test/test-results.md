# Resultados de Build and Test — intent `261121-paper-real-unico`

Data: 2026-11-25. Commit: `fc65b7f83e079d4396c97e6d9a4123c7cf9fbdfe` (HEAD de `main`, igual ao `origin/main`). Saída desta execução: **falha pelo predicado do Step 9** (há alvos `Not Met` e `Unverified`), com **halt-and-ask** ao fundador. Nenhum comando de build ou de teste falhou.

## 1. Como foi executado

| Passo do Step 9 | O que rodou | Onde |
|---|---|---|
| 1. Build | `mvnw verify` do `apps/api` e do `apps/txsigner`, lint, formatação e tipos dos dois apps web | CI `ci` run `45516978139` (o `mvnw` e o `verify.sh` são mortos por memória nesta máquina; `team.md`, Walking Skeleton) |
| 2. Testes de unidade | os comandos dos 4 `unit-test-instructions.md` são subconjuntos (`-Dtest=<lista>`, `jest <arquivos>`) das suítes completas; cada suíte completa rodou uma vez | CI (backend, txsigner, frontend, admin); local: `corepack pnpm@10.18.3 --dir apps/web exec jest --coverage` e `--dir apps/admin` |
| 3. Integração | failsafe completo do `apps/api` (Testcontainers) | CI backend |
| 4. Outros | contrato; `scripts/verify.sh` (ponta a ponta); `promtool check rules`; workflow `security`; testes offline do cenário de carga | CI contract, smoke, e2e, backend; CI `security` run `45516977943`; local `node --test "scripts/load/*.test.mjs"` |
| 4. Adiados | cenário `pnl-by-mode` (workflow manual `load-test`), repetição na devnet, custo nas contas AWS, ordem de implantação | `performance-validation`, `environment-provisioning`, `deployment-execution` (ficam `Unverified`) |
| 4. Não executáveis aqui | `terraform fmt/validate/test`, Checkov (não instalados); medida oficial da U4 (memória) | `Unverified` |

Condições da prova pela CI (`team.md`): o commit está no remoto; `git status` mostra mudanças só no registro do AI-DLC (`aidlc/spaces/default/intents/261121-paper-real-unico/`) e o arquivo de sessão não rastreado `apps/web/aidlc/.aidlc-sessions/<sessão>.binding.json`, que é estado de máquina do AI-DLC e não código. O `verify.sh` local completo também rodou hoje, no ponto de controle da U4, e terminou com "walking skeleton verified".

## 2. Build

**Sucesso.** CI `45516978139`, evento `push`, 2026-11-25 00:17 a 01:17 UTC:

| Job | Resultado | Duração |
|---|---|---|
| `contract` | success | 2 min |
| `admin` | success | 1 min |
| `backend` | success (`BUILD SUCCESS`, 37 min 07 s de Maven) | 37 min |
| `smoke` | success | 25 min |
| `txsigner` | success | 2 min |
| `frontend` | success | 2 min |
| `e2e` | success | 22 min |

Workflow `security` `45516977943` no mesmo commit: `secrets`, `sast (java-kotlin)`, `sast (javascript-typescript)`, `dependencies` com success.

## 3. Testes

| Suíte | Total | Passaram | Falharam | Pulados | Fonte |
|---|---|---|---|---|---|
| `apps/api` unidade (surefire) | 2.511 | 2.511 | 0 | 0 | CI backend |
| `apps/api` integração (failsafe) | 1.684 | 1.684 | 0 | 0 | CI backend |
| `apps/txsigner` | 247 (230 + 17) | 247 | 0 | 0 | CI txsigner |
| `apps/web` Jest | 1.004 (86 suítes) | 1.004 | 0 | 0 | CI frontend e local (164 s) |
| `apps/admin` Jest | 221 (20 suítes) | 221 | 0 | 0 | CI admin e local (98 s) |
| Contrato OpenAPI | 25 | 25 | 0 | 0 | CI contract; `check:api` dos dois apps: tipos iguais ao contrato |
| Ponta a ponta (`verify.sh`) | 62 | 62 | 0 | 0 | CI smoke (16,6 min) e CI e2e (15,7 min); 20 casos do `zz-paper-real-unico` em cada |
| Cenários de carga offline (`node:test`) | 104 | 104 | 0 | 0 | local (255 ms) |
| Regras de alerta (`promtool check rules`) | 6 arquivos, 63 regras | 63 | 0 | 0 | CI backend |

**Falhas:** nenhuma. Não há nome de teste, asserção nem stack trace a relatar.

Lint, Prettier e `tsc --noEmit` verdes nos dois apps; Spotless "1887 were already clean"; enforcer com as 4 regras aprovadas; ArchUnit dentro do surefire.

## 4. Cobertura

| Parte | Ferramenta | Linhas | Piso | Resultado |
|---|---|---|---|---|
| `apps/api` | JaCoCo 0.8.15, `jacoco-merged.exec` (unidade + integração), 1.605 classes | ≥ 80% (o `jacoco:check` não imprime o percentual) | 0,80 (`coverage.line.minimum`) | "All coverage checks have been met" |
| `apps/txsigner` | JaCoCo | ≥ 80% | 0,80 | "All coverage checks have been met" |
| `apps/web` | Jest, `coverageThreshold.global.lines = 80` | 94,43% (5.757/6.096); statements 93,93%, branches 87,16%, functions 91,31% | 80% | cumprido (CI e local iguais) |
| `apps/admin` | Jest | 89,81% (1.490/1.659); statements 89,35%, branches 84,82%, functions 79,07% | 80% | cumprido |

As exclusões do JaCoCo estão na configuração da ferramenta (`MemetradeApplication`, `*Config`, `*Properties` e outras listadas no `argLine`). Nenhuma exclusão nova foi decidida nesta etapa.

## 5. Desempenho (números da CI, Linux, 2 vCPU)

| Medida | Meta | Resultado | Veredito |
|---|---|---|---|
| U1 C4 / C2 / C3 / C1 (`ModePnlLatencyIT`) | p95 ≤ 1.000 ms | 172 / 5 / 4 / 8 ms | Met |
| U3 C4 `PAPER ALL`, `REAL ALL`, `PAPER 30d`, `REAL 30d` (`PnlViewsLatencyIT`) | p95 ≤ 1.000 ms | 58, 132, 18, 154 ms | Met (só IT) |
| U3 `listBots` PAPER / REAL / sem `market` | p95 ≤ 1.000 ms | 3 / 3 / 4 ms | Met |
| U3 C5 primeira / seguinte | p95 ≤ 1.000 ms | 152 / 146 ms | Met (só IT) |
| U3 C6 `ALL` / `RANGE 30d` | p95 ≤ 1.000 ms | 147 / 143 ms | Met (só IT) |
| U2 atribuição `GATE` (`RealAttributionMeasurementIT`) | p95 ≤ 250 ms | 8 ms; 0 timeouts; 0/100 inconsistentes | Met |
| U2 compra com posição sem preço | p95 ≤ 400 ms | 62 ms (carteiras sem marca de zeragem: 975 ms, pior caso informativo) | Met |
| U2 barreira REAL, compra / venda (`RealRiskMeasurementIT`) | teto 500 ms / 30 ms | 16 ms / 5 ms | Met |
| U2 cenário `pnl-by-mode` (C4, `GET /api/v1/real/pnl`, atribuição `PNL`) | p95 1.000 / 1.000 / 500 ms | não executado (sem opção no `load-test.yml`; nenhuma execução do workflow) | Unverified |
| **U4 leitura do `RealBotHoldings`, semente A, com o prazo de 250 ms** | **p99,9 ≤ 250 ms (≤ 0,1% `BUDGET_EXCEEDED`)** | n=300: p50 208, p99 256, p99,9 266 ms (máximo); `budgetExceeded` 55 (18,3%); `readFailed` 0 | **Not Met** |
| U4 semente A, sem prazo | informativo | p50 206, p99 328, p99,9 350 ms; acima de 250 ms: 36 (12,0%) | — |
| **U4 semente B (sem zeragem em 2 anos), com o prazo** | **p99,9 ≤ 250 ms** | n=300: p50 256, p99 260, p99,9 262 ms (corte); `budgetExceeded` 300 (100%) | **Not Met** |
| U4 semente B, sem prazo | informativo | p50 440, p99 742, p99,9 775 ms; acima de 250 ms: 300 (100%) | — |
| U4 ciclo com a flag ligada | p95 ≤ 2.000 ms | não medido (a CI roda com `u4.measure.cycles=0`) | Unverified |

Informativo da mesma execução (outros intents, sem meta deste): U9 abertura do painel `BOTS` p95 2.838 ms acima do alvo informativo de 2.000 ms do U9-NFR3.1; U8 kill switch com 60 bots p95 1.658 ms.

**U2-NFR1.5, limite relativo.** A base em `docs/perf/u2-barrier-baseline.md` (commit `30017d4`, nesta máquina Windows: compra 206 ms, venda 15 ms) não se compara com a CI Linux. O fundador decidiu em 2026-11-23 (audit: "Faça a comparação mas o teto passa a ser de 500ms") o teto absoluto de 500 ms para a compra e a comparação intercalada na mesma máquina. O coordenador relata a comparação intercalada como aprovada; os números dela não estão gravados em `docs/perf/` nem no registro. O veredito `Met` se apoia nos tetos absolutos medidos na CI (16 ms e 5 ms, com folga de 30× e 6×) e nesse relato.

## 6. Matriz final

A matriz completa, com uma linha por alvo, está em `build-and-test-summary.md`, seção `## Target Verification Matrix` (as mesmas linhas e os mesmos vereditos; não foi duplicada aqui por tamanho). Totais: **300 alvos — Met 279, Not Met 4, Unverified 16, N/A 1.** Nenhum `Pending`.

Alvos que não são `Met`:

| Target ID | Expected | Actual | Evidence | Owning Stage | Verdict |
|---|---|---|---|---|---|
| U4-NFR1.1 | leitura do `RealBotHoldings` p99,9 ≤ 250 ms | A: p99,9 266 ms, 18,3% `BUDGET_EXCEEDED`; B: 100% `BUDGET_EXCEEDED`, 775 ms sem prazo | CI backend, `RealBotHoldingsMeasurementIT` (linhas `u4-measure`) | build-and-test | Not Met |
| U4-NFR1.13 | SLI: ≤ 0,1% de `BUDGET_EXCEEDED` | 18,3% (A) e 100% (B) | o mesmo | build-and-test | Not Met |
| TGT-U4-ALARM | premissa dos alarmes de NFR2.12 (razão > 5% por 15 min) e NFR2.18 (3 seguidas): em operação normal ≤ 0,1% de falha | 18,3% e 100%: os dois alarmes disparariam em operação normal com a flag ligada | o mesmo; U4 `observability-requirements.md`, "Cálculo do limite" | build-and-test | Not Met |
| U2-NFR1.9 | `load-test.yml` com a opção `pnl-by-mode` (texto pronto do `code-summary.md` da U2) | opção ausente; arquivo do responsável | `grep pnl-by-mode .github/workflows/load-test.yml`: 0 | performance-validation | Not Met |
| U2-NFR1.1 | C4 p95 ≤ 1.000 ms com 20 pessoas | não executado | esperado `load-test-report.json`, `docs/perf/u2-real-bot-pnl-<data>.md` | performance-validation | Unverified |
| U2-NFR1.2 | `GET /api/v1/real/pnl` p95 ≤ 1.000 ms | não executado | o mesmo | performance-validation | Unverified |
| U2-NFR1.3 | atribuição `PNL` p95 ≤ 500 ms | não executado | o mesmo | performance-validation | Unverified |
| U2-NFR1.6 | cenário e portões (pool, memória ≤ 900 MB, `EXPLAIN` com o índice da `0023`) | cenário pronto; execução pendente | o mesmo | performance-validation | Unverified |
| U2-NFR1.24 | repetição de C4 na devnet | sem ambiente | esperado em `docs/perf/` | performance-validation | Unverified |
| U2-NFR1.25 | execução verde de `pnl-by-mode` no SHA candidato | nenhuma execução | o mesmo do U2-NFR1.1 | performance-validation | Unverified |
| U3-NFR1.6 | portões de C4 `RANGE` 30/366 d, C5 e C6 no cenário ampliado | não executado | o mesmo | performance-validation | Unverified |
| U4-NFR1.2 | ciclo com a flag ligada p95 ≤ 2.000 ms | não medido | esperado `docs/perf/u4-own-quantity-cycle-<data>.md` | build-and-test | Unverified |
| U4-NFR1.5 | medida oficial: 10.000 leituras × 3 execuções, mediana | só 300 leituras × 1 na CI | o mesmo | build-and-test | Unverified |
| U4-NFR2.17 | razão `STALE_SNAPSHOT` = 0 sem concorrência | não medida na CI | o mesmo | build-and-test | Unverified |
| U2-NFR8.2 | orçamento por conta não muda (+ US$ 0,40) | sem conta provisionada | AWS Budgets | environment-provisioning | Unverified |
| U4-NFR8.2 | orçamento por conta não muda (+ cerca de US$ 1,90) | sem conta provisionada | AWS Budgets | environment-provisioning | Unverified |
| U4-NFR8.4 | teste offline do Terraform: flag `false` por padrão | `main.tf` correto por inspeção; `terraform test` não rodou | `run own_quantity_flag_default` | environment-provisioning | Unverified |
| U4-NFR8.3 | flag ligada primeiro na devnet e depois na mainnet com o mesmo SHA | nenhuma configuração abre a devnet; a ordem é da implantação | registro da implantação | deployment-execution | Unverified |
| TGT-IAC-1 | `terraform fmt -check`, `validate`, `test` verdes | não instalado | saída do `terraform test` | environment-provisioning | Unverified |
| TGT-IAC-2 | Checkov sem achado bloqueante | não instalado | relatório do Checkov | environment-provisioning | Unverified |
| TGT-CI-MERGE | Run the selected tests in CI before merge. | — | CI Pipeline, later in this plan, sets up CI to run these tests before merge | ci-pipeline | N/A |

## 7. Diagnóstico da falha

### 7.1 U4-NFR1.1, U4-NFR1.13 e TGT-U4-ALARM (Not Met)

- **O que mede:** a leitura da posição própria do bot (C15, `RealBotHoldingsReader`, passos S0 a S6) antes da transação do ciclo REAL, com prazo duro de 250 ms (`ReadDeadline` e `statement_timeout`). Acima do prazo, a leitura vira `Unavailable(BUDGET_EXCEEDED)` e o bot não vende naquele ciclo (falha fechada; saque, "Vender tudo e sacar", condicionais e liquidação não dependem dela).
- **Semente B (sem zeragem em 2 anos):** a leitura inteira leva cerca de 440 ms de mediana na CI. O custo é o passo S4 pelo fluxo de C13 (`streamSettledOrders`), que lê todo o histórico de ordens assentadas da pessoa, cerca de 14.300 linhas por leitura, porque sem marca de zeragem a época de cada mint é o início do histórico e o número de ordens citadas passa do limite `ORDER_PREFETCH_THRESHOLD = 32`. Com o prazo, 100% das leituras são cortadas.
- **Semente A (com zeragens):** a mediana (206 a 208 ms) fica perto do prazo; a cauda passa dele em 18,3% das leituras (12,0% sem prazo). O custo dominante é o `RealLedger.view` desde a época mais a busca das ordens por id (S4 por `= ANY`, já otimizado na Code Generation). Os números desta máquina Windows na Code Generation variaram de 2,3% a 26,3% entre rodadas; a CI Linux confirma que o problema não é ruído da máquina local.
- **Efeito:** com a flag ligada, a taxa de `BUDGET_EXCEEDED` em operação normal passaria muito de 0,1%. O alarme de razão (> 5% por 15 min) disparia continuamente, e na situação da semente B todo bot entraria no alarme de "bot preso" e nunca venderia pelo ciclo. A amostra de 300 leituras basta para essa conclusão: 55 e 300 leituras acima do prazo contra o limite de 0,3 leitura (0,1% de 300).
- **Classificação (rung 2):** a causa está no caminho de leitura escolhido na Code Generation (S4 pelo fluxo de C13 acima de 32 ordens e a releitura completa a cada ciclo, por ADR-004 "nada guardado entre leituras"). Não é configuração de teste nem de build, então não há correção dentro desta etapa (rung 1 não se aplica).
- **Exposição atual:** nenhuma. A flag `memetrade.real.own-quantity-sell` é `false` por padrão no código e no Terraform, e a U4 não muda o comportamento com ela desligada (U4-NFR1.3, verde). A meta precisa ser cumprida antes da implantação que liga a flag (BR5.2).

### 7.2 U2-NFR1.9 (Not Met) e os alvos de carga (Unverified)

O texto pronto do `load-test.yml` (com `pnl-by-mode` e a pendência `real` do 261113) está no `code-summary.md` da U2, mas não foi aplicado; só o responsável altera `.github/workflows/`. Sem ele, o portão de carga da U2 e da U3 não pode rodar. Não é defeito de código: o cenário existe e os 104 testes offline passam. Esforço do responsável: colar o texto (minutos) e disparar `gh workflow run load-test -f scenario=pnl-by-mode` (45 a 70 min de runner, dentro da cota do Actions).

### 7.3 Medida oficial da U4 e IaC (Unverified)

- A medida oficial (U4-NFR1.2, NFR1.5, NFR2.17) não roda nesta máquina (memória). A CI usa 300 leituras e 0 ciclos. Caminho: disparo manual da CI ou máquina Linux com `-Du4.measure.reads=10000 -Du4.measure.cycles=100`, 3 vezes. Ela confirma os números, mas não muda a conclusão de 7.1.
- `terraform` e Checkov não estão instalados; o `team.md` diz que esses testes rodam à mão e que levá-los à CI é pendência do responsável.

## 8. Halt-and-ask: decisão do fundador

**Por que não houve loop-back.** O estado registra `Construction Autonomy Mode: autonomous`, e o rung 3 permitiria reabrir a Code Generation da U4 sozinho. Ele não foi disparado, por instrução do coordenador e pela regra do fundador: decisões de dinheiro e de desempenho são dele, e as opções abaixo mudam a meta, o desenho aprovado ou uma alternativa já rejeitada (U4-NFR8.12, ADR-004). Por isso não há `## Loop-Back Log`.

**Pergunta.** A leitura da posição própria da U4 não cumpre p99,9 ≤ 250 ms na CI (semente A: 18,3% cortadas; semente B: 100%). A flag está desligada e nada muda em produção. Como seguir antes de ligar a flag?

| Opção | O que muda | Esforço | Risco | Custo | Impacto esperado |
|---|---|---|---|---|---|
| **1. Buscar as ordens por id num só comando também acima de 32** (`real_order_id = any(:ids)`), ou um caminho por id paginado (por exemplo, lotes de 1.000 ids), no lugar do fluxo de C13 | `RealBotHoldingsReader`, passo S4, e `JdbcRealExecutionStore.settledOrdersById`; sem mudança de esquema nem de contrato | 0,5 a 1 dia de código e testes (o `RealBotHoldingsOrderPathParityIT` já prova a equivalência dos dois caminhos; acrescentar casos com 33, 1.000 e 15.000 ids) mais 1 execução da medida | Baixo a médio: mesmo resultado por construção, mesmo instantâneo `REPEATABLE READ`; risco de plano ruim com listas grandes (conferir com `EXPLAIN` no IT de plano) | US$ 0 de infraestrutura; cerca de 40 min de runner por execução da medida | Ataca a semente B: troca a leitura das ~14.300 ordens da pessoa pelas ordens citadas pelos lançamentos desde a época. Estimativa: de cerca de 440 ms para 150 a 250 ms de mediana na B; pouco efeito na cauda da A, que já usa o caminho por id. Sozinha, provavelmente não leva a A a ≤ 0,1%. |
| **2. Cache da posição por bot**, chaveado pela marca do bot e pela marca d'água do ledger (maior sequência de lançamento REAL da carteira e da ordem), invalidado em toda gravação | worker: um mapa em memória por bot, conferido dentro do mesmo instantâneo; sem tabela nova | 1,5 a 3 dias, com testes antes do código (núcleo financeiro): acerto, invalidação por lançamento, por ordem que muda de estado, por concorrência com `STALE_SNAPSHOT`, reinício do worker | Médio: um cache velho venderia a quantidade errada; mitigado porque a chave é conferida no próprio instantâneo da leitura (qualquer mudança é falta de cache). **Reabre a alternativa rejeitada U4-NFR8.12 ("guardar a quantidade própria numa tabela ou cache") e toca a regra "nada guardado entre leituras" do ADR-004: exige decisão do fundador.** | US$ 0 (memória do worker: dezenas de KB para 63 bots) | Com um ciclo a cada 5 min e poucos lançamentos novos por bot, a maior parte das leituras vira acerto de cache (consulta só das marcas, poucos ms). Ataca a cauda da semente A e o caso estável da B; a primeira leitura depois de uma mudança continua com o custo do caminho completo, por isso combina com a opção 1. |
| 3. Tabela-resumo da posição por bot mantida na gravação | ledger: tabela nova gravada na mesma transação dos três gravadores do ledger; migração aditiva e carga inicial | 4 a 7 dias, com testes antes do código, conferência exata com o ledger, migração expand-contract e revisão do ADR-004 | Alto: muda o núcleo financeiro (ledger) e o ADR-004; também reabre U4-NFR8.12 e a decisão da U2 (U2-NFR8.11, P&L guardado); risco de divergência entre a tabela e o ledger | pouco armazenamento no RDS; sem serviço novo | Leitura O(bots), poucos ms, nas duas sementes. Resolve o desempenho de vez, com o maior custo de mudança. |
| 4. Orçamento maior que 250 ms | só a configuração (`BR2.2`, NFR1.7); é **mudança de meta, decisão exclusiva do fundador**, nunca aplicada por esta etapa | minutos, mais o recálculo do limite dos alarmes (NFR2.12, NFR2.18) | Baixo para o código; o ciclo fica mais longo (a leitura fica fora da trava, dentro do limite de 2 s do ciclo) | US$ 0 | Com 400 ms, a semente A ficaria quase toda dentro (sem prazo: p99,9 350 ms); a B continuaria fora (p99 742 ms). Não resolve o pior caso. |

**Recomendação:** opção 1 primeiro (barata, sem mudança de desenho, ataca a semente B), medir de novo em Linux, e em seguida a opção 2, se o fundador aceitar reabrir U4-NFR8.12 para um cache validado no instantâneo, para levar a cauda da semente A a ≤ 0,1%. As opções 3 e 4 ficam como alternativas; a 4 só pelo fundador.

Para todas as opções: a flag continua desligada até a medida oficial (10.000 leituras × 3, Linux) cumprir a meta.

**Outras decisões do responsável, sem mudança de meta:**

- aplicar o texto pronto do `load-test.yml` e disparar `pnl-by-mode` (U2-NFR1.9, NFR1.25; U3-NFR1.6);
- rodar `terraform fmt -check -recursive`, `validate`, `test` e o Checkov à mão (TGT-IAC-1, TGT-IAC-2, U4-NFR8.4);
- registrar em `docs/perf/` os números da comparação intercalada da barreira de 2026-11-23 (U2-NFR1.5), para a evidência ficar no repositório;
- corrigir os `traceability.json` da Code Generation (achados F-T1 a F-T3 de `cross-unit-traceability.md`).

## Loop-Back Log

### Loop-back 1 — 2026-11-25T03:30:00Z

- **Diagnóstico:** U4-NFR1.1 `Not Met`, com os números da CI Linux do `fc65b7f` (run 45516978139, n=300). Na semente A, 18,3% das leituras passam de 250 ms; na semente B (nenhuma zeragem em 2 anos, ~14 mil ordens), 100%. Em consequência, U4-NFR1.13 e a premissa dos alarmes (TGT-U4-ALARM) também ficam `Not Met`. O custo vem de S4: acima de 32 ordens, a leitura percorre o fluxo de C13, que lê o histórico inteiro de ordens da carteira.
- **Etapa da causa:** `code-generation` da U4 (`u4-own-quantity-cycle`).
- **Correção planejada:** duas decisões do fundador (F6, 2026-11-25):
  1. **Orçamento:** o orçamento da leitura passa de 250 ms para 500 ms (`ReadDeadline`, U4-NFR1.1, U4-NFR1.13 e as premissas dos alarmes), com os textos de desenho alinhados.
  2. **Busca por id:** S4 busca as ordens por id num só comando (`= ANY(:ids)`, em lotes se for preciso) também acima de 32 ordens, no lugar do fluxo de C13. A equivalência exata é conferida pelos testes de paridade de S4 (32 e 33 ordens, sementes A e B) e pelos cenários T.
  
  Depois disso, a medida é refeita em Linux pela CI.
- **Impacto estimado:** de 0,5 a 1 dia de esforço, US$ 0 e risco de baixo a médio. A semente B deve cair de ~440 ms para 150 a 250 ms, dentro de 500 ms com folga. A flag continua desligada.
