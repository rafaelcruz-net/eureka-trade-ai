# Cloud of the REAL pilot (infrastructure-specification.md): written in the U1, completed by the Environment
# Provisioning of 261113-trading-real-bot, applied only when the AWS accounts exist, by the founder from his machine
# with the administration role and MFA. NOT applied by the CI: the CI never holds an AWS credential for Terraform.
# One state per environment and account (environment-provisioning-questions.md Q2): the devnet and the mainnet are
# separate member accounts of one AWS Organizations (SCP in infra/terraform/org/, a separate root). The state lives in
# an S3 bucket of the account itself (partial backend configuration given at `terraform init -backend-config=...`).
# Example values: infra/terraform/environments/<env>.tfvars.example (no secret there: secrets are set by hand in
# Secrets Manager, see validation-report.md of the Environment Provisioning).

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    # 5.81 is the first 5.x where the GitHub OIDC provider needs no thumbprint (modules/delivery).
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.81"
    }
  }
  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]
  default_tags {
    tags = {
      Project     = "eureka-trader-ai"
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = "founder"
    }
  }
}

variable "region" {
  type = string
}

variable "account_id" {
  description = "AWS account of this environment; the provider refuses any other credential"
  type        = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "account_id must be a 12-digit AWS account id."
  }
}

variable "zone" {
  description = "Zone of every workload"
  type        = string
}

variable "zone_b" {
  description = "Second zone: second leg of the load balancer and of the database subnet groups only"
  type        = string
}

variable "name" {
  type    = string
  default = "eureka-real"
}

variable "initial_image_tag" {
  description = "Commit SHA of the first images pushed to the ECR repositories (api, txsigner, web, admin); deploy.yml replaces them by digest afterwards"
  type        = string
  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.initial_image_tag))
    error_message = "initial_image_tag must be a full commit SHA (40 hexadecimal characters)."
  }
}

variable "db_bootstrap_image_tag" {
  description = "Tag of the db-bootstrap image (infra/postgres/bootstrap/Dockerfile), usually the same commit SHA"
  type        = string
}

variable "alarm_topic_arn" {
  description = "SNS topic of the alarms; null creates <name>-alarms with an e-mail subscription to alarm_email"
  type        = string
  default     = null
}

variable "alarm_email" {
  description = "E-mail subscribed to the alarm topic created here (confirmed by hand); ignored with alarm_topic_arn"
  type        = string
  default     = null
}

# 261113-trading-real-bot Deployment Pipeline (cd-config.md): one state per environment; the deploy role trusts only the
# GitHub Environment of the same name.
variable "environment" {
  description = "devnet or mainnet"
  type        = string
  validation {
    condition     = contains(["devnet", "mainnet"], var.environment)
    error_message = "environment must be devnet or mainnet."
  }
}

variable "github_repository" {
  type    = string
  default = "rafaelcruz-net/eureka-trade-ai"
}

variable "create_github_oidc_provider" {
  description = "false when another state of the same AWS account already created the GitHub OIDC provider"
  type        = bool
  default     = true
}

variable "deploy_passable_role_arns" {
  description = "Extra roles the deploy may pass to ECS; the api-worker, web, admin and db-migrate execution roles are always added. A founder-only role here fails the plan (SP-01)"
  type        = list(string)
  default     = []
}

# --- Environment Provisioning -------------------------------------------------------------------------------------

variable "api_domain" {
  description = "Host of the api (for example api.<domain>); same registrable domain as the web and the admin"
  type        = string
}

variable "web_domain" {
  type = string
}

variable "admin_domain" {
  type = string
}

variable "certificate_arn" {
  description = "ACM certificate of this region covering the three hosts, requested and DNS-validated by the founder"
  type        = string
}

variable "admin_wallet_addresses" {
  description = "Comma-separated public wallet addresses of the administrators (not a secret, BR1.5)"
  type        = string
}

