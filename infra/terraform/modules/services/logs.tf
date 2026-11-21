# Log groups of the tasks (cd-config.md: /ecs/${name}/...), created by Terraform with a retention so no task ever writes
# to a group without one. The deploy role reads /ecs/${name}/* (one-off tasks); each execution role writes only its own
# group (iam.tf). The applications mask addresses and signatures and never log a secret (project.md).

locals {
  log_groups = {
    txsigner   = "/ecs/${var.name}/txsigner"
    api-worker = "/ecs/${var.name}/api-worker"
    web        = "/ecs/${var.name}/web"
    admin      = "/ecs/${var.name}/admin"
    migrate    = "/ecs/${var.name}/migrate"
    bootstrap  = "/ecs/${var.name}/bootstrap"
  }

  log_configuration = {
    for key, group in local.log_groups : key => {
      logDriver = "awslogs"
      options = {
        awslogs-group         = group
        awslogs-region        = local.region
        awslogs-stream-prefix = key
      }
    }
  }
}

resource "aws_cloudwatch_log_group" "ecs" {
  # checkov:skip=CKV_AWS_158:CloudWatch Logs encrypts at rest with its own key; a customer key adds cost and no control the pilot uses.
  # checkov:skip=CKV_AWS_338:Retention of var.log_retention_days (90) on purpose: LGPD minimisation; the audit trail lives in the database.
  for_each          = local.log_groups
  name              = each.value
  retention_in_days = var.log_retention_days
}
