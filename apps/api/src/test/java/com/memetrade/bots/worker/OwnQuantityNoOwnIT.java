package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.TokenId;
import com.memetrade.realexecution.OpenSellsFixture;
import java.sql.Timestamp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U4, Etapa 4, T3 (written before the code; E6, E7, AC6.1.3; BR1.5, BR1.6, BR1.8, BR4.2): a mint
 * of the wallet without an own available quantity leaves the tokens of the request of A, its note NO_OWN_QUANTITY stays
 * in {@code context.ownQuantity.mints} even when the engine ends with NO_TOKENS or NO_TRADE, no SELL is recorded and
 * {@code real_cycle_sell_capped_total{outcome="NO_OWN_QUANTITY"}} counts it; A buys it neither (the wallet holds it, as
 * today); the opening record of the bot for that mint is not closed.
 */
@Tag("u4-own-quantity-cycle")
class OwnQuantityNoOwnIT extends AbstractOwnQuantityIT {

    @Test
    void e7OnlyBHoldsXyzSoTheRequestOfAHasNoXyzAndNothingIsSold() {
        Pair pair = pair(false, true);
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(cycle.lastRequest().tokens()).noneMatch(t -> t.token().mint().equals(pair.xyz()));
        assertThat(cycle.lastRequest().positions()).isEmpty();
        assertThat(outcome(result)).isEqualTo("NO_ORDER");
        assertThat(sellDecisions(pair.a())).isEmpty();
        assertThat(context(result)).contains("\"ownQuantity\"").contains("\"" + pair.xyz() + "\": \"NO_OWN_QUANTITY\"");
        assertThat(cycle.meters
                        .get("real_cycle_sell_capped")
                        .tag("outcome", "NO_OWN_QUANTITY")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void theMintsThatBlockABuyAreTheSameWithTheFlagOnAndOff() {
        Pair pair = pair(false, true);
        OwnQuantityCycle on = cycle(true).sellsAll(pair.xyz());
        OwnQuantityCycle off = cycle(false).sellsAll(pair.xyz());

        on.run(pair.refA(), slot());
        // With the flag off the wallet position is a position of A (today): the engine never buys a held mint.
        // With the flag on the mint leaves the tokens: the engine cannot buy it either. Same blocked set: the wallet.
        off.decidesNothing().run(pair.refA(), nextSlot());

        assertThat(OwnQuantityCycle.mints(off.lastRequest().positions())).containsExactly(pair.xyz());
        assertThat(on.lastRequest().tokens()).noneMatch(t -> t.token().mint().equals(pair.xyz()));
        assertThat(on.lastRequest().positions()).isEmpty();
    }

    @Test
    void e6AnAmbiguousSaleOfTheWholeOwnQuantitySellsNothingAndKeepsTheOpeningRecord() {
        Pair pair = pair();
        new OpenSellsFixture(jdbc)
                .order(pair.tenantId(), pair.userId(), pair.a(), "SELL", pair.xyz(), A_UNITS, "AMBIGUOUS");
        jdbc.sql("insert into bot_holding (bot_id, chain, token_mint, opened_at) values (:b, 'solana', :m, :at)")
                .param("b", pair.a())
                .param("m", pair.xyz())
                .param("at", Timestamp.from(clock.instant().minusSeconds(3_600)))
                .update();
        OwnQuantityCycle cycle = cycle(true).sellsAll(pair.xyz());

        CycleResult result = cycle.run(pair.refA(), slot());

        assertThat(outcome(result)).isEqualTo("NO_ORDER");
        assertThat(sellDecisions(pair.a())).isEmpty();
        assertThat(context(result)).contains("\"" + pair.xyz() + "\": \"NO_OWN_QUANTITY\"");
        assertThat(cycle.lastRequest().tokens()).noneMatch(t -> t.token().equals(new TokenId("solana", pair.xyz())));
        assertThat(jdbc.sql("select count(*) from bot_holding where bot_id = :b and token_mint = :m")
                        .param("b", pair.a())
                        .param("m", pair.xyz())
                        .query(Long.class)
                        .single())
                .as("BR1.8: the opening record stays open")
                .isEqualTo(1L);
    }
}
