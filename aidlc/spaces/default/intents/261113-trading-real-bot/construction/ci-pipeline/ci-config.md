# Configuração da CI — intent `261113-trading-real-bot`

Data: 2026-11-20. Commit de referência: `3497b2bc49052f01adb93de7094a05880532222b` (`main`). Runs de referência: `ci` 44765239890 (tentativa 2, 7 jobs `success`) e `security` 44765240176 (4 jobs `success`), os dois no mesmo `headSha`.

Decisões deste estágio (`ci-pipeline-questions.md`, todas opção A, resumo confirmado):
- a CI continua no GitHub Actions, estendendo os workflows atuais [Q1];
- a estratégia de branches é a do `team.md`, baseada em tronco [Q2];
- todos os portões ficam; entram a proteção do download do Maven e as ações em Node 24; o cenário de carga do REAL fica para a Performance Validation [Q3];
- nenhum repositório de artefatos por enquanto [Q4];
- a EX-014 e o `ignoreGhsas` saem [Q5];
- as mudanças de workflow chegam como texto pronto para o responsável aplicar; os demais arquivos são alterados aqui [Q6].

Nenhum arquivo em `.github/workflows/` nem o `scripts/verify.sh` foi editado neste estágio. Os únicos arquivos do repositório alterados aqui são `security/exceptions.yml`, `apps/web/package.json` e `apps/admin/package.json` (seção 6).

## 1. Pipeline atual

### 1.1 Workflows

| Workflow | Arquivo | Gatilhos | `permissions` | Concorrência |
|---|---|---|---|---|
| `ci` | `.github/workflows/ci.yml` | `pull_request` (qualquer branch), `push` em `main`, `workflow_dispatch` | `contents: read` no workflow; o job `txsigner` repete `contents: read` | `ci-${{ github.ref }}`, cancela o run anterior do mesmo ref |
| `security` | `.github/workflows/security.yml` | `pull_request`, `push` em `main` | `contents: read`; o job `sast` acrescenta `actions: read` | nenhuma |
| `load-test` | `.github/workflows/load-test.yml` | só `workflow_dispatch`, com a entrada `scenario` (u1, u4, u3, growth, u7, u7-jobs, u6, u5, legal, reacceptance, data-requests) | `contents: read` | nenhuma |
| (atualizações) | `.github/dependabot.yml` | semanal: maven (`/apps/api`), npm (`/apps/web`, `/apps/admin`, espera de 5 dias), docker, docker-compose, github-actions | — | — |

Nenhum workflow usa segredo, exceto `NVD_API_KEY` no job `dependencies` (chave de leitura da NVD, não de produção). Nenhum segredo de produção existe no repositório nem nos workflows.

### 1.2 Jobs do `ci`

| Job | Depende de | Quando roda | O que impõe |
|---|---|---|---|
| `backend` | — | todo PR e push | `docker compose config` (base e base + overlay de teste); `mvnw verify` da API: Spotless, testes de unidade (Surefire) e integração (Failsafe, Testcontainers com PostgreSQL 17), JaCoCo `check` ≥ 80% de linhas, enforcer (SDK Solana só em teste), ArchUnit; `promtool check rules` nos 6 arquivos de `infra/observability/`; guarda o artefato `backend-coverage` (7 dias) |
| `frontend` | — | todo PR e push | `apps/web`: `install --frozen-lockfile`, `lint`, `format:check`, `typecheck`, `jest --coverage` com `coverageThreshold.global.lines = 80` |
| `admin` | — | todo PR e push | `apps/admin`: o mesmo do `frontend` e mais `check:api` |
| `contract` | — | todo PR e push | `GuestOpenApiContractTest` e `AdminOpenApiContractTest` (OpenAPI gerado igual a `openapi-guest.yaml`/`openapi-admin.yaml`); `pnpm --dir apps/web check:api` |
| `smoke` | — | todo PR e push | `bash scripts/verify.sh` (imagens com etiqueta `${{ github.sha }}`, segredos efêmeros, validador Solana local, todos os specs Playwright); guarda `smoke-report` (7 dias) |
| `txsigner` | — | todo PR e push | `mvnw verify` do TxSigner: testes, JaCoCo ≥ 80%, Spotless, enforcer |
| `e2e` | os 6 acima | só `push` em `main` | `bash scripts/verify.sh` de novo, depois de todos os outros jobs verdes |

