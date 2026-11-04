package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 (AC4.6.3, NFR6.8): the same key and the same content in two cycles at the same time, 50 times: exactly
 * one order, one fill and one set of entries; the other cycle answers Duplicate with the same result.
 */
class PaperExecutionIdempotencyIT extends AbstractU7IT {

    private static final int REPETITIONS = 50;

    @Autowired
    private PaperExecution execution;

    @Test
    void theSameKeyInTwoThreadsGivesOneOperationFiftyTimes() {
        PaperDriver driver = new PaperDriver(execution, transactions);
        LedgerFacts facts = new LedgerFacts(jdbc);
        Principal person = newPerson();
        TokenId token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID bot = driver.fundedBot(person, "1000.000000");
            String key = UUID.randomUUID().toString();
            var request = PaperDriver.request(bot, OrderSide.BUY, token, "10", UUID.randomUUID(), 1);

            List<Concurrently.Outcome<ExecutionResult>> outcomes = Concurrently.run(
                    () -> driver.order(person, request, key), () -> driver.order(person, request, key));

            assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
            List<ExecutionResult> results =
                    outcomes.stream().map(Concurrently.Outcome::value).toList();
            assertThat(results)
                    .filteredOn(r -> r instanceof ExecutionResult.Filled)
                    .hasSize(1);
            ExecutionResult filled = results.stream()
                    .filter(r -> r instanceof ExecutionResult.Filled)
                    .findFirst()
                    .orElseThrow();
            assertThat(results).contains(new ExecutionResult.Duplicate(filled));
            assertThat(facts.orders(bot)).as("repetition " + repetition).isEqualTo(1);
            assertThat(facts.fills(bot)).isEqualTo(1);
            assertThat(facts.entries(bot)).as("FUNDING plus three entries").isEqualTo(4);
        }
    }

    @Test
    void theSameFundingKeyInTwoThreadsFundsOnce() {
        PaperDriver driver = new PaperDriver(execution, transactions);
        Principal person = newPerson();
        UUID bot = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        var outcomes = Concurrently.run(
                () -> driver.fund(person, bot, "1000.000000", key), () -> driver.fund(person, bot, "1000.000000", key));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes)
                .extracting(o -> ((PaperExecution.FundResult.Funded) o.value()).replayed())
                .containsExactlyInAnyOrder(false, true);
        assertThat(new LedgerFacts(jdbc).entries(bot)).isEqualTo(1);
    }
}
