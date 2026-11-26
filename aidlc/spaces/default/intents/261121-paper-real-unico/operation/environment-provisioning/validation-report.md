# Relatório de validação da infraestrutura — intent `261121-paper-real-unico`

Data: 2026-11-25. Conversa: português (Brasil). Commit de referência: `09de0a8daea32fc9ec9d8bfe380a9eac9f3b6c29` (`main`).

**Resumo.** Nada foi provisionado (Q1): não existe conta AWS, a região não foi escolhida e o Terraform e o Checkov não estão instalados nesta máquina (`which terraform checkov` sem resultado). Esta é uma **validação estática, por leitura dos arquivos**: cada conferência dá o valor esperado (do desenho), o que está no arquivo e linha, e o veredito. Nenhum comando de Terraform, Checkov ou AWS rodou. Nenhum arquivo de código, IaC ou workflow mudou.

| Veredito | Quantidade |
|---|---|
| `Met` (conferido por leitura) | **32** (V-01 a V-32; a V-14 com a observação O-1) |
| `Unverified` (precisa de ferramenta ou de nuvem) | **9** (U-01 a U-09) |
| `Not Met` | 0 |

## 1. Conferências estáticas

### 1.1 Variáveis da flag (F4)

| ID | Conferência | Esperado (desenho) | Encontrado (arquivo:linha) | Veredito |
|---|---|---|---|---|
| V-01 | Variável da API | `real_own_quantity_sell_api`, `type = bool`, `default = false` (U4 spec, "main.tf: a flag") | `infra/terraform/main.tf:155-159`: `type = bool`, `default = false`, descrição "…turned on per environment in its tfvars, after the 261113 Performance Validation" | `Met` |
| V-02 | Variável do worker | `real_own_quantity_sell_worker`, `type = bool`, `default = false` | `main.tf:161-164`: `type = bool`, `default = false` | `Met` |
| V-03 | Expressão da validação | `!var.real_own_quantity_sell_worker \|\| var.real_own_quantity_sell_api`, com a mensagem "turn the api on first and the worker off first (U4 NFR2.8)" | `main.tf:166-169`: `condition = !var.real_own_quantity_sell_worker \|\| var.real_own_quantity_sell_api`; `error_message` idêntico ao desenho. Tabela-verdade: (api, worker) = (F, F) ok, (T, F) ok, (T, T) ok, (F, T) recusado | `Met` |
| V-04 | Versão que aceita referência a outra variável numa `validation` | Terraform `>= 1.9` | `main.tf:11`: `required_version = ">= 1.9.0"` | `Met` |

### 1.2 Mapeamento para os contêineres

| ID | Conferência | Esperado | Encontrado | Veredito |
|---|---|---|---|---|
| V-05 | Chave da API | `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_api)` em `api_environment` | `main.tf:468`, dentro de `api_environment` (`main.tf:443-469`) | `Met` |
| V-06 | Chave do worker | `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_worker)` em `worker_environment` | `main.tf:475`, dentro de `worker_environment` (`main.tf:471-476`) | `Met` |
| V-07 | A chave **nunca** em `app_environment` | ausente | `main.tf:428-441`: 10 chaves fixas e `MEMETRADE_REAL_FOUNDER_USER_ID` condicional; nenhuma `MEMETRADE_REAL_OWN_QUANTITY_SELL` | `Met` |
| V-08 | `tostring` dá `"false"`/`"true"` | texto, porque o ambiente do ECS só aceita texto | `tostring(<bool>)` nas duas linhas; o `application.yml` liga com `${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}` (U4 spec) | `Met` |
| V-09 | Cada mapa vai só para o seu contêiner | `api` = `app` + `api`; `worker` = `app` + `worker`; sidecar sem os dois | `modules/services/api-worker.tf:78` (`merge(var.app_environment, var.api_environment)`), `:96` (`merge(var.app_environment, var.worker_environment, {...})`); `metrics-agent.tf:126-135` (só `CW_CONFIG_CONTENT`, `PROMETHEUS_CONFIG_CONTENT` e `MEMETRADE_REAL_NETWORK`) | `Met` |
| V-10 | Saída para o teste | `api_worker_container_environment` decodifica `container_definitions`, só `environment` | `modules/services/outputs.tf:80-86`: `{ for c in jsondecode(...) : c.name => { for e in try(c.environment, []) : e.name => e.value } }`; nenhum `secrets` | `Met` |
| V-11 | Nome da flag não parece segredo | o nome não casa com o filtro do `services_secrets.tftest.hcl` | filtro em `tests/services_secrets.tftest.hcl:122`: `PASSWORD\|SECRET_KEY\|SIGNING_KEY\|HMAC_KEY$\|API_KEY\|_TOKEN$\|RPC_.*_URL\|^NEXT_PUBLIC_`; `MEMETRADE_REAL_OWN_QUANTITY_SELL` não casa | `Met` |

