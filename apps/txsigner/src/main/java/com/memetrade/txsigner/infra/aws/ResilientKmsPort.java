package com.memetrade.txsigner.infra.aws;

import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Circuit of the KMS calls (reliability-design.md): window of 60 s, at least 3 calls, opens at 50% of TECHNICAL
 * failures, stays open 30 s; a refusal of the KMS never opens it. Open, every call fails fast as unavailable (503).
 * Also times every call in {@code txsigner_kms_latency_seconds}.
 */
public final class ResilientKmsPort implements KmsPort {

    private final KmsPort delegate;
    private final CircuitBreaker circuit;
    private final MeterRegistry meters;

    public ResilientKmsPort(KmsPort delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
        this.circuit = CircuitBreaker.of(
                "kms",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                        .slidingWindowSize(60)
                        .minimumNumberOfCalls(3)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(KmsUnavailableException.class)
                        .build());
    }

    public CircuitBreaker.State state() {
        return circuit.getState();
    }

    @Override
    public String createKey(KeyTags tags) {
        return call("CreateKey", () -> delegate.createKey(tags));
    }

    @Override
    public byte[] publicKey(String keyRef) {
        return call("GetPublicKey", () -> delegate.publicKey(keyRef));
    }

    @Override
    public byte[] sign(String keyRef, byte[] message) {
        return call("Sign", () -> delegate.sign(keyRef, message));
    }

    @Override
    public List<String> listKeyRefs() {
        return call("ListKeys", delegate::listKeyRefs);
    }

    @Override
    public Map<String, String> tags(String keyRef) {
        return call("ListResourceTags", () -> delegate.tags(keyRef));
    }

    @Override
    public java.time.Instant keyCreatedAt(String keyRef) {
        return call("DescribeKey", () -> delegate.keyCreatedAt(keyRef));
    }

    @Override
    public byte[] mac(byte[] data) {
        return call("GenerateMac", () -> delegate.mac(data));
    }

    @Override
    public boolean verifyMac(byte[] data, byte[] mac) {
        return call("VerifyMac", () -> delegate.verifyMac(data, mac));
    }

    @Override
    public boolean isReachable() {
        return delegate.isReachable();
    }

    private <T> T call(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return circuit.executeSupplier(action);
        } catch (CallNotPermittedException open) {
            throw new KmsUnavailableException("KMS circuit is open", open);
        } finally {
            sample.stop(Timer.builder("txsigner_kms_latency")
                    .description("Latency of the KMS calls")
                    .tag("operation", operation)
                    .register(meters));
        }
    }
}
