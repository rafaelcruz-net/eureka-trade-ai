# Alarms of the real market source (261113-trading-real-bot U2, monitoring-design.md). The same names, thresholds and
# windows as infra/observability/marketdata-alerts.yml (Prometheus, checked by promtool); CoinGeckoAlarmParityTest
# compares both files. The worker publishes its Micrometer metrics to the namespace Eureka/MarketData with the
# dimension source (never the key nor a mint); how the metrics reach CloudWatch (Micrometer registry or an agent) is
# set when the worker gets its own task. High alarms page by e-mail and SMS, medium ones by e-mail: both use the SNS
# topic of the pilot until a separate e-mail-only topic exists.

locals {
  marketdata_namespace = "Eureka/MarketData"
}

resource "aws_cloudwatch_metric_alarm" "market_source_failed" {
  alarm_name          = "${var.name}-market-source-failed"
  alarm_description   = "The real market source is FAILED for more than 2 minutes (high)."
  namespace           = local.marketdata_namespace
  metric_name         = "marketdata_source_state"
  dimensions          = { source = "coingecko", state = "FAILED" }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "real_price_stale" {
  alarm_name          = "${var.name}-real-price-stale"
  alarm_description   = "The oldest price is older than 60 s for 3 rounds of 30 s; REAL refuses orders (high)."
  namespace           = local.marketdata_namespace
  metric_name         = "marketdata_oldest_price_age_seconds"
  statistic           = "Minimum"
  period              = 30
  evaluation_periods  = 3
  threshold           = 60
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "market_call_counter_unavailable" {
  alarm_name          = "${var.name}-market-call-counter-unavailable"
  alarm_description   = "D-A2: the CoinGecko call counter is unavailable for more than 5 minutes (high)."
  namespace           = local.marketdata_namespace
  metric_name         = "marketdata_provider_conservative_mode"
  dimensions          = { source = "coingecko" }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "market_monthly_budget" {
  alarm_name          = "${var.name}-market-monthly-budget"
  alarm_description   = "80% of the monthly CoinGecko call budget is used (medium)."
  namespace           = local.marketdata_namespace
  metric_name         = "marketdata_provider_budget_ratio"
  dimensions          = { source = "coingecko" }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0.8
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "market_invalid_values" {
  alarm_name          = "${var.name}-market-invalid-values"
  alarm_description   = "More than 5% of the market values read in one hour were invalid (medium)."
  evaluation_periods  = 1
  threshold           = 0.05
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  metric_query {
    id          = "ratio"
    expression  = "invalid / MAX([observations, 1])"
    label       = "Invalid values over observations"
    return_data = true
  }

  metric_query {
    id = "invalid"
    metric {
      namespace   = local.marketdata_namespace
      metric_name = "marketdata_invalid_values_total"
      period      = 3600
      stat        = "Sum"
    }
  }

  metric_query {
    id = "observations"
    metric {
      namespace   = local.marketdata_namespace
      metric_name = "marketdata_observations_total"
      period      = 3600
      stat        = "Sum"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "market_provider_unstable" {
  alarm_name          = "${var.name}-market-provider-unstable"
  alarm_description   = "D-A1: more than 10% of the CoinGecko calls were retried in one hour (medium)."
  evaluation_periods  = 1
  threshold           = 0.1
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  metric_query {
    id          = "ratio"
    expression  = "retries / MAX([calls, 1])"
    label       = "Retried calls over calls"
    return_data = true
  }

  metric_query {
    id = "retries"
    metric {
      namespace   = local.marketdata_namespace
      metric_name = "marketdata_provider_calls_total"
      dimensions  = { source = "coingecko", outcome = "retry" }
      period      = 3600
      stat        = "Sum"
    }
  }

  metric_query {
    id = "calls"
    metric {
      namespace   = local.marketdata_namespace
      metric_name = "marketdata_provider_calls_total"
      dimensions  = { source = "coingecko" }
      period      = 3600
      stat        = "Sum"
    }
  }
}
