# Pipeline de CI/CD — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` (R-01 a R-06) e da decisão do fundador F4 (`infrastructure-specification.md`): o valor da flag fica por ambiente, no `tfvars`.

A U4 não muda os jobs da CI. Os testes novos rodam no job `backend` que já existe, inclusive o teste das regras de alerta com o `promtool` por Testcontainers [Q3]. As regras novas entram num arquivo que o `promtool check rules` do `ci.yml` já confere. O `scripts/verify.sh` não muda: com a flag desligada por padrão, o comportamento de ponta a ponta é o de hoje. Nenhum arquivo de `.github/workflows/` muda.

A U4 tem duas entregas separadas na nuvem [Q1]:

1. a **implantação do código**, com as duas variáveis da flag no padrão `false`;
2. a **virada da flag**, só depois da Performance Validation do 261113: uma mudança no `tfvars` do ambiente alvo, sem commit, em implantações só da flag, primeiro a API e depois o worker [F4].

## Estágios da CI e portões

| Job ou workflow | O que roda para a U4 | Portão | NFR |
|---|---|---|---|
| `backend` | `mvnw verify` da API. **Spotless.** **Testes novos:** `OwnSellCapTest`, `OwnHoldingsStreaksTest`, `OwnQuantityMetricsTest`, `OwnQuantityMetricsIT`, `RealBotHoldingsIsolationIT`, `RealBotHoldingsTenantIT`, `RealBotHoldingsMeasurementIT` (só registra o p99,9), `RealBotHoldingsOrderPathParityIT`, `RealOpenSellsPlanIT`, `Migration0026IT`, `Migration0026LockTimeoutIT`, `Migration0027IndexRestartIT`, `OwnQuantitySellDefaultIT`, `OwnQuantityExitsIT`, `OwnQuantityLogRedactionTest`, `RenewMarkOnSellIT`, `OwnQuantityArchitectureTest`, `RealOpsOwnHoldingsAlertRuleIT` (T20), `CiPromtoolDigestTest`, `AlertRuleTestAnnotationsTest`. **Testes que mudam:** `RealOpsAlarmParityTest` (`hasSize(16)`) e `AnonymizationMigrationIT` (desfaz e reaplica a `0026` e a `0027`). **Ainda no job:** JaCoCo ≥ 80%, enforcer, `docker compose config` e `promtool check rules` (`real-ops-alerts.yml` com 16 regras). | qualquer falha deixa o commit vermelho | NFR2.7, NFR2.12, NFR7.1 a NFR7.4, NFR7.6 |
| `frontend` | lint, formatação, tipos e Jest do `apps/web` (aviso do `BotList.tsx`, T15 a T17), piso de 80% | idem | NFR5.1, NFR6.1, NFR7.3 |
| `contract` | testes de contrato e `check:api` do `apps/web` (`BotPage.realOwnQuantitySell`, C7) | quebra de contrato falha | NFR6.2 |
| `smoke` | `bash scripts/verify.sh`, sem mudança, com a flag desligada | código de saída 0 | NFR7.5 |
| `admin`, `txsigner` | sem mudança (a U4 não toca a administração nem o TxSigner) | idem | NFR3.3 |
| `e2e` (só no push para `main`, depois dos seis jobs) | `bash scripts/verify.sh` de novo | código de saída 0 | NFR7.5 |
| workflow `security` | Gitleaks, CodeQL, OWASP Dependency-Check, `pnpm audit`; nenhuma dependência nova | Crítico ou Alto bloqueia | — |
| workflow `load-test` (manual) | sem mudança; a U4 não traz cenário novo | — | NFR1.6 |
| Testes do Terraform e Checkov (manuais) | `terraform fmt -check`, `terraform validate`, `terraform test` e Checkov em `infra/terraform/`, rodados à mão antes do commit que muda `main.tf`, `app-metrics.tf` e `real-ops.tf`. Levá-los à CI continua pendência do responsável (`team.md`). | rodados e verdes antes do commit | NFR8.2, NFR8.4 |

**T20 dentro do `backend`.** O teste lê o digest `prom/prometheus@sha256:...` do passo `Alert rules (promtool)` do `ci.yml` e exige um único valor. Ele roda o `promtool test rules` num contêiner do Testcontainers. O runner `ubuntu` do job já tem Docker para os outros testes de integração. O `ci.yml` continua o único lugar do digest; a lista de arquivos do laço do `promtool` não muda [Q3].

## Testes do Terraform que mudam

