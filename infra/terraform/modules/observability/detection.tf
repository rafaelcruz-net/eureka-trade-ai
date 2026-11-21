# Detection controls of an account that moves funds (security-posture-review.md SP-11), added by the review corrections
# of the 261113-trading-real-bot Environment Provisioning:
# - GuardDuty (CloudTrail management events, VPC flow logs and DNS, plus S3 data events and RDS login activity);
# - IAM Access Analyzer of the account (resource policies that open access outside the account);
# - EventBridge: GuardDuty findings of high severity (>= 7) and active Access Analyzer findings to the alarm topic;
# - CloudTrail metric filters on /<name>/cloudtrail, with alarms to the alarm topic, for every action that only the
#   founder (with MFA) is supposed to take on the signing path: an IAM change on the TxSigner role or on the execution
#   roles of the TxSigner and bootstrap families; a task definition revision of those families (or one that names the
#   TxSigner role); a RunTask/StartTask of those families (or one that overrides the task role with the TxSigner
#   role); an UpdateService of the txsigner service. The founder's own steps fire them too: that is the point, every
#   change of the signing path reaches the founder's e-mail, and one he did not make is an incident (P1).
# - KMS key policy and tag changes are already covered by the alarm kms-admin-action of main.tf (PutKeyPolicy,
#   TagResource, UntagResource, ScheduleKeyDeletion, DisableKey, CreateGrant).
#
# GuardDuty Runtime Monitoring of ECS Fargate stays OFF here: its agent is a sidecar pulled from an ECR repository of
# AWS, which the execution roles (own repositories only) and the endpoint policies (own account only) refuse, and the
# subnet of the TxSigner has no internet. Turning it on is a pending item (validation-report.md, pending table).

locals {
  account_id        = data.aws_caller_identity.current.account_id
  region            = data.aws_region.current.name
  txsigner_role_arn = "arn:aws:iam::${local.account_id}:role/${var.name}-txsigner"
  task_definition   = "arn:aws:ecs:${local.region}:${local.account_id}:task-definition"
  security_ns       = "Eureka/Security"

  # One filter per signal; CloudWatch Logs limits a pattern to 1024 characters (checked by the offline tests).
  security_filters = {
    founder-role-iam-change = {
      metric = "FounderOnlyRoleIamChanges"
      pattern = join(" ", [
        "{ ($.eventSource = \"iam.amazonaws.com\") && ($.readOnly IS FALSE) && (",
        "($.requestParameters.roleName = \"${var.name}-txsigner\") ||",
        "($.requestParameters.roleName = \"${var.name}-txsigner-*\") ||",
        "($.requestParameters.roleName = \"${var.name}-db-bootstrap-exec\")",
        ") }",
      ])
    }
    founder-family-registered = {
      metric = "FounderOnlyTaskDefinitionsRegistered"
      pattern = join(" ", [
        "{ ($.eventSource = \"ecs.amazonaws.com\") && ($.eventName = \"RegisterTaskDefinition\") && (",
        "($.requestParameters.family = \"${var.name}-txsigner*\") ||",
        "($.requestParameters.family = \"${var.name}-db-bootstrap\") ||",
        "($.requestParameters.taskRoleArn = \"${local.txsigner_role_arn}\") ||",
        "($.requestParameters.taskRoleArn = \"${var.name}-txsigner\")",
        ") }",
      ])
    }
    founder-family-run = {
      metric = "FounderOnlyTasksRun"
      pattern = join(" ", [
        "{ ($.eventSource = \"ecs.amazonaws.com\") && (($.eventName = \"RunTask\") || ($.eventName = \"StartTask\")) && (",
        "($.requestParameters.taskDefinition = \"${var.name}-txsigner*\") ||",
        "($.requestParameters.taskDefinition = \"${var.name}-db-bootstrap*\") ||",
        "($.requestParameters.taskDefinition = \"${local.task_definition}/${var.name}-txsigner*\") ||",
        "($.requestParameters.taskDefinition = \"${local.task_definition}/${var.name}-db-bootstrap*\") ||",
        "($.requestParameters.overrides.taskRoleArn = \"${local.txsigner_role_arn}\")",
        ") }",
      ])
    }
    txsigner-service-updated = {
      metric = "TxSignerServiceUpdates"
      pattern = join(" ", [
        "{ ($.eventSource = \"ecs.amazonaws.com\") && (($.eventName = \"UpdateService\") || ($.eventName = \"CreateService\") || ($.eventName = \"DeleteService\")) && (",
        "($.requestParameters.service = \"txsigner\") ||",
        "($.requestParameters.service = \"arn:aws:ecs:${local.region}:${local.account_id}:service/${var.name}/txsigner\") ||",
        "($.requestParameters.serviceName = \"txsigner\")",
        ") }",
      ])
    }
  }
}

