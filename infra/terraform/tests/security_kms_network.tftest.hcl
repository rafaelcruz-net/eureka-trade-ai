# Offline checks of the key policies, the TxSigner role trust and the VPC endpoint policies (security-posture-review.md
# SP-02, SP-03, SP-05; Environment Provisioning of 261113-trading-real-bot). The templates of modules/kms/policies are
# also read by KmsPolicyDocumentsTest (apps/txsigner); the endpoint condition is appended in HCL and checked here.
# mock_provider: no AWS credential, account nor network call. Run from infra/terraform: terraform test

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
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::111111111111:role/eureka-real-txsigner" }
  }
  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:sa-east-1:111111111111:key/hmac" }
  }
  mock_resource "aws_vpc_endpoint" {
    defaults = { id = "vpce-0kms", dns_entry = [{ dns_name = "vpce-1.kms.sa-east-1.vpce.amazonaws.com", hosted_zone_id = "Z" }] }
  }
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:sa-east-1:111111111111:log-group:/eureka-real/vpc-flow" }
  }
  mock_data "aws_ec2_managed_prefix_list" {
    defaults = { id = "pl-s3" }
  }
}

variables {
  name                = "eureka-real"
  kms_vpc_endpoint_id = "vpce-0kms"
  zone                = "sa-east-1a"
  zone_b              = "sa-east-1b"
}

run "key_policies_and_role_trust" {
  command = apply
  module {
    source = "./modules/kms"
  }

  # SP-02: Sign only through the KMS endpoint of the VPC, for every principal.
  assert {
    condition = [for s in [for t in jsondecode(output.wallet_key_policy).Statement : t if t.Sid == "SignOnlyThroughTheKmsVpcEndpoint"] :
      s.Effect == "Deny" && s.Principal.AWS == "*" && s.Action == ["kms:Sign"]
    && s.Condition.StringNotEquals["aws:SourceVpce"] == "vpce-0kms"] == [true]
    error_message = "the wallet key policy does not deny Sign outside the KMS endpoint"
  }
  assert {
    condition = [for s in [for t in jsondecode(aws_kms_key.anchor_hmac.policy).Statement : t if t.Sid == "MacOnlyThroughTheKmsVpcEndpoint"] :
      s.Effect == "Deny" && toset(s.Action) == toset(["kms:GenerateMac", "kms:VerifyMac"])
    && s.Condition.StringNotEquals["aws:SourceVpce"] == "vpce-0kms"] == [true]
    error_message = "the HMAC key policy does not deny GenerateMac/VerifyMac outside the KMS endpoint"
  }
  # The statements of the templates are kept (the TxSigner is still the only signer).
  assert {
    condition     = contains([for s in jsondecode(output.wallet_key_policy).Statement : s.Sid], "NobodyElseSigns")
    error_message = "NobodyElseSigns is missing"
  }
  # SP-03: no tag change through the account statement without MFA, in both key policies.
  assert {
    condition = alltrue([for policy in [jsondecode(output.wallet_key_policy), jsondecode(aws_kms_key.anchor_hmac.policy)] :
      length(setintersection(toset([for s in policy.Statement : s.Action if s.Sid == "AccountAdministrationThroughIam"][0]),
    toset(["kms:TagResource", "kms:UntagResource"]))) == 0])
    error_message = "the account statement still grants tag changes without MFA"
  }
  assert {
    condition = alltrue([for policy in [jsondecode(output.wallet_key_policy), jsondecode(aws_kms_key.anchor_hmac.policy)] :
      [for s in policy.Statement : s.Condition.Bool["aws:MultiFactorAuthPresent"]
    if s.Sid == "TagChangesOnlyWithMfa"][0] == "true"])
    error_message = "tag changes are not bound to MFA"
  }
  # SP-02: the TxSigner role trusts ECS tasks of this account only.
  assert {
    condition     = jsondecode(aws_iam_role.txsigner.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "111111111111"
    error_message = "the TxSigner trust lacks aws:SourceAccount"
  }
  assert {
    condition     = jsondecode(aws_iam_role.txsigner.assume_role_policy).Statement[0].Condition.ArnLike["aws:SourceArn"] == "arn:aws:ecs:sa-east-1:111111111111:*"
    error_message = "the TxSigner trust lacks aws:SourceArn"
  }
  assert {
    condition     = jsondecode(aws_iam_role.txsigner.assume_role_policy).Statement[0].Principal.Service == "ecs-tasks.amazonaws.com"
    error_message = "the TxSigner role trusts a principal other than ECS tasks"
  }
}

run "vpc_endpoint_policies" {
  command = apply
  module {
    source = "./modules/network"
  }

  # SP-05: every interface endpoint accepts principals and resources of this account only.
  assert {
    condition = alltrue([for key, endpoint in aws_vpc_endpoint.interface :
      jsondecode(endpoint.policy).Statement[0].Condition.StringEquals["aws:PrincipalAccount"] == "111111111111"
    && jsondecode(endpoint.policy).Statement[0].Condition.StringEqualsIfExists["aws:ResourceAccount"] == "111111111111"])
    error_message = "an interface endpoint accepts another account"
  }
  assert {
    condition     = jsondecode(aws_vpc_endpoint.interface["kms"].policy).Statement[0].Action == "kms:*" && jsondecode(aws_vpc_endpoint.interface["secretsmanager"].policy).Statement[0].Action == "secretsmanager:*"
    error_message = "the KMS or Secrets Manager endpoint serves other actions"
  }
  assert {
    condition     = length(aws_vpc_endpoint.interface) == 7
    error_message = "7 interface endpoints expected"
  }
  # S3 gateway: the ECR layer bucket of the region (read) and the buckets of this account only.
  assert {
    condition = [for s in jsondecode(aws_vpc_endpoint.s3.policy).Statement : s.Resource
    if s.Sid == "EcrImageLayersOfThisRegion"][0] == ["arn:aws:s3:::prod-sa-east-1-starport-layer-bucket/*"]
    error_message = "the S3 endpoint does not allow the ECR layer bucket of the region"
  }
  assert {
    condition = [for s in jsondecode(aws_vpc_endpoint.s3.policy).Statement : s.Condition.StringEquals["aws:ResourceAccount"]
    if s.Sid == "OwnAccountBucketsOnly"][0] == "111111111111"
    error_message = "the S3 endpoint accepts buckets of another account"
  }
  assert {
    condition     = length(jsondecode(aws_vpc_endpoint.s3.policy).Statement) == 2
    error_message = "the S3 endpoint policy has an unexpected statement"
  }
  assert {
    condition     = output.kms_endpoint_id == aws_vpc_endpoint.interface["kms"].id
    error_message = "kms_endpoint_id is not the KMS endpoint"
  }
}
