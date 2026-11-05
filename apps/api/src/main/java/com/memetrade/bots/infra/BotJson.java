package com.memetrade.bots.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.bots.domain.BotLimits;
import com.memetrade.bots.domain.TokenSelection;
import com.memetrade.decision.domain.SelectionRule;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSONB columns of {@code bot}: parameters, token selection and draft limits. Every decimal is stored as TEXT, so
 * the exact value and its scale come back unchanged (never a JSON number, never floating point).
 */
final class BotJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BotJson() {}

    static String parameters(Map<String, BigDecimal> parameters) {
        ObjectNode node = MAPPER.createObjectNode();
        parameters.forEach((key, value) -> node.put(key, value.toPlainString()));
        return write(node);
    }

    static Map<String, BigDecimal> parameters(String json) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        if (json == null) {
            return values;
        }
        read(json).fields().forEachRemaining(entry -> values.put(entry.getKey(), decimal(entry.getValue())));
        return values;
    }

    static String selection(TokenSelection selection) {
        ObjectNode node = MAPPER.createObjectNode();
        var tokens = node.putArray("tokens");
        selection
                .tokens()
                .forEach(token -> tokens.addObject().put("chain", token.chain()).put("mint", token.mint()));
        var rules = node.putArray("rules");
        selection.rules().forEach(rule -> rules.addObject()
                .put("field", rule.field().name())
                .put("operator", rule.operator().name())
                .put("value", rule.value().toPlainString()));
        return write(node);
    }

    static TokenSelection selection(String json) {
        if (json == null) {
            return TokenSelection.empty();
        }
        JsonNode node = read(json);
        List<TokenId> tokens = new ArrayList<>();
        node.path("tokens")
                .forEach(t -> tokens.add(
                        new TokenId(t.get("chain").asText(), t.get("mint").asText())));
        List<SelectionRule> rules = new ArrayList<>();
        node.path("rules")
                .forEach(r -> rules.add(new SelectionRule(
                        SelectionRule.Field.valueOf(r.get("field").asText()),
                        SelectionRule.Operator.valueOf(r.get("operator").asText()),
                        decimal(r.get("value")))));
        return new TokenSelection(tokens, rules);
    }

    static String limits(BotLimits limits) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("maxOrderSize", limits.maxOrderSize().toPlainString());
        node.put("maxTokenExposure", limits.maxTokenExposure().toPlainString());
        node.put("maxTotalExposure", limits.maxTotalExposure().toPlainString());
        node.put("maxDailyLoss", limits.maxDailyLoss().toPlainString());
        node.put("maxDrawdownPct", limits.maxDrawdownPct().toPlainString());
        node.put("maxSlippagePct", limits.maxSlippagePct().toPlainString());
        node.put("minLiquidity", limits.minLiquidity().toPlainString());
        node.put("minIntervalSeconds", limits.minIntervalSeconds());
        return write(node);
    }

    static BotLimits limits(String json) {
        JsonNode node = read(json);
        return new BotLimits(
                decimal(node.get("maxOrderSize")),
                decimal(node.get("maxTokenExposure")),
                decimal(node.get("maxTotalExposure")),
                decimal(node.get("maxDailyLoss")),
                decimal(node.get("maxDrawdownPct")),
                decimal(node.get("maxSlippagePct")),
                decimal(node.get("minLiquidity")),
                node.get("minIntervalSeconds").asLong());
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalStateException("A stored decimal of a bot is always text");
        }
        return new BigDecimal(node.asText());
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A stored column of a bot could not be read", failure);
        }
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A column of a bot could not be written", failure);
        }
    }
}
