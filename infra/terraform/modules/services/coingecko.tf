# Secret of the real market source (261113-trading-real-bot U2, infrastructure-specification.md "Infrastructure
# Services", BR1.10). Only the worker reads it: the policy below is attached to the execution role of the api+worker
# task (iam.tf) and only the worker container definition injects it (api-worker.tf; Environment Provisioning). The value
# is written by hand, never by Terraform, so it never lands in the state. Manual rotation every 90 days; on a leak:
# revoke the key at CoinGecko, store the new one here and force a new deployment of the worker.

resource "aws_secretsmanager_secret" "coingecko_api_key" {
  # checkov:skip=CKV_AWS_149:The AWS managed key of Secrets Manager; a customer key adds cost and no control the pilot uses.
  # checkov:skip=CKV2_AWS_57:Manual rotation every 90 days by the runbook; no rotation Lambda in the pilot.
  name                    = "${var.name}/coingecko-api-key"
  description             = "CoinGecko API key of the worker (COINGECKO_API_KEY); value set by hand, rotated every 90 days"
  recovery_window_in_days = 7
}

data "aws_iam_policy_document" "worker_reads_coingecko_key" {
  statement {
    sid       = "WorkerReadsTheCoinGeckoKeyOnly"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.coingecko_api_key.arn]
  }
}

resource "aws_iam_policy" "worker_reads_coingecko_key" {
  name        = "${var.name}-worker-reads-coingecko-key"
  description = "Read of the CoinGecko key by the execution role of the worker only (U2 NFR1.6)"
  policy      = data.aws_iam_policy_document.worker_reads_coingecko_key.json
}

output "coingecko_api_key_secret_arn" {
  description = "ARN of the secret coingecko-api-key, injected as COINGECKO_API_KEY into the worker only"
  value       = aws_secretsmanager_secret.coingecko_api_key.arn
}

output "worker_reads_coingecko_key_policy_arn" {
  description = "Policy to attach to the execution role of the worker task (never to the api nor the TxSigner)"
  value       = aws_iam_policy.worker_reads_coingecko_key.arn
}
