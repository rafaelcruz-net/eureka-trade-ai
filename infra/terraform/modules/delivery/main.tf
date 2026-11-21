# Continuous delivery of the REAL pilot (261113-trading-real-bot, operation/deployment-pipeline/cd-config.md, Q3 and
# Q6): the ECR repositories of the four images (tag = commit SHA, immutable, scanned on push), the GitHub OIDC
# provider and ONE deploy role per environment, assumable only by a job of this repository that runs in the GitHub
# Environment of the same name (devnet or mainnet; the mainnet Environment requires the founder's approval). Applied by
# the founder from his machine (administration role with MFA), like every other module; the CI never applies Terraform.
#
# Security review of the Environment Provisioning (security-posture-review.md SP-01, Critical): the deploy role must not
# be able to sign nor read a secret, not even indirectly through a role it passes to ECS. So it:
# - passes to ECS ONLY the execution roles of the families it deploys (api-worker, web, admin, db-migrate); the TxSigner
#   task role and the execution roles of the TxSigner and bootstrap families are founder-only (explicit Deny below);
# - pushes the api, web and admin images, never the txsigner image (the founder builds and pushes it with MFA);
# - updates only the api-worker, web and admin services and runs only the db-migrate family;
# - is explicitly denied RunTask/StartTask of the TxSigner and bootstrap families, any change of the txsigner service,
#   and putting a revision of those families in service through another service (condition ecs:task-definition);
# - reads only the log group of the migrations.
# The TxSigner (migration, revision, readiness) is deployed by the founder with MFA (cd-config.md 4.6), before
# deploy.yml. What IAM cannot express: RegisterTaskDefinition has no family condition, so the role can still register a
# revision of any family, but only with the roles it may pass; the founder's procedure never starts from the "latest"
# revision, and the CloudTrail alarm of modules/observability/detection.tf notifies every such registration (SP-11).

variable "name" {
  description = "Prefix of the resource names (also the ECS cluster name, see modules/services)"
  type        = string
}

variable "environment" {
  description = "Environment of this state: devnet or mainnet (the GitHub Environment the deploy role trusts)"
  type        = string
  validation {
    condition     = contains(["devnet", "mainnet"], var.environment)
    error_message = "environment must be devnet or mainnet."
  }
}

variable "github_repository" {
  description = "owner/repository whose deploy workflow may assume the role"
  type        = string
}

variable "create_github_oidc_provider" {
  description = "false when the GitHub OIDC provider already exists in this account (one per account)"
  type        = bool
}

variable "cluster_name" {
  type = string
}

variable "passable_role_arns" {
  description = "Execution roles the deploy may pass to ECS: those of api-worker, web, admin and db-migrate only (SP-01)"
  type        = list(string)
}

variable "founder_only_role_arns" {
  description = "TxSigner task role and execution roles of the TxSigner and bootstrap families: never passed by the deploy"
  type        = list(string)
}

variable "txsigner_security_group_id" {
  description = "Security group of the TxSigner, reused by the one-off readiness probe (run by the founder)"
  type        = string
}

