# The account trail (infrastructure-specification.md U1 "CloudTrail", NFR1.2 f). Until the Environment Provisioning of
# 261113-trading-real-bot only its bucket and its log group existed: no trail delivered to them, so the KMS metric
# filters and alarms of main.tf would never fire. Every region, global events, log file validation, delivered to the
# object-locked bucket and to the log group /<name>/cloudtrail that the metric filters read.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  trail_name = var.name
  trail_arn  = "arn:aws:cloudtrail:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:trail/${local.trail_name}"
}

data "aws_iam_policy_document" "trail_bucket" {
  statement {
    sid       = "CloudTrailReadsTheAcl"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.trail.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }
  statement {
    sid       = "CloudTrailWritesThisAccountOnly"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.trail.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }
  statement {
    sid       = "TlsOnly"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.trail.arn, "${aws_s3_bucket.trail.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "trail" {
  bucket     = aws_s3_bucket.trail.id
  policy     = data.aws_iam_policy_document.trail_bucket.json
  depends_on = [aws_s3_bucket_public_access_block.trail]
}

data "aws_iam_policy_document" "trail_logs_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }
}

resource "aws_iam_role" "trail_logs" {
  name               = "${var.name}-cloudtrail-logs"
  assume_role_policy = data.aws_iam_policy_document.trail_logs_trust.json
}

data "aws_iam_policy_document" "trail_logs" {
  statement {
    sid       = "WriteTheTrailLogGroupOnly"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.trail.arn}:*"]
  }
}

resource "aws_iam_role_policy" "trail_logs" {
  name   = "${var.name}-cloudtrail-logs"
  role   = aws_iam_role.trail_logs.id
  policy = data.aws_iam_policy_document.trail_logs.json
}

resource "aws_cloudtrail" "main" {
  # checkov:skip=CKV_AWS_35:The bucket is encrypted at rest with SSE-S3 and object-locked (SP-08); a customer KMS key for the trail adds cost and a key policy to maintain.
  # checkov:skip=CKV_AWS_252:The alarms of main.tf notify through the alarm topic; no per-delivery SNS notification.
  name                          = local.trail_name
  s3_bucket_name                = aws_s3_bucket.trail.id
  include_global_service_events = true
  is_multi_region_trail         = true
  enable_log_file_validation    = true
  cloud_watch_logs_group_arn    = "${aws_cloudwatch_log_group.trail.arn}:*"
  cloud_watch_logs_role_arn     = aws_iam_role.trail_logs.arn
  depends_on                    = [aws_s3_bucket_policy.trail, aws_iam_role_policy.trail_logs]
}
