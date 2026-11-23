package com.memetrade.audit.infra;

import com.memetrade.shared.wallet.WalletAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The reference of a wallet in the audit trail (u2-retention, BR2.1, BR2.2, BR2.4; security-design.md NFR1.1, NFR6.1):
 * the HMAC-SHA-256 of the address with the key of {@code memetrade.audit.wallet-hmac-key}, in lower-case hex of 64
 * characters. The same wallet gives the same HMAC while the key does not change, so events can be correlated without
 * keeping the address; without the key the HMAC does not identify the person.
 *
 * <ul>
 *   <li>The key is checked when the api or the worker starts: absent, or shorter than 32 bytes in UTF-8, the process
 *       stops naming the property and the variable, never the value.
 *   <li>The key lives only in a {@link SecretKeySpec} inside this object; no getter, no {@code toString}, and the
 *       actuator masks it ({@link WalletHmacKeySanitizer}).
 *   <li>Each thread reuses its own {@link Mac}, cloned from the initial one (NFR3.3): no key set-up per event.
 *   <li>Only a valid wallet address is ever hashed; any other text is refused without being echoed (BR2.15).
 * </ul>
 */
public final class WalletHasher {

    public static final String PROPERTY = "memetrade.audit.wallet-hmac-key";
    public static final String VARIABLE = "MEMETRADE_AUDIT_WALLET_HMAC_KEY";
    public static final int MIN_KEY_BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final Mac prototype;
    private final ThreadLocal<Mac> macs;

    public WalletHasher(String key) {
        byte[] bytes = key == null || key.isBlank() ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(PROPERTY + " (" + VARIABLE + ") is required and must have at least "
                    + MIN_KEY_BYTES + " bytes in UTF-8 (BR2.4); the api and the worker do not start without it");
        }
        this.prototype = newMac(new SecretKeySpec(bytes, ALGORITHM));
        this.macs = ThreadLocal.withInitial(this::copyOfPrototype);
    }

    /**
     * The HMAC of {@code walletAddress}.
     *
     * @throws IllegalArgumentException when the text is not a valid wallet address (the text is not echoed)
     */
    public String hmac(String walletAddress) {
        if (!WalletAddress.isValid(walletAddress)) {
            throw new IllegalArgumentException("Only a valid wallet address is hashed for the audit trail (BR2.15)");
        }
        Mac mac = macs.get();
        return HexFormat.of().formatHex(mac.doFinal(walletAddress.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 261121-paper-real-unico U2 (NFR3.9, D-NFR2, security-design.md R-06): the HMAC of a REAL transaction signature,
     * with the same key and algorithm as {@link #hmac}, over the message {@value #TX_SIGNATURE_LABEL} followed by the
     * signature in UTF-8. The label keeps the two families of HMAC apart; the HMAC of the wallets stays without it, so
     * the values already recorded stay valid.
     *
     * @throws IllegalArgumentException when the text is not a Base58 signature of 64 bytes (the text is not echoed)
     */
    public String hmacOfTxSignature(String txSignature) {
        if (!com.memetrade.audit.domain.AuditEventValidator.isTxSignature(txSignature)) {
            throw new IllegalArgumentException(
                    "Only a Base58 transaction signature of 64 bytes is hashed for the audit trail (NFR3.9)");
        }
        Mac mac = macs.get();
        return HexFormat.of()
                .formatHex(mac.doFinal((TX_SIGNATURE_LABEL + txSignature).getBytes(StandardCharsets.UTF_8)));
    }

    /** The domain label of the HMAC of a transaction signature (security-design.md R-06). */
    public static final String TX_SIGNATURE_LABEL = "real-tx-signature:";

    /** Never prints the key. */
    @Override
    public String toString() {
        return "WalletHasher[algorithm=" + ALGORITHM + ", key=******]";
    }

    private Mac copyOfPrototype() {
        try {
            synchronized (prototype) {
                return (Mac) prototype.clone();
            }
        } catch (CloneNotSupportedException notCloneable) {
            throw new IllegalStateException("The " + ALGORITHM + " provider must support cloning", notCloneable);
        }
    }

    private static Mac newMac(SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(ALGORITHM + " is required by the Java platform", missing);
        }
    }
}