variable "image_retention_count" {
  description = "Images kept per repository (the previous SHA must always be kept for the rollback)"
  type        = number
  default     = 30
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  region       = data.aws_region.current.name
  oidc_host    = "token.actions.githubusercontent.com"
  repositories = toset(["api", "txsigner", "web", "admin"])
  # The txsigner image is built and pushed by the founder (cd-config.md 4.6); the deploy only reads its digest.
  push_repositories = ["api", "web", "admin"]
  cluster_arn       = "arn:aws:ecs:${local.region}:${local.account_id}:cluster/${var.cluster_name}"
  task_definition   = "arn:aws:ecs:${local.region}:${local.account_id}:task-definition"
  service           = "arn:aws:ecs:${local.region}:${local.account_id}:service/${var.cluster_name}"
  oidc_provider_arn = (var.create_github_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
  : "arn:aws:iam::${local.account_id}:oidc-provider/${local.oidc_host}")

  # Services the deploy rolls out (cd-config.md 4.4). Never the txsigner service.
  deploy_services = ["api-worker", "web", "admin"]
  # The only one-off family the deploy runs. The TxSigner migration and the probe are founder-only.
  one_off_families = ["${var.name}-db-migrate"]
  # Families the deploy never runs nor puts in service: every TxSigner family (txsigner, txsigner-migrate,
  # txsigner-probe, txsigner-db-bootstrap) and the application bootstrap, in the two forms ECS accepts.
  founder_only_family_patterns = [
    "${local.task_definition}/${var.name}-txsigner*",
    "${local.task_definition}/${var.name}-db-bootstrap:*",
  ]
  # Long-running families: they change only through UpdateService, never as one-off tasks.
  long_running_family_patterns = [for family in ["api-worker", "web", "admin"] : "${local.task_definition}/${var.name}-${family}:*"]

  ecr_write_actions = [
    "ecr:BatchDeleteImage",
    "ecr:CompleteLayerUpload",
    "ecr:InitiateLayerUpload",
    "ecr:PutImage",
    "ecr:UploadLayerPart",
  ]

  deploy_policy = {
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPushAndReadTheApiWebAdminRepositoriesOnly"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:GetDownloadUrlForLayer",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
        ]
        Resource = [for key in local.push_repositories : aws_ecr_repository.app[key].arn]
      },
      {
        Sid      = "EcrReadTheTxSignerDigestOnly"
        Effect   = "Allow"
        Action   = ["ecr:DescribeImages"]
        Resource = [aws_ecr_repository.app["txsigner"].arn]
      },
      {
        # IAM has no family condition on RegisterTaskDefinition (see the header): the effect of a revision is bounded
        # by the roles the deploy may pass (PassOnlyTheDeployRolesToEcs) and by the Deny statements below.
        Sid       = "EcsTaskDefinitionRevisions"
        Effect    = "Allow"
        Action    = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
        Resource  = "*"
        Condition = { StringEquals = { "aws:RequestedRegion" = local.region } }
      },
      {
        Sid      = "EcsDescribeServicesOfThisCluster"
        Effect   = "Allow"
        Action   = ["ecs:DescribeServices"]
        Resource = "${local.service}/*"
      },
      {
        Sid      = "EcsUpdateTheApiWorkerWebAdminServicesOnly"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService"]
        Resource = [for service in local.deploy_services : "${local.service}/${service}"]
      },
      {
        Sid       = "EcsRunTheDbMigrationOnly"
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = [for family in local.one_off_families : "${local.task_definition}/${family}:*"]
        Condition = { ArnEquals = { "ecs:cluster" = local.cluster_arn } }
      },
      {
        Sid      = "EcsReadAndStopTasksOfThisCluster"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks", "ecs:StopTask"]
        Resource = "arn:aws:ecs:${local.region}:${local.account_id}:task/${var.cluster_name}/*"
      },
      {
        Sid       = "EcsListTasksOfThisCluster"
        Effect    = "Allow"
        Action    = ["ecs:ListTasks"]
        Resource  = "*"
        Condition = { ArnEquals = { "ecs:cluster" = local.cluster_arn } }
      },
      ], length(var.passable_role_arns) > 0 ? [{
        Sid       = "PassOnlyTheDeployRolesToEcs"
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = var.passable_role_arns
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }] : [], [
      {
        Sid      = "LoadBalancerHealthRead"
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:DescribeTargetGroups", "elasticloadbalancing:DescribeTargetHealth"]
        Resource = "*"
      },
      {
        # Abort criteria of the deploy (deployment-strategy.md): the 5xx rate of the load balancer and the alarms of
        # the REAL during the observation window. Read-only; GetMetricData has no resource-level permission.
        Sid      = "ReadMetricsForTheAbortCriteria"
        Effect   = "Allow"
        Action   = ["cloudwatch:GetMetricData"]
        Resource = "*"
      },
      {
        Sid      = "ReadTheAlarmsOfThisEnvironment"
        Effect   = "Allow"
        Action   = ["cloudwatch:DescribeAlarms"]
        Resource = "arn:aws:cloudwatch:${local.region}:${local.account_id}:alarm:${var.name}-*"
      },
      {
        # Only the migrations (db-migrate); never the TxSigner nor the bootstrap log groups.
        Sid      = "ReadTheLogsOfTheMigrationsOnly"
        Effect   = "Allow"
        Action   = ["logs:FilterLogEvents", "logs:GetLogEvents"]
        Resource = "arn:aws:logs:${local.region}:${local.account_id}:log-group:/ecs/${var.name}/migrate:*"
      },
      # --- Explicit denies: they hold even if a broader policy is attached by mistake ---------------------------
      {
        Sid      = "NeverKmsSecretsOrShell"
        Effect   = "Deny"
        Action   = ["ecs:ExecuteCommand", "kms:*", "secretsmanager:*", "ssm:StartSession"]
        Resource = "*"
      },
      {
        Sid      = "NeverRunTheFounderOnlyOrLongRunningFamiliesAsOneOff"
        Effect   = "Deny"
        Action   = ["ecs:RunTask", "ecs:StartTask"]
        Resource = concat(local.founder_only_family_patterns, local.long_running_family_patterns)
      },
      {
        Sid      = "NeverChangeTheTxSignerService"
        Effect   = "Deny"
        Action   = ["ecs:CreateService", "ecs:DeleteService", "ecs:UpdateService"]
        Resource = "${local.service}/txsigner"
      },
      {
        # A revision of a founder-only family is never put in service nor run through another service or family name.
        Sid       = "NeverUseAFounderOnlyRevision"
        Effect    = "Deny"
        Action    = ["ecs:CreateService", "ecs:RunTask", "ecs:StartTask", "ecs:UpdateService"]
        Resource  = "*"
        Condition = { ArnLike = { "ecs:task-definition" = local.founder_only_family_patterns } }
      },
      {
        Sid      = "NeverWriteTheTxSignerImages"
        Effect   = "Deny"
        Action   = local.ecr_write_actions
        Resource = [aws_ecr_repository.app["txsigner"].arn]
      },
      ], length(var.founder_only_role_arns) > 0 ? [{
        Sid      = "NeverPassTheFounderOnlyRoles"
        Effect   = "Deny"
        Action   = ["iam:PassRole"]
        Resource = var.founder_only_role_arns
    }] : [])
  }
}