### 1.3 Jobs do `security`

| Job | O que impõe |
|---|---|
| `secrets` | Gitleaks na versão de `.gitleaks-version`, com checksum conferido; `gitleaks git` (histórico completo, `fetch-depth: 0`) e `gitleaks dir` (árvore) com `.gitleaks.toml` |
| `sast` (matriz `java-kotlin`, `javascript-typescript`) | CodeQL; Java com build manual (`mvnw package` sem testes); bloqueia se houver resultado de nível `error` (Crítico/Alto) e só avisa a quantidade de `warning` (Médio); resultado como artefato (30 dias), sem upload para code scanning |
| `dependencies` | `SecurityExceptionsTest` (exceção vencida bloqueia de novo); exceções `dependency-check` viram supressões com `until`; OWASP Dependency-Check 12.1.0 com `-DfailBuildOnCVSS=7`; `pnpm audit --audit-level high` em `apps/web` e `apps/admin` |

### 1.4 Ações fixadas e caches

| Ação | SHA atual | Versão | Runtime |
|---|---|---|---|
| `actions/checkout` | `11bd71901bbe5b1630ceea73d27597364c9af683` | v4.2.2 | node20 |
| `actions/setup-java` | `c5195efecf7bdfc987ee8bae7a71cb8b11521c00` | v4.7.1 | node20 |
| `actions/cache` | `5a3ec84eff668545956fd18022155c47e93e2684` | v4.2.3 | node20 |
| `actions/upload-artifact` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | v4.6.2 | node20 |
| `actions/setup-node` | `49933ea5288caeca8642d1e84afbd3f7d6820020` | v4.4.0 | node20 |
| `pnpm/action-setup` | `a7487c7e89a18df4991f7f222e4898a00d66ddda` | v4.1.0 | node20 |
| `github/codeql-action/init` e `/analyze` | `51f77329afa6477de8c49fc9c7046c15b9a4e79d` | v3.29.5 | node20 |
| imagem `prom/prometheus` (promtool) | `sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996` | digest | — |

Todos os SHAs atuais foram conferidos com `gh api repos/<owner>/<repo>/commits/<tag>`: cada um é exatamente o commit da tag do comentário.

Caches:
- `setup-java` com `cache: maven` guarda `~/.m2/repository`. Na v4.7.1, ele **não** guarda a distribuição do wrapper (`~/.m2/wrapper`), por isso todo job baixa o `apache-maven-3.9.11-bin.zip` de novo;
- `setup-node` com `cache: pnpm` guarda o store do pnpm (`frontend`, `admin`, `contract`, `smoke`, `e2e`);
- `actions/cache` guarda a base da NVD (`nvd-<run_id>`, restaurada por `nvd-`).

### 1.5 Achados do inventário

1. **`main` sem proteção.** `gh api repos/rafaelcruz-net/eureka-trade-ai/branches/main/protection` responde `Branch not protected`, e não há ruleset. O comentário do `ci.yml` ("A red job blocks the merge") vale hoje por disciplina, não por regra do GitHub. Na estratégia do `team.md`, os commits entram direto em `main`, então a CI roda depois do push. Ver a seção 5 e a proposta opcional (d).
2. **Visibilidade do repositório.** A API devolve `"visibility": "public"`, e o `team.md` diz "repositório privado". O responsável deve conferir se isso é intencional. Num repositório público, os minutos dos runners padrão do GitHub Actions não contam na cota, o que muda o item "a definir" da cota no `team.md`. Nenhum segredo está no repositório (Gitleaks verde).
3. **Aviso `Resource not accessible by integration`** nos jobs `sast`. Ele vem de uma chamada de API do CodeQL sem `security-events: write`. É esperado com `upload: never` e não muda o resultado. Nenhuma permissão nova é proposta.
4. **O Maven também é baixado dentro do `docker build`** (`apps/api/Dockerfile` e `apps/txsigner/Dockerfile`, `RUN sh ./mvnw ... dependency:go-offline`), chamado pelo `verify.sh` nos jobs `smoke` e `e2e`. O cache e a nova tentativa da seção 3 (a) protegem o `mvnw` do host, mas não esse download dentro do contêiner. É um risco que sobra; a correção (um cache do BuildKit ou um `MVNW_REPOURL` para um espelho) muda os Dockerfiles e fica para quando o problema aparecer nesses jobs.

