# Verificação de fase — Construction → Operation (PAPER e REAL numa visão única)

**Veredito: FAIL (`pass: false` no portão entre Units, 173 de 177 IDs).** As 4 Units estão construídas e testadas, as 4 tabelas da Code Generation não têm alvo ausente, gap nem órfão, e a CI impõe os comandos do Build and Test. **O FAIL vem só de itens com dono, já aceitos pelo fundador ou retidos por ele:** nenhum ID ficou descoberto por falta de código nem por erro de registro depois das correções F-T1 a F-T3. A decisão de seguir é do fundador (seção 7). **Recomendação: transição condicional**, com as condições da seção 7; sem a confirmação dele, a regra do estágio manda parar e voltar aos estágios donos.

Conversa: português (Brasil). Data: 2026-11-25. Commit verificado: `09de0a8daea32fc9ec9d8bfe380a9eac9f3b6c29`. Fontes: `construction/build-and-test/cross-unit-traceability.md`, os 4 `construction/<unit>/code-generation/traceability.json`, `construction/build-and-test/build-and-test-summary.md` e `test-results.md`, e `construction/ci-pipeline/quality-gates.md` e `ci-config.md`.

## 1. Correções de registro (Q5): F-T1, F-T2, F-T3

Feitas neste estágio, só nos `traceability.json` (nenhum código mudou). Cada alvo `OK` editado foi conferido no disco antes de gravar. O formato é o do verificador (`project.md`, Corrections de 2026-11-20): IDs `NFRx.y` sem o prefixo da Unit, um único arquivo existente por alvo `OK` e os demais arquivos e testes na nota.

| Achado | Unit | Linhas | Antes | Depois |
|---|---|---|---|---|
| **F-T1** | `u3-pnl-views` | AC4.1.1, AC4.1.3, AC4.1.4, AC4.1.5, AC4.2.1, AC4.2.3, AC4.2.7, AC4.4.5, AC4.5.2, AC7.2.1, NFR4.6, NFR8.2, NFR8.3 | `N/A` com `"target": null` e o motivo em `note`; o sensor descartava o arquivo inteiro (`coverage[9].target must be a string`) | `N/A` com o motivo no `target` (por exemplo, "entregue pela u1-mode-skeleton (PnlModeBlock.tsx)"; AC7.2.1 "entregue pela u2-real-bot-pnl (RealPnlEngine.java)") |
| **F-T1** | `u3-pnl-views` | NFR7.3 | `N/A`, `target: null` | `OK`, `apps/web/jest.config.ts`; nota: web 94,43%, JaCoCo da API com `coverage.line.minimum` 0.80 ("All coverage checks have been met") no `ci` 45550427456, admin 89,81% |
| **F-T2** | `u1-mode-skeleton` | AC3.2.5, AC3.2.6, BR5.5, NFR2.1 (e a nota de BR5.2) | alvo `apps/api/src/main/java/com/memetrade/pnl/application/RealBotPnlQueryV1.java`, removido pela U2 no commit `4a2cf7d` (C10 V2, `code-summary.md` da U2: "Saíram `RealBotPnlQueryV1` …") | `OK`, `apps/api/src/main/java/com/memetrade/pnl/application/RealBotPnl.java`, o arquivo que o substituiu; notas com `RealPnlController.java`, `RealBotPnlQueryTest`, `RealBotPnlTest`, `RealPnlTenantIsolationIT`, `GuestOpenApiContractTest` e, em NFR2.1, `RealPnlEngine.java` (`Math.*Exact`) e a regra ArchUnit `theRealLedgerAndThePnlNeverUseFloatingPoint` |
| **F-T3** | `u1-mode-skeleton` | AC4.1.5, BR10.1, NFR6.1, NFR7.7, NFR7.12 | `Deferred` ("o spec ainda não rodou") | `OK`, `apps/web/e2e/zz-paper-real-unico/paper-real-unico.spec.ts`; evidência: `ci` 45550427456 no `09de0a8`, `smoke` 62/62 e `e2e` da tentativa 2 62/62, 20 casos do `zz-paper-real-unico`; `verify.sh` local de 2026-11-25 verde |
| **F-T3** | `u2-real-bot-pnl` | NFR1.4, NFR1.5, NFR1.8, NFR1.26, NFR7.2, NFR7.4, NFR7.6 | `Deferred` para o Build and Test | `OK`, com o alvo e o número da matriz do B&T (todos `Met`): `RealAttributionMeasurementIT.java`, `RealRiskMeasurementIT.java`, `docs/perf/u2-barrier-baseline.md`, `apps/api/pom.xml`, `scripts/verify-checkpoint.sh` e o spec. As ressalvas 5 e 6 do `build-and-test-summary.md` (números da comparação intercalada não gravados; pior caso de 678 ms só informativo) ficaram nas notas |
| **F-T3** | `u4-own-quantity-cycle` | NFR7.3, NFR7.5 | `Deferred` | `OK`, `apps/api/pom.xml` e o spec, com a mesma evidência da CI |