| Arquivo | `run` | Asserção nova |
|---|---|---|
| `tests/observability_metrics.tftest.hcl` | `every_alarm_reads_a_published_metric` | Os nomes `eureka-real-real-ops-own-holdings-unavailable` e `eureka-real-real-ops-own-holdings-stuck-bots` estão em `alarm_names`. |
| idem | idem | `real_own_holdings_reads_total`, `real_own_holdings_unavailable_total` e `real_own_holdings_stuck_bots` estão em `app_metric_names`, e `real_cycle_sell_capped_total` não está. |
| idem | idem | `app_metric_dimension_sets["real_own_holdings_unavailable_total"] == ["reason"]`; os conjuntos de `reads` e de `stuck_bots` são `[""]`. |
| idem | idem | `alarm_expressions["eureka-real-real-ops-own-holdings-unavailable"] == ["IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)"]`, pela saída nova de `app-metrics.tf`. |
| idem | idem (asserções que já existem) | Sem mudança de texto, os dois alarmes passam pelas conferências genéricas: inventário igual aos blocos; métrica e dimensões na lista; períodos de 600 s e 300 s ≥ 180 s; um único tópico SNS; sidecar das duas famílias com exatamente a lista. |
| idem | `own_quantity_flag_default` (novo, `command = apply`, com os mesmos provedores simulados) | Sem as variáveis: `module.services.api_worker_container_environment["api"]["MEMETRADE_REAL_OWN_QUANTITY_SELL"] == "false"` e o mesmo para `["worker"]`; `!contains(keys(module.services.api_worker_container_environment["metrics-agent"]), "MEMETRADE_REAL_OWN_QUANTITY_SELL")`, que prova que a chave não veio de `app_environment` (NFR8.4, R-02). |
| idem | `own_quantity_flag_api_only` (novo) | Com `real_own_quantity_sell_api = true`: `api` dá `"true"` e `worker` dá `"false"`. Isso prova que cada contêiner tem a própria chave. |
| idem | `own_quantity_flag_worker_without_api` (novo) | Com `real_own_quantity_sell_worker = true` e a API em `false`: `expect_failures = [var.real_own_quantity_sell_worker]` (a validação da F4 recusa). |
| `tests/services_secrets.tftest.hcl` | sem mudança | A conferência de nomes de segredo em variável comum já cobre a flag (o nome não casa com o filtro). |

As asserções leem só saídas de módulo, como os testes que já existem; nenhuma lê `local.*` (R-02). O resultado do `terraform test` rodado à mão antes do commit vai para o registro do Build and Test da U4.

Nenhum teste do Terraform fica mais frouxo. A contagem de painéis (3) e de consultas salvas (5) continua.

## Ordem dos commits

| Passo | Conteúdo | Portão |
|---|---|---|
| 1 | Testes antes do código (`team.md`, Testing Posture): T1 a T20, `OwnSellCapTest`, os testes das migrações e o arquivo de teste do `promtool` | falham sem o código da U4 (NFR7.1) |
| 2 | Migrações `0026` e `0027`; `openSells`; `EpochAttribution` e `RealBotHoldingsReader`; corte, métricas, `OwnHoldingsStep`, `CycleRunner`, `RealCycle`, `DecisionJson` | `ci` verde |
| 3 | Regras em `real-ops-alerts.yml`, alarmes em `real-ops.tf`, lista, inventário e saída `alarm_expressions` em `app-metrics.tf`, saída `api_worker_container_environment` em `modules/services/outputs.tf`, as duas variáveis com a validação e os dois mapas em `main.tf`, as linhas `false` nos `*.tfvars.example`, `RealOpsAlarmParityTest`, testes do Terraform rodados à mão | `ci` verde; `terraform test` verde |
| 4 | `BotPage` e `BotList.tsx`; seções 10 a 13 do runbook | `ci` verde |

Todo ponto de controle e toda implantação usam só um commit com a CI verde (`project.md`, Mandated). Nenhum commit liga a flag: o valor ligado só existe no `tfvars` de cada ambiente (F4).

## Artefatos

| Artefato | Origem | Marca |
|---|---|---|
| Imagem `memetrade-api` (API, worker e `db-migrate`, com a `0026` e a `0027` em `classpath:db/changelog/`) | `Dockerfile` que já existe | SHA do commit |
| Imagem `web` (aviso do `BotList.tsx`) | `Dockerfile` que já existe | SHA do commit |
| Imagem `txsigner` | sem mudança de código; publicada pelo fundador para o SHA, como em toda implantação | SHA do commit |

