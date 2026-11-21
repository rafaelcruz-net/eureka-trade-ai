# Inventário de ambientes — intent `261113-trading-real-bot`

Data: 2026-11-20. Conversa: português (Brasil). Commit de referência: `3497b2bc49052f01adb93de7094a05880532222b` (`main`), mais as mudanças deste estágio em `infra/terraform/` (ainda sem commit).

**Situação de todos os recursos: NÃO PROVISIONADO.** Não existe conta AWS (Q1). Nada foi aplicado, nenhuma credencial foi usada e nenhum `terraform plan` ou `apply` rodou contra uma conta. A IaC foi completada e validada sem rede de nuvem (`validation-report.md`).

**Atualização de 2026-11-20 (correções da revisão de segurança, `validation-report.md` seção 10):** recursos novos marcados com "SP-xx" na coluna "Origem": política nos endpoints, chave e política do tópico de alarmes, GuardDuty, IAM Access Analyzer, regras do EventBridge, filtros e alarmes `-security-*`, família e papel do probe do TxSigner. O papel de implantação, a política das chaves, a confiança do papel do TxSigner, o bucket da trilha e a SCP mudaram. Custo: cerca de US$ 7 a mais por conta (seção 4).

## 1. Contas (Q2)

| Conta | Papel | Raiz Terraform | Estado | Dono |
|---|---|---|---|---|
| Gerenciamento (management) | AWS Organizations, SCP, cobrança e orçamentos. Nenhuma carga, nenhuma chave KMS | `infra/terraform/org/` | NÃO PROVISIONADO | fundador |
| Membro `devnet` | Ambiente devnet: KMS real, rede Solana devnet | `infra/terraform/` com `environment = "devnet"` | NÃO PROVISIONADO | fundador |
| Membro `mainnet` | Ambiente mainnet: a única conta com chaves da mainnet | `infra/terraform/` com `environment = "mainnet"` | NÃO PROVISIONADO | fundador |

As contas não se conectam: não há peering nem Transit Gateway (Q5). Cada conta tem o próprio bucket de estado, o próprio provedor OIDC do GitHub e o próprio papel de implantação. O `allowed_account_ids` do provedor recusa aplicar o estado de um ambiente na conta do outro.

## 2. Convenções da tabela

- **Endereço:** endereço Terraform na raiz `infra/terraform/`. O mesmo endereço existe nos dois estados, um por conta.
- **Custo:** estimativa mensal do piloto, em US$, por conta, com preços on-demand de referência da `us-east-1` e 730 h por mês. A região ainda não foi escolhida; em `sa-east-1` os preços são mais altos. Antes do primeiro apply, o fundador recalcula na AWS Pricing Calculator da região escolhida. "≈ 0" quer dizer menos de US$ 0,50.
- **Dono:** o fundador (responsável do projeto) é o dono de todo recurso: aplica, paga e responde a incidentes. A coluna "Origem" diz qual Unit ou estágio pediu o recurso.
- **Diferença entre as contas:** só no KMS (número de chaves) e nos parâmetros (`environment`, domínios, `founder_user_id`). A topologia é a mesma.

## 3. Recursos por conta (devnet e mainnet)

### 3.1 Rede (`module.network`)

