package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 261121-paper-real-unico U3, Etapa 13 (method of NFR1.5; decision F-1; NFR3.17): the time of a 404 does not tell a bot
 * of another tenant from an id that does not exist. Person A asks, interleaved, for the bot of person B and for a new
 * uuid at each request; 10 warm-up and 100 measured per case, in C5 and in C6 (PAPER side and REAL side). The p95 is the
 * nearest rank; the test fails when {@code |p95(other tenant) - p95(unknown)| > max(5 ms, 10% of the larger p95)}. Both
 * p95 and the difference go to the log. An unstable failure is a defect to investigate, never a test to ignore.
 */
@Tag("u3-pnl-views")
class BotNotFoundTimingParityIT extends AbstractPaperRealIT {

    private static final Logger log = LoggerFactory.getLogger(BotNotFoundTimingParityIT.class);
    private static final int WARM_UP = 10;
    private static final int MEASURED = 100;

    @Test
    void theTimeOfA404DoesNotTellAnotherTenantFromAnUnknownId() throws Exception {
        Person person = signIn(false, false);
        Person stranger = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.STRATEGY);
        UUID strangerReal = rows.realBot(stranger.tenantId(), stranger.userId(), "X", PnlViewsRows.STRATEGY);
        UUID strangerPaper = rows.paperBot(stranger.tenantId(), stranger.userId(), "Y", PnlViewsRows.STRATEGY);

        parity("C5", person, strangerReal, bot -> "/api/v1/bots/" + bot + "/pnl/lines");
        parity(
                "C6-PAPER",
                person,
                strangerPaper,
                bot -> "/api/v1/pnl/compare?paperBotId=" + bot + "&realBotId=" + real);
        parity("C6-REAL", person, strangerReal, bot -> "/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + bot);
    }

    private void parity(String route, Person person, UUID otherTenantBot, Function<UUID, String> path)
            throws Exception {
        List<Long> other = new ArrayList<>();
        List<Long> unknown = new ArrayList<>();
        for (int i = 0; i < WARM_UP + MEASURED; i++) {
            // The order alternates at each round, so neither case always runs on a warmer path than the other.
            long a;
            long b;
            if (i % 2 == 0) {
                a = time(person, path.apply(otherTenantBot));
                b = time(person, path.apply(UUID.randomUUID()));
            } else {
                b = time(person, path.apply(UUID.randomUUID()));
                a = time(person, path.apply(otherTenantBot));
            }
            if (i >= WARM_UP) {
                other.add(a);
                unknown.add(b);
            }
        }
        long p95Other = nearestRankP95(other);
        long p95Unknown = nearestRankP95(unknown);
        long difference = Math.abs(p95Other - p95Unknown);
        long allowed = Math.max(Duration.ofMillis(5).toNanos(), Math.max(p95Other, p95Unknown) / 10);
        log.info(
                "U3 404 timing parity: route={}, p95OtherTenantUs={}, p95UnknownUs={}, differenceUs={}",
                route,
                p95Other / 1_000,
                p95Unknown / 1_000,
                difference / 1_000);
        assertThat(difference).as(route).isLessThanOrEqualTo(allowed);
    }

    private long time(Person person, String route) throws Exception {
        long started = System.nanoTime();
        int status = read(person, get(route)).getStatus();
        long elapsed = System.nanoTime() - started;
        assertThat(status).as(route).isEqualTo(404);
        return elapsed;
    }

    static long nearestRankP95(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