Nenhum repositório de imagem, registro ou artefato novo.

## Implantação do código da U4

O `deploy.yml` (manual, GitHub Environments `devnet` e `mainnet`, OIDC) continua uma pendência do responsável e ainda não existe em `.github/workflows/`. Quando existir, a U4 segue o fluxo de `team.md` e do `cd-config.md` do intent `261113-trading-real-bot`. Enquanto não existir, vale o caminho manual do fundador (seção "Caminho manual do fundador", R-04).

| Passo | Ação | Portão |
|---|---|---|
| 1 | Commit de `main` com `ci` e `security` verdes e o Build and Test da U4 aprovado | `project.md`, Mandated |
| 2 | O fundador revisa o `terraform plan` e aplica o Terraform com MFA, com o `tfvars` do ambiente sem mudança nas duas variáveis (padrão `false`). **Revisões de definição de tarefa:** a do `api-worker` acrescenta a variável `"false"` nos contêineres `api` e `worker` e muda o texto do sidecar; a do `txsigner` muda só o sidecar. **Alarmes:** os dois entram e ficam `OK` (`notBreaching`) até a imagem nova publicar as métricas. Nenhuma revisão entra em serviço sozinha (`ignore_changes`). | `terraform plan` sem outra mudança de família, papel, segredo ou imagem |
| 3 | **Revisão do sidecar do `txsigner`: fora do caminho crítico (R-03).** Ela não é passo nem pré-condição da U4. Entra na próxima implantação regular do TxSigner, pelo passo do fundador com MFA, que parte da revisão do Terraform. Se esse passo rodar nesta implantação por outro motivo (por exemplo, a imagem do TxSigner do SHA que o `deploy.yml` confere), a revisão vai junto, sem mudar nada no TxSigner além do agente. | nenhum para a U4 |
| 4 | `deploy.yml` com `mode=deploy`, primeiro passo: a tarefa avulsa `db-migrate` com a imagem nova aplica, nesta ordem, `0026-bot-cycle-outcome-not-valid`, `0026-bot-cycle-outcome-validate` e `0027-real-decision-request-bot-pending-sell-idx`. A imagem antiga continua no ar. | código de saída 0. Uma falha (`55P03` ou índice `INVALID`) para a implantação antes das imagens: ver "Falha da migração". |
| 5 | Atualização rolling de `api-worker` com a imagem nova e a revisão do passo 2 (tarefa nova saudável antes de a antiga sair, circuit breaker com volta automática). `web` recebe a imagem nova. | prontidão em `/actuator/health/readiness`, sem mudança |
| 6 | Smoke e janela de observação de 10 minutos. As 8 séries novas aparecem com 0 no worker, e `real_own_holdings_reads_total` não sobe (flag desligada). | smoke verde |
| 7 | Devnet primeiro, depois o mesmo SHA na mainnet, com a aprovação do fundador no Environment `mainnet` | NFR8.3 |

Nenhuma flag do REAL muda nesta implantação. Ligar a flag da U4 é a virada abaixo.

## Virada da flag (ligar)

**Pré-condições, todas registradas na nota da implantação:**

1. **Performance Validation do 261113 aprovada.** Hoje ela está pendente: o texto do `load-test.yml` ainda não foi aplicado pelo responsável. Sem ela, a flag não liga em nenhum ambiente (NFR2.8 (d)).
2. A medida do NFR1.5 (p99,9 ≤ 250 ms no Build and Test), ou a decisão do fundador sobre ela.
3. A aprovação do fundador, que acompanha a janela de observação e os 30 minutos seguintes (`team.md`).
4. O código da U4 já em serviço no ambiente, com o smoke verde.
5. **Só para a mainnet:** a prova do alarme da razão na devnet (R-05). Com a flag ligada no worker da devnet, por 1 hora, `real_own_holdings_reads_total` tem ponto em todo período de 600 s, um contador parado publica delta 0, e `e1` dá um valor em todo período (0 sem falhas). O resultado fica na nota da implantação da devnet.

**Ordem**, primeiro na devnet e depois na mainnet com o mesmo SHA de imagem. Cada implantação muda só a flag, nunca junto com código, e a virada não tem commit: muda só o `tfvars` do ambiente alvo, fora do repositório [F4].

