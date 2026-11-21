# Offline checks of the deploy role (security-posture-review.md SP-01, Environment Provisioning of
# 261113-trading-real-bot): it passes only the roles of the families it deploys, never a founder-only role, never runs
# nor updates the TxSigner or bootstrap families, never writes the txsigner images and reads only the migration logs.
# mock_provider: the "apply" is simulated in memory, no AWS credential, account nor network call.
# Run from infra/terraform: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_region" {
    defaults = { name = "sa-east-1" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "111111111111" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  name                        = "eureka-real"
  environment                 = "mainnet"
  github_repository           = "owner/repository"
  create_github_oidc_provider = true
  cluster_name                = "eureka-real"
  passable_role_arns = [
    "arn:aws:iam::111111111111:role/eureka-real-api-worker-exec",
    "arn:aws:iam::111111111111:role/eureka-real-web-exec",
    "arn:aws:iam::111111111111:role/eureka-real-admin-exec",
    "arn:aws:iam::111111111111:role/eureka-real-db-migrate-exec",
  ]
  founder_only_role_arns = [
    "arn:aws:iam::111111111111:role/eureka-real-txsigner",
    "arn:aws:iam::111111111111:role/eureka-real-txsigner-exec",
    "arn:aws:iam::111111111111:role/eureka-real-txsigner-migrate-exec",
    "arn:aws:iam::111111111111:role/eureka-real-txsigner-probe-exec",
    "arn:aws:iam::111111111111:role/eureka-real-db-bootstrap-exec",
    "arn:aws:iam::111111111111:role/eureka-real-txsigner-db-bootstrap-exec",
  ]
  txsigner_security_group_id = "sg-t"
}

run "deploy_role_scope" {
  command = apply
  module {
    source = "./modules/delivery"
  }

  # PassRole: exactly the four deploy roles, only to ECS tasks.
  assert {
    condition = toset([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "PassOnlyTheDeployRolesToEcs"][0]) == toset(var.passable_role_arns)
    error_message = "PassRole is not limited to the api-worker, web, admin and db-migrate execution roles"
  }
  assert {
    condition = [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Condition.StringEquals["iam:PassedToService"]
    if s.Sid == "PassOnlyTheDeployRolesToEcs"][0] == "ecs-tasks.amazonaws.com"
    error_message = "PassRole is not limited to ECS tasks"
  }
  assert {
    condition = length([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s
      if s.Effect == "Allow" && contains(flatten([s.Action]), "iam:PassRole")
    && anytrue([for r in flatten([s.Resource]) : strcontains(r, "txsigner") || strcontains(r, "bootstrap")])]) == 0
    error_message = "an Allow statement passes a TxSigner or bootstrap role"
  }
  # Explicit Deny of every founder-only role.
  assert {
    condition = [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s
    if s.Sid == "NeverPassTheFounderOnlyRoles"][0].Effect == "Deny"
    error_message = "NeverPassTheFounderOnlyRoles is not a Deny"
  }
  assert {
    condition = toset([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "NeverPassTheFounderOnlyRoles"][0]) == toset(var.founder_only_role_arns)
    error_message = "the Deny of PassRole does not cover every founder-only role"
  }
  # RunTask: the db-migrate family only; Deny of every TxSigner and bootstrap family and of the long-running ones.
  assert {
    condition = [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "EcsRunTheDbMigrationOnly"][0] == ["arn:aws:ecs:sa-east-1:111111111111:task-definition/eureka-real-db-migrate:*"]
    error_message = "the deploy may run a family other than db-migrate"
  }
  assert {
    condition = length(setsubtract([
      "arn:aws:ecs:sa-east-1:111111111111:task-definition/eureka-real-txsigner*",
      "arn:aws:ecs:sa-east-1:111111111111:task-definition/eureka-real-db-bootstrap:*",
      "arn:aws:ecs:sa-east-1:111111111111:task-definition/eureka-real-api-worker:*",
      ], [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "NeverRunTheFounderOnlyOrLongRunningFamiliesAsOneOff"][0])) == 0
    error_message = "RunTask of a TxSigner, bootstrap or long-running family is not denied"
  }
  # UpdateService: api-worker, web and admin only; the txsigner service is denied; no founder-only revision anywhere.
  assert {
    condition = toset([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
      if s.Sid == "EcsUpdateTheApiWorkerWebAdminServicesOnly"][0]) == toset([
      "arn:aws:ecs:sa-east-1:111111111111:service/eureka-real/api-worker",
      "arn:aws:ecs:sa-east-1:111111111111:service/eureka-real/web",
    "arn:aws:ecs:sa-east-1:111111111111:service/eureka-real/admin"])
    error_message = "UpdateService is allowed on a service other than api-worker, web and admin"
  }
  assert {
    condition = [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "NeverChangeTheTxSignerService" && s.Effect == "Deny"][0] == "arn:aws:ecs:sa-east-1:111111111111:service/eureka-real/txsigner"
    error_message = "the txsigner service is not denied"
  }
  assert {
    condition = contains([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Condition.ArnLike["ecs:task-definition"]
    if s.Sid == "NeverUseAFounderOnlyRevision" && s.Effect == "Deny"][0], "arn:aws:ecs:sa-east-1:111111111111:task-definition/eureka-real-txsigner*")
    error_message = "a TxSigner revision may be put in service through another service"
  }
  # ECR: push to api, web and admin only; the txsigner repository is read-only (digest) and its writes are denied.
  assert {
    condition = !contains([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "EcrPushAndReadTheApiWebAdminRepositoriesOnly"][0], aws_ecr_repository.app["txsigner"].arn)
    error_message = "the deploy may push the txsigner image"
  }
  assert {
    condition = [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "NeverWriteTheTxSignerImages" && s.Effect == "Deny"][0] == [aws_ecr_repository.app["txsigner"].arn]
    error_message = "writes to the txsigner repository are not denied"
  }
  # Logs: the migration log group only (never the TxSigner nor the bootstrap ones).
  assert {
    condition = [for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Resource
    if s.Sid == "ReadTheLogsOfTheMigrationsOnly"][0] == "arn:aws:logs:sa-east-1:111111111111:log-group:/ecs/eureka-real/migrate:*"
    error_message = "the deploy reads log groups other than the migrations"
  }
  # No KMS, no secret: no Allow names them, and the Deny stays.
  assert {
    condition = length([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s
    if s.Effect == "Allow" && anytrue([for a in flatten([s.Action]) : startswith(a, "kms:") || startswith(a, "secretsmanager:")])]) == 0
    error_message = "an Allow statement grants KMS or Secrets Manager"
  }
  assert {
    condition = toset([for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : s.Action
    if s.Sid == "NeverKmsSecretsOrShell"][0]) == toset(["ecs:ExecuteCommand", "kms:*", "secretsmanager:*", "ssm:StartSession"])
    error_message = "the Deny of KMS, secrets and shell changed"
  }
}

run "a_founder_only_role_in_passable_fails_the_plan" {
  command = plan
  module {
    source = "./modules/delivery"
  }
  variables {
    passable_role_arns = [
      "arn:aws:iam::111111111111:role/eureka-real-api-worker-exec",
      "arn:aws:iam::111111111111:role/eureka-real-txsigner",
    ]
  }
  expect_failures = [aws_iam_role_policy.deploy]
}
