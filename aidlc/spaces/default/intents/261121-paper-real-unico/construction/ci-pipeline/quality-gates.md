# Portões de qualidade da CI — intent `261121-paper-real-unico`

Data: 2026-11-25. Evidência: o último run verde do commit `09de0a8daea32fc9ec9d8bfe380a9eac9f3b6c29` (`build-and-test/test-results.md`, seção 9.1):
- `ci` **45550427456**, tentativa 2, 7 jobs `success`: `backend`, `frontend`, `admin`, `contract`, `smoke`, `txsigner` (reaproveitados da tentativa 1) e `e2e` (job `139189690166`);
- `security` **45550427465**, `success`: `secrets`, `sast (java-kotlin)`, `sast (javascript-typescript)`, `dependencies`.

Pela Q2 (opção A), todos os portões abaixo continuam como estão: o intent não acrescenta job, não muda limiar e não enfraquece nenhum portão. Um job vermelho deixa o run vermelho. Num PR, isso aparece antes do merge. Num push direto em `main` (`team.md`), a `main` vermelha é corrigida no commit seguinte, antes de qualquer outra mudança, e ponto de controle e implantação só usam commit com a CI verde (`project.md`, Mandated).

## 1. Portões que bloqueiam

| # | Portão | Workflow / job | Comando | Limiar (falha quando) | Regra de origem | Evidência no `09de0a8` |
|---|---|---|---|---|---|---|
| G1 | Lint, formatação e tipos dos apps web | `ci` / `frontend` e `admin` | `pnpm --dir apps/<app> lint`, `format:check`, `typecheck` | qualquer erro do ESLint, arquivo fora do Prettier ou erro do `tsc --noEmit` (`strict`) | `team.md` Code Style | ESLint, Prettier e `tsc` verdes nos dois apps (TGT-SUITE-WEB, TGT-SUITE-ADMIN `Met`) |
| G2 | Formatação do backend e do TxSigner | `ci` / `backend` e `txsigner` | `mvnw verify` (`spotless:check`) | qualquer arquivo Java fora do palantir-java-format | `team.md` Code Style | `BUILD SUCCESS` nos dois |
| G3 | Unidade e integração da API, com piso de cobertura | `ci` / `backend` | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | qualquer teste com falha ou erro; JaCoCo `check` < `0.80` de linhas (`coverage.line.minimum` em `apps/api/pom.xml`) | `team.md` Testing Posture (piso de 80%, Frequência) | Surefire 2.511/0 falhas/0 pulados; Failsafe 1.688/0/0; "All coverage checks have been met" (TGT-SUITE-API, TGT-COV-API `Met`) |
| G4 | Testes do TxSigner, com piso | `ci` / `txsigner` | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | qualquer teste com falha; JaCoCo < `0.80` | `team.md` Testing Posture | 230 + 17 testes, 0 falhas; piso atendido (TGT-SUITE-TXS, TGT-COV-TXS `Met`) |
| G5 | Jest e piso do `apps/web` | `ci` / `frontend` | `pnpm --dir apps/web exec jest --coverage` | qualquer teste com falha; `coverageThreshold.global.lines` < 80 (`apps/web/jest.config.ts`) | `team.md` Testing Posture | 86 suítes, 1.004 testes, 0 falhas; **94,43%** de linhas (TGT-COV-WEB `Met`) |
| G6 | Jest e piso do `apps/admin` | `ci` / `admin` | `pnpm --dir apps/admin exec jest --coverage` | idem, `apps/admin/jest.config.ts` | `team.md` Testing Posture | 20 suítes, 221 testes, 0 falhas; **89,81%** (TGT-COV-ADMIN `Met`) |
| G7 | Contrato entre frontend e backend | `ci` / `contract` e `admin` | `mvnw ... -Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest test`; `pnpm --dir apps/web check:api`; `pnpm --dir apps/admin check:api` | OpenAPI gerado diferente de `openapi-guest.yaml`/`openapi-admin.yaml`, ou `schema.d.ts` diferente do gerado | `team.md` Code Style (contrato) | 25 testes, 0 falhas; "schema.d.ts matches" nos dois apps (TGT-CONTRACT `Met`) |
| G8 | Fronteiras de módulo e proibições (ArchUnit) | `ci` / `backend` e `txsigner` | `mvnw verify` (`com.memetrade.architecture.*`) | qualquer regra violada: fronteiras, pureza do domínio, rede, chave, ponto flutuante, SDK; modos nunca somados (`NeverSumModesArchitectureTest`, `ModeBoundaryArchitectureTest`); `ownedByAnotherTenant` só no caminho do 404 | `team.md` Code Style; `project.md` Forbidden (ponto flutuante, total geral) | verdes (TGT-RISK-02 `Met`) |
| G9 | SDK Solana fora da produção | `ci` / `backend`, `txsigner`, `contract` | enforcer `BannedDependencies` | SDK Solana fora do escopo `test` | `project.md` Forbidden | `BUILD SUCCESS` |
| G10 | Regras de alerta | `ci` / `backend` | `promtool check rules` nos 6 arquivos de `infra/observability/` (imagem por digest); `promtool test rules` das regras da U4 dentro do `mvnw verify` (`RealOpsOwnHoldingsAlertRuleIT`) | erro de sintaxe, de expressão ou de teste de regra | `monitoring-design` das Units; `team.md` Cobertura da CI | 6 arquivos, 63 regras `SUCCESS` (TGT-PROMTOOL `Met`) |
| G11 | Composição do ambiente | `ci` / `backend` | `docker compose -f docker-compose.yml config --quiet` e com `-f infra/docker-compose.test.yml` | erro de parse ou de resolução | `team.md` Cobertura da CI | válido |
| G12 | Ponta a ponta, 62 specs | `ci` / `smoke` (todo PR e push) e `e2e` (push em `main`, depois dos outros 6) | `bash scripts/verify.sh` | código de saída diferente de zero: ambiente que não fica saudável, spec com falha ou endereço completo nos logs | `team.md` Walking Skeleton (Verificação; fatia do intent) e Frequência | 62 passed no `smoke` (16,1 min) e 62 no `e2e` da tentativa 2 (15,6 min), 20 casos do `zz-paper-real-unico` em cada (TGT-E2E `Met`). A tentativa 1 do `e2e` falhou: ver G12-a e a seção 4 |
| G13 | Segredos | `security` / `secrets` | `gitleaks git` (histórico) e `gitleaks dir` (árvore), versão com checksum conferido | qualquer achado | `team.md` Segurança na CI; `project.md` Mandated | "no leaks found" (TGT-SEC-CI `Met`) |
| G14 | SAST | `security` / `sast` | CodeQL; `jq` conta `level == "error"`, que deve ser 0 | qualquer resultado Crítico/Alto; Médio só avisa | `team.md` Segurança na CI | verdes nos dois idiomas |
| G15 | Dependências Java | `security` / `dependencies` | OWASP `dependency-check-maven:12.1.0:check -DfailBuildOnCVSS=7` com supressões das exceções vigentes | CVE com CVSS ≥ 7 sem exceção vigente | `team.md` Segurança na CI | abaixo de CVSS 7 |
| G16 | Dependências dos apps web | `security` / `dependencies` | `pnpm --dir apps/web audit --audit-level high`; o mesmo em `apps/admin` | achado Alto ou Crítico; Moderado só no log | `team.md` Segurança na CI | 0 Alto/Crítico; 6 Moderados no `apps/web` (aviso) |
| G17 | Exceções com validade | `security` / `dependencies` | `mvnw ... -Dtest=SecurityExceptionsTest test`; supressões com `until` | exceção vencida, sem justificativa, sem aprovação ou acima de 90 dias | `team.md` Segurança na CI | `success` |
| G18 | Ações fixadas por SHA e permissões mínimas | `ci` / `backend` (`WorkflowsTest` dentro do `mvnw verify`) | `WorkflowsTest`: `everyThirdPartyActionIsPinnedByAFullCommitSha`, `everyWorkflowDeclaresReadOnlyPermissionsAndNeverUsesPullRequestTarget` | `uses:` sem SHA de 40 caracteres, permissão de escrita ou `pull_request_target` | `team.md` Integridade da CI; `project.md` Mandated (SHA) | verde no `backend`. Diferente do precedente do 261113, a verificação é automática |
| G19 | Instalação pelos lockfiles | `ci` / `frontend`, `admin`, `contract`, `smoke`, `e2e` | `pnpm --dir apps/<app> install --frozen-lockfile` | lockfile ausente ou fora de sincronia | `project.md` Mandated (lockfiles) | instalações sem erro |

