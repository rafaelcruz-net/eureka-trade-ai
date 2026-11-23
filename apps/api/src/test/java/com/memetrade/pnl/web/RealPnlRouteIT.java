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
 * 261121-paper-real-unico U2, Etapa 20 (BR10.3, NFR1.2, NFR3.8): {@code GET /api/v1/real/pnl} is the total and the
 * positions of the {@code RealBotPnl}, read through the pool of the blocks: the same shape, the realized GROSS, the cost
 * of a position without the network fee, and a difference with the ledger answers the technical error of the route
 * (503, only the code and the correlation id), never a number.
 */
@Tag("u2-real-bot-pnl")
class RealPnlRouteIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/real/pnl";

    @Autowired
    private ObjectMapper json;

    @Test
    void theRouteKeepsItsShapeWithTheGrossRealizedAndTheOneAverageCost() throws Exception {
        Person person = signIn(true, true);
        UUID wallet = activeWallet(person, 5 * SOL);
        UUID bot = realBotRow(person.tenantId(), person.userId());
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minusSeconds(3_600),
                910_000);
        seed.buy(bot, mint, 100, 100_000_000L, 5_000L);
        seed.sell(bot, mint, 40, 60_000_000L, 5_000L);

        MockHttpServletResponse response = read(person, get(ROUTE));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        // The shape of the route does not change: the unrealized is left out when a price is unknown (no price here).
        assertThat(fieldNames(body))
                .contains("realizedLamports", "unrealizedStatus", "feesLamports", "positions")
                .isSubsetOf("realizedLamports", "unrealizedLamports", "unrealizedStatus", "feesLamports", "positions");
        assertThat(body.get("realizedLamports").asText()).isEqualTo("20000000");
        assertThat(body.get("feesLamports").asText()).isEqualTo("10000");
        JsonNode position = body.get("positions").get(0);
        assertThat(position.get("mint").asText()).isEqualTo(mint);
        assertThat(position.get("baseUnits").asText()).isEqualTo("60");
        assertThat(position.get("costLamports").asText()).isEqualTo("60000000");
        assertThat(body.toString()).doesNotContain(person.wallet().address());
    }

    @Test
    void aDifferenceWithTheLedgerAnswersTheTechnicalErrorOfTheRoute() throws Exception {
        Person person = signIn(true, true);
        UUID wallet = activeWallet(person, 5 * SOL);
        UUID bot = realBotRow(person.tenantId(), person.userId());
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minusSeconds(3_600),
                920_000);
        seed.buy(bot, mint, 100, 100_000_000L, 0);
        seed.sell(bot, mint, 40, 60_000_000L, 0);
        divergingCost.diverge.set(true);

        MockHttpServletResponse response = read(person, get(ROUTE));

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.has("correlationId")).isTrue();
        assertThat(problem.toString()).doesNotContain("Lamports").doesNotContain("REALIZED");
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
