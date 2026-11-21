# KMS of the bot wallet (infrastructure-specification.md "Política do KMS", Q3, NFR1.2): the role of the TxSigner, the
# HMAC key of the anchor (mainWalletMac) and the key policy the TxSigner passes in every CreateKey. The wallet keys
# themselves are created at run time by the TxSigner, never by Terraform.
#
# Security review of the Environment Provisioning (security-posture-review.md SP-02, SP-03):
# - the role trusts ECS tasks of THIS account only (aws:SourceAccount and aws:SourceArn), like the execution roles;
# - Sign (wallet keys) and GenerateMac/VerifyMac (anchor key) are denied to everyone, the TxSigner included, unless the
#   call comes through the KMS interface endpoint of the VPC (aws:SourceVpce): a TxSigner credential stolen from the
#   task metadata cannot sign from outside the VPC. These Deny statements are appended here, in HCL, to the versioned
#   templates of policies/ (read by KmsPolicyDocumentsTest of apps/txsigner), because the endpoint id only exists in
#   the plan;
# - the account (root) statement no longer grants TagResource/UntagResource: tag changes need an MFA session
#   (statement TagChangesOnlyWithMfa of the templates; the SCP of infra/terraform/org denies them too).

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.name

  wallet_key_policy_template = jsondecode(templatefile("${path.module}/policies/wallet-key-policy.json.tftpl", {
    account_id        = local.account_id
    txsigner_role_arn = aws_iam_role.txsigner.arn
  }))

  hmac_key_policy_template = jsondecode(templatefile("${path.module}/policies/hmac-key-policy.json.tftpl", {
    account_id        = local.account_id
    txsigner_role_arn = aws_iam_role.txsigner.arn
  }))

  wallet_key_policy = jsonencode(merge(local.wallet_key_policy_template, {
    Statement = concat(local.wallet_key_policy_template.Statement, [{
      Sid       = "SignOnlyThroughTheKmsVpcEndpoint"
      Effect    = "Deny"
      Principal = { AWS = "*" }
      Action    = ["kms:Sign"]
      Resource  = "*"
      Condition = { StringNotEquals = { "aws:SourceVpce" = var.kms_vpc_endpoint_id } }
    }])
  }))

  hmac_key_policy = jsonencode(merge(local.hmac_key_policy_template, {
    Statement = concat(local.hmac_key_policy_template.Statement, [{
      Sid       = "MacOnlyThroughTheKmsVpcEndpoint"
      Effect    = "Deny"
      Principal = { AWS = "*" }
      Action    = ["kms:GenerateMac", "kms:VerifyMac"]
      Resource  = "*"
      Condition = { StringNotEquals = { "aws:SourceVpce" = var.kms_vpc_endpoint_id } }
    }])
  }))
}

resource "aws_iam_role" "txsigner" {
  name = "${var.name}-txsigner"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "EcsTasksOfThisAccountOnly"
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:aws:ecs:${local.region}:${local.account_id}:*" }
      }
    }]
  })
}

resource "aws_kms_key" "anchor_hmac" {
  description              = "HMAC of the main wallet anchor of the bot wallets (mainWalletMac)"
  key_usage                = "GENERATE_VERIFY_MAC"
  customer_master_key_spec = "HMAC_256"
  deletion_window_in_days  = 30
  policy                   = local.hmac_key_policy
}

resource "aws_iam_role_policy" "txsigner" {
  name = "${var.name}-txsigner-kms"
  role = aws_iam_role.txsigner.id
  policy = templatefile("${path.module}/policies/txsigner-role.json.tftpl", {
    region       = local.region
    account_id   = local.account_id
    hmac_key_arn = aws_kms_key.anchor_hmac.arn
  })
}
