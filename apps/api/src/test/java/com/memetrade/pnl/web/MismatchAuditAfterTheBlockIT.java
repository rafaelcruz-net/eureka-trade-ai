package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.infra.MismatchAuditWriter;
import com.memetrade.pnl.RealPnlSeed;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U2, review R-01 (decision of the founder of 2026-11-23; NFR1.16, scalability-design.md
 * "Concorrência e conexões"): the event {@code REAL_PNL_RECONCILIATION_MISMATCH} is written only after the read
 * transaction of the REAL block has closed, so the read never holds a connection of the main pool while the audit
 * writes on its own pool. The proof is an order: the number the block takes when its REAL transaction ended is lower
 * than the number the writer takes right after it got its advisory lock. The block stays UNAVAILABLE with
 * {@code RECONCILIATION_MISMATCH}, on C4 and on {@code GET /api/v1/real/pnl}.
 */
@Tag("u2-real-bot-pnl")
class MismatchAuditAfterTheBlockIT extends AbstractPaperRealIT {

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MismatchAuditWriter writer;

    @AfterEach
    void releaseTheHook() {
        writer.observeBackendPids(pid -> {});
    }

    private Person seeded() throws Exception {
        Person person = signIn(true, true);
        UUID wallet = activeWallet(person, 5 * SOL);
        UUID bot = realBotRow(person.tenantId(), person.userId());
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minusSeconds(86_400),
                910_000);
        String mint = SyntheticIds.mint();
        seed.buy(bot, mint, 100, 100_000_000L, 5_000L);
        seed.sell(bot, mint, 40, 60_000_000L, 5_000L);
        return person;
    }

    /** The number of the sequence the writer took after its first lock (0 while it did not lock). */
    private AtomicLong lockOfTheWriter() {
        AtomicLong locked = new AtomicLong();
        writer.observeBackendPids(pid -> locked.compareAndSet(0, blockReads.sequence.incrementAndGet()));
        return locked;
    }

    @Test
    void onC4TheMainConnectionIsBackInThePoolBeforeTheAuditWrites() throws Exception {
        Person person = seeded();
        AtomicLong locked = lockOfTheWriter();
        divergingCost.diverge.set(true);

        MockHttpServletResponse response = read(person, get("/api/v1/pnl/by-mode"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode real = json.readTree(response.getContentAsString()).get("real");
        assertThat(real.get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(real.get("code").asText()).isEqualTo("RECONCILIATION_MISMATCH");
        RealPnlSeed.mismatchEvent(jdbc, person.userId());
        assertThat(locked.get())
                .as("the writer locked after the REAL transaction of the block ended")
                .isPositive()
                .isGreaterThan(blockReads.realTransactionEnded.get());
    }

    @Test
    void onTheRealPnlRouteTheMainConnectionIsBackInThePoolBeforeTheAuditWrites() throws Exception {
        Person person = seeded();
        AtomicLong locked = lockOfTheWriter();
        divergingCost.diverge.set(true);

        MockHttpServletResponse response = read(person, get("/api/v1/real/pnl"));

        assertThat(response.getStatus()).isEqualTo(503);
        RealPnlSeed.mismatchEvent(jdbc, person.userId());
        assertThat(locked.get())
                .as("the writer locked after the REAL transaction of the block ended")
                .isPositive()
                .isGreaterThan(blockReads.realTransactionEnded.get());
    }
}
