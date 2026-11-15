package com.memetrade.txsigner.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import com.memetrade.txsigner.policy.Ed25519Verifier;
import com.memetrade.txsigner.testsupport.KmsJsonProtocolServer;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Step 22, adapter tested after the code against the KMS JSON protocol of the kms-sim (contract X4 of the addendum):
 * the real SDK client creates the key with its tags, reads the DER public key, signs pure Ed25519 over RAW, lists only
 * customer keys, reads the tags, refuses a tag change, generates and checks the anchor MAC, and an unreachable endpoint
 * is a technical failure.
 */
class AwsKmsAdapterTest {

    private KmsJsonProtocolServer server;
    private KmsClient client;
    private AwsKmsAdapter adapter;

    @BeforeEach
    void start() throws Exception {
        server = new KmsJsonProtocolServer(new KmsSimulator());
        client = KmsClient.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(server.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        adapter = new AwsKmsAdapter(client, server.hmacKeyId(), null, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
    }

    @Test
    void createsAKeyReadsItsAddressAndSignsPureEd25519() {
        String keyRef = adapter.createKey(new KeyTags("aa", UUID.randomUUID().toString(), "LOCALNET"));
        byte[] address = adapter.publicKey(keyRef);
        byte[] message = "transfer".getBytes(StandardCharsets.UTF_8);

        byte[] signature = adapter.sign(keyRef, message);

        assertThat(Base58.encode(address)).hasSizeBetween(32, 44);
        assertThat(new Ed25519Verifier().verify(address, message, signature)).isTrue();
        assertThat(adapter.keyCreatedAt(keyRef)).isNotNull();
    }

    @Test
    void listsOnlyCustomerKeysAndReadsTheirTags() {
        String keyRef = adapter.createKey(new KeyTags("bb", "req-1", "LOCALNET"));

        assertThat(adapter.listKeyRefs()).containsExactly(keyRef);
        assertThat(adapter.tags(keyRef))
                .containsEntry(KeyTags.APP, KeyTags.APP_VALUE)
                .containsEntry(KeyTags.MAIN_WALLET_MAC, "bb");
        assertThat(adapter.isReachable()).isTrue();
    }

    @Test
    void theAnchorMacVerifiesOnlyTheSameData() {
        byte[] mac = adapter.mac("main".getBytes(StandardCharsets.UTF_8));

        assertThat(adapter.verifyMac("main".getBytes(StandardCharsets.UTF_8), mac))
                .isTrue();
        assertThat(adapter.verifyMac("other".getBytes(StandardCharsets.UTF_8), mac))
                .isFalse();
    }

    @Test
    void anUnknownKeyIsARefusalNotAnOutage() {
        assertThatThrownBy(() -> adapter.sign("missing", new byte[] {1})).isInstanceOf(KmsRefusedException.class);
    }

    @Test
    void anUnreachableKmsIsATechnicalFailure() {
        server.close();

        assertThatThrownBy(() -> adapter.publicKey("any")).isInstanceOf(KmsUnavailableException.class);
        assertThat(adapter.isReachable()).isFalse();
    }
}
