package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.pnl.RealPnlSeed;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U2, Etapa 20 (written before the REAL block was wired to the route; BR3.4, BR8.7, F8, NFR3.8,
 * NFR3.13, NFR4.5, NFR1.6): every failure of the REAL block of {@code GET /api/v1/pnl/by-mode} answers 200 with the
 * REAL block UNAVAILABLE (only status, code and correlation id, never a number), the PAPER block as it was, and one more
 * count in the series of its cause of {@code pnl.mode_block.unavailable}:
 *
 * <ul>
 *   <li>a difference with the ledger and an order of a bot that is not the person's: {@code RECONCILIATION_MISMATCH},
 *       audited with the same correlation id as the block (CorrelationParity);
 *   <li>a failed read of the attribution: {@code ERROR};
 *   <li>the attribution past its 500 ms ({@code 57014}): {@code TIMEOUT}.
 * </ul>
 *
 * The cap of {@code real-max-orders} in the stream, the deadline reached between two blocks of 500 rows (no
 * {@code 57014}) and a full queue are covered by {@code RealBotPnlTest}, {@code ReadDeadlineTest} and
 * {@code ModePnlFailureIT} (REJECTED), which do not need 50.000 orders in the database.
 */
@Tag("u2-real-bot-pnl")
class RealBlockFailureIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/pnl/by-mode";

    @Autowired
    private ObjectMapper json;

    private record Seeded(Person person, UUID bot, UUID wallet, RealPnlSeed seed, String mint) {}

    private Seeded seeded() throws Exception {
        Person person = signIn(true, true);
        UUID wallet = activeWallet(person, 5 * SOL);
        UUID bot = realBotRow(person.tenantId(), person.userId());
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minusSeconds(86_400),
                900_000);
        String mint = SyntheticIds.mint();
        seed.buy(bot, mint, 100, 100_000_000L, 5_000L);
        seed.sell(bot, mint, 40, 60_000_000L, 5_000L);
        return new Seeded(person, bot, wallet, seed, mint);
    }

    private JsonNode byMode(Person person) throws Exception {
        MockHttpServletResponse response = read(person, get(ROUTE));
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private static void assertUnavailableWithoutNumbers(JsonNode real, String code) {
        assertThat(real.get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(real.get("code").asText()).isEqualTo(code);
        assertThat(real.has("realizedLamports")).isFalse();
        assertThat(real.has("bots")).isFalse();
        assertThat(real.toString()).doesNotContainPattern("\"[a-zA-Z]*Lamports\"");
    }

    @Test
    void theReadyBlockReconcilesAndShowsTheGrossRealized() throws Exception {
        Seeded seeded = seeded();

        JsonNode real = byMode(seeded.person()).get("real");

        assertThat(real.get("status").asText()).isEqualTo("AVAILABLE");
        // Realized GROSS: 60.000.000 - ceil(100.000.000 x 40 / 100) = +20.000.000; fees 10.000 on their own.
        assertThat(real.get("realizedLamports").asText()).isEqualTo("20000000");
        assertThat(real.get("feesLamports").asText()).isEqualTo("10000");
        assertThat(real.get("bots").get(0).get("realizedLamports").asText()).isEqualTo("20000000");
    }

    @Test
    void aDifferenceWithTheLedgerIsReconciliationMismatchAuditedWithTheCorrelationIdOfTheBlock() throws Exception {
        Seeded seeded = seeded();
        JsonNode paperBefore = byMode(seeded.person()).get("paper");
        double before = unavailableBlocks("REAL", "RECONCILIATION_MISMATCH");
        divergingCost.diverge.set(true);

        JsonNode answer = byMode(seeded.person());

        JsonNode real = answer.get("real");
        assertUnavailableWithoutNumbers(real, "RECONCILIATION_MISMATCH");
        assertThat(answer.get("paper")).isEqualTo(paperBefore);
        assertThat(unavailableBlocks("REAL", "RECONCILIATION_MISMATCH")).isEqualTo(before + 1);
        String correlationId = real.get("correlationId").asText();
        JsonNode event =
                json.readTree(RealPnlSeed.mismatchEvent(jdbc, seeded.person().userId()));
        assertThat(event.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(event.get("details").get("checks").asText()).contains("REALIZED");
        assertThat(event.get("details").get("diff.REALIZED").asText()).isEqualTo("-1");
        assertThat(event.toString()).doesNotContain(seeded.person().wallet().address());
    }

    @Test
    void anOrderOfABotThatIsNotThePersonsIsReconciliationMismatchWithoutTheOtherBot() throws Exception {
        Seeded seeded = seeded();
        Person other = signIn(true, true);
        UUID foreignBot = realBotRow(other.tenantId(), other.userId());
        seeded.seed().orderOnly(foreignBot, seeded.mint());
        double before = unavailableBlocks("REAL", "RECONCILIATION_MISMATCH");

        JsonNode real = byMode(seeded.person()).get("real");

        assertUnavailableWithoutNumbers(real, "RECONCILIATION_MISMATCH");
        assertThat(unavailableBlocks("REAL", "RECONCILIATION_MISMATCH")).isEqualTo(before + 1);
        String event = RealPnlSeed.mismatchEvent(jdbc, seeded.person().userId());
        assertThat(event).contains("UNKNOWN_BOT").doesNotContain(foreignBot.toString());
    }

    @Test
    void aFailedReadOfTheAttributionIsATechnicalError() throws Exception {
        Seeded seeded = seeded();
        double before = unavailableBlocks("REAL", "ERROR");
        faultyState.walletIdsFault.set(Fault.FAIL);

        JsonNode answer = byMode(seeded.person());

        assertUnavailableWithoutNumbers(answer.get("real"), "SERVICE_UNAVAILABLE");
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(unavailableBlocks("REAL", "ERROR")).isEqualTo(before + 1);
    }

    @Test
    void theAttributionPastItsBudgetIsATimeout() throws Exception {
        Seeded seeded = seeded();
        double before = unavailableBlocks("REAL", "TIMEOUT");
        faultyState.walletIdsFault.set(Fault.SLEEP);

        JsonNode answer = byMode(seeded.person());

        assertUnavailableWithoutNumbers(answer.get("real"), "SERVICE_UNAVAILABLE");
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(unavailableBlocks("REAL", "TIMEOUT")).isEqualTo(before + 1);
    }
}
