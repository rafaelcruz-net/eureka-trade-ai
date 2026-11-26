# Inventário de ambientes — intent `261121-paper-real-unico`

Data: 2026-11-25. Conversa: português (Brasil). Commit de referência: `09de0a8daea32fc9ec9d8bfe380a9eac9f3b6c29` (`main`). Este estágio não muda nenhum arquivo de código, IaC ou workflow.

**Nada está provisionado. O único ambiente que roda é o local**, no Windows, com Docker Compose (e a CI no GitHub Actions). Não existe conta AWS, a região não foi escolhida e o Terraform não está instalado nesta máquina. Nenhum `terraform plan` ou `apply` rodou e nenhuma credencial foi usada. Provisionar é uma decisão de custo do fundador, fora deste intent [Q1].

Este inventário registra só o **delta planejado** que este intent soma, em cada conta, ao inventário do `261113-trading-real-bot` (`aidlc/spaces/default/intents/261113-trading-real-bot/operation/environment-provisioning/environment-inventory.md`). Tudo o que não aparece aqui continua como está naquele inventário.

## 1. Situação por ambiente

| Ambiente | Raiz Terraform | Estado | O que este intent acrescenta | Dono |
|---|---|---|---|---|
| Local (Windows, Docker Compose) | — (`docker-compose.yml`, `infra/docker-compose.real.yml`) | **EM USO** | migrações `0023` a `0027`; flag da U4 desligada pelo padrão do `application.yml` | fundador |
| CI (GitHub Actions, Linux) | — | **EM USO** | as mesmas migrações nos testes de integração; `promtool` com 16 regras `real-ops` | fundador |
| Conta de gerenciamento | `infra/terraform/org/` | NÃO PROVISIONADO | nada (SCP sem mudança) | fundador |
| Membro `devnet` | `infra/terraform/` com `environment = "devnet"` | NÃO PROVISIONADO | o delta da seção 2 | fundador |
| Membro `mainnet` | `infra/terraform/` com `environment = "mainnet"` | NÃO PROVISIONADO | o delta da seção 2, idêntico ao da devnet | fundador |

As contas continuam sem conexão entre si. A devnet fica para os testes do fundador e a pré-produção do mesmo SHA; os usuários usam só a mainnet (`project.md`, Forbidden).

## 2. Delta por conta (devnet e mainnet)

Todo recurso abaixo está **NÃO PROVISIONADO**. O endereço é o mesmo nos dois estados. As linhas são as do commit `09de0a8`. Os arquivos de `infra/` que este intent mudou são exatamente nove (`git diff --stat 600140e HEAD -- infra/terraform infra/observability`): `main.tf`, os dois `tfvars.example`, `modules/services/outputs.tf`, `modules/observability/app-metrics.tf`, `modules/observability/real-ops.tf`, `tests/observability_metrics.tftest.hcl`, `infra/observability/real-ops-alerts.yml` e `infra/observability/tests/real-ops-own-holdings-unavailable.test.yml`.

### 2.1 Variáveis da raiz (U4, decisão F4)

| Item | Arquivo e linha | Conteúdo | Custo/mês | Origem |
|---|---|---|---|---|
| `variable "real_own_quantity_sell_api"` | `infra/terraform/main.tf:155-159` | `type = bool`, `default = false` | 0 | U4 F4 |
| `variable "real_own_quantity_sell_worker"` | `infra/terraform/main.tf:161-170` | `type = bool`, `default = false` | 0 | U4 F4 |
| Validação entre as duas | `infra/terraform/main.tf:166-169` | `condition = !var.real_own_quantity_sell_worker \|\| var.real_own_quantity_sell_api`; `error_message = "real_own_quantity_sell_worker needs real_own_quantity_sell_api: turn the api on first and the worker off first (U4 NFR2.8)."` | 0 | U4 F4, NFR2.8 |
| Versão mínima que aceita a validação entre variáveis | `infra/terraform/main.tf:11` | `required_version = ">= 1.9.0"` (já existia) | 0 | 261113 |
| Valores de exemplo | `infra/terraform/environments/devnet.tfvars.example:36-37` e `mainnet.tfvars.example:36-37` | `real_own_quantity_sell_api    = false` e `real_own_quantity_sell_worker = false`, com o comentário da ordem (linhas 33-35) | 0 | U4 F4 |

