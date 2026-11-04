package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.application.EquityPeakUpdater;
import com.memetrade.pnl.infra.EquityMarkingJob;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.Concurrently;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.CycleAuthContext;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 13 (written before the code): the equity peak (BR7.3, Q10, NFR4.5, NFR10.7). It starts at the FUNDING, rises
 * after fills and markings by a conditional update, never with an unknown price, inside the fill's transaction, and a
 * marking concurrent with a fill never raises it with a half-seen state (50 repetitions).
 */
class EquityPeakConcurrencyIT extends AbstractU7IT {

    private static final int REPETITIONS = 50;

    @Autowired
    private PaperExecution execution;

    @Autowired
    private EquityPeakUpdater updater;

    @Autowired
    private EquityMarkingJob marking;

    private PaperDriver driver;
    private LedgerFacts facts;
    private Principal person;
    private TokenId token;

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        facts = new LedgerFacts(jdbc);
        person = newPerson();
        token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
    }

    private BigDecimal peak(UUID bot) {
        return (BigDecimal) facts.peak(bot).get("peak_equity");
    }

    private BigDecimal last(UUID bot) {
        return (BigDecimal) facts.peak(bot).get("last_equity");
    }

    @Test
    void theFundingStartsThePeakAtTheFundedAmount() {
        UUID bot = driver.fundedBot(person, "1000.000000");

        assertThat(peak(bot)).isEqualByComparingTo("1000");
        assertThat(last(bot)).isEqualByComparingTo("1000");
    }

    @Test
    void c9TheMarkingByTheExitPriceRaisesThePeakTo1017996432() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.200000", "100000.000000", 6);

        Optional<BigDecimal> marked = transactions.execute(status -> updater.mark(bot));
        assertThat(marked).contains(new BigDecimal("1017.996432"));

        assertThat(last(bot)).isEqualByComparingTo("1017.996432");
        assertThat(peak(bot)).isEqualByComparingTo("1017.996432");
    }

    @Test
    void c8AnUnknownPriceEmptiesTheLastEquityAndKeepsThePeak() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.price(token, ControllableMarketData.unknown());

        Optional<BigDecimal> unknown = transactions.execute(status -> updater.mark(bot));
        assertThat(unknown).isEmpty();

        assertThat(last(bot)).isNull();
        assertThat(peak(bot)).isEqualByComparingTo("1000");
    }

    @Test
    void aLowerEquityLowersTheLastButNeverThePeak() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.200000", "100000.000000", 6);
        transactions.execute(status -> updater.mark(bot));
        market.set(token, "0.500000", "100000.000000", 6);

        transactions.execute(status -> updater.mark(bot));

        assertThat(peak(bot)).isEqualByComparingTo("1017.996432");
        assertThat(last(bot)).isLessThan(new BigDecimal("1000"));
    }

    @Test
    void theFillListenerUpdatesThePeakInTheSameTransactionAndRollsBackWithIt() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        Map<String, Object> before = facts.peak(bot);

        CycleAuthContext.run(
                person,
                () -> transactions.executeWithoutResult(status -> {
                    execution.execute(
                            PaperDriver.request(
                                    bot,
                                    com.memetrade.ledger.api.PaperEnums.OrderSide.BUY,
                                    token,
                                    "100",
                                    UUID.randomUUID(),
                                    1),
                            UUID.randomUUID().toString());
                    assertThat((BigDecimal) jdbc.sql("select last_equity from equity_peak where bot_id = :b")
                                    .param("b", bot)
                                    .query(BigDecimal.class)
                                    .single())
                            .as("inside the cycle, after the fill")
                            .isNotEqualByComparingTo((BigDecimal) before.get("last_equity"));
                    status.setRollbackOnly();
                }));

        assertThat(facts.peak(bot).get("last_equity")).isEqualTo(before.get("last_equity"));
        assertThat(facts.fills(bot)).isZero();
    }

    @Test
    void aMarkingConcurrentWithAFillNeverRaisesThePeakWithAHalfSeenState() {
        market.set(token, "1.000000", "100000.000000", 6);
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID bot = driver.fundedBot(person, "1000.000000");
            driver.buy(person, bot, token, "100");
            market.set(token, "1.500000", "100000.000000", 6);
            Optional<BigDecimal> beforeSell = transactions.execute(status -> updater.equityNow(bot));

            var outcomes = Concurrently.run(
                    () -> driver.sell(person, bot, token, "50"),
                    () -> transactions.execute(status -> updater.mark(bot)));

            assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
            Optional<BigDecimal> afterSell = transactions.execute(status -> updater.equityNow(bot));
            BigDecimal expected =
                    new BigDecimal("1000.000000").max(beforeSell.orElseThrow()).max(afterSell.orElseThrow());
            assertThat(peak(bot))
                    .as("repetition " + repetition + ": the peak is an equity computed under the lock")
                    .isEqualByComparingTo(expected);
            market.set(token, "1.000000", "100000.000000", 6);
        }
    }

    @Test
    void theMarkingRoundVisitsOpenAccountsAndSkipsALockedOne() throws Exception {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.200000", "100000.000000", 6);

        try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP.user(), APP.password())) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock =
                    holder.prepareStatement("select bot_id from paper_account where bot_id = ? for update")) {
                lock.setObject(1, bot);
                lock.executeQuery().close();
            }
            EquityMarkingJob.Round round = marking.runOnce();
            assertThat(round.skipped()).isGreaterThanOrEqualTo(1);
            holder.rollback();
        }
        assertThat(peak(bot)).isEqualByComparingTo("1000");

        EquityMarkingJob.Round round = marking.runOnce();

        assertThat(round.marked()).isGreaterThanOrEqualTo(1);
        assertThat(peak(bot)).isEqualByComparingTo("1017.996432");
    }
}
