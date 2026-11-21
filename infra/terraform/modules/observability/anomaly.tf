# Anomaly detection, warning only (261113-trading-real-bot Observability Setup, Q7; anomaly-config.md): the volume of
# REAL order events and the volume of refusals of the REAL barrier, every 6 minutes (3 reads of 120 s), against a band of 3 standard
# deviations that CloudWatch learns from the history of the metric (about 2 weeks of data before the band is useful).
# Only a volume ABOVE the band alarms: a quiet hour of the pilot is normal. Three bands of 6 minutes in a row, so one
# burst does not page. The model never blocks anything; the fixed alarms stay the control.

locals {
  anomaly_band_width = 3
}

resource "aws_cloudwatch_metric_alarm" "anomaly_real_orders" {
  alarm_name          = "${var.name}-anomaly-real-orders"
  alarm_description   = "Q7: the volume of REAL order events is above its anomaly band for 18 minutes (warning). Check a bot loop or a retry storm (dashboard Visão REAL)."
  comparison_operator = "GreaterThanUpperThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold_metric_id = "band"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  metric_query {
    id          = "band"
    expression  = "ANOMALY_DETECTION_BAND(orders, ${local.anomaly_band_width})"
    label       = "expected REAL order events"
    return_data = true
  }

  metric_query {
    id          = "orders"
    return_data = true
    metric {
      namespace   = local.app_metrics_namespace
      metric_name = "real_orders_total"
      stat        = "Sum"
      period      = 360
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "anomaly_real_refusals" {
  alarm_name          = "${var.name}-anomaly-real-refusals"
  alarm_description   = "Q7: the volume of refusals of the REAL barrier is above its anomaly band for 18 minutes (warning). Check limits, market data or a misconfigured bot (dashboard Visão REAL)."
  comparison_operator = "GreaterThanUpperThreshold"
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold_metric_id = "band"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  metric_query {
    id          = "band"
    expression  = "ANOMALY_DETECTION_BAND(refusals, ${local.anomaly_band_width})"
    label       = "expected refusals of the REAL barrier"
    return_data = true
  }

  metric_query {
    id          = "refusals"
    return_data = true
    metric {
      namespace   = local.app_metrics_namespace
      metric_name = "real_risk_evaluations_total"
      dimensions  = { outcome = "REJECTED" }
      stat        = "Sum"
      period      = 360
    }
  }
}