variable "founder_user_id" {
  description = "UUID of the founder's user (MEMETRADE_REAL_FOUNDER_USER_ID); required on the mainnet"
  type        = string
  default     = ""
}

variable "legal_storage_location" {
  description = "MEMETRADE_LEGAL_STORAGE_LOCATION of the privacy policy (deployment field)"
  type        = string
}

variable "legal_international_transfer" {
  description = "MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER of the privacy policy (deployment field)"
  type        = string
}

variable "legal_dpo_email" {
  description = "MEMETRADE_LEGAL_DPO_EMAIL of the privacy policy (deployment field)"
  type        = string
}

variable "service_desired_count" {
  description = "Tasks per long-running service: 0 on the very first apply (before the bootstrap and the migrations), then 1"
  type        = number
  default     = 1
  validation {
    condition     = contains([0, 1], var.service_desired_count)
    error_message = "service_desired_count is 0 (first apply) or 1 (pilot: one task per service)."
  }
}

variable "inject_jupiter_api_key" {
  description = "true only after the real recording of the Jupiter Trigger (U7 BR0) and with a value in the secret"
  type        = bool
  default     = false
}

variable "log_retention_days" {
  type    = number
  default = 90
}

variable "p95_alarms_enabled" {
  description = "true only after the code publishes the p95 summaries of gaps OBS-G1 and OBS-G2 (observability-setup alarms.md)"
  type        = bool
  default     = false
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget of this account (validation-report.md: about US$ 293 to 302 devnet, US$ 312 to 321 mainnet in us-east-1, observability included)"
  type        = number
  default     = 350
}

locals {
  network_upper = upper(var.environment)
  images = {
    for app, url in module.delivery.ecr_repository_urls : app => "${url}:${var.initial_image_tag}"
  }
}

check "founder_on_mainnet" {
  assert {
    condition     = var.environment != "mainnet" || can(regex("^[0-9a-fA-F-]{36}$", var.founder_user_id))
    error_message = "founder_user_id (UUID) is required on the mainnet (U5 BR1.3); the api and the worker refuse to start without it."
  }
}

# Key of the alarm topic. The AWS managed key aws/sns cannot be used: its key policy cannot be changed and does not
# allow CloudWatch alarms nor EventBridge to publish to an encrypted topic, so every alarm would fail to notify (found
# while wiring the detection alarms of SP-11). A customer key with the two publishers, rotated every year.
resource "aws_kms_key" "alarms" {
  count                   = var.alarm_topic_arn == null ? 1 : 0
  description             = "Encryption of the alarm topic ${var.name}-alarms (CloudWatch alarms and EventBridge publish to it)"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountAdministrationThroughIam"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${var.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AlarmAndEventPublishers"
        Effect    = "Allow"
        Principal = { Service = ["cloudwatch.amazonaws.com", "events.amazonaws.com"] }
        Action    = ["kms:Decrypt", "kms:GenerateDataKey*"]
        Resource  = "*"
      },
    ]
  })
}

resource "aws_sns_topic" "alarms" {
  count             = var.alarm_topic_arn == null ? 1 : 0
  name              = "${var.name}-alarms"
  kms_master_key_id = aws_kms_key.alarms[0].arn
}

