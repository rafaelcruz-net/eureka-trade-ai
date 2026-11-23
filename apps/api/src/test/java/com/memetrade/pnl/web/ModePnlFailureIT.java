package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.modeview.BlockReads;
import com.memetrade.pnl.modeview.BlockResult;
import com.memetrade.pnl.modeview.ModeBlockExecutor;
import com.memetrade.pnl.modeview.ModePnlStatement;
import com.memetrade.pnl.modeview.ModePnlView;
import com.memetrade.pnl.modeview.PnlModeMetrics;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.LedgerRows;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1, Step 11, WRITTEN BEFORE THE CODE (C4; BR5.1 to BR5.10, BR8.1 to BR8.3, NFR3.3, NFR3.6,
 * NFR4.1, NFR4.4, NFR4.6): {@code GET /api/v1/pnl/by-mode} through the whole filter chain.
 *
 * <ul>
 *   <li>the answer has only {@code period}, {@code paper} and {@code real}; each block only its own mode and currency,
 *       the PAPER disclaimer, {@code unowned} null, and no wallet address;
 *   <li>the PAPER lines plus the line "Sem bot" (US$ 0,00 in the U1) are exactly the PAPER total of the mode;
 *   <li>the four failure scenarios: PAPER fails, REAL fails, a block past its deadline, and the pool saturated; every
 *       answer is 200 with the failed block UNAVAILABLE and the correlation id of the response;
 *   <li>period ALL only; 401 without a session, 403 with new terms pending; another person's bots never appear.
 * </ul>
 */
class ModePnlFailureIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/pnl/by-mode";
    private static final LocalDate CLOSED_DAY = LocalDate.of(2026, 10, 27);

    @Autowired
    private PaperBlockSource paperSource;

    @Autowired
    private RealBlockSource realSource;

    @Autowired
    private BlockReads blockTransactions;

    @Autowired
    private PnlModeMetrics metrics;

    @Test
    void theAnswerHasOnlyThePeriodAndTwoBlocksEachInItsOwnModeAndCurrency() throws Exception {
        Person person = eligiblePerson();
        UUID paperBot = paperBot(person, "Simulado A", "12.400000", "0.400000");
        UUID realBot = realBotRow(person.tenantId(), person.userId());

        JsonNode answer = ok(person, ROUTE);

        assertThat(fields(answer)).containsExactly("period", "paper", "real");
        assertThat(answer.get("period").get("kind").asText()).isEqualTo("ALL");
        JsonNode paper = answer.get("paper");
        assertThat(paper.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(paper.get("market").asText()).isEqualTo("PAPER");
        assertThat(paper.get("currency").asText()).isEqualTo("USD");
        assertThat(paper.get("disclaimer").asText()).isEqualTo("O resultado simulado não garante o resultado real.");
        assertThat(paper.get("bots")).hasSize(1);
        assertThat(paper.get("bots").get(0).get("botId").asText()).isEqualTo(paperBot.toString());
        assertThat(paper.get("bots").get(0).get("botStatus").asText()).isEqualTo("ACTIVE");
        assertThat(new BigDecimal(
                        paper.get("bots").get(0).get("total").get("value").asText()))
                .isEqualByComparingTo("12.000000");
        JsonNode real = answer.get("real");
        assertThat(real.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(real.get("market").asText()).isEqualTo("REAL");
        assertThat(real.get("currency").asText()).isEqualTo("SOL");
        assertThat(real.has("unowned")).isTrue();
        // 261121-paper-real-unico U2 (C4): the line "Sem bot" is always an object; zero and without a reason here.
        assertThat(real.get("unowned").get("realizedLamports").asText()).isEqualTo("0");
        assertThat(real.get("unowned").get("reasons")).isEmpty();
        assertThat(real.get("bots")).hasSize(1);
        assertThat(real.get("bots").get(0).get("botId").asText()).isEqualTo(realBot.toString());
        assertThat(real.get("bots").get(0).get("realizedLamports").isTextual()).isTrue();
        assertThat(real.get("bots").get(0).get("prorated").get("count").asInt()).isZero();
        assertThat(answer.toString()).doesNotContain(person.wallet().address());
    }

    /** BR5.10, NFR2.2: the PAPER conference is exact; the line "Sem bot" of the PAPER is US$ 0,00 in the U1. */
    @Test
    void thePaperLinesAddUpExactlyToThePaperTotalOfTheMode() throws Exception {
        Person person = signIn(false, false);
        paperBot(person, "A", "12.400000", "0.400000");
        paperBot(person, "B", "-3.100000", "0.050000");
        paperBot(person, "C", "0.000001", "0.000000");

        JsonNode paper = ok(person, ROUTE).get("paper");

        BigDecimal lines = new BigDecimal("0.00");
        for (JsonNode line : paper.get("bots")) {
            lines = lines.add(new BigDecimal(line.get("total").get("value").asText()));
        }
        BigDecimal unowned = new BigDecimal("0.00");
        BigDecimal total = new BigDecimal(paper.get("total").get("value").asText());
        assertThat(lines.add(unowned).compareTo(total)).isZero();
        assertThat(total).isEqualByComparingTo("8.850001");
    }

    @Test
    void aPaperFailureLeavesTheRealBlockAndAnswers200WithTheCorrelationId() throws Exception {
        Person person = signIn(false, false);
        faultyPaper.fault.set(Fault.FAIL);
        double before = unavailableBlocks("PAPER");

        MockHttpServletResponse response = read(person, get(ROUTE));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode answer = body(response);
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(answer.get("paper").get("currency").asText()).isEqualTo("USD");
        assertThat(answer.get("paper").get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(answer.get("paper").get("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-Id"));
        assertThat(answer.get("real").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(unavailableBlocks("PAPER")).isEqualTo(before + 1);
    }

    @Test
    void aRealFailureLeavesThePaperBlock() throws Exception {
        Person person = signIn(false, false);
        faultyReal.fault.set(Fault.FAIL);
        double before = unavailableBlocks("REAL");

        JsonNode answer = ok(person, ROUTE);

        assertThat(answer.get("real").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(answer.get("real").get("currency").asText()).isEqualTo("SOL");
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(unavailableBlocks("REAL")).isEqualTo(before + 1);
    }

    @Test
    void aBlockPastItsDeadlineIsUnavailableAndTheAnswerStaysBelowOneSecond() throws Exception {
        Person person = signIn(false, false);
        faultyReal.fault.set(Fault.SLEEP);
        long started = System.nanoTime();

        JsonNode answer = ok(person, ROUTE);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_000));
        assertThat(answer.get("real").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("AVAILABLE");
    }

    /** reliability-design.md scenario 4: pool 1, queue 0, the only thread held: the blocks are refused, never 500. */
    @Test
    void aSaturatedPoolRefusesBothBlocksAsUnavailable() throws Exception {
        Person person = signIn(false, false);
        Principal principal = principal(person);
        ModeBlockExecutor saturated = new ModeBlockExecutor(1, 0, blockTransactions);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        try {
            saturated.submit(
                    PnlMarket.PAPER, System.nanoTime() + Duration.ofSeconds(10).toNanos(), () -> {
                        held.countDown();
                        return release.await(10, TimeUnit.SECONDS);
                    });
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            ModePnlView view = new ModePnlView(saturated, metrics, PnlByModeProperties.defaults());

            ModePnlStatement<?, ?> statement =
                    view.statement(() -> paperSource.block(principal), () -> realSource.block(principal));

            assertThat(statement.paper()).isInstanceOf(BlockResult.Unavailable.class);
            assertThat(statement.real()).isInstanceOf(BlockResult.Unavailable.class);
        } finally {
            release.countDown();
            saturated.destroy();
        }
    }

    @Test
    void onlyThePeriodAllIsAcceptedInTheU1() throws Exception {
        Person person = signIn(false, false);

        assertThat(read(person, get(ROUTE + "?period=ALL")).getStatus()).isEqualTo(200);
        assertThat(read(person, get(ROUTE + "?period=RANGE")).getStatus()).isEqualTo(400);
        assertThat(read(person, get(ROUTE + "?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z"))
                        .getStatus())
                .isEqualTo(400);
        assertThat(read(person, get(ROUTE + "?period=SOMETHING")).getStatus()).isEqualTo(400);
    }

    @Test
    void withoutASessionIs401AndWithNewTermsPendingIs403() throws Exception {
        assertThat(mvc.perform(get(ROUTE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        MockHttpServletResponse locked = read(staleTermsPerson(), get(ROUTE));
        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(body(locked).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void anotherPersonsBotsNeverAppear() throws Exception {
        Person owner = signIn(false, false);
        UUID ownerBot = paperBot(owner, "Do dono", "1.000000", "0.000000");
        Person stranger = signIn(false, false);

        JsonNode answer = ok(stranger, ROUTE + "?tenantId=" + owner.tenantId());

        assertThat(answer.get("paper").get("bots")).isEmpty();
        assertThat(answer.get("paper").get("empty").asBoolean()).isTrue();
        assertThat(answer.toString()).doesNotContain(ownerBot.toString());
    }

    @Test
    void theReadWritesNothing() throws Exception {
        Person person = signIn(false, false);
        paperBot(person, "A", "1.000000", "0.000000");
        long before = jdbc.sql("select (select count(*) from bot) + (select count(*) from pnl_daily)"
                        + " + (select count(*) from idempotency_record)")
                .query(Long.class)
                .single();

        for (int i = 0; i < 3; i++) {
            ok(person, ROUTE);
        }

        assertThat(jdbc.sql("select (select count(*) from bot) + (select count(*) from pnl_daily)"
                                + " + (select count(*) from idempotency_record)")
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
    }

    // ----- helpers -----

    /** An ACTIVE PAPER bot of the person with a PAPER account and one closed day of realized and fees. */
    private UUID paperBot(Person person, String name, String realized, String fees) {
        Principal principal = principal(person);
        LedgerRows rows = new LedgerRows(jdbc);
        UUID botId = rows.account(principal, "OPEN");
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, :name, 'MOMENTUM_VOLUME', 'v1', 'ACTIVE', clock_timestamp(),
                          clock_timestamp(), 'PAPER')""")
                .param("b", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("name", name)
                .update();
        rows.daily(principal, botId, CLOSED_DAY, realized, fees, 1);
        return botId;
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = node.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }
}
