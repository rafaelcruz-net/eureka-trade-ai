package com.memetrade.txsigner.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 24, the JSON of the KMS policies versioned in infra/terraform/modules/kms/policies (infrastructure-specification
 * "Política do KMS", security-design.md "Testes da política de acesso"; the kms-sim does not evaluate permissions):
 * creation only with the four tags, use only of keys tagged for the bot wallet, tags immutable, no deletion nor
 * delegation, and the review corrections: every key policy denies Sign (and the HMAC key GenerateMac) to any principal
 * that is not the TxSigner role.
 */
class KmsPolicyDocumentsTest {

    private static final Path POLICIES = Path.of("..", "..", "infra", "terraform", "modules", "kms", "policies");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode render(String file) throws Exception {
        String text = Files.readString(POLICIES.resolve(file))
                .replace("${region}", "us-east-1")
                .replace("${account_id}", "123456789012")
                .replace("${hmac_key_arn}", "arn:aws:kms:us-east-1:123456789012:key/hmac")
                .replace("${txsigner_role_arn}", "arn:aws:iam::123456789012:role/eureka-real-txsigner");
        assertThat(text).doesNotContain("${");
        return JSON.readTree(text);
    }

    private static JsonNode statement(JsonNode policy, String sid) {
        for (JsonNode statement : policy.path("Statement")) {
            if (sid.equals(statement.path("Sid").asText())) {
                return statement;
            }
        }
        throw new AssertionError("No statement " + sid);
    }

    private static List<String> actions(JsonNode statement) {
        List<String> actions = new ArrayList<>();
        statement.path("Action").forEach(action -> actions.add(action.asText()));
        return actions;
    }

    @Test
    void theRoleCreatesKeysOnlyEd25519WithTheFourTags() throws Exception {
        JsonNode create = statement(render("txsigner-role.json.tftpl"), "CreateWalletKeyOnlyWithTheFourTags");

        assertThat(create.at("/Condition/StringEquals/kms:KeySpec").asText()).isEqualTo("ECC_NIST_EDWARDS25519");
        assertThat(create.at("/Condition/StringEquals/kms:KeyUsage").asText()).isEqualTo("SIGN_VERIFY");
        assertThat(create.at("/Condition/StringEquals/aws:RequestTag~1app").asText())
                .isEqualTo("eureka-botwallet");
        assertThat(create.at("/Condition/ForAllValues:StringEquals/aws:TagKeys").toString())
                .contains("app", "mainWalletMac", "creationRequestId", "network");
        for (String tag : List.of("app", "mainWalletMac", "creationRequestId", "network")) {
            assertThat(create.at("/Condition/Null/aws:RequestTag~1" + tag).asText())
                    .isEqualTo("false");
        }
    }

    @Test
    void theRoleUsesOnlyBotWalletKeysAndTheHmacKey() throws Exception {
        JsonNode policy = render("txsigner-role.json.tftpl");

        JsonNode use = statement(policy, "UseOnlyBotWalletKeys");
        assertThat(actions(use))
                .containsExactlyInAnyOrder("kms:Sign", "kms:GetPublicKey", "kms:DescribeKey", "kms:ListResourceTags");
        assertThat(use.at("/Condition/StringEquals/aws:ResourceTag~1app").asText())
                .isEqualTo("eureka-botwallet");
        assertThat(statement(policy, "AnchorMacOnlyWithTheHmacKey")
                        .path("Resource")
                        .asText())
                .endsWith("key/hmac");
    }

    @Test
    void tagsAreImmutableAndNothingIsDeletedNorDelegated() throws Exception {
        JsonNode policy = render("txsigner-role.json.tftpl");

        JsonNode immutable = statement(policy, "TagsAreImmutableAfterCreation");
        assertThat(immutable.path("Effect").asText()).isEqualTo("Deny");
        assertThat(actions(immutable)).containsExactlyInAnyOrder("kms:TagResource", "kms:UntagResource");
        JsonNode never = statement(policy, "NeverDestroyNorDelegate");
        assertThat(never.path("Effect").asText()).isEqualTo("Deny");
        assertThat(actions(never))
                .contains(
                        "kms:ScheduleKeyDeletion",
                        "kms:DisableKey",
                        "kms:PutKeyPolicy",
                        "kms:CreateGrant",
                        "kms:Decrypt");
    }

    @Test
    void everyWalletKeyDeniesSignToAnyPrincipalButTheTxSigner() throws Exception {
        JsonNode deny = statement(render("wallet-key-policy.json.tftpl"), "NobodyElseSigns");

        assertThat(deny.path("Effect").asText()).isEqualTo("Deny");
        assertThat(actions(deny)).containsExactly("kms:Sign");
        assertThat(deny.at("/Condition/ArnNotEquals/aws:PrincipalArn").asText())
                .isEqualTo("arn:aws:iam::123456789012:role/eureka-real-txsigner");
        JsonNode admin = statement(render("wallet-key-policy.json.tftpl"), "AccountAdministrationThroughIam");
        assertThat(actions(admin)).doesNotContain("kms:Sign", "kms:*");
    }

    @Test
    void theHmacKeyDeniesGenerateMacToAnyPrincipalButTheTxSigner() throws Exception {
        JsonNode deny = statement(render("hmac-key-policy.json.tftpl"), "NobodyElseGeneratesTheAnchorMac");

        assertThat(deny.path("Effect").asText()).isEqualTo("Deny");
        assertThat(actions(deny)).containsExactly("kms:GenerateMac");
        assertThat(deny.at("/Condition/ArnNotEquals/aws:PrincipalArn").asText()).endsWith("role/eureka-real-txsigner");
    }
}
