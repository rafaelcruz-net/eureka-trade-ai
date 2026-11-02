package com.memetrade.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replays a recorded fixture of a simulated source ({@code src/test/resources/fixtures/market/<id>.json}), with every
 * observation stamped at the request time. Synthetic mints only (never real); no network access.
 */
public final class FixtureMarketSource implements MarketSourceAdapter {

    public static final String CHAIN = "solana";
    public static final String COMMON_A = "FxCommonA1111111111111111111111111111111111";
    public static final String COMMON_B = "FxCommonB1111111111111111111111111111111111";
    public static final String ONLY_A = "FxUniqAaa1111111111111111111111111111111111";
    public static final String ONLY_A_NO_AGE = "FxUniqAbb1111111111111111111111111111111111";
    public static final String ONLY_B = "FxUniqBbb1111111111111111111111111111111111";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceRef source;
    private final JsonNode fixture;

    private FixtureMarketSource(JsonNode fixture) {
        this(
                fixture,
                new SourceRef(
                        fixture.get("sourceId").asText(), fixture.get("name").asText(), true));
    }

    private FixtureMarketSource(JsonNode fixture, SourceRef source) {
        this.fixture = fixture;
        this.source = source;
    }

    /**
     * The same recorded data answered under another source identity, so integration tests use their own source rows
     * and never the shared sim-a and sim-b.
     */
    public FixtureMarketSource as(String sourceId) {
        return new FixtureMarketSource(fixture, new SourceRef(sourceId, "Fixture " + sourceId, true));
    }

    /** Loads {@code fixtures/market/<sourceId>.json} from the test classpath. */
    public static FixtureMarketSource load(String sourceId) {
        try (InputStream in = FixtureMarketSource.class.getResourceAsStream("/fixtures/market/" + sourceId + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture for source " + sourceId);
            }
            return new FixtureMarketSource(JSON.readTree(in));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    public static TokenId token(String mint) {
        return new TokenId(CHAIN, mint);
    }

    @Override
    public SourceRef source() {
        return source;
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        Instant at = request.requestedAt().truncatedTo(ChronoUnit.SECONDS);
        List<TokenInfo> tokens = new ArrayList<>();
        for (JsonNode token : fixture.get("tokens")) {
            tokens.add(new TokenInfo(
                    token(token.get("mint").asText()),
                    token.get("symbol").asText(),
                    token.get("name").asText(),
                    token.get("decimals").asInt()));
        }
        List<Observation> observations = new ArrayList<>();
        for (JsonNode observation : fixture.get("observations")) {
            observations.add(new Observation(
                    token(observation.get("mint").asText()),
                    MarketField.valueOf(observation.get("field").asText()),
                    value(observation, at)));
        }
        return new FetchResult(tokens, observations);
    }

    private MarketValue value(JsonNode observation, Instant at) {
        if (observation.has("unknown")) {
            return new MarketValue.Unknown(
                    UnknownReason.valueOf(observation.get("unknown").asText()), Optional.of(source));
        }
        if (observation.has("createdMinutesAgo")) {
            long epoch = at.minus(observation.get("createdMinutesAgo").asLong(), ChronoUnit.MINUTES)
                    .getEpochSecond();
            return new MarketValue.Known(BigDecimal.valueOf(epoch), source, at, false);
        }
        return new MarketValue.Known(new BigDecimal(observation.get("value").asText()), source, at, false);
    }
}
