# Execution roles (one per task family) and the extra permission of the TxSigner task role. An execution role is used
# by ECS only, to start the task: pull the image, write the log stream and read the secrets it injects. It is never
# visible to the code in the container. Least privilege: each role names its repositories, its log group and its
# secrets; none has kms, ssm, ecs:ExecuteCommand nor any write outside its log group.
#
# Security review (security-posture-review.md SP-01): the deploy role of GitHub may pass ONLY the execution roles of the
# families it deploys (local.deploy_execution_roles: api-worker, web, admin, db-migrate). Every other role is
# founder-only: the TxSigner task role and the execution roles of the TxSigner families (txsigner, txsigner-migrate,
# txsigner-probe) and of the two bootstraps (RDS master secret). Those families are registered and run only by the
# founder with MFA (cd-config.md 4.6). A role added to execution_roles is founder-only unless it is listed below.
#
# The web and the admin have NO task role. The api+worker task role (metrics-agent.tf) serves only the CloudWatch Agent
# sidecar (EMF to its own metrics log group); no application code calls AWS. The TxSigner task role is the KMS role of
# modules/kms; here it gains the read of its service token secret and, in metrics-agent.tf, the EMF of its sidecar.

locals {
  ecr_repository_arn = "arn:aws:ecr:${local.region}:${local.account_id}:repository/${var.name}"

  execution_roles = {
    # cloudwatch-agent: image of the metrics sidecar (metrics-agent.tf, Observability Setup Q1).
    txsigner = {
      repositories = ["txsigner", "cloudwatch-agent"]
      log_group    = "txsigner"
      secrets      = [aws_secretsmanager_secret.this["txsigner-db-app-password"].arn]
    }
    txsigner-migrate = {
      repositories = ["txsigner"]
      log_group    = "migrate"
      secrets      = [aws_secretsmanager_secret.this["txsigner-db-owner-password"].arn]
    }
    # Readiness probe of the TxSigner (one-off.tf): pull of the txsigner image and its log group, no secret at all.
    txsigner-probe = {
      repositories = ["txsigner"]
      log_group    = "txsigner"
      secrets      = []
    }
    api-worker = {
      repositories = ["api", "cloudwatch-agent"]
      log_group    = "api-worker"
      secrets = concat([
        aws_secretsmanager_secret.this["app-db-app-password"].arn,
        aws_secretsmanager_secret.this["app-db-retention-password"].arn,
        aws_secretsmanager_secret.this["access-token-signing-key"].arn,
        aws_secretsmanager_secret.this["audit-wallet-hmac-key"].arn,
        aws_secretsmanager_secret.this["txsigner-service-token"].arn,
        aws_secretsmanager_secret.this["rpc-primary-url"].arn,
        aws_secretsmanager_secret.this["rpc-secondary-url"].arn,
        # The CoinGecko key and the GitHub token come from their own policies (coingecko.tf, github-actions.tf),
        # attached below to this role only.
      ], var.inject_jupiter_api_key ? [aws_secretsmanager_secret.this["jupiter-api-key"].arn] : [])
    }
    db-migrate = {
      repositories = ["api"]
      log_group    = "migrate"
      secrets      = [aws_secretsmanager_secret.this["app-db-owner-password"].arn]
    }
    web = {
      repositories = ["web"]
      log_group    = "web"
      secrets      = []
    }
    admin = {
      repositories = ["admin"]
      log_group    = "admin"
      secrets      = []
    }
    db-bootstrap = {
      repositories = ["db-bootstrap"]
      log_group    = "bootstrap"
      secrets = [
        var.db_master_secret_arns["app"],
        aws_secretsmanager_secret.this["app-db-owner-password"].arn,
        aws_secretsmanager_secret.this["app-db-app-password"].arn,
        aws_secretsmanager_secret.this["app-db-retention-password"].arn,
      ]
    }
    txsigner-db-bootstrap = {
      repositories = ["db-bootstrap"]
      log_group    = "bootstrap"
      secrets = [
        var.db_master_secret_arns["txsigner"],
        aws_secretsmanager_secret.this["txsigner-db-owner-password"].arn,
        aws_secretsmanager_secret.this["txsigner-db-app-password"].arn,
      ]
    }
  }

  # The only execution roles the deploy role may pass to ECS (SP-01). Everything else is founder-only.
  deploy_execution_roles  = ["api-worker", "web", "admin", "db-migrate"]
  founder_execution_roles = sort(setsubtract(keys(local.execution_roles), local.deploy_execution_roles))
}

data "aws_iam_policy_document" "execution_trust" {
  statement {
    sid     = "EcsTasksOfThisAccountOnly"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  for_each           = local.execution_roles
  name               = "${var.name}-${each.key}-exec"
  description        = "Execution role of ${var.name}-${each.key}: pull, own log group, own secrets only"
  assume_role_policy = data.aws_iam_policy_document.execution_trust.json
}

data "aws_iam_policy_document" "execution" {
  for_each = local.execution_roles

  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "PullOnlyItsImages"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [for repository in each.value.repositories : "${local.ecr_repository_arn}/${repository}"]
  }

  statement {
    sid       = "WriteOnlyItsLogGroup"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.ecs[each.value.log_group].arn}:*"]
  }

  dynamic "statement" {
    for_each = length(each.value.secrets) > 0 ? [1] : []
    content {
      sid       = "ReadOnlyItsSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = each.value.secrets
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  for_each = local.execution_roles
  name     = "${var.name}-${each.key}-exec"
  role     = aws_iam_role.execution[each.key].id
  policy   = data.aws_iam_policy_document.execution[each.key].json
}

# U2 and U5: the two worker secrets keep their own policies, now attached to the execution role of the api+worker
# task (the api container never receives them: only the worker container definition injects them, api-worker.tf).
resource "aws_iam_role_policy_attachment" "worker_secrets" {
  for_each = {
    coingecko = aws_iam_policy.worker_reads_coingecko_key.arn
    github    = aws_iam_policy.worker_reads_github_actions_token.arn
  }
  role       = aws_iam_role.execution["api-worker"].name
  policy_arn = each.value
}

# NFR1.5: the TxSigner reads its service token (versions AWSCURRENT and AWSPREVIOUS) every 5 minutes with its TASK role.
data "aws_iam_policy_document" "txsigner_reads_service_token" {
  statement {
    sid       = "ReadTheServiceTokenOnly"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.this["txsigner-service-token"].arn]
  }
}

resource "aws_iam_role_policy" "txsigner_reads_service_token" {
  name   = "${var.name}-txsigner-service-token"
  role   = var.txsigner_role_name
  policy = data.aws_iam_policy_document.txsigner_reads_service_token.json
}