| Passo | Ação | Portão |
|---|---|---|
| 1 | Conferir as pré-condições 1 a 4, e a 5 na mainnet. | todas cumpridas |
| 2 | **Conferência só de leitura, antes da implantação da API.** Linha de base no ambiente: as duas contagens do passo 4 e os alarmes `real_ops_own_holdings_*` em `OK`. | registradas na nota |
| 3 | **Implantação só da API.** No `tfvars` do ambiente alvo, `real_own_quantity_sell_api = true`. O fundador revisa o `terraform plan` e aplica com MFA; o `plan` mostra só `MEMETRADE_REAL_OWN_QUANTITY_SELL` = `"true"` no contêiner `api`. Depois, `gh workflow run deploy.yml -f sha=<SHA em serviço> -f environment=<env> -f mode=deploy` (ou o caminho manual). | `plan` com uma única diferença; smoke; janela de 10 min; o aviso aparece na lista de bots; o ciclo continua o de hoje |
| 4 | **Segunda conferência, logo antes da implantação do worker** (runbook, seção 11), numa sessão `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY`: `SELECT now() AS t1` (anotar); `SELECT count(*) FROM real_decision_request WHERE side = 'SELL' AND state = 'PENDING'`; `SELECT count(*) FROM real_order WHERE side = 'SELL' AND bot_id IS NOT NULL AND state IN ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS')`; `COMMIT`. | as duas contagens dão 0. Senão, o fundador espera (uma ordem `AMBIGUOUS` segue o runbook do 261113 até se resolver) e repete o passo. |
| 5 | **Implantação só do worker.** No `tfvars` do ambiente alvo, `real_own_quantity_sell_worker = true`; `terraform plan` e `apply` com MFA; `deploy.yml` com `mode=deploy` e o mesmo SHA de imagem (ou o caminho manual). | `plan` com uma única diferença no contêiner `worker`; smoke; janela de 10 min |
| 6 | **Conferência depois da troca**, quando a última tarefa antiga saiu do rolling. Na mesma sessão só de leitura, repetir as duas contagens e rodar `SELECT r.decision_request_id, r.bot_id, r.created_at FROM real_decision_request r JOIN decision d ON d.decision_id = r.decision_id WHERE r.side = 'SELL' AND r.created_at >= :t1 AND NOT jsonb_exists(d.context, 'ownQuantity')`. Cada linha devolvida vai para a nota (efeito do T19: vende como hoje, e o excesso vai para o rateio da U2). | `real_own_holdings_reads_total` sobe; alarmes em `OK`; `pnl_real_reconciliation_mismatches_total` em 0 |
| 7 | Na devnet: a prova do alarme da razão (pré-condição 5) e a revisão de `STALE_SNAPSHOT` (runbook, seção 13). Depois, na mainnet, com o mesmo SHA de imagem, do passo 1 em diante, mudando o `tfvars` da mainnet e com a aprovação do Environment `mainnet`. | prova registrada; razão ≤ 1% |

**Regras da virada:**

- A mainnet só liga quando o `tfvars` dela muda. Um `apply` de outra mudança na mainnet mantém os valores dela, então não há regra de procedimento sobre outros `apply` [F4].
- A validação de `real_own_quantity_sell_worker` recusa, já no `plan`, o worker ligado com a API desligada. Isso vale na virada e no desligamento.
- As consultas rodam com o login só de leitura e pelo caminho de manutenção do `rollback-runbook.md` do 261113. `jsonb_exists` substitui o operador `?`, que um cliente JDBC trata como marcador.
- Ligar a flag não muda `MEMETRADE_REAL_ENABLED`, `TXSIGNER_REAL_ENABLED`, o kill switch nem as liberações. O saque continua disponível durante toda a virada.

## Desligar a flag (rollback do comportamento)

| Passo | Ação |
|---|---|
| 0 | Se houver venda errada em andamento, acionar primeiro o kill switch REAL na administração (segundos). O saque continua disponível. |
| 1 | Implantação só do worker: no `tfvars` do ambiente, `real_own_quantity_sell_worker = false`; `terraform apply` com MFA; `deploy.yml` com o SHA em serviço (ou o caminho manual). O ciclo seguinte volta ao de hoje, sem migração. |
| 2 | Implantação só da API: `real_own_quantity_sell_api = false`, e o aviso some. Desligar a API antes do worker é recusado pela validação. |
| 3 | As notas e os resultados gravados (`REAL_NO_OWN_QUANTITY`, `REAL_OWN_QUANTITY_UNAVAILABLE`, `context.ownQuantity`) ficam como histórico (BR5.4). |

