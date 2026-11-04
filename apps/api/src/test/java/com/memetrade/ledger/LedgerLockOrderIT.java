package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.LedgerRows;
import com.memetrade.testsupport.PaperDriver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 (BR2.8, NFR10.5): executions on two tokens (with their daily summary rows), the closing, the pending close
 * job and the marking run at the same time on the same bot, 50 times, and never deadlock: every operation locks the
 * account first, then the positions by (chain, mint), then the daily summary, then the equity peak.
 */
class LedgerLockOrderIT extends AbstractU7IT {

    private static final int REPETITIONS = 50;
    private static final String DEADLOCK = "40P01";

    @Autowired
    private PaperExecution execution;

    @Autowired
    private LedgerReadModel readModel;

    @Autowired
    private com.memetrade.ledger.infra.PendingCloseJob pendingClose;

    @Autowired
    private com.memetrade.pnl.application.EquityPeakUpdater peaks;

    private UUID positionOf(UUID bot, TokenId token) {
        return jdbc.sql("select position_id from position where bot_id = :b and token_mint = :m")
                .param("b", bot)
                .param("m", token.mint())
                .query(UUID.class)
                .single();
    }

    @Test
    void executionClosingPendingCloseAndMarkingNeverDeadlock() {
        PaperDriver driver = new PaperDriver(execution, transactions);
        Principal person = newPerson();
        TokenId a = newToken();
        TokenId b = newToken();
        market.set(a, "1.000000", "100000.000000", 6);
        market.set(b, "2.000000", "100000.000000", 6);

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID bot = driver.fundedBot(person, "1000.000000");
            driver.buy(person, bot, a, "10");
            driver.buy(person, bot, b, "10");
            List<Callable<Object>> actors = new java.util.ArrayList<>(List.of(
                    () -> driver.buy(person, bot, b, "1"),
                    () -> driver.sell(person, bot, a, "1"),
                    () -> driver.buy(person, bot, a, "1"),
                    () -> driver.close(person, bot, UUID.randomUUID().toString()),
                    () -> transactions.execute(status -> readModel.lockAccountForMarking(bot, Duration.ofSeconds(5))),
                    () -> pendingClose.completeOne(bot, positionOf(bot, b)),
                    () -> transactions.execute(status -> peaks.mark(bot))));

            @SuppressWarnings("unchecked")
            List<Concurrently.Outcome<Object>> outcomes = Concurrently.run(actors.toArray(new Callable[0]));

            for (Concurrently.Outcome<Object> outcome : outcomes) {
                assertThat(outcome.failed() ? LedgerRows.sqlState(outcome.failure()) : "ok")
                        .as("repetition " + repetition + ": " + outcome.failure())
                        .isNotEqualTo(DEADLOCK)
                        .isEqualTo("ok");
            }
        }
    }
}
