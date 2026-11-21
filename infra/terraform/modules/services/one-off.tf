# One-off task families (cd-config.md 5.3), never long-running services.
#
# - ${name}-db-migrate: run by deploy.yml before the api-worker rollout (deploy role: EcsRunTheDbMigrationOnly), with the
#   OWNER login of the application schema, which no other family receives.
# - ${name}-txsigner-migrate and ${name}-txsigner-probe: TxSigner families, run by the FOUNDER only with MFA, before
#   deploy.yml (security-posture-review.md SP-01, cd-config.md 4.6). The deploy role can neither pass their roles nor
#   run them. The probe has NO task role (so no KMS) and an execution role without any secret.
# - ${name}-db-bootstrap and ${name}-txsigner-db-bootstrap: run by the FOUNDER only (administration role with MFA), once
#   per environment before the first migration and again after a password rotation. They log in with the RDS-managed
#   master secret and create the logins of infra/postgres/bootstrap. The deploy role can neither pass their roles nor
#   run them.
#
# All of them write to /ecs/${name}/migrate or /ecs/${name}/bootstrap. The network (subnet and group) is chosen at
# run-task time: the TxSigner ones in subnet A with the TxSigner group, the application ones in subnet B with the
# api+worker group (validation-report.md, health checks and runbook).

resource "aws_ecr_repository" "db_bootstrap" {
  # checkov:skip=CKV_AWS_136:AES256 like the four repositories of modules/delivery; the image holds the SQL only, no secret.
  name                 = "${var.name}/db-bootstrap"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecs_task_definition" "txsigner_migrate" {
  family                   = "${var.name}-txsigner-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution["txsigner-migrate"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode([{
    name                   = "txsigner-migrate"
    image                  = var.images["txsigner"]
    essential              = true
    readonlyRootFilesystem = true
    mountPoints            = local.tmp_mount
    environment = [for k, v in {
      SPRING_PROFILES_ACTIVE = "migrate"
      TXSIGNER_DB_HOST       = var.db_endpoints["txsigner"]
      TXSIGNER_DB_NAME       = var.db_names["txsigner"]
      TXSIGNER_DB_OWNER_USER = "txsigner_owner"
    } : { name = k, value = v }]
    secrets = [
      { name = "TXSIGNER_DB_OWNER_PASSWORD", valueFrom = aws_secretsmanager_secret.this["txsigner-db-owner-password"].arn },
    ]
    logConfiguration = local.log_configuration["migrate"]
  }])
}

# Readiness of a TxSigner task (deployment-strategy.md "Ordem e prontidão"), declared here instead of being registered by
# the deploy workflow (SP-01, item 3 of the suggested fix). The founder runs it in subnet A with the TxSigner security
# group and gives the private address of the task to check in TXSIGNER_PROBE_IP (container override of the
# environment, cd-config.md 4.6). It ends with 0 when /actuator/health/readiness (database and ListKeys) answers 200.
resource "aws_ecs_task_definition" "txsigner_probe" {
  family                   = "${var.name}-txsigner-probe"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution["txsigner-probe"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = "probe"
    image                  = var.images["txsigner"]
    essential              = true
    readonlyRootFilesystem = true
    entryPoint             = ["sh", "-c"]
    command = [
      "[ -n \"$TXSIGNER_PROBE_IP\" ] || exit 2; for i in $(seq 1 30); do wget -q -O - \"http://$TXSIGNER_PROBE_IP:8444/actuator/health/readiness\" && exit 0; sleep 10; done; exit 1",
    ]
    logConfiguration = local.log_configuration["txsigner"]
  }])
}

