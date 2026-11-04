package com.memetrade.scanner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.FieldViolation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR2.7, NFR6.2: every invalid parameter of the list is refused at once, by field. */
@Tag("u4")
class ScannerQueryParserTest {

    @Test
    void withoutParametersTheListIsTheYoungestFirstFiftyAtATime() {
        ScannerQuery query = ScannerQueryParser.parse(Map.of());

        assertThat(query.sort()).isEqualTo(SortField.AGE);
        assertThat(query.order()).isEqualTo(SortOrder.ASC);
        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.filters().anyActive()).isFalse();
    }

    @Test
    void everyFilterIsReadWithItsType() {
        ScannerQuery query = ScannerQueryParser.parse(params(
                "minLiquidity", "1000.50",
                "maxAgeMinutes", "15",
                "minVolume24h", "0",
                "minHolders", "200",
                "maxRiskScore", "69",
                "sourceId", "sim-b",
                "sort", "LIQUIDITY",
                "limit", "100"));

        assertThat(query.filters().minLiquidity())
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1000.5"));
        assertThat(query.filters().maxAgeMinutes()).contains(15L);
        assertThat(query.filters().minHolders())
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("200"));
        assertThat(query.filters().maxRiskScore()).contains(69);
        assertThat(query.filters().sourceId()).contains("sim-b");
        assertThat(query.order()).as("largest first by default").isEqualTo(SortOrder.DESC);
        assertThat(query.limit()).isEqualTo(100);
    }

    @ParameterizedTest(name = "{0}={1} is refused")
    @CsvSource({
        "minLiquidity, -1",
        "minLiquidity, 1e3",
        "minLiquidity, 1.",
        "minVolume24h, abc",
        "minLiquidity, 0.0000000000000000001",
        "maxAgeMinutes, -5",
        "maxAgeMinutes, 1.5",
        "minHolders, 1000000000000",
        "maxRiskScore, 101",
        "limit, 0",
        "limit, 101",
        "sort, PRICE",
        "order, asc",
        "sourceId, SIM_A"
    })
    void anInvalidValueIsRefusedNamingTheField(String name, String value) {
        assertThatThrownBy(() -> ScannerQueryParser.parse(params(name, value)))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.fields())
                        .extracting(FieldViolation::field)
                        .containsExactly(name));
    }

    @Test
    void theBoundsOfTheContractAreAccepted() {
        assertThat(ScannerQueryParser.parse(params("maxRiskScore", "0", "limit", "1"))
                        .limit())
                .isEqualTo(1);
        assertThat(ScannerQueryParser.parse(params("maxRiskScore", "100"))
                        .filters()
                        .maxRiskScore())
                .contains(100);
    }

    @Test
    void unknownAndRepeatedParametersAreRefusedAndEveryErrorIsListed() {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        parameters.put("color", List.of("red"));
        parameters.put("minHolders", List.of("1", "2"));
        parameters.put("limit", List.of("0"));

        assertThatThrownBy(() -> ScannerQueryParser.parse(parameters))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.fields())
                        .extracting(FieldViolation::field)
                        .contains("color", "minHolders", "limit"));
    }

    @Test
    void aTenantIdIsIgnoredAndABlankCursorIsTheFirstPage() {
        ScannerQuery query = ScannerQueryParser.parse(params("tenantId", "x", "cursor", ""));

        assertThat(query.cursor()).isEmpty();
    }

    @Test
    void theFiltersDigestFollowsTheFiltersNotTheirSpelling() {
        String a = ScannerQueryParser.parse(params("minLiquidity", "10.50")).filtersDigest();
        String b = ScannerQueryParser.parse(params("minLiquidity", "10.5")).filtersDigest();
        String c = ScannerQueryParser.parse(params("minLiquidity", "10.6")).filtersDigest();

        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }

    private static Map<String, List<String>> params(String... pairs) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            parameters.put(pairs[i], List.of(pairs[i + 1]));
        }
        return parameters;
    }
}
