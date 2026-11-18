# Audit and alarms of the KMS (infrastructure-specification.md "CloudTrail", NFR1.2 f): the account trail to its own
# encrypted bucket with object lock and one year of retention; an alarm on every administrative KMS action and on more
# than 10 keys created per hour.

variable "name" {
  type = string
}

variable "alarm_topic_arn" {
  type = string
}

resource "aws_s3_bucket" "trail" {
  bucket              = "${var.name}-cloudtrail"
  object_lock_enabled = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "trail" {
  bucket                  = aws_s3_bucket.trail.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_object_lock_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id
  rule {
    default_retention {
      mode = "COMPLIANCE"
      days = 365
    }
  }
}

resource "aws_cloudwatch_log_group" "trail" {
  name              = "/${var.name}/cloudtrail"
  retention_in_days = 365
}

resource "aws_cloudwatch_log_metric_filter" "kms_admin" {
  name           = "${var.name}-kms-admin-actions"
  log_group_name = aws_cloudwatch_log_group.trail.name
  pattern        = "{ ($.eventSource = \"kms.amazonaws.com\") && (($.eventName = \"PutKeyPolicy\") || ($.eventName = \"ScheduleKeyDeletion\") || ($.eventName = \"DisableKey\") || ($.eventName = \"TagResource\") || ($.eventName = \"UntagResource\") || ($.eventName = \"CreateGrant\")) }"
  metric_transformation {
    name      = "KmsAdministrativeActions"
    namespace = "Eureka/Kms"
    value     = "1"
  }
}

resource "aws_cloudwatch_log_metric_filter" "kms_create_key" {
  name           = "${var.name}-kms-create-key"
  log_group_name = aws_cloudwatch_log_group.trail.name
  pattern        = "{ ($.eventSource = \"kms.amazonaws.com\") && ($.eventName = \"CreateKey\") }"
  metric_transformation {
    name      = "KmsKeysCreated"
    namespace = "Eureka/Kms"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "kms_admin" {
  alarm_name          = "${var.name}-kms-admin-action"
  namespace           = "Eureka/Kms"
  metric_name         = "KmsAdministrativeActions"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}

# NFR5.19 (loop-back 1 of Build and Test): more than 3 KMS calls of the TxSigner throttled or failed in 5 minutes, the
# twin of the rule TxSignerKmsErrors in infra/observability/real-alerts.yml (RealOpsAlarmParityTest compares both). The
# TxSigner publishes txsigner_kms_errors_total with the dimension kind (THROTTLED, ERROR) only; how its metrics reach
# CloudWatch is the decision still pending for U1 to U4, to take before the mainnet.
locals {
  real_bot_wallet_namespace = "Eureka/RealBotWallet"
}

resource "aws_cloudwatch_metric_alarm" "txsigner_kms_errors" {
  alarm_name          = "${var.name}-tx-signer-kms-errors"
  alarm_description   = "NFR5.19: more than 3 KMS calls of the TxSigner throttled or failed in 5 minutes (high)."
  evaluation_periods  = 1
  threshold           = 3
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]

  # Each kind is its own CloudWatch metric: alarm on their sum, a missing kind counting as zero.
  metric_query {
    id          = "kms_errors"
    expression  = "FILL(throttled, 0) + FILL(failed, 0)"
    label       = "KMS calls throttled or failed"
    return_data = true
  }

  metric_query {
    id = "throttled"
    metric {
      namespace   = local.real_bot_wallet_namespace
      metric_name = "txsigner_kms_errors_total"
      dimensions  = { kind = "THROTTLED" }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id = "failed"
    metric {
      namespace   = local.real_bot_wallet_namespace
      metric_name = "txsigner_kms_errors_total"
      dimensions  = { kind = "ERROR" }
      stat        = "Sum"
      period      = 300
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "kms_keys_per_hour" {
  alarm_name          = "${var.name}-kms-keys-per-hour"
  namespace           = "Eureka/Kms"
  metric_name         = "KmsKeysCreated"
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
}
