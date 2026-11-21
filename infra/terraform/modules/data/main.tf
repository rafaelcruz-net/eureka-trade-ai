# Databases (infrastructure-specification.md "RDS"): one for the application, one for the TxSigner; encrypted, deletion
# protection on, point-in-time recovery of 7 days (RPO 5 min), no public access. The master passwords are managed by
# RDS in the secrets manager (never in the Terraform state) and are read only by the one-off db-bootstrap tasks, which
# create the logins of the applications (infra/postgres/bootstrap).
#
# 261113-trading-real-bot Environment Provisioning: one subnet group per database (its workload subnet plus the
# second-zone subnet AWS requires), each instance pinned to the workload zone, the database name the applications
# use, a parameter group with the settings of the local PostgreSQL (track_commit_timestamp, which the audit outbox
# reads, and no statement nor parameter in the logs) and the egress rules of the task groups to their own database.

variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "zone" {
  description = "Zone of the workloads; both instances are placed there"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets of each database subnet group (app and txsigner): the workload subnet and the second-zone one"
  type        = map(list(string))
}

variable "app_security_group_id" {
  type = string
}

variable "txsigner_security_group_id" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 90
}

locals {
  clients = { app = var.app_security_group_id, txsigner = var.txsigner_security_group_id }
  # Database names the applications connect to (DB_NAME and TXSIGNER_DB_NAME).
  db_names = { app = "memetrade", txsigner = "txsigner" }
}

resource "aws_db_subnet_group" "db" {
  for_each   = var.subnet_ids
  name       = "${var.name}-${each.key}"
  subnet_ids = each.value
}

resource "aws_security_group" "db" {
  # checkov:skip=CKV2_AWS_5:Attached to aws_db_instance.main through vpc_security_group_ids (for_each keys); a Checkov false positive.
  for_each    = local.clients
  name        = "${var.name}-db-${each.key}"
  description = "RDS of the ${each.key}: 5432 only from its own task group"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "db" {
  for_each                     = local.clients
  security_group_id            = aws_security_group.db[each.key].id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from the ${each.key} tasks only"
}

# The only database egress of each task group: its own RDS (the api and the worker never reach the TxSigner database).
resource "aws_vpc_security_group_egress_rule" "to_db" {
  for_each                     = local.clients
  security_group_id            = each.value
  referenced_security_group_id = aws_security_group.db[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL of the ${each.key} database"
}

# Same settings as the local PostgreSQL of docker-compose.yml. track_commit_timestamp is static: it applies at the
# creation of the instance (and after a reboot if changed later).
resource "aws_db_parameter_group" "postgres" {
  name        = "${var.name}-postgres17"
  family      = "postgres17"
  description = "track_commit_timestamp, TLS only, no statement nor parameter in the logs"

  parameter {
    name         = "track_commit_timestamp"
    value        = "1"
    apply_method = "pending-reboot"
  }
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_statement"
    value = "none"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "-1"
  }
  parameter {
    name  = "log_min_error_statement"
    value = "panic"
  }
  parameter {
    name  = "log_error_verbosity"
    value = "terse"
  }
  parameter {
    name  = "log_parameter_max_length"
    value = "0"
  }
  parameter {
    name  = "log_parameter_max_length_on_error"
    value = "0"
  }
}

# Created before the instances, so the exported PostgreSQL log never lands in a group without retention.
resource "aws_cloudwatch_log_group" "postgresql" {
  # checkov:skip=CKV_AWS_158:CloudWatch Logs encrypts at rest with its own key; a customer key adds cost and no control the pilot uses.
  # checkov:skip=CKV_AWS_338:90 days on purpose (LGPD minimisation); the parameter group keeps statements and parameters out.
  for_each          = toset(["app", "txsigner"])
  name              = "/aws/rds/instance/${var.name}-${each.key}/postgresql"
  retention_in_days = var.log_retention_days
}

resource "aws_db_instance" "main" {
  # checkov:skip=CKV_AWS_157:Single zone in the pilot (infrastructure-specification.md, cost); PITR of 7 days covers the RPO of 5 min.
  # checkov:skip=CKV_AWS_118:Enhanced monitoring left out of the pilot; the basic RDS metrics are in CloudWatch.
  # checkov:skip=CKV_AWS_161:The applications log in with their own password logins (db-bootstrap); IAM authentication needs code changes.
  # checkov:skip=CKV_AWS_353:Performance Insights left out of the pilot (db.t4g.micro, cost).
  # checkov:skip=CKV2_AWS_30:Statement logging is OFF on purpose: no wallet address, amount nor secret may reach a log.
  for_each                        = toset(["app", "txsigner"])
  identifier                      = "${var.name}-${each.key}"
  engine                          = "postgres"
  engine_version                  = "17"
  instance_class                  = "db.t4g.micro"
  allocated_storage               = 20
  storage_type                    = "gp3"
  storage_encrypted               = true
  deletion_protection             = true
  backup_retention_period         = 7
  publicly_accessible             = false
  multi_az                        = false
  availability_zone               = var.zone
  db_name                         = local.db_names[each.key]
  db_subnet_group_name            = aws_db_subnet_group.db[each.key].name
  parameter_group_name            = aws_db_parameter_group.postgres.name
  vpc_security_group_ids          = [aws_security_group.db[each.key].id]
  username                        = "${each.key}_master"
  manage_master_user_password     = true
  auto_minor_version_upgrade      = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name}-${each.key}-final"
  copy_tags_to_snapshot           = true
  depends_on                      = [aws_cloudwatch_log_group.postgresql]
}

output "endpoints" {
  value = { for k, db in aws_db_instance.main : k => db.address }
}

output "db_names" {
  value = local.db_names
}

output "master_secret_arns" {
  description = "RDS-managed master secrets (JSON username/password), read only by the db-bootstrap tasks"
  value       = { for k, db in aws_db_instance.main : k => db.master_user_secret[0].secret_arn }
}
