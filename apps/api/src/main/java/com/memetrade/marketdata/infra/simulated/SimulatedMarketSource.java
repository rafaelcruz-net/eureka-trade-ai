package com.memetrade.marketdata.infra.simulated;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulated market source (BR3.3, FR4.1): identifies itself as simulated, is fully deterministic for a seed and the
 * injectable clock, never touches the network and always includes the mandatory cases: token #0 without holders,
 * token #1 with liquidity informed as 0, token #2 without creation time, and tokens #3 and #4 sharing a symbol with
 * different mints. All values are exact decimals (never floating point).
 */
public class SimulatedMarketSource implements MarketSourceAdapter {

    public static final SourceRef SOURCE = new SourceRef("simulated", "Fonte simulada", true);
    public static final String CHAIN = "solana";
    static final int MIN_TOKENS = 5;

    private static final String[] SYMBOLS = {
        "BONK", "MOON", "WIF", "PEPE", "PEPE", "CAT", "FROG", "SHIB2", "TURBO", "GIGA"
    };

    private final long seed;
    private final List<TokenInfo> tokens;
    private final List<Instant> creationTimes;
    private final AtomicLong collections = new AtomicLong();

    public SimulatedMarketSource(long seed, int tokenCount, Clock clock) {
        if (tokenCount < MIN_TOKENS) {
            throw new IllegalArgumentException("The simulated source needs at least " + MIN_TOKENS + " tokens");
        }
        this.seed = seed;
        Random random = new Random(seed);
        Instant anchor = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        List<TokenInfo> generated = new ArrayList<>();
        List<Instant> created = new ArrayList<>();
        for (int i = 0; i < tokenCount; i++) {
            byte[] mint = new byte[32];
            random.nextBytes(mint);
            mint[0] = (byte) (mint[0] | 0x01);
            String symbol = SYMBOLS[i % SYMBOLS.length];
            generated.add(new TokenInfo(
                    new TokenId(CHAIN, SyntheticMintEncoder.encode(mint)), symbol, symbol + " (simulado)", 6));
            created.add(i == 2 ? null : anchor.minus(3L + 7L * i + random.nextInt(5), ChronoUnit.MINUTES));
        }
        this.tokens = List.copyOf(generated);
        this.creationTimes = created;
    }

    @Override
    public SourceRef source() {
        return SOURCE;
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        long collection = collections.getAndIncrement();
        Instant observedAt = request.requestedAt().truncatedTo(ChronoUnit.SECONDS);
        List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Random random = new Random(seed * 1_000_003L + collection * 7_919L + i);
            TokenId token = tokens.get(i).token();
            observations.add(known(token, MarketField.PRICE, decimal(1 + random.nextInt(9_999_999), 9), observedAt));
            BigDecimal liquidity = i == 1 ? BigDecimal.ZERO : decimal(100_000L + random.nextInt(50_000_000), 2);
            observations.add(known(token, MarketField.LIQUIDITY, liquidity, observedAt));
            observations.add(known(token, MarketField.VOLUME_24H, decimal(random.nextInt(90_000_000), 2), observedAt));
            if (i == 0) {
                observations.add(new Observation(token, MarketField.HOLDERS, MarketValue.Unknown.notProvided(SOURCE)));
            } else {
                observations.add(
                        known(token, MarketField.HOLDERS, BigDecimal.valueOf(10L + random.nextInt(5_000)), observedAt));
            }
            Instant created = creationTimes.get(i);
            if (created != null) {
                observations.add(
                        known(token, MarketField.CREATED_AT, BigDecimal.valueOf(created.getEpochSecond()), observedAt));
            }
        }
        return new FetchResult(tokens, observations);
    }

    private static Observation known(TokenId token, MarketField field, BigDecimal value, Instant observedAt) {
        return new Observation(token, field, new MarketValue.Known(value, SOURCE, observedAt, false));
    }

    private static BigDecimal decimal(long unscaled, int scale) {
        return BigDecimal.valueOf(unscaled, scale);
    }
}
