# Offline checks of the task definitions of modules/services (261113-trading-real-bot Environment Provisioning): each
# container receives only its own secrets, no secret-like name is a plain environment variable, the worker probe runs
# on 8082 and the Jupiter key reaches only the worker when enabled. mock_provider: the "apply" is simulated in memory,
# no AWS credential, account nor network call. Run from infra/terraform: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_region" {
    defaults = { name = "sa-east-1" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "111111111111" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:sa-east-1:111111111111:secret:mock" }
  }
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:111111111111:loadbalancer/app/eureka-real/1" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:111111111111:targetgroup/eureka-real-api/1" }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:111111111111:listener/app/eureka-real/1/1" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111111111111:role/mock" }
  }
  mock_resource "aws_iam_policy" {
    defaults = { arn = "arn:aws:iam::111111111111:policy/mock" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:sa-east-1:111111111111:log-group:/ecs/mock" }
  }
  mock_resource "aws_ecs_cluster" {
    defaults = { arn = "arn:aws:ecs:sa-east-1:111111111111:cluster/eureka-real", id = "arn:aws:ecs:sa-east-1:111111111111:cluster/eureka-real" }
  }
  mock_resource "aws_ecs_task_definition" {
    defaults = { arn = "arn:aws:ecs:sa-east-1:111111111111:task-definition/mock:1" }
  }
  mock_resource "aws_service_discovery_http_namespace" {
    defaults = { arn = "arn:aws:servicediscovery:sa-east-1:111111111111:namespace/ns-1" }
  }
  mock_resource "aws_service_discovery_service" {
    defaults = { arn = "arn:aws:servicediscovery:sa-east-1:111111111111:service/srv-1" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock", id = "mock" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = { repository_url = "111111111111.dkr.ecr.sa-east-1.amazonaws.com/eureka-real/db-bootstrap" }
  }
  mock_data "aws_elb_service_account" {
    defaults = { arn = "arn:aws:iam::507241528517:root" }
  }
}

variables {
  name                        = "eureka-real"
  environment                 = "devnet"
  txsigner_role_arn           = "arn:aws:iam::111111111111:role/eureka-real-txsigner"
  txsigner_role_name          = "eureka-real-txsigner"
  images                      = { api = "r/api:s", txsigner = "r/txsigner:s", web = "r/web:s", admin = "r/admin:s" }
  db_bootstrap_image_tag      = "s"
  vpc_id                      = "vpc-1"
  subnet_id                   = "subnet-a"
  app_subnet_id               = "subnet-b"
  web_subnet_id               = "subnet-w"
  public_subnet_ids           = ["subnet-p1", "subnet-p2"]
  security_group_id           = "sg-t"
  app_security_group_id       = "sg-a"
  web_admin_security_group_id = "sg-w"
  alb_security_group_id       = "sg-l"
  txsigner_environment        = { TXSIGNER_NETWORK = "DEVNET" }
  app_environment             = { MEMETRADE_REAL_NETWORK = "DEVNET", MEMETRADE_REAL_ENABLED = "false" }
  api_environment             = { SPRING_PROFILES_ACTIVE = "production" }
  worker_environment          = { DB_POOL_MAX_SIZE = "36" }
  db_endpoints                = { app = "app.db", txsigner = "tx.db" }
  db_names                    = { app = "memetrade", txsigner = "txsigner" }
  db_master_secret_arns       = { app = "arn:aws:secretsmanager:sa-east-1:111111111111:secret:rds-app-AbCdEf", txsigner = "arn:aws:secretsmanager:sa-east-1:111111111111:secret:rds-tx-AbCdEf" }
  api_domain                  = "api.x"
  web_domain                  = "app.x"
  admin_domain                = "admin.x"
  certificate_arn             = "arn:aws:acm:sa-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"
  desired_count               = 1
  inject_jupiter_api_key      = false
  log_retention_days          = 90
  app_metrics_namespace       = "Eureka/App"
  app_metric_declarations = [
    { dimensions = [["job"]], metrics = ["process_uptime_seconds", "real_risk_job_runs_total"] },
    { dimensions = [[]], metrics = ["real_kill_switch_active", "txsigner_kms_errors_total"] },
    { dimensions = [["kind"]], metrics = ["txsigner_kms_errors_total"] },
  ]
}

run "secrets_per_container" {
  command = apply
  module {
    source = "./modules/services"
  }
  assert {
    condition = toset([for s in jsondecode(aws_ecs_task_definition.api_worker.container_definitions)[0].secrets : s.name]) == toset([
    "DB_PASSWORD", "DB_RETENTION_PASSWORD", "ACCESS_TOKEN_SIGNING_KEY", "MEMETRADE_AUDIT_WALLET_HMAC_KEY", "MEMETRADE_TXSIGNER_TOKEN"])
    error_message = "api secrets differ"
  }
  assert {
    condition = toset([for s in jsondecode(aws_ecs_task_definition.api_worker.container_definitions)[1].secrets : s.name]) == toset([
      "DB_PASSWORD", "MEMETRADE_AUDIT_WALLET_HMAC_KEY", "MEMETRADE_TXSIGNER_TOKEN", "MEMETRADE_RPC_PRIMARY_URL",
    "MEMETRADE_RPC_SECONDARY_URL", "COINGECKO_API_KEY", "MEMETRADE_GITHUB_ACTIONS_TOKEN"])
    error_message = "worker secrets differ"
  }
  assert {
    condition = length([for c in concat(
      jsondecode(aws_ecs_task_definition.api_worker.container_definitions),
      jsondecode(aws_ecs_task_definition.txsigner.container_definitions),
      jsondecode(aws_ecs_task_definition.frontend["web"].container_definitions),
      jsondecode(aws_ecs_task_definition.frontend["admin"].container_definitions),
      jsondecode(aws_ecs_task_definition.db_migrate.container_definitions),
      jsondecode(aws_ecs_task_definition.txsigner_migrate.container_definitions),
    ) : c.name if length([for e in try(c.environment, []) : e.name if can(regex("PASSWORD|SECRET_KEY|SIGNING_KEY|HMAC_KEY$|API_KEY|_TOKEN$|RPC_.*_URL|^NEXT_PUBLIC_", e.name))]) > 0]) == 0
    error_message = "a secret-like name is in a plain environment variable"
  }
  assert {
    condition     = !can(jsondecode(aws_ecs_task_definition.frontend["web"].container_definitions)[0].secrets) && !can(jsondecode(aws_ecs_task_definition.frontend["admin"].container_definitions)[0].secrets)
    error_message = "web has secrets"
  }
  assert {
    condition     = jsondecode(aws_ecs_task_definition.api_worker.container_definitions)[1].healthCheck.command[6] == "http://127.0.0.1:8082/actuator/health/liveness"
    error_message = "worker health probe not on 8082"
  }
}

run "jupiter_key_only_in_worker_when_enabled" {
  command = apply
  module {
    source = "./modules/services"
  }
  variables {
    inject_jupiter_api_key = true
  }
  assert {
    condition     = contains([for s in jsondecode(aws_ecs_task_definition.api_worker.container_definitions)[1].secrets : s.name], "MEMETRADE_JUPITER_API_KEY")
    error_message = "worker lacks the Jupiter key"
  }
  assert {
    condition     = !contains([for s in jsondecode(aws_ecs_task_definition.api_worker.container_definitions)[0].secrets : s.name], "MEMETRADE_JUPITER_API_KEY")
    error_message = "api received the Jupiter key"
  }
}

# Observability Setup Q1: the CloudWatch Agent sidecar in the api+worker and TxSigner tasks, not essential, read-only
# root, pinned by digest from the own repository, with a task role limited to its own metrics log group.
run "metrics_agent_sidecar" {
  command = apply
  module {
    source = "./modules/services"
  }

  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.api_worker.container_definitions) : c.name] == ["api", "worker", "metrics-agent"]
    error_message = "the api+worker task is not api, worker and the metrics sidecar"
  }
  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.txsigner.container_definitions) : c.name] == ["txsigner", "metrics-agent"]
    error_message = "the TxSigner task is not the TxSigner and the metrics sidecar"
  }
  assert {
    condition = alltrue([for c in concat(
      jsondecode(aws_ecs_task_definition.api_worker.container_definitions),
      jsondecode(aws_ecs_task_definition.txsigner.container_definitions),
    ) : c.readonlyRootFilesystem == true])
    error_message = "a container of the api+worker or TxSigner task has a writable root file system"
  }
  assert {
    condition = alltrue([for c in concat(
      jsondecode(aws_ecs_task_definition.api_worker.container_definitions),
      jsondecode(aws_ecs_task_definition.txsigner.container_definitions),
      ) : c.essential == false && c.image == "${aws_ecr_repository.cloudwatch_agent.repository_url}@sha256:cc169bdfffee5f795cd0a534660d6721d987b1cb9e0a39df8174147a36ae0086" && !can(c.secrets) && c.memory == 128
    if c.name == "metrics-agent"])
    error_message = "the sidecar must be non-essential, pinned by digest in the own repository, without secrets, 128 MB"
  }
  assert {
    condition     = aws_ecr_repository.cloudwatch_agent.name == "eureka-real/cloudwatch-agent" && aws_ecr_repository.cloudwatch_agent.image_tag_mutability == "IMMUTABLE"
    error_message = "the agent image must live in the own immutable repository eureka-real/cloudwatch-agent"
  }
  assert {
    condition     = aws_ecs_task_definition.api_worker.memory == "3072" && aws_ecs_task_definition.api_worker.task_role_arn == aws_iam_role.api_worker_task.arn
    error_message = "the api+worker task needs 3 GB and its metrics task role"
  }
  # Least privilege: one statement, the three log actions, only the own metrics log group; no PutMetricData, no wildcard.
  assert {
    condition = alltrue([for family, policy in output.metrics_agent_config : (
      length(jsondecode(policy.policy).Statement) == 1
      && toset(jsondecode(policy.policy).Statement[0].Action) == toset(["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"])
      && toset(jsondecode(policy.policy).Statement[0].Resource) == toset([aws_cloudwatch_log_group.metrics[family].arn, "${aws_cloudwatch_log_group.metrics[family].arn}:*"])
    )])
    error_message = "a metrics task policy does more than EMF to its own metrics log group"
  }
  assert {
    condition     = aws_iam_role_policy.metrics_agent["txsigner"].role == "eureka-real-txsigner" && aws_iam_role_policy.metrics_agent["api-worker"].role == aws_iam_role.api_worker_task.name
    error_message = "the metrics policies are not on the TxSigner role and the api+worker task role"
  }
  assert {
    condition     = jsondecode(aws_iam_role.api_worker_task.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "111111111111"
    error_message = "the api+worker task role trusts tasks of another account"
  }
  # The agent configuration: namespace, EMF declarations and the Prometheus keep filter come from the allow-list.
  assert {
    condition     = jsondecode(output.metrics_agent_config["api-worker"].agent).logs.metrics_collected.prometheus.emf_processor.metric_namespace == "Eureka/App"
    error_message = "the agent does not publish to the namespace of the alarms"
  }
  assert {
    condition     = length(jsondecode(output.metrics_agent_config["txsigner"].agent).logs.metrics_collected.prometheus.emf_processor.metric_declaration) == length(var.app_metric_declarations)
    error_message = "the EMF declarations differ from the allow-list"
  }
  assert {
    condition = [for job in yamldecode(output.metrics_agent_config["api-worker"].prometheus).scrape_configs : "${job.job_name}=${job.static_configs[0].targets[0]}"] == [
    "api=127.0.0.1:8081", "worker=127.0.0.1:8082"]
    error_message = "the api+worker sidecar does not scrape the api on 8081 and the worker on 8082"
  }
  assert {
    condition     = yamldecode(output.metrics_agent_config["txsigner"].prometheus).scrape_configs[0].static_configs[0].targets[0] == "127.0.0.1:8444"
    error_message = "the TxSigner sidecar does not scrape the management port 8444"
  }
  assert {
    condition = alltrue([for family, config in output.metrics_agent_config : alltrue([for job in yamldecode(config.prometheus).scrape_configs :
      job.honor_labels == true && job.metric_relabel_configs[0].action == "keep"
    && job.metric_relabel_configs[0].regex == "(process_uptime_seconds|real_kill_switch_active|real_risk_job_runs_total|txsigner_kms_errors_total)"])])
    error_message = "a scrape job does not keep only the allow-list, or lets the scrape job overwrite the job label"
  }
  # deploy.yml checks MEMETRADE_REAL_NETWORK on every container of the api-worker family (cd-config.md).
  assert {
    condition     = [for e in jsondecode(aws_ecs_task_definition.api_worker.container_definitions)[2].environment : e.value if e.name == "MEMETRADE_REAL_NETWORK"] == ["DEVNET"]
    error_message = "the sidecar of the api-worker family would fail the network check of the deploy"
  }
}
