package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.OwnQuantitySell;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotMarket;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.OwnQuantityMetrics;
import com.memetrade.pnl.api.HoldingsResult;
import com.memetrade.pnl.api.RealBotHoldings;
import com.memetrade.pnl.api.UnavailableReason;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 261121-paper-real-unico U4, Etapa 4, T5 (written before the code; F1 steps 0 and 1, BR2.5, BR3.1, NFR1.10, NFR2.10):
 * the checks before the read, P0 to P3, in order and without a lock. A PAPER bot, the flag off, a slot older than 5
 * minutes, a slot already claimed, a bot not ACTIVE, a bot with a pause reason and the system pause give no read of the
 * own holdings and no read of the mark M0 (except when P1, the read of M0 itself, is the check that fails). M0 comes
 * from the same row as P1 and is read before the own holdings; {@code real_own_holdings_reads_total} counts before the
 * call whatever its answer.
 */
class OwnHoldingsStepTest {

    private static final Instant SLOT = Instant.parse("2026-11-24T12:00:00Z");
    private static final BotRef BOT =
            new BotRef(UUID.randomUUID(), new TenantScope(UUID.randomUUID(), UUID.randomUUID()));
    private static final Duration BUDGET = Duration.ofMillis(250);

    private final JdbcBotRepository bots = mock(JdbcBotRepository.class);
    private final RealBotHoldings holdings = mock(RealBotHoldings.class);
    private final OwnQuantitySell flag = mock(OwnQuantitySell.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(SLOT.plusSeconds(60));
    private final OwnHoldingsStreaks streaks = new OwnHoldingsStreaks(clock);
    private final OwnQuantityMetrics metrics = new OwnQuantityMetrics(meters, streaks);
    private final OwnHoldingsStep step = new OwnHoldingsStep(bots, holdings, flag, metrics, streaks, clock);

    @BeforeEach
    void setUp() {
        when(flag.enabled()).thenReturn(true);
        when(bots.precheckRow(BOT)).thenReturn(Optional.of(row(BotState.ACTIVE, Set.of(), BotMarket.REAL, 17)));
        when(bots.slotClaimed(BOT.botId(), SLOT)).thenReturn(false);
        when(bots.systemPaused()).thenReturn(false);
        when(holdings.holdings(BOT.scope().tenantId(), BOT.scope().ownerUserId(), BOT.botId(), BUDGET))
                .thenReturn(new HoldingsResult.Available(List.of(), SLOT));
    }

    @Test
    void aRealBotThatWillRunReadsM0FromP1BeforeTheOwnHoldings() {
        HoldingsSnapshot snapshot = step.read(BOT, Optional.of(BotMarket.REAL), SLOT);

        assertThat(snapshot).isEqualTo(new HoldingsSnapshot.Read(17, new HoldingsResult.Available(List.of(), SLOT)));
        InOrder order = inOrder(flag, bots, holdings);
        order.verify(flag).enabled();
        order.verify(bots).precheckRow(BOT);
        order.verify(bots).slotClaimed(BOT.botId(), SLOT);
        order.verify(bots).systemPaused();
        order.verify(holdings).holdings(BOT.scope().tenantId(), BOT.scope().ownerUserId(), BOT.botId(), BUDGET);
        assertThat(reads()).isEqualTo(1.0);
    }

    @Test
    void aPaperBotAndTheFlagOffReadNothing() {
        assertThat(step.read(BOT, Optional.of(BotMarket.PAPER), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);
        verify(flag, never()).enabled();
        when(flag.enabled()).thenReturn(false);
        assertThat(step.read(BOT, Optional.of(BotMarket.REAL), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);

        verify(bots, never()).precheckRow(any());
        verify(holdings, never()).holdings(any(), any(), any(), any());
        assertThat(reads()).isZero();
    }

    @Test
    void aSlotOlderThanFiveMinutesReadsNothing() {
        clock.set(SLOT.plus(Duration.ofMinutes(5)).plusSeconds(1));

        assertThat(step.read(BOT, Optional.of(BotMarket.REAL), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);

        verify(bots, never()).precheckRow(any());
        verify(holdings, never()).holdings(any(), any(), any(), any());
    }

    @Test
    void aBotNotActiveWithAPauseReasonOrOfAnotherMarketReadsNoOwnHoldings() {
        for (var row : List.of(
                row(BotState.PAUSED, Set.of(PauseReason.PERSON), BotMarket.REAL, 1),
                row(BotState.CLOSING_PENDING, Set.of(), BotMarket.REAL, 1),
                row(BotState.ACTIVE, Set.of(PauseReason.SYSTEM), BotMarket.REAL, 1),
                row(BotState.ACTIVE, Set.of(), BotMarket.PAPER, 1))) {
            when(bots.precheckRow(BOT)).thenReturn(Optional.of(row));
            assertThat(step.read(BOT, Optional.empty(), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);
        }
        when(bots.precheckRow(BOT)).thenReturn(Optional.empty());
        assertThat(step.read(BOT, Optional.empty(), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);

        verify(bots, never()).slotClaimed(any(), any());
        verify(holdings, never()).holdings(any(), any(), any(), any());
        assertThat(reads()).isZero();
    }

    @Test
    void aClaimedSlotOrTheSystemPauseReadsNoOwnHoldings() {
        when(bots.slotClaimed(BOT.botId(), SLOT)).thenReturn(true);
        assertThat(step.read(BOT, Optional.of(BotMarket.REAL), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);
        verify(bots, never()).systemPaused();

        when(bots.slotClaimed(BOT.botId(), SLOT)).thenReturn(false);
        when(bots.systemPaused()).thenReturn(true);
        assertThat(step.read(BOT, Optional.of(BotMarket.REAL), SLOT)).isInstanceOf(HoldingsSnapshot.Skipped.class);

        verify(holdings, never()).holdings(any(), any(), any(), any());
        assertThat(reads()).isZero();
    }

    @Test
    void theReadCountsWhateverItsAnswerAndAnUnavailableOneCountsItsReason() {
        when(holdings.holdings(any(), any(), any(), any()))
                .thenReturn(new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED));

        HoldingsSnapshot snapshot = step.read(BOT, Optional.of(BotMarket.REAL), SLOT);

        assertThat(snapshot)
                .isEqualTo(new HoldingsSnapshot.Read(
                        17, new HoldingsResult.Unavailable(UnavailableReason.BUDGET_EXCEEDED)));
        assertThat(reads()).isEqualTo(1.0);
        assertThat(meters.get("real_own_holdings_unavailable")
                        .tag("reason", "BUDGET_EXCEEDED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(streaks.streak(BOT.botId())).isEqualTo(1);
    }

    /**
     * Review R-02 (BR2.6; written before the fix): an exception in the checks before the read (P1 to P3) never leaves
     * the step. It behaves like an unavailable read: no own holdings for the cut (the cycle sells nothing of the
     * wallet), the read and its reason READ_FAILED are counted, the streak goes up and one WARN line is written.
     */
    @Test
    void aFailureOfTheChecksBeforeTheReadNeverLeavesTheStepAndCountsAsReadFailed() {
        for (Runnable failing : List.<Runnable>of(
                () -> when(bots.precheckRow(BOT)).thenThrow(new IllegalStateException("database down (test)")),
                () -> when(bots.slotClaimed(BOT.botId(), SLOT))
                        .thenThrow(new IllegalStateException("database down (test)")),
                () -> when(bots.systemPaused()).thenThrow(new IllegalStateException("database down (test)")))) {
            org.mockito.Mockito.reset(bots);
            setUp();
            failing.run();
            double before = reads();

            HoldingsSnapshot snapshot = step.read(BOT, Optional.of(BotMarket.REAL), SLOT);

            assertThat(snapshot).isNotInstanceOf(HoldingsSnapshot.NotRead.class);
            if (snapshot instanceof HoldingsSnapshot.Read read) {
                assertThat(read.result()).isEqualTo(new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED));
            } else {
                assertThat(snapshot).isEqualTo(new HoldingsSnapshot.Skipped("PRE_PHASE_FAILED"));
            }
            assertThat(reads()).isEqualTo(before + 1);
        }
        verify(holdings, never()).holdings(any(), any(), any(), any());
        assertThat(meters.get("real_own_holdings_unavailable")
                        .tag("reason", "READ_FAILED")
                        .counter()
                        .count())
                .isEqualTo(3.0);
        assertThat(streaks.streak(BOT.botId())).isEqualTo(3);
    }

    /** Review R-02: a read that breaks its contract and throws is an unavailable read, counted once. */
    @Test
    void aReadThatThrowsIsReadFailedAndCountsOnce() {
        when(holdings.holdings(any(), any(), any(), any())).thenThrow(new IllegalStateException("defect (test)"));

        HoldingsSnapshot snapshot = step.read(BOT, Optional.of(BotMarket.REAL), SLOT);

        assertThat(snapshot)
                .isEqualTo(
                        new HoldingsSnapshot.Read(17, new HoldingsResult.Unavailable(UnavailableReason.READ_FAILED)));
        assertThat(reads()).isEqualTo(1.0);
        assertThat(meters.get("real_own_holdings_unavailable")
                        .tag("reason", "READ_FAILED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /** Review R-02: a failure of the bookkeeping after a good read keeps the read (the cut still uses it). */
    @Test
    void aFailureOfTheStreaksAfterTheReadKeepsTheRead() {
        OwnHoldingsStreaks broken = mock(OwnHoldingsStreaks.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("defect (test)"))
                .when(broken)
                .update(any(), any());
        OwnHoldingsStep withBrokenStreaks = new OwnHoldingsStep(bots, holdings, flag, metrics, broken, clock);

        HoldingsSnapshot snapshot = withBrokenStreaks.read(BOT, Optional.of(BotMarket.REAL), SLOT);

        assertThat(snapshot).isEqualTo(new HoldingsSnapshot.Read(17, new HoldingsResult.Available(List.of(), SLOT)));
        assertThat(reads()).isEqualTo(1.0);
    }

    private double reads() {
        return meters.get("real_own_holdings_reads").counter().count();
    }

    private static JdbcBotRepository.PrecheckRow row(
            BotState state, java.util.Set<PauseReason> reasons, BotMarket market, long mark) {
        EnumSet<PauseReason> set = EnumSet.noneOf(PauseReason.class);
        set.addAll(reasons);
        return new JdbcBotRepository.PrecheckRow(state, set, market, mark);
    }

    private static final class Set {
        static java.util.Set<PauseReason> of(PauseReason... reasons) {
            return java.util.Set.of(reasons);
        }
    }
}
