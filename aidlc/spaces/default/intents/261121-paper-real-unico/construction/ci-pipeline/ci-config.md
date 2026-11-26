# Configuração da CI — intent `261121-paper-real-unico`

Data: 2026-11-25. Commit de referência: `09de0a8daea32fc9ec9d8bfe380a9eac9f3b6c29` (`main`, `HEAD`). Runs de referência: `ci` **45550427456** (tentativa 2, 7 jobs `success`) e `security` **45550427465** (`success`), os dois no mesmo commit (`build-and-test/test-results.md`, seção 9.1).

Decisões deste estágio (`ci-pipeline-questions.md`, Q1 a Q5 todas opção A, resumo confirmado):
- a CI continua no GitHub Actions (`ci`, `security` e `load-test` manual), com o tronco `main` e commits diretos; uma `main` vermelha se corrige no commit seguinte [Q1];
- os portões que já existem continuam sem mudança, e o intent não acrescenta job nem muda os existentes [Q2];
- as mudanças de workflow chegam como texto pronto para o responsável aplicar: o `load-test.yml` com o `pnl-by-mode` da U3 e o passo `node --test` do `ci.yml`; as pendências do `team.md` continuam citadas, sem mudança [Q3];
- nenhum repositório de artefatos novo; o ECR fica para o `deploy.yml`, que ainda não existe [Q4];
- os defeitos de registro F-T1 a F-T3 da rastreabilidade são corrigidos antes do veredito da fronteira (`verification/phase-check-construction.md`) [Q5].

Nenhum arquivo em `.github/workflows/` nem o `scripts/verify.sh` foi editado neste estágio, e nenhum código mudou. Os únicos arquivos alterados fora desta pasta são os quatro `traceability.json` da Code Generation (correções de registro da Q5, descritas em `verification/phase-check-construction.md`, seção 1).

## 1. Pipeline atual (lido dos arquivos em `.github/workflows/` no `09de0a8`)

### 1.1 Workflows

| Workflow | Arquivo | Gatilhos | `permissions` | Concorrência |
|---|---|---|---|---|
| `ci` | `.github/workflows/ci.yml` (196 linhas) | `pull_request` (qualquer branch), `push` em `main`, `workflow_dispatch` | `contents: read` no workflow; o job `txsigner` repete `contents: read` | `ci-${{ github.ref }}`, cancela o run anterior do mesmo ref |
| `security` | `.github/workflows/security.yml` (127 linhas) | `pull_request`, `push` em `main` (sem `workflow_dispatch`) | `contents: read`; o job `sast` acrescenta `actions: read` | nenhuma |
| `load-test` | `.github/workflows/load-test.yml` (63 linhas) | só `workflow_dispatch`, com a entrada `scenario`: `u1`, `u4`, `u3`, `growth`, `u7`, `u7-jobs`, `u6`, `u5`, `legal`, `reacceptance`, `data-requests`. **Não tem `real` nem `pnl-by-mode`** | `contents: read` | nenhuma |
| (atualizações) | `.github/dependabot.yml` | semanal: maven (`/apps/api`), npm (`/apps/web`, `/apps/admin`), docker, docker-compose, github-actions | — | — |

O único segredo usado é o `NVD_API_KEY` no job `dependencies` (leitura da NVD, não de produção). Nenhum segredo de produção existe nos workflows.

### 1.2 Jobs do `ci`

| Job | Depende de | Quando roda | Passos (na ordem do arquivo) |
|---|---|---|---|
| `backend` | — | todo PR, push e disparo manual | `Compose config` (`docker compose -f docker-compose.yml config --quiet` e com `-f infra/docker-compose.test.yml`, valores de exemplo); `Verify backend` (`sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify`: Spotless, enforcer, Surefire, Failsafe com Testcontainers, ArchUnit, JaCoCo com piso de 80%); `Alert rules (promtool)` nos 6 arquivos de `infra/observability/` (imagem `prom/prometheus` fixada por digest); artefato `backend-coverage` (7 dias) |
| `frontend` | — | idem | `apps/web`: `install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `Jest with the 80% line floor` (`exec jest --coverage`) |
| `contract` | — | idem | `GuestOpenApiContractTest` e `AdminOpenApiContractTest` pelo `mvnw ... test`; `pnpm --dir apps/web check:api` |
| `smoke` | — | idem | `playwright install --with-deps chromium`; `bash scripts/verify.sh` com `IMAGE_TAG` e `BUILD_SHA` = `${{ github.sha }}`; artefato `smoke-report` (7 dias) |
| `admin` | — | idem | `apps/admin`: `install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` |
| `txsigner` | — | idem | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` (testes, JaCoCo 80%, Spotless) |
| `e2e` | os 6 acima | só `push` em `refs/heads/main` | `bash scripts/verify.sh` de novo, com a mesma etiqueta pelo SHA |