# Who may publish: the account itself, the CloudWatch alarms and the EventBridge rules of this account (GuardDuty and
# IAM Access Analyzer findings, modules/observability/detection.tf).
resource "aws_sns_topic_policy" "alarms" {
  count = var.alarm_topic_arn == null ? 1 : 0
  arn   = aws_sns_topic.alarms[0].arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "OwnerAccount"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${var.account_id}:root" }
        Action = [
          "sns:GetTopicAttributes",
          "sns:SetTopicAttributes",
          "sns:AddPermission",
          "sns:RemovePermission",
          "sns:DeleteTopic",
          "sns:Subscribe",
          "sns:ListSubscriptionsByTopic",
          "sns:Publish",
        ]
        Resource = aws_sns_topic.alarms[0].arn
      },
      {
        Sid       = "CloudWatchAlarmsOfThisAccount"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alarms[0].arn
        Condition = {
          StringEquals = { "aws:SourceAccount" = var.account_id }
          ArnLike      = { "aws:SourceArn" = "arn:aws:cloudwatch:${var.region}:${var.account_id}:alarm:*" }
        }
      },
      {
        Sid       = "EventBridgeRulesOfThisAccount"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alarms[0].arn
        Condition = {
          StringEquals = { "aws:SourceAccount" = var.account_id }
          ArnLike      = { "aws:SourceArn" = "arn:aws:events:${var.region}:${var.account_id}:rule/*" }
        }
      },
    ]
  })
}

