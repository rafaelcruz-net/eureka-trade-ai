package com.memetrade.txsigner.proof;

import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.infra.aws.AwsKmsAdapter;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.Tag.Builder;
import software.amazon.awssdk.services.kms.model.TagResourceRequest;

/**
 * Proof 1 against the REAL AWS KMS (docs/proofs/kms-ed25519.md). Tagged {@code aws-proof}: excluded from every Maven
 * run and from the CI (NFR8.4); the founder runs it by hand, with the TxSigner role credentials of the new account:
 *
 * <pre>
 * AWS_REGION=... TXSIGNER_PROOF_HMAC_KEY_ID=... sh apps/txsigner/mvnw -B -ntp -f apps/txsigner/pom.xml test \
 *   -Dtest=AwsKmsEd25519ProofTest -DexcludedGroups=none -Dgroups=aws-proof
 * </pre>
 *
 * Each run creates keys in the account (about US$ 1 per key per month): schedule their deletion with the
 * administration role after the run.
 */
@Tag("aws-proof")
class AwsKmsEd25519ProofTest extends KmsEd25519Contract {

    private KmsClient client;
    private AwsKmsAdapter adapter;

    @BeforeEach
    void connect() {
        String region = System.getenv("AWS_REGION");
        String hmacKeyId = System.getenv("TXSIGNER_PROOF_HMAC_KEY_ID");
        Assumptions.assumeTrue(region != null && hmacKeyId != null, "AWS_REGION and TXSIGNER_PROOF_HMAC_KEY_ID");
        client = KmsClient.builder().region(Region.of(region)).build();
        adapter = new AwsKmsAdapter(client, hmacKeyId, null, Duration.ofSeconds(5));
    }

    @Override
    protected KmsPort kms() {
        return adapter;
    }

    @Override
    protected void changeTag(String keyRef, Map<String, String> tags) {
        try {
            client.tagResource(TagResourceRequest.builder()
                    .keyId(keyRef)
                    .tags(tags.entrySet().stream()
                            .map(e -> tag(e.getKey(), e.getValue()))
                            .toList())
                    .build());
        } catch (AwsServiceException denied) {
            throw new KmsRefusedException(denied.awsErrorDetails().errorCode(), denied);
        }
    }

    @Override
    protected void createWithRawTags(Map<String, String> tags) {
        try {
            client.createKey(CreateKeyRequest.builder()
                    .keySpec(AwsKmsAdapter.KEY_SPEC)
                    .keyUsage(KeyUsageType.SIGN_VERIFY)
                    .tags(tags.entrySet().stream()
                            .map(e -> tag(e.getKey(), e.getValue()))
                            .toList())
                    .build());
        } catch (AwsServiceException denied) {
            throw new KmsRefusedException(denied.awsErrorDetails().errorCode(), denied);
        }
    }

    @Override
    protected byte[] publicKeyDer(String keyRef) {
        return client.getPublicKey(GetPublicKeyRequest.builder().keyId(keyRef).build())
                .publicKey()
                .asByteArray();
    }

    private static software.amazon.awssdk.services.kms.model.Tag tag(String key, String value) {
        Builder builder = software.amazon.awssdk.services.kms.model.Tag.builder();
        return builder.tagKey(key).tagValue(value).build();
    }
}
