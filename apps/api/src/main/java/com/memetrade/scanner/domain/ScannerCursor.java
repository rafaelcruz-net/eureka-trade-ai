package com.memetrade.scanner.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.marketdata.api.MarketListingQuery.ListingPosition;
import com.memetrade.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Opaque cursor (BR3.6): the cutoff instant of the first page, the group (creation known or unknown), the order key
 * derived from the creation instant, the mint (and chain) of the last item, and the total N of the first page.
 */
public record ScannerCursor(Instant cutoff, ListingPosition last, long total) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public String encode() {
        ObjectNode node = JSON.createObjectNode()
                .put("c", cutoff.toString())
                .put("g", last.group())
                .put("k", last.sortKey())
                .put("m", last.mint())
                .put("ch", last.chain())
                .put("n", total);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static ScannerCursor decode(String text) {
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(text));
            int group = node.get("g").asInt();
            long total = node.get("n").asLong();
            if ((group != 0 && group != 1)
                    || total < 0
                    || !node.get("m").isTextual()
                    || !node.get("ch").isTextual()) {
                throw invalid();
            }
            return new ScannerCursor(
                    Instant.parse(node.get("c").asText()),
                    new ListingPosition(
                            group,
                            node.get("k").asLong(),
                            node.get("m").asText(),
                            node.get("ch").asText()),
                    total);
        } catch (ApiException rethrown) {
            throw rethrown;
        } catch (RuntimeException | java.io.IOException malformed) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.validation("cursor", "Cursor is not valid; start again from the first page.");
    }
}
