package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 261121-paper-real-unico U3, Etapa 13 (NFR1.1 to NFR1.4, NFR1.9; method of NFR1.5): the latency of the 11 reads of the
 * U3 on the reference seed of the U1 (1 person, 10 bots, 5 PAPER and 5 REAL, 730 days, 4 orders per bot per day). Per
 * case: 10 warm-up requests thrown away and 100 measured in sequence; the p95 (nearest rank) is only LOGGED next to the
 * target of 1,000 ms (the gate is the load scenario {@code pnl-by-mode}); the test fails only when a measured request
 * does not answer 200.
 */
@Tag("u3-pnl-views")
class PnlViewsLatencyIT extends AbstractPaperRealIT {

    private static final Logger log = LoggerFactory.getLogger(PnlViewsLatencyIT.class);
    private static final int DAYS = 730;
    private static final int ORDERS_PER_DAY = 4;
    private static final int WARM_UP = 10;
    private static final int MEASURED = 100;

    @Test
    void theElevenReadsAnswerOnTheReferenceSeedAndTheirP95IsLogged() throws Exception {
        Person person = eligiblePerson();
        List<UUID> paper = new ArrayList<>();
        List<UUID> real = new ArrayList<>();
        seed(person, paper, real);
        Instant to = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String range30 = "period=RANGE&from=" + to.minus(Duration.ofDays(30)) + "&to=" + to;
        String compare = "/api/v1/pnl/compare?paperBotId=" + paper.getFirst() + "&realBotId=" + real.getFirst();
        String lines = "/api/v1/bots/" + real.getFirst() + "/pnl/lines?limit=50";
        String next = body(read(person, get(lines))).get("nextCursor").asText(null);

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("C4 market=PAPER ALL", "/api/v1/pnl/by-mode?market=PAPER");
        cases.put("C4 market=REAL ALL", "/api/v1/pnl/by-mode?market=REAL");
        cases.put("C4 market=PAPER RANGE 30d", "/api/v1/pnl/by-mode?market=PAPER&" + range30);
        cases.put("C4 market=REAL RANGE 30d", "/api/v1/pnl/by-mode?market=REAL&" + range30);
        cases.put("listBots market=PAPER", "/api/v1/bots?market=PAPER");
        cases.put("listBots market=REAL", "/api/v1/bots?market=REAL");
        cases.put("listBots", "/api/v1/bots");
        cases.put("C5 first page", lines);
        cases.put("C5 next page", next == null ? lines : lines + "&cursor=" + next);
        cases.put("C6 ALL", compare);
        cases.put("C6 RANGE 30d", compare + "&" + range30);
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            measure(person, entry.getKey(), entry.getValue());
        }
    }

    private void measure(Person person, String name, String route) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            read(person, get(route));
        }
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < MEASURED; i++) {
            long started = System.nanoTime();
            int status = read(person, get(route)).getStatus();
            millis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
            assertThat(status).as(name).isEqualTo(200);
        }
        Collections.sort(millis);
        log.info(
                "U3 latency: case={}, p95Ms={}, targetMs=1000", name, millis.get((int) Math.ceil(0.95 * MEASURED) - 1));
    }

    /** The reference seed of NFR1.5, written in bulk (synthetic mints, never a real one). */
    private void seed(Person person, List<UUID> paper, List<UUID> real) {
        Instant now = clock.instant();
        com.memetrade.testsupport.LedgerRows rows = new com.memetrade.testsupport.LedgerRows(jdbc);
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        for (int bot = 0; bot < 5; bot++) {
            UUID paperBot = rows.account(principal(person), "OPEN");
            botRow(person, paperBot, "PAPER", "Simulado " + bot);
            jdbc.sql("""
                            insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint, utc_day,
                              realized, fees, fill_count, updated_at)
                            select :t, :u, :b, 'PAPER', 'solana', 'MintLatency', d::date, 1.250000, 0.010000, 4, now()
                            from generate_series(cast(:from as date), cast(:to as date), interval '1 day') d""")
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .param("b", paperBot)
                    .param(
                            "from",
                            java.sql.Date.valueOf(java.time.LocalDate.ofInstant(
                                    now.minus(Duration.ofDays(DAYS)), java.time.ZoneOffset.UTC)))
                    .param(
                            "to",
                            java.sql.Date.valueOf(java.time.LocalDate.ofInstant(
                                    now.minus(Duration.ofDays(1)), java.time.ZoneOffset.UTC)))
                    .update();
            paper.add(paperBot);
            UUID realBot = UUID.randomUUID();
            botRow(person, realBot, "REAL", "Real " + bot);
            jdbc.sql("""
                            insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                              side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,
                              tx_signature, out_base_units, fee_lamports, fee_payer, created_at, updated_at)
                            select gen_random_uuid(), :t, :u, :b, 'BOT', 'latency-u3:' || :b || ':' || n, 'BUY',
                              'MintLatency' || (n % 7), 1000000, 'AUTO_FULL', 'AUTO_FULL', 100, 'carga', 'CONFIRMED',
                              'sig-u3-' || :b || '-' || n, 10000000, 5000, 'BOT_WALLET',
                              cast(:start as timestamptz) + n * interval '6 hours',
                              cast(:start as timestamptz) + n * interval '6 hours'
                            from generate_series(1, :count) n""")
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .param("b", realBot)
                    .param("start", Timestamp.from(now.minus(Duration.ofDays(DAYS))))
                    .param("count", DAYS * ORDERS_PER_DAY)
                    .update();
            real.add(realBot);
        }
    }

    private void botRow(Person person, UUID botId, String market, String name) {
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, :name, 'MOMENTUM_VOLUME', 'v1', 'ACTIVE', clock_timestamp(),
                          clock_timestamp(), :market)""")
                .param("b", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("name", name)
                .param("market", market)
                .update();
    }
}
