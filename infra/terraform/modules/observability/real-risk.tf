# Alarms of the REAL risk barrier (261113-trading-real-bot U3, monitoring-design.md). The same names, thresholds and
# windows as infra/observability/real-risk-alerts.yml (Prometheus, checked by promtool); RealRiskAlarmParityTest compares
# both files. The api and the worker publish their Micrometer metrics to the namespace Eureka/RealRisk with the
# dimension job for the jobs (never a mint, an address nor a person); how the metrics reach CloudWatch (Micrometer
# registry or an agent) is the decision still pending for U1, U2 and U3, to take before the mainnet. If it changes the
# namespace or the dimensions, this file and the parity test change together.
#
# The p95 of the evaluation (RealRiskSlow) has no alarm here yet: it needs the metric as a distribution (deferred,
# monitoring-design.md). Every alarm of the U3 goes to the one SNS topic of the pilot (e-mail and SMS); the severity is
# in the description.

# U3-NFR5.12: one alarm per job of the worker, on the counter of successful runs, with missing data breaching so a job
# that never published also alarms.
locals {
  real_risk_namespace = "Eureka/RealRisk"
}

resource "aws_cloudwatch_metric_alarm" "real_risk_errors" {
  alarm_name          = "${var.name}-real-risk-errors"
  alarm_description   = "U3-NFR5.10: the REAL barrier failed closed more than 5 times in 5 minutes (critical)."
  namespace           = local.real_risk_namespace
  metric_name         = "real_risk_errors_total"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_risk_job_stalled_daily_loss" {
  alarm_name          = "${var.name}-real-risk-job-stalled-daily-loss"
  alarm_description   = "U3-NFR5.12: the job of the loss of the day did not succeed in 5 minutes (critical)."
  namespace           = local.real_risk_namespace
  metric_name         = "real_risk_job_runs_total"
  dimensions          = { job = "daily-loss" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_risk_job_stalled_day" {
  alarm_name          = "${var.name}-real-risk-job-stalled-day"
  alarm_description   = "U3-NFR5.12: the job of the day did not succeed in 15 minutes (critical)."
  namespace           = local.real_risk_namespace
  metric_name         = "real_risk_job_runs_total"
  dimensions          = { job = "day" }
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_risk_job_stalled_reservation_expiry" {
  alarm_name          = "${var.name}-real-risk-job-stalled-reservation-expiry"
  alarm_description   = "U3-NFR5.12: the expiry of the reservations did not succeed in 15 minutes (critical)."
  namespace           = local.real_risk_namespace
  metric_name         = "real_risk_job_runs_total"
  dimensions          = { job = "reservation-expiry" }
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_risk_reservation_expired" {
  alarm_name          = "${var.name}-real-risk-reservation-expired"
  alarm_description   = "U3-NFR5.9: a reservation of an approved REAL buy expired without the hand-over of the U4 (warning)."
  namespace           = local.real_risk_namespace
  metric_name         = "real_risk_reservations_expired_total"
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}