Os `tfvars` reais de cada conta ficam fora do repositório (`team.md`, Proteção de segredos). A flag **não é segredo** [Q2]. Ela fica `false` em todos os ambientes até a condição 1 do `cd-config.md` (seção 6).

### 2.2 Mapeamento para os contêineres da tarefa `api-worker`

| Item | Arquivo e linha | Conteúdo | Origem |
|---|---|---|---|
| Chave da API | `infra/terraform/main.tf:468` (dentro de `api_environment`, linhas 443-469) | `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_api)` | U4 F4 |
| Chave do worker | `infra/terraform/main.tf:475` (dentro de `worker_environment`, linhas 471-476) | `MEMETRADE_REAL_OWN_QUANTITY_SELL = tostring(var.real_own_quantity_sell_worker)` | U4 F4 |
| `app_environment` | `infra/terraform/main.tf:428-441` | **sem a chave** (uma chave ali viraria os dois contêineres de uma vez) | U4 F4, NFR8.4 |
| Chaves de ajuste da U1 (não são flags nem segredos) | `infra/terraform/main.tf:463-465` | `MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT = "800ms"`, `MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT = "500ms"`, `MEMETRADE_PNL_BYMODE_REALMAXORDERS = "50000"`, iguais aos padrões do `application.yml` | U1 |
| Como os mapas chegam aos contêineres | `infra/terraform/modules/services/api-worker.tf:78` e `:96` | `api` = `merge(var.app_environment, var.api_environment)`; `worker` = `merge(var.app_environment, var.worker_environment, {...})`. O sidecar `metrics-agent` não recebe nenhum dos dois mapas (`metrics-agent.tf:126-135`). | 261113 |

Efeito: o primeiro `apply` com este intent registra uma revisão nova da família `api-worker` (as chaves da U1, a flag `"false"` e o texto do sidecar) e uma da família `txsigner` (só o sidecar). Os serviços têm `ignore_changes = [task_definition]` (`api-worker.tf:156`), então nenhuma revisão entra em serviço sozinha.

### 2.3 Saída de módulo

| Item | Arquivo e linha | Conteúdo | Uso |
|---|---|---|---|
| `output "api_worker_container_environment"` | `infra/terraform/modules/services/outputs.tf:80-86` | `{ for c in jsondecode(aws_ecs_task_definition.api_worker.container_definitions) : c.name => { for e in try(c.environment, []) : e.name => e.value } }`, só `environment`, nunca `secrets` | asserções offline da flag (`observability_metrics.tftest.hcl:248-287`) |
| `output "alarm_expressions"` | `infra/terraform/modules/observability/app-metrics.tf:279-287` | `alarm_name → [expressões das metric_query]`, só dos alarmes de expressão | asserção do texto da razão (`observability_metrics.tftest.hcl:188-191`) |

### 2.4 Lista de permissão do CloudWatch (`Eureka/App`)

| Métrica | Arquivo e linha | Dimensões | Séries no CloudWatch | Origem |
|---|---|---|---|---|
| `pnl_real_reconciliation_mismatches_total` | `modules/observability/app-metrics.tf:71` | nenhuma (grupo `[[]]`; o agente soma as 8 séries de `check`) | 1 | U2 (D-INF1) |
| `real_own_holdings_reads_total` | `app-metrics.tf:74` | nenhuma | 1 | U4 NFR2.16 |
| `real_own_holdings_stuck_bots` | `app-metrics.tf:75` | nenhuma | 1 | U4 NFR2.18 (F3) |
| `real_own_holdings_unavailable_total` | `app-metrics.tf:79` (grupo novo `{ dimensions = [["reason"]], ... }`) | `reason` (enum: `BUDGET_EXCEEDED`, `READ_FAILED`, `BOT_NOT_OWNED`) | 3 | U4 NFR2.12 |
| `real_cycle_sell_capped_total` | fora da lista (comentário em `app-metrics.tf:73`) | — | 0 (só Prometheus) | U4 NFR2.11 |
| **Total** | | | **6 séries novas** (1 da U2, 5 da U4) | |