A ordem inversa evita o estado em que o worker corta pela posição própria sem a API mostrar o aviso. Desligar um ambiente não muda o outro.

## Rollback da imagem

| Passo | Ação |
|---|---|
| 1 | Se o REAL estiver ligado no ambiente, desligar primeiro a flag do REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) ou acionar o kill switch REAL (`team.md`). O saque continua disponível durante todo o rollback. |
| 2 | `deploy.yml` com `mode=rollback` (ou o caminho manual): volta só a imagem de `api-worker` (e de `web`) para a tag anterior e mantém as flags da revisão mais recente (`rollback-runbook.md` do 261113). |
| 3 | Não reverter a `0026` nem a `0027`. A imagem anterior só grava valores da lista antiga, que a regra nova aceita, e não usa o índice (`Migration0026IT`). Em produção nenhuma migração volta. |
| 4 | Uma imagem anterior à U4 ignora `MEMETRADE_REAL_OWN_QUANTITY_SELL`, mesmo com `"true"`: ela não tem a propriedade e vende como antes. Antes de voltar à imagem da U4, desligar a flag do worker no `tfvars` (passo 1 de "Desligar") e refazer a virada a partir do passo 4. |
| 5 | Os alarmes, as métricas e as revisões do sidecar podem ficar: sem a imagem nova, os contadores não chegam e os alarmes ficam `OK` (`notBreaching`). |
| 6 | A implantação de volta só termina quando o smoke passa. |

## Caminho manual do fundador (enquanto o `deploy.yml` não existe)

Este caminho vale para a implantação do código, para cada implantação da virada, para o desligamento e para o rollback da imagem (R-04). Ele roda da máquina do fundador, com MFA, sempre com um commit de `main` com a CI verde e com o mesmo SHA de imagem nos dois ambientes. Ele repete, à mão, o que o `deploy.yml` faria, no padrão do passo 4.6 do `cd-config.md` do 261113.

| Passo | Ação | Portão |
|---|---|---|
| 1 | `terraform plan -var-file=<tfvars do ambiente>` revisado e `terraform apply` com MFA. Na virada, só a variável da flag muda. | `plan` com só as diferenças esperadas |
| 2 | Base da revisão: a revisão mais recente da família `api-worker` registrada pelo Terraform (com a flag do ambiente). Revisão nova = essa base com a imagem dos contêineres `api` e `worker` trocada pelo digest do SHA (na virada, o SHA em serviço). Família, papéis, segredos e flags vêm da base. | a revisão nova tem o digest do SHA nos dois contêineres e a flag esperada |
| 3 | Só na implantação do código: `aws ecs run-task` da família `db-migrate` com o digest do SHA, nas sub-redes privadas e no grupo de segurança da aplicação; esperar o fim e ler o código de saída. | código de saída 0; senão, "Falha da migração" |
| 4 | `aws ecs update-service` do serviço `api-worker` com a revisão nova (100/200, circuit breaker); `aws ecs wait services-stable`; conferir em `describe-services` que a implantação `PRIMARY` usa a revisão nova e terminou `COMPLETED`. Na implantação do código, `web` recebe a imagem do SHA da mesma forma. | outra revisão em serviço = o circuit breaker voltou: parar e investigar |
| 5 | Prontidão (`/actuator/health/readiness` pelo balanceador) e o smoke do `rollback-runbook.md`; janela de observação de 10 min e os 30 min seguintes com o fundador presente. | smoke verde |
| 6 | Nota da implantação: SHA, revisão, ambiente, valores da flag, resultados das conferências e a hora. | registrada |

O papel de implantação do GitHub não é usado. A credencial é a sessão com MFA do fundador, como no passo do TxSigner. Este caminho não liga o REAL e não toca no TxSigner.

## Falha da migração (runbook, seção 12)

| Passo | Ação |
|---|---|
| 1 | O `db-migrate` sai com código diferente de zero; a atualização rolling não começa e a imagem antiga continua no ar. |
| 2 | **`55P03` (`lock_not_available`) na `0026`:** no log do `db-migrate`, ver qual changeset falhou. Com o login dono, conferir em `pg_stat_activity` a transação que segurava `bot_cycle` e esperar que ela termine. Nunca matar à força a transação do ciclo ou da fila. |
| 3 | **`0027` interrompida:** conferir `select to_regclass('real_decision_request_bot_pending_sell_idx') is not null as exists, (select indisvalid from pg_index where indexrelid = to_regclass('real_decision_request_bot_pending_sell_idx')) as valid;`. `exists = true` com `valid = false` é o resto de uma criação interrompida. |
| 4 | Disparar de novo a mesma implantação com o mesmo SHA. Se a falha foi no changeset 1, a `0026` recomeça do changeset 1. Se foi no 2, só o 2 roda. O `DROP INDEX CONCURRENTLY IF EXISTS` da `0027` apaga o índice `INVALID`. |
| 5 | Trava do changelog presa depois de o processo morrer (`locked = true` e nenhuma tarefa `db-migrate` no ECS): liberar só ela, com o login dono, como na seção 9 (`update databasechangeloglock set locked = false, lockgranted = null, lockedby = null where id = 1;`), e repetir o passo 4. Nunca aplicar a migração à mão fora do Liquibase. |

