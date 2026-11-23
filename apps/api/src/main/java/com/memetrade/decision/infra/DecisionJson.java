package com.memetrade.decision.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.DecisionRequest;
import com.memetrade.decision.domain.Factor;
import com.memetrade.decision.domain.FactorValue;
import com.memetrade.decision.domain.PositionInput;
import com.memetrade.decision.domain.StrategyId;
import com.memetrade.decision.domain.TokenInputs;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The recorded form of a decision (BR4.10, NFR8.1): {@code parameters}, {@code inputs} (every market value used, with
 * source and observation instant, UNKNOWN kept as UNKNOWN), {@code context} (balance, maximum per order, positions with
 * their opening, slot and instant) and {@code factors}, as JSON text with every decimal as a STRING (never a JSON
 * number, never floating point). {@link #read} rebuilds the exact {@link DecisionRequest}, so the decision can be
 * recomputed from what was recorded (BR4.2).
 */
public final class DecisionJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What is recorded with each decision (BR4.10): parameters, inputs, context and factors as JSON text. */
    public record Recorded(String parameters, String inputs, String context, String factors) {}

    public Recorded write(DecisionRequest request, DecisionOutcome outcome) {
        ObjectNode parameters = MAPPER.createObjectNode();
        new TreeMap<>(request.parameters()).forEach((key, value) -> parameters.put(key, value.toPlainString()));
        ArrayNode inputs = MAPPER.createArrayNode();
        request.tokens().forEach(token -> inputs.add(tokenInputs(token)));
        ObjectNode context = MAPPER.createObjectNode();
        context.put("availableBalance", request.availableBalance().toPlainString());
        context.put("maxOrderSize", request.maxOrderSize().toPlainString());
        context.put("slotStart", request.slotStart().toString());
        context.put("now", request.now().toString());
        ArrayNode positions = context.putArray("positions");
        request.positions().forEach(position -> positions.add(position(position)));
        return new Recorded(text(parameters), text(inputs), text(context), text(factors(outcome.factors())));
    }

    /** The factors of a decision as recorded and as the C4 {@code Decision.factors} carries them. */
    public static ArrayNode factors(List<Factor> factors) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Factor factor : factors) {
            ObjectNode node = array.addObject();
            node.set("token", token(factor.token()));
            node.put("name", factor.name());
            if (factor.value() instanceof FactorValue.Known known) {
                node.putObject("value")
                        .put("status", "KNOWN")
                        .put("value", known.value().toPlainString());
            } else {
                node.putObject("value").put("status", "UNKNOWN");
            }
            factor.threshold().ifPresent(threshold -> node.put("threshold", threshold.toPlainString()));
            node.put("satisfied", factor.satisfied());
            factor.label().ifPresent(label -> node.put("label", label));
        }
        return array;
    }

    public DecisionRequest read(StrategyId strategy, String strategyVersion, long limitsVersion, Recorded recorded) {
        JsonNode parameters = tree(recorded.parameters());
        JsonNode inputs = tree(recorded.inputs());
        JsonNode context = tree(recorded.context());
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        parameters.fields().forEachRemaining(entry -> values.put(entry.getKey(), decimal(entry.getValue())));
        List<TokenInputs> tokens = new ArrayList<>();
        inputs.forEach(node -> tokens.add(readTokenInputs(node)));
        List<PositionInput> positions = new ArrayList<>();
        context.get("positions")
                .forEach(node -> positions.add(new PositionInput(
                        readToken(node),
                        decimal(node.get("quantity")),
                        // 261121-paper-real-unico U2 (F-3): a REAL cost unknown at the decision is recorded as null.
                        node.get("costBasis") == null || node.get("costBasis").isNull()
                                ? java.util.Optional.empty()
                                : java.util.Optional.of(decimal(node.get("costBasis"))),
                        Instant.parse(node.get("openedAt").asText()))));
        return new DecisionRequest(
                strategy,
                strategyVersion,
                values,
                limitsVersion,
                decimal(context.get("maxOrderSize")),
                decimal(context.get("availableBalance")),
                tokens,
                positions,
                Instant.parse(context.get("slotStart").asText()),
                Instant.parse(context.get("now").asText()));
    }

    // ----- writing -----

    private static ObjectNode tokenInputs(TokenInputs inputs) {
        ObjectNode node = token(inputs.token());
        ObjectNode window = node.putObject("window");
        window.put("from", inputs.window().from().toString());
        window.put("to", inputs.window().to().toString());
        ArrayNode points = window.putArray("points");
        for (PriceVolumeWindow.Point point : inputs.window().points()) {
            ObjectNode p = points.addObject();
            p.put("bucketStart", point.bucketStart().toString());
            p.set("price", marketValue(point.price()));
            p.set("volume24h", marketValue(point.volume24h()));
        }
        node.set("referencePrice", marketValue(inputs.referencePrice()));
        node.set("decimals", marketValue(inputs.decimals()));
        node.set("assessment", assessment(inputs.assessment()));
        return node;
    }

    private static ObjectNode marketValue(MarketValue value) {
        ObjectNode node = MAPPER.createObjectNode();
        if (value instanceof MarketValue.Known known) {
            node.put("status", "KNOWN");
            node.put("value", known.value().toPlainString());
            node.set("source", source(known.source()));
            node.put("observedAt", known.observedAt().toString());
            node.put("stale", known.stale());
        } else {
            MarketValue.Unknown unknown = (MarketValue.Unknown) value;
            node.put("status", "UNKNOWN");
            node.put("reason", unknown.reason().name());
            unknown.source().ifPresent(source -> node.set("source", source(source)));
        }
        return node;
    }

    private static ObjectNode assessment(AssessmentResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        if (result instanceof AssessmentResult.Available available) {
            node.put("status", "AVAILABLE");
            node.put("score", available.score());
            node.put("band", available.band().name());
            node.put("weightSetVersion", available.weightSetVersion());
        } else {
            node.put("status", "UNAVAILABLE");
            node.put("reason", ((AssessmentResult.Unavailable) result).reason().name());
        }
        return node;
    }

    private static ObjectNode position(PositionInput position) {
        ObjectNode node = token(position.token());
        node.put("quantity", position.quantity().toPlainString());
        position.costBasis()
                .ifPresentOrElse(cost -> node.put("costBasis", cost.toPlainString()), () -> node.putNull("costBasis"));
        node.put("openedAt", position.openedAt().toString());
        return node;
    }

    private static ObjectNode source(SourceRef source) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sourceId", source.sourceId());
        node.put("name", source.name());
        node.put("simulated", source.simulated());
        return node;
    }

    private static ObjectNode token(TokenId token) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("chain", token.chain());
        node.put("mint", token.mint());
        return node;
    }

    // ----- reading -----

    private static TokenInputs readTokenInputs(JsonNode node) {
        TokenId token = readToken(node);
        JsonNode window = node.get("window");
        List<PriceVolumeWindow.Point> points = new ArrayList<>();
        window.get("points")
                .forEach(p -> points.add(new PriceVolumeWindow.Point(
                        Instant.parse(p.get("bucketStart").asText()),
                        readMarketValue(p.get("price")),
                        readMarketValue(p.get("volume24h")))));
        return new TokenInputs(
                token,
                new PriceVolumeWindow(
                        token,
                        Instant.parse(window.get("from").asText()),
                        Instant.parse(window.get("to").asText()),
                        points),
                readMarketValue(node.get("referencePrice")),
                readMarketValue(node.get("decimals")),
                readAssessment(node.get("assessment")));
    }

    private static MarketValue readMarketValue(JsonNode node) {
        if ("KNOWN".equals(node.get("status").asText())) {
            return new MarketValue.Known(
                    decimal(node.get("value")),
                    readSource(node.get("source")),
                    Instant.parse(node.get("observedAt").asText()),
                    node.get("stale").asBoolean());
        }
        return new MarketValue.Unknown(
                UnknownReason.valueOf(node.get("reason").asText()),
                node.has("source") ? Optional.of(readSource(node.get("source"))) : Optional.empty());
    }

    private static AssessmentResult readAssessment(JsonNode node) {
        if ("AVAILABLE".equals(node.get("status").asText())) {
            return new AssessmentResult.Available(
                    node.get("score").asInt(),
                    RiskBand.valueOf(node.get("band").asText()),
                    node.get("weightSetVersion").asInt());
        }
        return new AssessmentResult.Unavailable(
                UnavailableReason.valueOf(node.get("reason").asText()));
    }

    private static SourceRef readSource(JsonNode node) {
        return new SourceRef(
                node.get("sourceId").asText(),
                node.get("name").asText(),
                node.get("simulated").asBoolean());
    }

    private static TokenId readToken(JsonNode node) {
        return new TokenId(node.get("chain").asText(), node.get("mint").asText());
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalStateException("A recorded decimal is always text");
        }
        return new BigDecimal(node.asText());
    }

    private static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A recorded decision could not be read", failure);
        }
    }

    private static String text(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("A decision could not be recorded", failure);
        }
    }
}
