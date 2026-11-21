# Offline checks of the detection controls and of the trail bucket (security-posture-review.md SP-08, SP-11;
# Environment Provisioning of 261113-trading-real-bot). mock_provider: no AWS credential, account nor network call.
# Run from infra/terraform: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_region" {
    defaults = { name = "sa-east-1" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "111111111111" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:sa-east-1:111111111111:log-group:/eureka-real/cloudtrail" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111111111111:role/eureka-real-cloudtrail-logs" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::eureka-real-cloudtrail-111111111111", id = "eureka-real-cloudtrail-111111111111" }
  }
}

variables {
  name                         = "eureka-real"
  alarm_topic_arn              = "arn:aws:sns:sa-east-1:111111111111:eureka-real-alarms"
  api_load_balancer_arn_suffix = "app/eureka-real/1"
  api_target_group_arn_suffix  = "targetgroup/eureka-real-api/1"
  app_log_group_names = {
    txsigner   = "/ecs/eureka-real/txsigner"
    api-worker = "/ecs/eureka-real/api-worker"
    web        = "/ecs/eureka-real/web"
    admin      = "/ecs/eureka-real/admin"
    migrate    = "/ecs/eureka-real/migrate"
    bootstrap  = "/ecs/eureka-real/bootstrap"
  }
}

run "detection_and_trail" {
  command = apply
  module {
    source = "./modules/observability"
  }

  # SP-08: the trail bucket uses SSE-S3 (AES256).
  assert {
    condition     = [for rule in aws_s3_bucket_server_side_encryption_configuration.trail.rule : rule.apply_server_side_encryption_by_default[0].sse_algorithm][0] == "AES256"
    error_message = "the trail bucket is not SSE-S3"
  }
  # SP-11: GuardDuty on, runtime monitoring explicitly off (pending), Access Analyzer of the account.
  assert {
    condition     = aws_guardduty_detector.main.enable
    error_message = "GuardDuty is off"
  }
  assert {
    condition     = aws_guardduty_detector_feature.disabled["RUNTIME_MONITORING"].status == "DISABLED"
    error_message = "runtime monitoring must stay off until its agent can be pulled (pending item)"
  }
  assert {
    condition     = aws_accessanalyzer_analyzer.account.type == "ACCOUNT"
    error_message = "no account Access Analyzer"
  }
  # Findings to the alarm topic.
  assert {
    condition     = aws_cloudwatch_event_target.guardduty_high.arn == var.alarm_topic_arn && aws_cloudwatch_event_target.access_analyzer_active.arn == var.alarm_topic_arn
    error_message = "the findings do not reach the alarm topic"
  }
  assert {
    condition     = jsondecode(aws_cloudwatch_event_rule.guardduty_high.event_pattern).detail.severity[0].numeric == [">=", 7]
    error_message = "the GuardDuty rule is not severity >= 7"
  }
  # Four CloudTrail alarms on the signing path, all to the topic, every pattern within the 1024 characters limit.
  assert {
    condition     = toset(keys(aws_cloudwatch_metric_alarm.security)) == toset(["founder-role-iam-change", "founder-family-registered", "founder-family-run", "txsigner-service-updated"])
    error_message = "the four security alarms are not all there"
  }
  assert {
    condition     = alltrue([for alarm in aws_cloudwatch_metric_alarm.security : alarm.alarm_actions == toset([var.alarm_topic_arn]) && alarm.threshold == 1])
    error_message = "a security alarm does not notify on the first event"
  }
  assert {
    condition     = alltrue([for filter in aws_cloudwatch_log_metric_filter.security : length(filter.pattern) <= 1024])
    error_message = "a metric filter pattern is longer than 1024 characters"
  }
  assert {
    condition     = strcontains(aws_cloudwatch_log_metric_filter.security["founder-role-iam-change"].pattern, "\"eureka-real-txsigner\"")
    error_message = "the IAM alarm does not watch the TxSigner role"
  }
  assert {
    condition = alltrue([for needle in ["\"RegisterTaskDefinition\"", "\"eureka-real-txsigner*\"", "arn:aws:iam::111111111111:role/eureka-real-txsigner"] :
    strcontains(aws_cloudwatch_log_metric_filter.security["founder-family-registered"].pattern, needle)])
    error_message = "the registration alarm does not watch the TxSigner family or role"
  }
  assert {
    condition = alltrue([for needle in ["\"RunTask\"", "eureka-real-txsigner*", "eureka-real-db-bootstrap*", "overrides.taskRoleArn"] :
    strcontains(aws_cloudwatch_log_metric_filter.security["founder-family-run"].pattern, needle)])
    error_message = "the RunTask alarm does not watch the TxSigner and bootstrap families"
  }
  # KMS key policy and tag changes: the existing kms-admin-action alarm covers them.
  assert {
    condition = alltrue([for needle in ["PutKeyPolicy", "TagResource", "UntagResource"] :
    strcontains(aws_cloudwatch_log_metric_filter.kms_admin.pattern, "\"${needle}\"")])
    error_message = "kms-admin-action no longer covers key policy or tag changes"
  }
}