### 1.3 Jobs do `security`

| Job | O que impõe |
|---|---|
| `secrets` | Gitleaks na versão de `.gitleaks-version`, com checksum conferido; `gitleaks git` (histórico completo, `fetch-depth: 0`) e `gitleaks dir` (árvore) com `.gitleaks.toml` |
| `sast` (matriz `java-kotlin`, `javascript-typescript`) | CodeQL v3.29.5; Java com build manual (`mvnw package` sem testes); falha com qualquer resultado `level == "error"` e só avisa a contagem de `warning`; resultado como artefato (30 dias), sem upload |
| `dependencies` | `SecurityExceptionsTest` (exceção vencida volta a bloquear); exceções `dependency-check` viram supressões com `until`; OWASP Dependency-Check 12.1.0 com `-DfailBuildOnCVSS=7`; `pnpm audit --audit-level high` em `apps/web` e `apps/admin` |

### 1.4 Ações fixadas

Todas as ações de terceiros dos três workflows estão fixadas por SHA de 40 caracteres, e o `WorkflowsTest` (`apps/api/src/test/java/com/memetrade/infra/WorkflowsTest.java`, roda no `backend`) confere isso, as permissões só de leitura e a ausência de `pull_request_target`. Os SHAs continuam os da versão em Node 20, porque a troca para Node 24 ainda é pendência do responsável (seção 4):

| Ação | SHA | Versão |
|---|---|---|
| `actions/checkout` | `11bd71901bbe5b1630ceea73d27597364c9af683` | v4.2.2 |
| `actions/setup-java` | `c5195efecf7bdfc987ee8bae7a71cb8b11521c00` | v4.7.1 |
| `actions/setup-node` | `49933ea5288caeca8642d1e84afbd3f7d6820020` | v4.4.0 |
| `pnpm/action-setup` | `a7487c7e89a18df4991f7f222e4898a00d66ddda` | v4.1.0 |
| `actions/upload-artifact` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | v4.6.2 |
| `actions/cache` | `5a3ec84eff668545956fd18022155c47e93e2684` | v4.2.3 |
| `github/codeql-action/init` e `/analyze` | `51f77329afa6477de8c49fc9c7046c15b9a4e79d` | v3.29.5 |
| imagem `prom/prometheus` (promtool) | `sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996` | digest |

## 2. Textos prontos para o responsável (Q3)

Pelo `team.md` ("Só o responsável do projeto altera os arquivos de `.github/workflows/`"), nada abaixo foi aplicado. Os dois textos não trazem ação nova, SHA novo nem permissão nova, então o `WorkflowsTest` continua valendo como está.

### 2.1 `load-test.yml`: cenário `pnl-by-mode` (texto da U3, 9 leituras e 7 portões)

**Origem:** `construction/u3-pnl-views/code-generation/code-summary.md`, seção "Texto pronto para o responsável: `.github/workflows/load-test.yml`", copiado sem mudança. **Ele substitui o texto da U2** (`construction/u2-real-bot-pnl/code-generation/code-summary.md`), que não deve mais ser usado. Aplicá-lo fecha a meta U2-NFR1.9 (`Not Met`) e permite rodar o portão de carga da U2 e da U3.

**Conferência com `scripts/load/` no `09de0a8`:**