**G12-a, e2e sem instabilidade (TGT-E2E-FLAKY, `Not Met`).** O alvo do B&T é "a suíte passa sem repetição no mesmo commit". Ele não é um job à parte: é o próprio G12. Na tentativa 1 do `e2e` do `09de0a8` a compra REAL do `real-swap` ficou sem confirmação por 7,5 min e 6 specs caíram; a repetição passou. O portão G12 funcionou (deixou o run vermelho). O defeito continua aberto, com dono e ações em `ci-config.md`, seção 6. Nenhuma repetição automática é acrescentada.

## 2. Mapeamento: comando do Build and Test → job da CI

Comandos de `build-and-test/build-instructions.md` (tabela "Comandos") e da matriz de `build-and-test-summary.md`.

| Meta do B&T | Comando do B&T | Workflow / job | A CI impõe? |
|---|---|---|---|
| TGT-SUITE-API, TGT-COV-API, os testes por risco TGT-RISK-01 a TGT-RISK-13 e TGT-RISK-15 do backend, TGT-ORDER e TGT-VOLUME na API, e os `NFRx.y` `Met` das 4 Units medidos por IT | `sh apps/api/mvnw -B -ntp -f apps/api/pom.xml verify` | `ci` / `backend` | sim, G2, G3, G8, G9, G18 |
| TGT-PROMTOOL; U4-NFR2.12 e NFR2.18 (`promtool test rules`) | `docker run ... promtool check rules /rules/<arquivo>.yml` (6 arquivos); `RealOpsOwnHoldingsAlertRuleIT` | `ci` / `backend` | sim, G10 |
| (composição) | `docker compose ... config --quiet` | `ci` / `backend` | sim, G11 |
| TGT-SUITE-TXS, TGT-COV-TXS, TGT-RISK-13 e TGT-RISK-14 no TxSigner | `sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml verify` | `ci` / `txsigner` | sim, G2, G4 |
| TGT-SUITE-WEB, TGT-COV-WEB | `pnpm --dir apps/web lint`, `format:check`, `typecheck`, `exec jest --coverage` | `ci` / `frontend` | sim, G1, G5 |
| TGT-SUITE-ADMIN, TGT-COV-ADMIN | `pnpm --dir apps/admin lint`, `format:check`, `typecheck`, `exec jest --coverage`, `check:api` | `ci` / `admin` | sim, G1, G6, G7 |
| TGT-CONTRACT | `mvnw ... -Dtest=GuestOpenApiContractTest,AdminOpenApiContractTest ... test`; `pnpm --dir apps/web check:api` | `ci` / `contract` | sim, G7 |
| TGT-E2E (e o spec `zz-paper-real-unico`: U1-NFR6.1, NFR7.7, NFR7.12; U2-NFR7.6; U4-NFR7.5) | `bash scripts/verify.sh` | `ci` / `smoke` e `e2e` | sim, G12 |
| TGT-SEC-CI | `gitleaks git`/`dir`; CodeQL; `dependency-check ... -DfailBuildOnCVSS=7`; `pnpm audit --audit-level high` nos dois apps; `SecurityExceptionsTest` | `security` / `secrets`, `sast`, `dependencies` | sim, G13 a G17 |
| Comandos por Unit dos 4 `unit-test-instructions.md` (`-Dtest=<lista>`, `jest <arquivos>`) | subconjuntos das suítes acima | `ci` / `backend`, `frontend` (rodam inteiros, uma vez) | sim, contidos em G3 e G5 |
| TGT-LOAD-OFFLINE (U2-NFR7.8, U3-NFR7.13) | `node --test "scripts/load/*.test.mjs"` | **nenhum job hoje**; o passo D-INF4 (`ci-config.md`, seção 2.2) o coloca no `frontend` | **não**, até o responsável aplicar o texto pronto; prova atual: execução manual, 104/104 (também nesta execução) |
| TGT-CI-MERGE (`org.md`, piso de escopo: "CI execution before merge"); dono `ci-pipeline` | todos os acima | `ci` inteiro | sim (seção 3) |

