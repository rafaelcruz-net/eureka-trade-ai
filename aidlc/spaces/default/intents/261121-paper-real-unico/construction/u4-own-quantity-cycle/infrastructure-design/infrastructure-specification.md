# Especificação de infraestrutura — U4 `u4-own-quantity-cycle`

Conversa: português (Brasil). Data: 2026-11-24. Revisado depois de `reviews/review-01.md` (R-01 a R-06) e da decisão do fundador F4.

A U4 não traz serviço, processo, réplica, fila, cache nem permissão nova. Ela roda no contêiner `worker` da tarefa `api-worker`, que já existe, e muda a infraestrutura em quatro pontos [Q1] [Q2] [F4]:

- duas migrações só de expansão, `0026` e `0027`, aplicadas pelo `db-migrate` antes da troca das imagens;
- duas variáveis Terraform por ambiente, `real_own_quantity_sell_api` e `real_own_quantity_sell_worker` (`bool`, padrão `false`), levadas a `MEMETRADE_REAL_OWN_QUANTITY_SELL` em `api_environment` e em `worker_environment` de `infra/terraform/main.tf`;
- três métricas novas na lista de permissão do CloudWatch. Essa lista muda a configuração do sidecar `metrics-agent` nas duas famílias, `api-worker` e `txsigner`;
- dois alarmes novos em `real-ops.tf` e as duas regras gêmeas em `real-ops-alerts.yml` (desenho em `monitoring-design.md`).

Este estágio não altera nenhum arquivo. Tudo é aplicado na Code Generation. Hoje nenhum ambiente na nuvem está provisionado. O que vale para a nuvem passa a valer quando o Terraform de `infra/terraform/` for aplicado (`team.md`, Deployment).

## Decisão do fundador (2026-11-24)

| ID | Decisão | Efeito neste desenho |
|---|---|---|
| F4 | **Valor da flag por ambiente, no `tfvars`.** Duas variáveis `bool` com padrão `false`, uma para a API e outra para o worker, no padrão de `founder_user_id`. Uma validação recusa o worker ligado com a API desligada. O `main.tf` as leva, como texto, a `api_environment` e a `worker_environment`. O commit do código nunca muda as variáveis. A virada é só uma mudança no `tfvars` do ambiente alvo, que fica fora do repositório: primeiro a devnet, depois a mainnet. | Resolve R-01. A mainnet só liga quando o `tfvars` dela muda, então um `apply` de outra mudança não liga a flag por acidente. A validação substitui a regra de procedimento "nenhum outro apply na mainnet" e a sugestão de `precondition`, que saem do desenho. A virada não tem mais commit; o registro dela é o `terraform plan` e o `apply` com MFA (`RegisterTaskDefinition` no CloudTrail), a implantação e a nota da implantação (`cicd-pipeline.md`). |

## Implantação

