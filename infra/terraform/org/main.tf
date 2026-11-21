# AWS Organizations guardrails of the REAL pilot (261113-trading-real-bot Environment Provisioning, Q2): a SEPARATE
# root, applied once by the founder from the MANAGEMENT account (root user only to create the organization; then an
# administration role with MFA). It is never applied by the CI and never shares a state with the workload roots.
#
# Account layout:
#   management account  Organizations, the SCP below, billing and budgets; no workload, no KMS key
#   devnet member       Terraform root infra/terraform with environment = "devnet" (KMS real, Solana devnet)
#   mainnet member      Terraform root infra/terraform with environment = "mainnet"; the only account with mainnet keys
#
# The SCP (policies/scp-guardrails.json.tftpl) is attached to the two member accounts only (an SCP never restricts
# the management account). It denies every regional action outside the allowed region, the deletion, disabling, policy
# change and tag change of KMS keys without MFA, leaving the organization, and turning the CloudTrail off or deleting
# its log group without MFA. Security review of the Environment Provisioning (security-posture-review.md SP-02, SP-03):
# without MFA, nobody changes, deletes or passes the TxSigner role nor the execution roles of the TxSigner and bootstrap
# families, nobody assumes the TxSigner role, and the TxSigner role is never passed to anything but ECS tasks. An SCP
# never applies to service principals, so ECS still assumes the role for the TxSigner task. The policy is minified
# (an SCP holds at most 5120 characters; tests/scp.tftest.hcl checks it).

terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.81"
    }
  }
  backend "s3" {
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region              = var.region
  allowed_account_ids = [var.management_account_id]
}

variable "region" {
  description = "The single region allowed to the member accounts"
  type        = string
}

variable "management_account_id" {
  type = string
}

variable "devnet_account_id" {
  type = string
}

variable "mainnet_account_id" {
  type = string
}

variable "name" {
  description = "Prefix of the workload resources in the member accounts (var.name of infra/terraform)"
  type        = string
  default     = "eureka-real"
}

# The organization itself is created once by hand (aws organizations create-organization --feature-set ALL) and the
# two member accounts are created or invited by the founder; this root only manages the policy and its attachments,
# so a mistake here never deletes an account.
resource "aws_organizations_policy" "guardrails" {
  name        = "eureka-real-guardrails"
  description = "Region lock; KMS keys, TxSigner roles and audit trail changed only with MFA; no leaving the organization"
  type        = "SERVICE_CONTROL_POLICY"
  content = jsonencode(jsondecode(templatefile("${path.module}/policies/scp-guardrails.json.tftpl", {
    allowed_region = var.region
    name           = var.name
  })))
}

resource "aws_organizations_policy_attachment" "member" {
  for_each  = { devnet = var.devnet_account_id, mainnet = var.mainnet_account_id }
  policy_id = aws_organizations_policy.guardrails.id
  target_id = each.value
}

output "scp_id" {
  value = aws_organizations_policy.guardrails.id
}
