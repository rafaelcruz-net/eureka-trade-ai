package com.memetrade.scanner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketListingQuery.Filters;
import com.memetrade.marketdata.api.MarketListingQuery.Position;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.shared.error.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The opaque cursor of the list (BR2.6, BR2.7). */
@Tag("u1")
class ScannerCursorTest {

    private static final ScannerQuery QUERY =
            new ScannerQuery(Filters.none(), SortField.LIQUIDITY, SortOrder.DESC, Optional.empty(), 50);
    private static final ScannerCursor CURSOR = new ScannerCursor(
            Instant.parse("2026-10-28T12:00:00Z"),
            QUERY.filtersDigest(),
            SortField.LIQUIDITY,
            SortOrder.DESC,
            new Position(Optional.of(new BigDecimal("1234.50")), "MintA", "solana"),
            50);

    @Test
    void roundTripsPhotoFiltersOrderPositionAndOffset() {
        assertThat(ScannerCursor.decode(CURSOR.encode())).isEqualTo(CURSOR);
        ScannerCursor unknownLast = new ScannerCursor(
                CURSOR.snapshotAt(), "d", SortField.AGE, SortOrder.ASC, new Position(Optional.empty(), "m", "c"), 0);
        assertThat(ScannerCursor.decode(unknownLast.encode())).isEqualTo(unknownLast);
    }

    @Test
    void theCursorIsOpaqueUrlSafeText() {
        assertThat(CURSOR.encode()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void malformedOrTamperedCursorsAreValidationFailed() {
        String json = "{\"v\":1,\"s\":\"x\"}";
        for (String bad : new String[] {
            "not base64 !", "e30", Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))
        }) {
            assertThatThrownBy(() -> ScannerCursor.decode(bad))
                    .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(
                                    refused.fields().getFirst().field())
                            .isEqualTo("cursor"));
        }
    }

    @Test
    void aNegativeOffsetIsRefused() {
        String encoded =
                new ScannerCursor(CURSOR.snapshotAt(), "d", SortField.AGE, SortOrder.ASC, CURSOR.last(), -1).encode();

        assertThatThrownBy(() -> ScannerCursor.decode(encoded)).isInstanceOf(ApiException.class);
    }

    @Test
    @Tag("u4")
    void aCursorContinuesOnlyTheSameQuery() {
        assertThat(CURSOR.requireFor(QUERY)).isEqualTo(CURSOR);
        assertThatThrownBy(() -> CURSOR.requireFor(
                        new ScannerQuery(Filters.none(), SortField.LIQUIDITY, SortOrder.ASC, Optional.empty(), 50)))
                .isInstanceOf(ApiException.class);
    }
}