**Leitura:** todo comando do Build and Test que sustenta uma meta `Met` roda num job da CI em todo `pull_request`, em todo `push` em `main` e no disparo manual do `ci`. A única exceção entre os comandos de meta `Met` é o `node --test` dos cenários de carga (TGT-LOAD-OFFLINE), cujo passo de CI é um texto pronto ainda não aplicado; até lá ele é conferido à mão, como o `team.md` permite para o que só o responsável altera.

## 3. Meta `TGT-CI-MERGE` da matriz do B&T

| Campo | Valor |
|---|---|
| Fonte | Testing Contract das 4 Units, `obligations.scope_floor` (`org.md` Testing Posture, escopo `feature`: "80% line-coverage floor and CI execution before merge") |
| Esperado | rodar na CI, antes do merge, os testes selecionados pelo Build and Test |
| Veredito no B&T | `N/A`, dono `ci-pipeline` |

**Leitura deste estágio: atendida, com a ressalva do tronco.**
- Os testes selecionados (seção 2) rodam no `ci` em todo PR e push; os pisos de 80% são medidos e impostos separadamente na API, no TxSigner, no `apps/web` e no `apps/admin` (G3 a G6).
- A suíte completa (`verify.sh`, 62 specs) roda em toda mudança (`smoke`) e de novo no push em `main` (`e2e`).
- O run `45550427456` (todos os jobs verdes na tentativa 2) e o `45550427465` são a prova no commit verificado.
- **Ressalva:** na estratégia do `team.md`, os commits entram direto em `main`, sem proteção de branch. Nos pushes diretos, a CI roda logo depois do commit; "antes do merge" vale nos PRs (Dependabot ou branch pedida) e, nos pushes diretos, como a regra de corrigir a `main` vermelha no commit seguinte e de só usar commit verde em ponto de controle e implantação (`project.md`, Mandated).

