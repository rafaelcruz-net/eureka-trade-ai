# Saved CloudWatch Logs Insights queries (261113-trading-real-bot Observability Setup, Q5; log-queries.md). The logs
# are JSON (logstash encoder): timestamp, level, component (logger), message, correlationId, plus the structured
# arguments of each line (for example eventType, operation and reason of the TxSigner audit). Addresses and signatures
# are masked by the encoder; no query reads a secret, a token or a full address. Retention stays 90 days
# (var.log_retention_days of modules/services, LGPD minimisation).

locals {
  log_query_folder = var.name

  log_queries = {
    "seguir-correlation-id" = {
      groups = ["api-worker", "txsigner"]
      # Replace the value before running: Logs Insights saved queries have no parameters.
      query = <<-EOT
        fields @timestamp, @logStream, level, component, message
        | filter correlationId = "COLE-AQUI-O-CORRELATION-ID"
        | sort @timestamp asc
        | limit 500
      EOT
    }
    "recusas-txsigner-por-motivo" = {
      groups = ["txsigner"]
      query  = <<-EOT
        fields @timestamp, eventType, operation, reason
        | filter eventType in ["SIGNING_REFUSED", "WALLET_CREATION_REFUSED", "POLICY_CHANGE_REFUSED", "REQUEST_MISMATCH"]
        | stats count(*) as recusas by eventType, operation, reason
        | sort recusas desc
      EOT
    }
    "erros-5xx-por-componente" = {
      groups = ["api-worker"]
      # The api does not log the route of a 5xx (gap OBS-G3, log-queries.md): grouped by component and message; the
      # route comes from the load balancer metrics of the dashboard Serviços.
      query = <<-EOT
        fields @timestamp, component, message, correlationId
        | filter level = "ERROR" and component like /ProblemDetailsHandler|Errors$/
        | stats count(*) as erros by component, message
        | sort erros desc
      EOT
    }
    "ordens-real-ambiguas" = {
      groups = ["api-worker"]
      query  = <<-EOT
        fields @timestamp, level, component, message, correlationId
        | filter message like /(?i)ambiguous/
        | sort @timestamp desc
        | limit 200
      EOT
    }
    "falhas-dos-trabalhos" = {
      groups = ["api-worker", "txsigner"]
      query  = <<-EOT
        fields @timestamp, level, component, message
        | filter level in ["ERROR", "WARN"] and component like /(Job|Jobs|Schedule|Driver|Consumer|Watcher)$/
        | stats count(*) as falhas, latest(message) as ultima by component, bin(1h)
        | sort falhas desc
      EOT
    }
  }
}

resource "aws_cloudwatch_query_definition" "saved" {
  for_each        = local.log_queries
  name            = "${local.log_query_folder}/${each.key}"
  log_group_names = [for group in each.value.groups : var.app_log_group_names[group]]
  query_string    = trimspace(each.value.query)
}

output "log_query_names" {
  value = sort([for query in aws_cloudwatch_query_definition.saved : query.name])
}