| Recurso | Endereço | Estado | Custo/mês devnet | Custo/mês mainnet | Origem |
|---|---|---|---|---|---|
| VPC `10.40.0.0/16` | `module.network.aws_vpc.main` | NÃO PROVISIONADO | 0 | 0 | U1 |
| Grupo de segurança padrão sem regras | `module.network.aws_default_security_group.default` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Subrede pública A (balanceador, NAT) | `module.network.aws_subnet.public` | NÃO PROVISIONADO | 0 | 0 | U1 |
| Subrede pública B (só a segunda perna do balanceador) | `module.network.aws_subnet.public_b` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Subrede privada A (TxSigner e RDS dele, sem internet) | `module.network.aws_subnet.private_a` | NÃO PROVISIONADO | 0 | 0 | U1 |
| Subrede privada B (api+worker e RDS da aplicação, com NAT) | `module.network.aws_subnet.private_b` | NÃO PROVISIONADO | 0 | 0 | U1 |
| Subrede privada web (web e admin, sem internet) | `module.network.aws_subnet.private_web` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Subrede de dados B (só para os subnet groups do RDS) | `module.network.aws_subnet.private_data_b` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Internet gateway e rota pública | `module.network.aws_internet_gateway.main`, `aws_route.public_internet`, `aws_route_table.public`, `aws_route_table_association.public["a"]`, `["b"]` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Tabelas de rota privadas | `module.network.aws_route_table.private_a`, `.private_b`, `.private_web` e as associações | NÃO PROVISIONADO | 0 | 0 | U1 e este estágio |
| NAT gateway único e IP elástico | `module.network.aws_nat_gateway.main`, `aws_eip.nat`, `aws_route.private_b_nat` | NÃO PROVISIONADO | ≈ 37 (32,85 por hora + cerca de 10 GB de dados + 3,65 do IPv4) | ≈ 37 | este estágio (Q3) |
| Endpoints de interface: `kms`, `secretsmanager`, `sts`, `logs`, `ecr.api`, `ecr.dkr`, `monitoring` (1 zona), **com política: só principais e recursos da conta, só as ações do serviço** | `module.network.aws_vpc_endpoint.interface["<serviço>"]` | NÃO PROVISIONADO | ≈ 51 (7 × 0,01/h) | ≈ 51 | U1 (Q3); política: SP-05 |
| Endpoint de gateway do S3 (nas três tabelas privadas), **com política: bucket de camadas do ECR da região e buckets da conta** | `module.network.aws_vpc_endpoint.s3` | NÃO PROVISIONADO | 0 | 0 | U1 (Q3); política: SP-05 |
| Flow logs da VPC (ALL), grupo de log de 90 dias e papel | `module.network.aws_flow_log.main`, `aws_cloudwatch_log_group.flow`, `aws_iam_role.flow`, `aws_iam_role_policy.flow` | NÃO PROVISIONADO | ≈ 1 | ≈ 1 | este estágio |
| Grupos de segurança: `api-worker`, `txsigner`, `vpc-endpoints`, `alb`, `web-admin` | `module.network.aws_security_group.<nome>` | NÃO PROVISIONADO | 0 | 0 | U1 e este estágio |
| Regras de entrada e saída (lista completa em `validation-report.md`, seção 3) | `module.network.aws_vpc_security_group_ingress_rule.*`, `aws_vpc_security_group_egress_rule.*` | NÃO PROVISIONADO | 0 | 0 | U1, Deployment Pipeline e este estágio |

### 3.2 KMS e papel do TxSigner (`module.kms`)

| Recurso | Endereço | Estado | Custo/mês devnet | Custo/mês mainnet | Origem |
|---|---|---|---|---|---|
| Papel de tarefa do TxSigner (KMS), confiança só de tarefas ECS da conta (`aws:SourceAccount`, `aws:SourceArn`) | `module.kms.aws_iam_role.txsigner`, `aws_iam_role_policy.txsigner` | NÃO PROVISIONADO | 0 | 0 | U1; confiança: SP-02 |
| Chave HMAC da âncora (`HMAC_256`); MAC só pelo endpoint do KMS; etiquetas só com MFA | `module.kms.aws_kms_key.anchor_hmac` | NÃO PROVISIONADO | 1 | 1 | U1; política: SP-02, SP-03 |
| Chaves Ed25519 das carteiras do bot (criadas pelo TxSigner em tempo de execução, nunca pelo Terraform), com a política `TXSIGNER_KMS_KEY_POLICY`: `Sign` só pelo endpoint do KMS; etiquetas só com MFA | fora do Terraform (política na saída `module.kms.wallet_key_policy`) | NÃO PROVISIONADO | ≈ 3 (até 3 carteiras de teste) | ≈ 21 (fundador + até 20 convidados) | U1; política: SP-02, SP-03 |
| Assinaturas e MACs | — | NÃO PROVISIONADO | ≈ 0 | < 1 | U1 |
| Leitura do token de serviço pelo papel do TxSigner | `module.services.aws_iam_role_policy.txsigner_reads_service_token` | NÃO PROVISIONADO | 0 | 0 | este estágio |

### 3.3 Bancos (`module.data`)

