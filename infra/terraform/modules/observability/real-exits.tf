# Alarms of the conditional exits of the REAL (261113-trading-real-bot U7, monitoring-design.md). The same names,
# thresholds and windows as infra/observability/real-exits-alerts.yml (Prometheus, checked by promtool);
# RealExitsAlarmParityTest compares both files. The worker, the api and the TxSigner publish their Micrometer metrics to
# the namespace Eureka/RealExits with the dimension job only (never a mint, an address, a signature nor a person); how
# the metrics reach CloudWatch is the decision still pending for U1 to U7, to take before the mainnet. If it changes the
# namespace or the dimensions, this file and the parity test change together. Every alarm goes to the one SNS topic of
# the pilot; the severity is in the description.

locals {
  real_exits_namespace = "Eureka/RealExits"

  # U7-NFR5.10: one alarm per job on the counter of successful runs, with missing data breaching so a job that never
  # published also alarms. The window is period x evaluations; the purge looks at 26 hours as 26 periods of 1 hour.
  real_exits_jobs = {
    read         = { period = 120, evaluations = 1 }
    cancel       = { period = 180, evaluations = 1 }
    exits-gauges = { period = 120, evaluations = 1 }
    purge        = { period = 3600, evaluations = 26 }
  }
}

resource "aws_cloudwatch_metric_alarm" "real_exits_cancelling_stalled" {
  alarm_name          = "${var.name}-real-exits-cancelling-stalled"
  alarm_description   = "U7-NFR5.7: an automatic cancellation of a conditional stalled for 15 minutes (critical)."
  namespace           = local.real_exits_namespace
  metric_name         = "real_conditional_cancelling_oldest_seconds"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 900
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_exits_conditional_unread" {
  alarm_name          = "${var.name}-real-exits-conditional-unread"
  alarm_description   = "U7-NFR5.8: an OPEN conditional without a successful read for 2 hours (high)."
  namespace           = local.real_exits_namespace
  metric_name         = "real_conditional_unread_oldest_seconds"
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 7200
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_exits_trigger_policy_refused" {
  alarm_name          = "${var.name}-real-exits-trigger-policy-refused"
  alarm_description   = "U7-NFR5.9: the TxSigner refused a trigger operation; the server is out of the policy (high)."
  namespace           = local.real_exits_namespace
  metric_name         = "txsigner_trigger_refusals_total"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_exits_job_stalled" {
  for_each = local.real_exits_jobs

  alarm_name          = "${var.name}-real-exits-job-stalled-${each.key}"
  alarm_description   = "U7-NFR5.10: the REAL exits job ${each.key} did not succeed in its window (critical)."
  namespace           = local.real_exits_namespace
  metric_name         = "real_exits_job_runs_total"
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