`modules/services/metrics-agent.tf` não mudou: o `keep` do Prometheus e o `metric_declaration` do agente são gerados dessa lista, nas famílias `api-worker` e `txsigner`. A U3 não acrescenta métrica ao CloudWatch (os três contadores dela ficam só no Prometheus; D-INF3).

### 2.5 Alarmes (`modules/observability/real-ops.tf`)

| Alarme | Arquivo e linha | Parâmetros | Custo/mês | Origem |
|---|---|---|---|---|
| `aws_cloudwatch_metric_alarm.real_ops_pnl_reconciliation_mismatch` (`${var.name}-real-ops-pnl-reconciliation-mismatch`) | `real-ops.tf:198-210` | `pnl_real_reconciliation_mismatches_total`, `Sum`, `period = 240`, 1 avaliação, `> 0`, `notBreaching` | 0,10 | U2 NFR2.14 |
| `aws_cloudwatch_metric_alarm.real_ops_own_holdings_unavailable` (`${var.name}-real-ops-own-holdings-unavailable`) | `real-ops.tf:217-265` | expressão `e1 = IF(FILL(m3, 0) > 0, (FILL(m1, 0) + FILL(m2, 0)) / m3, 0)`; `m1`/`m2` = `real_own_holdings_unavailable_total` com `reason` `BUDGET_EXCEEDED`/`READ_FAILED`; `m3` = `real_own_holdings_reads_total`; `Sum`, `period = 600`; 2 de 2; `> 0.05`; `notBreaching` | 0,30 (3 métricas) | U4 NFR2.12 (F2) |
| `aws_cloudwatch_metric_alarm.real_ops_own_holdings_stuck_bots` (`${var.name}-real-ops-own-holdings-stuck-bots`) | `real-ops.tf:270-283` | `real_own_holdings_stuck_bots`, `Minimum`, `period = 300`, 2 de 2, `> 0`, `notBreaching` | 0,10 | U4 NFR2.18 (F3) |

Os três alarmes vão para o tópico SNS único (`alarm_actions = [var.alarm_topic_arn]`) e entram no inventário `alarm_resource_names` (`app-metrics.tf:162-163`) e em `metric_alarms` (`app-metrics.tf:190-192`). As regras gêmeas do Prometheus estão em `infra/observability/real-ops-alerts.yml:144-185`.

### 2.6 Migrações do RDS `app`

Aplicadas pela família avulsa `db-migrate` (que já existe) antes da troca das imagens. Ficam em `apps/api/src/main/resources/db/changelog/changes/` e em `db.changelog-master.yaml:47-55`.

| Ordem | Arquivo | Changesets | Natureza | Origem |
|---|---|---|---|---|
| 1 | `0023-real-entry-slot.sql` | `0023-real-entry-slot`, `-append-only`, `-privileges`, `0023-real-ledger-entry-wallet-created-idx` (`runInTransaction:false`, `CREATE INDEX CONCURRENTLY`) | tabela nova só de inserção, restrição de privilégios na tabela nova, índice | U2 |
| 2 | `0024-real-risk-day-deferred.sql` | `0024-real-risk-day-deferred`, `-privileges` | tabela nova, índice de expurgo, `GRANT SELECT, DELETE` a `risk_retention` | U2 |
| 3 | `0025-real-position-epoch.sql` | `0025-real-position-epoch`, `-append-only`, `-privileges` | tabela nova só de inserção, índice | U2 (Etapa 14b) |
| 4 | `0026-bot-cycle-own-quantity-outcomes.sql` | `0026-bot-cycle-outcome-not-valid`, `0026-bot-cycle-outcome-validate` (cada um com `SET LOCAL lock_timeout = '5s'`) | regra `bot_cycle_outcome_ck` mais larga (+2 valores), `NOT VALID` e depois `VALIDATE` | U4 |
| 5 | `0027-real-decision-request-bot-pending-sell-idx.sql` | `0027-real-decision-request-bot-pending-sell-idx` (`runInTransaction:false`, `DROP INDEX CONCURRENTLY IF EXISTS` + `CREATE INDEX CONCURRENTLY`) | índice parcial `WHERE state = 'PENDING' AND side = 'SELL'` | U4 |

