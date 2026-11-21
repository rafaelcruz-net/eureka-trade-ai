# Public load balancer of the api, the web and the admin (infrastructure-specification.md U1 "Load balancer";
# cd-config.md 5.2). HTTPS only (TLS 1.2+), HTTP redirected; one host per app on the same registrable domain; the
# targets are the private task addresses. Health checks: the api on its readiness (8081: readinessState, db,
# migrations), the web and the admin on /api/health. Access logs go to a bucket of this account (90 days).

resource "aws_lb" "main" {
  # checkov:skip=CKV2_AWS_28:No WAF in the pilot (about US$ 10 per month); candidate control of G1 before the invited users (team.md, Deployment).
  name                       = var.name
  internal                   = false
  load_balancer_type         = "application"
  subnets                    = var.public_subnet_ids
  security_groups            = [var.alb_security_group_id]
  drop_invalid_header_fields = true
  enable_deletion_protection = local.deletion_protection
  access_logs {
    bucket  = aws_s3_bucket.alb_logs.id
    prefix  = "alb"
    enabled = true
  }
  depends_on = [aws_s3_bucket_policy.alb_logs]
}

resource "aws_lb_target_group" "api" {
  # checkov:skip=CKV_AWS_378:TLS ends at the load balancer; the hop to the task stays inside the VPC, limited by security groups.
  name                 = "${var.name}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    port                = "8081"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "frontend" {
  # checkov:skip=CKV_AWS_378:TLS ends at the load balancer; the hop to the task stays inside the VPC, limited by security groups.
  for_each             = local.frontends
  name                 = "${var.name}-${each.key}"
  port                 = 3000
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/api/health"
    port                = "traffic-port"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  # checkov:skip=CKV_AWS_2:Port 80 only answers a permanent redirect to HTTPS.
  # checkov:skip=CKV_AWS_103:Port 80 only answers a permanent redirect to HTTPS.
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  # Unknown host: 404, never a default app.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "host" {
  for_each = {
    api   = { priority = 10, host = var.api_domain, target = aws_lb_target_group.api.arn }
    web   = { priority = 20, host = var.web_domain, target = aws_lb_target_group.frontend["web"].arn }
    admin = { priority = 30, host = var.admin_domain, target = aws_lb_target_group.frontend["admin"].arn }
  }
  listener_arn = aws_lb_listener.https.arn
  priority     = each.value.priority
  action {
    type             = "forward"
    target_group_arn = each.value.target
  }
  condition {
    host_header {
      values = [each.value.host]
    }
  }
}

# --- Access logs --------------------------------------------------------------------------------------------------

data "aws_elb_service_account" "current" {}

resource "aws_s3_bucket" "alb_logs" {
  # checkov:skip=CKV_AWS_144:One region by design (SCP of the account); no cross-region replication of access logs.
  # checkov:skip=CKV_AWS_145:Load balancer access logs support SSE-S3 only.
  # checkov:skip=CKV_AWS_18:This bucket is itself the access log destination; logging it again adds cost and no control.
  # checkov:skip=CKV2_AWS_62:No consumer of object events.
  bucket        = "${var.name}-alb-logs-${local.account_id}"
  force_destroy = !local.deletion_protection
}

resource "aws_s3_bucket_versioning" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "alb_logs" {
  bucket                  = aws_s3_bucket.alb_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "alb_logs" {
  bucket = aws_s3_bucket.alb_logs.id
  rule {
    id     = "expire-after-90-days"
    status = "Enabled"
    filter {}
    expiration {
      days = 90
    }
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "alb_logs" {
  statement {
    sid       = "LoadBalancerWritesItsAccessLogs"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.alb_logs.arn}/alb/AWSLogs/${local.account_id}/*"]
    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.current.arn]
    }
  }
  statement {
    sid       = "TlsOnly"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.alb_logs.arn, "${aws_s3_bucket.alb_logs.arn}/*"]
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

resource "aws_s3_bucket_policy" "alb_logs" {
  bucket     = aws_s3_bucket.alb_logs.id
  policy     = data.aws_iam_policy_document.alb_logs.json
  depends_on = [aws_s3_bucket_public_access_block.alb_logs]
}
