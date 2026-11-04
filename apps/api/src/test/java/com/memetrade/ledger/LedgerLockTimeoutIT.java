package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.LedgerRows;
import com.memetrade.testsupport.PaperDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Passo 11 (NFR10.8): another transaction holds the lock of the account for 3 s; the execution gives up after the
 * {@code lock_timeout} of 2 s with a TECHNICAL failure (the cycle of U5 rolls back) and the marking after its 1 s. The
 * lock is held by an open transaction released when the test is done, never by sleeping.
 */
class LedgerLockTimeoutIT extends AbstractU7IT {

    @Autowired
    private PaperExecution execution;

    @Autowired
    private LedgerReadModel readModel;

    @Test
    void theExecutionGivesUpAfterTwoSecondsAndTheMarkingAfterOne() throws Exception {
        PaperDriver driver = new PaperDriver(execution, transactions);
        Principal person = newPerson();
        TokenId token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
        UUID bot = driver.fundedBot(person, "1000.000000");

        try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP.user(), APP.password())) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select bot_id from paper_account where bot_id = ? for update")) {
                lock.setObject(1, bot);
                lock.executeQuery().close();
            }

            long started = System.nanoTime();
            Throwable execute =
                    org.assertj.core.api.Assertions.catchThrowable(() -> driver.buy(person, bot, token, "1"));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(execute).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(LedgerRows.sqlState(execute)).isEqualTo("55P03");
            assertThat(waited).isBetween(Duration.ofMillis(1_900), Duration.ofMillis(3_000));

            started = System.nanoTime();
            Throwable marking = org.assertj.core.api.Assertions.catchThrowable(
                    () -> transactions.execute(status -> readModel.lockAccountForMarking(bot, Duration.ofSeconds(1))));
            waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(marking).isInstanceOf(PessimisticLockingFailureException.class);
            assertThat(waited).isBetween(Duration.ofMillis(900), Duration.ofMillis(2_000));
            holder.rollback();
        }
        assertThat(driver.buy(person, bot, token, "1"))
                .as("once released, the next cycle executes")
                .isInstanceOf(PaperExecution.ExecutionResult.Filled.class);
    }
}