Todas são só de expansão: nenhuma coluna removida, nenhum DML em tabela existente. Em produção nenhuma migração é revertida; só a imagem volta (`team.md`, Deployment). Num ambiente novo, o primeiro `db-migrate` aplica de `0001` a `0027` de uma vez. Armazenamento: `real_entry_slot` cresce cerca de 20 MB por ano no piloto e cabe nos 20 GB `gp3` atuais (U2, NFR1.19).

## 3. Custo do delta por conta

Preços públicos de referência da `us-east-1`. A região ainda não foi escolhida; antes do primeiro `apply` o fundador recalcula.

| Item | Quantidade | Por mês e por conta (US$) | Origem |
|---|---|---|---|
| Métricas personalizadas | 6 séries × cerca de 0,30 | ≈ 1,80 | U2 (1), U4 (5) |
| Métricas lidas por alarmes | 5 × cerca de 0,10 (1 da U2, 3 da razão, 1 do bot preso) | ≈ 0,50 | U2, U4 |
| Ingestão EMF do agente (6 séries a cada 120 s) | cerca de 4.300 eventos por dia | < 0,01 | U2, U4 |
| Revisões de definição de tarefa, variáveis, saídas, migrações | — | 0 | U1 a U4 |
| U1 (3 chaves de ajuste) e U3 (nenhum recurso) | — | 0 | U1, U3 |
| **Total do intent** | | **≈ 2,30** (U2 0,40 + U4 1,90); **≈ 4,60** nas duas contas | |

Efeito no total do 261113 (seção 4 daquele inventário): devnet de ≈ 293 a 302 para **≈ 295 a 304**; mainnet de ≈ 312 a 321 para **≈ 314 a 323**. Os dois ficam dentro do orçamento de **US$ 350 por conta** (`monthly_budget_usd`; `project.md`, correção de 2026-11-20). As mitigações propostas do D-INF2 e do IR-G3 (`security-posture-review.md`) ainda **não** estão nesse total.

## 4. O que não muda

| Área | Situação | Onde está |
|---|---|---|
| Rede (VPC, sub-redes, NAT, endpoints, grupos de segurança, NACLs, balanceador) | **sem mudança** | inventário do 261113, 3.1 e 3.4 |
| Segredos (Secrets Manager, segredos mestres do RDS) | **sem mudança**: nenhum segredo novo; a flag é variável comum | 261113, 3.3 e 3.4 |
| KMS (chave HMAC da âncora, chaves de carteira, políticas) | **sem mudança** | 261113, 3.2 |
| IAM (papéis de tarefa, de execução, de implantação, OIDC, SCP) | **sem mudança**: nenhum papel, política ou anexo novo | 261113, 3.2, 3.4, 3.6 e 3.7 |
| TxSigner (serviço, imagem, papel, banco `txsigner`, flags) | **sem mudança**; só a revisão do sidecar `metrics-agent` gerada pela lista, que entra na próxima implantação regular do TxSigner pelo passo do fundador com MFA e não é pré-condição deste intent | 261113, 3.2 e 3.4; U4 R-03 |
| RDS (classe `db.t4g.micro`, 20 GB `gp3`, grupo de parâmetros, backup) | **sem mudança** de instância ou parâmetro; só as migrações da seção 2.6 | `modules/data/main.tf:85-123`, `:144` |
| Dimensionamento das tarefas (`api-worker` com `cpu = 1024`, `memory = 3072`) e pools | **sem mudança** | U4 `infrastructure-specification.md` |
| Flags do REAL (`MEMETRADE_REAL_ENABLED`, `MEMETRADE_REAL_EXITS_ENABLED`), kill switch e liberação | **sem mudança**; continuam `"false"` em `app_environment` (`main.tf:434`, `:436`) | 261113 |
| Painéis e consultas salvas | **sem mudança** (3 painéis, 5 consultas) | U4 `cicd-pipeline.md` |