resource "aws_sns_topic_subscription" "alarm_email" {
  count     = var.alarm_topic_arn == null && var.alarm_email != null ? 1 : 0
  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# Cost alarm of the account (FinOps): e-mail at 80% of the forecast and at 100% of the actual monthly cost.
resource "aws_budgets_budget" "monthly" {
  count        = var.alarm_email != null ? 1 : 0
  name         = "${var.name}-${var.environment}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alarm_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }
}

module "network" {
  source = "./modules/network"
  name   = var.name
  zone   = var.zone
  zone_b = var.zone_b
}

module "kms" {
  source              = "./modules/kms"
  name                = var.name
  kms_vpc_endpoint_id = module.network.kms_endpoint_id
}

module "data" {
  source = "./modules/data"
  name   = var.name
  vpc_id = module.network.vpc_id
  zone   = var.zone
  subnet_ids = {
    app      = [module.network.private_b_subnet_id, module.network.private_data_b_subnet_id]
    txsigner = [module.network.private_a_subnet_id, module.network.private_data_b_subnet_id]
  }
  app_security_group_id      = module.network.api_worker_security_group_id
  txsigner_security_group_id = module.network.txsigner_security_group_id
  log_retention_days         = var.log_retention_days
}

module "services" {
  source                      = "./modules/services"
  name                        = var.name
  environment                 = var.environment
  txsigner_role_arn           = module.kms.txsigner_role_arn
  txsigner_role_name          = module.kms.txsigner_role_name
  images                      = local.images
  db_bootstrap_image_tag      = var.db_bootstrap_image_tag
  vpc_id                      = module.network.vpc_id
  subnet_id                   = module.network.private_a_subnet_id
  app_subnet_id               = module.network.private_b_subnet_id
  web_subnet_id               = module.network.private_web_subnet_id
  public_subnet_ids           = module.network.public_subnet_ids
  security_group_id           = module.network.txsigner_security_group_id
  app_security_group_id       = module.network.api_worker_security_group_id
  web_admin_security_group_id = module.network.web_admin_security_group_id
  alb_security_group_id       = module.network.alb_security_group_id
  db_endpoints                = module.data.endpoints
  db_names                    = module.data.db_names
  db_master_secret_arns       = module.data.master_secret_arns
  api_domain                  = var.api_domain
  web_domain                  = var.web_domain
  admin_domain                = var.admin_domain
  certificate_arn             = var.certificate_arn
  desired_count               = var.service_desired_count
  inject_jupiter_api_key      = var.inject_jupiter_api_key
  log_retention_days          = var.log_retention_days

  # Observability Setup Q1: the CloudWatch Agent sidecars publish exactly the allow-list of the alarms and dashboards.
  app_metrics_namespace   = module.observability.app_metrics_namespace
  app_metric_declarations = module.observability.app_metric_declarations

  # Flags and parameters without secret, versioned here (Q5 of the Deployment Pipeline, Q4 of this stage). Secrets
  # never appear in these maps: they are injected from Secrets Manager by the container definitions.
  txsigner_environment = {
    SPRING_PROFILES_ACTIVE         = "production"
    AWS_REGION                     = var.region
    TXSIGNER_NETWORK               = local.network_upper
    TXSIGNER_REAL_ENABLED          = "false"
    TXSIGNER_CREDENTIAL_SOURCE     = "secrets-manager"
    TXSIGNER_AWS_ACCOUNT_ID        = var.account_id
    TXSIGNER_KMS_HMAC_KEY_ID       = module.kms.anchor_hmac_key_id
    TXSIGNER_KMS_KEY_POLICY        = module.kms.wallet_key_policy
    TXSIGNER_ALLOWED_KMS_ENDPOINTS = "https://${module.network.kms_endpoint_dns}"
    TXSIGNER_DB_HOST               = module.data.endpoints["txsigner"]
    TXSIGNER_DB_NAME               = module.data.db_names["txsigner"]
    TXSIGNER_DB_USER               = "txsigner_app"
  }

  app_environment = merge({
    DB_HOST                      = module.data.endpoints["app"]
    DB_PORT                      = "5432"
    DB_NAME                      = module.data.db_names["app"]
    DB_USER                      = "memetrade_app"
    MARKET_SOURCES               = "coingecko"
    MEMETRADE_REAL_ENABLED       = "false"
    MEMETRADE_REAL_NETWORK       = local.network_upper
    MEMETRADE_REAL_EXITS_ENABLED = "false"
    MEMETRADE_TXSIGNER_URL       = "http://txsigner.internal:8443"
    MEMETRADE_GITHUB_REPOSITORY  = var.github_repository
    }, var.founder_user_id == "" ? {} : {
    MEMETRADE_REAL_FOUNDER_USER_ID = var.founder_user_id
  })

  api_environment = {
    SPRING_PROFILES_ACTIVE                       = "production"
    DB_RETENTION_USER                            = "memetrade_retention"
    AUDIT_RETENTION_MONTHS                       = "12"
    GUEST_APP_ORIGIN                             = "https://${var.web_domain}"
    ADMIN_APP_ORIGIN                             = "https://${var.admin_domain}"
    ADMIN_WALLET_ADDRESSES                       = var.admin_wallet_addresses
    DB_POOL_MAX_SIZE                             = "20"
    SCHEDULER_POOL_SIZE                          = "23"
    MEMETRADE_LEGAL_STORAGE_LOCATION             = var.legal_storage_location
    MEMETRADE_LEGAL_INTERNATIONAL_TRANSFER       = var.legal_international_transfer
    MEMETRADE_LEGAL_DPO_EMAIL                    = var.legal_dpo_email
    MEMETRADE_LEGAL_REQUIRE_PUBLISHABLE          = "true"
    MEMETRADE_LEGAL_RATE_LIMIT_INTERNAL_NETWORKS = module.network.private_web_cidr
    # TRUSTED_PROXIES stays empty: the api compares exact addresses and the load balancer nodes change address
    # (validation-report.md, open items: CIDR support before the invited users).
    TRUSTED_PROXIES = ""
    # 261121-paper-real-unico U1 (infrastructure-specification.md "Configuração nova"): tuning values of the P&L by mode
    # and of the REAL eligibility, no secret and not REAL flags, equal to the defaults of application.yml (a newer image
    # without them, or an older image with them, behaves the same). Read by @ConfigurationProperties (relaxed binding).
    MEMETRADE_PNL_BYMODE_BLOCKTIMEOUT     = "800ms"
    MEMETRADE_BOTS_REALELIGIBILITYTIMEOUT = "500ms"
    MEMETRADE_PNL_BYMODE_REALMAXORDERS    = "50000"
  }

  worker_environment = {
    DB_POOL_MAX_SIZE    = "36"
    SCHEDULER_POOL_SIZE = "28"
  }
}

module "observability" {
  source          = "./modules/observability"
  name            = var.name
  alarm_topic_arn = var.alarm_topic_arn == null ? aws_sns_topic.alarms[0].arn : var.alarm_topic_arn

  # Observability Setup: SLO of the api at the load balancer, saved queries on the log groups of the services, and the
  # two p95 alarms only after the code publishes their summaries (gaps OBS-G1 and OBS-G2).
  api_load_balancer_arn_suffix = module.services.api_load_balancer_arn_suffix
  api_target_group_arn_suffix  = module.services.api_target_group_arn_suffix
  app_log_group_names          = module.services.log_group_names
  p95_alarms_enabled           = var.p95_alarms_enabled
}

module "delivery" {
  source                      = "./modules/delivery"
  name                        = var.name
  environment                 = var.environment
  github_repository           = var.github_repository
  create_github_oidc_provider = var.create_github_oidc_provider
  cluster_name                = var.name
  # SP-01: only the execution roles of the families the deploy rolls out. The TxSigner task role and the execution
  # roles of the TxSigner and bootstrap families are founder-only (explicit Deny in the deploy policy).
  # Observability Setup: plus the task role of the api+worker family (metrics sidecar only), which every revision the
  # deploy registers carries.
  passable_role_arns = concat(
    module.services.deploy_passable_execution_role_arns,
    [module.services.api_worker_task_role_arn],
    var.deploy_passable_role_arns,
  )
  founder_only_role_arns = concat(
    [module.kms.txsigner_role_arn],
    module.services.founder_only_execution_role_arns,
  )
  txsigner_security_group_id = module.network.txsigner_security_group_id
}

output "deploy_role_arn" {
  value = module.delivery.deploy_role_arn
}

# Revisions of the founder-only families registered by this state (cd-config.md 4.6): the founder's procedure starts
# from them, never from the latest revision of the family.
output "founder_task_definition_arns" {
  value = module.services.founder_task_definition_arns
}

output "ecr_repository_urls" {
  value = module.delivery.ecr_repository_urls
}

# Values of the GitHub Environment variables (cd-config.md 4.3); none is a secret.
output "github_environment_variables" {
  value = {
    AWS_REGION              = var.region
    AWS_ACCOUNT_ID          = var.account_id
    AWS_DEPLOY_ROLE_ARN     = module.delivery.deploy_role_arn
    NAME_PREFIX             = var.name
    API_BASE_URL            = "https://${var.api_domain}"
    API_TARGET_GROUP_ARN    = module.services.api_target_group_arn
    SUBNET_PRIVATE_A        = module.network.private_a_subnet_id
    SUBNET_PRIVATE_B        = module.network.private_b_subnet_id
    TXSIGNER_SECURITY_GROUP = module.network.txsigner_security_group_id
    APP_SECURITY_GROUP      = module.network.api_worker_security_group_id
    WEB_ADMIN_ON_ECS        = "true"
    WEB_BASE_URL            = "https://${var.web_domain}"
    ADMIN_BASE_URL          = "https://${var.admin_domain}"
  }
}

output "load_balancer_dns_name" {
  description = "Point the three hosts here (CNAME or alias record at the founder's DNS)"
  value       = module.services.load_balancer_dns_name
}

output "secret_arns" {
  description = "Secrets to fill by hand before the first deploy (validation-report.md); names only"
  value       = module.services.secret_arns
}

output "db_bootstrap_repository_url" {
  value = module.services.db_bootstrap_repository_url
}

# Observability Setup: where the founder copies the agent image by digest, and the names of the dashboards.
output "cloudwatch_agent_image" {
  value = module.services.cloudwatch_agent_image
}

output "dashboard_names" {
  value = module.observability.dashboard_names
}

output "nat_public_ip" {
  value = module.network.nat_public_ip
}

output "private_web_subnet_id" {
  value = module.network.private_web_subnet_id
}

output "web_admin_security_group_id" {
  value = module.network.web_admin_security_group_id
}