### 1.3 `tfvars.example`

| ID | Conferência | Esperado | Encontrado | Veredito |
|---|---|---|---|---|
| V-12 | Devnet | as duas linhas com `false` e o comentário da ordem | `infra/terraform/environments/devnet.tfvars.example:33-37`: comentário (API primeiro, worker depois; worker desliga primeiro) e `real_own_quantity_sell_api    = false`, `real_own_quantity_sell_worker = false` | `Met` |
| V-13 | Mainnet | idem, com "after the same SHA passed on the devnet" | `environments/mainnet.tfvars.example:33-37`: o mesmo, com a menção à devnet | `Met` |

### 1.4 Testes do Terraform presentes (execução em U-03)

| ID | Conferência | Esperado (U4 `cicd-pipeline.md`, "Testes do Terraform que mudam") | Encontrado em `infra/terraform/tests/observability_metrics.tftest.hcl` | Veredito |
|---|---|---|---|---|
| V-14 | Asserções da flag | `own_quantity_flag_default` (`command = apply`), `own_quantity_flag_api_only`, `own_quantity_flag_worker_without_api` com `expect_failures = [var.real_own_quantity_sell_worker]` | `:248-259` (api `"false"`, worker `"false"`, chave ausente do `metrics-agent`); `:261-276` (api `"true"`, worker `"false"`); `:278-287` (`command = plan`, `expect_failures = [var.real_own_quantity_sell_worker]`) | `Met` com observação O-1 |
| V-15 | Asserção do alarme do P&L REAL (U2) | alarme e métrica presentes | `:160-163` | `Met` |
| V-16 | Asserções dos alarmes da U4 | os 2 nomes em `alarm_names`; as 3 métricas na lista e `real_cycle_sell_capped_total` fora; dimensões `["reason"]`, `[""]`, `[""]`; texto exato da expressão por `alarm_expressions` | `:167-170`, `:171-179`, `:180-187`, `:188-191`: as quatro presentes, com o texto `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)` | `Met` |
| V-17 | Saída usada pela asserção da expressão | `alarm_expressions` em `app-metrics.tf` | `modules/observability/app-metrics.tf:279-287` | `Met` |

**O-1 (observação, sem mudança neste estágio).** O `cicd-pipeline.md` da U4 (linha 37) diz que a asserção `!contains(keys(...["metrics-agent"]), "MEMETRADE_REAL_OWN_QUANTITY_SELL")` "prova que a chave não veio de `app_environment`". Pela leitura de `metrics-agent.tf:126-135`, o sidecar **nunca** recebe `app_environment` (só copia `MEMETRADE_REAL_NETWORK` por `lookup`), então essa asserção passa mesmo se a chave entrar em `app_environment`. O `own_quantity_flag_api_only` também passaria, porque `worker_environment` sobrescreve `app_environment` no `merge`. Hoje a regra é cumprida (V-07), mas só a leitura a garante. Sugestão para a próxima volta da U4 à Construction (dono: fundador): um teste de texto, no estilo do `RealOpsAlarmParityTest`, que recuse a chave dentro do bloco `app_environment` de `main.tf`, ou uma saída de módulo com as chaves de `var.app_environment`.

