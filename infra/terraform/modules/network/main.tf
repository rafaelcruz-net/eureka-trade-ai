# Network of the pilot (infrastructure-specification.md "Rede na nuvem"; 261113-trading-real-bot Environment
# Provisioning, environment-provisioning-questions.md Q3 and Q5). One VPC per account, no peering nor Transit Gateway.
# The workloads run in ONE zone (var.zone); a second zone (var.zone_b) only carries what AWS requires in two zones: the
# second leg of the load balancer and the second subnet of the database subnet groups (no workload, no internet).
#
#   public    (zone a) load balancer and the NAT gateway             route to the internet gateway
#   public_b  (zone b) load balancer only                             route to the internet gateway
#   private_a (zone a) TxSigner and its RDS                           NO route to the internet: VPC endpoints only
#   private_b (zone a) task api+worker and the application RDS        NAT gateway (RPC, Jupiter, CoinGecko, GitHub)
#   private_web (zone a) web and admin                                NO route to the internet: endpoints and the api
#   private_data_b (zone b) database subnet groups only               NO route to the internet
#
# Every security group has explicit egress rules (Terraform removes the default allow-all egress).

variable "name" {
  type = string
}

variable "cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "zone" {
  description = "Zone of every workload (ECS tasks and RDS instances)"
  type        = string
}

variable "zone_b" {
  description = "Second zone: second leg of the load balancer and of the database subnet groups only"
  type        = string
}

variable "flow_log_retention_days" {
  type    = number
  default = 90
}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  # IAM prefix of the actions each interface endpoint serves (the endpoint policy allows only those).
  endpoint_actions = {
    kms            = "kms"
    secretsmanager = "secretsmanager"
    sts            = "sts"
    logs           = "logs"
    "ecr.api"      = "ecr"
    "ecr.dkr"      = "ecr"
    monitoring     = "cloudwatch"
  }
}

resource "aws_vpc" "main" {
  cidr_block           = var.cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

# CKV2_AWS_12: the default security group of the VPC allows nothing; every resource uses its own group.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id
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

resource "aws_subnet" "private_web" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr, 8, 3)
  availability_zone = var.zone
  tags              = { Name = "${var.name}-private-web" }
}

resource "aws_subnet" "public_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr, 8, 4)
  availability_zone = var.zone_b
  tags              = { Name = "${var.name}-public-b-lb-only" }
}

resource "aws_subnet" "private_data_b" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = cidrsubnet(var.cidr, 8, 5)
  availability_zone = var.zone_b
  tags              = { Name = "${var.name}-private-data-b-subnet-group-only" }
}

# --- Routes -------------------------------------------------------------------------------------------------------

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = var.name }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  for_each       = { a = aws_subnet.public.id, b = aws_subnet.public_b.id }
  subnet_id      = each.value
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private_a" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-private-a-no-internet" }
}

resource "aws_route_table_association" "private_a" {
  subnet_id      = aws_subnet.private_a.id
  route_table_id = aws_route_table.private_a.id
}

# The second-zone database subnet shares the route table without internet of the TxSigner subnet.
resource "aws_route_table_association" "private_data_b" {
  subnet_id      = aws_subnet.private_data_b.id
  route_table_id = aws_route_table.private_a.id
}

resource "aws_route_table" "private_web" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-private-web-no-internet" }
}

resource "aws_route_table_association" "private_web" {
  subnet_id      = aws_subnet.private_web.id
  route_table_id = aws_route_table.private_web.id
}

# Q3 / Environment Provisioning decision: ONE managed NAT gateway, only for subnet B (the worker reaches the two RPC
# providers, Jupiter, CoinGecko and api.github.com, which have no VPC endpoint). Chosen over the NAT instance of the U1
# design: no instance to patch nor to keep alive, at about US$ 26 per month more (validation-report.md, cost). A single
# zone is a single point of failure for the freshness of the market data, accepted in the pilot (U2).
resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "${var.name}-nat" }
}

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public.id
  tags          = { Name = var.name }
  depends_on    = [aws_internet_gateway.main]
}

