package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.domain.TokenSelection;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.NoTradeReason;
import com.memetrade.decision.domain.StrategyId;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.config.RecordedSwapFixture;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * 261113-trading-real-bot U4, test strategy of the recorded swap (test-after: a test-profile helper of the e2e, not a
 * rule of the product). It acts only on the REAL bot whose selection is exactly the recorded mint, buys ONCE for exactly
 * the recorded lamports, then only holds or waits; it exists only in the test profile and in the worker.
 */
class RecordedSwapStrategyTest {

    private static final String MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";
    private static final String OTHER = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    private static final Instant SLOT = Instant.parse("2026-11-15T12:00:00Z");
    private static final RecordedSwapFixture FIXTURE = new RecordedSwapFixture(MINT, 5, 50_000_000L, 154_230_000_000L);

    private final JdbcCycleRepository cycles = mock(JdbcCycleRepository.class);
    private final RecordedSwapStrategy strategy = new RecordedSwapStrategy(Optional.of(FIXTURE), cycles);

    private static Bot bot(BotMarket market, List<String> mints) {
        return new Bot(
                UUID.randomUUID(),
                new TenantScope(UUID.randomUUID(), UUID.randomUUID()),
                "Swap gravado (teste)",
                StrategyId.MOMENTUM_VOLUME,
                "v1",
                Map.of(),
                new TokenSelection(
                        mints.stream().map(m -> new TokenId("solana", m)).toList(), List.of()),
                Optional.of(new BigDecimal("1000.000000")),
                BotStatus.of(BotState.ACTIVE),
                0,
                4,
                Optional.of(1),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                SLOT,
                SLOT,
                0,
                market);
    }

    @Test
    void theFirstCycleOfTheTestBotBuysExactlyTheRecordedLamports() {
        Bot bot = bot(BotMarket.REAL, List.of(MINT));
        when(cycles.hasOutcome(bot.botId(), "REAL_RECORDED")).thenReturn(false);

        RecordedSwapStrategy.Decision decision =
                strategy.decide(bot, false, SLOT, List.of()).orElseThrow();

        assertThat(decision.outcome().action()).isEqualTo(DecisionAction.BUY);
        assertThat(decision.outcome().token()).contains(new TokenId("solana", MINT));
        assertThat(decision.buyLamports()).contains(50_000_000L);
        assertThat(decision.outcome().requestedQuantity()).contains(new BigDecimal("1542300.00000"));
        assertThat(decision.outcome().validUntil()).isEqualTo(SLOT.plusSeconds(300));
    }

    @Test
    void afterARecordedOrderTheBotNeverBuysAgain() {
        Bot bot = bot(BotMarket.REAL, List.of(MINT));
        when(cycles.hasOutcome(bot.botId(), "REAL_RECORDED")).thenReturn(true);

        RecordedSwapStrategy.Decision decision =
                strategy.decide(bot, false, SLOT, List.of()).orElseThrow();

        assertThat(decision.outcome().action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(decision.outcome().noTradeReason()).contains(NoTradeReason.NO_SIGNAL);
        assertThat(decision.buyLamports()).isEmpty();
    }

    @Test
    void withTheRecordedPositionOpenTheBotHoldsAndNeverSells() {
        Bot bot = bot(BotMarket.REAL, List.of(MINT));

        RecordedSwapStrategy.Decision decision =
                strategy.decide(bot, true, SLOT, List.of()).orElseThrow();

        assertThat(decision.outcome().action()).isEqualTo(DecisionAction.HOLD);
        assertThat(decision.buyLamports()).isEmpty();
        verify(cycles, never()).hasOutcome(any(), anyString());
    }

    @Test
    void aPaperBotOfTheRecordedMintKeepsTheDecisionEngine() {
        assertThat(strategy.decide(bot(BotMarket.PAPER, List.of(MINT)), false, SLOT, List.of()))
                .isEmpty();
    }

    @Test
    void aRealBotOfAnotherSelectionKeepsTheDecisionEngine() {
        assertThat(strategy.decide(bot(BotMarket.REAL, List.of(OTHER)), false, SLOT, List.of()))
                .isEmpty();
        assertThat(strategy.decide(bot(BotMarket.REAL, List.of(MINT, OTHER)), false, SLOT, List.of()))
                .isEmpty();
    }

    @Test
    void withoutTheRecordingsNothingChanges() {
        RecordedSwapStrategy without = new RecordedSwapStrategy(Optional.empty(), cycles);

        assertThat(without.decide(bot(BotMarket.REAL, List.of(MINT)), false, SLOT, List.of()))
                .isEmpty();
        verify(cycles, never()).hasOutcome(any(), anyString());
    }

    @Test
    void theStrategyExistsOnlyInTheTestProfileOfTheWorker() {
        assertThat(RecordedSwapStrategy.class.getAnnotation(Profile.class).value())
                .containsExactly("test");
        assertThat(RecordedSwapStrategy.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);
    }
}
