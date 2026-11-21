# Metrics of the applications in CloudWatch (261113-trading-real-bot Observability Setup, Q1 and Q2; closes SP-12 in
# the IaC). A CloudWatch Agent sidecar in the api+worker task and in the TxSigner task scrapes /actuator/prometheus on
# localhost and publishes, through the embedded metric format (EMF) of CloudWatch Logs, ONLY the metrics declared
# below. This list is the single source of truth: modules/services builds the agent configuration from it (the
# Prometheus `keep` filter and the EMF metric declarations), and the offline test checks that every alarm reads a
# metric of this list with one of its dimension sets, or a metric with another real source (CloudTrail metric filters,
# the load balancer).
#
# Facts checked offline with the pinned agent image (observability-setup alarms.md, "Validação"):
# - one agent configuration has ONE metric namespace, so every application metric lives in Eureka/App (the alarms of
#   U1 to U7 moved from Eureka/RealRisk, Eureka/RealExecution... to it);
# - Prometheus histograms are dropped by the agent (also when renamed by a relabel rule); counters become deltas per
#   scrape, gauges and summary quantiles pass as they are; an empty dimension set ([]) publishes the metric without
#   dimensions;
# - with honor_labels, the metric's own `job` label (the job of the worker) wins over the scrape job; a metric without
#   it gets the scrape job (api, worker, txsigner), which is the dimension of the heartbeat below.
#
# Windows (owner's decision of 2026-11-20: the agent reads every 120 s; alarms.md, section 1):
# - an alarm that treats missing data as breaching, and every stalled-job alarm, has periods that are multiples of
#   240 s, so each period always holds two reads: its window is the Prometheus window rounded up to 240 s;
# - every other alarm on Eureka/App has periods of at least 180 s, so each period holds at least one read: its window is
#   the Prometheus window, or 180 s when the Prometheus window is shorter;
# - the offline test (observability_metrics.tftest.hcl) and the *AlarmParityTest check both rules.

locals {
  app_metrics_namespace = "Eureka/App"

  # Dimension sets per group of metrics. A metric may appear in more than one entry (one CloudWatch metric per set).
  app_metric_declarations = [
    {
      # The heartbeat (scrape job) and the successful runs of the jobs of U3, U4, U5 and U7.
      dimensions = [["job"]]
      metrics = [
        "process_uptime_seconds",
        "real_risk_job_runs_total",
        "real_execution_job_runs_total",
        "real_ops_job_runs_total",
        "real_exits_job_runs_total",
      ]
    },
    {
      # Without dimensions: the label values are summed (Sum) or the worst one is taken (Maximum).
      dimensions = [[]]
      metrics = [
        "marketdata_oldest_price_age_seconds",
        "marketdata_invalid_values_total",
        "marketdata_observations_total",
        "real_risk_errors_total",
        "real_risk_reservations_expired_total",
        "real_orders_total",
        "real_orders_ambiguous_oldest_seconds",
        "real_orders_sent_oldest_seconds",
        "real_orders_ambiguous_open",
        "real_upstream_pauses_total",
        "real_divergences_open",
        "real_kill_switch_active",
        "real_reconciliation_deferred_over_2h",
        "real_pending_txsigner_oldest_seconds",
        "real_release_blocked_oldest_seconds",
        "real_conditional_cancelling_oldest_seconds",
        "real_conditional_unread_oldest_seconds",
        "real_conditional_fills_total",
        "real_conditional_cancels_total",
        "real_bot_wallets_active",
        "txsigner_trigger_refusals_total",
        "txsigner_auth_failures_total",
        "txsigner_orphan_keys",
        "txsigner_latency_seconds_max",
        "txsigner_kms_sign_seconds_max",
      ]
    },
    { dimensions = [["source"]], metrics = ["marketdata_provider_conservative_mode", "marketdata_provider_budget_ratio", "marketdata_provider_calls_total"] },
    { dimensions = [["source", "state"]], metrics = ["marketdata_source_state"] },
    { dimensions = [["source", "outcome"]], metrics = ["marketdata_provider_calls_total"] },
    { dimensions = [["kind"]], metrics = ["txsigner_kms_errors_total"] },
    { dimensions = [["outcome"]], metrics = ["txsigner_swap_decisions_total", "real_risk_evaluations_total"] },
    { dimensions = [["code"]], metrics = ["real_orders_total"] },
    { dimensions = [["provider"]], metrics = ["real_upstream_failures_total"] },
    { dimensions = [["phase"]], metrics = ["real_restore_pause_active"] },
    { dimensions = [["state"]], metrics = ["realwallet_withdrawals_total", "realwallet_withdrawal_oldest_pending_seconds"] },
    { dimensions = [["kind", "decision"]], metrics = ["txsigner_decisions_total"] },
    {
      # GAP OBS-G1 and OBS-G2 (alarms.md): the p95 of U3-NFR5.11 and U4-NFR5.13. The code publishes them as
      # histograms, which the agent drops; these are the summaries (quantile 0.95) a code change has to add. Until
      # then nothing is published under these names and their alarms stay off (var.p95_alarms_enabled).
      dimensions = [["side", "quantile"], ["mode", "quantile"]]
      metrics    = ["real_risk_evaluation_p95_seconds", "real_order_decision_to_send_p95_seconds"]
    },
  ]

  app_metric_names = sort(distinct(flatten([for declaration in local.app_metric_declarations : declaration.metrics])))

  # metric => every dimension set declared for it, each one as its sorted label names joined by commas ("" = none).
  app_metric_dimension_pairs = flatten([
    for declaration in local.app_metric_declarations : [
      for metric in declaration.metrics : [for set in declaration.dimensions : { metric = metric, set = join(",", sort(set)) }]
    ]
  ])
  app_metric_dimension_sets = {
    for metric in local.app_metric_names : metric => distinct([for pair in local.app_metric_dimension_pairs : pair.set if pair.metric == metric])
  }

  # Q2: one heartbeat per long-running JVM process (scrape job of the sidecar). process_uptime_seconds is a gauge every
  # Spring Boot process always exposes; no sample in 4 minutes (two reads of 120 s) means the process, its scrape or the
  # agent is down.
  heartbeat_jobs = {
    api      = "the api (port 8081 of the api+worker task)"
    worker   = "the worker (port 8082 of the api+worker task)"
    txsigner = "the TxSigner (port 8444 of the TxSigner task)"
  }
}

