# Offline checks of the Observability Setup of 261113-trading-real-bot (SP-12): every CloudWatch alarm reads a metric
# that really reaches CloudWatch, either through the allow-list of the CloudWatch Agent sidecar (Eureka/App, with one of
# its declared dimension sets) or from another real source (the CloudTrail metric filters, the load balancer); every
# dashboard metric of Eureka/App is in the allow-list; the sidecars publish exactly that allow-list; the burn-rate
# thresholds follow the formula of slo-config.md. mock_provider: the "apply" is simulated in memory, no AWS credential,
# account nor network call. Run from infra/terraform: terraform init -backend=false && terraform test

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
  mock_data "aws_elb_service_account" {
    defaults = { arn = "arn:aws:iam::507241528517:root" }
  }
  mock_resource "aws_db_instance" {
    defaults = { master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:sa-east-1:111111111111:secret:rds-master-AbCdEf", kms_key_id = "k", secret_status = "active" }] }
  }
  mock_resource "aws_vpc_endpoint" {
    defaults = { dns_entry = [{ dns_name = "vpce-1.kms.sa-east-1.vpce.amazonaws.com", hosted_zone_id = "Z" }] }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111111111111:role/mock" }
  }
  mock_resource "aws_iam_policy" {
    defaults = { arn = "arn:aws:iam::111111111111:policy/mock" }
  }
  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:sa-east-1:111111111111:secret:mock" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:sa-east-1:111111111111:log-group:/mock" }
  }
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:sa-east-1:111111111111:key/mock" }
  }
  mock_resource "aws_sns_topic" {
    defaults = { arn = "arn:aws:sns:sa-east-1:111111111111:eureka-real-alarms" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock", id = "mock" }
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
  mock_resource "aws_lb" {
    defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:111111111111:loadbalancer/app/eureka-real/1", arn_suffix = "app/eureka-real/1" }
  }
  mock_resource "aws_lb_target_group" {
    defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:111111111111:targetgroup/eureka-real-api/1", arn_suffix = "targetgroup/eureka-real-api/1" }
  }
  mock_resource "aws_lb_listener" {
    defaults = { arn = "arn:aws:elasticloadbalancing:sa-east-1:111111111111:listener/app/eureka-real/1/1" }
  }
  mock_resource "aws_ecr_repository" {
    defaults = { repository_url = "111111111111.dkr.ecr.sa-east-1.amazonaws.com/eureka-real/cloudwatch-agent", arn = "arn:aws:ecr:sa-east-1:111111111111:repository/eureka-real/cloudwatch-agent" }
  }
  mock_resource "aws_cloudwatch_metric_alarm" {
    defaults = { arn = "arn:aws:cloudwatch:sa-east-1:111111111111:alarm:mock" }
  }
}

variables {
  environment                  = "devnet"
  account_id                   = "111111111111"
  region                       = "sa-east-1"
  zone                         = "sa-east-1a"
  zone_b                       = "sa-east-1b"
  initial_image_tag            = "3497b2bc49052f01adb93de7094a05880532222b"
  db_bootstrap_image_tag       = "3497b2bc49052f01adb93de7094a05880532222b"
  api_domain                   = "api.devnet.example.com"
  web_domain                   = "app.devnet.example.com"
  admin_domain                 = "admin.devnet.example.com"
  certificate_arn              = "arn:aws:acm:sa-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"
  admin_wallet_addresses       = "11111111111111111111111111111111"
  legal_storage_location       = "x"
  legal_international_transfer = "x"
  legal_dpo_email              = "dpo@example.com"
  alarm_email                  = "founder@example.com"
}

# The deploy role is not under test here (security_delivery.tftest.hcl covers it): with one mocked ARN for every role,
# its SP-01 precondition would see the founder-only roles in passable_role_arns.
override_module {
  target = module.delivery
  outputs = {
    deploy_role_arn = "arn:aws:iam::111111111111:role/eureka-real-deploy"
    ecr_repository_urls = {
      api      = "111111111111.dkr.ecr.sa-east-1.amazonaws.com/eureka-real/api"
      txsigner = "111111111111.dkr.ecr.sa-east-1.amazonaws.com/eureka-real/txsigner"
      web      = "111111111111.dkr.ecr.sa-east-1.amazonaws.com/eureka-real/web"
      admin    = "111111111111.dkr.ecr.sa-east-1.amazonaws.com/eureka-real/admin"
    }
  }
}

run "every_alarm_reads_a_published_metric" {
  command = apply

  # The inventory of the module lists every alarm resource block written in its files (an alarm added without being
  # listed, and so without this check, fails here).
  assert {
    condition = toset(module.observability.alarm_resource_names) == toset(flatten([
      for f in fileset("./modules/observability", "*.tf") : [
        for m in regexall("resource \"aws_cloudwatch_metric_alarm\" \"([a-z0-9_]+)\"", file("./modules/observability/${f}")) : m[0]
      ]
    ]))
    error_message = "alarm_resource_names of modules/observability/app-metrics.tf differs from the alarm blocks of the module"
  }
  # Eureka/App: the metric and its exact dimension set are declared to the agent. Other namespaces: a real source.
  assert {
    condition = alltrue([for read in module.observability.alarm_metric_reads : (
      read.namespace == module.observability.app_metrics_namespace
      ? contains(lookup(module.observability.app_metric_dimension_sets, read.metric, []), read.dimensions)
      : contains(lookup(module.observability.other_metric_sources, read.namespace, []), read.metric)
    )])
    error_message = "an alarm reads a metric nobody publishes: ${jsonencode([for read in module.observability.alarm_metric_reads : "${read.alarm}: ${read.namespace}/${read.metric}{${read.dimensions}}" if !(read.namespace == module.observability.app_metrics_namespace ? contains(lookup(module.observability.app_metric_dimension_sets, read.metric, []), read.dimensions) : contains(lookup(module.observability.other_metric_sources, read.namespace, []), read.metric))])}"
  }
  # Standard resolution of the EMF metrics: only whole minutes. The agent reads every 120 s: on Eureka/App, a period
  # with missing data breaching (or of a stalled job) is a multiple of 240 s, so it always holds two reads; any other
  # period is at least 180 s, so it always holds one read.
  assert {
    condition = alltrue([for read in module.observability.alarm_metric_reads : read.period % 60 == 0 && (
      read.namespace != module.observability.app_metrics_namespace ? read.period >= 60 : (
        read.breaching || strcontains(read.alarm, "job-stalled") ? read.period % 240 == 0 : read.period >= 180
      )
    )])
    error_message = "an alarm period does not hold the reads of the agent (120 s): ${jsonencode([for read in module.observability.alarm_metric_reads : "${read.alarm}: ${read.period}" if read.namespace == module.observability.app_metrics_namespace && !(read.breaching || strcontains(read.alarm, "job-stalled") ? read.period % 240 == 0 : read.period >= 180)])}"
  }
  assert {
    condition     = alltrue([for family, config in module.services.metrics_agent_config : yamldecode(config.prometheus).global.scrape_interval == "120s"])
    error_message = "the agent must read every 120 s (the alarm windows depend on it)"
  }
  # Every alarm notifies the one SNS topic of the pilot (Q7).
  assert {
    condition     = alltrue([for read in module.observability.alarm_metric_reads : length(read.actions) == 1 && read.actions[0] == "arn:aws:sns:sa-east-1:111111111111:eureka-real-alarms"])
    error_message = "an alarm does not notify the alarm topic"
  }
  # The p95 alarms are off until the code publishes their summaries (OBS-G1, OBS-G2).
  assert {
    condition     = !contains(module.observability.alarm_names, "eureka-real-real-risk-slow") && !contains(module.observability.alarm_names, "eureka-real-real-execution-slow")
    error_message = "the p95 alarms must stay off by default"
  }
  # Q2: one heartbeat per process.
  assert {
    condition     = toset([for name in module.observability.alarm_names : name if startswith(name, "eureka-real-metrics-absent-")]) == toset(["eureka-real-metrics-absent-api", "eureka-real-metrics-absent-worker", "eureka-real-metrics-absent-txsigner"])
    error_message = "a process has no metrics-absent alarm"
  }
  # Every Eureka/App metric of the three dashboards is in the allow-list.
  assert {
    condition     = length(setsubtract(toset(module.observability.dashboard_app_metrics), toset(module.observability.app_metric_names))) == 0
    error_message = "a dashboard draws a metric outside the allow-list: ${jsonencode(setsubtract(toset(module.observability.dashboard_app_metrics), toset(module.observability.app_metric_names)))}"
  }
  assert {
    condition     = length(module.observability.dashboard_names) == 3 && length(module.observability.log_query_names) == 5
    error_message = "three dashboards and five saved queries expected"
  }
  # The sidecars publish exactly the allow-list, to the namespace of the alarms.
  assert {
    condition = alltrue([for family, config in module.services.metrics_agent_config : (
      jsondecode(config.agent).logs.metrics_collected.prometheus.emf_processor.metric_namespace == module.observability.app_metrics_namespace
      && toset(flatten([for d in jsondecode(config.agent).logs.metrics_collected.prometheus.emf_processor.metric_declaration : [for s in d.metric_selectors : trimsuffix(trimprefix(s, "^"), "$")]])) == toset(module.observability.app_metric_names)
      && alltrue([for job in yamldecode(config.prometheus).scrape_configs : job.metric_relabel_configs[0].regex == "(${join("|", module.observability.app_metric_names)})"])
    )])
    error_message = "a sidecar does not publish exactly the allow-list of the alarms"
  }
  # Burn-rate math (slo-config.md): bad ratio = share x 720 / window hours x (1 - target); slots = ceil(ratio x slots of 240 s).
  assert {
    condition = alltrue(flatten([for kind, target in module.observability.slo_math.targets : [
      for window, spec in module.observability.slo_math.windows : (
        abs(module.observability.slo_math.bad_ratio[kind][window] - spec.budget_share * module.observability.slo_math.budget_hours / (spec.seconds / 3600) * (1 - target)) < 0.000001
        && module.observability.slo_math.bad_slots[kind][window] == ceil(module.observability.slo_math.bad_ratio[kind][window] * spec.seconds / module.observability.slo_math.slot_seconds - 0.000001)
      )
    ]]))
    error_message = "a burn-rate threshold does not follow the formula of slo-config.md"
  }
}

run "p95_alarms_when_the_code_publishes_them" {
  command = apply
  variables {
    p95_alarms_enabled = true
  }
  assert {
    condition     = contains(module.observability.alarm_names, "eureka-real-real-risk-slow") && contains(module.observability.alarm_names, "eureka-real-real-execution-slow")
    error_message = "the two p95 alarms are missing with p95_alarms_enabled"
  }
  assert {
    condition = alltrue([for read in module.observability.alarm_metric_reads : (
      read.namespace == module.observability.app_metrics_namespace
      ? contains(lookup(module.observability.app_metric_dimension_sets, read.metric, []), read.dimensions)
      : contains(lookup(module.observability.other_metric_sources, read.namespace, []), read.metric)
    )])
    error_message = "a p95 alarm reads a metric outside the allow-list"
  }
}
