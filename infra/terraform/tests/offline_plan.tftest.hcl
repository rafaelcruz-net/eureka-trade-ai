# Offline plan of the whole root (261113-trading-real-bot Environment Provisioning). mock_provider: no AWS credential,
# no account, no network call. Run from infra/terraform: terraform init -backend=false && terraform test

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
  mock_resource "aws_db_instance" {
    defaults = { master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:sa-east-1:111111111111:secret:rds-master-AbCdEf", kms_key_id = "k", secret_status = "active" }] }
  }
  mock_resource "aws_vpc_endpoint" {
    defaults = { dns_entry = [{ dns_name = "vpce-1.kms.sa-east-1.vpce.amazonaws.com", hosted_zone_id = "Z" }] }
  }
}

variables {
  environment                  = "devnet"
  account_id                   = "111111111111"
  region                       = "sa-east-1"
  zone                         = "sa-east-1a"
  zone_b                       = "sa-east-1b"
  initial_image_tag            = "3497b2bc49052f01adb93de7094a05880532222b"
  db_bootstrap_image_tag       = "3497b2bc49052f01adb93de7094a05880532222b"
  api_domain                   = "api.devnet.example.com"
  web_domain                   = "app.devnet.example.com"
  admin_domain                 = "admin.devnet.example.com"
  certificate_arn              = "arn:aws:acm:sa-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"
  admin_wallet_addresses       = "11111111111111111111111111111111"
  legal_storage_location       = "x"
  legal_international_transfer = "x"
  legal_dpo_email              = "dpo@example.com"
  alarm_email                  = "founder@example.com"
}

run "devnet_plan" {
  command = plan
  # 8 families plus the txsigner-probe declared by the security review (SP-01).
  assert {
    condition     = length(module.services.execution_role_arns) == 9
    error_message = "9 execution roles expected"
  }
  # SP-01: the deploy role may pass only the execution roles of the families it deploys; the TxSigner and bootstrap
  # execution roles are founder-only.
  assert {
    condition = module.services.deploy_passable_execution_role_names == [
    "eureka-real-api-worker-exec", "eureka-real-web-exec", "eureka-real-admin-exec", "eureka-real-db-migrate-exec"]
    error_message = "the deploy may pass a role other than api-worker, web, admin and db-migrate"
  }
  assert {
    condition = toset(module.services.founder_only_execution_role_names) == toset([
      "eureka-real-txsigner-exec", "eureka-real-txsigner-migrate-exec", "eureka-real-txsigner-probe-exec",
    "eureka-real-db-bootstrap-exec", "eureka-real-txsigner-db-bootstrap-exec"])
    error_message = "the founder-only execution roles differ"
  }
  assert {
    condition     = length(module.services.secret_arns) == 13
    error_message = "13 secrets expected"
  }
}

run "mainnet_without_founder_warns" {
  command = plan
  variables {
    environment = "mainnet"
  }
  expect_failures = [check.founder_on_mainnet]
}

run "mainnet_with_founder" {
  command = plan
  variables {
    environment     = "mainnet"
    founder_user_id = "8f1c2d3e-4b5a-4c6d-8e7f-901234567890"
  }
}