### 1.5 Lista de permissão e alarmes contra os desenhos

| ID | Conferência | Esperado | Encontrado | Veredito |
|---|---|---|---|---|
| V-18 | Lista de permissão | U2: `pnl_real_reconciliation_mismatches_total` em `[[]]`; U4: `real_own_holdings_reads_total` e `real_own_holdings_stuck_bots` em `[[]]`, `real_own_holdings_unavailable_total` em `[["reason"]]`; `real_cycle_sell_capped_total` fora | `modules/observability/app-metrics.tf:71`, `:74`, `:75`, `:79`; `real_cycle_sell_capped_total` só no comentário `:73` | `Met` |
| V-19 | Inventário dos alarmes | os 3 nomes em `alarm_resource_names` e os 3 recursos em `metric_alarms` | `app-metrics.tf:162-163` e `:190-192` | `Met` |
| V-20 | Alarme da razão, `metric_query` | `e1` = `IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`, `return_data = true`; `m1`/`m2` = `real_own_holdings_unavailable_total` com `reason = "BUDGET_EXCEEDED"`/`"READ_FAILED"`; `m3` = `real_own_holdings_reads_total` sem dimensão; `Sum`, `period = 600` nos três; sem `BOT_NOT_OWNED` | `modules/observability/real-ops.tf:226-264`: idêntico, item por item | `Met` |
| V-21 | Alarme da razão, atributos | `evaluation_periods = 2`, `datapoints_to_alarm = 2`, `threshold = 0.05`, `GreaterThanThreshold`, `notBreaching`, tópico único, nome `${var.name}-real-ops-own-holdings-unavailable` | `real-ops.tf:217-225` | `Met` |
| V-22 | Alarme do bot preso | `real_own_holdings_stuck_bots`, sem dimensões, `Minimum`, `period = 300`, 2 de 2, `> 0`, `notBreaching` (U4 `monitoring-design.md`, linha 45) | `real-ops.tf:270-283` | `Met` |
| V-23 | Alarme do P&L REAL (U2) | `pnl_real_reconciliation_mismatches_total`, `Sum`, `period = 240`, 1 avaliação, `> 0`, `notBreaching` (U2 `monitoring-design.md`, linha 44) | `real-ops.tf:198-210` | `Met` |
| V-24 | Regra do projeto para janelas do CloudWatch (agente a 120 s; `notBreaching` com pelo menos 180 s) | períodos ≥ 180 s | 240 s, 600 s, 300 s | `Met` |

### 1.6 Paridade Prometheus × CloudWatch

| ID | Conferência | Esperado | Encontrado | Veredito |
|---|---|---|---|---|
| V-25 | Número de regras `real-ops` | 16 (`RealOpsAlarmParityTest`, `hasSize(16)`) | `infra/observability/real-ops-alerts.yml`: 16 linhas `alert:`; as três deste intent em `:144` (`RealOpsPnlReconciliationMismatch`), `:155` (`RealOpsOwnHoldingsUnavailable`) e `:173` (`RealOpsOwnHoldingsStuckBots`) | `Met` |
| V-26 | Número de alarmes `real-ops` | 16, mesmos nomes em kebab | `real-ops.tf`: 11 blocos `resource`, sendo `real_ops_job_stalled` com `for_each` de 6 trabalhos (`:15-22`: `gates`, `reconciliation`, `kill-switch-cancel`, `txsigner-resend`, `ops-gauges`, `purge`) → 10 + 6 = 16 | `Met` |
| V-27 | Teste de paridade | `assertThat(rules).hasSize(16)` e conferências próprias da razão e do bot preso | `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java:138` (`hasSize(16)`), `:208-242` (razão: `for: 15m`, `cloud_periods 2`, expressão, 4 ocorrências de `period      = 600`, sem `BOT_NOT_OWNED`), `:245-271` (bot preso: `Minimum`, `period 300`, sem `for`). Verde na CI: job `backend` do run `ci` `45550427456` (commit `09de0a8`, `test-results.md`, seção 9) | `Met` |
| V-28 | Janelas das regras gêmeas | razão: `[10m]`, `for: 15m`, `cloud_periods: "2"`; bot preso: `min_over_time(...[10m])`, `cloud_periods: "1"` | `real-ops-alerts.yml:161-172` e `:177-185` | `Met` |