## 2. Mapeamento: comando do Build and Test → job da CI

| Meta do B&T | Comando | Workflow / job |
|---|---|---|
| BT-UNIT-IT-API, BT-COV-API, BT-SPOTLESS (API), BT-ENFORCER, BT-ARCHUNIT (API) | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | `ci` / `backend` |
| BT-COMPOSE | `docker compose -f docker-compose.yml config --quiet` e com `-f infra/docker-compose.test.yml` | `ci` / `backend` |
| BT-PROMTOOL | `promtool check rules` em `real-alerts`, `marketdata-alerts`, `real-risk-alerts`, `real-execution-alerts`, `real-ops-alerts`, `real-exits-alerts` | `ci` / `backend` |
| BT-UNIT-IT-TXSIGNER, BT-COV-TXSIGNER, BT-SPOTLESS (TxSigner), BT-ARCHUNIT (TxSigner) | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | `ci` / `txsigner` |
| BT-JEST-WEB, BT-COV-WEB, BT-STATIC-WEB-ADMIN (web) | `pnpm --dir apps/web lint`, `format:check`, `typecheck`, `exec jest --coverage` | `ci` / `frontend` |
| BT-JEST-ADMIN, BT-COV-ADMIN, BT-STATIC-WEB-ADMIN (admin) | `pnpm --dir apps/admin lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` | `ci` / `admin` |
| BT-CONTRACT | `mvnw ... -Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest test`; `pnpm --dir apps/web check:api`; `pnpm --dir apps/admin check:api` | `ci` / `contract` e `admin` |
| BT-E2E | `bash scripts/verify.sh` (42 specs) | `ci` / `smoke` (todo PR e push) e `e2e` (push em `main`) |
| BT-SEC-SECRETS | `gitleaks git` e `gitleaks dir` | `security` / `secrets` |
| BT-SEC-SAST | CodeQL `java-kotlin` e `javascript-typescript`, bloqueio por `level == "error"` | `security` / `sast` |
| BT-SEC-DEPS-JAVA | `SecurityExceptionsTest`; `dependency-check-maven:12.1.0:check -DfailBuildOnCVSS=7` | `security` / `dependencies` |
| BT-SEC-DEPS-WEB, BT-SEC-DEPS-ADMIN | `pnpm --dir apps/web audit --audit-level high`; `pnpm --dir apps/admin audit --audit-level high` | `security` / `dependencies` |
| BT-TC-VOLUME, BT-TC-GREEN | todos os acima | `ci` inteiro |
| Comandos por Unit dos `unit-test-instructions.md` (`-Dtest='com.memetrade.<módulo>.**'`) | subconjuntos do `verify` da API e do TxSigner | `ci` / `backend` e `txsigner` (rodam inteiros, uma vez) |
| BT-TC-PERF e as metas de carga do REAL | cenário `real` no `load-test.yml` (ainda não existe) | **não mapeado**: dono `performance-validation` (seção 3 (c)) |

Toda meta `Met` da matriz do B&T que depende de um comando tem um job da CI que roda esse comando em todo PR e push em `main`. O único comando sem job é o teste de carga do REAL, que é uma meta `Unverified` de `performance-validation`.

## 3. Mudanças propostas nos workflows (texto pronto para o responsável)

Pelo `team.md` ("Só o responsável do projeto altera o `ci.yml`") e pela Q6, nada abaixo foi aplicado. Todos os SHAs novos foram resolvidos nesta execução com `gh api repos/<owner>/<repo>/git/ref/tags/<tag>` (seguindo a tag anotada até o commit, quando existe) e conferidos de novo com `gh api repos/<owner>/<repo>/commits/<tag>`. Os dois caminhos deram o mesmo SHA em todos os casos. O `runs.using` de cada `action.yml` foi lido no próprio SHA.