| Faceta | Escolha | Justificativa |
|---|---|---|
| Modelo de computação | ECS Fargate, na tarefa `api-worker` que já existe: contêineres `api` e `worker` da mesma imagem e o sidecar `metrics-agent`. Nenhuma família, serviço ou contêiner novo. | A leitura da posição própria roda no worker; a API só lê a flag para o aviso da tela (NFR8.1, NFR3.6). |
| Definições de tarefa | `api-worker` ganha uma revisão no `terraform apply` do commit da U4. A revisão muda a variável da flag nos contêineres `api` e `worker` e o texto do sidecar. `txsigner` ganha uma revisão **só do sidecar**, que entra na próxima implantação regular do TxSigner e não é pré-condição da U4 (R-03). Não mudam: imagens, segredos, CPU, memória, portas e health checks. Os serviços têm `ignore_changes = [task_definition]`, então nenhuma revisão entra em serviço sozinha. | Q1 e Q2; padrão D-INF1 da U2. |
| Dimensionamento | Sem mudança. A tarefa `api-worker` continua com `cpu = 1024` e `memory = 3072`; `api` e `worker` têm 1024 MiB cada. O mapa em memória do gauge (F3) ocupa no máximo cerca de 1 MB (10.000 entradas). | `scalability-design.md` (NFR1.8, NFR1.9). |
| Rede | Sem mudança: mesma VPC, sub-redes, grupos de segurança, balanceador e endpoints. A leitura é uma consulta local ao RDS `app`. | NFR8.1. A U4 não chama TxSigner, RPC nem KMS (NFR3.3). |
| Armazenamento | O mesmo RDS `app` (`db.t4g.micro`, 20 GB `gp3`), sem mudança de classe, parâmetros ou réplicas. Entram 2 valores em `bot_cycle_outcome_ck` (`0026`) e 1 índice parcial (`0027`). Nenhuma tabela ou coluna nova. | NFR1.12, NFR2.7, NFR3.8 [Q1]. |
| Ambientes | Local (Docker Compose) e CI com a flag desligada (o valor padrão do `application.yml`). Na nuvem, o valor vem do `tfvars` de cada conta. A flag liga primeiro na devnet, só para os testes do fundador, e depois na mainnet com o mesmo SHA de imagem. Nenhuma configuração da U4 abre a devnet. | NFR8.3; F4; `project.md`, Forbidden (devnet fechada). |
| Abordagem de IaC | Terraform em `infra/terraform/`. Arquivos alterados: `main.tf` (as duas variáveis, a validação e os dois mapas); `environments/devnet.tfvars.example` e `mainnet.tfvars.example` (as duas linhas com `false`); `modules/services/outputs.tf` (saída `api_worker_container_environment`, para o teste); `modules/observability/app-metrics.tf` (lista de permissão, inventário dos alarmes e saída `alarm_expressions`); `modules/observability/real-ops.tf` (os dois alarmes). O sidecar continua gerado da lista: `metrics-agent.tf` não muda. | NFR8.2, NFR8.4 [Q1] [Q2] [F4]. |
| Segredos | Nenhum segredo novo. A flag não é segredo: o nome não casa com o filtro de nomes de segredo do `services_secrets.tftest.hcl`. | NFR3.6. |
| Escala | A mesma regra da tarefa `api-worker`. Continua um único worker ativo (trava `bots-slot-dispatch` do ShedLock). O pool do worker continua com 36 conexões. | `scalability-design.md`. |
| Custo | Cerca de US$ 1,90 por mês por conta (ver "Custo"). | NFR8.2 [Q2]. |

## Mudanças no Terraform

### `infra/terraform/main.tf`: a flag (F4)

| Onde | Diferença | Observação |
|---|---|---|
| `variable "real_own_quantity_sell_api"` (nova) | `type = bool`, `default = false`, descrição "MEMETRADE_REAL_OWN_QUANTITY_SELL of the api container (U4); turned on per environment in its tfvars, after the 261113 Performance Validation" | Fica ao lado de `founder_user_id`. |
| `variable "real_own_quantity_sell_worker"` (nova) | `type = bool`, `default = false`; `validation { condition = !var.real_own_quantity_sell_worker \|\| var.real_own_quantity_sell_api, error_message = "real_own_quantity_sell_worker needs real_own_quantity_sell_api: turn the api on first and the worker off first (U4 NFR2.8)." }` | A referência a outra variável numa `validation` é aceita desde o Terraform 1.9; o projeto fixa `>= 1.9.0`. A regra vale nos dois sentidos: ligar a API antes do worker e desligar o worker antes da API. |
| `api_environment` | `+ MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_api)`, com um comentário que cita a U4 e a F4 | Dá `"false"` ou `"true"`. Ligada à `memetrade.real.own-quantity-sell` pelo `${MEMETRADE_REAL_OWN_QUANTITY_SELL:false}` do `application.yml`. |
| `worker_environment` | `+ MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_worker)` | A chave fica separada da chave da API, porque cada contêiner vira numa implantação própria (NFR2.8). |
| `app_environment` | **sem mudança**; a chave nunca entra aqui | Em `app_environment`, uma única mudança viraria os dois contêineres de uma vez. |
| `environments/*.tfvars.example` | `+ real_own_quantity_sell_api = false` e `+ real_own_quantity_sell_worker = false`, com o comentário da ordem | Os `tfvars` reais ficam fora do repositório (`team.md`). |