| Recurso | Endereço | Estado | Custo/mês devnet | Custo/mês mainnet | Origem |
|---|---|---|---|---|---|
| RDS PostgreSQL 17 da aplicação, `db.t4g.micro`, gp3 20 GB, 1 zona, banco `memetrade` | `module.data.aws_db_instance.main["app"]` | NÃO PROVISIONADO | ≈ 14 | ≈ 14 | U1 |
| RDS PostgreSQL 17 do TxSigner, igual, banco `txsigner` | `module.data.aws_db_instance.main["txsigner"]` | NÃO PROVISIONADO | ≈ 14 | ≈ 14 | U1 |
| Subnet groups (um por banco) | `module.data.aws_db_subnet_group.db["app"]`, `["txsigner"]` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Grupo de parâmetros (`track_commit_timestamp`, TLS obrigatório, nada de SQL nem parâmetro no log) | `module.data.aws_db_parameter_group.postgres` | NÃO PROVISIONADO | 0 | 0 | este estágio |
| Grupos de segurança e regras 5432 (entrada só do grupo da tarefa; saída da tarefa só para o próprio banco) | `module.data.aws_security_group.db[*]`, `aws_vpc_security_group_ingress_rule.db[*]`, `aws_vpc_security_group_egress_rule.to_db[*]` | NÃO PROVISIONADO | 0 | 0 | U1 e este estágio |
| Grupos de log do PostgreSQL (90 dias) | `module.data.aws_cloudwatch_log_group.postgresql["app"]`, `["txsigner"]` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | este estágio |
| Segredos mestres gerenciados pelo RDS (2) | criados pelo RDS (`manage_master_user_password`) | NÃO PROVISIONADO | 0,80 | 0,80 | U1 |
| AWS Backup: cofre, plano diário de 30 dias, seleção dos dois bancos, papel | `module.data.aws_backup_vault.rds`, `aws_backup_plan.rds_daily`, `aws_backup_selection.rds`, `aws_iam_role.backup` | NÃO PROVISIONADO | ≈ 2 | ≈ 2 | U1:NFR4.8 (este estágio) |

### 3.4 Serviços (`module.services`)

| Recurso | Endereço | Estado | Custo/mês devnet | Custo/mês mainnet | Origem |
|---|---|---|---|---|---|
| Cluster ECS com Container Insights | `module.services.aws_ecs_cluster.main` | NÃO PROVISIONADO | ≈ 5 (métricas) | ≈ 5 | U1 e este estágio |
| Namespace HTTP `internal` (Service Connect) | `module.services.aws_service_discovery_http_namespace.internal` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | U1 |
| Namespace DNS privado `<name>.internal` e serviço `api` | `module.services.aws_service_discovery_private_dns_namespace.app`, `aws_service_discovery_service.api` | NÃO PROVISIONADO | ≈ 0,60 | ≈ 0,60 | este estágio |
| Serviço e tarefa `txsigner` (0,5 vCPU, 1 GB, 1 tarefa) | `module.services.aws_ecs_service.txsigner`, `aws_ecs_task_definition.txsigner` | NÃO PROVISIONADO | ≈ 18 | ≈ 18 | U1 |
| Serviço e tarefa `api-worker` (1 vCPU, 2 GB, 1 tarefa com 2 contêineres) | `module.services.aws_ecs_service.api_worker`, `aws_ecs_task_definition.api_worker` | NÃO PROVISIONADO | ≈ 36 | ≈ 36 | U1 a U7 (este estágio) |
| Serviços e tarefas `web` e `admin` (0,25 vCPU, 0,5 GB cada) | `module.services.aws_ecs_service.frontend["web"]`, `["admin"]`, `aws_ecs_task_definition.frontend[*]` | NÃO PROVISIONADO | ≈ 18 | ≈ 18 | este estágio (`WEB_ADMIN_ON_ECS=true`) |
| Famílias avulsas `-txsigner-migrate` (só o fundador roda) e `-db-migrate` (o deploy roda) | `module.services.aws_ecs_task_definition.txsigner_migrate`, `.db_migrate` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | Deployment Pipeline (este estágio) |
| Família avulsa `-txsigner-probe` (prontidão do TxSigner): sem papel de tarefa, papel de execução sem segredo, rodada só pelo fundador | `module.services.aws_ecs_task_definition.txsigner_probe` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | SP-01 |
| Famílias avulsas `-db-bootstrap` e `-txsigner-db-bootstrap` e repositório ECR `db-bootstrap` | `module.services.aws_ecs_task_definition.db_bootstrap`, `.txsigner_db_bootstrap`, `aws_ecr_repository.db_bootstrap` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | este estágio |
| Papéis de execução (9, um por família) e políticas. Só `api-worker`, `web`, `admin` e `db-migrate` podem ser passados pelo deploy; `txsigner`, `txsigner-migrate`, `txsigner-probe`, `db-bootstrap` e `txsigner-db-bootstrap` são do fundador | `module.services.aws_iam_role.execution["<família>"]`, `aws_iam_role_policy.execution[*]`, `aws_iam_role_policy_attachment.worker_secrets[*]` | NÃO PROVISIONADO | 0 | 0 | este estágio; separação: SP-01 |
| Grupos de log `/ecs/<name>/txsigner`, `/api-worker`, `/web`, `/admin`, `/migrate`, `/bootstrap` (90 dias) | `module.services.aws_cloudwatch_log_group.ecs["<chave>"]` | NÃO PROVISIONADO | ≈ 2 | ≈ 2 | este estágio |
| Segredos do Secrets Manager (13, sem valor) | `module.services.aws_secretsmanager_secret.this["<nome>"]` (11), `.coingecko_api_key`, `.github_actions_read_token` | NÃO PROVISIONADO | ≈ 5,20 | ≈ 5,20 | U1, U2, U5, U7 e este estágio (Q4) |
| Balanceador público (2 zonas), listeners 443 e 80, regras por host, 3 target groups | `module.services.aws_lb.main`, `aws_lb_listener.https`, `.http`, `aws_lb_listener_rule.host[*]`, `aws_lb_target_group.api`, `.frontend[*]` | NÃO PROVISIONADO | ≈ 30 (16,43 por hora + até 5,84 de LCU + 7,30 de 2 IPv4) | ≈ 30 | U1 e este estágio |
| Bucket dos logs de acesso do balanceador (90 dias) | `module.services.aws_s3_bucket.alb_logs` e recursos `aws_s3_bucket_*.alb_logs` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | este estágio |
| Segredos da U2 e da U5 com as políticas próprias | `module.services.aws_iam_policy.worker_reads_coingecko_key`, `.worker_reads_github_actions_token` | NÃO PROVISIONADO | 0 | 0 | U2, U5 |

