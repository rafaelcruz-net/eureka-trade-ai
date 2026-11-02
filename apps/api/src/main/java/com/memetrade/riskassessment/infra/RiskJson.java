package com.memetrade.riskassessment.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.CurvePoint;
import com.memetrade.riskassessment.api.FactorDefinition;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.domain.FactorResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSONB of the RiskAssessment tables: decimals always as TEXT, never JSON numbers (no floating point anywhere).
 * Factor inputs keep value, source and observation time (NFR8.1); the stale flag is never stored (BR3.6).
 */
final class RiskJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RiskJson() {}

    static String writeDefinitions(List<FactorDefinition> factors) {
        ArrayNode array = JSON.createArrayNode();
        for (FactorDefinition factor : factors) {
            ObjectNode node = array.addObject()
                    .put("factorId", factor.factorId().name())
                    .put("weight", factor.weight().toPlainString());
            ArrayNode curve = node.putArray("curve");
            factor.curve().forEach(point -> curve.addObject()
                    .put("input", point.input().toPlainString())
                    .put("risk", point.risk()));
        }
        return array.toString();
    }

    static List<FactorDefinition> readDefinitions(String json) {
        List<FactorDefinition> factors = new ArrayList<>();
        for (JsonNode node : tree(json)) {
            List<CurvePoint> curve = new ArrayList<>();
            node.get("curve")
                    .forEach(point -> curve.add(new CurvePoint(
                            new BigDecimal(point.get("input").asText()),
                            point.get("risk").asInt())));
            factors.add(new FactorDefinition(
                    FactorId.valueOf(node.get("factorId").asText()),
                    new BigDecimal(node.get("weight").asText()),
                    curve));
        }
        return factors;
    }

    static String writeResults(List<FactorResult> factors) {
        ArrayNode array = JSON.createArrayNode();
        for (FactorResult factor : factors) {
            ObjectNode node = array.addObject()
                    .put("factorId", factor.factorId().name())
                    .put("weight", factor.weight().toPlainString())
                    .put("factorRisk", factor.factorRisk());
            writeValue(node.putObject("input"), factor.input());
            if (factor.contribution().isPresent()) {
                node.put("contribution", factor.contribution().get().toPlainString());
            } else {
                node.put("contribution", "UNKNOWN");
            }
        }
        return array.toString();
    }

    static List<FactorResult> readResults(String json) {
        List<FactorResult> factors = new ArrayList<>();
        for (JsonNode node : tree(json)) {
            String contribution = node.get("contribution").asText();
            factors.add(new FactorResult(
                    FactorId.valueOf(node.get("factorId").asText()),
                    new BigDecimal(node.get("weight").asText()),
                    readValue(node.get("input")),
                    node.get("factorRisk").asInt(),
                    "UNKNOWN".equals(contribution) ? Optional.empty() : Optional.of(new BigDecimal(contribution))));
        }
        return factors;
    }

    private static void writeValue(ObjectNode node, MarketValue value) {
        switch (value) {
            case MarketValue.Known known -> {
                node.put("status", "KNOWN").put("value", known.value().toPlainString());
                writeSource(node, known.source());
                node.put("observedAt", known.observedAt().toString());
            }
            case MarketValue.Unknown unknown -> {
                node.put("status", "UNKNOWN").put("reason", unknown.reason().name());
                unknown.source().ifPresent(source -> writeSource(node, source));
            }
        }
    }

    private static MarketValue readValue(JsonNode node) {
        Optional<SourceRef> source = node.has("sourceId")
                ? Optional.of(new SourceRef(
                        node.get("sourceId").asText(),
                        node.get("sourceName").asText(),
                        node.get("simulated").asBoolean()))
                : Optional.empty();
        if ("KNOWN".equals(node.get("status").asText())) {
            return new MarketValue.Known(
                    new BigDecimal(node.get("value").asText()),
                    source.orElseThrow(),
                    Instant.parse(node.get("observedAt").asText()),
                    false);
        }
        return new MarketValue.Unknown(UnknownReason.valueOf(node.get("reason").asText()), source);
    }

    private static void writeSource(ObjectNode node, SourceRef source) {
        node.put("sourceId", source.sourceId()).put("sourceName", source.name()).put("simulated", source.simulated());
    }

    private static JsonNode tree(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Stored risk JSON is not readable", corrupt);
        }
    }
}