Com o padrão `false`, o valor é o mesmo do código. Por isso, a entrada das variáveis no commit da U4 não muda comportamento e não conta como virada da flag. O commit do código nunca muda as variáveis.

### `infra/terraform/modules/services/outputs.tf`: saída para o teste (R-02)

| Saída nova | Valor | Uso |
|---|---|---|
| `api_worker_container_environment` | `{ for c in jsondecode(aws_ecs_task_definition.api_worker.container_definitions) : c.name => { for e in try(c.environment, []) : e.name => e.value } }`, sem segredos (só `environment`) | As asserções da flag em `observability_metrics.tftest.hcl` leem o ambiente real de cada contêiner (`api`, `worker`, `metrics-agent`), como o `services_secrets.tftest.hcl` já decodifica a definição de tarefa. |

### `infra/terraform/modules/observability/app-metrics.tf`: lista de permissão

| Grupo de `app_metric_declarations` | Diferença | Métricas no CloudWatch |
|---|---|---|
| `dimensions = [[]]` (existente) | `+ "real_own_holdings_reads_total"` e `+ "real_own_holdings_stuck_bots"`, com um comentário (U4, NFR2.16, NFR2.18) | 2 |
| novo: `{ dimensions = [["reason"]], metrics = ["real_own_holdings_unavailable_total"] }` | grupo novo | 3 (`BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`) |
| fora da lista | `real_cycle_sell_capped_total` | 0; o filtro `keep` descarta a métrica, que fica só no Prometheus (NFR2.11) |

Outras diferenças no mesmo arquivo:

| Local ou saída | Diferença |
|---|---|
| `alarm_resource_names` | `+ "real_ops_own_holdings_unavailable"`, `+ "real_ops_own_holdings_stuck_bots"` |
| `metric_alarms` | `+ aws_cloudwatch_metric_alarm.real_ops_own_holdings_unavailable`, `+ aws_cloudwatch_metric_alarm.real_ops_own_holdings_stuck_bots` |
| saída nova `alarm_expressions` | mapa `alarm_name → [expressões das metric_query]`, só dos alarmes de expressão. O teste offline confere o texto exato da expressão por ela (ver "Testes do Terraform"). |

O `alarm_metric_reads` já lê as `metric_query` com `metric`, então os dois alarmes entram nas conferências genéricas sem outra mudança.

### Sidecar `metrics-agent` nas duas famílias

`modules/services/metrics-agent.tf` **não muda**. Ele gera a configuração do agente a partir da lista, e a mudança da lista altera dois textos em cada família:

| Variável do contêiner `metrics-agent` | `api-worker` | `txsigner` |
|---|---|---|
| `PROMETHEUS_CONFIG_CONTENT` | o `regex` do `keep` (`agent_keep_regex`) ganha os 3 nomes, nos jobs `api` e `worker` | o `regex` do job `txsigner` ganha os mesmos 3 nomes. Eles nunca existem no TxSigner; o filtro só fica igual nas duas famílias. |
| `CW_CONFIG_CONTENT` | `metric_declaration` ganha o grupo `["reason"]` e os 2 seletores `^real_own_holdings_reads_total$` e `^real_own_holdings_stuck_bots$` no grupo `[]` | igual |

Efeito: o `terraform apply` registra uma revisão nova de cada família. A do `api-worker` entra em serviço na mesma implantação da imagem da U4. A do `txsigner` **não é pré-condição da U4** (R-03). As três métricas nunca existem no TxSigner, e nenhum alarme lê o sidecar dele. Ela entra na próxima implantação regular do TxSigner, pelo passo do fundador com MFA, que parte da revisão do Terraform (`founder_task_definition_arns`, `cd-config.md` 4.6 do intent `261113-trading-real-bot`). Nessa revisão, imagem, flags, papéis e segredos são os da base; só o sidecar muda. O papel de implantação do GitHub continua sem tocar no TxSigner.