**Ordem recomendada:** aplicar (b) e (a) no mesmo commit. Com o `setup-java` v6.3.0 de (b), o `cache: maven` já guarda `~/.m2/wrapper/dists` numa entrada própria, com chave no hash de `**/.mvn/wrapper/maven-wrapper.properties` (README da v6.3.0, seção "Wrapper caches"). Assim, o passo `actions/cache` explícito de (a.1) fica dispensável; mantenha-o só se (b) for adiado. A nova tentativa de (a.2) vale nos dois casos, porque o cache não ajuda no primeiro run nem depois de uma remoção do cache. A troca de versão do `setup-java` muda a chave do cache, então o primeiro run depois de (b) começa frio.

### (a) Download do Maven resistente a falha de rede

**Falha observada:** `wget: Failed to fetch https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip`. Aconteceu na tentativa 1 do job `contract` do `ci` 44765239890 e no `security` do run anterior. O `mvnw` (`distributionType=only-script`) baixa a distribuição para `${MAVEN_USER_HOME:-~/.m2}/wrapper/dists/apache-maven-3.9.11/<hash da URL>` e confere o `distributionSha256Sum`.

**Onde ficam as propriedades do wrapper:**
- `apps/api/.mvn/wrapper/maven-wrapper.properties`;
- `apps/txsigner/.mvn/wrapper/maven-wrapper.properties`.

Os dois arquivos têm a mesma `distributionUrl` e o mesmo `distributionSha256Sum` (`0d7125e8…eadb`). Por isso os dois wrappers usam o mesmo diretório em `~/.m2/wrapper/dists`, e um único aquecimento serve aos dois.

**Jobs que chamam o `mvnw` no host:**

| Workflow / job | Primeira chamada do `mvnw` |
|---|---|
| `ci` / `backend` | passo `Verify backend` |
| `ci` / `contract` | passo `Generated OpenAPI equals …` |
| `ci` / `smoke` | `scripts/verify.sh` (linhas 77 a 101: `mvnw … exec:java`) |
| `ci` / `txsigner` | passo `TxSigner verify …` (`apps/txsigner/mvnw`) |
| `ci` / `e2e` | `scripts/verify.sh` |
| `security` / `sast` (só `java-kotlin`) | `mvnw … package` |
| `security` / `dependencies` | passo `Expired security exceptions block again` |
| `load-test` / `load` | `scripts/load-test.sh` (linha 30) |

#### (a.1) Cache da distribuição do wrapper (dispensável depois de (b))

Inserir **logo depois do passo `actions/setup-java`** de cada job da tabela acima. No `sast`, acrescentar `if: matrix.language == 'java-kotlin'`. A ação já vai fixada na versão em Node 24 de (b):

```yaml
      - name: Cache the Maven wrapper distribution
        uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
        with:
          path: ~/.m2/wrapper
          key: maven-wrapper-${{ runner.os }}-${{ hashFiles('apps/api/.mvn/wrapper/maven-wrapper.properties', 'apps/txsigner/.mvn/wrapper/maven-wrapper.properties') }}
```

A chave muda só quando uma das propriedades do wrapper muda (outra versão do Maven), e todos os jobs dividem a mesma entrada.

#### (a.2) Aquecimento do wrapper com nova tentativa limitada

Inserir **depois do passo de cache (ou do `setup-java`, se (a.1) não for usado) e antes da primeira chamada do `mvnw`** em cada job da tabela. No `txsigner`, trocar `apps/api/mvnw` por `apps/txsigner/mvnw` (mesmo download, mas deixa o passo junto do projeto que ele serve). No `sast`, acrescentar `if: matrix.language == 'java-kotlin'`.

```yaml
      # The Maven distribution download (repo.maven.apache.org) failed by network in ci 44765239890 attempt 1.
      # Warm the wrapper up with 3 bounded attempts; later mvnw calls find the distribution in ~/.m2/wrapper.
      - name: Maven wrapper warm-up (3 attempts)
        run: |
          for attempt in 1 2 3; do
            if sh apps/api/mvnw -B -ntp --version; then
              exit 0
            fi
            if [ "${attempt}" -lt 3 ]; then
              echo "::warning::Maven wrapper download failed (attempt ${attempt}/3); retrying in $((attempt * 20)) s"
              sleep $((attempt * 20))
            fi
          done
          echo "::error::Maven wrapper download failed after 3 attempts"
          exit 1
```

