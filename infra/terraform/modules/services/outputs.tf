output "cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "execution_role_arns" {
  description = "Every execution role (deploy and founder-only)"
  value       = [for role in aws_iam_role.execution : role.arn]
}

output "deploy_passable_execution_role_arns" {
  description = "The execution roles the deploy role may pass to ECS (api-worker, web, admin, db-migrate; SP-01)"
  value       = [for key in local.deploy_execution_roles : aws_iam_role.execution[key].arn]
}

output "deploy_passable_execution_role_names" {
  description = "Names of deploy_passable_execution_role_arns (offline tests)"
  value       = [for key in local.deploy_execution_roles : aws_iam_role.execution[key].name]
}

output "founder_only_execution_role_arns" {
  description = "Execution roles of the TxSigner and bootstrap families: passed only by the founder with MFA (SP-01)"
  value       = [for key in local.founder_execution_roles : aws_iam_role.execution[key].arn]
}

output "founder_only_execution_role_names" {
  description = "Names of founder_only_execution_role_arns (offline tests)"
  value       = [for key in local.founder_execution_roles : aws_iam_role.execution[key].name]
}

# Revisions registered by Terraform (reviewed in the founder's plan) of the founder-only families. The founder's
# procedure of cd-config.md 4.6 starts from these ARNs, never from the "latest" revision of the family, because the
# deploy role can still register a revision of any family (RegisterTaskDefinition has no family condition in IAM).
output "founder_task_definition_arns" {
  description = "Terraform revisions of the founder-only families (txsigner, txsigner-migrate, txsigner-probe, bootstraps)"
  value = {
    txsigner              = aws_ecs_task_definition.txsigner.arn
    txsigner-migrate      = aws_ecs_task_definition.txsigner_migrate.arn
    txsigner-probe        = aws_ecs_task_definition.txsigner_probe.arn
    db-bootstrap          = aws_ecs_task_definition.db_bootstrap.arn
    txsigner-db-bootstrap = aws_ecs_task_definition.txsigner_db_bootstrap.arn
  }
}

output "api_target_group_arn" {
  description = "GitHub Environment variable API_TARGET_GROUP_ARN"
  value       = aws_lb_target_group.api.arn
}

output "load_balancer_dns_name" {
  description = "Target of the DNS records (CNAME or alias) of the three hosts"
  value       = aws_lb.main.dns_name
}

output "load_balancer_zone_id" {
  value = aws_lb.main.zone_id
}

output "db_bootstrap_repository_url" {
  value = aws_ecr_repository.db_bootstrap.repository_url
}

output "secret_arns" {
  description = "Secrets whose value the founder sets by hand before the first deploy (names only, never values)"
  value = merge(
    { for key, secret in aws_secretsmanager_secret.this : key => secret.arn },
    {
      coingecko-api-key         = aws_secretsmanager_secret.coingecko_api_key.arn
      github-actions-read-token = aws_secretsmanager_secret.github_actions_read_token.arn
    },
  )
}

output "txsigner_service_token_secret_arn" {
  value = aws_secretsmanager_secret.this["txsigner-service-token"].arn
}

# 261121-paper-real-unico U4 (F4, NFR8.4): the plain environment of each container of the api+worker task (container =>
# name => value), so the offline test reads the flag of the own quantity where the containers read it. Only
# `environment`, never `secrets`.
output "api_worker_container_environment" {
  description = "The environment (no secret) of each container of the api+worker task definition (offline tests)"
  value = {
    for c in jsondecode(aws_ecs_task_definition.api_worker.container_definitions) :
    c.name => { for e in try(c.environment, []) : e.name => e.value }
  }
}