### `infra/terraform/modules/observability/real-ops.tf`: dois alarmes

Os dois entram depois de `real_ops_pnl_reconciliation_mismatch`. O desenho completo, com as regras do Prometheus, está em `monitoring-design.md`.

| Recurso | Atributos |
|---|---|
| `aws_cloudwatch_metric_alarm.real_ops_own_holdings_unavailable` | `alarm_name = "${var.name}-real-ops-own-holdings-unavailable"`; `evaluation_periods = 2`; `datapoints_to_alarm = 2`; `threshold = 0.05`; `GreaterThanThreshold`; `treat_missing_data = "notBreaching"`; `alarm_actions = [var.alarm_topic_arn]`; 4 `metric_query` (abaixo) |
| `aws_cloudwatch_metric_alarm.real_ops_own_holdings_stuck_bots` | `alarm_name = "${var.name}-real-ops-own-holdings-stuck-bots"`; `namespace = local.real_ops_namespace`; `metric_name = "real_own_holdings_stuck_bots"`; sem `dimensions`; `statistic = "Minimum"`; `period = 300`; `evaluation_periods = 2`; `datapoints_to_alarm = 2`; `threshold = 0`; `GreaterThanThreshold`; `notBreaching`; `alarm_actions = [var.alarm_topic_arn]` |

`metric_query` do alarme da razão:

| `id` | Conteúdo | `return_data` |
|---|---|---|
| `e1` | `expression = "IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)"`, `label = "own holdings unavailable ratio"` | `true` |
| `m1` | `metric { namespace = local.real_ops_namespace, metric_name = "real_own_holdings_unavailable_total", dimensions = { reason = "BUDGET_EXCEEDED" }, stat = "Sum", period = 600 }` | omitido (falso) |
| `m2` | igual a `m1`, com `dimensions = { reason = "READ_FAILED" }` | omitido |
| `m3` | `metric { namespace = local.real_ops_namespace, metric_name = "real_own_holdings_reads_total", stat = "Sum", period = 600 }`, sem `dimensions` | omitido |

`alarm_description` (em inglês, como os demais):

- razão: `"U4 NFR2.12: more than 5% of the own-holdings reads of the REAL bots unavailable (BUDGET_EXCEEDED or READ_FAILED) in two windows of 600 s. Runbook: real-operations.md, section 10."`;
- bot preso: `"U4 NFR2.18 (F3): a REAL bot has 3 or more own-holdings reads unavailable in a row for 10 minutes. Runbook: real-operations.md, section 10."`.

## Migrações

As duas entram em `apps/api/src/main/resources/db/changelog/changes/` e em `db.changelog-master.yaml`, depois da `0025`, nesta ordem. O Liquibase roda só no `db-migrate` (perfil `migrate`, login dono do esquema, pool de 1 conexão). A API e o worker rodam com `spring.liquibase.enabled: false`.

