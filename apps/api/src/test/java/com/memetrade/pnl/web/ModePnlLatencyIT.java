package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.LedgerRows;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1 (NFR1.5, NFR1.10, NFR1.14; performance-design.md "Medição"): the latency of C1 to C4 on
 * the reference seed: one person, 10 bots (5 PAPER and 5 REAL), 730 days, 4 orders per bot per day (14,600 settled REAL
 * orders of the REAL bots) and the {@code pnl_daily} rows of the closed days of the PAPER bots. Per route: 10 warm-up requests thrown
 * away and 100 measured in sequence; the p95 is the 95th smallest time and is only LOGGED ({@code route}, {@code p95Ms},
 * {@code targetMs=1000}); a p95 above the target does not fail the build (the gate is the load scenario of the U2). The
 * test fails when a measured request answers another status, or when a block of C4 is UNAVAILABLE on the reference
 * seed (a block over its deadline is a defect of performance, not noise).
 */
class ModePnlLatencyIT extends AbstractPaperRealIT {

    private static final Logger log = LoggerFactory.getLogger(ModePnlLatencyIT.class);
    private static final int DAYS = 730;
    private static final int ORDERS_PER_DAY = 4;
    private static final int WARM_UP = 10;
    private static final int MEASURED = 100;

    @Test
    void theRoutesOfTheUnitAnswerOnTheReferenceSeedAndTheirP95IsLogged() throws Exception {
        Person person = eligiblePerson();
        seed(person);

        measure("/api/v1/pnl/by-mode", () -> {
            MockHttpServletResponse response = read(person, get("/api/v1/pnl/by-mode"));
            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode answer = body(response);
            assertThat(answer.get("paper").get("status").asText()).isEqualTo("AVAILABLE");
            assertThat(answer.get("real").get("status").asText()).isEqualTo("AVAILABLE");
            return null;
        });
        measure("/api/v1/bots/real-eligibility", () -> {
            assertThat(read(person, get("/api/v1/bots/real-eligibility")).getStatus())
                    .isEqualTo(200);
            return null;
        });
        measure("/api/v1/real/account-limits", () -> {
            assertThat(read(person, get("/api/v1/real/account-limits")).getStatus())
                    .isEqualTo(200);
            return null;
        });
        measure("/api/v1/bots", () -> {
            assertThat(create(person, "REAL", UUID.randomUUID()).getStatus()).isEqualTo(201);
            return null;
        });
    }

    private void measure(String route, Callable<Void> request) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            request.call();
        }
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < MEASURED; i++) {
            long started = System.nanoTime();
            request.call();
            millis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        Collections.sort(millis);
        log.info("U1 latency: route={}, p95Ms={}, targetMs=1000", route, millis.get(94));
    }

    /** The reference seed of NFR1.5, written in bulk (synthetic mints, never a real one). */
    private void seed(Person person) {
        Instant now = clock.instant();
        LedgerRows rows = new LedgerRows(jdbc);
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        for (int bot = 0; bot < 5; bot++) {
            UUID paper = rows.account(principal(person), "OPEN");
            botRow(person, paper, "PAPER", "Simulado " + bot);
            jdbc.sql("""
                            insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint, utc_day,
                              realized, fees, fill_count, updated_at)
                            select :t, :u, :b, 'PAPER', 'solana', 'MintLatency', d::date, 1.250000, 0.010000, 4, now()
                            from generate_series(cast(:from as date), cast(:to as date), interval '1 day') d""")
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .param("b", paper)
                    .param(
                            "from",
                            java.sql.Date.valueOf(java.time.LocalDate.ofInstant(
                                    now.minus(Duration.ofDays(DAYS)), java.time.ZoneOffset.UTC)))
                    .param(
                            "to",
                            java.sql.Date.valueOf(java.time.LocalDate.ofInstant(
                                    now.minus(Duration.ofDays(1)), java.time.ZoneOffset.UTC)))
                    .update();
            UUID real = UUID.randomUUID();
            botRow(person, real, "REAL", "Real " + bot);
            jdbc.sql("""
                            insert into real_order (real_order_id, tenant_id, user_id, bot_id, origin, idempotency_key,
                              side, mint, input_lamports, operation_mode, signing_mode, max_slippage_bps, reason, state,
                              tx_signature, out_base_units, fee_lamports, fee_payer, created_at, updated_at)
                            select gen_random_uuid(), :t, :u, :b, 'BOT', 'latency:' || :b || ':' || n, 'BUY',
                              'MintLatency' || (n % 7), 1000000, 'AUTO_FULL', 'AUTO_FULL', 100, 'carga', 'CONFIRMED',
                              'sig-' || :b || '-' || n, 10000000, 5000, 'BOT_WALLET',
                              cast(:start as timestamptz) + n * interval '6 hours',
                              cast(:start as timestamptz) + n * interval '6 hours'
                            from generate_series(1, :count) n""")
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .param("b", real)
                    .param("start", Timestamp.from(now.minus(Duration.ofDays(DAYS))))
                    .param("count", DAYS * ORDERS_PER_DAY)
                    .update();
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
