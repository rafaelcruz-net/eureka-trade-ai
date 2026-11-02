package com.memetrade.riskassessment.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.riskassessment.api.FactorId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Summary of what an assessment depends on (entities.md, {@code inputsFingerprint}): the weight version and, for each
 * factor, the value, source and observation time of its input, or its unknown reason; plus the extra basis values
 * (the current price and the 1-hour sample of PRICE_CHANGE_1H). The stale flag never enters: the same summary always
 * gives the same result (BR3.7).
 */
public final class InputsFingerprint {

    private InputsFingerprint() {}

    public static String of(int version, Map<FactorId, MarketValue> inputs, List<MarketValue> basis) {
        StringBuilder text = new StringBuilder("v=").append(version);
        for (FactorId factor : FactorId.values()) {
            text.append('|').append(factor.name()).append('=').append(describe(inputs.get(factor)));
        }
        for (MarketValue value : basis) {
            text.append("|basis=").append(describe(value));
        }
        return sha256(text.toString());
    }

    private static String describe(MarketValue value) {
        if (value == null) {
            return "absent";
        }
        return switch (value) {
            case MarketValue.Known k ->
                "K:" + k.value().stripTrailingZeros().toPlainString() + "@"
                        + k.source().sourceId() + "@" + k.observedAt();
            case MarketValue.Unknown u ->
                "U:" + u.reason() + "@" + u.source().map(s -> s.sourceId()).orElse("-");
        };
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", unavailable);
        }
    }
}