### 1.7 Migrações e ausência de mudança fora do delta

| ID | Conferência | Esperado | Encontrado | Veredito |
|---|---|---|---|---|
| V-29 | Ordem das migrações | `0023` → `0027` no changelog mestre | `apps/api/src/main/resources/db/changelog/db.changelog-master.yaml:47-55` | `Met` |
| V-30 | Índices sem bloquear escrita | `CREATE INDEX CONCURRENTLY` com `runInTransaction:false` e `DROP ... IF EXISTS` antes | `0023-real-entry-slot.sql:68-76`; `0027-real-decision-request-bot-pending-sell-idx.sql:7-15` | `Met` |
| V-31 | Troca da regra com espera limitada | `SET LOCAL lock_timeout = '5s'` nos dois changesets da `0026`; `NOT VALID` e depois `VALIDATE` | `0026-bot-cycle-own-quantity-outcomes.sql:16-23` e `:28-33` | `Met` |
| V-32 | Nenhuma mudança em IAM, rede, KMS ou segredos | 0 recursos `aws_iam_*`, `aws_security_group*`, `aws_vpc*`, `aws_kms_*`, `aws_secretsmanager_*` novos ou alterados | `git diff 600140e HEAD -- infra/terraform`: os únicos recursos novos são os 3 `aws_cloudwatch_metric_alarm`; os 9 arquivos mudados estão listados em `environment-inventory.md`, seção 2 | `Met` |

Total: 32 conferências `Met`; a V-14 leva a observação O-1.

## 2. O que fica `Unverified`

Cada linha tem um dono e o comando exato. Os comandos do Terraform e do Checkov rodam em Docker, numa cópia de `infra/terraform` fora do repositório (como no 261113, Anexo A), porque o `init` cria `.terraform/` e `.terraform.lock.hcl`.