### 3.5 Observabilidade e trilha (`module.observability` e raiz)

| Recurso | Endereço | Estado | Custo/mês devnet | Custo/mês mainnet | Origem |
|---|---|---|---|---|---|
| Trilha do CloudTrail (todas as regiões, validação de arquivo) e papel de entrega ao CloudWatch | `module.observability.aws_cloudtrail.main`, `aws_iam_role.trail_logs`, `aws_iam_role_policy.trail_logs` | NÃO PROVISIONADO | ≈ 1 | ≈ 1 | U1 (este estágio: a trilha faltava) |
| Bucket da trilha com object lock de 1 ano, SSE-S3 (`AES256`), política, versionamento e ciclo de vida | `module.observability.aws_s3_bucket.trail` e `aws_s3_bucket_*.trail` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | U1 e este estágio; SSE-S3: SP-08 |
| Grupo de log da trilha (365 dias) e filtros de métrica do KMS | `module.observability.aws_cloudwatch_log_group.trail`, `aws_cloudwatch_log_metric_filter.*` | NÃO PROVISIONADO | ≈ 1 | ≈ 1 | U1 |
| Alarmes (33 recursos de alarme, alguns por trabalho; até cerca de 50 alarmes) | `module.observability.aws_cloudwatch_metric_alarm.*` | NÃO PROVISIONADO | ≈ 5 | ≈ 5 | U1 a U7 |
| Tópico SNS dos alarmes e inscrição por e-mail | `aws_sns_topic.alarms[0]`, `aws_sns_topic_subscription.alarm_email[0]` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | este estágio |
| Chave própria do tópico (rotação anual; aceita CloudWatch e EventBridge) e política do tópico (a conta e as duas origens da conta) | `aws_kms_key.alarms[0]`, `aws_sns_topic_policy.alarms[0]` | NÃO PROVISIONADO | 1 | 1 | SP-11 (sem ela, nenhum alarme chegaria ao e-mail) |
| GuardDuty: detector, eventos S3 e login do RDS ligados; Runtime Monitoring, EKS, EBS e Lambda desligados | `module.observability.aws_guardduty_detector.main`, `aws_guardduty_detector_feature.enabled[*]`, `.disabled[*]` | NÃO PROVISIONADO | ≈ 5 | ≈ 5 | SP-11 |
| IAM Access Analyzer da conta | `module.observability.aws_accessanalyzer_analyzer.account` | NÃO PROVISIONADO | 0 | 0 | SP-11 |
| Regras do EventBridge: achados do GuardDuty com severidade 7 ou mais e achados ativos do Access Analyzer, para o tópico | `module.observability.aws_cloudwatch_event_rule.guardduty_high`, `.access_analyzer_active` e os `aws_cloudwatch_event_target` | NÃO PROVISIONADO | ≈ 0 | ≈ 0 | SP-11 |
| Filtros de métrica e 4 alarmes do caminho de assinatura (`-security-founder-role-iam-change`, `-founder-family-registered`, `-founder-family-run`, `-txsigner-service-updated`) | `module.observability.aws_cloudwatch_log_metric_filter.security[*]`, `aws_cloudwatch_metric_alarm.security[*]` | NÃO PROVISIONADO | 0,40 | 0,40 | SP-11 |