**Por que U2 e U4 também foram tocadas.** A Q5 citava "U1 e U3", mas a U3 não tinha nenhuma linha `Deferred`, e o próprio achado F-T3 do `cross-unit-traceability.md` lista as linhas da U2 e da U4. Depois de corrigir U1 e U3, o sensor ainda dava **NFR7 descoberto só por essas linhas** (U2: NFR7.2, NFR7.4, NFR7.6; U4: NFR7.3, NFR7.5), todas `Met` na matriz. Era um defeito real de registro mostrado pelo sensor, por isso foi corrigido. As linhas da U2 e da U4 que continuam `Deferred` ou `Not Met` de verdade não foram tocadas: U2 NFR1.1, NFR1.9, NFR1.25, AC3.11.1, AC3.11.2, e U4 NFR1.1 e NFR1.13 (que já eram `OK` na rastreabilidade; ver F-T6).

## 2. Rastreabilidade da Code Generation por Unit

Comando, nesta execução, para cada Unit: `aidlc engine sensor-traceability --stage code-generation --output-path <record>/construction/<unit>/code-generation/traceability.json`.

| Unit | Arquivo | Linhas | OK | Deferred | N/A | Gaps, órfãos, ausentes da tabela, entradas ou alvos inválidos | `missing_from_upstream_ids` | `pass` |
|---|---|---|---|---|---|---|---|---|
| `u1-mode-skeleton` | sim | 193 | 171 | 0 | 22 | 0 | 0 | true |
| `u2-real-bot-pnl` | sim | 198 | 179 | 5 | 14 | 0 | 1: BR1.9 | false |
| `u3-pnl-views` | sim | 165 | 152 | 0 | 13 | 0 | 1: NFR1.25 | false |
| `u4-own-quantity-cycle` | sim | 113 | 104 | 0 | 9 | 0 | 3: NFR1.15, NFR1.18, NFR3.11 | false |
| **Total** | **4 de 4** | **669** | **606** | **5** | **58** | **0** | **5** | 1 de 4 |

**Os 5 `missing_from_upstream_ids` são falsos positivos do sensor (classe F-T5), não achados:** são IDs de outra Unit citados em prosa nos artefatos de requisitos, que o sensor lê como IDs da própria Unit.
- U2 BR1.9: citado na suposição 10 de `u2-real-bot-pnl/functional-design/rules.md` ("As pendências de texto da U1 (… e o BR1.9) são da U1"); BR1.9 é da U1, onde está `OK` (`CycleRunner.java`).
- U3 NFR1.25: citado no `## Sources` de `u3-pnl-views/nfr-requirements/performance-requirements.md` ("… NFR1.25 (cenário `pnl-by-mode` …)" da U2).
- U4 NFR1.15, NFR1.18: citados no `## Sources` de `u4-own-quantity-cycle/nfr-requirements/scalability-requirements.md` ("… NFR1.15, NFR1.18 … da U2"); os dois estão `OK` na U2.
- U4 NFR3.11: citado no `## Sources` de `u4-own-quantity-cycle/nfr-requirements/security-requirements.md` ("… NFR3.11 … da U2"); `OK` na U2 (`RealRiskPolicy.java`).

Nenhum deles existe nos requisitos da Unit em que o sensor o conta. Na versão do `HEAD` (antes das correções), a U2 e a U4 já davam os mesmos `missing_from_upstream_ids`, e a U3 nem chegava a essa conta porque o arquivo era recusado. As correções de registro não criaram nenhum achado novo. Para silenciá-los, o caminho seria citar como "NFR1.15 da U2" fora do padrão de ID, o que muda artefatos aprovados e não foi feito.

**Os 5 `Deferred` restantes (todos na U2) são pendências reais, com dono:** AC3.11.1 e NFR1.1, NFR1.9, NFR1.25 (portão de carga `pnl-by-mode`, manual, `performance-validation`; NFR1.9 depende de o responsável aplicar o texto do `load-test.yml`) e AC3.11.2 (medição na devnet, `performance-validation`).

**Resultado:** as 4 tabelas estão presentes; nenhuma tem alvo ausente, gap ou órfão. Os únicos achados do sensor por Unit são as 5 citações F-T5.