| ID | Alvo | Por que não rodou | Dono | Comando exato | Esperado |
|---|---|---|---|---|---|
| U-01 | TGT-IAC-1: formatação | Terraform não instalado | `environment-provisioning` (fundador roda à mão; levar à CI é pendência do responsável, `team.md`) | `docker run --rm -v "$PWD/infra/terraform:/w" -w /w hashicorp/terraform:1.9.8 fmt -check -recursive -diff` | saída 0, sem diferença |
| U-02 | TGT-IAC-1: validação | idem | `environment-provisioning` | `cp -r infra/terraform "$TMPDIR/tf" && docker run --rm -v "$TMPDIR/tf:/w" -w /w hashicorp/terraform:1.9.8 init -backend=false && docker run --rm -v "$TMPDIR/tf:/w" -w /w hashicorp/terraform:1.9.8 validate`; o mesmo com `-w /w/org` | "Success! The configuration is valid." nas duas raízes |
| U-03 | TGT-IAC-1 e U4-NFR8.4: testes offline (`mock_provider "aws"`, sem credencial) | idem | `environment-provisioning` | depois do `init` de U-02: `docker run --rm -v "$TMPDIR/tf:/w" -w /w hashicorp/terraform:1.9.8 test` | todos os `run` verdes, inclusive `every_alarm_reads_a_published_metric`, `own_quantity_flag_default`, `own_quantity_flag_api_only` e `own_quantity_flag_worker_without_api` |
| U-04 | TGT-IAC-2: Checkov | Checkov não instalado | `environment-provisioning` | `docker run --rm -v "$PWD/infra/terraform:/w:ro" bridgecrew/checkov:3 -d /w --framework terraform` | 0 falhas; o delta não traz supressão nova (só alarmes, variáveis e saídas) |
| U-05 | Prova do alarme da razão: `real_own_holdings_reads_total` com ponto em todo período de 600 s, contador parado publicando delta 0, `e1` com valor em todo período (0 sem falhas) | não há nuvem; só existe com o worker ligado na devnet | fundador, na devnet, antes da virada da flag na mainnet (`deployment-strategy.md`, seção 3, passo 1; U4 `cicd-pipeline.md`, Virada, passo 7) | com a flag ligada no worker da devnet, depois de 1 hora: `aws cloudwatch get-metric-data --start-time <t0> --end-time <t0+3600> --metric-data-queries '[{"Id":"m1","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_unavailable_total","Dimensions":[{"Name":"reason","Value":"BUDGET_EXCEEDED"}]},"Period":600,"Stat":"Sum"},"ReturnData":false},{"Id":"m2","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_unavailable_total","Dimensions":[{"Name":"reason","Value":"READ_FAILED"}]},"Period":600,"Stat":"Sum"},"ReturnData":false},{"Id":"m3","MetricStat":{"Metric":{"Namespace":"Eureka/App","MetricName":"real_own_holdings_reads_total"},"Period":600,"Stat":"Sum"},"ReturnData":true},{"Id":"e1","Expression":"IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)","ReturnData":true}]'` e `aws cloudwatch describe-alarms --alarm-names <NAME_PREFIX>-real-ops-own-holdings-unavailable --query 'MetricAlarms[0].StateValue'` | `m3` com 6 pontos e `e1` com 6 valores na hora; estado `OK`; resultado na nota da implantação da devnet |
| U-06 | U2-NFR8.2 e U4-NFR8.2: custo por conta (+ ≈ US$ 0,40 e + ≈ US$ 1,90) | não há conta | `environment-provisioning`, depois do primeiro mês de cada conta | `aws ce get-cost-and-usage --time-period Start=<AAAA-MM-01>,End=<AAAA-MM-01 seguinte> --granularity MONTHLY --metrics UnblendedCost --filter '{"Dimensions":{"Key":"SERVICE","Values":["AmazonCloudWatch"]}}'` em cada conta; e `aws budgets describe-budgets --account-id <conta>` | CloudWatch dentro do previsto do 261113 mais ≈ 2,30; conta abaixo de US$ 350 |
| U-07 | Saúde do ambiente depois do `apply` (health checks) | não há nuvem | `environment-provisioning` | os comandos da seção 6 do `validation-report.md` do 261113, mais: `aws ecs describe-services --cluster <NAME_PREFIX> --services api-worker --query 'services[0].{running:runningCount,desired:desiredCount,rollout:deployments[0].rolloutState}'`; `aws elbv2 describe-target-health --target-group-arn <tg api>`; `aws cloudwatch describe-alarms --alarm-name-prefix <NAME_PREFIX>-real-ops- --query 'MetricAlarms[].[AlarmName,StateValue]' --output text` | `running = desired`, `COMPLETED`, alvos `healthy`; os 16 alarmes `real-ops` em `OK` ou `INSUFFICIENT_DATA` |
| U-08 | Logs da implantação da pilha | não há nuvem | `environment-provisioning` | `terraform plan -var-file=environments/<env>.tfvars -out=<env>.plan` e `terraform apply <env>.plan` com saída guardada fora do repositório (`team.md`: planos nunca entram no git); `aws logs filter-log-events --log-group-name /ecs/<NAME_PREFIX>/migrate --filter-pattern '"0027"'` | plano com os 3 alarmes novos e as 2 revisões de definição de tarefa; `db-migrate` com saída 0 até a `0027` |
| U-09 | Limite real de conexões do RDS `app` (base do D-INF2) | não há nuvem | `environment-provisioning`, na primeira conta | sem caminho SQL na nuvem hoje (IR-G3); até lá: `aws rds describe-db-parameters --db-parameter-group-name <NAME_PREFIX>-postgres17 --query "Parameters[?ParameterName=='max_connections'].[ParameterValue]"` e a fórmula da AWS | cerca de 112 na `db.t4g.micro`; com o IR-G3 fechado, `SHOW max_connections;` |