resource "aws_ecr_repository" "app" {
  # checkov:skip=CKV_AWS_136:AES256 on purpose - the images hold no secret, and the deploy role denies kms:* (the deny
  # would also apply to the KMS calls ECR makes on its behalf), so a KMS key here would break every push.
  for_each             = local.repositories
  name                 = "${var.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  for_each   = aws_ecr_repository.app
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last ${var.image_retention_count} images (the rollback target is always among them)"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.image_retention_count
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_github_oidc_provider ? 1 : 0
  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "deploy_trust" {
  statement {
    sid     = "OnlyTheDeployWorkflowInThisGitHubEnvironment"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.environment}"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name}-deploy-${var.environment}"
  description          = "Deploy of ${var.environment} by the GitHub workflow deploy.yml (OIDC, no long-lived key)"
  assume_role_policy   = data.aws_iam_policy_document.deploy_trust.json
  max_session_duration = 3600
}

# Written with jsonencode (not a policy document data source) so the offline tests read the exact statements.
resource "aws_iam_role_policy" "deploy" {
  name   = "${var.name}-deploy-${var.environment}"
  role   = aws_iam_role.deploy.id
  policy = jsonencode(local.deploy_policy)

  lifecycle {
    precondition {
      condition     = length(setintersection(toset(var.passable_role_arns), toset(var.founder_only_role_arns))) == 0
      error_message = "A founder-only role (TxSigner task role, TxSigner or bootstrap execution role) is in passable_role_arns (SP-01)."
    }
  }
}

# Readiness probe of the TxSigner (deployment-strategy.md "Ordem e prontidão"): the one-off family
# ${name}-txsigner-probe of modules/services, run by the FOUNDER with MFA (cd-config.md 4.6), with the TxSigner security
# group and no task role (so no KMS); it reads http://<task IP>:8444/actuator/health/readiness (database and ListKeys).
# The management port answers health, prometheus and info only (show-details never) and is reachable only from tasks
# that carry this same security group: the TxSigner itself and the probe.
resource "aws_vpc_security_group_ingress_rule" "txsigner_management_from_probe" {
  security_group_id            = var.txsigner_security_group_id
  referenced_security_group_id = var.txsigner_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 8444
  to_port                      = 8444
  description                  = "Readiness probe of the TxSigner (same security group only)"
}

resource "aws_vpc_security_group_egress_rule" "probe_to_txsigner_management" {
  security_group_id            = var.txsigner_security_group_id
  referenced_security_group_id = var.txsigner_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 8444
  to_port                      = 8444
  description                  = "Readiness probe of the TxSigner (same security group only)"
}

output "deploy_role_arn" {
  description = "Role of the GitHub Environment variable AWS_DEPLOY_ROLE_ARN"
  value       = aws_iam_role.deploy.arn
}

output "deploy_role_policy" {
  description = "Inline policy of the deploy role (JSON), read by the offline tests"
  value       = aws_iam_role_policy.deploy.policy
}

output "ecr_repository_urls" {
  value = { for key, repository in aws_ecr_repository.app : key => repository.repository_url }
}

output "github_oidc_provider_arn" {
  value = local.oidc_provider_arn
}