### 3.6 Entrega (`module.delivery`, do Deployment Pipeline)

| Recurso | Endereço | Estado | Custo/mês devnet | Custo/mês mainnet | Origem |
|---|---|---|---|---|---|
| Repositórios ECR `api`, `txsigner`, `web`, `admin` (30 imagens cada); `txsigner` só recebe imagens do fundador | `module.delivery.aws_ecr_repository.app["<app>"]`, `aws_ecr_lifecycle_policy.app[*]` | NÃO PROVISIONADO | ≈ 1 | ≈ 1 | Deployment Pipeline; `txsigner`: SP-01 |
| Provedor OIDC do GitHub | `module.delivery.aws_iam_openid_connect_provider.github[0]` | NÃO PROVISIONADO | 0 | 0 | Deployment Pipeline |
| Papel de implantação `<name>-deploy-<env>`: passa só os papéis de `api-worker`, `web`, `admin` e `db-migrate`; atualiza só esses serviços; roda só o `db-migrate`; Deny das famílias, do serviço e dos papéis do TxSigner e do bootstrap | `module.delivery.aws_iam_role.deploy`, `aws_iam_role_policy.deploy` | NÃO PROVISIONADO | 0 | 0 | Deployment Pipeline; escopo: SP-01 |
| Regras 8444 do probe de prontidão (probe rodado pelo fundador) | `module.delivery.aws_vpc_security_group_ingress_rule.txsigner_management_from_probe`, `aws_vpc_security_group_egress_rule.probe_to_txsigner_management` | NÃO PROVISIONADO | 0 | 0 | Deployment Pipeline |

### 3.7 Conta de gerenciamento (`infra/terraform/org/`)

| Recurso | Endereço | Estado | Custo/mês | Dono |
|---|---|---|---|---|
| SCP `eureka-real-guardrails` (minificada, 2911 de 5120 caracteres): região, chaves KMS (exclusão, política e etiquetas só com MFA), papel do TxSigner e papéis de execução do TxSigner e do bootstrap (mudança, `PassRole` e `AssumeRole` só com MFA; `PassRole` do TxSigner só para o ECS), trilha e grupo de log da trilha | `aws_organizations_policy.guardrails` | NÃO PROVISIONADO | 0 | fundador |
| Anexos às contas `devnet` e `mainnet` | `aws_organizations_policy_attachment.member["devnet"]`, `["mainnet"]` | NÃO PROVISIONADO | 0 | fundador |
| Organização e as duas contas-membro | criadas à mão (seção 5.1 do `validation-report.md`), fora do Terraform | NÃO PROVISIONADO | 0 | fundador |

## 4. Custo estimado por conta (piloto)

Preços on-demand de referência da `us-east-1`, 730 h por mês, uma tarefa por serviço, tráfego baixo. Para a região escolhida, recalcular.

