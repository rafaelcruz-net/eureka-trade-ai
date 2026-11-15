# Databases (infrastructure-specification.md "RDS"): one for the application, one for the TxSigner; encrypted, deletion
# protection on, point-in-time recovery of 7 days (RPO 5 min), no public access. Passwords are managed by RDS in the
# secrets manager (never in the Terraform state).

variable "name" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "app_security_group_id" {
  type = string
}

variable "txsigner_security_group_id" {
  type = string
}

resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "db" {
  for_each = { app = var.app_security_group_id, txsigner = var.txsigner_security_group_id }
  name     = "${var.name}-db-${each.key}"
  vpc_id   = data.aws_db_subnet_group.lookup.vpc_id
}

data "aws_db_subnet_group" "lookup" {
  name = aws_db_subnet_group.main.name
}

resource "aws_vpc_security_group_ingress_rule" "db" {
  for_each                     = { app = var.app_security_group_id, txsigner = var.txsigner_security_group_id }
  security_group_id            = aws_security_group.db[each.key].id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_instance" "main" {
  for_each                    = toset(["app", "txsigner"])
  identifier                  = "${var.name}-${each.key}"
  engine                      = "postgres"
  engine_version              = "17"
  instance_class              = "db.t4g.micro"
  allocated_storage           = 20
  storage_encrypted           = true
  deletion_protection         = true
  backup_retention_period     = 7
  publicly_accessible         = false
  multi_az                    = false
  db_subnet_group_name        = aws_db_subnet_group.main.name
  vpc_security_group_ids      = [aws_security_group.db[each.key].id]
  username                    = "${each.key}_master"
  manage_master_user_password = true
  skip_final_snapshot         = false
  final_snapshot_identifier   = "${var.name}-${each.key}-final"
  copy_tags_to_snapshot       = true
}

output "endpoints" {
  value = { for k, db in aws_db_instance.main : k => db.address }
}
