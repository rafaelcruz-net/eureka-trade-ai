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
import java.util.Map;
import java.util.Random;

/**
 * Simulated market source (FR4.1, C19): identifies itself as simulated, is fully deterministic for a seed and the
 * request time, never touches the network and only formats random bytes as SYNTHETIC mints.
 *
 * <p>U4: two instances, {@code sim-a} and {@code sim-b}. The first {@value #COMMON_TOKENS} tokens (or all, when fewer)
 * are the same in both (same chain + mint, generated from the shared seed) with the same base values; on those, sim-b
 * differs on purpose: every common token {@code k} with {@code k % 10 == 3} has a price 10% higher (beyond the 5%
 * tolerance), {@code k % 10 == 6} a liquidity 2% higher (within it) and {@code k % 10 == 7} no holders. The other
 * tokens belong to one source only. Mandatory cases of each source (kept from U1): token #0 without holders, token #1
 * with liquidity informed as 0, token #2 without creation time, tokens #3 and #4 sharing a symbol with different mints.
 * All values are exact decimals (never floating point).
 */
public class SimulatedMarketSource implements MarketSourceAdapter {

    public static final SourceRef SOURCE_A = new SourceRef("sim-a", "Fonte simulada A", true);
    public static final SourceRef SOURCE_B = new SourceRef("sim-b", "Fonte simulada B", true);

    /** The source of the U1 constructor; kept as sim-a so the U1 behaviour is unchanged. */
    public static final SourceRef SOURCE = SOURCE_A;

    public static final Map<String, SourceRef> KNOWN = Map.of("sim-a", SOURCE_A, "sim-b", SOURCE_B);
    public static final String CHAIN = "solana";
    public static final int COMMON_TOKENS = 50;
    static final int MIN_TOKENS = 5;

    private static final String[] SYMBOLS = {
        "BONK", "MOON", "WIF", "PEPE", "PEPE", "CAT", "FROG", "SHIB2", "TURBO", "GIGA"
    };
    private static final BigDecimal DIVERGENT_PRICE = new BigDecimal("1.10");
    private static final BigDecimal CLOSE_LIQUIDITY = new BigDecimal("1.02");

    private final SourceRef source;
    private final long seed;
    private final boolean isB;
    private final List<TokenInfo> tokens;
    private final List<Instant> creationTimes;

    /** U1 constructor: the sim-a source. */
    public SimulatedMarketSource(long seed, int tokenCount, Clock clock) {
        this(SOURCE_A, seed, tokenCount, clock);
    }

    public SimulatedMarketSource(SourceRef source, long seed, int tokenCount, Clock clock) {
        if (tokenCount < MIN_TOKENS) {
            throw new IllegalArgumentException("The simulated source needs at least " + MIN_TOKENS + " tokens");
        }
        if (!KNOWN.containsValue(source)) {
            throw new IllegalArgumentException("Unknown simulated source " + source.sourceId());
        }
        this.source = source;
        this.seed = seed;
        this.isB = SOURCE_B.equals(source);
        Instant anchor = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Random common = new Random(seed);
        Random own = new Random(seed * 31 + source.sourceId().hashCode());
        List<TokenInfo> generated = new ArrayList<>();
        List<Instant> created = new ArrayList<>();
        int shared = Math.min(COMMON_TOKENS, tokenCount);
        for (int i = 0; i < tokenCount; i++) {
            Random random = i < shared ? common : own;
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

    public static SimulatedMarketSource forSource(String sourceId, long seed, int tokenCount, Clock clock) {
        SourceRef ref = KNOWN.get(sourceId);
        if (ref == null) {
            throw new IllegalArgumentException("Unknown simulated source " + sourceId);
        }
        return new SimulatedMarketSource(ref, seed, tokenCount, clock);
    }

    @Override
    public SourceRef source() {
        return source;
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        Instant observedAt = request.requestedAt().truncatedTo(ChronoUnit.SECONDS);
        List<Observation> observations = new ArrayList<>();
        int shared = Math.min(COMMON_TOKENS, tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            boolean isCommon = i < shared;
            long salt = isCommon ? 0 : source.sourceId().hashCode();
            Random random = new Random(seed * 1_000_003L + observedAt.getEpochSecond() * 7_919L + i * 31L + salt);
            observations.addAll(observe(i, tokens.get(i).token(), random, observedAt, isCommon && isB));
        }
        return new FetchResult(tokens, observations);
    }

    private List<Observation> observe(int i, TokenId token, Random random, Instant at, boolean divergeAsB) {
        List<Observation> out = new ArrayList<>();
        BigDecimal price = decimal(1 + random.nextInt(9_999_999), 9);
        BigDecimal liquidity = i == 1 ? BigDecimal.ZERO : decimal(random.nextInt(20_000_000), 2);
        BigDecimal volume = decimal(random.nextInt(200_000_000), 2);
        BigDecimal holders = BigDecimal.valueOf(10L + random.nextInt(6_000));
        if (divergeAsB && i % 10 == 3) {
            price = price.multiply(DIVERGENT_PRICE);
        }
        if (divergeAsB && i % 10 == 6) {
            liquidity = liquidity.multiply(CLOSE_LIQUIDITY).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        out.add(known(token, MarketField.PRICE, price, at));
        out.add(known(token, MarketField.LIQUIDITY, liquidity, at));
        out.add(known(token, MarketField.VOLUME_24H, volume, at));
        if (i == 0 || (divergeAsB && i % 10 == 7)) {
            out.add(new Observation(token, MarketField.HOLDERS, MarketValue.Unknown.notProvided(source)));
        } else {
            out.add(known(token, MarketField.HOLDERS, holders, at));
        }
        Instant created = creationTimes.get(i);
        if (created != null) {
            out.add(known(token, MarketField.CREATED_AT, BigDecimal.valueOf(created.getEpochSecond()), at));
        }
        return out;
    }

    private Observation known(TokenId token, MarketField field, BigDecimal value, Instant observedAt) {
        return new Observation(token, field, new MarketValue.Known(value, source, observedAt, false));
    }

    private static BigDecimal decimal(long unscaled, int scale) {
        return BigDecimal.valueOf(unscaled, scale);
    }
}