## Segredos na CI/CD

| Item | Decisão |
|---|---|
| Segredos novos | Nenhum. A flag é variável comum, sem valor secreto. |
| Segredos efêmeros do `verify.sh` | Os de hoje, gerados por execução. |
| Variáveis novas nos workflows | Nenhuma. O T20 lê o digest do `ci.yml`, que não muda. |
| Credencial AWS | OIDC no `deploy.yml`, quando existir; nenhuma chave longa; `id-token: write` só nesse workflow. `terraform apply` só pelo fundador, com MFA. |
| Proteção de segredos | Gitleaks no `.githooks/pre-commit` e no workflow `security`, sem mudança. |

## Sources

- `infrastructure-design-questions.md`: Q1 a Q3 e o resumo confirmado [Q1] [Q2] [Q3].
- `reviews/review-01.md` (R-01 a R-06) e a decisão do fundador F4 de 2026-11-24.
- `construction/u4-own-quantity-cycle/nfr-design/reliability-design.md` (migrações, `lock_timeout`, repetição, flag (a) a (e), consultas com `jsonb_exists`, runbook), `observability-design.md` (T20, paridade, teste offline), `logical-components.md` (ordem de construção, A5, A7, A16), `security-design.md` (flag e teste offline).
- `construction/u4-own-quantity-cycle/nfr-requirements/reliability-requirements.md` (NFR2.7, NFR2.8, NFR7.1 a NFR7.6), `performance-requirements.md` (NFR1.5), `scalability-requirements.md` (NFR8.3, NFR8.4).
- `construction/u2-real-bot-pnl/infrastructure-design/cicd-pipeline.md` (formato, passos de implantação, falha do índice, trava do changelog).
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/cd-config.md` (4.6, guarda de configuração, seção 7 "Mudar uma flag"), `rollback-runbook.md`, `operation/performance-validation/test-results.md` (situação pendente).
- Arquivos lidos: `.github/workflows/ci.yml` (jobs, passo `Alert rules (promtool)` com o digest), lista de `.github/workflows/` (`ci.yml`, `load-test.yml`, `security.yml`; sem `deploy.yml`), `infra/terraform/tests/observability_metrics.tftest.hcl`, `offline_plan.tftest.hcl`, `services_secrets.tftest.hcl`, `infra/terraform/main.tf`, `modules/services/api-worker.tf`.
- `aidlc/spaces/default/memory/team.md`: Way of Working (workflows só do responsável, pendências da CI), Testing Posture (testes da IaC à mão), Deployment (flags, rollback, passo com MFA); `project.md`: Mandated (CI verde, saque sempre disponível).

## Assumptions & Open Questions

1. As asserções da flag leem a saída `api_worker_container_environment` do módulo `services` (R-02). [assumption] Os provedores simulados do `observability_metrics.tftest.hcl` dão uma `container_definitions` conhecida em `command = apply`, como já acontece com o `services_secrets.tftest.hcl`.
2. [assumption] A Performance Validation do 261113 ainda está pendente (`test-results.md`). Por isso a virada fica bloqueada por tempo indeterminado. A implantação do código com a flag `"false"` não depende dela.
3. A validação da variável do worker (F4) substitui a regra de procedimento "nenhum outro apply na mainnet" e a sugestão de `precondition` da primeira versão, que saem do desenho.
4. [assumption] O caminho manual do fundador usa as mesmas variáveis dos Environments (`cd-config.md` 4.3), lidas do estado do Terraform. O smoke manual é o do `rollback-runbook.md` do 261113; este estágio não cria script novo.
5. [assumption] O acesso do fundador ao banco da nuvem para as consultas e para a recuperação usa o caminho de manutenção do `rollback-runbook.md` do 261113; este estágio não cria caminho novo.