## Sources

- `operation/environment-provisioning/environment-provisioning-questions.md`: Q1 a Q3 e o resumo confirmado [Q1] [Q2] [Q3].
- `aidlc/spaces/default/intents/261113-trading-real-bot/operation/environment-provisioning/environment-inventory.md` (base do inventário e dos custos), `validation-report.md` e `security-posture-review.md`.
- `construction/u1-mode-skeleton/infrastructure-design/infrastructure-specification.md` (custo 0, chaves de ajuste).
- `construction/u2-real-bot-pnl/infrastructure-design/infrastructure-specification.md` e `monitoring-design.md` (`0023`, `0024`, D-INF1, D-INF2, US$ 0,40).
- `construction/u3-pnl-views/infrastructure-design/infrastructure-specification.md` (`terraform plan` vazio, custo 0).
- `construction/u4-own-quantity-cycle/infrastructure-design/infrastructure-specification.md`, `monitoring-design.md` e `cicd-pipeline.md` (F4, `0026`, `0027`, 3 métricas, 2 alarmes, cerca de US$ 1,90).
- `operation/deployment-pipeline/cd-config.md` (seções 3 a 9), `deployment-strategy.md` (1.3) e `rollback-runbook.md`.
- Código lido no commit `09de0a8`: `infra/terraform/main.tf`, `environments/devnet.tfvars.example`, `environments/mainnet.tfvars.example`, `modules/services/outputs.tf`, `modules/services/api-worker.tf`, `modules/services/metrics-agent.tf`, `modules/observability/app-metrics.tf`, `modules/observability/real-ops.tf`, `modules/data/main.tf`, `infra/observability/real-ops-alerts.yml`, `apps/api/src/main/resources/db/changelog/db.changelog-master.yaml` e `changes/0023` a `0027`, `apps/api/src/main/java/com/memetrade/bots/infra/OwnQuantityMetrics.java` (valores de `reason`).
- `git diff --stat 600140e HEAD -- infra/terraform infra/observability` (os nove arquivos do delta).
- `aidlc/spaces/default/memory/team.md` (Deployment) e `project.md` (Forbidden, Mandated, correção do CloudWatch a 120 s e do orçamento de US$ 350).

## Assumptions & Open Questions

1. [assumption] Preços do CloudWatch de referência da `us-east-1` (US$ 0,30 por métrica personalizada, US$ 0,10 por métrica de alarme padrão). A região muda o valor, mas não a ordem de grandeza.
2. [assumption] O total por conta do 261113 (≈ 293 a 302 na devnet, ≈ 312 a 321 na mainnet) continua a base. Este estágio não recalculou aquele inventário.
3. Aberta: a região e as contas. Sem elas, nenhuma linha deste inventário passa de NÃO PROVISIONADO. Dono: fundador (`cd-config.md`, seção 9, item 7).
4. Aberta: D-INF2 e IR-G3, com as opções e os custos em `security-posture-review.md`. Nenhuma das mitigações entra no inventário até o fundador decidir.
5. [assumption] O delta da U1 do intent (as três chaves de ajuste em `api_environment`) conta como mudança deste intent, embora a U1 o tenha descrito como "nenhum custo novo"; ele só muda a revisão da definição de tarefa.
