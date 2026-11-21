# U1:NFR4.8 (reliability-requirements.md): besides the point-in-time recovery of 7 days of the instances (RPO 5 min),
# one daily copy of each database kept for 30 days. AWS Backup, a vault of this account, a daily rule and a selection
# of the two instances. Added by the Environment Provisioning of 261113-trading-real-bot (it was missing from the IaC).

variable "daily_backup_retention_days" {
  type    = number
  default = 30
}

resource "aws_backup_vault" "rds" {
  # checkov:skip=CKV_AWS_166:The AWS managed key of AWS Backup; a customer key adds cost and no control the pilot uses.
  name = "${var.name}-rds"
}

resource "aws_backup_plan" "rds_daily" {
  name = "${var.name}-rds-daily"
  rule {
    rule_name         = "daily-30-days"
    target_vault_name = aws_backup_vault.rds.name
    schedule          = "cron(0 5 * * ? *)"
    start_window      = 60
    completion_window = 240
    lifecycle {
      delete_after = var.daily_backup_retention_days
    }
  }
}

data "aws_iam_policy_document" "backup_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["backup.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backup" {
  name               = "${var.name}-rds-backup"
  assume_role_policy = data.aws_iam_policy_document.backup_trust.json
}

resource "aws_iam_role_policy_attachment" "backup" {
  for_each = toset([
    "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup",
    "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForRestores",
  ])
  role       = aws_iam_role.backup.name
  policy_arn = each.value
}

resource "aws_backup_selection" "rds" {
  name         = "${var.name}-rds"
  plan_id      = aws_backup_plan.rds_daily.id
  iam_role_arn = aws_iam_role.backup.arn
  resources    = [for db in aws_db_instance.main : db.arn]
}