## 4. O que fica fora da CI, e por quê

| Alvo | Situação | Dono | Motivo |
|---|---|---|---|
| Portão de carga `pnl-by-mode` da U2 e da U3: U2-NFR1.1, 1.2, 1.3, 1.6, 1.24, 1.25 e U3-NFR1.6 (`Unverified`); U2-NFR1.9 (`Not Met`); AC3.11.1 | o `load-test` é **só manual** e ainda não tem a opção `pnl-by-mode` (`grep` dá 0 ocorrências em `.github/workflows/load-test.yml`) | responsável (aplicar o texto da seção 2.1 de `ci-config.md`), depois `performance-validation` | `team.md`: "O workflow `load-test` é só manual" e "nenhum portão de desempenho ou carga até que os requisitos não funcionais definam metas numéricas"; o disparo custa 45 a 70 min de runner por execução. Com o texto aplicado, o portão existe, mas continua manual e não bloqueia PR |
| AC3.11.2 (medição do fundador na devnet) | nenhum ambiente na nuvem provisionado | `performance-validation` | `team.md` Deployment: só o ambiente local existe; a devnet é do fundador e nenhum teste da CI usa devnet (Determinismo) |
| Medida oficial da U4: U4-NFR1.2, NFR1.5, NFR2.17 (`Unverified`) | a CI roda o `RealBotHoldingsMeasurementIT` com 300 leituras e 0 ciclos (padrão desde `fc65b7f`); a medida oficial precisa de `-Du4.measure.reads=10000 -Du4.measure.cycles=100`, 3 vezes, em Linux | `build-and-test` (com a decisão F7 do fundador) | tempo e memória: o `backend` já leva cerca de 38 min, e a medida oficial não cabe nele; ela roda por disparo manual ou numa máquina Linux. As medidas de 300 leituras da CI são só registro, não portão; por isso U4-NFR1.1 e NFR1.13 (`Not Met`) não deixam o `backend` vermelho |
| TGT-U4-ALARM (`Not Met`) | premissa dos alarmes medida pelo mesmo IT, só como registro | `build-and-test` / fundador (F7) | mesma razão; a flag `memetrade.real.own-quantity-sell` fica desligada |
| Terraform: `fmt`, `validate`, `test` (TGT-IAC-1), Checkov (TGT-IAC-2), U4-NFR8.4 (`Unverified`) | rodam **só à mão**; não instalados nesta máquina | responsável (pendência da CI) e `environment-provisioning` | `team.md` Testing Posture, "Testes da IaC": levá-los à CI é pendência do responsável [Q5 do `team.md`] |
| Custos nas contas (U2-NFR8.2, U4-NFR8.2) e ordem devnet → mainnet (U4-NFR8.3) | sem conta na nuvem | `environment-provisioning`, `deployment-execution` | só se medem com a conta AWS e a implantação |
| Acessibilidade automática | sem portão | — | `team.md` Testing Posture: "sem portão automático de acessibilidade na CI por enquanto"; os critérios básicos valem pelo spec de 360 px (G12) |
| DAST, varredura de imagens e SBOM | inexistentes | estágios de segurança e infraestrutura, antes do beta multiusuário (G1 do PRD) | `team.md` Deployment ("candidatos, a definir") |

## Sources

- `construction/ci-pipeline/ci-pipeline-questions.md` (Q2, Q3)
- `construction/build-and-test/build-and-test-summary.md` (matriz: TGT-*, metas `Unverified` e `Not Met` com donos)
- `construction/build-and-test/build-instructions.md` (comandos, `-Du4.measure.*`)
- `construction/build-and-test/test-results.md` (seções 7, 9 e 10; runs `45550427456` e `45550427465`)
- `construction/u3-pnl-views/code-generation/code-summary.md` (D-INF4 e o texto do `load-test.yml`)
- `.github/workflows/ci.yml`, `security.yml`, `load-test.yml`; `apps/api/pom.xml` (`coverage.line.minimum`); `apps/web/jest.config.ts`, `apps/admin/jest.config.ts`; `apps/api/src/test/java/com/memetrade/infra/WorkflowsTest.java`
- `team.md` (Way of Working, Testing Posture, Code Style, Deployment), `project.md` (Forbidden, Mandated), `org.md` (Testing Posture)