variable "p95_alarms_enabled" {
  description = "true only after the code publishes the p95 summaries of OBS-G1 and OBS-G2 (alarms.md); until then the two p95 alarms are not created"
  type        = bool
  default     = false
}

# Q2 / SP-12 / U1-NFR5.11: missing data is BREACHING here, on a metric that always exists, so a broken metrics path
# (process down, agent down, wrong IAM, endpoint refused) alarms instead of silencing every notBreaching alarm.
resource "aws_cloudwatch_metric_alarm" "metrics_absent" {
  for_each = local.heartbeat_jobs

  alarm_name          = "${var.name}-metrics-absent-${each.key}"
  alarm_description   = "Q2/SP-12: no metric of ${each.value} in CloudWatch for 4 minutes, two reads of 120 s missed (critical): process down or metrics path broken. Runbook: alarms.md, Métrica ausente."
  namespace           = local.app_metrics_namespace
  metric_name         = "process_uptime_seconds"
  dimensions          = { job = each.key }
  statistic           = "SampleCount"
  period              = 240
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]
}

# Inventory of every metric alarm of the module (offline test: each one reads an allowed metric or a metric with
# another real source). The test also compares alarm_resource_names with the resource blocks found in the .tf files,
# so an alarm added without being listed here fails the test.
locals {
  alarm_resource_names = [
    "kms_admin", "txsigner_kms_errors", "kms_keys_per_hour", "security",
    "market_source_failed", "real_price_stale", "market_call_counter_unavailable", "market_monthly_budget",
    "market_invalid_values", "market_provider_unstable",
    "real_risk_errors", "real_risk_job_stalled_daily_loss", "real_risk_job_stalled_day",
    "real_risk_job_stalled_reservation_expiry", "real_risk_reservation_expired", "real_risk_slow",
    "real_execution_ambiguous_old", "real_execution_sent_old", "real_execution_policy_refused",
    "real_execution_signing_refused", "real_execution_signer_unavailable", "real_execution_upstream_pause",
    "real_execution_job_stalled", "real_execution_slow",
    "real_ops_divergence_open", "real_ops_restore_pause", "real_ops_kill_switch_on", "real_ops_reconciliation_deferred",
    "real_ops_txsigner_command_pending", "real_ops_release_blocked", "real_ops_job_stalled",
    "real_ops_job_stalled_restore_check",
    "real_exits_cancelling_stalled", "real_exits_conditional_unread", "real_exits_trigger_policy_refused",
    "real_exits_job_stalled",
    "metrics_absent", "slo_api_availability_burn", "slo_txsigner_availability_burn", "slo_freshness_burn",
    "anomaly_real_orders", "anomaly_real_refusals",
  ]

  metric_alarms = concat(
    [
      aws_cloudwatch_metric_alarm.kms_admin, aws_cloudwatch_metric_alarm.txsigner_kms_errors,
      aws_cloudwatch_metric_alarm.kms_keys_per_hour,
      aws_cloudwatch_metric_alarm.market_source_failed, aws_cloudwatch_metric_alarm.real_price_stale,
      aws_cloudwatch_metric_alarm.market_call_counter_unavailable, aws_cloudwatch_metric_alarm.market_monthly_budget,
      aws_cloudwatch_metric_alarm.market_invalid_values, aws_cloudwatch_metric_alarm.market_provider_unstable,
      aws_cloudwatch_metric_alarm.real_risk_errors, aws_cloudwatch_metric_alarm.real_risk_job_stalled_daily_loss,
      aws_cloudwatch_metric_alarm.real_risk_job_stalled_day,
      aws_cloudwatch_metric_alarm.real_risk_job_stalled_reservation_expiry,
      aws_cloudwatch_metric_alarm.real_risk_reservation_expired,
      aws_cloudwatch_metric_alarm.real_execution_ambiguous_old, aws_cloudwatch_metric_alarm.real_execution_sent_old,
      aws_cloudwatch_metric_alarm.real_execution_policy_refused,
      aws_cloudwatch_metric_alarm.real_execution_signing_refused,
      aws_cloudwatch_metric_alarm.real_execution_signer_unavailable,
      aws_cloudwatch_metric_alarm.real_execution_upstream_pause,
      aws_cloudwatch_metric_alarm.real_ops_divergence_open, aws_cloudwatch_metric_alarm.real_ops_restore_pause,
      aws_cloudwatch_metric_alarm.real_ops_kill_switch_on, aws_cloudwatch_metric_alarm.real_ops_reconciliation_deferred,
      aws_cloudwatch_metric_alarm.real_ops_txsigner_command_pending, aws_cloudwatch_metric_alarm.real_ops_release_blocked,
      aws_cloudwatch_metric_alarm.real_ops_job_stalled_restore_check,
      aws_cloudwatch_metric_alarm.real_exits_cancelling_stalled,
      aws_cloudwatch_metric_alarm.real_exits_conditional_unread,
      aws_cloudwatch_metric_alarm.real_exits_trigger_policy_refused,
      aws_cloudwatch_metric_alarm.anomaly_real_orders, aws_cloudwatch_metric_alarm.anomaly_real_refusals,
    ],
    values(aws_cloudwatch_metric_alarm.security),
    values(aws_cloudwatch_metric_alarm.real_execution_job_stalled),
    values(aws_cloudwatch_metric_alarm.real_ops_job_stalled),
    values(aws_cloudwatch_metric_alarm.real_exits_job_stalled),
    values(aws_cloudwatch_metric_alarm.metrics_absent),
    values(aws_cloudwatch_metric_alarm.slo_api_availability_burn),
    values(aws_cloudwatch_metric_alarm.slo_txsigner_availability_burn),
    values(aws_cloudwatch_metric_alarm.slo_freshness_burn),
    aws_cloudwatch_metric_alarm.real_risk_slow,
    aws_cloudwatch_metric_alarm.real_execution_slow,
  )

  # One entry per metric read by an alarm (simple alarm or metric_query of a metric math alarm).
  alarm_metric_reads = flatten([
    for alarm in local.metric_alarms : concat(
      alarm.metric_name == null || alarm.metric_name == "" ? [] : [{
        alarm      = alarm.alarm_name
        namespace  = alarm.namespace
        metric     = alarm.metric_name
        dimensions = alarm.dimensions == null ? "" : join(",", sort(keys(alarm.dimensions)))
        period     = alarm.period
        actions    = sort(tolist(alarm.alarm_actions))
        breaching  = alarm.treat_missing_data == "breaching"
      }],
      [for query in alarm.metric_query : {
        alarm      = alarm.alarm_name
        namespace  = query.metric[0].namespace
        metric     = query.metric[0].metric_name
        dimensions = query.metric[0].dimensions == null ? "" : join(",", sort(keys(query.metric[0].dimensions)))
        period     = query.metric[0].period
        actions    = sort(tolist(alarm.alarm_actions))
        breaching  = alarm.treat_missing_data == "breaching"
      } if length(query.metric) > 0],
    )
  ])

  # Metrics that reach CloudWatch without the agent: the CloudTrail metric filters and the load balancer.
  other_metric_sources = {
    "Eureka/Kms"         = [aws_cloudwatch_log_metric_filter.kms_admin.metric_transformation[0].name, aws_cloudwatch_log_metric_filter.kms_create_key.metric_transformation[0].name]
    "Eureka/Security"    = [for filter in aws_cloudwatch_log_metric_filter.security : filter.metric_transformation[0].name]
    "AWS/ApplicationELB" = ["RequestCount", "HTTPCode_Target_5XX_Count", "HTTPCode_ELB_5XX_Count"]
  }
}

output "app_metrics_namespace" {
  description = "Namespace of every metric the CloudWatch Agent sidecars publish (modules/services)"
  value       = local.app_metrics_namespace
}

output "app_metric_declarations" {
  description = "Allow-list of the agent: metric names and their dimension sets (modules/services builds the agent configuration from it)"
  value       = local.app_metric_declarations
}

output "app_metric_names" {
  value = local.app_metric_names
}

output "app_metric_dimension_sets" {
  value = local.app_metric_dimension_sets
}

output "alarm_resource_names" {
  value = local.alarm_resource_names
}

output "alarm_metric_reads" {
  description = "Every metric an alarm reads (offline test)"
  value       = local.alarm_metric_reads
}

output "other_metric_sources" {
  value = local.other_metric_sources
}

output "alarm_names" {
  value = sort([for alarm in local.metric_alarms : alarm.alarm_name])
}
