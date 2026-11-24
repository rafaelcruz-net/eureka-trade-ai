package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 8 (BR8.1, BR8.2, AC3.10.3, NFR3.1, NFR3.2; team.md "isolamento entre contas"): two
 * people of different tenants trade the same mint; the lines of each one never carry anything of the other, and the bot
 * of the other is a 404 without any data. The negative test of the route C5 in {@code route-registry.yml}.
 */
@Tag("u3-pnl-views")
class BotPnlLinesTenantIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    @Test
    void theLinesOfAnotherTenantAreNeverFoundNorRead() throws Exception {
        String mint = SyntheticIds.mint();
        Person first = signIn(true, true);
        Person second = signIn(true, true);
        assertThat(first.tenantId()).isNotEqualTo(second.tenantId());
        UUID firstBot = bot(first, mint, 100_000_000L, 1_000L, 5_000_000);
        UUID secondBot = bot(second, mint, 900_000_000L, 9_000L, 5_000_010);

        MockHttpServletResponse mine = read(first, get("/api/v1/bots/" + firstBot + "/pnl/lines"));
        MockHttpServletResponse theirs = read(first, get("/api/v1/bots/" + secondBot + "/pnl/lines"));

        assertThat(mine.getStatus()).isEqualTo(200);
        JsonNode items = body(mine).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("amountLamports").asText()).isEqualTo("100000000");
        assertThat(items.get(0).get("feeLamports").asText()).isEqualTo("1000");
        assertThat(mine.getContentAsString()).doesNotContain("900000000").doesNotContain(secondBot.toString());
        assertThat(theirs.getStatus()).isEqualTo(404);
        assertThat(theirs.getContentAsString())
                .doesNotContain("900000000")
                .doesNotContain(second.tenantId().toString())
                .doesNotContain(secondBot.toString());
    }

    private UUID bot(Person person, String mint, long paid, long fee, long slot) {
        UUID bot = new PnlViewsRows(jdbc).realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        UUID wallet = activeWallet(person, 5 * SOL);
        new RealPnlSeed(
                        jdbc,
                        person.tenantId(),
                        person.userId(),
                        wallet,
                        clock.instant().minus(Duration.ofHours(2)),
                        slot)
                .buy(bot, mint, 100, paid, fee);
        return bot;
    }
}
