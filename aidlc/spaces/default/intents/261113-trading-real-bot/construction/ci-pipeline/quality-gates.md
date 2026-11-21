# Portões de qualidade da CI — intent `261113-trading-real-bot`

Data: 2026-11-20. Evidência: o último run verde do commit `3497b2bc49052f01adb93de7094a05880532222b`:
- `ci` **44765239890**, tentativa 2, conclusão `success`, 7 jobs `success`: `backend`, `frontend`, `admin`, `contract`, `smoke`, `txsigner`, `e2e`;
- `security` **44765240176**, tentativa 1, conclusão `success`, 4 jobs `success`: `secrets`, `sast (java-kotlin)`, `sast (javascript-typescript)`, `dependencies`.

Os dois foram conferidos de novo nesta execução com `gh run view <id> --json conclusion,headSha,jobs`.

Pela Q3 (opção A), todos os portões abaixo continuam como estão. Nenhum limiar foi alterado, e as mudanças propostas em `ci-config.md` (cache e nova tentativa do Maven, ações em Node 24) não enfraquecem nenhum deles. Um job vermelho deixa o run vermelho. Num PR, isso aparece antes do merge. Num push direto em `main` (estratégia do `team.md`), o `main` vermelho é corrigido antes de qualquer outro commit (ver `ci-config.md`, seção 5).

## 1. Portões que bloqueiam