## 3. Units construídas e testadas

| Unit | Construída | Testada (`ci` 45550427456, `09de0a8`) | Observação |
|---|---|---|---|
| U1 `u1-mode-skeleton` | sim | `backend`, `frontend`, `contract`, `smoke`/`e2e` (spec `zz-paper-real-unico`) | esqueleto verificado e aprovado; `verify.sh` local verde no `09de0a8` |
| U2 `u2-real-bot-pnl` | sim | `backend` (incluindo `RealPnlPropertyTest` com 1.050 sequências), `contract`, `smoke`/`e2e` | portão de carga `pnl-by-mode` não rodou (seção 6) |
| U3 `u3-pnl-views` | sim | `backend`, `frontend`, `contract`, `smoke`/`e2e` (casos U3 1 a U3 10) | IT de latência só informativo; carga com a U2 |
| U4 `u4-own-quantity-cycle` | sim, com a flag `memetrade.real.own-quantity-sell` desligada por padrão | `backend` (`RealBotHoldingsMeasurementIT`, `RealOpsOwnHoldingsAlertRuleIT`), `frontend`, `smoke`/`e2e` (casos U4 1 e U4 2) | U4-NFR1.1 e NFR1.13 `Not Met` na semente B (seção 6) |

Totais: Surefire 2.511 e Failsafe 1.688 na API, 230 + 17 no TxSigner, 1.004 no `apps/web`, 221 no `apps/admin`, 25 de contrato, 62/62 specs no `smoke` e no `e2e` (tentativa 2), 0 falhas na execução que vale. Pisos de cobertura cumpridos nas 4 partes (API e TxSigner pelo JaCoCo; web 94,43%; admin 89,81%). Matriz do B&T: **301 alvos — Met 279, Not Met 5, Unverified 16, N/A 1** (`TGT-CI-MERGE`, atendido por este estágio).

**Resultado: as 4 Units estão construídas e testadas.**

## 4. Portão entre Units (FR, NFR e AC)

Comando: `aidlc engine sensor-traceability --stage build-and-test`, executado da raiz do repositório, sem `cd` nem pipe.

| Medida | Antes (Build and Test, e início deste estágio) | Depois de F-T1, F-T2, F-T3 |
|---|---|---|
| `pass` | false | **false** |
| IDs cobertos | 134 de 177 | **173 de 177** |
| FR | 23 de 31 | **31 de 31** |
| AC | 111 de 138 | **136 de 138** |
| NFR do intent | 0 de 8 | **6 de 8** |
| `reason` | alvo `RealBotPnlQueryV1.java` inexistente (U1); `coverage[9].target must be a string` (U3) | nenhum (sem erro de formato nem alvo ausente) |
| Descobertos | 43 | **4: NFR1, NFR3, AC3.11.1, AC3.11.2** |

Iterações: (1) U1 e U3 corrigidas: 5 descobertos (NFR1, NFR3, NFR7, AC3.11.1, AC3.11.2); (2) linhas F-T3 da U2 e da U4: NFR7 coberto, 4 descobertos.

**Os 4 que sobram:**

| ID | Por que está descoberto | Natureza | Dono |
|---|---|---|---|
| AC3.11.1 | `Deferred` na U2: portão p95 ≤ 1 s de C4 no `load-test` manual, cenário `pnl-by-mode`, que ainda não existe no `load-test.yml` | **lacuna real** | responsável (aplicar o texto de `ci-config.md`, seção 2.1), depois `performance-validation` |
| AC3.11.2 | `Deferred` na U2: medição do fundador na devnet com o mesmo cenário | **lacuna real** | `performance-validation` |
| NFR1 | U2 NFR1.1, NFR1.9, NFR1.25 `Deferred` (o mesmo portão de carga) | **lacuna real**, mais 3 falsos positivos F-T5 na mesma linha (U3 "NFR1.25", U4 "NFR1.15" e "NFR1.18", citações da U2) | `performance-validation` (NFR1.9: o responsável) |
| NFR3 | só U4 "NFR3.11" | **falso positivo F-T5**: a U4 não tem NFR3.11; é citação da U2 no `## Sources` de `security-requirements.md`. Sem a citação, NFR3 estaria coberto | — |

**Resultado: o portão entre Units não passou** (`pass: false`). Não sobrou nenhum ID descoberto por falta de código ou por defeito de registro: sobram a lacuna real de carga e devnet (AC3.11.1, AC3.11.2, NFR1) e os falsos positivos F-T5 (NFR3 e parte de NFR1).

