package com.memetrade.txsigner.infra.aws;

import com.memetrade.txsigner.codec.Ed25519Keys;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateMacRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.KeyListEntry;
import software.amazon.awssdk.services.kms.model.KeyManagerType;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.KmsInvalidMacException;
import software.amazon.awssdk.services.kms.model.ListKeysRequest;
import software.amazon.awssdk.services.kms.model.ListResourceTagsRequest;
import software.amazon.awssdk.services.kms.model.MacAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.Tag;
import software.amazon.awssdk.services.kms.model.VerifyMacRequest;

/**
 * AWS KMS adapter of {@link KmsPort} (NFR1.1, docs/proofs/kms-ed25519.md): one asymmetric key per bot wallet with
 * {@code KeySpec ECC_NIST_EDWARDS25519} and {@code KeyUsage SIGN_VERIFY}, non-exportable; signatures with
 * {@code ED25519_SHA_512} over the RAW message, which is pure Ed25519 (RFC 8032) as Solana requires; the anchor MAC with
 * the {@code HMAC_256} key. The adapter never asks the KMS for private material: the KMS has no such operation.
 */
public final class AwsKmsAdapter implements KmsPort {

    public static final String KEY_SPEC = "ECC_NIST_EDWARDS25519";
    public static final String SIGNING_ALGORITHM = "ED25519_SHA_512";

    private static final Set<String> TECHNICAL_ERRORS = Set.of(
            "ThrottlingException",
            "LimitExceededException",
            "KMSInternalException",
            "DependencyTimeoutException",
            "KeyUnavailableException");

    private final KmsClient client;
    private final String hmacKeyId;
    private final String keyPolicy;
    private final AwsRequestOverrideConfiguration timeout;

    /**
     * @param keyPolicy JSON key policy passed in CreateKey (infrastructure-specification.md "Política de cada chave");
     *     {@code null} only for the kms-sim of the tests and the local Compose
     */
    public AwsKmsAdapter(KmsClient client, String hmacKeyId, String keyPolicy, Duration callTimeout) {
        this.client = client;
        this.hmacKeyId = hmacKeyId;
        this.keyPolicy = keyPolicy;
        this.timeout = AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(callTimeout)
                .build();
    }

    @Override
    public String createKey(KeyTags tags) {
        CreateKeyRequest.Builder request = CreateKeyRequest.builder()
                .keySpec(KEY_SPEC)
                .keyUsage(KeyUsageType.SIGN_VERIFY)
                .description("Eureka bot wallet")
                .tags(tags.asMap().entrySet().stream()
                        .map(e -> Tag.builder()
                                .tagKey(e.getKey())
                                .tagValue(e.getValue())
                                .build())
                        .toList())
                .overrideConfiguration(timeout);
        if (keyPolicy != null && !keyPolicy.isBlank()) {
            request.policy(keyPolicy);
        }
        return call(
                "CreateKey",
                () -> client.createKey(request.build()).keyMetadata().keyId());
    }

    @Override
    public byte[] publicKey(String keyRef) {
        byte[] der = call("GetPublicKey", () -> client.getPublicKey(GetPublicKeyRequest.builder()
                        .keyId(keyRef)
                        .overrideConfiguration(timeout)
                        .build())
                .publicKey()
                .asByteArray());
        return Ed25519Keys.rawFromSpki(der);
    }

    @Override
    public byte[] sign(String keyRef, byte[] message) {
        return call("Sign", () -> client.sign(SignRequest.builder()
                        .keyId(keyRef)
                        .message(SdkBytes.fromByteArray(message))
                        .messageType(MessageType.RAW)
                        .signingAlgorithm(SIGNING_ALGORITHM)
                        .overrideConfiguration(timeout)
                        .build())
                .signature()
                .asByteArray());
    }