| # | Portão | Workflow / job | Comando | Limiar (falha quando) | Regra de origem | Evidência do último run verde |
|---|---|---|---|---|---|---|
| G1 | Lint, formatação e tipos do frontend | `ci` / `frontend` e `admin` | `pnpm --dir apps/<app> lint`, `format:check`, `typecheck` | qualquer erro do ESLint, arquivo fora do Prettier ou erro do `tsc --noEmit` (modo `strict`) | `team.md` Code Style ("Lint, formatação e verificação de tipos rodam na CI antes do merge; falha bloqueia o merge"; "TypeScript: modo `strict`") | 44765239890 `frontend` e `admin`: verdes (BT-STATIC-WEB-ADMIN `Met`) |
| G2 | Formatação do backend | `ci` / `backend` e `txsigner` | `mvnw verify` (fase `spotless:check`) | qualquer arquivo Java fora do formatador | `team.md` Code Style (Spotless) | 44765239890: 1.587 arquivos limpos na API e 128 no TxSigner (BT-SPOTLESS `Met`) |
| G3 | Testes de unidade e integração da API, com piso de cobertura | `ci` / `backend` | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | qualquer teste com falha ou erro; JaCoCo `check` com `LINE COVEREDRATIO` < `0.80` (`coverage.line.minimum` em `apps/api/pom.xml`) | `team.md` Testing Posture ("Piso de cobertura: 80% de cobertura de linhas, medido separadamente no backend e no frontend"; "Frequência") | 3.485 testes (2.091 + 1.394), 0 falhas; **92,34%** de linhas; "All coverage checks have been met" (BT-UNIT-IT-API, BT-COV-API `Met`) |
| G4 | Testes do TxSigner, com piso de cobertura | `ci` / `txsigner` | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | qualquer teste com falha; JaCoCo `check` < `0.80` | `team.md` Testing Posture ("O processo TxSigner também segue o piso de 80%") | 247 testes (230 + 17), 0 falhas; check atendido (BT-UNIT-IT-TXSIGNER, BT-COV-TXSIGNER `Met`) |
| G5 | Testes e piso de cobertura do `apps/web` | `ci` / `frontend` | `pnpm --dir apps/web exec jest --coverage` | qualquer teste com falha; `coverageThreshold.global.lines` < 80 (`apps/web/jest.config.ts`) | `team.md` Testing Posture (piso de 80% medido separadamente) | 800 testes, 0 falhas; **94,68%** (BT-JEST-WEB, BT-COV-WEB `Met`) |
| G6 | Testes e piso de cobertura do `apps/admin` | `ci` / `admin` | `pnpm --dir apps/admin exec jest --coverage` | qualquer teste com falha; `coverageThreshold.global.lines` < 80 (`apps/admin/jest.config.ts`) | `team.md` Testing Posture | 221 testes, 0 falhas; **89,81%** (BT-JEST-ADMIN, BT-COV-ADMIN `Met`) |
| G7 | Contrato entre frontend e backend | `ci` / `contract` e `admin` | `mvnw … -Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest test`; `pnpm --dir apps/web check:api`; `pnpm --dir apps/admin check:api` | o OpenAPI gerado difere de `openapi-guest.yaml`/`openapi-admin.yaml`, ou o `schema.d.ts` difere do gerado | `team.md` Code Style ("os tipos do frontend são gerados a partir do contrato da API … Uma quebra de contrato falha a CI") | 20 testes de contrato (13 + 7), `check:api` dos dois apps verde (BT-CONTRACT `Met`; tentativa 1 do `contract` falhou só por rede ao baixar o Maven) |
| G8 | Fronteiras de módulo (ArchUnit) | `ci` / `backend` e `txsigner` | `mvnw verify` (classes `com.memetrade.architecture.*`) | qualquer regra violada: fronteiras, pureza do domínio, proibições de rede, chave, ponto flutuante e SDK | `team.md` Code Style ("Fronteiras de módulos (verificadas por ArchUnit)") e `project.md` Forbidden (SDK Solana, ponto flutuante) | todas verdes: `ArchitectureTest`, `BotWallet…`, `RealRisk…`, `RealExecution…`, `RealOps…`, `RealExits…`, TxSigner (BT-ARCHUNIT `Met`) |
| G9 | SDK Solana fora da produção | `ci` / `backend`, `txsigner`, `contract` | enforcer `BannedDependencies` no `mvnw` | o SDK Solana aparece fora do escopo `test` | `project.md` Forbidden ("NEVER incluir o SDK Solana no código de produção") | "BannedDependencies passed" (BT-ENFORCER `Met`) |
| G10 | Regras de alerta | `ci` / `backend` | `promtool check rules` nos 6 arquivos de `infra/observability/` (imagem `prom/prometheus` fixada por digest) | qualquer arquivo com erro de sintaxe ou de expressão | `monitoring-design` das Units; `team.md` Integridade da CI (imagem fixada) | 6 arquivos, 60 regras `SUCCESS` (BT-PROMTOOL `Met`) |
| G11 | Composição do ambiente | `ci` / `backend` | `docker compose … config --quiet` (base e base + `infra/docker-compose.test.yml`) | qualquer erro de parse ou de resolução | U2 (`ci.yml`) | válido (BT-COMPOSE `Met`) |
| G12 | Ponta a ponta, 42 specs | `ci` / `smoke` (todo PR e push) e `e2e` (push em `main`, depois dos outros 6) | `bash scripts/verify.sh` | código de saída diferente de zero: ambiente que não fica saudável ou qualquer spec Playwright com falha | `team.md` Walking Skeleton ("Verificação: … `bash scripts/verify.sh` … já roda na CI") e Testing Posture ("Frequência"; o spec da carteira "roda em toda mudança e no merge para `main`") | 42/42 no `smoke` (16,3 min) e 42/42 no `e2e` (13,2 min), 0 pulados, com `real-bot-wallet`, `real-swap`, `real-ops`, `real-ui`, `real-v1-exports`, `real-v2-exits` (BT-E2E `Met`) |
| G13 | Segredos | `security` / `secrets` | `gitleaks git` (histórico completo) e `gitleaks dir` (árvore), versão de `.gitleaks-version` com checksum conferido | qualquer achado | `team.md` Segurança na CI; `project.md` Mandated ("ALWAYS bloquear commits e merges que contenham segredos, com varredura no pré-commit e na CI") | sem achado no histórico nem na árvore (BT-SEC-SECRETS `Met`) |
| G14 | SAST | `security` / `sast` (`java-kotlin`, `javascript-typescript`) | CodeQL; `jq '[.runs[].results[] \| select(.level == "error")] \| length'` deve dar 0 | qualquer resultado de nível `error` (Crítico/Alto); `warning` (Médio) só avisa | `team.md` Segurança na CI ("Achado Crítico ou Alto bloqueia o merge; achado Médio gera aviso") | 0 resultados `error` nos dois idiomas; "CodeQL medium findings: 0" (BT-SEC-SAST `Met`) |
| G15 | Dependências Java | `security` / `dependencies` | OWASP `dependency-check-maven:12.1.0:check -DfailBuildOnCVSS=7`, com supressões geradas só das exceções vigentes | qualquer CVE com CVSS ≥ 7 sem exceção vigente | `team.md` Segurança na CI | `BUILD SUCCESS`; nada ≥ 7 fora de EX-001…EX-013 (BT-SEC-DEPS-JAVA `Met`) |
| G16 | Dependências do frontend | `security` / `dependencies` | `pnpm --dir apps/web audit --audit-level high`; `pnpm --dir apps/admin audit --audit-level high` | qualquer achado Alto ou Crítico; Moderado só aparece no log | `team.md` Segurança na CI | 0 Alto/Crítico; 6 Moderados por app (BT-SEC-DEPS-WEB, BT-SEC-DEPS-ADMIN `Met`). Depois da Q5, sem `ignoreGhsas`: local, código de saída 0 nos dois apps (`ci-config.md`, seção 6) |
| G17 | Exceções de segurança com validade | `security` / `dependencies` | `mvnw … -Dtest=SecurityExceptionsTest test`; supressões com `until="<expires>Z"` | exceção vencida, sem justificativa, sem aprovação ou com validade acima de 90 dias depois da aprovação | `team.md` Segurança na CI ("validade de no máximo 90 dias e aprovação registrada do responsável do projeto; exceção vencida volta a bloquear") | `success`; local depois da Q5: 3 testes, 0 falhas. Registro atual: EX-001…EX-013, aprovadas em 2026-11-11, vencem em 2027-02-09 |
| G18 | Ações fixadas pelo SHA | todos os workflows | revisão do `uses:` (sem verificação automática no workflow) | `uses:` com tag ou branch em vez de SHA de 40 caracteres | `team.md` Integridade da CI; `project.md` Mandated ("ALWAYS fixar pelo SHA do commit as ações de terceiros usadas na CI") | os 40 `uses:` dos três workflows estão fixados por SHA, e cada SHA é o commit da tag do comentário (`gh api …/commits/<tag>`); a imagem do `promtool` está fixada por digest |
| G19 | Permissões mínimas | todos os workflows | `permissions:` no topo de cada workflow | qualquer permissão de escrita ou workflow sem `permissions` | `team.md` Integridade da CI ("cada workflow declara `permissions` mínimas (por padrão, só leitura do conteúdo)") | `ci`, `security` e `load-test` declaram `contents: read`; só o `sast` acrescenta `actions: read`; nenhuma permissão de escrita; o único segredo é o `NVD_API_KEY` (leitura da NVD), e nenhum segredo de produção existe |
| G20 | Instalação pelos lockfiles | `ci` / `frontend`, `admin`, `contract`, `smoke`, `e2e` | `pnpm --dir apps/<app> install --frozen-lockfile` | lockfile ausente ou fora de sincronia com o `package.json` | `project.md` Mandated ("ALWAYS versionar os lockfiles e instalar as dependências a partir deles na CI") | instalações sem erro no run 44765239890 |

