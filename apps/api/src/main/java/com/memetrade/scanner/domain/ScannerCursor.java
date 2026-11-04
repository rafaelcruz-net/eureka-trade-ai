package com.memetrade.scanner.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.marketdata.api.MarketListingQuery.Position;
import com.memetrade.marketdata.api.MarketListingQuery.SortField;
import com.memetrade.marketdata.api.MarketListingQuery.SortOrder;
import com.memetrade.shared.error.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Opaque cursor of the list (entities.md, ScannerSnapshotCursor): the photo instant, the summary of the filters, the
 * order, the position of the last item (sort value, mint, chain) and how many items were shown before the next page.
 * A cursor that cannot be read, or that belongs to other filters or another order, is refused (BR2.7).
 */
public record ScannerCursor(
        Instant snapshotAt, String filtersDigest, SortField sort, SortOrder order, Position last, long offsetShown) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 2;

    public String encode() {
        ObjectNode node = JSON.createObjectNode()
                .put("v", VERSION)
                .put("s", snapshotAt.toString())
                .put("d", filtersDigest)
                .put("so", sort.name())
                .put("o", order.name())
                .put("m", last.mint())
                .put("c", last.chain())
                .put("n", offsetShown);
        last.sortValue().ifPresentOrElse(value -> node.put("k", value.toPlainString()), () -> node.putNull("k"));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static ScannerCursor decode(String text) {
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(text));
            if (node.get("v").asInt() != VERSION
                    || !node.get("m").isTextual()
                    || !node.get("c").isTextual()) {
                throw invalid();
            }
            long offset = node.get("n").asLong();
            if (offset < 0) {
                throw invalid();
            }
            JsonNode value = node.get("k");
            Optional<BigDecimal> sortValue =
                    value == null || value.isNull() ? Optional.empty() : Optional.of(new BigDecimal(value.asText()));
            return new ScannerCursor(
                    Instant.parse(node.get("s").asText()),
                    node.get("d").asText(),
                    SortField.valueOf(node.get("so").asText()),
                    SortOrder.valueOf(node.get("o").asText()),
                    new Position(
                            sortValue, node.get("m").asText(), node.get("c").asText()),
                    offset);
        } catch (ApiException rethrown) {
            throw rethrown;
        } catch (RuntimeException | java.io.IOException malformed) {
            throw invalid();
        }
    }

    /** The cursor must come from the same filters and the same order as the request it continues. */
    public ScannerCursor requireFor(ScannerQuery query) {
        if (!filtersDigest.equals(query.filtersDigest()) || sort != query.sort() || order != query.order()) {
            throw invalid();
        }
        return this;
    }

    static ApiException invalid() {
        return ApiException.validation("cursor", "Cursor is not valid; start again from the first page.");
    }
}
