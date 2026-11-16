package com.memetrade.botwallet.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.botwallet.domain.TokenDiff;
import java.util.ArrayList;
import java.util.List;

/**
 * 261113-trading-real-bot U5: the token diffs of a run and of a divergence as JSON ({@code [{mint, chain, ledger,
 * diff}]}); the amounts go as integer text (base units, never floating point) and are read back exactly.
 */
final class TokenDiffsJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TokenDiffsJson() {}

    static String write(List<TokenDiff> diffs) {
        ArrayNode array = JSON.createArrayNode();
        for (TokenDiff diff : diffs) {
            ObjectNode node = array.addObject();
            node.put("mint", diff.mint());
            node.put("chain", Long.toString(diff.chain()));
            node.put("ledger", Long.toString(diff.ledger()));
            node.put("diff", Long.toString(diff.diff()));
        }
        return array.toString();
    }

    static List<TokenDiff> read(String json) {
        List<TokenDiff> diffs = new ArrayList<>();
        for (JsonNode node : tree(json)) {
            diffs.add(new TokenDiff(
                    node.path("mint").asText(),
                    Long.parseLong(node.path("chain").asText("0")),
                    Long.parseLong(node.path("ledger").asText("0"))));
        }
        return diffs;
    }

    static String writeMap(java.util.Map<String, String> values) {
        ObjectNode node = JSON.createObjectNode();
        values.forEach(node::put);
        return node.toString();
    }

    static java.util.Map<String, String> readMap(String json) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        tree(json)
                .fields()
                .forEachRemaining(
                        entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    static String writeStrings(List<String> values) {
        ArrayNode array = JSON.createArrayNode();
        values.forEach(array::add);
        return array.toString();
    }

    static List<String> readStrings(String json) {
        List<String> values = new ArrayList<>();
        tree(json).forEach(node -> values.add(node.asText()));
        return values;
    }

    private static JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            return JSON.createArrayNode();
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("A JSON column of the REAL operations is not valid", corrupt);
        }
    }
}