resource "aws_ecs_task_definition" "db_migrate" {
  family                   = "${var.name}-db-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.execution["db-migrate"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode([{
    name                   = "db-migrate"
    image                  = var.images["api"]
    essential              = true
    readonlyRootFilesystem = true
    mountPoints            = local.tmp_mount
    # Same entry point as the db-migrate service of docker-compose.yml.
    entryPoint = ["java", "-Dloader.main=com.memetrade.ops.MigrationRunner", "-cp", "/app/app.jar",
    "org.springframework.boot.loader.launch.PropertiesLauncher"]
    environment = [for k, v in {
      DB_HOST       = var.db_endpoints["app"]
      DB_PORT       = "5432"
      DB_NAME       = var.db_names["app"]
      DB_OWNER_USER = "memetrade_owner"
    } : { name = k, value = v }]
    secrets = [
      { name = "DB_OWNER_PASSWORD", valueFrom = aws_secretsmanager_secret.this["app-db-owner-password"].arn },
    ]
    logConfiguration = local.log_configuration["migrate"]
  }])
}

# Bootstrap of the application database: infra/postgres/bootstrap/bootstrap.sh (10-roles.sql) with the master login.
resource "aws_ecs_task_definition" "db_bootstrap" {
  family                   = "${var.name}-db-bootstrap"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution["db-bootstrap"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "db-bootstrap"
    image     = "${aws_ecr_repository.db_bootstrap.repository_url}:${var.db_bootstrap_image_tag}"
    essential = true
    environment = [for k, v in {
      POSTGRES_HOST     = var.db_endpoints["app"]
      POSTGRES_PORT     = "5432"
      POSTGRES_DB       = var.db_names["app"]
      DB_OWNER_USER     = "memetrade_owner"
      DB_USER           = "memetrade_app"
      DB_RETENTION_USER = "memetrade_retention"
    } : { name = k, value = v }]
    secrets = [
      { name = "POSTGRES_USER", valueFrom = "${var.db_master_secret_arns["app"]}:username::" },
      { name = "POSTGRES_PASSWORD", valueFrom = "${var.db_master_secret_arns["app"]}:password::" },
      { name = "DB_OWNER_PASSWORD", valueFrom = aws_secretsmanager_secret.this["app-db-owner-password"].arn },
      { name = "DB_PASSWORD", valueFrom = aws_secretsmanager_secret.this["app-db-app-password"].arn },
      { name = "DB_RETENTION_PASSWORD", valueFrom = aws_secretsmanager_secret.this["app-db-retention-password"].arn },
    ]
    logConfiguration = local.log_configuration["bootstrap"]
  }])
}

# Bootstrap of the TxSigner database: ONLY 20-txsigner.sql (its schema and two logins), never the application logins.
resource "aws_ecs_task_definition" "txsigner_db_bootstrap" {
  family                   = "${var.name}-txsigner-db-bootstrap"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution["txsigner-db-bootstrap"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name       = "txsigner-db-bootstrap"
    image      = "${aws_ecr_repository.db_bootstrap.repository_url}:${var.db_bootstrap_image_tag}"
    essential  = true
    entryPoint = ["sh", "-c"]
    command = [
      "PGPASSWORD=\"$POSTGRES_PASSWORD\" psql -X -v ON_ERROR_STOP=1 -h \"$POSTGRES_HOST\" -p 5432 -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -f /bootstrap/20-txsigner.sql",
    ]
    environment = [for k, v in {
      POSTGRES_HOST          = var.db_endpoints["txsigner"]
      POSTGRES_DB            = var.db_names["txsigner"]
      TXSIGNER_DB_OWNER_USER = "txsigner_owner"
      TXSIGNER_DB_USER       = "txsigner_app"
    } : { name = k, value = v }]
    secrets = [
      { name = "POSTGRES_USER", valueFrom = "${var.db_master_secret_arns["txsigner"]}:username::" },
      { name = "POSTGRES_PASSWORD", valueFrom = "${var.db_master_secret_arns["txsigner"]}:password::" },
      { name = "TXSIGNER_DB_OWNER_PASSWORD", valueFrom = aws_secretsmanager_secret.this["txsigner-db-owner-password"].arn },
      { name = "TXSIGNER_DB_PASSWORD", valueFrom = aws_secretsmanager_secret.this["txsigner-db-app-password"].arn },
    ]
    logConfiguration = local.log_configuration["bootstrap"]
  }])
}