Comportamento:
- no máximo 3 tentativas, com espera de 20 s e depois 40 s entre elas, o que acrescenta no máximo 1 min a um job que falharia de qualquer forma;
- o `mvnw --version` só baixa e confere a distribuição, sem tocar no projeto; um download parcial fica no diretório temporário do `mvnw`, que o apaga ao sair, então a tentativa seguinte recomeça limpa;
- a falha continua vermelha depois da 3ª tentativa: não é um "continue-on-error", e nenhum portão fica mais fraco.

Exemplo de posição no job `contract`, com (a) e (b) aplicados (só os passos até a primeira chamada do `mvnw`):

```yaml
  contract:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880 # v6.3.0
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Maven wrapper warm-up (3 attempts)
        run: |
          for attempt in 1 2 3; do
            if sh apps/api/mvnw -B -ntp --version; then
              exit 0
            fi
            if [ "${attempt}" -lt 3 ]; then
              echo "::warning::Maven wrapper download failed (attempt ${attempt}/3); retrying in $((attempt * 20)) s"
              sleep $((attempt * 20))
            fi
          done
          echo "::error::Maven wrapper download failed after 3 attempts"
          exit 1
      - name: Generated OpenAPI equals apps/api/openapi/openapi-guest.yaml and openapi-admin.yaml
        run: sh apps/api/mvnw -B -ntp -f apps/api/pom.xml -Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

No `smoke` e no `e2e`, o aquecimento entra antes do passo do `verify.sh`. O próprio `verify.sh` não muda.

### (b) Ações em Node 24 (fim do Node 20)

As anotações dos runs 44765239890 e 44765240176 dizem "Node.js 20 is deprecated … being forced to run on Node.js 24" para `actions/checkout`, `actions/setup-java`, `actions/setup-node`, `pnpm/action-setup`, `actions/upload-artifact` e `github/codeql-action/init`/`analyze`. O `actions/cache` (v4.2.3) também é `node20`. O CodeQL entra na lista, embora o pedido original não o citasse, porque aparece nas anotações do `sast` e porque o CHANGELOG da própria ação diz que a v3 "will be deprecated in December 2026".

Versão escolhida: a última versão estável do major atual, publicada há pelo menos 5 dias (a mesma espera do `dependabot.yml` para pacotes do frontend). Por isso o CodeQL fica na v4.39.3 (2026-11-04), e não na v4.39.4, publicada hoje.

| Ação | SHA antigo → SHA novo | Versão | Publicada | `runs.using` novo |
|---|---|---|---|---|
| `actions/checkout` | `11bd71901bbe5b1630ceea73d27597364c9af683` → `3d3c42e5aac5ba805825da76410c181273ba90b1` | v4.2.2 → v7.0.1 | 2026-07-20 | node24 |
| `actions/setup-java` | `c5195efecf7bdfc987ee8bae7a71cb8b11521c00` → `d1374f8ac74474200d16ef8ecee22c7ae7a51880` | v4.7.1 → v6.3.0 | 2026-11-14 | node24 |
| `actions/cache` | `5a3ec84eff668545956fd18022155c47e93e2684` → `55cc8345863c7cc4c66a329aec7e433d2d1c52a9` | v4.2.3 → v6.1.0 | 2026-06-26 | node24 |
| `actions/upload-artifact` | `ea165f8d65b6e75b540449e92b4886f43607fa02` → `cf430e030ddbb5b0abf93d22962f4752f3646cd9` | v4.6.2 → v7.0.2 | 2026-10-07 | node24 |
| `actions/setup-node` | `49933ea5288caeca8642d1e84afbd3f7d6820020` → `949feb2413d6458794dcd2491c4babbbce0c15c1` | v4.4.0 → v7.1.0 | 2026-10-08 | node24 |
| `pnpm/action-setup` | `a7487c7e89a18df4991f7f222e4898a00d66ddda` → `73e46992e05b19abd31f2f3c20e0e7370d762115` | v4.1.0 → v6.1.1 | 2026-11-03 | node24 |
| `github/codeql-action/init` e `/analyze` | `51f77329afa6477de8c49fc9c7046c15b9a4e79d` → `33d7b85da1a04171e211befe2c739303b9a4c066` | v3.29.5 → v4.39.3 | 2026-11-04 | node24 (`init/action.yml` e `analyze/action.yml`) |

**Mudanças de entrada e de comportamento** (das notas de release, `gh api repos/<owner>/<repo>/releases/tags/<tag>`, e do CHANGELOG do CodeQL no SHA novo):

| Ação | O que muda | Efeito aqui |
|---|---|---|
| `actions/checkout` | v5: Node 24, runner ≥ v2.327.1. v6: as credenciais persistidas vão para um arquivo separado. v7: bloqueia o checkout de PR de fork em `pull_request_target`/`workflow_run`; módulo ESM. | Nenhum. Os runners são do GitHub; nenhum workflow usa `pull_request_target` nem `workflow_run`; nenhum passo faz `git push`. O `fetch-depth: 0` do `secrets` continua igual. |
| `actions/setup-java` | v5: Node 24. v6: cache da distribuição do wrapper separado do cache de dependências; cache do JDK ligado quando `cache` está definido; `.mvn/extensions.xml` na chave; `jdkFile` vira `jdk-file` (alias mantido). v6.3.0: o problem matcher do Java passa a ser opcional (`problem-matcher: true`). | O cache do wrapper resolve (a.1) sozinho. A chave do cache muda, então o primeiro run fica frio. Os erros do `javac` deixam de virar anotação no diff do PR, mas continuam no log e continuam falhando o build. Nenhuma entrada usada (`distribution`, `java-version`, `cache`) mudou. |
| `actions/cache` | v5: Node 24, runner ≥ v2.327.1. v6: ESM e pacotes atualizados. | Nenhum. As entradas `path`, `key` e `restore-keys` são as mesmas. A v5.1.0 saiu depois da v6.1.0 como correção da v5; a v6 é o major atual. |
| `actions/upload-artifact` | v5: preparação para Node 24. v6: Node 24 por padrão. v7: ESM e a entrada nova `archive` (padrão `true`). | Nenhum. As entradas usadas (`name`, `path`, `retention-days`) e o zip padrão não mudam; o `gh run download` continua igual. |
| `actions/setup-node` | v5: cache automático quando o `package.json` tem `packageManager` (desligável com `package-manager-cache: false`). v6: o cache automático fica só para npm. v7: removido o `NODE_AUTH_TOKEN` fictício; ESM. v7.1.0: nova tentativa ao baixar o manifesto do Node. | Nenhum. Os jobs com `cache: pnpm` continuam iguais. O `dependencies` e o `load` não pedem cache, e com a v6+ o cache automático não vale para pnpm. Nenhum passo publica no npm. |
| `pnpm/action-setup` | v5: Node 24. v6: suporte ao pnpm 11; entrada `standalone`. | Nenhum. `package_json_file` continua existindo, e a versão vem do `packageManager: pnpm@10.18.3` de cada app. |
| `github/codeql-action` | v4: Node 24 (CHANGELOG, "[v4+ only] The CodeQL Action now runs on Node.js v24"). A v3 será descontinuada em dezembro de 2026. Desde a v4.35, a limpeza de cache TRAP por variável de ambiente foi removida. | Nenhum esperado. As entradas `languages` e `build-mode` (`init`), `upload: never` e `output` (`analyze`) existem no `action.yml` do SHA novo. A v4.0.0 não tem página de release; a leitura foi feita no CHANGELOG. O primeiro run do `security` depois da troca é a prova. |

**Como aplicar (b):** substituir cada `uses:` antigo pelo novo, com o comentário da versão. São linhas iguais nos três workflows, por exemplo:

```yaml
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880 # v6.3.0
      - uses: pnpm/action-setup@73e46992e05b19abd31f2f3c20e0e7370d762115 # v6.1.1
      - uses: actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7.1.0
        uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2
        uses: actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0
      - uses: github/codeql-action/init@33d7b85da1a04171e211befe2c739303b9a4c066 # v4.39.3
      - uses: github/codeql-action/analyze@33d7b85da1a04171e211befe2c739303b9a4c066 # v4.39.3
