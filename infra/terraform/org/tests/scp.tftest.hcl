# Offline checks of the SCP (security-posture-review.md SP-02 and SP-03; Environment Provisioning of
# 261113-trading-real-bot). mock_provider: no AWS credential, account nor network call.
# Run from infra/terraform/org: terraform init -backend=false && terraform test

mock_provider "aws" {}

variables {
  region                = "sa-east-1"
  management_account_id = "000000000000"
  devnet_account_id     = "111111111111"
  mainnet_account_id    = "222222222222"
}

run "guardrails" {
  command = plan

  assert {
    condition     = length(aws_organizations_policy.guardrails.content) <= 5120
    error_message = "the SCP is longer than the 5120 characters AWS accepts"
  }
  # SP-02: IAM changes, PassRole and AssumeRole of the TxSigner role need MFA; PassRole only to ECS tasks.
  assert {
    condition = alltrue([for sid in ["DenyFounderOnlyRoleChangesWithoutMfa", "DenyPassingFounderOnlyRolesWithoutMfa"] :
      [for s in [for t in jsondecode(aws_organizations_policy.guardrails.content).Statement : t if t.Sid == sid] :
        s.Condition.BoolIfExists["aws:MultiFactorAuthPresent"] == "false"
    && contains(s.Resource, "arn:aws:iam::*:role/eureka-real-txsigner") && contains(s.Resource, "arn:aws:iam::*:role/eureka-real-txsigner-*")] == [true]])
    error_message = "the TxSigner roles may be changed or passed without MFA"
  }
  assert {
    condition = length(setsubtract(["iam:UpdateAssumeRolePolicy", "iam:PutRolePolicy", "iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:DeleteRolePolicy", "iam:DeleteRole", "iam:UpdateRole"],
    [for s in jsondecode(aws_organizations_policy.guardrails.content).Statement : s.Action if s.Sid == "DenyFounderOnlyRoleChangesWithoutMfa"][0])) == 0
    error_message = "an IAM change of the TxSigner role is not denied without MFA"
  }
  assert {
    condition = [for s in jsondecode(aws_organizations_policy.guardrails.content).Statement : s.Condition.StringNotEquals["iam:PassedToService"]
    if s.Sid == "DenyPassingTheTxSignerRoleOutsideEcs"][0] == "ecs-tasks.amazonaws.com"
    error_message = "the TxSigner role may be passed outside ECS"
  }
  assert {
    condition = [for s in [for t in jsondecode(aws_organizations_policy.guardrails.content).Statement : t if t.Sid == "DenyAssumingTheTxSignerRoleWithoutMfa"] :
      s.Action == "sts:AssumeRole*" && s.Resource == "arn:aws:iam::*:role/eureka-real-txsigner"
    && s.Condition.BoolIfExists["aws:MultiFactorAuthPresent"] == "false" && s.Condition.BoolIfExists["aws:PrincipalIsAWSService"] == "false"] == [true]
    error_message = "the TxSigner role may be assumed without MFA"
  }
  # SP-03: tag changes of KMS keys need MFA, except the TxSigner role (tags at CreateKey; its own policy denies later changes).
  assert {
    condition = [for s in jsondecode(aws_organizations_policy.guardrails.content).Statement : s.Condition.ArnNotLike["aws:PrincipalArn"]
    if s.Sid == "DenyKeyTagChangesWithoutMfa"][0] == "arn:aws:iam::*:role/eureka-real-txsigner"
    error_message = "KMS tag changes are not bound to MFA"
  }
  # The guardrails of before are kept.
  assert {
    condition = length(setsubtract(["DenyEverythingOutsideTheAllowedRegion", "DenyKeyDeletionWithoutMfa", "DenyLeavingTheOrganization", "DenyTurningTheAuditOff", "DenyDeletingTheTrailLogsWithoutMfa"],
    [for s in jsondecode(aws_organizations_policy.guardrails.content).Statement : s.Sid])) == 0
    error_message = "a previous guardrail is missing"
  }
}
