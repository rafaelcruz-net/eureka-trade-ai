package com.memetrade.txsigner.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.domain.kms.KmsThrottledException;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import com.memetrade.txsigner.testsupport.KmsSimulator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 8, adapter tested after the code (reliability-design.md "Proteção das chamadas"): the KMS circuit opens on
 * technical failures only, never on refusals, fails fast while open and times every call. Loop-back 1 of Build and
 * Test (NFR5.14, NFR5.19), written after the code: the Sign latency and the KMS errors by kind, registered at zero.
 */
class ResilientKmsPortTest {

    private final KmsSimulator simulator = new KmsSimulator();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ResilientKmsPort kms = new ResilientKmsPort(simulator, meters);

    @Test
    void callsPassThroughAndAreTimed() {
        String keyRef = kms.createKey(new KeyTags("aa", UUID.randomUUID().toString(), "LOCALNET"));

        assertThat(kms.publicKey(keyRef)).hasSize(32);
        assertThat(kms.sign(keyRef, new byte[] {1})).hasSize(64);
        assertThat(kms.tags(keyRef)).containsEntry(KeyTags.NETWORK, "LOCALNET");
        assertThat(kms.listKeyRefs()).contains(keyRef);
        assertThat(kms.keyCreatedAt(keyRef)).isNotNull();
        assertThat(kms.verifyMac(new byte[] {2}, kms.mac(new byte[] {2}))).isTrue();
        assertThat(kms.isReachable()).isTrue();
        assertThat(meters.get("txsigner_kms_latency")
                        .tag("operation", "Sign")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void technicalFailuresOpenTheCircuitAndItThenFailsFast() {
        simulator.goDown();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(kms::listKeyRefs).isInstanceOf(KmsUnavailableException.class);
        }

        assertThat(kms.state()).isEqualTo(CircuitBreaker.State.OPEN);
        simulator.recover();
        assertThatThrownBy(kms::listKeyRefs)
                .isInstanceOf(KmsUnavailableException.class)
                .hasMessageContaining("circuit is open");
    }

    @Test
    void theErrorCountersStartAtZeroAndTheSignCallsAreTimed() {
        String keyRef = kms.createKey(new KeyTags("aa", UUID.randomUUID().toString(), "LOCALNET"));
        kms.sign(keyRef, new byte[] {1});
        kms.sign(keyRef, new byte[] {2});

        assertThat(errors("THROTTLED")).isZero();
        assertThat(errors("ERROR")).isZero();
        assertThat(meters.get("txsigner_kms_sign").timer().count()).isEqualTo(2);
    }

    @Test
    void quotaRefusalsCountAsThrottledAndOtherOutagesAsErrors() {
        String keyRef = kms.createKey(new KeyTags("aa", UUID.randomUUID().toString(), "LOCALNET"));
        simulator.throttle();
        assertThatThrownBy(() -> kms.sign(keyRef, new byte[] {1})).isInstanceOf(KmsThrottledException.class);
        simulator.recover();
        simulator.goDown();
        assertThatThrownBy(() -> kms.publicKey(keyRef)).isInstanceOf(KmsUnavailableException.class);

        assertThat(errors("THROTTLED")).isEqualTo(1);
        assertThat(errors("ERROR")).isEqualTo(1);
        assertThat(meters.get("txsigner_kms_sign").timer().count()).isEqualTo(1);
    }

    @Test
    void neitherAnOpenCircuitNorARefusalCountsAsAKmsError() {
        simulator.goDown();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(kms::listKeyRefs).isInstanceOf(KmsUnavailableException.class);
        }
        double errorsWhileDown = errors("ERROR");
        simulator.recover();

        assertThatThrownBy(kms::listKeyRefs).hasMessageContaining("circuit is open");
        assertThat(errors("ERROR")).isEqualTo(errorsWhileDown).isEqualTo(3);
        assertThat(errors("THROTTLED")).isZero();
    }

    @Test
    void aRefusalIsNotAKmsError() {
        assertThatThrownBy(() -> kms.publicKey("unknown-key")).isInstanceOf(KmsRefusedException.class);

        assertThat(errors("ERROR")).isZero();
        assertThat(errors("THROTTLED")).isZero();
    }

    private double errors(String kind) {
        return meters.get("txsigner_kms_errors").tag("kind", kind).counter().count();
    }

    @Test
    void refusalsNeverOpenTheCircuit() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> kms.publicKey("unknown-key")).isInstanceOf(KmsRefusedException.class);
        }

        assertThat(kms.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