```

Substituição mecânica, depois revisada com `git diff` (Git Bash, da raiz):

```bash
sed -i \
  -e 's#actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 \# v4.2.2#actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 \# v7.0.1#' \
  -e 's#actions/setup-java@c5195efecf7bdfc987ee8bae7a71cb8b11521c00 \# v4.7.1#actions/setup-java@d1374f8ac74474200d16ef8ecee22c7ae7a51880 \# v6.3.0#' \
  -e 's#actions/cache@5a3ec84eff668545956fd18022155c47e93e2684 \# v4.2.3#actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 \# v6.1.0#' \
  -e 's#actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 \# v4.6.2#actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 \# v7.0.2#' \
  -e 's#actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020 \# v4.4.0#actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 \# v7.1.0#' \
  -e 's#pnpm/action-setup@a7487c7e89a18df4991f7f222e4898a00d66ddda \# v4.1.0#pnpm/action-setup@73e46992e05b19abd31f2f3c20e0e7370d762115 \# v6.1.1#' \
  -e 's#github/codeql-action/\(init\|analyze\)@51f77329afa6477de8c49fc9c7046c15b9a4e79d \# v3.29.5#github/codeql-action/\1@33d7b85da1a04171e211befe2c739303b9a4c066 \# v4.39.3#' \
  .github/workflows/ci.yml .github/workflows/security.yml .github/workflows/load-test.yml
