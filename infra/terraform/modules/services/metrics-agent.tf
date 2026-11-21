# CloudWatch Agent sidecar of the api+worker task and of the TxSigner task (261113-trading-real-bot Observability Setup,
# Q1; closes SP-12 in the IaC, pending the devnet check). The agent scrapes /actuator/prometheus on localhost (the
# management ports of the task: api 8081, worker 8082, TxSigner 8444) every 120 seconds (owner's decision of 2026-11-20;
# the alarm windows follow it, modules/observability/app-metrics.tf) and publishes, through the
# embedded metric format (EMF) of CloudWatch Logs, only the metrics of the allow-list of modules/observability
# (app-metrics.tf), in one namespace (Eureka/App). The application code does not change.
#
# Security (operation phase rule; observability-setup alarms.md, "Implicações de segurança"):
# - The image is the public CloudWatch Agent image, COPIED by the founder into an own ECR repository and pinned by its
#   digest: the execution roles pull only own repositories and the VPC endpoints accept only resources of this account
#   (SP-05), and subnet A (TxSigner) has no internet.
# - The agent sends PutLogEvents through the logs VPC endpoint (its policy already allows logs:* for principals and
#   resources of this account). It never calls cloudwatch:PutMetricData: CloudWatch extracts the metrics from the EMF
#   log events. The least privilege is therefore logs:CreateLogStream/PutLogEvents/DescribeLogStreams on the own metrics
#   log group of the task, and nothing else. IAM cannot confine an EMF namespace (no cloudwatch:namespace condition on
#   PutLogEvents); the namespace is fixed by the agent configuration below and checked by the offline test.
# - The api+worker task gets its FIRST task role for this (until now no AWS permission at all, NFR1.2): the api and the
#   worker containers can also use these credentials, so a compromised application could write fake datapoints into
#   its own metrics log group (never read a secret, a key or another log group). Accepted risk, recorded in alarms.md.
# - The TxSigner task role (KMS role of modules/kms) gains the same permission on its own metrics log group only.
# - The sidecar is not essential (a crash of the agent never stops the api, the worker or the TxSigner; the alarms
#   metrics-absent-* fire instead), has a read-only root file system and no secret.

variable "app_metrics_namespace" {
  description = "Namespace of the application metrics (modules/observability output app_metrics_namespace)"
  type        = string
}

variable "app_metric_declarations" {
  description = "Allow-list of the agent (modules/observability output app_metric_declarations): metric names and dimension sets"
  type = list(object({
    dimensions = list(list(string))
    metrics    = list(string)
  }))
}

variable "cloudwatch_agent_image_digest" {
  description = "Digest of the linux/amd64 manifest of public.ecr.aws/cloudwatch-agent/cloudwatch-agent copied to <name>/cloudwatch-agent. Default: tag 1.300074.1b2005 (built 2026-10-27), read from the public registry on 2026-11-20"
  type        = string
  default     = "sha256:cc169bdfffee5f795cd0a534660d6721d987b1cb9e0a39df8174147a36ae0086"
  validation {
    condition     = can(regex("^sha256:[0-9a-f]{64}$", var.cloudwatch_agent_image_digest))
    error_message = "cloudwatch_agent_image_digest must be a sha256 digest (pin by digest, never by tag)."
  }
}

locals {
  # Scrape jobs of each sidecar: job name => target on the loopback of the task (awsvpc: one network namespace).
  metrics_agent_scrape = {
    api-worker = { api = "127.0.0.1:8081", worker = "127.0.0.1:8082" }
    txsigner   = { txsigner = "127.0.0.1:8444" }
  }

  metrics_log_groups = { for family in keys(local.metrics_agent_scrape) : family => "/ecs/${var.name}/metrics/${family}" }

  agent_metric_names = sort(distinct(flatten([for declaration in var.app_metric_declarations : declaration.metrics])))

  # Prometheus side of the allow-list: everything else is dropped right after the scrape, so it is neither converted
  # nor sent as a log event (the agent sends every kept series as a log event, declared or not).
  agent_keep_regex = "(${join("|", local.agent_metric_names)})"

  prometheus_config = {
    for family, jobs in local.metrics_agent_scrape : family => yamlencode({
      global = { scrape_interval = "120s", scrape_timeout = "10s" }
      scrape_configs = [for job, target in jobs : {
        job_name     = job
        honor_labels = true
        metrics_path = "/actuator/prometheus"
        scheme       = "http"
        sample_limit = 5000
        static_configs = [{
          targets = [target]
        }]
        metric_relabel_configs = [{
          source_labels = ["__name__"]
          regex         = local.agent_keep_regex
          action        = "keep"
        }]
      }]
    })
  }

  agent_config = {
    for family in keys(local.metrics_agent_scrape) : family => jsonencode({
      agent = { region = local.region, debug = false, usage_data = false }
      logs = {
        force_flush_interval = 15
        metrics_collected = {
          prometheus = {
            cluster_name           = var.name
            log_group_name         = local.metrics_log_groups[family]
            prometheus_config_path = "env:PROMETHEUS_CONFIG_CONTENT"
            emf_processor = {
              metric_declaration_dedup = true
              metric_namespace         = var.app_metrics_namespace
              metric_declaration = [for declaration in var.app_metric_declarations : {
                source_labels    = ["job"]
                label_matcher    = ".*"
                dimensions       = declaration.dimensions
                metric_selectors = [for metric in declaration.metrics : "^${metric}$"]
              }]
            }
          }
        }
      }
    })
  }

  # The agent writes only under these paths (checked with the pinned image: etc and var ship empty).
  metrics_agent_paths = {
    cwagent-etc  = "/opt/aws/amazon-cloudwatch-agent/etc"
    cwagent-var  = "/opt/aws/amazon-cloudwatch-agent/var"
    cwagent-logs = "/opt/aws/amazon-cloudwatch-agent/logs"
    cwagent-tmp  = "/tmp"
  }

  metrics_agent_container = {
    for family in keys(local.metrics_agent_scrape) : family => {
      name                   = "metrics-agent"
      image                  = "${aws_ecr_repository.cloudwatch_agent.repository_url}@${var.cloudwatch_agent_image_digest}"
      essential              = false
      cpu                    = 64
      memory                 = 128
      readonlyRootFilesystem = true
      mountPoints            = [for volume, path in local.metrics_agent_paths : { sourceVolume = volume, containerPath = path, readOnly = false }]
      environment = concat(
        [
          { name = "CW_CONFIG_CONTENT", value = local.agent_config[family] },
          { name = "PROMETHEUS_CONFIG_CONTENT", value = local.prometheus_config[family] },
        ],
        # deploy.yml (cd-config.md) checks that EVERY container of the api-worker family carries the network of the
        # target; the agent ignores it. Until the deploy check names the application containers only (open item of
        # alarms.md), the sidecar repeats it.
        family == "api-worker" ? [{ name = "MEMETRADE_REAL_NETWORK", value = lookup(var.app_environment, "MEMETRADE_REAL_NETWORK", "") }] : [],
      )
      logConfiguration = local.log_configuration[family]
    }
  }
}

