# Network of the pilot (infrastructure-specification.md "Rede na nuvem"): one zone; private subnet A without a route to
# the internet (TxSigner and its RDS, only VPC endpoints); private subnet B through a NAT instance (api and worker; the
# worker reaches the RPC); a public subnet for the load balancer and the NAT instance.

variable "name" {
  type = string
}

variable "cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "zone" {
  type = string
}

resource "aws_vpc" "main" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr, 8, 0)
  availability_zone = var.zone
  tags              = { Name = "${var.name}-public" }
}

resource "aws_subnet" "private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr, 8, 1)
  availability_zone = var.zone
  tags              = { Name = "${var.name}-private-a-txsigner" }
}

resource "aws_subnet" "private_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr, 8, 2)
  availability_zone = var.zone
  tags              = { Name = "${var.name}-private-b-app" }
}

resource "aws_route_table" "private_a" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-private-a-no-internet" }
}

resource "aws_route_table_association" "private_a" {
  subnet_id      = aws_subnet.private_a.id
  route_table_id = aws_route_table.private_a.id
}

resource "aws_security_group" "api_worker" {
  name   = "${var.name}-api-worker"
  vpc_id = aws_vpc.main.id
}

resource "aws_security_group" "txsigner" {
  name   = "${var.name}-txsigner"
  vpc_id = aws_vpc.main.id
}

resource "aws_security_group" "endpoints" {
  name   = "${var.name}-vpc-endpoints"
  vpc_id = aws_vpc.main.id
}

# TxSigner: 8443 only from the api and the worker.
resource "aws_vpc_security_group_ingress_rule" "txsigner_from_app" {
  security_group_id            = aws_security_group.txsigner.id
  referenced_security_group_id = aws_security_group.api_worker.id
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
}

# Endpoints: 443 only from the TxSigner and the app (review correction: the endpoint security group).
resource "aws_vpc_security_group_ingress_rule" "endpoints_from_txsigner" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.txsigner.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_app" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.api_worker.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

resource "aws_vpc_security_group_egress_rule" "txsigner_to_endpoints" {
  security_group_id            = aws_security_group.txsigner.id
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}

data "aws_region" "current" {}

# Interface endpoints in subnet A; "monitoring" added by the review of the Infrastructure Design (CloudWatch metrics).
resource "aws_vpc_endpoint" "interface" {
  for_each            = toset(["kms", "secretsmanager", "sts", "logs", "ecr.api", "ecr.dkr", "monitoring"])
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${data.aws_region.current.name}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [aws_subnet.private_a.id]
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private_a.id]
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "private_a_subnet_id" {
  value = aws_subnet.private_a.id
}

output "private_b_subnet_id" {
  value = aws_subnet.private_b.id
}

output "txsigner_security_group_id" {
  value = aws_security_group.txsigner.id
}

output "api_worker_security_group_id" {
  value = aws_security_group.api_worker.id
}

output "kms_endpoint_dns" {
  value = aws_vpc_endpoint.interface["kms"].dns_entry[0].dns_name
}