**F-T6 (lembrete):** o sensor só confere se o alvo existe. U4-NFR1.1 e NFR1.13 estão `OK` na rastreabilidade (o `RealBotHoldingsReader.java` existe), mas a meta está `Not Met` na matriz. A cobertura de rastreabilidade não substitui o veredito da matriz; esses dois estão na seção 6.

## 5. A CI impõe os comandos do Build and Test

Todo comando de `build-instructions.md` que sustenta uma meta `Met` roda num job do `ci` ou do `security` em todo `pull_request` e `push` em `main` (`quality-gates.md`, G1 a G19 e seção 2): `mvnw verify` da API (`backend`), `promtool` e `docker compose config` (`backend`), `mvnw verify` do TxSigner (`txsigner`), lint, formatação, tipos e Jest com piso dos dois apps (`frontend`, `admin`), contrato (`contract`, `admin`), `verify.sh` (`smoke` e `e2e`), Gitleaks, CodeQL, Dependency-Check e `pnpm audit` (`security`). O `WorkflowsTest` (dentro do `backend`) confere as ações fixadas por SHA e as permissões só de leitura.

Ressalvas:
- **`node --test "scripts/load/*.test.mjs"` (TGT-LOAD-OFFLINE) ainda não está em job.** O texto pronto do passo, no fim do job `frontend`, está em `ci-config.md`, seção 2.2; até o responsável aplicá-lo, a prova é a execução manual (104/104, repetida nesta execução).
- **Ficam fora da CI, por decisão registrada:** o `load-test` (só manual, `team.md`), a medida oficial da U4 (precisa de `-Du4.measure.reads=10000 -Du4.measure.cycles=100`; a CI roda 300 leituras e só registra) e o Terraform e o Checkov (à mão; pendência do responsável) (`quality-gates.md`, seção 4).
- `main` não tem proteção: nos pushes diretos, a CI roda depois do commit, e o bloqueio é por disciplina (`project.md`, Mandated).

**Resultado: atendido**, com o passo `node --test` pendente do responsável.

## 6. Falhas do Build and Test aceitas pelo fundador, e alvos `Unverified`

O fundador escolheu **Accept failure** no Build and Test em 2026-11-25 (`test-results.md`, seção 10), com 1 de 3 loop-backs usados.

| Alvo | Veredito | Dono | O que fecha | Fase do dono |
|---|---|---|---|---|
| **U4-NFR1.1** | Not Met (semente B: 62,7% de `BUDGET_EXCEEDED` com 500 ms) | `build-and-test` / decisão F7 do fundador | medida por passo; F7 (consulta enxuta de S4, cache por bot que reabre U4-NFR8.12 e o ADR-004, ou orçamento maior); medida oficial 10.000 × 3 em Linux | **Construction** |
| **U4-NFR1.13** | Not Met (mesma causa) | idem | idem | **Construction** |
| **TGT-U4-ALARM** | Not Met (premissa dos alarmes NFR2.12 e NFR2.18) | idem | idem, mais o recálculo das premissas | **Construction** |
| **U2-NFR1.9** | Not Met (`load-test.yml` sem `pnl-by-mode`) | responsável (só ele altera `.github/workflows/`), depois `performance-validation` | aplicar o texto de `ci-config.md`, seção 2.1 | Operation (o arquivo é do responsável) |
| **TGT-E2E-FLAKY** | Not Met (`real-swap` sem confirmação na tentativa 1 do `e2e`) | responsável, com o coordenador | as 5 ações de `ci-config.md`, seção 6, com teste de regressão antes da correção | **Construction** (defeito de teste ou de ambiente) |

**Os 16 `Unverified`, com dono:**

| Alvos | Dono |
|---|---|
| U2-NFR1.1, NFR1.2, NFR1.3, NFR1.6, NFR1.24, NFR1.25; U3-NFR1.6 | `performance-validation` (portão `pnl-by-mode`) |
| U4-NFR1.2, NFR1.5, NFR2.17 | `build-and-test` (medida oficial com `-D`) |
| TGT-IAC-1, TGT-IAC-2, U4-NFR8.4 | `environment-provisioning` (Terraform e Checkov à mão) |
| U2-NFR8.2, U4-NFR8.2 | `environment-provisioning` (custo nas contas) |
| U4-NFR8.3 | `deployment-execution` (ordem devnet → mainnet) |

Exposição hoje: nenhuma em produção. Nenhum ambiente na nuvem existe (`team.md`, Deployment); a flag `memetrade.real.own-quantity-sell` é `false` por padrão no código e no Terraform, e com ela desligada a U4 não muda o comportamento (U4-NFR1.3 verde); o REAL continua desligado por padrão (TGT-RISK-09 verde).

