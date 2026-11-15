# Cloud of the REAL pilot (infrastructure-specification.md): written in the U1, applied only when the AWS account
# exists, by the founder from his machine with the administration role and MFA. NOT applied by the CI: the CI only
# runs fmt, validate and Checkov (cicd-pipeline.md). The state lives in an S3 bucket of the account itself (partial
# backend configuration given at `terraform init -backend-config=...`).

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type = string
}

variable "zone" {
  type = string
}

variable "name" {
  type    = string
  default = "eureka-real"
}

variable "txsigner_image" {
  description = "Image of the TxSigner tagged with the commit SHA"
  type        = string
}

variable "alarm_topic_arn" {
  type = string
}

module "network" {
  source = "./modules/network"
  name   = var.name
  zone   = var.zone
}

module "kms" {
  source = "./modules/kms"
  name   = var.name
}

module "data" {
  source                     = "./modules/data"
  name                       = var.name
  subnet_ids                 = [module.network.private_a_subnet_id, module.network.private_b_subnet_id]
  app_security_group_id      = module.network.api_worker_security_group_id
  txsigner_security_group_id = module.network.txsigner_security_group_id
}

module "services" {
  source            = "./modules/services"
  name              = var.name
  txsigner_role_arn = module.kms.txsigner_role_arn
  txsigner_image    = var.txsigner_image
  subnet_id         = module.network.private_a_subnet_id
  security_group_id = module.network.txsigner_security_group_id
  environment = {
    SPRING_PROFILES_ACTIVE         = "production"
    TXSIGNER_NETWORK               = "MAINNET"
    TXSIGNER_CREDENTIAL_SOURCE     = "secrets-manager"
    TXSIGNER_KMS_HMAC_KEY_ID       = module.kms.anchor_hmac_key_id
    TXSIGNER_KMS_KEY_POLICY        = module.kms.wallet_key_policy
    TXSIGNER_ALLOWED_KMS_ENDPOINTS = "https://${module.network.kms_endpoint_dns}"
    TXSIGNER_DB_HOST               = module.data.endpoints["txsigner"]
  }
}

module "observability" {
  source          = "./modules/observability"
  name            = var.name
  alarm_topic_arn = var.alarm_topic_arn
}
