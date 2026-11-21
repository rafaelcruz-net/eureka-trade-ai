variable "name" {
  description = "Prefix of the resource names"
  type        = string
}

variable "kms_vpc_endpoint_id" {
  description = "Interface endpoint of the KMS in the VPC: Sign and GenerateMac/VerifyMac are denied from anywhere else (SP-02)"
  type        = string
}

output "txsigner_role_arn" {
  value = aws_iam_role.txsigner.arn
}

output "txsigner_role_name" {
  description = "Task role of the TxSigner (Environment Provisioning attaches the read of its service token secret)"
  value       = aws_iam_role.txsigner.name
}

output "anchor_hmac_key_id" {
  value = aws_kms_key.anchor_hmac.key_id
}

output "wallet_key_policy" {
  description = "Key policy passed by the TxSigner in every CreateKey (TXSIGNER_KMS_KEY_POLICY)"
  value       = local.wallet_key_policy
}
