package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.OwnQuantitySell;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.OwnHoldings;
import com.memetrade.bots.domain.OwnPosition;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.OwnQuantityMetrics;
import com.memetrade.decision.domain.DecisionEngine;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.SyntheticIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 261121-paper-real-unico U4, Etapa 16 (after the layer; NFR3.7, NFR3.9, NFR3.10): the five log events of the own
 * quantity (unavailable, BOT_NOT_OWNED, STALE_SNAPSHOT, NO_OWN_QUANTITY, CAPPED) are written once each, with the
 * correlation id of the cycle and the id of the bot, and never with the address of the bot wallet or of the main
 * wallet, a signature, a token, an Authorization header, a nonce nor SQL.
 */
class OwnQuantityLogRedactionTest {

    private static final String MINT = SyntheticIds.mint();
    private static final String BOT_WALLET = SyntheticIds.mint();
    private static final String MAIN_WALLET = SyntheticIds.mint();
    private static final String SIGNATURE = SyntheticIds.signature();
    private static final BotRef BOT =
            new BotRef(UUID.randomUUID(), new TenantScope(UUID.randomUUID(), UUID.randomUUID()));

    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();
    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(OwnHoldingsStep.class), (Logger)
            LoggerFactory.getLogger(CycleRunner.class));
    private final MutableClock clock = new MutableClock();
    private final OwnHoldingsStreaks streaks = new OwnHoldingsStreaks(clock);
    private final OwnQuantityMetrics metrics = new OwnQuantityMetrics(new SimpleMeterRegistry(), streaks);

    @BeforeEach
    void capture() {
        for (Logger logger : loggers) {
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            appenders.add(appender);
        }
        MDC.put(CorrelationId.MDC_KEY, "cycle-correlation");
    }

    @AfterEach
    void release() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).detachAppender(appenders.get(i));
        }
        MDC.remove(CorrelationId.MDC_KEY);
    }

    @Test
    void theFiveEventsCarryTheCorrelationAndTheBotAndNoSecret() {
        step(UnavailableReason.BUDGET_EXCEEDED);
        step(UnavailableReason.BOT_NOT_OWNED);
        CycleRunner runner = runner();
        Bot bot = bot(18);
        runner.cut(bot, sell("100"), known(100_000_000L), 17); // STALE_SNAPSHOT: M0 17, M1 18
        runner.cut(bot, sell("100"), known(0), 18); // NO_OWN_QUANTITY
        runner.cut(bot, sell("150"), known(100_000_000L), 18); // CAPPED

        List<ILoggingEvent> events = new ArrayList<>();
        appenders.forEach(appender -> events.addAll(appender.list));
        for (String event : List.of(
                "event=REAL_OWN_HOLDINGS_UNAVAILABLE botId=" + BOT.botId() + " reason=BUDGET_EXCEEDED",
                "event=REAL_OWN_HOLDINGS_UNAVAILABLE botId=" + BOT.botId() + " reason=BOT_NOT_OWNED",
                "event=REAL_OWN_SELL_STALE",
                "event=REAL_OWN_SELL_NO_QUANTITY",
                "event=REAL_OWN_SELL_CAPPED")) {
            assertThat(events)
                    .as(event)
                    .filteredOn(e -> e.getFormattedMessage().contains(event))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getMDCPropertyMap()).containsEntry(CorrelationId.MDC_KEY, "cycle-correlation");
                        assertThat(e.getFormattedMessage()).contains(BOT.botId().toString());
                    });
        }
        assertThat(events).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain(BOT_WALLET)
                .doesNotContain(MAIN_WALLET)
                .doesNotContain(SIGNATURE)
                .doesNotContainIgnoringCase("authorization")
                .doesNotContainIgnoringCase("bearer")
                .doesNotContainIgnoringCase("nonce")
                .doesNotContainIgnoringCase("select ")
                .doesNotContainIgnoringCase("token="));
    }

    private void step(UnavailableReason reason) {
        JdbcBotRepository bots = mock(JdbcBotRepository.class);
        when(bots.precheckRow(BOT))
                .thenReturn(
                        Optional.of(new JdbcBotRepository.PrecheckRow(BotState.ACTIVE, Set.of(), BotMarket.REAL, 1)));
        RealBotHoldings holdings = (tenant, user, bot, budget) -> new HoldingsResult.Unavailable(reason);
        OwnQuantitySell flag = () -> true;
        new OwnHoldingsStep(bots, holdings, flag, metrics, streaks, clock)
                .read(BOT, Optional.of(BotMarket.REAL), SlotDispatcher.slotOf(clock.instant()));
    }

    private CycleRunner runner() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        CycleRunner runner = new CycleRunner(
                null,
                null,
                null,
                null,
                null,
                null,
                mock(DecisionEngine.class),
                new DecisionJson(),
                null,
                null,
                null,
                null,
                null,
                clock,
                manager,
                Optional.empty());
        RealCycle real = mock(RealCycle.class);
        when(real.decimalsOf(any())).thenReturn(Optional.of(6));
        runner.useRealCycle(real);
        runner.useOwnQuantity(null, () -> true, metrics);
        return runner;
    }

    private static Bot bot(long mark) {
        Bot bot = mock(Bot.class);
        when(bot.botId()).thenReturn(BOT.botId());
        when(bot.changeSeq()).thenReturn(mark);
        return bot;
    }

    private static OwnHoldings known(long available) {
        return new OwnHoldings.Known(List.of(new OwnPosition(MINT, 100_000_000L, 100_000_000L, available)));
    }

    private static DecisionOutcome sell(String quantity) {
        return OwnQuantityCycle.sell(new TokenId("solana", MINT), new BigDecimal(quantity));
    }
}
