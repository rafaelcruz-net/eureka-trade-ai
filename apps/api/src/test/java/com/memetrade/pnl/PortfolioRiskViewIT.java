package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.PortfolioRiskView;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.CycleAuthContext;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.PaperDriver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Passo 14 (written before the code): C13 for U6 (BR7.1, BR7.2, BR7.4, BR9.1, NFR5.2): exposure by cost, the day loss
 * read straight from the fills (never from the daily summary), the equity as {@code Optional}, the balance and the
 * last trade; a bot of another person is refused and audited.
 */
class PortfolioRiskViewIT extends AbstractU7IT {

    private static final LocalDate TODAY = LocalDate.ofInstant(MutableClock.DEFAULT_START, java.time.ZoneOffset.UTC);

    @Autowired
    private PaperExecution execution;

    @Autowired
    private PortfolioRiskView riskView;

    private PaperDriver driver;
    private Principal person;
    private TokenId token;

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        person = newPerson();
        token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
    }

    private RiskSnapshot snapshot(Principal who, UUID bot, TokenId evaluated, LocalDate day) {
        return inCycle(who, () -> riskView.snapshot(bot, evaluated.chain(), evaluated.mint(), day));
    }

    @Test
    void c2TheDayLossCountsRealizedAndFeesOfTheUtcDay() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "0.800000", "100000.000000", 6);
        driver.sell(person, bot, token, "100");

        RiskSnapshot snapshot = snapshot(person, bot, token, TODAY);

        assertThat(snapshot.realizedDayLossWithFees()).isEqualByComparingTo("21.604408");
        assertThat(snapshot.availableVirtualBalance()).isEqualByComparingTo("978.395592");
        assertThat(snapshot.tokenExposure()).isEqualByComparingTo("0");
        assertThat(snapshot.lastTradeAt()).contains(MutableClock.DEFAULT_START);
        assertThat(snapshot(person, bot, token, TODAY.plusDays(1)).realizedDayLossWithFees())
                .as("another UTC day")
                .isEqualByComparingTo("0");
    }

    @Test
    void theExposureIsTheCostOfTheOpenPositions() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        TokenId other = newToken();
        market.set(other, "2.000000", "100000.000000", 6);
        driver.buy(person, bot, token, "100");
        driver.buy(person, bot, other, "100");

        RiskSnapshot snapshot = snapshot(person, bot, other, TODAY);

        assertThat(snapshot.tokenExposure()).isEqualByComparingTo("201.4");
        assertThat(snapshot.totalExposure()).isEqualByComparingTo("302");
        assertThat(snapshot.peakEquity()).isGreaterThanOrEqualTo(new BigDecimal("1000"));
    }

    @Test
    void theDayLossNeverReadsTheDailySummary() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "0.800000", "100000.000000", 6);
        driver.sell(person, bot, token, "100");
        appRole()
                .sql("update pnl_daily set realized = realized - 1000, fill_count = fill_count + 1 where bot_id = :b")
                .param("b", bot)
                .update();

        assertThat(snapshot(person, bot, token, TODAY).realizedDayLossWithFees())
                .as("a wrong summary never changes what the barrier sees")
                .isEqualByComparingTo("21.604408");
    }

    @Test
    void c9TheEquityIsKnownWithReliablePricesAndEmptyOtherwiseNeverZero() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        market.set(token, "1.200000", "100000.000000", 6);

        assertThat(snapshot(person, bot, token, TODAY).equity())
                .hasValueSatisfying(equity -> assertThat(equity).isEqualByComparingTo("1017.996432"));

        market.price(token, ControllableMarketData.unknown());
        assertThat(snapshot(person, bot, token, TODAY).equity()).isEmpty();
    }

    @Test
    void aBotOfAnotherPersonIsRefusedAndAudited() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        Principal intruder = samePersonTenant(person);
        LedgerFacts facts = new LedgerFacts(jdbc);
        long before = facts.eventsWithDetail(AuditActions.REQUEST_FORBIDDEN, "operation", "pnl.risk-snapshot");

        assertThatThrownBy(() -> snapshot(intruder, bot, token, TODAY))
                .isInstanceOf(PortfolioRiskView.TenantMismatchException.class);
        assertThatThrownBy(() -> snapshot(newPerson(), bot, token, TODAY))
                .as("another tenant")
                .isInstanceOf(PortfolioRiskView.TenantMismatchException.class);
        assertThat(facts.eventsWithDetail(AuditActions.REQUEST_FORBIDDEN, "operation", "pnl.risk-snapshot"))
                .isEqualTo(before + 2);
    }

    @Test
    void aBotWithoutAccountIsATechnicalFailureAndTheViewNeedsTheCycleTransaction() {
        assertThatThrownBy(() -> snapshot(person, UUID.randomUUID(), token, TODAY))
                .isInstanceOf(IllegalStateException.class);
        UUID bot = driver.fundedBot(person, "1000.000000");
        assertThatThrownBy(() ->
                        CycleAuthContext.as(person, () -> riskView.snapshot(bot, token.chain(), token.mint(), TODAY)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aFreshBotHasTheFundingAsBalancePeakAndEquity() {
        UUID bot = driver.fundedBot(person, "250.000000");

        RiskSnapshot snapshot = snapshot(person, bot, token, TODAY);

        assertThat(snapshot.availableVirtualBalance()).isEqualByComparingTo("250");
        assertThat(snapshot.peakEquity()).isEqualByComparingTo("250");
        assertThat(snapshot.equity()).hasValueSatisfying(e -> assertThat(e).isEqualByComparingTo("250"));
        assertThat(snapshot.totalExposure()).isEqualByComparingTo("0");
        assertThat(snapshot.lastTradeAt()).isEmpty();
    }
}