## 7. Veredito e recomendação (a decisão é do fundador)

**Veredito: FAIL.** A regra do estágio diz: "If any traceability file is missing or any unresolved finding remains, stop the Construction → Operation transition and revisit the owning stage." Os arquivos estão todos presentes, mas o portão automático deu `pass: false`, e há itens `Not Met` cujo dono está na Construction. Este documento não os declara resolvidos e não chama o resultado de PASS.

**O FAIL é causado só por itens retidos pelo responsável ou aceitos por ele:**
- AC3.11.1, AC3.11.2 e NFR1 (portão de carga e devnet) dependem de o responsável aplicar o `load-test.yml` e de estágios da Operation; nenhum estágio da Construction pode fechá-los.
- NFR3 e parte de NFR1 são falsos positivos do sensor (F-T5).
- U4-NFR1.1, U4-NFR1.13, TGT-U4-ALARM e TGT-E2E-FLAKY foram aceitos como falha no Build and Test, com dono e ação; U2-NFR1.9 espera um arquivo que só o responsável altera.
- Nenhum ID ficou descoberto por falta de código ou por erro de registro.

**Recomendo seguir para a Operation de forma condicional**, com estas condições válidas até cada item ser verificado:
1. **U4 (U4-NFR1.1, NFR1.13, TGT-U4-ALARM):** `memetrade.real.own-quantity-sell` fica `false` em todos os ambientes até a medida por passo, a decisão F7 e uma medida oficial (10.000 leituras × 3, Linux, com ciclos) cumprirem a meta. Esse trabalho volta à Construction (Code Generation e Build and Test da U4) quando o fundador decidir a F7, sem bloquear os estágios da Operation que não ligam a flag.
2. **Carga (AC3.11.1, AC3.11.2, NFR1, U2-NFR1.9):** o responsável aplica o texto do `load-test.yml` (`ci-config.md`, seção 2.1) e dispara o `pnl-by-mode` num SHA com a CI verde; a implantação na mainnet fica bloqueada até `performance-validation` registrar os grupos U2 e U3 verdes e a medição na devnet.
3. **TGT-E2E-FLAKY:** o defeito é aberto e investigado com as ações de `ci-config.md`, seção 6; até a correção, toda repetição do `e2e` é registrada e só um run verde vale para ponto de controle ou implantação.
4. **Passo `node --test` no `ci.yml`:** o responsável aplica o texto de `ci-config.md`, seção 2.2, junto com o `load-test.yml`.
5. **Os 16 `Unverified`** continuam com os donos da seção 6 e são conferidos antes da mainnet.

**Se o fundador preferir a leitura literal da regra**, a transição **para aqui**, e o próximo passo é voltar à Code Generation da U4 com a decisão F7 e à investigação do TGT-E2E-FLAKY, deixando a Operation esperar.

## 8. Aprovação humana

- [ ] O fundador aceita a transição condicional da seção 7, com as condições 1 a 5. Se não aceitar, a transição para, e o próximo passo é a U4 (F7) e o TGT-E2E-FLAKY na Construction.

O registro do `PHASE_VERIFIED` na trilha de auditoria é feito pelo motor, não por este documento.

## Sources

- `construction/build-and-test/cross-unit-traceability.md` (achados F-T1 a F-T6, resultado de 134 de 177)
- `construction/u1-mode-skeleton/code-generation/traceability.json`, `u2-real-bot-pnl/…`, `u3-pnl-views/…`, `u4-own-quantity-cycle/…` (editados neste estágio, seção 1)
- `construction/u2-real-bot-pnl/code-generation/code-summary.md` (C10 V2: saída de `RealBotPnlQueryV1`); `git log --diff-filter=D` (commit `4a2cf7d`)
- `construction/build-and-test/build-and-test-summary.md` (matriz de 301 alvos) e `test-results.md` (seções 9 e 10)
- `construction/ci-pipeline/ci-config.md` e `quality-gates.md`
- `construction/ci-pipeline/ci-pipeline-questions.md` (Q5)
- `aidlc engine sensor-traceability --stage build-and-test` e `--stage code-generation` por Unit, executados em 2026-11-25
- `team.md` (Testing Posture, Deployment), `project.md` (Mandated, Corrections de 2026-11-20)

## 8. Decisão do fundador (2026-11-25)

O fundador aprovou a **transição condicional** de Construction para Operation, com as 5 condições da seção 7. As condições continuam valendo nos estágios de Operation e são conferidas antes de ligar a flag `memetrade.real.own-quantity-sell` e antes de qualquer implantação na mainnet.
