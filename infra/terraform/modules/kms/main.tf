# KMS of the bot wallet (infrastructure-specification.md "Política do KMS", Q3, NFR1.2): the role of the TxSigner, the
# HMAC key of the anchor (mainWalletMac) and the key policy the TxSigner passes in every CreateKey. The wallet keys
# themselves are created at run time by the TxSigner, never by Terraform.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

resource "aws_iam_role" "txsigner" {
  name = "${var.name}-txsigner"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_kms_key" "anchor_hmac" {
  description              = "HMAC of the main wallet anchor of the bot wallets (mainWalletMac)"
  key_usage                = "GENERATE_VERIFY_MAC"
  customer_master_key_spec = "HMAC_256"
  deletion_window_in_days  = 30
  policy = templatefile("${path.module}/policies/hmac-key-policy.json.tftpl", {
    account_id        = data.aws_caller_identity.current.account_id
    txsigner_role_arn = aws_iam_role.txsigner.arn
  })
}

resource "aws_iam_role_policy" "txsigner" {
  name = "${var.name}-txsigner-kms"
  role = aws_iam_role.txsigner.id
  policy = templatefile("${path.module}/policies/txsigner-role.json.tftpl", {
    region       = data.aws_region.current.name
    account_id   = data.aws_caller_identity.current.account_id
    hmac_key_arn = aws_kms_key.anchor_hmac.arn
  })
}

locals {
  wallet_key_policy = templatefile("${path.module}/policies/wallet-key-policy.json.tftpl", {
    account_id        = data.aws_caller_identity.current.account_id
    txsigner_role_arn = aws_iam_role.txsigner.arn
  })
}
