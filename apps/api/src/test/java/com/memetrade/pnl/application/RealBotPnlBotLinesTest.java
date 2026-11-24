package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlLine;
import com.memetrade.pnl.api.RealPnlLineCursorRejected;
import com.memetrade.pnl.api.RealPnlLineItem;
import com.memetrade.pnl.api.RealPnlLineOrigin;
import com.memetrade.pnl.api.RealPnlLinePage;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.real.Holder;
import com.memetrade.pnl.real.PnlScenario;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 3, WRITTEN BEFORE THE CODE (BR4.2, BR4.3, BR4.4, BR4.6, AC3.10.1, AC3.10.2, NFR2.3,
 * NFR7.7): {@code botLines} of C10 over the doubles of the ports of {@code RealBotPnlTest}. The lines of one bot come
 * from the same pass as C4, newest first in network order (ties by the order of the enum {@code origin}); the page
 * starts after the anchor of the cursor; a buy realizes 0; the prorated parts of the bot keep their origin; an event of a
 * fee alone is a line with the side of its order, amount 0 and the fee; a bot that is not a REAL bot of the person gives
 * nothing (404 on the route).
 */
class RealBotPnlBotLinesTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final Holder A =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000a"), Instant.parse("2026-11-01T10:00:00Z"));
    private static final Holder B =
            Holder.bot(UUID.fromString("00000000-0000-0000-0000-00000000000b"), Instant.parse("2026-11-02T10:00:00Z"));

    private static UUID a() {
        return A.botId().orElseThrow();
    }

    /** A buys, B buys, A sells, a liquidation shares among A and B, B sells more than it holds, A's order fails. */
    private static PnlScenario story() {
        PnlScenario scenario = new PnlScenario().bot(A).bot(B);
        scenario.at("2026-11-10T10:00:00Z").buy(A, 300, 300_000_000L, 1_000L);
        scenario.at("2026-11-10T11:00:00Z").buy(B, 100, 200_000_000L, 2_000L);
        scenario.at("2026-11-10T12:00:00Z").ownSale(A, 100, 150_000_000L, 3_000L);
        scenario.at("2026-11-10T13:00:00Z").liquidation(100, 120_000_000L, 4_000L);
        scenario.at("2026-11-10T14:00:00Z").ownSale(B, 150, 300_000_000L, 5_000L);
        scenario.at("2026-11-10T15:00:00Z").failed(A, 6_000L);
        return scenario;
    }

    private RealBotPnl query(PnlScenario scenario) {
        RealBotPnlTest doubles = new RealBotPnlTest();
        RealBotPnl query = doubles.query(scenario, PnlByModeProperties.defaults());
        doubles.prices(Map.of());
        return query;
    }

    @Test
    void theLinesOfTheBotComeNewestFirstInNetworkOrderWithTheirOrigin() {
        RealPnlLinePage page =
                query(story()).botLines(TENANT, USER, a(), Optional.empty(), 50).orElseThrow();

        assertThat(page.items())
                .extracting(RealPnlLineItem::origin, RealPnlLineItem::side)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RealPnlLineOrigin.BOT, RealPnlLineItem.Side.BUY),
                        org.assertj.core.groups.Tuple.tuple(
                                RealPnlLineOrigin.SOLD_BY_OTHER_BOT, RealPnlLineItem.Side.SELL),
                        org.assertj.core.groups.Tuple.tuple(RealPnlLineOrigin.LIQUIDATION, RealPnlLineItem.Side.SELL),
                        org.assertj.core.groups.Tuple.tuple(RealPnlLineOrigin.BOT, RealPnlLineItem.Side.SELL),
                        org.assertj.core.groups.Tuple.tuple(RealPnlLineOrigin.BOT, RealPnlLineItem.Side.BUY));
        assertThat(page.items())
                .extracting(RealPnlLineItem::at)
                .containsExactly(
                        Instant.parse("2026-11-10T15:00:00Z"),
                        Instant.parse("2026-11-10T14:00:00Z"),
                        Instant.parse("2026-11-10T13:00:00Z"),
                        Instant.parse("2026-11-10T12:00:00Z"),
                        Instant.parse("2026-11-10T10:00:00Z"));
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void theLinesAddUpExactlyToTheLineOfTheBotInTheBlock() {
        RealBotPnl query = query(story());

        RealPnlLinePage page =
                query.botLines(TENANT, USER, a(), Optional.empty(), 100).orElseThrow();
        RealBotPnlLine line = query.botPnl(TENANT, USER, a(), PnlWindow.all()).orElseThrow();

        long realized = 0;
        long fees = 0;
        for (RealPnlLineItem item : page.items()) {
            realized = Math.addExact(realized, item.realizedLamports());
            fees = Math.addExact(fees, item.feeLamports());
        }
        assertThat(realized).isEqualTo(line.realizedLamports());
        assertThat(fees).isEqualTo(line.feesLamports());
    }

    @Test
    void aBuyRealizesZeroAndAFeeAloneIsALineWithTheSideOfItsOrderAmountZeroAndTheFee() {
        List<RealPnlLineItem> items = query(story())
                .botLines(TENANT, USER, a(), Optional.empty(), 50)
                .orElseThrow()
                .items();

        RealPnlLineItem failed = items.getFirst();
        assertThat(failed.amountLamports()).isZero();
        assertThat(failed.tokenBaseUnits()).isZero();
        assertThat(failed.feeLamports()).isEqualTo(6_000L);
        assertThat(failed.realizedLamports()).isZero();
        RealPnlLineItem buy = items.getLast();
        assertThat(buy.side()).isEqualTo(RealPnlLineItem.Side.BUY);
        assertThat(buy.realizedLamports()).isZero();
        assertThat(buy.amountLamports()).isEqualTo(300_000_000L);
        assertThat(buy.tokenBaseUnits()).isEqualTo(300L);
        assertThat(buy.feeLamports()).isEqualTo(1_000L);
        assertThat(buy.mint()).isEqualTo(PnlScenario.XYZ);
        RealPnlLineItem sale = items.get(3);
        assertThat(sale.amountLamports()).isEqualTo(150_000_000L);
        assertThat(sale.realizedLamports()).isEqualTo(50_000_000L);
    }

    @Test
    void theLimitIsKeptAndTheNextCursorIsTheCursorOfTheLastLineReturned() {
        RealBotPnl query = query(story());

        RealPnlLinePage first =
                query.botLines(TENANT, USER, a(), Optional.empty(), 2).orElseThrow();

        assertThat(first.items()).hasSize(2);
        RealPnlLineItem last = first.items().getLast();
        assertThat(first.nextCursor()).contains(BotLinesCursor.encode(a(), last.identity(), last.origin()));

        RealPnlLinePage second =
                query.botLines(TENANT, USER, a(), first.nextCursor(), 2).orElseThrow();
        RealPnlLinePage third =
                query.botLines(TENANT, USER, a(), second.nextCursor(), 2).orElseThrow();

        assertThat(second.items()).hasSize(2);
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextCursor()).isEmpty();
        Set<String> seen = new HashSet<>();
        List<RealPnlLineItem> all = new ArrayList<>(first.items());
        all.addAll(second.items());
        all.addAll(third.items());
        for (RealPnlLineItem item : all) {
            assertThat(seen.add(item.identity() + "|" + item.origin())).isTrue();
        }
        assertThat(all).hasSize(5);
    }

    @Test
    void anAnchorThatIsNotFoundIsRefused() {
        RealBotPnl query = query(story());
        String unknown = BotLinesCursor.encode(a(), "no-such-key", RealPnlLineOrigin.BOT);

        assertThatThrownBy(() -> query.botLines(TENANT, USER, a(), Optional.of(unknown), 50))
                .isInstanceOf(RealPnlLineCursorRejected.class);
    }

    @Test
    void aBotThatIsNotARealBotOfThePersonGivesNothing() {
        RealBotPnl query = query(story());

        assertThat(query.botLines(TENANT, USER, UUID.randomUUID(), Optional.empty(), 50))
                .isEmpty();
    }

    @Test
    void theLinesOfOneBotNeverCarryALineOfAnother() {
        RealBotPnl query = query(story());

        List<RealPnlLineItem> ofB = query.botLines(TENANT, USER, B.botId().orElseThrow(), Optional.empty(), 50)
                .orElseThrow()
                .items();

        // B: its buy, its share of the liquidation and its own sale (the excess went to A).
        assertThat(ofB)
                .extracting(RealPnlLineItem::origin)
                .containsExactly(RealPnlLineOrigin.BOT, RealPnlLineOrigin.LIQUIDATION, RealPnlLineOrigin.BOT);
    }
}