Observações sobre G18 e G19: os dois são impostos pela forma dos workflows e pela revisão do responsável, que é o único que altera os workflows (`team.md`). Nenhum passo da CI os verifica automaticamente. Um verificador (por exemplo, `zizmor` ou um `grep` que recuse `uses:` sem SHA) seria uma melhoria futura; não faz parte da Q3.

## 2. Meta `BT-CI-BEFORE-MERGE` da matriz do B&T

| Campo | Valor |
|---|---|
| Fonte | `org.md` Testing Posture (escopos com piso: "an 80% line-coverage floor and CI execution before merge") |
| Esperado | rodar na CI, antes do merge, os testes selecionados pelo Build and Test |
| Veredito no B&T | `N/A`, dono `ci-pipeline` |

**Leitura deste estágio: atendida.**
- Todo comando de teste e verificação que a matriz do B&T marca como `Met` tem um job que o executa em todo `pull_request` e em todo `push` em `main` (mapeamento completo em `ci-config.md`, seção 2): G3 a G7, G10 a G12 e G13 a G17.
- O piso de 80% é medido separadamente em cada parte (API, TxSigner, web, admin) e falha o build abaixo do limiar (G3 a G6), como pede o `team.md`.
- A suíte completa (`verify.sh` com os 42 specs) roda em toda mudança (`smoke`) e de novo no push em `main` (`e2e`), sem suíte maior reservada ao merge, como pede o `team.md` ("Frequência").
- O run 44765239890 (todos os jobs verdes) e o 44765240176 são a prova no commit verificado.