As 9 linhas fecham os 5 `Unverified` que a condição 5 do `cd-config.md` (seção 6) atribui a este estágio (TGT-IAC-1 em U-01 a U-03, TGT-IAC-2 em U-04, U4-NFR8.4 em U-03, U2-NFR8.2 e U4-NFR8.2 em U-06), mais a prova da virada (U-05), os health checks (U-07), os logs da pilha (U-08) e o limite de conexões (U-09).

## 3. Rede, segredos e contas

Sem mudança em relação ao 261113 [Q2]: VPC, sub-redes, grupos de segurança, NACLs, endpoints, Secrets Manager, Parameter Store (não usado), KMS e as contas continuam como no `validation-report.md` do 261113 (seções 3 e 5). Nenhuma conexão entre a devnet e a mainnet. A flag não é segredo (V-11). O valor ligado de cada conta vive no `tfvars` fora do repositório.

## Sources

- `operation/environment-provisioning/environment-provisioning-questions.md` (Q1 a Q3 e o resumo confirmado) [Q1] [Q2] [Q3].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/environment-provisioning/validation-report.md` (formato, seções 3, 5 e 6, Anexo A com os comandos em Docker).
- `construction/u4-own-quantity-cycle/infrastructure-design/infrastructure-specification.md`, `monitoring-design.md` (linhas 45, 94-98, 160) e `cicd-pipeline.md` (linhas 25-41, 87-91).
- `construction/u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md` e `monitoring-design.md` (linhas 18, 36, 44, 64-66).
- `construction/u3-pnl-views/infrastructure-design/infrastructure-specification.md`.
- `construction/build-and-test/test-results.md` (linhas 108-113 e seção 9: run `ci` `45550427456`).
- `operation/deployment-pipeline/cd-config.md` (seções 3, 5, 6, 8 e 9), `deployment-strategy.md` (1.3 e 3), `rollback-runbook.md`.
- Arquivos lidos no commit `09de0a8`: `infra/terraform/main.tf`, `environments/*.tfvars.example`, `modules/services/outputs.tf`, `api-worker.tf`, `metrics-agent.tf`, `modules/observability/app-metrics.tf`, `real-ops.tf`, `modules/data/main.tf`, `tests/observability_metrics.tftest.hcl`, `tests/services_secrets.tftest.hcl`, `infra/observability/real-ops-alerts.yml`, `apps/api/src/test/java/com/memetrade/botwallet/RealOpsAlarmParityTest.java`, `db.changelog-master.yaml` e `changes/0023` a `0027`.
- `git diff --stat 600140e HEAD -- infra/terraform infra/observability`.
- `aidlc/spaces/default/memory/team.md` (Deployment, Testes da IaC) e `project.md` (correção do CloudWatch a 120 s).

## Assumptions & Open Questions

1. [assumption] A imagem `hashicorp/terraform:1.9.8` e o provedor `hashicorp/aws` `~> 5.81` são os mesmos do 261113. Se o fundador instalar outra versão do Terraform, ela precisa ser `>= 1.9.0` (V-04).
2. [assumption] `bridgecrew/checkov:3` segue o comando do `cd-config.md` (condição 5); a tag não é fixa por digest. Para repetir o resultado, o fundador registra o digest usado na nota.
3. Aberta: O-1. A garantia de que a flag nunca entra em `app_environment` hoje é só por leitura. A sugestão de teste fica para a próxima volta da U4 à Construction; decide o fundador.
4. Aberta: U-05 depende da condição 1 (flag liberada) e de a devnet existir; U-06 a U-09 dependem de a conta existir.
5. [assumption] O número `45550427456` do run `ci` verde vem do `test-results.md`; este estágio não consultou o GitHub.
