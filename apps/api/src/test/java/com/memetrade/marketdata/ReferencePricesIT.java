package com.memetrade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 3 of the U3, written before the code (M4, Q3 of the NFR Design, BR2.4, BR3.1, BR5.2): the prices of a set of
 * tokens in one read, with the same {@code observedAt} and the same unknown rule as the read of one token; the decimals
 * of a set of tokens; the 24 h volume of one token. A token never observed is unknown, never zero.
 */
class ReferencePricesIT extends AbstractPostgresIT {

    @Autowired
    private MarketDataQuery market;

    private String source() {
        String id = "u3-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("insert into data_source (source_id, name, simulated, status) values (:id, :id, false, 'OK')")
                .param("id", id)
                .update();
        return id;
    }

    private TokenId token(int decimals) {
        TokenId token = new TokenId("solana", SyntheticIds.mint());
        jdbc.sql("""
                        insert into token (chain, mint, symbol, decimals, first_seen_at)
                        values (:chain, :mint, 'TST', :decimals, :at)""")
                .param("chain", token.chain())
                .param("mint", token.mint())
                .param("decimals", decimals)
                .param("at", Timestamp.from(clock.instant().minus(Duration.ofDays(1))))
                .update();
        return token;
    }

    private void observe(TokenId token, String source, String field, String value, Instant at) {
        jdbc.sql("""
                        insert into market_observation (observation_id, chain, mint, source_id, field, status, value,
                          unknown_reason, observed_at)
                        values (:id, :chain, :mint, :source, :field, :status, :value, :reason, :at)""")
                .param("id", UUID.randomUUID())
                .param("chain", token.chain())
                .param("mint", token.mint())
                .param("source", source)
                .param("field", field)
                .param("status", value == null ? "UNKNOWN" : "KNOWN")
                .param("value", value == null ? null : new BigDecimal(value))
                .param("reason", value == null ? "NOT_PROVIDED" : null)
                .param("at", Timestamp.from(at))
                .update();
    }

    @Test
    void thePricesOfSeveralTokensComeInOneReadLikeTheReadOfOneToken() {
        String source = source();
        Instant at = clock.instant().minusSeconds(30);
        TokenId known = token(6);
        TokenId unknown = token(9);
        TokenId neverSeen = new TokenId("solana", SyntheticIds.mint());
        observe(known, source, "PRICE", "0.000123", at);
        observe(unknown, source, "PRICE", null, at);

        Map<TokenId, MarketValue> prices = market.referencePrices(Set.of(known, unknown, neverSeen));

        assertThat(prices).containsOnlyKeys(known, unknown, neverSeen);
        assertThat(prices.get(known)).isEqualTo(market.referencePrice(known));
        assertThat(prices.get(known)).isInstanceOfSatisfying(MarketValue.Known.class, value -> {
            assertThat(value.value()).isEqualByComparingTo("0.000123");
            assertThat(value.observedAt()).isEqualTo(at);
        });
        assertThat(prices.get(unknown)).isInstanceOf(MarketValue.Unknown.class);
        assertThat(prices.get(neverSeen))
                .isInstanceOfSatisfying(MarketValue.Unknown.class, value -> assertThat(value.reason())
                        .isEqualTo(UnknownReason.NOT_YET_OBSERVED));
    }

    @Test
    void anEmptySetReadsNothing() {
        assertThat(market.referencePrices(Set.of())).isEmpty();
        assertThat(market.decimalsOf(Set.of())).isEmpty();
    }

    @Test
    void theDecimalsOfSeveralTokensAreReadAtOnceAndAMissingTokenIsUnknown() {
        String source = source();
        TokenId six = token(6);
        TokenId nine = token(9);
        TokenId neverSeen = new TokenId("solana", SyntheticIds.mint());
        observe(six, source, "PRICE", "1", clock.instant());
        observe(nine, source, "PRICE", "1", clock.instant());

        Map<TokenId, MarketValue> decimals = market.decimalsOf(Set.of(six, nine, neverSeen));

        assertThat(((MarketValue.Known) decimals.get(six)).value().intValueExact())
                .isEqualTo(6);
        assertThat(((MarketValue.Known) decimals.get(nine)).value().intValueExact())
                .isEqualTo(9);
        assertThat(decimals.get(neverSeen)).isInstanceOf(MarketValue.Unknown.class);
    }

    @Test
    void theVolumeOfOneTokenIsReadWithItsObservationTime() {
        String source = source();
        TokenId token = token(6);
        Instant at = clock.instant().minusSeconds(61);
        observe(token, source, "VOLUME_24H", "250000", at);

        assertThat(market.volume24h(token)).isInstanceOfSatisfying(MarketValue.Known.class, value -> {
            assertThat(value.value()).isEqualByComparingTo("250000");
            assertThat(value.observedAt()).isEqualTo(at);
        });
        assertThat(market.volume24h(new TokenId("solana", SyntheticIds.mint())))
                .isInstanceOf(MarketValue.Unknown.class);
    }
}