| Ordem | Changeset | Conteúdo | Atributos | Trava | Falha |
|---|---|---|---|---|---|
| 1 | `0026-bot-cycle-outcome-not-valid` | `SET LOCAL lock_timeout = '5s'`; um único `ALTER TABLE bot_cycle DROP CONSTRAINT bot_cycle_outcome_ck, ADD CONSTRAINT bot_cycle_outcome_ck CHECK (... as 12 de hoje, 'REAL_NO_OWN_QUANTITY', 'REAL_OWN_QUANTITY_UNAVAILABLE') NOT VALID` | padrão (em transação) | `ACCESS EXCLUSIVE` só na troca do catálogo, no máximo 5 s de espera | `55P03`: a transação é desfeita, a regra antiga continua, o `db-migrate` sai com código diferente de zero e a implantação para antes das imagens |
| 2 | `0026-bot-cycle-outcome-validate` | `SET LOCAL lock_timeout = '5s'`; `ALTER TABLE bot_cycle VALIDATE CONSTRAINT bot_cycle_outcome_ck` | padrão | `SHARE UPDATE EXCLUSIVE`; os inserts dos ciclos continuam | a regra fica `NOT VALID`, mas vale para linhas novas; a repetição roda só este changeset |
| 3 | `0027-real-decision-request-bot-pending-sell-idx` | `DROP INDEX CONCURRENTLY IF EXISTS real_decision_request_bot_pending_sell_idx;` e `CREATE INDEX CONCURRENTLY real_decision_request_bot_pending_sell_idx ON real_decision_request (bot_id) WHERE state = 'PENDING' AND side = 'SELL';` | `runInTransaction:false`, `splitStatements` padrão (verdadeiro), um único `--rollback` | `SHARE UPDATE EXCLUSIVE`; espera as transações abertas (segundos) | índice `INVALID` e nenhuma linha em `DATABASECHANGELOG`; a execução seguinte apaga o índice e o cria de novo |

| Regra | Decisão |
|---|---|
| Só expansão | 0 tabelas, 0 colunas, 0 `GRANT`/`REVOKE`, 0 DML (NFR3.8). A regra nova aceita tudo o que a imagem anterior grava. |
| `lock_timeout` | Fica só no `SET LOCAL` de cada changeset da `0026`. Nenhum `lock_timeout` de sessão, porque ele vazaria para a `0027`. |
| Rollback | **Só a imagem volta.** Em produção nenhuma migração é revertida. A imagem anterior funciona com a regra mais larga e ignora o índice. Os blocos `--rollback` existem só para os ambientes local e de teste (`team.md`, Deployment). |
| Testes | `Migration0026IT`, `Migration0026LockTimeoutIT`, `Migration0027IndexRestartIT`, `RealOpenSellsPlanIT`; o `AnonymizationMigrationIT` passa a desfazer e reaplicar a `0026` e a `0027` (um `--rollback` por changeset). |
| Recuperação | Runbook, seção 12 (`cicd-pipeline.md`). |

## Serviços de infraestrutura

| Serviço | Papel | Configuração | Observações |
|---|---|---|---|
| PostgreSQL da aplicação (RDS `app`) | banco de dados | Sem mudança de instância, armazenamento ou parâmetros. +2 valores na regra de `bot_cycle` e +1 índice parcial. | O índice só guarda decisões SELL `PENDING`: dezenas de entradas no pior caso. |
| Hikari do worker (`memetrade-worker-pool`, 36) | pool de conexões | Sem mudança. No máximo 1 conexão por ciclo de cada vez. | NFR1.9. |
| Tarefa avulsa `db-migrate` | migração | Sem mudança de definição. Aplica a `0026` e a `0027`. | Falha para a implantação (runbook, seção 12). |
| Sidecar `metrics-agent` (`api-worker`, `txsigner`) | coleta de métricas | Leitura a cada 120 s, sem mudança. A lista ganha 3 nomes. | Revisão nova nas duas famílias. |
| CloudWatch (`Eureka/App`) | métricas e alarmes | +5 métricas, +1 alarme de expressão (3 métricas), +1 alarme padrão; tópico SNS único | `monitoring-design.md`. |
| CloudWatch Logs | logs | Sem mudança de grupo, retenção ou consultas salvas. | Os eventos novos levam `correlationId` e uuid, nunca carteira ou assinatura (NFR3.7, NFR3.9). |
| Balanceador, cache, fila, CDN, DNS | — | Sem mudança ou não usados. | NFR8.1. |

## Infraestrutura compartilhada

