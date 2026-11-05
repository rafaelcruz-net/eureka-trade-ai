package com.memetrade.riskcontrol.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskcontrol.domain.MarketInputs;
import com.memetrade.riskcontrol.domain.ReasonDetail;
import com.memetrade.riskcontrol.domain.RiskFigure;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * The {@code order_rejection.market} document (jsonb). Every decimal is TEXT, never a JSON number, so the scale is
 * kept exactly; a missing value is {@code {"status":"UNKNOWN", "reason": ...}} or the text {@code UNKNOWN}, never zero.
 */
final class MarketInputsCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MarketInputsCodec() {}

    static String write(MarketInputs market) {
        ObjectNode root = JSON.createObjectNode();
        root.set("referencePrice", value(market.referencePrice()));
        root.set("liquidity", value(market.liquidity()));
        root.put("estimatedSlippagePct", market.estimatedSlippagePct().text());
        root.put("orderValue", market.orderValue().text());
        return root.toString();
    }

    static MarketInputs read(String text) {
        try {
            JsonNode root = JSON.readTree(text);
            return new MarketInputs(
                    value(root.path("referencePrice")),
                    value(root.path("liquidity")),
                    figure(root.path("estimatedSlippagePct").asText()),
                    figure(root.path("orderValue").asText()));
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A recorded MarketInputs document cannot be read", unreadable);
        }
    }

    private static ObjectNode value(MarketValue value) {
        ObjectNode node = JSON.createObjectNode();
        switch (value) {
            case MarketValue.Known known -> {
                node.put("status", "KNOWN");
                node.put("value", known.value().toPlainString());
                source(node, known.source());
                node.put("observedAt", known.observedAt().toString());
                node.put("stale", known.stale());
            }
            case MarketValue.Unknown unknown -> {
                node.put("status", "UNKNOWN");
                node.put("reason", unknown.reason().name());
                unknown.source().ifPresent(source -> source(node, source));
            }
        }
        return node;
    }

    private static void source(ObjectNode node, SourceRef source) {
        node.put("sourceId", source.sourceId());
        node.put("sourceName", source.name());
        node.put("simulated", source.simulated());
    }

    private static MarketValue value(JsonNode node) {
        Optional<SourceRef> source = node.hasNonNull("sourceId")
                ? Optional.of(new SourceRef(
                        node.path("sourceId").asText(),
                        node.path("sourceName").asText(),
                        node.path("simulated").asBoolean()))
                : Optional.empty();
        if ("KNOWN".equals(node.path("status").asText())) {
            return new MarketValue.Known(
                    new BigDecimal(node.path("value").asText()),
                    source.orElseThrow(() -> new IllegalStateException("A KNOWN market value has no source")),
                    Instant.parse(node.path("observedAt").asText()),
                    node.path("stale").asBoolean());
        }
        return new MarketValue.Unknown(UnknownReason.valueOf(node.path("reason").asText()), source);
    }

    private static RiskFigure figure(String text) {
        return ReasonDetail.UNKNOWN.equals(text) ? RiskFigure.unknown() : RiskFigure.known(new BigDecimal(text));
    }
}