resource "aws_route_table" "private_b" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.name}-private-b-nat" }
}

resource "aws_route" "private_b_nat" {
  route_table_id         = aws_route_table.private_b.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main.id
}

resource "aws_route_table_association" "private_b" {
  subnet_id      = aws_subnet.private_b.id
  route_table_id = aws_route_table.private_b.id
}

# --- Flow logs (CKV2_AWS_11): every accepted and rejected flow, for the forensics of the REAL ----------------------

resource "aws_cloudwatch_log_group" "flow" {
  # checkov:skip=CKV_AWS_158:CloudWatch Logs encrypts at rest with its own key; a customer key adds cost and no control the pilot uses.
  # checkov:skip=CKV_AWS_338:90 days on purpose (LGPD minimisation; the flows carry client IP addresses).
  name              = "/vpc/${var.name}/flow-logs"
  retention_in_days = var.flow_log_retention_days
}

data "aws_iam_policy_document" "flow_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow" {
  name               = "${var.name}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_trust.json
}

data "aws_iam_policy_document" "flow" {
  statement {
    sid       = "WriteTheFlowLogGroupOnly"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.flow.arn}:*"]
  }
}

resource "aws_iam_role_policy" "flow" {
  name   = "${var.name}-vpc-flow-logs"
  role   = aws_iam_role.flow.id
  policy = data.aws_iam_policy_document.flow.json
}

resource "aws_flow_log" "main" {
  vpc_id          = aws_vpc.main.id
  traffic_type    = "ALL"
  log_destination = aws_cloudwatch_log_group.flow.arn
  iam_role_arn    = aws_iam_role.flow.arn
}

# --- Security groups ----------------------------------------------------------------------------------------------

