# ECS Fargate services of the pilot (infrastructure-specification.md "Deployment"; 261113-trading-real-bot Deployment
# Pipeline cd-config.md and Environment Provisioning): the TxSigner, the task api+worker, the web and the admin, one
# zone, Service Connect with a private Cloud Map namespace (txsigner.internal:8443). Every service keeps
# minimumHealthyPercent 100 and maximumPercent 200 with the circuit breaker; the revision in service is chosen by
# deploy.yml. Only the TxSigner task assumes the KMS role; the web and the admin have NO task role, and the api+worker
# task has only the role of its metrics sidecar (EMF to its own metrics log group, metrics-agent.tf), no other AWS
# permission (NFR1.2). Each task family has its own execution role, which pulls only its images,
# writes only its log group and reads only its own secrets (iam.tf).
#
# Files: main.tf (cluster, namespaces, TxSigner), api-worker.tf, web-admin.tf, one-off.tf (migrations and bootstrap),
# alb.tf, secrets.tf, iam.tf, logs.tf, coingecko.tf and github-actions.tf (secrets of the U2 and the U5).

variable "name" {
  type = string
}

variable "environment" {
  description = "devnet or mainnet"
  type        = string
}

variable "txsigner_role_arn" {
  type = string
}

variable "txsigner_role_name" {
  type = string
}

variable "images" {
  description = "Initial image of each family (api, txsigner, web, admin), in the ECR repository of this environment; deploy.yml replaces it by digest"
  type        = map(string)
}

variable "db_bootstrap_image_tag" {
  description = "Tag of the db-bootstrap image pushed by hand by the founder (infra/postgres/bootstrap/Dockerfile)"
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "Private subnet A (TxSigner, no internet)"
  type        = string
}

variable "app_subnet_id" {
  description = "Private subnet B (api+worker, NAT)"
  type        = string
}

variable "web_subnet_id" {
  description = "Private subnet of the web and admin (no internet)"
  type        = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  description = "Security group of the TxSigner"
  type        = string
}

variable "app_security_group_id" {
  type = string
}

variable "web_admin_security_group_id" {
  type = string
}

variable "alb_security_group_id" {
  type = string
}

variable "txsigner_environment" {
  description = "Non-secret environment of the TxSigner (secrets come from the secrets manager)"
  type        = map(string)
}

variable "app_environment" {
  description = "Non-secret environment shared by the api and the worker containers"
  type        = map(string)
}

variable "api_environment" {
  description = "Non-secret environment of the api container only"
  type        = map(string)
}

variable "worker_environment" {
  description = "Non-secret environment of the worker container only"
  type        = map(string)
}

variable "db_endpoints" {
  type = map(string)
}

variable "db_names" {
  type = map(string)
}

variable "db_master_secret_arns" {
  type = map(string)
}

variable "api_domain" {
  type = string
}

variable "web_domain" {
  type = string
}

variable "admin_domain" {
  type = string
}

variable "certificate_arn" {
  description = "ACM certificate (same region) covering the three domains, requested and validated by the founder"
  type        = string
}

variable "desired_count" {
  description = "Tasks per long-running service: 0 on the very first apply (before the bootstrap and the migrations), then 1"
  type        = number
}

variable "inject_jupiter_api_key" {
  description = "Inject MEMETRADE_JUPITER_API_KEY into the worker; true only after the recording of the Trigger (U7 BR0), with a value set"
  type        = bool
}

variable "log_retention_days" {
  type = number
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name
  # Only the mainnet load balancer refuses a destroy; the devnet can be torn down by code.
  deletion_protection = var.environment == "mainnet"
}

resource "aws_ecs_cluster" "main" {
  name = var.name
  # CKV_AWS_65: task and service metrics (CPU, memory, restarts) for the health metric of every component.
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_service_discovery_http_namespace" "internal" {
  name = "internal"
}

# DNS name of the api for the SERVER side of the web and admin (API_INTERNAL_ORIGIN): plain A records of the api+worker
# task, without a proxy, so the api sees the real address of the web/admin servers (exempt range of the legal limit).
resource "aws_service_discovery_private_dns_namespace" "app" {
  name = "${var.name}.internal"
  vpc  = var.vpc_id
}

resource "aws_service_discovery_service" "api" {
  name = "api"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.app.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
  health_check_custom_config {
    failure_threshold = 1
  }
}

# JVM containers run with a read-only root file system and a task-local /tmp (Tomcat work dir, hsperfdata, HealthProbe).
locals {
  tmp_mount = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
}

resource "aws_ecs_task_definition" "txsigner" {
  family                   = "${var.name}-txsigner"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  task_role_arn            = var.txsigner_role_arn
  execution_role_arn       = aws_iam_role.execution["txsigner"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "tmp"
  }
  # Observability Setup Q1: volumes of the CloudWatch Agent sidecar (metrics-agent.tf).
  dynamic "volume" {
    for_each = local.metrics_agent_paths
    content {
      name = volume.key
    }
  }
  # The TxSigner container has no hard memory limit (-Xmx384m in its image); the sidecar takes at most 128 of the 1024 MB.
  container_definitions = jsonencode([{
    name                   = "txsigner"
    image                  = var.images["txsigner"]
    essential              = true
    readonlyRootFilesystem = true
    mountPoints            = local.tmp_mount
    portMappings           = [{ name = "txsigner", containerPort = 8443, protocol = "tcp" }]
    environment = [for k, v in merge(var.txsigner_environment, {
      TXSIGNER_CREDENTIAL_SECRET_ID = aws_secretsmanager_secret.this["txsigner-service-token"].arn
    }) : { name = k, value = v }]
    # The service token is NOT injected here: the TxSigner reads AWSCURRENT and AWSPREVIOUS itself every 5 minutes
    # with its task role (TXSIGNER_CREDENTIAL_SECRET_ID), so the 24 h overlap of a rotation needs no restart.
    secrets = [
      { name = "TXSIGNER_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.this["txsigner-db-app-password"].arn },
    ]
    logConfiguration = local.log_configuration["txsigner"]
    # Liveness only (process alive): a slow KMS takes the task out of readiness but never restarts it. The deploy
    # checks the readiness (database and ListKeys) with a one-off probe before it moves on (cd-config.md).
    healthCheck = {
      command     = ["CMD-SHELL", "wget -q -O - http://127.0.0.1:8444/actuator/health/liveness || exit 1"]
      interval    = 10
      timeout     = 5
      retries     = 6
      startPeriod = 60
    }
  }, local.metrics_agent_container["txsigner"]])
}

resource "aws_ecs_service" "txsigner" {
  name                               = "txsigner"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.txsigner.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # 261113-trading-real-bot Deployment Pipeline: a new revision that never becomes healthy is rolled back by ECS to the
  # revision that was running (deployment-strategy.md).
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # The revision in service is chosen by the deploy workflow (image by digest of the commit SHA). Terraform registers
  # new revisions (flags, resources) but never rolls them out by itself: a flag change reaches the service only through
  # an audited run of deploy.yml with the SHA in service (cd-config.md, "Flags").
  lifecycle {
    ignore_changes = [task_definition]
  }

  network_configuration {
    subnets          = [var.subnet_id]
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.internal.arn
    service {
      port_name = "txsigner"
      client_alias {
        dns_name = "txsigner.internal"
        port     = 8443
      }
    }
  }
}
