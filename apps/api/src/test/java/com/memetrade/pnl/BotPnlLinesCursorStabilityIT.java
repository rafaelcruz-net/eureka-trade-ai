package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealPnlLineItem;
import com.memetrade.pnl.api.RealPnlLinePage;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 3, WRITTEN BEFORE THE CODE (BR4.3, AC3.10.1, NFR1.11, NFR2.3 (b), NFR7.7): the
 * stability of the cursor of C5. The first page is read, a new confirmed order of the same bot is written, and the next
 * pages are read with the cursor: 0 lines repeated (by the identity {@code idempotencyKey} plus {@code origin}), 0 lines
 * lost; the new line is not in the next pages and is in a first page read again. A late confirmation with a slot older
 * than the anchor appears once only. Each page is a new pass (not a snapshot): only lines are compared, never values.
 */
@Tag("u3-pnl-views")
class BotPnlLinesCursorStabilityIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    @Autowired
    private RealBotPnlQuery query;

    private record Story(Person person, UUID bot, RealPnlSeed seed, String mint) {}

    /** 30 buys and 30 sales of one bot: 60 lines. */
    private Story story(long firstSlot) throws Exception {
        Person person = signIn(true, true);
        UUID bot = new PnlViewsRows(jdbc).realBot(person.tenantId(), person.userId(), "Alvo", PnlViewsRows.STRATEGY);
        UUID wallet = activeWallet(person, 5 * SOL);
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minus(Duration.ofDays(3)),
                firstSlot);
        for (int round = 0; round < 30; round++) {
            seed.buy(bot, mint, 10, 10_000_000L + round, 1_000L);
            seed.sell(bot, mint, 5, 6_000_000L + round, 500L);
        }
        return new Story(person, bot, seed, mint);
    }

    @Test
    void aNewOrderBetweenTwoPagesRepeatsNothingLosesNothingAndAppearsOnlyInANewFirstPage() throws Exception {
        Story story = story(1_000_000);
        Set<String> before = identities(all(story));
        assertThat(before).hasSize(60);

        RealPnlLinePage first = page(story, Optional.empty());
        story.seed()
                .at(clock.instant().minus(Duration.ofMinutes(1)))
                .buy(story.bot(), story.mint(), 7, 7_000_000L, 900L);
        List<RealPnlLineItem> rest = new ArrayList<>();
        Optional<String> cursor = first.nextCursor();
        while (cursor.isPresent()) {
            RealPnlLinePage next = page(story, cursor);
            rest.addAll(next.items());
            cursor = next.nextCursor();
        }

        List<RealPnlLineItem> read = new ArrayList<>(first.items());
        read.addAll(rest);
        assertThat(read).hasSize(60);
        assertThat(identities(read)).isEqualTo(before);
        Set<String> fresh = identities(all(story));
        fresh.removeAll(before);
        assertThat(fresh).hasSize(1);
        assertThat(identities(rest)).doesNotContainAnyElementsOf(fresh);
        assertThat(identity(page(story, Optional.empty()).items().getFirst()))
                .isEqualTo(fresh.iterator().next());
    }

    @Test
    void aLateConfirmationWithASlotOlderThanTheAnchorAppearsOnce() throws Exception {
        Story story = story(2_000_000);
        RealPnlLinePage first = page(story, Optional.empty());
        Set<String> before = identities(all(story));

        Instant old = clock.instant().minus(Duration.ofDays(4));
        story.seed().at(old).slotAt(1_999_000).buy(story.bot(), story.mint(), 2, 2_000_000L, 100L);
        Set<String> late = identities(all(story));
        late.removeAll(before);
        assertThat(late).hasSize(1);

        List<RealPnlLineItem> rest = new ArrayList<>();
        Optional<String> cursor = first.nextCursor();
        while (cursor.isPresent()) {
            RealPnlLinePage next = page(story, cursor);
            rest.addAll(next.items());
            cursor = next.nextCursor();
        }
        List<String> restIdentities =
                rest.stream().map(BotPnlLinesCursorStabilityIT::identity).toList();
        assertThat(restIdentities).containsOnlyOnce(late.iterator().next());
        assertThat(new HashSet<>(restIdentities)).hasSize(restIdentities.size());
        assertThat(rest).hasSize(11);
    }

    @Test
    void theRouteChainsTheCursorItGives() throws Exception {
        Story story = story(3_000_000);

        JsonNode first = ok(story.person(), "/api/v1/bots/" + story.bot() + "/pnl/lines?limit=50");
        String cursor = first.get("nextCursor").asText();
        JsonNode second = ok(story.person(), "/api/v1/bots/" + story.bot() + "/pnl/lines?limit=50&cursor=" + cursor);

        assertThat(first.get("items")).hasSize(50);
        assertThat(cursor).hasSize(44);
        assertThat(second.get("items")).hasSize(10);
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    private RealPnlLinePage page(Story story, Optional<String> cursor) {
        return query.botLines(story.person().tenantId(), story.person().userId(), story.bot(), cursor, 50)
                .orElseThrow();
    }

    private List<RealPnlLineItem> all(Story story) {
        return query.botLines(story.person().tenantId(), story.person().userId(), story.bot(), Optional.empty(), 100)
                .orElseThrow()
                .items();
    }

    private static Set<String> identities(List<RealPnlLineItem> items) {
        Set<String> identities = new HashSet<>();
        items.forEach(item -> assertThat(identities.add(identity(item))).isTrue());
        return identities;
    }

    private static String identity(RealPnlLineItem item) {
        return item.identity() + "|" + item.origin();
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }
}