**Ressalva honesta.** Na estratégia do `team.md`, os commits entram direto em `main`, e `main` não tem proteção nem ruleset (`ci-config.md`, achado 1.5.1). Nos pushes diretos, a CI roda logo depois do commit, não antes. "Antes do merge" vale nos PRs (Dependabot ou branch pedida) e, nos pushes diretos, como a regra do `team.md` de não seguir com `main` vermelho. A opção (d) de `ci-config.md` (ruleset com checks obrigatórios em PR e bypass do administrador) fecha a parte dos PRs sem mudar o push direto, se o responsável quiser.

## 3. O que ainda não é portão

| Assunto | Situação hoje | Dono | Motivo |
|---|---|---|---|
| Carga e desempenho do REAL (BT-TC-PERF; U1:NFR3.1 a 3.4, 3.6, 3.8, 3.9; U2:NFR3.1 a 3.4; U4-NFR3.4; U5-NFR3.1 a 3.4) | o `load-test.yml` é só manual e não tem cenário `real`; as medições do job `backend` (U3, U4) são só informativas | `performance-validation` | `team.md` Testing Posture: "nenhum portão de desempenho ou carga até que os requisitos não funcionais definam metas numéricas"; Q3 (c) |
| Disponibilidade e frescor medidos em produção (U1:NFR4.1, U2:NFR4.1) | sem ambiente implantado | `performance-validation` | só se medem com tráfego real em 30 dias |
| Varredura dinâmica (DAST), varredura de imagens e SBOM por release | inexistentes | estágios de segurança e infraestrutura, antes do beta multiusuário (G1 do PRD) | `team.md` Deployment ("candidatos a definir nos estágios de segurança e infraestrutura") |
| Alarmes de p95 na nuvem (U3-NFR5.11, U4-NFR5.13) | regras do Prometheus validadas pelo `promtool` (G10); o alarme na nuvem não existe | `observability-setup` | depende do caminho das métricas até o CloudWatch (decisão aberta do responsável) |
| Acessibilidade automática | sem portão | — | `team.md` Testing Posture ("sem portão automático de acessibilidade na CI por enquanto") |
| Verificação automática de SHA e de permissões (G18, G19) | por revisão | — | melhoria futura, fora da Q3 |

## Sources

- `construction/ci-pipeline/ci-pipeline-questions.md` (Q3 e Q6)
- `construction/build-and-test/build-and-test-summary.md` (matriz: metas BT-*, `BT-CI-BEFORE-MERGE`)
- `construction/build-and-test/test-results.md` (números dos runs 44765239890 e 44765240176)
- `construction/<unit>/code-generation/code-summary.md` das 7 Units
- `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`; `apps/api/pom.xml`, `apps/txsigner/pom.xml` (`coverage.line.minimum`); `apps/web/jest.config.ts`, `apps/admin/jest.config.ts` (`coverageThreshold`); `security/exceptions.yml`
- `team.md` (Way of Working, Testing Posture, Code Style, Deployment), `project.md` (Forbidden, Mandated), `org.md` (Testing Posture)
- `gh run view 44765239890` e `gh run view 44765240176` (conclusão, `headSha`, jobs, anotações); `gh api …/branches/main/protection`
