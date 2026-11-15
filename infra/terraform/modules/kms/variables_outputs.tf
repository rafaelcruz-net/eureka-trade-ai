variable "name" {
  description = "Prefix of the resource names"
  type        = string
}

output "txsigner_role_arn" {
  value = aws_iam_role.txsigner.arn
}

output "anchor_hmac_key_id" {
  value = aws_kms_key.anchor_hmac.key_id
}

output "wallet_key_policy" {
  description = "Key policy passed by the TxSigner in every CreateKey (TXSIGNER_KMS_KEY_POLICY)"
  value       = local.wallet_key_policy
}
