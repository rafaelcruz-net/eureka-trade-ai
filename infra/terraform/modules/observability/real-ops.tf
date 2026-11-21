# Alarms of the operations of the REAL (261113-trading-real-bot U5, monitoring-design.md). The same names, thresholds
# and windows as infra/observability/real-ops-alerts.yml (Prometheus, checked by promtool); RealOpsAlarmParityTest
# compares both files. The CloudWatch Agent sidecar of the api+worker task publishes the Micrometer metrics of the worker
# and the api to the namespace Eureka/App with the dimensions job and phase only (never a mint, an address, a signature
# nor a person): Observability Setup Q1, allow-list in app-metrics.tf. If the namespace or the dimensions change, this
# file, the allow-list and the parity test change together. Every alarm goes to the one SNS topic of the pilot; the severity is in
# the description.

locals {
  real_ops_namespace = "Eureka/App"

  # U5-NFR5.13: one alarm per job on the counter of successful runs, with missing data breaching so a job that never
  # published also alarms. The window is period x evaluations; the purge looks at 26 hours as 26 periods of 1 hour,
  # every one of them without a run. The restore check has its own metric math alarm below.
  real_ops_jobs = {
    gates              = { period = 720, evaluations = 1 }
    reconciliation     = { period = 1920, evaluations = 1 }
    kill-switch-cancel = { period = 240, evaluations = 1 }
    txsigner-resend    = { period = 240, evaluations = 1 }
    ops-gauges         = { period = 240, evaluations = 1 }
    purge              = { period = 3600, evaluations = 26 }
  }
}

resource "aws_cloudwatch_metric_alarm" "real_ops_divergence_open" {
  alarm_name          = "${var.name}-real-ops-divergence-open"
  alarm_description   = "U5-NFR5.7: a REAL bot wallet has an open divergence (critical)."
  namespace           = local.real_ops_namespace
  metric_name         = "real_divergences_open"
  statistic           = "Maximum"
  period              = 180
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_ops_restore_pause" {
  alarm_name          = "${var.name}-real-ops-restore-pause"
  alarm_description   = "U5-NFR5.8: the REAL of everybody is paused by a restore (critical)."
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  # The metric carries the dimension phase, so each phase is its own CloudWatch metric: alarm on the larger one.
  metric_query {
    id          = "paused"
    expression  = "MAX([restore, check_pending])"
    label       = "restore pause in any phase"
    return_data = true
  }

  metric_query {
    id = "restore"
    metric {
      namespace   = local.real_ops_namespace
      metric_name = "real_restore_pause_active"
      dimensions  = { phase = "RESTORE" }
      stat        = "Maximum"
      period      = 180
    }
  }

  metric_query {
    id = "check_pending"
    metric {
      namespace   = local.real_ops_namespace
      metric_name = "real_restore_pause_active"
      dimensions  = { phase = "CHECK_PENDING" }
      stat        = "Maximum"
      period      = 180
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "real_ops_kill_switch_on" {
  alarm_name          = "${var.name}-real-ops-kill-switch-on"
  alarm_description   = "U5-NFR5.9: the kill switch REAL is on for 30 minutes (high)."
  namespace           = local.real_ops_namespace
  metric_name         = "real_kill_switch_active"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 6
  datapoints_to_alarm = 6
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_ops_reconciliation_deferred" {
  alarm_name          = "${var.name}-real-ops-reconciliation-deferred"
  alarm_description   = "U5-NFR5.10: a REAL bot wallet without a measured reconciliation for more than 2 hours (high)."
  namespace           = local.real_ops_namespace
  metric_name         = "real_reconciliation_deferred_over_2h"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_ops_txsigner_command_pending" {
  alarm_name          = "${var.name}-real-ops-txsigner-command-pending"
  alarm_description   = "U5-NFR5.11: a command to the TxSigner unconfirmed for more than 900 seconds (high)."
  namespace           = local.real_ops_namespace
  metric_name         = "real_pending_txsigner_oldest_seconds"
  statistic           = "Maximum"
  period              = 180
  evaluation_periods  = 1
  threshold           = 900
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_ops_release_blocked" {
  alarm_name          = "${var.name}-real-ops-release-blocked"
  alarm_description   = "U5-NFR5.12: a REAL release BLOCKED for more than 3600 seconds (warning)."
  namespace           = local.real_ops_namespace
  metric_name         = "real_release_blocked_oldest_seconds"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 3600
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_ops_job_stalled" {
  for_each = local.real_ops_jobs

  alarm_name          = "${var.name}-real-ops-job-stalled-${each.key}"
  alarm_description   = "U5-NFR5.13: the REAL operations job ${each.key} did not succeed in its window (critical)."
  namespace           = local.real_ops_namespace
  metric_name         = "real_ops_job_runs_total"
  dimensions          = { job = each.key }
  statistic           = "Sum"
  period              = each.value.period
  evaluation_periods  = each.value.evaluations
  datapoints_to_alarm = each.value.evaluations
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}

# U5-NFR5.13: the restore check alarms only while a check is pending: IF(pending > 0 AND runs < 1, 1, 0) >= 1. Missing
# runs count as zero (FILL), and without a pending check the expression is 0.
resource "aws_cloudwatch_metric_alarm" "real_ops_job_stalled_restore_check" {
  alarm_name          = "${var.name}-real-ops-job-stalled-restore-check"
  alarm_description   = "U5-NFR5.13: the restore check did not run in 3 minutes while a check is pending (critical)."
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  metric_query {
    id          = "stalled"
    expression  = "IF(pending > 0 AND FILL(runs, 0) < 1, 1, 0)"
    label       = "restore-check stalled while CHECK_PENDING"
    return_data = true
  }

  metric_query {
    id = "pending"
    metric {
      namespace   = local.real_ops_namespace
      metric_name = "real_restore_pause_active"
      dimensions  = { phase = "CHECK_PENDING" }
      stat        = "Maximum"
      period      = 240
    }
  }

  metric_query {
    id = "runs"
    metric {
      namespace   = local.real_ops_namespace
      metric_name = "real_ops_job_runs_total"
      dimensions  = { job = "restore-check" }
      stat        = "Sum"
      period      = 240
    }
  }
}
