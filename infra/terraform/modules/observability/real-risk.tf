# Alarms of the REAL risk barrier (261113-trading-real-bot U3, monitoring-design.md). The same names, thresholds and
# windows as infra/observability/real-risk-alerts.yml (Prometheus, checked by promtool); RealRiskAlarmParityTest compares
# both files. The CloudWatch Agent sidecar of the api+worker task publishes the Micrometer metrics of the api and the
# worker to the namespace Eureka/App with the dimension job for the jobs (never a mint, an address nor a person):
# Observability Setup Q1, allow-list in app-metrics.tf. If the namespace or the dimensions change, this file, the
# allow-list and the parity test change together.
#
# The p95 of the evaluation (RealRiskSlow, U3-NFR5.11) is below, created only with var.p95_alarms_enabled: the code
# publishes the time of the evaluation as a histogram, which the agent drops, so the alarm reads the summary of gap
# OBS-G1 (alarms.md) that a code change has to add. Every alarm of the U3 goes to the one SNS topic of the pilot (e-mail
# and SMS); the severity is in the description.

# U3-NFR5.12: one alarm per job of the worker, on the counter of successful runs, with missing data breaching so a job
# that never published also alarms.
locals {
  real_risk_namespace = "Eureka/App"
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
  period              = 480
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
  period              = 960
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
  period              = 960
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

# U3-NFR5.11 (RealRiskSlow, Q3): p95 of the evaluation of a REAL buy above 150 ms for 10 minutes (warning). Reads the
# quantile 0.95 of the summary real_risk_evaluation_p95_seconds{side,quantile} (gap OBS-G1: not published yet), the
# worst value of each 5 minutes, twice in a row. Off until the code publishes it (var.p95_alarms_enabled).
resource "aws_cloudwatch_metric_alarm" "real_risk_slow" {
  count = var.p95_alarms_enabled ? 1 : 0

  alarm_name          = "${var.name}-real-risk-slow"
  alarm_description   = "U3-NFR5.11: the p95 of the REAL buy evaluation is above 150 ms for 10 minutes (warning, informative target)."
  namespace           = local.real_risk_namespace
  metric_name         = "real_risk_evaluation_p95_seconds"
  dimensions          = { side = "BUY", quantile = "0.95" }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  threshold           = 0.15
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}
