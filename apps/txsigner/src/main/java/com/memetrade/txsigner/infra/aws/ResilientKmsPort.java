package com.memetrade.txsigner.infra.aws;

import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsThrottledException;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Circuit of the KMS calls (reliability-design.md): window of 60 s, at least 3 calls, opens at 50% of TECHNICAL
 * failures, stays open 30 s; a refusal of the KMS never opens it. Open, every call fails fast as unavailable (503).
 * Also times every call in {@code txsigner_kms_latency_seconds}, the Sign calls in {@code txsigner_kms_sign_seconds}, and
 * counts the technical failures of the KMS in {@code txsigner_kms_errors_total{kind}}: {@code THROTTLED} for the quota
 * refusals and {@code ERROR} for the others, both registered at zero (NFR5.14, alert NFR5.19). A call refused by the
 * open circuit never reached the KMS and is not counted; a refusal of the KMS (access, key state) is not an error here.
 */
public final class ResilientKmsPort implements KmsPort {

    private final KmsPort delegate;
    private final CircuitBreaker circuit;
    private final MeterRegistry meters;
    private final Timer signTimer;
    private final Counter throttled;
    private final Counter errors;

    public ResilientKmsPort(KmsPort delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
        this.signTimer = Timer.builder("txsigner_kms_sign")
                .description("Latency of the KMS Sign calls")
                .register(meters);
        this.throttled = errorCounter(meters, "THROTTLED");
        this.errors = errorCounter(meters, "ERROR");
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
        Timer.Sample sample = Timer.start(meters);
        try {
            return call("Sign", () -> delegate.sign(keyRef, message));
        } finally {
            sample.stop(signTimer);
        }
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

    private static Counter errorCounter(MeterRegistry meters, String kind) {
        return Counter.builder("txsigner_kms_errors")
                .description("Technical failures of the KMS calls: THROTTLED (quota) or ERROR")
                .tag("kind", kind)
                .register(meters);
    }

    private <T> T call(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return circuit.executeSupplier(action);
        } catch (CallNotPermittedException open) {
            throw new KmsUnavailableException("KMS circuit is open", open);
        } catch (KmsThrottledException quota) {
            throttled.increment();
            throw quota;
        } catch (KmsUnavailableException failure) {
            errors.increment();
            throw failure;
        } finally {
            sample.stop(Timer.builder("txsigner_kms_latency")
                    .description("Latency of the KMS calls")
                    .tag("operation", operation)
                    .register(meters));
        }
    }
}
