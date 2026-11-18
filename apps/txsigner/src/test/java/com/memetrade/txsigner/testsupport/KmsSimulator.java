package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.domain.kms.KeyTags;
import com.memetrade.txsigner.domain.kms.KmsPort;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * In-memory KMS of the unit tests (unit-test-instructions.md "Ferramentas"), with the contract of {@link KmsPort}.
 * Like the real KMS with the IAM policy of infrastructure-specification.md, it requires the four tags at creation,
 * refuses any later tag change and never exposes the private key. Key generation exists ONLY here, in test code.
 */
public final class KmsSimulator implements KmsPort {

    private static final Set<String> REQUIRED_TAGS =
            Set.of(KeyTags.APP, KeyTags.MAIN_WALLET_MAC, KeyTags.CREATION_REQUEST_ID, KeyTags.NETWORK);

    private final Map<String, StoredKey> keys = new LinkedHashMap<>();
    private final byte[] hmacKey;
    private final AtomicInteger signCalls = new AtomicInteger();
    private final AtomicInteger createCalls = new AtomicInteger();
    private volatile boolean unavailable;
    private volatile boolean throttled;

    public KmsSimulator() {
        this.hmacKey = "kms-sim-anchor-hmac-key-for-tests-only".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public synchronized String createKey(KeyTags tags) {
        return createKeyWithTags(tags.asMap());
    }

    /** Lower-level creation used by the proof: any missing or unexpected tag is refused, like the IAM conditions. */
    public synchronized String createKeyWithTags(Map<String, String> tags) {
        failIfUnavailable();
        if (!tags.keySet().equals(REQUIRED_TAGS) || !KeyTags.APP_VALUE.equals(tags.get(KeyTags.APP))) {
            throw new KmsRefusedException("AccessDeniedException: CreateKey requires exactly the four tags");
        }
        createCalls.incrementAndGet();
        String keyRef = "sim-key-" + (keys.size() + 1);
        keys.put(keyRef, new StoredKey(generate(), Map.copyOf(tags), clock.instant()));
        return keyRef;
    }

    /** TagResource after creation: always denied (IAM denial when aws:ResourceTag/app exists). */
    public void tagResource(String keyRef, Map<String, String> tags) {
        requireKey(keyRef);
        throw new KmsRefusedException("AccessDeniedException: TagResource is denied after creation");
    }

    /** UntagResource after creation: always denied. */
    public void untagResource(String keyRef, String tagKey) {
        requireKey(keyRef);
        throw new KmsRefusedException("AccessDeniedException: UntagResource is denied after creation");
    }

    @Override
    public byte[] publicKey(String keyRef) {
        failIfUnavailable();
        byte[] encoded = requireKey(keyRef).keyPair().getPublic().getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    /** DER SubjectPublicKeyInfo, as GetPublicKey of the real KMS returns it. */
    public byte[] publicKeyDer(String keyRef) {
        return requireKey(keyRef).keyPair().getPublic().getEncoded();
    }

    @Override
    public byte[] sign(String keyRef, byte[] message) {
        failIfUnavailable();
        signCalls.incrementAndGet();
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(requireKey(keyRef).keyPair().getPrivate());
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public synchronized List<String> listKeyRefs() {
        failIfUnavailable();
        return new ArrayList<>(keys.keySet());
    }

    @Override
    public Map<String, String> tags(String keyRef) {
        failIfUnavailable();
        return requireKey(keyRef).tags();
    }

    @Override
    public java.time.Instant keyCreatedAt(String keyRef) {
        failIfUnavailable();
        return requireKey(keyRef).createdAt();
    }

    /** Clock of the key creation dates (tests of the orphan keys move it). */
    public void useClock(java.time.Clock clock) {
        this.clock = clock;
    }

    private java.time.Clock clock = java.time.Clock.systemUTC();

    @Override
    public byte[] mac(byte[] data) {
        failIfUnavailable();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public boolean verifyMac(byte[] data, byte[] mac) {
        return MessageDigest.isEqual(mac(data), mac);
    }

    @Override
    public boolean isReachable() {
        return !unavailable;
    }

    /** Makes every later call fail like a KMS outage, until {@link #recover()}. */
    public void goDown() {
        unavailable = true;
    }

    public void recover() {
        unavailable = false;
        throttled = false;
    }

    /** Makes every later call fail like a KMS quota refusal (throttling), until {@link #recover()}. */
    public void throttle() {
        throttled = true;
    }

    public int signCalls() {
        return signCalls.get();
    }

    public int createCalls() {
        return createCalls.get();
    }

    private void failIfUnavailable() {
        if (unavailable) {
            throw new KmsUnavailableException("kms-sim is down");
        }
        if (throttled) {
            throw new com.memetrade.txsigner.domain.kms.KmsThrottledException("kms-sim is throttling");
        }
    }

    private synchronized StoredKey requireKey(String keyRef) {
        StoredKey key = keys.get(keyRef);
        if (key == null) {
            throw new KmsRefusedException("NotFoundException: unknown key");
        }
        return key;
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private record StoredKey(KeyPair keyPair, Map<String, String> tags, java.time.Instant createdAt) {}
}