| O que o texto diz | O que o repositório tem | Confere |
|---|---|---|
| 9 leituras por ciclo, 7 novas da U3 com portão de 1 s | `scripts/load/pnl-by-mode.mjs`: `CYCLE` com 9 rótulos (`byModeAll`, `realPnl`, `byModeRange30`, `byModeRange366`, `linesFirst`, `linesNext`, `compareAll`, `compareRange30`, `compareRange366`); `U2_READS` com 2; `NEW_READS` = as 7 restantes | sim |
| relatório em dois grupos, `docs/perf/u2-real-bot-pnl-<data>.md` e `docs/perf/u3-pnl-views-<data>.md` | `scripts/load/load-test.mjs` (bloco `SCENARIO === 'pnl-by-mode'`, `groups.u2`/`groups.u3`) | sim |
| opções `real` e `pnl-by-mode` aceitas pelo script | `scripts/load-test.sh`: `case` com `real)` (exige `infra/solana-fixtures/recorded.env`, presente) e `pnl-by-mode)`; mensagem de erro lista as 13 opções | sim |
| `SPRING_PROFILES_ACTIVE: test,loadtest` | `scripts/load-test.sh` recusa qualquer outro valor | sim |
| testes offline do cenário | `node --test "scripts/load/*.test.mjs"` rodado nesta execução: **104 testes, 104 verdes**, 0 falhas (igual ao `code-summary.md` da U3 e ao TGT-LOAD-OFFLINE) | sim |
| cenários antigos (`u3`, `u7`, `u7-jobs`, `u6`, `u5`) continuam | o texto mantém as 11 opções atuais, que o `WorkflowsTest` confere | sim |

O arquivo inteiro é substituído pelo texto abaixo:

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
# Since 261121-paper-real-unico U3 (NFR1.6): it also reads C4 with RANGE of 30 and 366 days, the first and the next
# page of C5 and C6 with ALL, RANGE of 30 days and RANGE of 366 days, and fails when the p95 of any of these 7 reads,
# timed by the client, exceeds 1 s. The report keeps the gates of the U2 and of the U3 in two groups, each with its
# own result (docs/perf/u2-real-bot-pnl-<date>.md and docs/perf/u3-pnl-views-<date>.md).
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
    # with the 9 reads of the U2 and the U3 (45 to 70 minutes expected). The other scenarios keep the GitHub default
    # of 360 minutes, as before.
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

**Disparo, depois do commit (em um SHA com `ci` verde):**

```bash
gh workflow run load-test -f scenario=pnl-by-mode
gh workflow run load-test -f scenario=real
```

O primeiro é o portão de carga da U2 e da U3 (45 a 70 min de runner; `timeout-minutes: 90`). A leitura do relatório segue a regra R-04 do `code-summary.md` da U3: U2 verde e U3 verde fecham as duas Units; só U3 vermelho deixa só a U3 pendente; U2 vermelho deixa as duas pendentes. O segundo é a pendência `real` do intent `261113-trading-real-bot` (R-07), que o texto também traz.

### 2.2 `ci.yml`: passo `node --test "scripts/load/*.test.mjs"` (D-INF4)

**Onde:** no job `frontend` do `.github/workflows/ci.yml`, como **último passo**, logo depois de `- name: Jest with the 80% line floor` / `run: pnpm --dir apps/web exec jest --coverage` (hoje a linha 80 do arquivo, a última do job, antes de `  contract:`).

**Por que ali:** o passo só precisa do Node 24, que o job já instala com `actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0` e `node-version-file: .nvmrc` (`24`). Não entra ação nova, então não há SHA novo a fixar; as ações do job continuam fixadas como estão. Os testes não instalam pacotes nem usam rede (só `node:test` e módulos locais de `scripts/load/`).

```yaml
      - name: Gates of the load scenarios (scripts/load)
        run: node --test "scripts/load/*.test.mjs"
```

O job `frontend` fica assim, com o passo novo no fim (os `uses:` são os do arquivo atual, fixados por SHA):

```yaml
  frontend:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
      - uses: pnpm/action-setup@a7487c7e89a18df4991f7f222e4898a00d66ddda # v4.1.0
        with:
          package_json_file: apps/web/package.json
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 # v4.4.0
        with:
          node-version-file: .nvmrc
          cache: pnpm
          cache-dependency-path: apps/web/pnpm-lock.yaml
      - run: pnpm --dir apps/web install --frozen-lockfile
      - run: pnpm --dir apps/web lint
      - run: pnpm --dir apps/web format:check
      - run: pnpm --dir apps/web typecheck
      - name: Jest with the 80% line floor
        run: pnpm --dir apps/web exec jest --coverage
      - name: Gates of the load scenarios (scripts/load)
        run: node --test "scripts/load/*.test.mjs"
```

O padrão fica entre aspas para o próprio Node expandir; a forma com diretório (`node --test scripts/load`) falha no Node 24 (`build-instructions.md`, solução de problemas). Custo: menos de 1 s de execução (104 testes em cerca de 0,25 s nesta máquina). Se o responsável aplicar antes as ações em Node 24 (seção 4), o passo não muda: ele herda o `setup-node` que estiver no job.

