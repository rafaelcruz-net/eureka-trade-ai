# ECS Fargate services of the pilot (infrastructure-specification.md "Deployment"): the task api+worker and the
# TxSigner, one zone, Service Connect with a private Cloud Map namespace (txsigner.internal:8443). The TxSigner keeps
# minimumHealthyPercent 100 and maximumPercent 200 (no window without signatures). Only the TxSigner task assumes the
# KMS role; the api and the worker have no KMS permission (NFR1.2).

variable "name" {
  type = string
}

variable "txsigner_role_arn" {
  type = string
}

variable "txsigner_image" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "security_group_id" {
  type = string
}

variable "environment" {
  description = "Non-secret environment of the TxSigner (secrets come from the secrets manager)"
  type        = map(string)
}

resource "aws_ecs_cluster" "main" {
  name = var.name
}

resource "aws_service_discovery_http_namespace" "internal" {
  name = "internal"
}

resource "aws_ecs_task_definition" "txsigner" {
  family                   = "${var.name}-txsigner"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  task_role_arn            = var.txsigner_role_arn
  container_definitions = jsonencode([{
    name         = "txsigner"
    image        = var.txsigner_image
    essential    = true
    portMappings = [{ name = "txsigner", containerPort = 8443, protocol = "tcp" }]
    environment  = [for k, v in var.environment : { name = k, value = v }]
  }])
}

resource "aws_ecs_service" "txsigner" {
  name                               = "txsigner"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.txsigner.arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

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
