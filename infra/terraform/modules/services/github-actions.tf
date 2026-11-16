# Read-only token of GitHub Actions for the CI gate of the REAL releases (261113-trading-real-bot U5,
# infrastructure-specification.md "Segredo", BR1.4). Only the worker reads it (MEMETRADE_GITHUB_ACTIONS_TOKEN); the
# worker task does not exist in Terraform yet (pending since the U2), so this secret has no consumer today: when the
# task is created (Environment Provisioning), its execution role receives the policy below. The value is written by
# hand, never by Terraform, so it never lands in the state. It is a fine-grained token with "Actions: read" on this
# repository only; rotated every 90 days with docs/runbooks/real-operations.md "Troca do token do GitHub".

resource "aws_secretsmanager_secret" "github_actions_read_token" {
  name                    = "${var.name}/github-actions-read-token"
  description             = "GitHub Actions read-only token of the worker (MEMETRADE_GITHUB_ACTIONS_TOKEN); set by hand, rotated every 90 days"
  recovery_window_in_days = 7
}

data "aws_iam_policy_document" "worker_reads_github_actions_token" {
  statement {
    sid       = "WorkerReadsTheGitHubActionsTokenOnly"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.github_actions_read_token.arn]
  }
}

resource "aws_iam_policy" "worker_reads_github_actions_token" {
  name        = "${var.name}-worker-reads-github-actions-token"
  description = "Read of the GitHub Actions token by the execution role of the worker only (U5-NFR1)"
  policy      = data.aws_iam_policy_document.worker_reads_github_actions_token.json
}

output "github_actions_read_token_secret_arn" {
  description = "ARN of the secret github-actions-read-token, injected as MEMETRADE_GITHUB_ACTIONS_TOKEN into the worker only"
  value       = aws_secretsmanager_secret.github_actions_read_token.arn
}

output "worker_reads_github_actions_token_policy_arn" {
  description = "Policy to attach to the execution role of the worker task (never to the api nor the TxSigner)"
  value       = aws_iam_policy.worker_reads_github_actions_token.arn
}
