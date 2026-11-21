# Web and admin (Environment Provisioning decision: WEB_ADMIN_ON_ECS=true). Each Next.js server runs as its own ECS
# service behind the same load balancer (host-based rules, alb.tf), in a private subnet WITHOUT internet: it only
# reaches the api (API_INTERNAL_ORIGIN, server side) and the VPC endpoints. No secret, no task role, no NEXT_PUBLIC_*
# variable (the origins are runtime server configuration, R-04). The three hosts share one registrable domain, so the
# SameSite=Strict refresh cookie keeps working (project.md, Corrections).

locals {
  frontends = {
    web = {
      environment = {
        API_ORIGIN          = "https://${var.api_domain}"
        API_INTERNAL_ORIGIN = "http://api.${var.name}.internal:8080"
      }
    }
    admin = {
      environment = {
        API_ORIGIN          = "https://${var.api_domain}"
        GUEST_APP_ORIGIN    = "https://${var.web_domain}"
        API_INTERNAL_ORIGIN = "http://api.${var.name}.internal:8080"
      }
    }
  }
}

resource "aws_ecs_task_definition" "frontend" {
  # checkov:skip=CKV_AWS_336:The Next.js standalone server writes its cache under .next/cache; read-only root to be tried on the devnet first (validation-report.md, open items).
  for_each                 = local.frontends
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name             = each.key
    image            = var.images[each.key]
    essential        = true
    portMappings     = [{ name = each.key, containerPort = 3000, protocol = "tcp" }]
    environment      = [for k, v in each.value.environment : { name = k, value = v }]
    logConfiguration = local.log_configuration[each.key]
    # Same probe as the Dockerfile and docker-compose.yml.
    healthCheck = {
      command     = ["CMD", "node", "-e", "fetch('http://127.0.0.1:3000/api/health').then(r=>process.exit(r.status===200?0:1)).catch(()=>process.exit(1))"]
      interval    = 10
      timeout     = 3
      retries     = 6
      startPeriod = 20
    }
  }])
}

resource "aws_ecs_service" "frontend" {
  for_each                           = local.frontends
  name                               = each.key
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.frontend[each.key].arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 60

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.frontend[each.key].arn
    container_name   = each.key
    container_port   = 3000
  }

  network_configuration {
    subnets          = [var.web_subnet_id]
    security_groups  = [var.web_admin_security_group_id]
    assign_public_ip = false
  }

  # The revision in service is chosen by deploy.yml (cd-config.md).
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.https]
}
