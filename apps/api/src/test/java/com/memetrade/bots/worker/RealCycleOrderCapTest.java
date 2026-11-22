package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.bots.application.RealDecisionStep;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.riskcontrol.api.RealAccountLimits;
import com.memetrade.riskcontrol.api.RealAccountLimitsView;
import com.memetrade.riskcontrol.api.RealAccountLimitsView.Amount;
import com.memetrade.riskcontrol.api.RealLimitsUnavailable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * 261121-paper-real-unico U1 (BR1.9, review R-03): the cap of a buy of a REAL bot fails CLOSED. Any failure of the read
 * of C9, an overflow of the exact arithmetic included, gives {@link RealCycle#NO_BUY} with a WARN naming only the kind
 * of failure; it never escapes and never breaks the cycle of the bot.
 */
class RealCycleOrderCapTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final BigDecimal SOL_USD = new BigDecimal("150.000000");

    private final RealAccountLimits accountLimits = mock(RealAccountLimits.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final RealCycle cycle = new RealCycle(
            mock(RealStateQuery.class),
            mock(RealLedger.class),
            mock(MarketDataQuery.class),
            mock(RealDecisionStep.class),
            accountLimits,
            transactions);
    private final Bot bot = mock(Bot.class);
    private final RealCycle.Inputs inputs = new RealCycle.Inputs(new BigDecimal("10"), List.of(), List.of(), SOL_USD);

    private final Logger logger = (Logger) LoggerFactory.getLogger(RealCycle.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level level;

    @BeforeEach
    void setUp() {
        when(bot.scope()).thenReturn(new TenantScope(TENANT, OWNER));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        appender.start();
        logger.addAppender(appender);
        level = logger.getLevel();
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(level);
    }

    @Test
    void aKnownOrderLimitIsItsDollarsAtTheSolOfTheCycleRoundedDown() {
        when(accountLimits.read(TENANT, OWNER))
                .thenReturn(Optional.of(new RealAccountLimitsView(
                        new Amount.Known(5_000_000_000L, null),
                        new Amount.Known(333_333_333L, new BigDecimal("10")),
                        20,
                        new Amount.Known(1_000_000_000L, new BigDecimal("20")))));

        // 333_333_333 lamports x 150 / 1e9 = 49.99999995 -> 49.999999 (down).
        assertThat(cycle.maxOrderSize(bot, inputs)).isEqualByComparingTo("49.999999");
    }

    @Test
    void anOverflowOfTheExactArithmeticIsNoBuyWithAWarnNeverAnEscapedException() {
        when(accountLimits.read(TENANT, OWNER)).thenThrow(new ArithmeticException("long overflow"));

        assertThat(cycle.maxOrderSize(bot, inputs)).isSameAs(RealCycle.NO_BUY);

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("ArithmeticException");
        });
    }

    @Test
    void anUnavailableReadIsNoBuyWithAWarn() {
        when(accountLimits.read(TENANT, OWNER)).thenThrow(new RealLimitsUnavailable(new IllegalStateException("down")));

        assertThat(cycle.maxOrderSize(bot, inputs)).isSameAs(RealCycle.NO_BUY);
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("RealLimitsUnavailable"));
    }

    @Test
    void withoutAnOrderLimitOrWithoutAWalletThereIsNoBuy() {
        when(accountLimits.read(TENANT, OWNER))
                .thenReturn(Optional.of(new RealAccountLimitsView(
                        new Amount.NotDefined(), new Amount.NotDefined(), 20, new Amount.NotDefined())));
        assertThat(cycle.maxOrderSize(bot, inputs)).isSameAs(RealCycle.NO_BUY);

        when(accountLimits.read(TENANT, OWNER)).thenReturn(Optional.empty());
        assertThat(cycle.maxOrderSize(bot, inputs)).isSameAs(RealCycle.NO_BUY);
    }
}
