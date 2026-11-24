package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 13 (test after the layer; NFR1.10, NFR1.16): the reads of the U3 write nothing and
 * take no lock but the {@code AccessShareLock} of a plain read: after three reads in a row of C4 with {@code market} and
 * with {@code RANGE}, {@code listBots}, C5 and C6, the tables touched have no row more, and while they run no
 * {@code RowShareLock} ({@code FOR UPDATE}) nor advisory lock of the person is ever held.
 */
@Tag("u3-pnl-views")
class PnlViewsReadOnlyIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    @Test
    void threeReadsOfEveryRouteWriteNothingAndTakeNoLock() throws Exception {
        Person person = signIn(true, true);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.STRATEGY);
        UUID wallet = activeWallet(person, 5 * SOL);
        new RealPnlSeed(
                        jdbc,
                        person.tenantId(),
                        person.userId(),
                        wallet,
                        clock.instant().minus(Duration.ofDays(1)),
                        6_000_000)
                .buy(real, SyntheticIds.mint(), 10, 10_000_000L, 1_000L);
        List<String> routes = List.of(
                "/api/v1/pnl/by-mode?market=PAPER",
                "/api/v1/pnl/by-mode?market=REAL&period=RANGE&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z",
                "/api/v1/bots?market=REAL",
                "/api/v1/bots/" + real + "/pnl/lines",
                "/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + real);
        long before = rows.rowsOfTheReads();
        AtomicBoolean reading = new AtomicBoolean(true);
        AtomicReference<String> seen = new AtomicReference<>("");
        Thread watcher = new Thread(() -> {
            while (reading.get()) {
                String locks = jdbc.sql("""
                                select coalesce(string_agg(distinct mode || ':' || relation::regclass::text, ','), '')
                                from pg_locks
                                where pid <> pg_backend_pid() and mode in ('RowShareLock', 'RowExclusiveLock')
                                  and relation::regclass::text in ('bot', 'real_order', 'real_ledger_entry', 'fill',
                                    'paper_account', 'bot_wallet_account', 'pnl_daily', 'position')""").query(String.class).single();
                if (!locks.isEmpty()) {
                    seen.set(locks);
                }
            }
        });
        watcher.start();
        try {
            for (int round = 0; round < 3; round++) {
                for (String route : routes) {
                    assertThat(read(person, get(route)).getStatus()).as(route).isEqualTo(200);
                }
            }
        } finally {
            reading.set(false);
            watcher.join();
        }

        assertThat(rows.rowsOfTheReads()).isEqualTo(before);
        assertThat(seen.get()).as("locks taken while the reads ran").isEmpty();
    }
}