resource "aws_security_group" "api_worker" {
  # checkov:skip=CKV2_AWS_5:Attached in another module (ECS services, one-off tasks, RDS, load balancer); Checkov does not follow module outputs.
  name        = "${var.name}-api-worker"
  description = "Task api+worker, db-migrate and the db-bootstrap of the application database"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "txsigner" {
  # checkov:skip=CKV2_AWS_5:Attached in another module (ECS services, one-off tasks, RDS, load balancer); Checkov does not follow module outputs.
  name        = "${var.name}-txsigner"
  description = "TxSigner, txsigner-migrate, the readiness probe and the db-bootstrap of the TxSigner database"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "endpoints" {
  name        = "${var.name}-vpc-endpoints"
  description = "Interface VPC endpoints (KMS, Secrets Manager, STS, Logs, ECR, CloudWatch)"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "alb" {
  # checkov:skip=CKV2_AWS_5:Attached in another module (ECS services, one-off tasks, RDS, load balancer); Checkov does not follow module outputs.
  name        = "${var.name}-alb"
  description = "Public load balancer of the api, web and admin"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "web_admin" {
  # checkov:skip=CKV2_AWS_5:Attached in another module (ECS services, one-off tasks, RDS, load balancer); Checkov does not follow module outputs.
  name        = "${var.name}-web-admin"
  description = "Next.js servers of the web and admin apps"
  vpc_id      = aws_vpc.main.id
}

data "aws_ec2_managed_prefix_list" "s3" {
  name = "com.amazonaws.${data.aws_region.current.name}.s3"
}

# TxSigner: 8443 only from the api and the worker (Service Connect). Nothing outside the VPC reaches it: it has no
# public address, its subnet has no internet route and no other ingress rule than this one and the 8444 of the probe.
resource "aws_vpc_security_group_ingress_rule" "txsigner_from_app" {
  security_group_id            = aws_security_group.txsigner.id
  referenced_security_group_id = aws_security_group.api_worker.id
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  description                  = "Signing API from the api and the worker only"
}

# Endpoints: 443 only from the groups of the tasks.
resource "aws_vpc_security_group_ingress_rule" "endpoints_from_txsigner" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.txsigner.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "AWS APIs from the TxSigner"
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_app" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.api_worker.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "AWS APIs from the api and the worker"
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_from_web_admin" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.web_admin.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "ECR and CloudWatch Logs from the web and admin"
}

# Load balancer: HTTPS from the internet (80 only redirects to 443).
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  # checkov:skip=CKV_AWS_260:Public entry of the product; 80 only answers a redirect to 443.
  for_each          = toset(["443", "80"])
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  description       = each.value == "443" ? "HTTPS from the internet" : "HTTP from the internet, redirected to HTTPS"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_api" {
  for_each                     = toset(["8080", "8081"])
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.api_worker.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  description                  = each.value == "8080" ? "Requests to the api" : "Health check of the api (readiness)"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_web_admin" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.web_admin.id
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
  description                  = "Requests and health checks to the web and admin"
}

# api and worker: 8080 from the load balancer and from the web/admin servers; 8081 (readiness) from the load balancer.
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  for_each                     = toset(["8080", "8081"])
  security_group_id            = aws_security_group.api_worker.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  description                  = each.value == "8080" ? "Requests from the load balancer" : "Health check from the load balancer"
}

resource "aws_vpc_security_group_ingress_rule" "app_from_web_admin" {
  security_group_id            = aws_security_group.api_worker.id
  referenced_security_group_id = aws_security_group.web_admin.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  description                  = "Server-side reads of the web and admin (API_INTERNAL_ORIGIN)"
}

resource "aws_vpc_security_group_ingress_rule" "web_admin_from_alb" {
  security_group_id            = aws_security_group.web_admin.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
  description                  = "Requests and health checks from the load balancer"
}

# Egress of the tasks. Each group reaches the interface endpoints (443) and S3 through the gateway endpoint (443 to the
# S3 prefix list: the layers of the ECR images). The database rules live in modules/data, next to the database groups.
resource "aws_vpc_security_group_egress_rule" "txsigner_to_endpoints" {
  security_group_id            = aws_security_group.txsigner.id
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "KMS, Secrets Manager, STS, Logs, ECR, CloudWatch through the VPC endpoints"
}

resource "aws_vpc_security_group_egress_rule" "to_s3" {
  for_each = {
    txsigner  = aws_security_group.txsigner.id
    api       = aws_security_group.api_worker.id
    web_admin = aws_security_group.web_admin.id
  }
  security_group_id = each.value
  prefix_list_id    = data.aws_ec2_managed_prefix_list.s3.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "S3 through the gateway endpoint (ECR image layers)"
}

resource "aws_vpc_security_group_egress_rule" "app_to_endpoints" {
  security_group_id            = aws_security_group.api_worker.id
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "Secrets Manager, Logs, ECR, CloudWatch through the VPC endpoints"
}

resource "aws_vpc_security_group_egress_rule" "app_to_txsigner" {
  security_group_id            = aws_security_group.api_worker.id
  referenced_security_group_id = aws_security_group.txsigner.id
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  description                  = "Signing API of the TxSigner (Service Connect)"
}

# The only egress to the internet of the whole VPC: HTTPS of the api+worker task through the NAT gateway (subnet B).
# The worker calls the two RPC providers, Jupiter, CoinGecko and api.github.com; the hosts are fixed lists in the code
# (security groups cannot filter by name). The api shares the task and the group in the pilot (accepted in the U2).
resource "aws_vpc_security_group_egress_rule" "app_to_internet" {
  # checkov:skip=CKV_AWS_382:HTTPS to the RPC providers, Jupiter, CoinGecko and GitHub, whose addresses are not fixed; only through the NAT of subnet B.
  security_group_id = aws_security_group.api_worker.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS to RPC, Jupiter, CoinGecko and GitHub through the NAT gateway"
}

resource "aws_vpc_security_group_egress_rule" "web_admin_to_endpoints" {
  security_group_id            = aws_security_group.web_admin.id
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "ECR and CloudWatch Logs through the VPC endpoints"
}

resource "aws_vpc_security_group_egress_rule" "web_admin_to_api" {
  security_group_id            = aws_security_group.web_admin.id
  referenced_security_group_id = aws_security_group.api_worker.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  description                  = "Server-side reads of the api (API_INTERNAL_ORIGIN)"
}

# --- VPC endpoints (Q3) -------------------------------------------------------------------------------------------

# Interface endpoints in subnet A; "monitoring" added by the review of the Infrastructure Design (CloudWatch metrics).
#
# Endpoint policies (security-posture-review.md SP-05): a task of the VPC, even a compromised one in subnet A (no
# internet), reaches through an endpoint only principals AND resources of this account. A credential of another account
# is refused (aws:PrincipalAccount), and so is a resource of another account, such as a secret, key or repository whose
# resource policy would accept this account (aws:ResourceAccount). The resource condition is "IfExists" because some
# calls have no resource (ecr:GetAuthorizationToken, kms:CreateKey, kms:ListKeys, cloudwatch:PutMetricData,
# sts:GetCallerIdentity); those are still bound to the principals of this account.
resource "aws_vpc_endpoint" "interface" {
  for_each            = local.endpoint_actions
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.${data.aws_region.current.name}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [aws_subnet.private_a.id]
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "OwnAccountPrincipalsAndResourcesOnly"
      Effect    = "Allow"
      Principal = "*"
      Action    = "${each.value}:*"
      Resource  = "*"
      Condition = {
        StringEquals         = { "aws:PrincipalAccount" = local.account_id }
        StringEqualsIfExists = { "aws:ResourceAccount" = local.account_id }
      }
    }]
  })
}

