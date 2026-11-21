# Three CloudWatch dashboards (261113-trading-real-bot Observability Setup, Q4; dashboards.md): Visão REAL, Serviços and
# Segurança. Application metrics come from the agent allow-list (Eureka/App, app-metrics.tf); the rest from AWS
# namespaces (load balancer, ECS Container Insights, RDS, KMS usage, EventBridge) and the CloudTrail metric filters.
# Nothing here carries a mint, an address or a person: the dimensions are the labels of the allow-list.

variable "app_log_group_names" {
  description = "Log groups of the services (modules/services): txsigner, api-worker, web, admin, migrate, bootstrap"
  type        = map(string)
}

locals {
  ns = local.app_metrics_namespace

  # Widget helpers: a metric line of Eureka/App, optionally with one dimension.
  app_metric = { for metric in local.app_metric_names : metric => [local.ns, metric] }

  real_alarm_arns = concat(
    [
      aws_cloudwatch_metric_alarm.real_ops_divergence_open.arn, aws_cloudwatch_metric_alarm.real_ops_kill_switch_on.arn,
      aws_cloudwatch_metric_alarm.real_ops_restore_pause.arn, aws_cloudwatch_metric_alarm.real_execution_ambiguous_old.arn,
      aws_cloudwatch_metric_alarm.real_execution_sent_old.arn, aws_cloudwatch_metric_alarm.real_execution_signing_refused.arn,
      aws_cloudwatch_metric_alarm.real_execution_signer_unavailable.arn, aws_cloudwatch_metric_alarm.real_risk_errors.arn,
      aws_cloudwatch_metric_alarm.real_exits_cancelling_stalled.arn, aws_cloudwatch_metric_alarm.real_price_stale.arn,
      aws_cloudwatch_metric_alarm.anomaly_real_orders.arn, aws_cloudwatch_metric_alarm.anomaly_real_refusals.arn,
    ],
    [for alarm in aws_cloudwatch_metric_alarm.slo_freshness_burn : alarm.arn],
  )

  service_alarm_arns = concat(
    [for alarm in aws_cloudwatch_metric_alarm.metrics_absent : alarm.arn],
    [for alarm in aws_cloudwatch_metric_alarm.slo_api_availability_burn : alarm.arn],
    [for alarm in aws_cloudwatch_metric_alarm.slo_txsigner_availability_burn : alarm.arn],
  )

  security_alarm_arns = concat(
    [for alarm in aws_cloudwatch_metric_alarm.security : alarm.arn],
    [
      aws_cloudwatch_metric_alarm.kms_admin.arn, aws_cloudwatch_metric_alarm.kms_keys_per_hour.arn,
      aws_cloudwatch_metric_alarm.txsigner_kms_errors.arn, aws_cloudwatch_metric_alarm.real_execution_policy_refused.arn,
      aws_cloudwatch_metric_alarm.real_exits_trigger_policy_refused.arn,
    ],
  )

  alb_dimensions = ["LoadBalancer", var.api_load_balancer_arn_suffix, "TargetGroup", var.api_target_group_arn_suffix]

  real_dashboard = {
    widgets = [
      { type = "text", x = 0, y = 0, width = 24, height = 2, properties = { markdown = "## Visão REAL (${var.name})\nOrdens, recusas, carteiras, saques, divergências, kill switch e saídas condicionais. Fonte: Eureka/App (sidecar do CloudWatch Agent). Runbooks: docs/runbooks/real-execution.md e alarms.md." } },
      { type = "alarm", x = 0, y = 2, width = 24, height = 3, properties = { title = "Alarmes do REAL", alarms = local.real_alarm_arns } },
      {
        type = "metric", x = 0, y = 5, width = 12, height = 6
        properties = {
          title = "Ordens REAL (eventos por 5 min) e SIGNING_REFUSED", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            concat(local.app_metric["real_orders_total"], [{ label = "eventos de ordem" }]),
            [local.ns, "real_orders_total", "code", "SIGNING_REFUSED", { label = "SIGNING_REFUSED" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 5, width = 12, height = 6
        properties = {
          title = "Barreira REAL: aprovadas e recusadas (5 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            [local.ns, "real_risk_evaluations_total", "outcome", "APPROVED", { label = "aprovadas" }],
            [local.ns, "real_risk_evaluations_total", "outcome", "REJECTED", { label = "recusadas" }],
            concat(local.app_metric["real_risk_errors_total"], [{ label = "falhas (fechou)" }]),
          ]
        }
      },
      {
        type = "metric", x = 0, y = 11, width = 6, height = 6
        properties = {
          title   = "Carteiras do bot ativas", region = local.region, view = "singleValue", stat = "Maximum", period = 300
          metrics = [concat(local.app_metric["real_bot_wallets_active"], [{ label = "ativas" }])]
        }
      },
      {
        type = "metric", x = 6, y = 11, width = 9, height = 6
        properties = {
          title   = "Saques por estado (5 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [[{ expression = "SEARCH('{${local.ns},state} MetricName=\"realwallet_withdrawals_total\"', 'Sum', 360)", id = "withdrawals", label = "" }]]
        }
      },
      {
        type = "metric", x = 15, y = 11, width = 9, height = 6
        properties = {
          title       = "Saque pendente mais antigo por estado (s)", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics     = [[{ expression = "SEARCH('{${local.ns},state} MetricName=\"realwallet_withdrawal_oldest_pending_seconds\"', 'Maximum', 240)", id = "pending", label = "" }]]
          annotations = { horizontal = [{ label = "AMBIGUOUS 300 s", value = 300 }, { label = "parado 600 s", value = 600 }] }
        }
      },
      {
        type = "metric", x = 0, y = 17, width = 8, height = 6
        properties = {
          title = "Divergências abertas e reconciliação adiada", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics = [
            concat(local.app_metric["real_divergences_open"], [{ label = "divergências abertas" }]),
            concat(local.app_metric["real_reconciliation_deferred_over_2h"], [{ label = "sem reconciliação > 2 h" }]),
          ]
        }
      },
      {
        type = "metric", x = 8, y = 17, width = 8, height = 6
        properties = {
          title = "Kill switch REAL e pausa de restauração", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics = [
            concat(local.app_metric["real_kill_switch_active"], [{ label = "kill switch" }]),
            [local.ns, "real_restore_pause_active", "phase", "RESTORE", { label = "restauração" }],
            [local.ns, "real_restore_pause_active", "phase", "CHECK_PENDING", { label = "conferência pendente" }],
          ]
        }
      },
      {
        type = "metric", x = 16, y = 17, width = 8, height = 6
        properties = {
          title = "Ordens AMBIGUOUS e SENT mais antigas (s)", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics = [
            concat(local.app_metric["real_orders_ambiguous_oldest_seconds"], [{ label = "AMBIGUOUS mais antiga" }]),
            concat(local.app_metric["real_orders_sent_oldest_seconds"], [{ label = "SENT mais antiga" }]),
            concat(local.app_metric["real_orders_ambiguous_open"], [{ label = "AMBIGUOUS abertas", yAxis = "right" }]),
          ]
          annotations = { horizontal = [{ label = "SENT 75 s", value = 75 }, { label = "AMBIGUOUS 300 s", value = 300 }] }
        }
      },
      {
        type = "metric", x = 0, y = 23, width = 12, height = 6
        properties = {
          title = "Saídas condicionais: execuções e cancelamentos (5 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            concat(local.app_metric["real_conditional_fills_total"], [{ label = "executadas" }]),
            concat(local.app_metric["real_conditional_cancels_total"], [{ label = "canceladas" }]),
          ]
        }
      },
      {
        type = "metric", x = 12, y = 23, width = 12, height = 6
        properties = {
          title = "Saídas condicionais: cancelamento parado e leitura atrasada (s)", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics = [
            concat(local.app_metric["real_conditional_cancelling_oldest_seconds"], [{ label = "cancelando há" }]),
            concat(local.app_metric["real_conditional_unread_oldest_seconds"], [{ label = "sem leitura há" }]),
          ]
        }
      },
      {
        type = "metric", x = 0, y = 29, width = 24, height = 6
        properties = {
          title       = "Frescor do preço mais velho (s) — SLO 99% dos minutos <= 60 s", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics     = [concat(local.app_metric["marketdata_oldest_price_age_seconds"], [{ label = "idade do preço mais velho" }])]
          annotations = { horizontal = [{ label = "60 s", value = 60 }] }
        }
      },
    ]
  }

  services_dashboard = {
    widgets = [
      { type = "text", x = 0, y = 0, width = 24, height = 2, properties = { markdown = "## Serviços (${var.name})\nSinais de ouro do api pelo balanceador, batimento dos processos, ECS, RDS e TxSigner. SLO de disponibilidade 99,5% em 30 dias (slo-config.md)." } },
      { type = "alarm", x = 0, y = 2, width = 24, height = 3, properties = { title = "Batimento e consumo do orçamento de erro", alarms = local.service_alarm_arns } },
      {
        type = "metric", x = 0, y = 5, width = 8, height = 6
        properties = {
          title   = "api: tráfego (pedidos por minuto)", region = local.region, view = "timeSeries", stat = "Sum", period = 60
          metrics = [concat(["AWS/ApplicationELB", "RequestCount"], local.alb_dimensions, [{ label = "pedidos" }])]
        }
      },
      {
        type = "metric", x = 8, y = 5, width = 8, height = 6
        properties = {
          title = "api: latência no balanceador (s)", region = local.region, view = "timeSeries", period = 60
          metrics = [
            concat(["AWS/ApplicationELB", "TargetResponseTime"], local.alb_dimensions, [{ stat = "p50", label = "p50" }]),
            concat(["AWS/ApplicationELB", "TargetResponseTime"], local.alb_dimensions, [{ stat = "p95", label = "p95" }]),
            concat(["AWS/ApplicationELB", "TargetResponseTime"], local.alb_dimensions, [{ stat = "p99", label = "p99" }]),
          ]
        }
      },
      {
        type = "metric", x = 16, y = 5, width = 8, height = 6
        properties = {
          title = "api: erros 5xx (alvo e balanceador)", region = local.region, view = "timeSeries", stat = "Sum", period = 60
          metrics = [
            concat(["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count"], local.alb_dimensions, [{ label = "5xx do api" }]),
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.api_load_balancer_arn_suffix, { label = "5xx do balanceador" }],
            concat(["AWS/ApplicationELB", "UnHealthyHostCount"], local.alb_dimensions, [{ stat = "Maximum", label = "alvos não saudáveis", yAxis = "right" }]),
          ]
        }
      },
      {
        type = "metric", x = 0, y = 11, width = 8, height = 6
        properties = {
          title   = "Batimento dos processos (leituras a cada 4 min; 0 = sem métricas)", region = local.region, view = "timeSeries", stat = "SampleCount", period = 240
          metrics = [for job in keys(local.heartbeat_jobs) : [local.ns, "process_uptime_seconds", "job", job, { label = job }]]
        }
      },
      {
        type = "metric", x = 8, y = 11, width = 8, height = 6
        properties = {
          title   = "ECS: CPU por serviço (unidades)", region = local.region, view = "timeSeries", stat = "Average", period = 60
          metrics = [for service in ["api-worker", "txsigner", "web", "admin"] : ["ECS/ContainerInsights", "CpuUtilized", "ClusterName", var.name, "ServiceName", service, { label = service }]]
        }
      },
      {
        type = "metric", x = 16, y = 11, width = 8, height = 6
        properties = {
          title = "ECS: memória por serviço (MB) e tarefas rodando", region = local.region, view = "timeSeries", stat = "Average", period = 60
          metrics = concat(
            [for service in ["api-worker", "txsigner", "web", "admin"] : ["ECS/ContainerInsights", "MemoryUtilized", "ClusterName", var.name, "ServiceName", service, { label = service }]],
            [for service in ["api-worker", "txsigner"] : ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.name, "ServiceName", service, { label = "${service} tarefas", stat = "Minimum", yAxis = "right" }]],
          )
        }
      },
      {
        type = "metric", x = 0, y = 17, width = 8, height = 6
        properties = {
          title   = "RDS: CPU (%)", region = local.region, view = "timeSeries", stat = "Average", period = 300
          metrics = [for db in ["app", "txsigner"] : ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", "${var.name}-${db}", { label = db }]]
        }
      },
      {
        type = "metric", x = 8, y = 17, width = 8, height = 6
        properties = {
          title   = "RDS: conexões", region = local.region, view = "timeSeries", stat = "Maximum", period = 300
          metrics = [for db in ["app", "txsigner"] : ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", "${var.name}-${db}", { label = db }]]
        }
      },
      {
        type = "metric", x = 16, y = 17, width = 8, height = 6
        properties = {
          title   = "RDS: espaço livre (bytes)", region = local.region, view = "timeSeries", stat = "Minimum", period = 300
          metrics = [for db in ["app", "txsigner"] : ["AWS/RDS", "FreeStorageSpace", "DBInstanceIdentifier", "${var.name}-${db}", { label = db }]]
        }
      },
      {
        type = "metric", x = 0, y = 23, width = 12, height = 6
        properties = {
          title = "TxSigner: pior latência de assinatura e do KMS Sign (s)", region = local.region, view = "timeSeries", stat = "Maximum", period = 240
          metrics = [
            concat(local.app_metric["txsigner_latency_seconds_max"], [{ label = "pedido de assinatura (máximo)" }]),
            concat(local.app_metric["txsigner_kms_sign_seconds_max"], [{ label = "KMS Sign (máximo)" }]),
          ]
          annotations = { horizontal = [{ label = "1 s", value = 1 }] }
        }
      },
      {
        type = "metric", x = 12, y = 23, width = 12, height = 6
        properties = {
          title = "Falhas de fornecedores (5 min) e pausas UPSTREAM", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = concat(
            [for provider in ["TXSIGNER", "RPC", "JUPITER"] : [local.ns, "real_upstream_failures_total", "provider", provider, { label = provider }]],
            [concat(local.app_metric["real_upstream_pauses_total"], [{ label = "pausas UPSTREAM", yAxis = "right" }])],
          )
        }
      },
      {
        type = "metric", x = 0, y = 29, width = 24, height = 6
        properties = {
          title   = "Execuções com sucesso dos trabalhos (por 5 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [[{ expression = "SEARCH('{${local.ns},job} MetricName=~\"real_(risk|execution|ops|exits)_job_runs_total\"', 'Sum', 360)", id = "jobs", label = "" }]]
        }
      },
    ]
  }

  security_dashboard = {
    widgets = [
      { type = "text", x = 0, y = 0, width = 24, height = 2, properties = { markdown = "## Segurança (${var.name})\nKMS, recusas da política do TxSigner, credenciais, GuardDuty, Access Analyzer e os alarmes `-security-*` (SP-11). Toda mudança do caminho de assinatura que o fundador não fez é P1: kill switch REAL primeiro." } },
      { type = "alarm", x = 0, y = 2, width = 24, height = 3, properties = { title = "Alarmes de segurança", alarms = local.security_alarm_arns } },
      {
        type = "metric", x = 0, y = 5, width = 8, height = 6
        properties = {
          title = "KMS: ações administrativas e chaves criadas (CloudTrail)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            ["Eureka/Kms", "KmsAdministrativeActions", { label = "ações administrativas" }],
            ["Eureka/Kms", "KmsKeysCreated", { label = "chaves criadas" }],
          ]
        }
      },
      {
        type = "metric", x = 8, y = 5, width = 8, height = 6
        properties = {
          title = "KMS: erros do TxSigner e chamadas de Sign da conta", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            [local.ns, "txsigner_kms_errors_total", "kind", "THROTTLED", { label = "THROTTLED" }],
            [local.ns, "txsigner_kms_errors_total", "kind", "ERROR", { label = "ERROR" }],
            ["AWS/Usage", "CallCount", "Service", "KMS", "Type", "API", "Resource", "Sign", "Class", "None", { label = "Sign (uso da conta)", yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 16, y = 5, width = 8, height = 6
        properties = {
          title = "TxSigner: chaves órfãs e falhas de credencial", region = local.region, view = "timeSeries", period = 300
          metrics = [
            concat(local.app_metric["txsigner_orphan_keys"], [{ stat = "Maximum", label = "chaves órfãs" }]),
            concat(local.app_metric["txsigner_auth_failures_total"], [{ stat = "Sum", label = "401/403" }]),
          ]
        }
      },
      {
        type = "metric", x = 0, y = 11, width = 12, height = 6
        properties = {
          title = "TxSigner: recusas da política por tipo de operação (5 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            [{ expression = "SEARCH('{${local.ns},kind,decision} MetricName=\"txsigner_decisions_total\" decision=\"REFUSED\"', 'Sum', 360)", id = "refused", label = "" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 11, width = 12, height = 6
        properties = {
          title = "TxSigner: swaps recusados e operações do Trigger recusadas (5 min)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [
            [local.ns, "txsigner_swap_decisions_total", "outcome", "REFUSED", { label = "swaps recusados" }],
            concat(local.app_metric["txsigner_trigger_refusals_total"], [{ label = "Trigger recusadas" }]),
          ]
        }
      },
      {
        type = "metric", x = 0, y = 17, width = 12, height = 6
        properties = {
          title = "GuardDuty (severidade >= 7) e Access Analyzer: eventos encaminhados", region = local.region, view = "timeSeries", stat = "Sum", period = 3600
          metrics = [
            ["AWS/Events", "MatchedEvents", "RuleName", aws_cloudwatch_event_rule.guardduty_high.name, { label = "GuardDuty alto/crítico" }],
            ["AWS/Events", "MatchedEvents", "RuleName", aws_cloudwatch_event_rule.access_analyzer_active.name, { label = "Access Analyzer ativo" }],
            ["AWS/Events", "FailedInvocations", "RuleName", aws_cloudwatch_event_rule.guardduty_high.name, { label = "falha ao avisar (GuardDuty)", yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 17, width = 12, height = 6
        properties = {
          title   = "Mudanças do caminho de assinatura (CloudTrail, SP-11)", region = local.region, view = "timeSeries", stat = "Sum", period = 300
          metrics = [for key, filter in local.security_filters : [local.security_ns, filter.metric, { label = key }]]
        }
      },
    ]
  }
}

resource "aws_cloudwatch_dashboard" "real" {
  dashboard_name = "${var.name}-visao-real"
  dashboard_body = jsonencode(local.real_dashboard)
}

resource "aws_cloudwatch_dashboard" "services" {
  dashboard_name = "${var.name}-servicos"
  dashboard_body = jsonencode(local.services_dashboard)
}

resource "aws_cloudwatch_dashboard" "security" {
  dashboard_name = "${var.name}-seguranca"
  dashboard_body = jsonencode(local.security_dashboard)
}

output "dashboard_names" {
  value = [aws_cloudwatch_dashboard.real.dashboard_name, aws_cloudwatch_dashboard.services.dashboard_name, aws_cloudwatch_dashboard.security.dashboard_name]
}

# Every Eureka/App metric a dashboard draws (offline test: all of them are in the allow-list).
output "dashboard_app_metrics" {
  value = sort(distinct(flatten([
    for dashboard in [local.real_dashboard, local.services_dashboard, local.security_dashboard] : [
      for widget in dashboard.widgets : [
        for line in try(widget.properties.metrics, []) : line[1] if try(line[0], "") == local.ns
      ]
    ]
  ])))
}