Até o passo ser aplicado, a prova do TGT-LOAD-OFFLINE é a execução manual registrada (104 verdes).

## 3. Repositório de artefatos (Q4)

**Nenhum novo.** As imagens são montadas pelo `verify.sh` nos jobs `smoke` e `e2e`, com a etiqueta `${{ github.sha }}`, e descartadas com o runner. Os artefatos que ficam são os relatórios dos runs: `backend-coverage` e `smoke-report` (7 dias), `codeql-*`, `dependency-check` e `load-test-report` (30 dias). A publicação no ECR, com etiqueta imutável pelo SHA, faz parte do `deploy.yml`, que ainda não existe e é pendência do intent `261113-trading-real-bot` com o responsável (`team.md`, Deployment, "ainda não aplicada").

## 4. Pendências da CI com o responsável (do `team.md`, sem mudança)

Citadas do `team.md`, Way of Working, "Pendências da CI, com o responsável" [Q5 do `team.md`]. Este estágio não as altera nem as reprioriza; o texto pronto de cada uma continua onde já estava.

| Pendência | Onde está o texto pronto ou a decisão | Situação no `09de0a8` |
|---|---|---|
| Ações em Node 24 | `261113-trading-real-bot/construction/ci-pipeline/ci-config.md`, seção 3 (b) | não aplicada: os 3 workflows ainda usam os SHAs v4/v3 da seção 1.4 |
| CodeQL v4 | o mesmo, seção 3 (b) (`github/codeql-action` v4.39.3) | não aplicada: `security.yml` ainda usa a v3.29.5, que o próprio CHANGELOG da ação diz que será descontinuada em dezembro de 2026 |
| Proteção do download do Maven | o mesmo, seção 3 (a) (cache do wrapper e aquecimento com 3 tentativas) | não aplicada |
| `terraform fmt`, `validate` e `test` na CI | `team.md`, Testing Posture, "Testes da IaC" | rodam só à mão; nesta máquina o `terraform` não está instalado (TGT-IAC-1 `Unverified`) |
| Checkov na CI | `team.md`, idem | roda só à mão; não instalado aqui (TGT-IAC-2 `Unverified`) |
| `id-token: write` só no `deploy.yml` | `team.md`, Integridade da CI e Pendências | o `WorkflowsTest` hoje exige permissões só de leitura em todo workflow; ele precisa aceitar `id-token: write` só no job de implantação do `deploy.yml` antes que esse workflow entre |

## 5. Custo da CI

Run `45550427456` no `09de0a8` (`gh run view 45550427456 --json jobs`):

| Job | Duração |
|---|---|
| `backend` | **cerca de 38 min** (04:59 a 05:37 UTC; no run `45516978139` do `fc65b7f`, 37 min, com 37 min 07 s de Maven) |
| `smoke` | cerca de 23 min |
| `e2e` (tentativa 2) | **cerca de 22 min** (12:00 a 12:22 UTC) |
| `frontend`, `contract`, `txsigner`, `admin` | 1 a 2 min cada |

- Por push em `main`: cerca de 90 min de runner no `ci` (soma dos jobs) mais o `security`. Os jobs rodam em paralelo, então o tempo de relógio até o `e2e` começar é o do `backend` (cerca de 38 min), e o run inteiro leva cerca de 60 min.
- O `backend` é o caminho crítico: o `e2e` espera por ele. A U4 acrescentou o `RealBotHoldingsMeasurementIT` (300 leituras por semente) e o Surefire com 3 GB de heap (`402fa48`); a medida oficial com `-D` não roda na CI justamente por tempo e memória.
- Uma repetição do `e2e` (como a da tentativa 2, por causa do TGT-E2E-FLAKY) custa mais cerca de 22 min de runner.
- O repositório é privado desde 2026-11-21, e os minutos contam na cota do plano. Pelo `team.md` (Hospedagem), se a cota apertar, a frequência dos jobs é revista com dados de consumo. Este estágio não muda nenhum job (Q2); o número acima é o dado de consumo para essa revisão.
- Cada disparo manual do `load-test` `pnl-by-mode` custa mais 45 a 70 min.

## 6. Defeito TGT-E2E-FLAKY (e2e instável)

