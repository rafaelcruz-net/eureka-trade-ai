package com.memetrade.scanner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketListingQuery.ListingPosition;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class ScannerCursorTest {

    private final ScannerCursor cursor = new ScannerCursor(
            Instant.parse("2026-10-28T12:00:00Z"),
            new ListingPosition(0, -1_700_000_000_000_000L, "MintA", "solana"),
            42);

    @Test
    void roundTripsCutoffGroupKeyMintAndTotal() {
        assertThat(ScannerCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void theCursorIsOpaqueUrlSafeText() {
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void malformedOrTamperedCursorsAreValidationFailed() {
        for (String bad : new String[] {
            "!!!",
            "e30",
            encode("{\"c\":\"x\"}"),
            encode("{\"c\":\"2026-10-28T12:00:00Z\",\"g\":7,\"k\":0,\"m\":\"a\",\"ch\":\"b\",\"n\":1}")
        }) {
            assertThatThrownBy(() -> ScannerCursor.decode(bad))
                    .as(bad)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.fields()).extracting("field").containsExactly("cursor");
                    });
        }
    }

    @Test
    void aNegativeTotalIsRefused() {
        String json = "{\"c\":\"2026-10-28T12:00:00Z\",\"g\":0,\"k\":0,\"m\":\"a\",\"ch\":\"b\",\"n\":-1}";
        assertThatThrownBy(() -> ScannerCursor.decode(encode(json))).isInstanceOf(ApiException.class);
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