| Grupo | Devnet (US$/mês) | Mainnet (US$/mês) |
|---|---|---|
| Fargate (TxSigner 18, api-worker 36, web 9, admin 9) | ≈ 72 | ≈ 72 |
| RDS (2 × `db.t4g.micro` com 20 GB gp3) e AWS Backup | ≈ 30 | ≈ 30 |
| Balanceador (hora, LCU e 2 IPv4) | ≈ 30 | ≈ 30 |
| NAT gateway (hora, dados e IPv4) | ≈ 37 | ≈ 37 |
| Endpoints de interface (7 numa zona) | ≈ 51 | ≈ 51 |
| KMS (HMAC + chaves de carteira + chamadas) | ≈ 4 | ≈ 23 |
| Secrets Manager (13 + 2 do RDS) | ≈ 6 | ≈ 6 |
| CloudWatch (logs, flow logs, alarmes, Container Insights) e CloudTrail | ≈ 15 | ≈ 15 |
| ECR, Cloud Map, S3, SNS | ≈ 2 | ≈ 2 |
| Detecção (SP-11): GuardDuty ≈ 5, chave do tópico 1, 4 alarmes 0,40, Access Analyzer e EventBridge 0 | ≈ 7 | ≈ 7 |
| Observabilidade (Observability Setup): agente com leitura a cada 120 s, +1 GB no `api-worker`, ≈ 105 métricas customizadas, alarmes novos e 3 painéis (`operation/observability-setup/alarms.md`, seção 9) | ≈ 39 a 48 | ≈ 39 a 48 |
| **Total da conta** | **≈ 293 a 302** (≈ 254 sem a observabilidade) | **≈ 312 a 321** (≈ 273 sem a observabilidade) |
| Conta de gerenciamento (Organizations e SCP) | — | — |
| **Total das três contas** | | **≈ 605 a 623** (≈ 527 sem a observabilidade) |

O orçamento mensal de cada conta (`monthly_budget_usd`) é de **US$ 350**, decisão do responsável de 20/11/2026.

O GuardDuty tem 30 dias de teste gratuito; depois, trocar a estimativa pelo custo do console de uso dele.

Fora da tabela: o RPC (plano gratuito na devnet; os dois provedores da mainnet ainda sem plano escolhido) e o plano da CoinGecko (preço não confirmado, U2:NFR9.1).

**Teto do NFR9 (KMS, RPC e dados até US$ 100 por mês):** na mainnet, KMS ≈ 23 + RPC (a definir) + CoinGecko (a definir). O NFR9 só fecha com os dois preços (seção 6 do `validation-report.md`).

**Diferença em relação ao Infrastructure Design da U1 (≈ US$ 154 por conta de hospedagem):**

| Mudança | Efeito por conta | Motivo |
|---|---|---|
| Web e admin no ECS | + 18 | o desenho da U1 não dizia onde rodavam; decisão `WEB_ADMIN_ON_ECS=true` |
| api-worker com 1 vCPU e 2 GB, em vez de 0,5 vCPU e 1 GB | + 18 | duas JVMs com `-Xmx512m` não cabem em 1 GB |
| NAT gateway gerenciado, em vez de instância NAT `t4g.nano` | + 26 | sem instância para corrigir e manter de pé; decisão deste estágio |
| 7º endpoint (`monitoring`), já pedido pela revisão da U1 | + 7 | métricas do CloudWatch |
| Segundo IPv4 e LCU do balanceador, flow logs, Container Insights, Backup, segredos a mais | + 24 | exigências da AWS (2 zonas no balanceador) e controles deste estágio |

**Formas de baixar o custo, para o fundador decidir (nenhuma aplicada):**
- devnet com `service_desired_count = 0` fora dos testes: menos cerca de US$ 72 na devnet;
- destruir a parte sem estado da devnet entre as rodadas (`terraform destroy` com alvo), mantendo bancos e KMS;
- imagens ARM (Graviton): cerca de 20% a menos no Fargate, mas exige `docker buildx` para `arm64` no `deploy.yml`;
- Compute Savings Plan de 1 ano depois de 2 ou 3 meses de uso medido;
- tirar os endpoints de interface da devnet: menos cerca de US$ 51, mas enfraquece o isolamento do TxSigner. Fica registrado como alternativa, não como recomendação.

## Sources

- `operation/environment-provisioning/environment-provisioning-questions.md` (Q1 a Q5 e o resumo confirmado)
- `construction/u1-bot-wallet/infrastructure-design/infrastructure-specification.md` (rede, RDS, KMS, custo do piloto) e as especificações de U2 a U7 (`construction/u*/infrastructure-design/infrastructure-specification.md`)
- `operation/deployment-pipeline/cd-config.md` (seções 2, 4.3, 5 e 8)
- `construction/build-and-test/test-results.md` (as 7 metas de `environment-provisioning`)
- `infra/terraform/` (todos os módulos, depois das mudanças deste estágio e das correções da revisão de segurança)
- `operation/environment-provisioning/security-posture-review.md` e `validation-report.md` (seções 10 a 12)
- `team.md` (Deployment) e `project.md` (Forbidden e Mandated)