Origem: `build-and-test/test-results.md`, seção 9.2; matriz do B&T `Not Met`; aceito pelo fundador como falha conhecida em 2026-11-25 (seção 10), sem ser ignorado.

- **O que aconteceu:** na tentativa 1 do `e2e` do `09de0a8` (job `139004347381`), `e2e/real-swap/swap.spec.ts:224` esperou 7,5 min (limite de 420 s) por uma compra REAL `CONFIRMED` no validador Solana local. Em cascata caíram `real-ui`, `real-v1-exports`, `real-v2-exits` e `zz-paper-real-unico`, e também `u2-audit-trail/smoke.spec.ts:19` (`page.waitForURL`, 60 s), que não é REAL. 6 falharam, 26 não rodaram, 30 passaram. O `smoke` do mesmo commit e a tentativa 2 do `e2e` passaram 62/62.
- **Dono:** o responsável do projeto, com o coordenador.
- **Ações:**
  1. abrir um defeito "compra REAL do `real-swap` sem confirmação no validador local";
  2. guardar os artefatos da tentativa 1: logs do validador, do worker e do TxSigner e o `error-context.md` do Playwright;
  3. ver se a ordem ficou `SENT` sem confirmação (validador lento, `ChainWatcher`) ou se nem foi enviada;
  4. explicar a falha do `u2-audit-trail`;
  5. escrever um teste de regressão que reproduza a falha antes da correção (`team.md`, Defeitos).
- **Custo estimado:** 0,5 a 1 dia de investigação e cerca de 25 min de runner por repetição.
- **Na CI:** nenhuma mudança de workflow é proposta para ele. Não entra repetição automática (`retries`) nem `continue-on-error`: isso esconderia o defeito, e o `team.md` diz que teste instável "nunca é ignorado em silêncio". Enquanto ele estiver aberto, um `e2e` vermelho só pode ser repetido depois de conferir que a falha é a mesma assinatura (compra REAL sem confirmação), e cada repetição fica registrada. Pelo `project.md` (Mandated), ponto de controle e implantação só usam um commit com a CI verde.

## 7. Estratégia de branches (Q1)

- Desenvolvimento baseado em tronco (`team.md`, Way of Working): tronco `main`, commits diretos pedidos pelo responsável, sem branch nem worktree por Bolt; squash-merge só quando o responsável pede uma branch; sem branch de release; tags anotadas `vAAAA.MM.DD-N` em `main`.
- `ci` e `security` rodam em todo PR e push em `main`; o `e2e` roda só no push em `main`, depois dos 6 jobs verdes; o `load-test` é só manual.
- Num push direto, a CI roda depois do commit; uma `main` vermelha é corrigida no commit seguinte, antes de qualquer outra mudança (`project.md`, Mandated). `main` não tem proteção de branch (`team.md`), então o bloqueio é por disciplina, como já registrado no precedente.

## Sources

- `construction/ci-pipeline/ci-pipeline-questions.md` (Q1 a Q5 e o resumo confirmado)
- `construction/build-and-test/test-results.md` (seções 2, 7, 9 e 10: runs `45550427456`, `45550427465`, `45516978139`; TGT-E2E-FLAKY)
- `construction/build-and-test/build-and-test-summary.md` (matriz: TGT-*, U2-NFR1.9, TGT-LOAD-OFFLINE, TGT-IAC-1, TGT-IAC-2)
- `construction/build-and-test/build-instructions.md` (comandos e solução de problemas)
- `construction/u3-pnl-views/code-generation/code-summary.md` (textos prontos do `load-test.yml` e do passo D-INF4)
- `construction/u2-real-bot-pnl/code-generation/code-summary.md` (texto anterior do `load-test.yml`, substituído)
- `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`, `.github/dependabot.yml`; `apps/api/src/test/java/com/memetrade/infra/WorkflowsTest.java`; `scripts/load-test.sh`, `scripts/load/pnl-by-mode.mjs`, `scripts/load/load-test.mjs`; `.nvmrc`
- `261113-trading-real-bot/construction/ci-pipeline/ci-config.md` (precedente; textos prontos de Node 24, CodeQL v4 e Maven)
- `team.md` (Way of Working, Testing Posture, Deployment) e `project.md` (Mandated)
- `gh run view 45550427456 --json jobs` e `node --test "scripts/load/*.test.mjs"`, executados em 2026-11-25