    @Override
    public List<String> listKeyRefs() {
        List<String> customerKeys = new ArrayList<>();
        call("ListKeys", () -> {
            for (KeyListEntry key : client.listKeysPaginator(ListKeysRequest.builder()
                            .overrideConfiguration(timeout)
                            .build())
                    .keys()) {
                if (isCustomerKey(key.keyId())) {
                    customerKeys.add(key.keyId());
                }
            }
            return customerKeys;
        });
        return customerKeys;
    }

    @Override
    public Map<String, String> tags(String keyRef) {
        return call("ListResourceTags", () -> {
            Map<String, String> tags = new LinkedHashMap<>();
            client.listResourceTags(ListResourceTagsRequest.builder()
                            .keyId(keyRef)
                            .overrideConfiguration(timeout)
                            .build())
                    .tags()
                    .forEach(tag -> tags.put(tag.tagKey(), tag.tagValue()));
            return tags;
        });
    }

    @Override
    public java.time.Instant keyCreatedAt(String keyRef) {
        return call("DescribeKey", () -> client.describeKey(DescribeKeyRequest.builder()
                        .keyId(keyRef)
                        .overrideConfiguration(timeout)
                        .build())
                .keyMetadata()
                .creationDate());
    }

    @Override
    public byte[] mac(byte[] data) {
        return call("GenerateMac", () -> client.generateMac(GenerateMacRequest.builder()
                        .keyId(hmacKeyId)
                        .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)
                        .message(SdkBytes.fromByteArray(data))
                        .overrideConfiguration(timeout)
                        .build())
                .mac()
                .asByteArray());
    }

    @Override
    public boolean verifyMac(byte[] data, byte[] mac) {
        try {
            return call("VerifyMac", () -> client.verifyMac(VerifyMacRequest.builder()
                            .keyId(hmacKeyId)
                            .macAlgorithm(MacAlgorithmSpec.HMAC_SHA_256)
                            .message(SdkBytes.fromByteArray(data))
                            .mac(SdkBytes.fromByteArray(mac))
                            .overrideConfiguration(timeout)
                            .build())
                    .macValid());
        } catch (KmsRefusedException refused) {
            if (refused.getCause() instanceof KmsInvalidMacException) {
                return false;
            }
            throw refused;
        }
    }

    @Override
    public boolean isReachable() {
        try {
            call(
                    "ListKeys",
                    () -> client.listKeys(ListKeysRequest.builder()
                            .limit(1)
                            .overrideConfiguration(timeout)
                            .build()));
            return true;
        } catch (KmsUnavailableException | KmsRefusedException down) {
            return false;
        }
    }

    /** Keys managed by AWS answer AccessDenied to DescribeKey; that is not a KMS error (infrastructure-specification). */
    private boolean isCustomerKey(String keyId) {
        try {
            return KeyManagerType.CUSTOMER
                    == client.describeKey(DescribeKeyRequest.builder()
                                    .keyId(keyId)
                                    .overrideConfiguration(timeout)
                                    .build())
                            .keyMetadata()
                            .keyManager();
        } catch (AwsServiceException denied) {
            if (isTechnical(denied)) {
                throw new KmsUnavailableException("KMS DescribeKey failed: " + errorCode(denied), denied);
            }
            return false;
        }
    }

    private static <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (SdkClientException technical) {
            throw new KmsUnavailableException("KMS " + operation + " did not answer", technical);
        } catch (AwsServiceException failure) {
            if (isTechnical(failure)) {
                throw new KmsUnavailableException("KMS " + operation + " failed: " + errorCode(failure), failure);
            }
            throw new KmsRefusedException("KMS " + operation + " refused: " + errorCode(failure), failure);
        }
    }

    private static boolean isTechnical(AwsServiceException failure) {
        return failure.statusCode() >= 500 || TECHNICAL_ERRORS.contains(errorCode(failure));
    }

    private static String errorCode(AwsServiceException failure) {
        return failure.awsErrorDetails() == null
                ? "unknown"
                : failure.awsErrorDetails().errorCode();
    }
}
