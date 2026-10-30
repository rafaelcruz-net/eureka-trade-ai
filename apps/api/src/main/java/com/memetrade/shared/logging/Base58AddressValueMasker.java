package com.memetrade.shared.logging;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

/** Plugs {@link LogMasking} into the JSON log encoder so every string value is masked before it is written. */
public class Base58AddressValueMasker implements ValueMasker {

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (value instanceof CharSequence text) {
            String original = text.toString();
            String masked = LogMasking.maskBase58(original);
            return masked.equals(original) ? null : masked;
        }
        return null;
    }
}