resource "aws_guardduty_detector" "main" {
  # checkov:skip=CKV2_AWS_3:One detector per member account (devnet, mainnet), applied by each account's own state in the only region the SCP allows; the pilot has no delegated GuardDuty administrator nor organization auto-enable, and the management account runs no workload.
  enable                       = true
  finding_publishing_frequency = "FIFTEEN_MINUTES"
}

resource "aws_guardduty_detector_feature" "enabled" {
  for_each    = toset(["S3_DATA_EVENTS", "RDS_LOGIN_EVENTS"])
  detector_id = aws_guardduty_detector.main.id
  name        = each.key
  status      = "ENABLED"
}

# Explicitly off (see the header): no EKS, no Lambda, no EBS malware scan in this account; Fargate runtime pending.
resource "aws_guardduty_detector_feature" "disabled" {
  for_each    = toset(["RUNTIME_MONITORING", "EKS_AUDIT_LOGS", "EBS_MALWARE_PROTECTION", "LAMBDA_NETWORK_LOGS"])
  detector_id = aws_guardduty_detector.main.id
  name        = each.key
  status      = "DISABLED"
}

resource "aws_accessanalyzer_analyzer" "account" {
  analyzer_name = "${var.name}-account"
  type          = "ACCOUNT"
}

resource "aws_cloudwatch_event_rule" "guardduty_high" {
  name        = "${var.name}-guardduty-high"
  description = "GuardDuty findings of severity 7 or more (high and critical) to the alarm topic (SP-11)"
  event_pattern = jsonencode({
    source        = ["aws.guardduty"]
    "detail-type" = ["GuardDuty Finding"]
    detail        = { severity = [{ numeric = [">=", 7] }] }
  })
}

resource "aws_cloudwatch_event_target" "guardduty_high" {
  rule = aws_cloudwatch_event_rule.guardduty_high.name
  arn  = var.alarm_topic_arn
}

resource "aws_cloudwatch_event_rule" "access_analyzer_active" {
  name        = "${var.name}-access-analyzer-active"
  description = "Active IAM Access Analyzer findings (access from outside the account) to the alarm topic (SP-11)"
  event_pattern = jsonencode({
    source        = ["aws.access-analyzer"]
    "detail-type" = ["Access Analyzer Finding"]
    detail        = { status = ["ACTIVE"] }
  })
}

resource "aws_cloudwatch_event_target" "access_analyzer_active" {
  rule = aws_cloudwatch_event_rule.access_analyzer_active.name
  arn  = var.alarm_topic_arn
}

resource "aws_cloudwatch_log_metric_filter" "security" {
  for_each       = local.security_filters
  name           = "${var.name}-security-${each.key}"
  log_group_name = aws_cloudwatch_log_group.trail.name
  pattern        = each.value.pattern
  metric_transformation {
    name      = each.value.metric
    namespace = local.security_ns
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "security" {
  for_each            = local.security_filters
  alarm_name          = "${var.name}-security-${each.key}"
  alarm_description   = "SP-11: a change of the signing path (${each.key}). Expected only right after a founder step with MFA (cd-config.md 4.6); otherwise P1, kill switch REAL first."
  namespace           = local.security_ns
  metric_name         = each.value.metric
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  depends_on          = [aws_cloudwatch_log_metric_filter.security]
}
