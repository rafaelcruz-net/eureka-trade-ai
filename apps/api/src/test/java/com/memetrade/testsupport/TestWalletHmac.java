package com.memetrade.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The HMAC-SHA-256 the audit trail must hold for a test wallet (u2-retention, BR2.1, BR2.2), computed here with the
 * standard JCA {@link Mac}, independently of the production {@code WalletHasher}, and with the fake test key read from
 * the single place it lives ({@code config/application.yml} of the test classpath).
 */
public final class TestWalletHmac {

    public static final String KEY_PROPERTY = "memetrade.audit.wallet-hmac-key";

    private TestWalletHmac() {}

    /** The fake key of the tests (never a production key). */
    public static String testKey() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("config/application.yml"));
        Properties properties = yaml.getObject();
        String key = properties == null ? null : properties.getProperty(KEY_PROPERTY);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(KEY_PROPERTY + " is missing from config/application.yml of the tests");
        }
        return key;
    }

    /** Lower-case hex HMAC-SHA-256 of {@code walletAddress} with {@code key}. */
    public static String of(String key, String walletAddress) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(walletAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException("HmacSHA256 is required by the Java platform", missing);
        }
    }

    /** The HMAC of {@code walletAddress} with the test key. */
    public static String of(String walletAddress) {
        return of(testKey(), walletAddress);
    }
}