| Recurso compartilhado | Unit dona | Units consumidoras | Fronteira de acesso |
|---|---|---|---|
| RDS `app` | já existente | intents anteriores, U1 a U4 | A `0026` só troca a regra de `bot_cycle` (módulo `bots`); a `0027` só indexa `real_decision_request` (módulo `realexecution`), lida pelo `openSells` atrás de `RealExecutionPort` (ArchUnit). |
| `api_environment`, `worker_environment` | já existente | U1 (chaves de ajuste), U4 (flag) | Uma chave por contêiner, nunca em `app_environment`. |
| Lista de permissão (`app-metrics.tf`) e o sidecar das duas famílias | Observability Setup do `261113` | U2 (1 métrica), U4 (3 métricas) | Entrada nova só com decisão de estágio; esta é a Q2. |
| `real-ops-alerts.yml` e `real-ops.tf` | U5 do `261113` | U2 (1 par), U4 (2 pares) | Mesmo grupo `real-ops`, paridade em `RealOpsAlarmParityTest` (`hasSize(16)`). |
| `docs/runbooks/real-operations.md` | U5 do `261113` | U2 (seções 8 e 9), U4 (seções 10 a 13) | As seções 1 a 9 não mudam. |

## Custo

| Item | Quantidade | Preço unitário (público) | Por mês e por conta |
|---|---|---|---|
| Métricas personalizadas | 5 (3 de `unavailable` por `reason`, `reads`, `stuck_bots`) | cerca de US$ 0,30 | cerca de US$ 1,50 |
| Alarme de expressão da razão | 3 métricas lidas | cerca de US$ 0,10 por métrica | cerca de US$ 0,30 |
| Alarme do bot preso | 1 métrica | cerca de US$ 0,10 | cerca de US$ 0,10 |
| Eventos EMF do agente | 5 séries a cada 120 s (cerca de 3.600 eventos por dia) | ingestão de logs | menos de US$ 0,01 |
| Revisões de definição de tarefa | 2 no `apply` do commit da U4 e 1 em cada virada da flag | sem custo | US$ 0 |
| **Total** | | | **cerca de US$ 1,90** (cerca de US$ 3,80 nas duas contas) |

O total fica dentro do orçamento de US$ 350 por conta (`project.md`). Ele passa o NFR8.2 (US$ 1,50) em US$ 0,40, por causa da F3 (alinhamento A17 de `logical-components.md`). `real_cycle_sell_capped_total` fica só no Prometheus: US$ 0.

## Implicações de segurança

| Mudança | Implicação | Risco |
|---|---|---|
| Flag em variável de ambiente | Ligada só por uma revisão de definição de tarefa (mudança no `tfvars` do ambiente, `terraform apply` com MFA e implantação); sem rota, tabela ou actuator gravável. A validação recusa o worker ligado sem a API. A mainnet só muda quando o `tfvars` dela muda (F4). Ligar a flag não liga o REAL nem muda `memetrade.real.enabled` ou `txsigner.real.enabled`. | Baixo (NFR3.6). |
| Migrações | Só a regra de `bot_cycle` e um índice; nenhuma permissão. O login dono já cria índices e altera regras. | Baixo (NFR3.8). |
| Revisão do sidecar no `txsigner` | Só a configuração do agente muda; nenhum papel IAM, segredo, KMS ou imagem nova. A revisão entra só pelo passo do fundador com MFA. | Baixo (padrão D-INF1 da U2). |
| Métricas e alarmes | Etiquetas só com enum (`reason`) ou nenhuma; nenhum tenant, pessoa, bot, mint ou carteira no CloudWatch. O agente já publica em `Eureka/App`; nenhuma permissão IAM nova. | Baixo (NFR3.7). |
| Saque e saídas | Nada da U4 toca o saque, "Vender tudo e sacar", as condicionais ou a liquidação; eles continuam disponíveis durante as implantações, a virada e o rollback. | Coberto por NFR2.2 e `project.md`, Mandated. |

Nenhum controle de segurança é removido ou contornado. Nenhuma mudança em IAM, rede ou criptografia.

## Sources