grep -n "uses:" .github/workflows/*.yml
```

O comando foi testado numa cópia temporária dos três workflows (fora do repositório): trocou todos os 40 `uses:` de ações, e nenhum SHA antigo sobrou. O digest da imagem `prom/prometheus` não muda.

Depois de aplicar, a prova é um run do `ci` e um do `security` sem a anotação "Node.js 20 is deprecated" e com todos os jobs verdes. O Dependabot (`github-actions`, semanal) mantém os SHAs atualizados daqui em diante.

### (c) Cenário de carga do REAL: não entra aqui

O cenário `real` do `load-test.yml` **não** é acrescentado neste estágio. Pela matriz do B&T, BT-TC-PERF e as metas de desempenho do REAL (U1:NFR3.1 a NFR3.4, NFR3.6, NFR3.8, NFR3.9; U2:NFR3.1 a NFR3.4; U4-NFR3.4; U5-NFR3.1 a NFR3.4) são `Unverified` com dono `performance-validation`. O `team.md` diz "nenhum portão de desempenho ou carga até que os requisitos não funcionais definam metas numéricas". O cenário e a decisão de torná-lo portão ficam para a Performance Validation, que parte do `performance-test-instructions.md` do B&T.

### (d) Opcional, decisão do responsável: exigir os checks em PR

Não faz parte da Q3 e só é registrado aqui por causa do achado 1.5.1. Um ruleset em `main` com "Require status checks to pass" (os jobs do `ci` e do `security`) e o administrador do repositório como *bypass actor* faria os PRs (por exemplo, os do Dependabot) só entrarem com a CI verde, sem mudar o push direto do responsável previsto no `team.md`. Não é proposto como texto pronto porque muda a configuração do repositório, não um workflow, e porque o `team.md` (Q2) mantém o push direto.

## 4. Repositório de artefatos (Q4)

**Nenhum por enquanto.**
- As imagens são montadas na própria CI pelo `verify.sh` (jobs `smoke` e `e2e`), com a etiqueta `${{ github.sha }}` (`IMAGE_TAG`, `BUILD_SHA`), e são descartadas com o runner. É o que o `team.md` pede ("artefatos são marcados com o SHA do commit").
- Ainda não existe conta de nuvem (`team.md` Deployment: C-10, C-12). A conta AWS nova entra no Environment Provisioning. Publicar no ECR antes disso não tem destino, e publicar no GHCR criaria uma origem de imagem que nenhum ambiente consome.
- Os artefatos que ficam são só os relatórios dos runs: `backend-coverage` e `smoke-report` (7 dias), `codeql-*`, `dependency-check` e `load-test-report` (30 dias).
- A publicação no ECR, com etiqueta imutável pelo SHA e política de retenção, fica para o Environment Provisioning e a Deployment Pipeline.

## 5. Estratégia de branches (Q2)

- **Desenvolvimento baseado em tronco** (`team.md` Way of Working): tronco `main`; as mudanças entram direto em `main`, em commits pedidos pelo responsável; não há branch nem worktree por Bolt; squash-merge só quando o responsável pede uma branch; nenhuma branch de release de longa duração; releases por tag em `main` quando houver ambiente.
- **CI:**
  - `ci` e `security` rodam em todo `pull_request` e em todo `push` em `main`;
  - o `e2e` (segunda execução completa do `verify.sh`) roda só no push em `main`, depois dos 6 jobs verdes;
  - os 42 specs também rodam em todo PR e push pelo job `smoke`;
  - o `load-test` é só manual.
- **"Antes do merge" no tronco:** num push direto em `main` não há merge; a CI roda logo depois do push, e a regra do `team.md` ("nenhum merge acontece com a CI vermelha") vale como "um `main` vermelho é corrigido antes de qualquer outro commit". Nos PRs (Dependabot ou branch pedida), os checks aparecem antes do merge. Como `main` não tem proteção (1.5.1), o bloqueio é por disciplina; a opção (d) é o caminho se o responsável quiser o bloqueio pelo GitHub nos PRs.

## 6. Q5 — remoção da EX-014: resultado

**Alterado neste estágio:**
- `security/exceptions.yml`: removidas a entrada `EX-014` (`tool: pnpm-audit`, `finding: GHSA-fc5q-39xc-vjpf`) e as 4 linhas do comentário de cabeçalho sobre ela. Ficam EX-001 a EX-013, todas `dependency-check`, válidas até 2027-02-09.
- `apps/web/package.json` e `apps/admin/package.json`: removido o bloco `pnpm.auditConfig` inteiro, que só tinha `ignoreGhsas: ["GHSA-fc5q-39xc-vjpf"]`. Não havia outro GHSA ignorado. O `pnpm.overrides` (`postcss: 8.5.28`) ficou. O JSON continua válido; os lockfiles não mudam, porque o `auditConfig` não entra no `pnpm-lock.yaml`.

**Conferência:**

| Comando | Resultado |
|---|---|
| `corepack pnpm@10.18.3 --dir apps/web audit --audit-level high` | código de saída **0**; 6 vulnerabilidades, todas Moderadas: glob GHSA-3g3m-2fjr-6pw4, minimatch GHSA-g3hv-75wg-m249 e next GHSA-7qx7-33qx-rqx2, GHSA-f3mh-rxg3-f5g2, GHSA-pc58-hwjq-p385. O `picomatch` (GHSA-fc5q-39xc-vjpf) não aparece mais |
| `corepack pnpm@10.18.3 --dir apps/admin audit --audit-level high` | código de saída **0**; os mesmos 6 Moderados |
| `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml test -Dtest=SecurityExceptionsTest -Dsurefire.failIfNoSpecifiedTests=false` | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS` |
| `prettier --check package.json` em `apps/web` e `apps/admin` | "All matched files use Prettier code style!" (o `format:check` da CI continua verde para esses arquivos) |

A conferência no workflow `security` (Q5) acontece no próximo push com essas mudanças. Os Moderados continuam só como aviso, como pede o `team.md`, e são os mesmos que o B&T registrou. Se o GHSA-fc5q-39xc-vjpf voltar a ser publicado, o `pnpm audit` volta a falhar no `dependencies`, e a decisão volta ao responsável (nova exceção com validade de no máximo 90 dias ou atualização do pacote).

## Sources

- `construction/ci-pipeline/ci-pipeline-questions.md` (Q1 a Q6 e o resumo confirmado)
- `construction/build-and-test/build-and-test-summary.md` (matriz, metas BT-*, pendência 7 do Maven)
- `construction/build-and-test/test-results.md` (runs 44765239890 e 44765240176, falha da tentativa 1 do `contract`)
- `construction/u1-bot-wallet/code-generation/code-summary.md` a `construction/u7-exits-and-fiscal/code-generation/code-summary.md` (comandos por Unit, textos de CI já aplicados pelo responsável)
- `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`, `.github/dependabot.yml`
- `security/exceptions.yml`, `apps/web/package.json`, `apps/admin/package.json`, `apps/*/.mvn/wrapper/maven-wrapper.properties`, `apps/*/Dockerfile`
- `team.md` (Way of Working, Testing Posture, Deployment) e `project.md` (Mandated: SHA nas ações, lockfiles, segredos)
- `gh api` (tags, commits, `action.yml` e releases das ações; proteção de `main`; anotações dos runs), executado em 2026-11-20
