package com.memetrade.audit.infra;

import java.util.Locale;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;

/**
 * u2-retention (NFR6.1): the actuator always masks the HMAC key of the audit wallet references, by property name and by
 * variable name, whatever {@code show-values} says. The {@code env} and {@code configprops} endpoints are not exposed
 * today; this keeps the key masked if one of them is exposed later.
 */
public class WalletHmacKeySanitizer implements SanitizingFunction {

    @Override
    public SanitizableData apply(SanitizableData data) {
        String key = data.getKey();
        if (key == null) {
            return data;
        }
        // memetrade.audit.wallet-hmac-key and MEMETRADE_AUDIT_WALLET_HMAC_KEY normalise to the same name.
        String normalized = key.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
        return normalized.endsWith("wallet.hmac.key") ? data.withValue(SanitizableData.SANITIZED_VALUE) : data;
    }
}
