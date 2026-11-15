# Alarms of the REAL execution (261113-trading-real-bot U4, monitoring-design.md). The same names, thresholds and windows
# as infra/observability/real-execution-alerts.yml (Prometheus, checked by promtool); RealExecutionAlarmParityTest
# compares both files. The api, the worker and the TxSigner publish their Micrometer metrics to the namespace
# Eureka/RealExecution with the dimensions job and provider only (never a mint, an address, a signature nor a person);
# how the metrics reach CloudWatch (Micrometer registry or an agent, and the VPC endpoint of the TxSigner, which has no
# internet) is the decision still pending for U1 to U4, to take before the mainnet. If it changes the namespace or the
# dimensions, this file and the parity test change together.
#
# The p95 from the decision to the send (RealExecutionSlow) has no alarm here yet: it needs the metric as a distribution
# (deferred, as the p95 of the U3). Every alarm of the U4 goes to the one SNS topic of the pilot (e-mail and SMS); the
# severity is in the description.

locals {
  real_execution_namespace = "Eureka/RealExecution"

  # U4-NFR5.14: one alarm per job of the worker, on the counter of successful runs, with missing data breaching so a
  # job that never published also alarms. The window is 1 minute for the queue, the driver and the confirmation and
  # 5 minutes for the others.
  real_execution_jobs = {
    queue            = 60
    driver           = 60
    confirmation     = 60
    ambiguous        = 300
    signing-deadline = 300
    health           = 300
    liquidation      = 300
  }
}

resource "aws_cloudwatch_metric_alarm" "real_execution_ambiguous_old" {
  alarm_name          = "${var.name}-real-execution-ambiguous-old"
  alarm_description   = "U4-NFR5.9: a REAL order is AMBIGUOUS for more than 300 seconds (critical)."
  namespace           = local.real_execution_namespace
  metric_name         = "real_orders_ambiguous_oldest_seconds"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 300
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_execution_sent_old" {
  alarm_name          = "${var.name}-real-execution-sent-old"
  alarm_description   = "U4-NFR3.5: a REAL order is SENT for more than 75 seconds (critical)."
  namespace           = local.real_execution_namespace
  metric_name         = "real_orders_sent_oldest_seconds"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 75
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_execution_policy_refused" {
  alarm_name          = "${var.name}-real-execution-policy-refused"
  alarm_description   = "U4-NFR5.10: the TxSigner refused a REAL swap against its content policy (critical)."
  namespace           = local.real_execution_namespace
  metric_name         = "txsigner_swap_decisions_total"
  dimensions          = { outcome = "REFUSED" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_execution_signing_refused" {
  alarm_name          = "${var.name}-real-execution-signing-refused"
  alarm_description   = "U4-NFR5.11: a REAL order failed with SIGNING_REFUSED (critical)."
  namespace           = local.real_execution_namespace
  metric_name         = "real_orders_total"
  dimensions          = { code = "SIGNING_REFUSED" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_execution_signer_unavailable" {
  alarm_name          = "${var.name}-real-execution-signer-unavailable"
  alarm_description   = "U4-NFR5.11: the TxSigner did not answer more than 2 times in 5 minutes (critical)."
  namespace           = local.real_execution_namespace
  metric_name         = "real_upstream_failures_total"
  dimensions          = { provider = "TXSIGNER" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_execution_upstream_pause" {
  alarm_name          = "${var.name}-real-execution-upstream-pause"
  alarm_description   = "U4-NFR5.12: a REAL wallet was paused UPSTREAM after repeated failures of a provider (high)."
  namespace           = local.real_execution_namespace
  metric_name         = "real_upstream_pauses_total"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_execution_job_stalled" {
  for_each = local.real_execution_jobs

  alarm_name          = "${var.name}-real-execution-job-stalled-${each.key}"
  alarm_description   = "U4-NFR5.14: the REAL execution job ${each.key} did not succeed in its window (critical)."
  namespace           = local.real_execution_namespace
  metric_name         = "real_execution_job_runs_total"
  dimensions          = { job = each.key }
  statistic           = "Sum"
  period              = each.value
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}
