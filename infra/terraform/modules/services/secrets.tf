# Secrets of the services (environment-provisioning-questions.md Q4). Terraform creates ONLY the secret, never a value:
# the founder writes each value by hand (validation-report.md, apply runbook), so no value ever lands in the state nor
# in the repository. Each task reads only its own secrets: the execution role of each family is allowed exactly the
# ARNs it injects (iam.tf), and the container definitions inject each one into the container that needs it only.
# Manual rotation every 90 days (docs/runbooks); recovery window of 7 days like the secrets of the U2 and the U5.
# The CoinGecko key and the GitHub token keep their own files (coingecko.tf, github-actions.tf).

locals {
  secrets = {
    "app-db-owner-password"      = "Password of the owner login of the application schema (DB_OWNER_PASSWORD): db-migrate and db-bootstrap only"
    "app-db-app-password"        = "Password of the application login (DB_PASSWORD): api, worker and db-bootstrap"
    "app-db-retention-password"  = "Password of the retention login (DB_RETENTION_PASSWORD): api and db-bootstrap"
    "txsigner-db-owner-password" = "Password of the owner login of the schema txsigner (TXSIGNER_DB_OWNER_PASSWORD): txsigner-migrate and its db-bootstrap"
    "txsigner-db-app-password"   = "Password of the TxSigner login (TXSIGNER_DB_PASSWORD): TxSigner and its db-bootstrap"
    "access-token-signing-key"   = "HMAC key of the 15-minute access token (ACCESS_TOKEN_SIGNING_KEY): api only"
    "audit-wallet-hmac-key"      = "HMAC key of the wallet addresses in the audit trail (MEMETRADE_AUDIT_WALLET_HMAC_KEY): api and worker"
    "txsigner-service-token"     = "Service credential api/worker -> TxSigner (MEMETRADE_TXSIGNER_TOKEN); the TxSigner reads AWSCURRENT and AWSPREVIOUS itself"
    "rpc-primary-url"            = "URL of the primary Solana RPC provider, may carry its API key (MEMETRADE_RPC_PRIMARY_URL): worker only"
    "rpc-secondary-url"          = "URL of the secondary Solana RPC provider, may carry its API key (MEMETRADE_RPC_SECONDARY_URL): worker only"
    "jupiter-api-key"            = "Jupiter API key (MEMETRADE_JUPITER_API_KEY): worker only, injected only after the recording of the Trigger (U7 BR0)"
  }
}

resource "aws_secretsmanager_secret" "this" {
  # checkov:skip=CKV_AWS_149:The AWS managed key of Secrets Manager, like the secrets of the U2 and the U5; a customer key adds cost and no control the pilot uses.
  # checkov:skip=CKV2_AWS_57:Manual rotation every 90 days by the runbook (infrastructure-specification.md U1); no rotation Lambda in the pilot.
  for_each                = local.secrets
  name                    = "${var.name}/${each.key}"
  description             = each.value
  recovery_window_in_days = 7
}