resource "aws_ecr_repository" "cloudwatch_agent" {
  # checkov:skip=CKV_AWS_136:AES256 like the other repositories of the pilot (modules/delivery); the image is public and holds no secret.
  name                 = "${var.name}/cloudwatch-agent"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "cloudwatch_agent" {
  repository = aws_ecr_repository.cloudwatch_agent.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 3 agent images (the pinned digest and the rollback target)"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 3 }
      action       = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "metrics" {
  # checkov:skip=CKV_AWS_158:CloudWatch Logs encrypts at rest with its own key; the EMF events carry only metric values and closed labels (no personal data).
  # checkov:skip=CKV_AWS_338:7 days on purpose: these log events only carry the metrics, which CloudWatch keeps for 15 months once extracted.
  for_each          = local.metrics_log_groups
  name              = each.value
  retention_in_days = 7
}

# First task role of the api+worker task: the metrics of the sidecar only (see the header).
resource "aws_iam_role" "api_worker_task" {
  name        = "${var.name}-api-worker-task"
  description = "Task role of ${var.name}-api-worker: EMF of the CloudWatch Agent sidecar to its own metrics log group, nothing else"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "EcsTasksOfThisAccountOnly"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:aws:ecs:${local.region}:${local.account_id}:*" }
      }
    }]
  })
}

locals {
  metrics_task_roles = {
    api-worker = aws_iam_role.api_worker_task.name
    txsigner   = var.txsigner_role_name
  }

  metrics_agent_policy = {
    for family in keys(local.metrics_agent_scrape) : family => jsonencode({
      Version = "2012-10-17"
      Statement = [{
        Sid      = "EmfToItsOwnMetricsLogGroupOnly"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = [aws_cloudwatch_log_group.metrics[family].arn, "${aws_cloudwatch_log_group.metrics[family].arn}:*"]
      }]
    })
  }
}

resource "aws_iam_role_policy" "metrics_agent" {
  for_each = local.metrics_task_roles
  name     = "${var.name}-${each.key}-metrics-agent"
  role     = each.value
  policy   = local.metrics_agent_policy[each.key]
}

output "api_worker_task_role_arn" {
  description = "Task role of the api+worker family (metrics sidecar only); the deploy role must be able to pass it"
  value       = aws_iam_role.api_worker_task.arn
}

output "metrics_log_group_names" {
  value = local.metrics_log_groups
}

output "log_group_names" {
  description = "Log groups of the services (Logs Insights saved queries)"
  value       = local.log_groups
}

output "cloudwatch_agent_repository_url" {
  description = "Where the founder copies the agent image by digest (crane copy or docker buildx imagetools create)"
  value       = aws_ecr_repository.cloudwatch_agent.repository_url
}

output "cloudwatch_agent_image" {
  value = "${aws_ecr_repository.cloudwatch_agent.repository_url}@${var.cloudwatch_agent_image_digest}"
}

output "api_load_balancer_arn_suffix" {
  value = aws_lb.main.arn_suffix
}

output "api_target_group_arn_suffix" {
  value = aws_lb_target_group.api.arn_suffix
}

# Offline tests and the harness of the agent (alarms.md, "Validação").
output "metrics_agent_config" {
  value = {
    for family in keys(local.metrics_agent_scrape) : family => {
      agent      = local.agent_config[family]
      prometheus = local.prometheus_config[family]
      policy     = local.metrics_agent_policy[family]
      scrape     = local.metrics_agent_scrape[family]
    }
  }
}