- `infrastructure-design-questions.md`: Q1 a Q3 e o resumo confirmado [Q1] [Q2] [Q3].
- `reviews/review-01.md` (R-01 a R-06) e a decisão do fundador F4 de 2026-11-24.
- Código lido para a F4: `infra/terraform/main.tf` (`variable "founder_user_id"`, `check "founder_on_mainnet"`, `required_version = ">= 1.9.0"`), `infra/terraform/environments/*.tfvars.example`, `infra/terraform/tests/services_secrets.tftest.hcl` (decodificação de `container_definitions`).
- `construction/u4-own-quantity-cycle/nfr-design/`: `observability-design.md` (métricas, F2, F3, `FILL`/`IF`, `hasSize(16)`, custo), `reliability-design.md` (`0026`, `0027`, `lock_timeout`, flag, runbook), `scalability-design.md` (volume, conexões, custo), `security-design.md` (flag, migrações, etiquetas), `performance-design.md` (índice da `0027`), `logical-components.md` (A1 a A19), `traceability.json`.
- `construction/u4-own-quantity-cycle/nfr-requirements/`: `scalability-requirements.md` (NFR8.1 a NFR8.4), `reliability-requirements.md` (NFR2.7, NFR2.8), `observability-requirements.md`, `tech-stack-decisions.md` (NFR8.6 a NFR8.9).
- `construction/u2-real-bot-pnl/infrastructure-design/` (formato, D-INF1, padrão do índice `CONCURRENTLY`).
- Código lido: `infra/terraform/main.tf` (`app_environment`, `api_environment`, `worker_environment`), `modules/services/api-worker.tf` (merge dos mapas por contêiner, `ignore_changes`, `deployment_maximum_percent = 200`), `modules/services/metrics-agent.tf` (`agent_keep_regex`, `metric_declaration`, `CW_CONFIG_CONTENT`, `PROMETHEUS_CONFIG_CONTENT`), `modules/observability/app-metrics.tf`, `modules/observability/real-ops.tf` (`real_ops_namespace`, alarme de expressão `real_ops_restore_pause`), `infra/observability/real-ops-alerts.yml`, `docs/runbooks/real-operations.md` (seções 1 a 9).
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/deployment-pipeline/cd-config.md` (4.6, seção 7).
- `aidlc/spaces/default/memory/team.md` (Deployment) e `project.md` (orçamento de US$ 350, CloudWatch Agent a 120 s, devnet fechada, saque sempre disponível).

## Assumptions & Open Questions

1. [assumption] Os preços do CloudWatch são os públicos (US$ 0,30 por métrica, US$ 0,10 por métrica de alarme padrão). A região ainda não foi definida, e isso não muda a decisão.
2. [assumption] A saída `alarm_expressions` e os textos de `alarm_description` são propostas deste estágio. A Code Generation pode mudar os nomes, mas mantém o que o teste confere.
3. O brief pedia os arquivos `infrastructure-design.md` e `deployment-architecture.md`. O contrato do estágio (`produces`) e a U2 usam `infrastructure-specification.md`, `monitoring-design.md` e `cicd-pipeline.md`; prevalece o contrato. O conteúdo pedido está nesses três arquivos.
4. O teto teórico de conexões do RDS durante a atualização rolling (D-INF2 da U2) continua uma pendência do responsável. A U4 não soma conexões.
5. **Diferença em relação ao `team.md` (F4).** O `team.md` (Deployment, Flags) e a seção 7 do `cd-config.md` do 261113 dizem que as flags ficam versionadas no Terraform e mudam por commit em `main`. Com a F4, a declaração e o padrão `false` continuam versionados, mas o valor ligado de cada conta fica no `tfvars` fora do repositório, como o `founder_user_id`. O registro da virada passa a ser o `plan` e o `apply` com MFA, o CloudTrail e a nota da implantação. A decisão é do fundador; o texto do `team.md` só muda pelo ritual de aprendizados, se ele quiser.
6. [assumption] A validação entre duas variáveis depende do Terraform 1.9 ou mais novo, que o `required_version` já exige.
