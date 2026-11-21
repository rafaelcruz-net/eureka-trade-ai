# Task api+worker (infrastructure-specification.md U1: one Fargate task with the two containers of the same image;
# cd-config.md 5.2: service "api-worker", family "${name}-api-worker"). Pending since the U2, created here.
#
# - api: port 8080 behind the load balancer, management 8081 (readiness of the target group).
# - worker: SPRING_PROFILES_ACTIVE=worker; it shares the network namespace of the task, so its server and management
#   port move from 8081 (docker-compose.yml) to 8082 here.
# - Each container receives only its own secrets: the CoinGecko key, the GitHub token, the RPC URLs and the Jupiter key
#   go to the worker only; the access token key and the retention password to the api only.
# - Service Connect client of the namespace "internal" (txsigner.internal:8443); registered in the private DNS namespace
#   as api.<name>.internal for the server side of the web and admin.
# - metrics-agent: CloudWatch Agent sidecar (metrics-agent.tf, Observability Setup Q1), not essential; the task gets its
#   first task role for it (EMF to its own metrics log group only).
#
# Sizing: 1 vCPU and 3 GB (the U1 design said 0.5 vCPU and 1 GB): two JVMs with -Xmx512m each in 1 GB containers, plus
# 128 MB for the metrics agent; Fargate offers 2 GB or 3 GB with 1 vCPU, and 2 GB no longer fits the three hard limits.

locals {
  app_secret = { for key in keys(local.secrets) : key => aws_secretsmanager_secret.this[key].arn }

  api_secrets = [
    { name = "DB_PASSWORD", valueFrom = local.app_secret["app-db-app-password"] },
    { name = "DB_RETENTION_PASSWORD", valueFrom = local.app_secret["app-db-retention-password"] },
    { name = "ACCESS_TOKEN_SIGNING_KEY", valueFrom = local.app_secret["access-token-signing-key"] },
    { name = "MEMETRADE_AUDIT_WALLET_HMAC_KEY", valueFrom = local.app_secret["audit-wallet-hmac-key"] },
    { name = "MEMETRADE_TXSIGNER_TOKEN", valueFrom = local.app_secret["txsigner-service-token"] },
  ]

  worker_secrets = concat([
    { name = "DB_PASSWORD", valueFrom = local.app_secret["app-db-app-password"] },
    { name = "MEMETRADE_AUDIT_WALLET_HMAC_KEY", valueFrom = local.app_secret["audit-wallet-hmac-key"] },
    { name = "MEMETRADE_TXSIGNER_TOKEN", valueFrom = local.app_secret["txsigner-service-token"] },
    { name = "MEMETRADE_RPC_PRIMARY_URL", valueFrom = local.app_secret["rpc-primary-url"] },
    { name = "MEMETRADE_RPC_SECONDARY_URL", valueFrom = local.app_secret["rpc-secondary-url"] },
    { name = "COINGECKO_API_KEY", valueFrom = aws_secretsmanager_secret.coingecko_api_key.arn },
    { name = "MEMETRADE_GITHUB_ACTIONS_TOKEN", valueFrom = aws_secretsmanager_secret.github_actions_read_token.arn },
    ], var.inject_jupiter_api_key ? [
    { name = "MEMETRADE_JUPITER_API_KEY", valueFrom = local.app_secret["jupiter-api-key"] },
  ] : [])

  # Same probe class as docker-compose.yml (com.memetrade.ops.HealthProbe), with the port of each container.
  health_probe = ["CMD", "java", "-Dloader.main=com.memetrade.ops.HealthProbe", "-cp", "/app/app.jar",
  "org.springframework.boot.loader.launch.PropertiesLauncher"]
}

resource "aws_ecs_task_definition" "api_worker" {
  family                   = "${var.name}-api-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"
  memory                   = "3072"
  execution_role_arn       = aws_iam_role.execution["api-worker"].arn
  task_role_arn            = aws_iam_role.api_worker_task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "tmp"
  }
  dynamic "volume" {
    for_each = local.metrics_agent_paths
    content {
      name = volume.key
    }
  }
  container_definitions = jsonencode([
    {
      name                   = "api"
      image                  = var.images["api"]
      essential              = true
      memory                 = 1024
      readonlyRootFilesystem = true
      mountPoints            = local.tmp_mount
      portMappings = [
        { name = "api", containerPort = 8080, protocol = "tcp" },
        { name = "api-management", containerPort = 8081, protocol = "tcp" },
      ]
      environment      = [for k, v in merge(var.app_environment, var.api_environment) : { name = k, value = v }]
      secrets          = local.api_secrets
      logConfiguration = local.log_configuration["api-worker"]
      healthCheck = {
        command     = local.health_probe
        interval    = 10
        timeout     = 5
        retries     = 6
        startPeriod = 60
      }
    },
    {
      name                   = "worker"
      image                  = var.images["api"]
      essential              = true
      memory                 = 1024
      readonlyRootFilesystem = true
      mountPoints            = local.tmp_mount
      environment = [for k, v in merge(var.app_environment, var.worker_environment, {
        SPRING_PROFILES_ACTIVE = "worker,production"
        MEMETRADE_PROCESS      = "worker"
        SERVER_PORT            = "8082"
        MANAGEMENT_SERVER_PORT = "8082"
      }) : { name = k, value = v }]
      secrets          = local.worker_secrets
      logConfiguration = local.log_configuration["api-worker"]
      healthCheck = {
        command     = concat(local.health_probe, ["http://127.0.0.1:8082/actuator/health/liveness"])
        interval    = 10
        timeout     = 5
        retries     = 6
        startPeriod = 60
      }
    },
    local.metrics_agent_container["api-worker"],
  ])
}

resource "aws_ecs_service" "api_worker" {
  name                               = "api-worker"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.api_worker.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 120

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  network_configuration {
    subnets          = [var.app_subnet_id]
    security_groups  = [var.app_security_group_id]
    assign_public_ip = false
  }

  # Client only: resolves txsigner.internal through the Service Connect proxy.
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.internal.arn
  }

  # A records of the task address (awsvpc): no container name nor port.
  service_registries {
    registry_arn = aws_service_discovery_service.api.arn
  }

  # The revision in service is chosen by deploy.yml (cd-config.md).
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.https]
}