# Gateway endpoint of S3 (no cost) on every private route table, so no image layer goes through the NAT. Policy (SP-05):
# the ECR layer bucket of the region (read only; it belongs to AWS and the request is presigned by ECR, so it carries
# no principal of this account) and the buckets of this account, by principals of this account. Every other bucket is
# refused from the three private subnets, the api+worker subnet included (no application code uses S3).
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private_a.id, aws_route_table.private_b.id, aws_route_table.private_web.id]
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EcrImageLayersOfThisRegion"
        Effect    = "Allow"
        Principal = "*"
        Action    = ["s3:GetObject"]
        Resource  = ["arn:aws:s3:::prod-${data.aws_region.current.name}-starport-layer-bucket/*"]
      },
      {
        Sid       = "OwnAccountBucketsOnly"
        Effect    = "Allow"
        Principal = "*"
        Action    = "s3:*"
        Resource  = "*"
        Condition = {
          StringEquals = {
            "aws:PrincipalAccount" = local.account_id
            "aws:ResourceAccount"  = local.account_id
          }
        }
      },
    ]
  })
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

output "private_web_subnet_id" {
  value = aws_subnet.private_web.id
}

output "private_web_cidr" {
  description = "Range of the web and admin servers (the api exempts it from the legal rate limit)"
  value       = aws_subnet.private_web.cidr_block
}

output "private_data_b_subnet_id" {
  value = aws_subnet.private_data_b.id
}

output "public_subnet_ids" {
  value = [aws_subnet.public.id, aws_subnet.public_b.id]
}

output "txsigner_security_group_id" {
  value = aws_security_group.txsigner.id
}

output "api_worker_security_group_id" {
  value = aws_security_group.api_worker.id
}

output "web_admin_security_group_id" {
  value = aws_security_group.web_admin.id
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "kms_endpoint_dns" {
  value = aws_vpc_endpoint.interface["kms"].dns_entry[0].dns_name
}

output "kms_endpoint_id" {
  description = "Id of the KMS interface endpoint: the key policies accept Sign and GenerateMac only through it (SP-02)"
  value       = aws_vpc_endpoint.interface["kms"].id
}

output "nat_public_ip" {
  description = "Fixed egress address of the worker (allow-list it at the RPC providers when they offer it)"
  value       = aws_eip.nat.public_ip
}
