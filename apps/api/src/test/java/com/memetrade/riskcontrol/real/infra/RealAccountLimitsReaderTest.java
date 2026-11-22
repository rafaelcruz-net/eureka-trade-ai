package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.riskcontrol.api.RealAccountLimitsView;
import com.memetrade.riskcontrol.api.RealAccountLimitsView.Amount;
import com.memetrade.riskcontrol.api.RealLimitsUnavailable;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 261121-paper-real-unico U1, Step 9 (BR3.1 to BR3.3, C9): the four REAL limits of the account from the same inputs as
 * the barrier, in exact integers; a cap of zero is NOT_DEFINED (never a zero limit); the daily loss is on the base
 * {@code min(cap at the start of the UTC day, cap now)}; percentages are {@code bps / 100}, exact.
 */
class RealAccountLimitsReaderTest {

    private static final long SOL = 1_000_000_000L;
    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();

    private final RealStateQuery states = mock(RealStateQuery.class);
    private final JdbcRealRiskStore store = mock(JdbcRealRiskStore.class);
    private final RealAccountLimitsReader reader =
            new RealAccountLimitsReader(states, store, RealRiskLimits.DEFAULTS, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aCapOfFiveSolGivesTheFourLimitsOfTheBarrier() {
        wallet(5 * SOL);

        RealAccountLimitsView view = reader.read(TENANT, USER).orElseThrow();

        assertThat(view.cap()).isEqualTo(new Amount.Known(5 * SOL, null));
        assertThat(view.orderMax()).isEqualTo(new Amount.Known(500_000_000L, new BigDecimal("10")));
        assertThat(view.dailyBuys()).isEqualTo(20);
        assertThat(view.dailyLoss()).isEqualTo(new Amount.Known(1_000_000_000L, new BigDecimal("20")));
    }

    @Test
    void aCapOfZeroIsNotDefinedForTheCapTheOrderAndTheLossButTheBuysStayKnown() {
        wallet(0);

        RealAccountLimitsView view = reader.read(TENANT, USER).orElseThrow();

        assertThat(view.cap()).isInstanceOf(Amount.NotDefined.class);
        assertThat(view.orderMax()).isInstanceOf(Amount.NotDefined.class);
        assertThat(view.dailyLoss()).isInstanceOf(Amount.NotDefined.class);
        assertThat(view.dailyBuys()).isEqualTo(20);
    }

    @Test
    void theOrderAndTheLossAreRoundedDownInLamports() {
        wallet(1_234_567_891L);

        RealAccountLimitsView view = reader.read(TENANT, USER).orElseThrow();

        // 1_234_567_891 x 1000 / 10000 = 123_456_789.1 -> 123_456_789; x 2000 / 10000 = 246_913_578.2 -> 246_913_578.
        assertThat(((Amount.Known) view.orderMax()).lamports()).isEqualTo(123_456_789L);
        assertThat(((Amount.Known) view.dailyLoss()).lamports()).isEqualTo(246_913_578L);
    }

    @Test
    void theDailyLossIsOnTheSmallerOfTheStartCapAndTheCapNow() {
        wallet(5 * SOL);
        snapshot(2 * SOL);
        assertThat(((Amount.Known) reader.read(TENANT, USER).orElseThrow().dailyLoss()).lamports())
                .isEqualTo(400_000_000L);

        snapshot(9 * SOL);
        assertThat(((Amount.Known) reader.read(TENANT, USER).orElseThrow().dailyLoss()).lamports())
                .isEqualTo(1_000_000_000L);
    }

    @Test
    void withoutAnActiveWalletThereIsNoLimit() {
        when(states.readRealState(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(reader.read(TENANT, USER)).isEmpty();
    }

    @Test
    void aFailedReadIsRealLimitsUnavailable() {
        when(states.readRealState(TENANT, USER)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> reader.read(TENANT, USER)).isInstanceOf(RealLimitsUnavailable.class);
    }

    @Test
    void aCapThatOverflowsTheExactShareIsRealLimitsUnavailableNeverAWrongLimit() {
        // Review R-03: Long.MAX_VALUE x 1000 bps overflows Math.multiplyExact; the read fails closed (503 in C3, no buy
        // in the cycle), never a wrapped value nor an unhandled ArithmeticException.
        wallet(Long.MAX_VALUE - 1);

        assertThatThrownBy(() -> reader.read(TENANT, USER))
                .isInstanceOf(RealLimitsUnavailable.class)
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    void percentagesAreTheBasisPointsOverOneHundredExact() {
        assertThat(RealAccountLimitsReader.percent(1000).toPlainString()).isEqualTo("10");
        assertThat(RealAccountLimitsReader.percent(1250).toPlainString()).isEqualTo("12.5");
        assertThat(RealAccountLimitsReader.percent(1).toPlainString()).isEqualTo("0.01");
        assertThat(RealAccountLimitsReader.share(3, 3333)).isZero();
        RealAccountLimitsView stricter = RealAccountLimitsReader.of(
                10 * SOL,
                Optional.empty(),
                new RealRiskLimits(500, 1000, 5, Duration.ofSeconds(60), Duration.ofMinutes(5)));
        assertThat(stricter.orderMax()).isEqualTo(new Amount.Known(500_000_000L, new BigDecimal("5")));
        assertThat(stricter.dailyBuys()).isEqualTo(5);
    }

    private void wallet(long cap) {
        when(states.readRealState(TENANT, USER))
                .thenReturn(Optional.of(new RealStateView(
                        WALLET, true, true, RealOperationMode.AUTO_FULL, cap, 30_000_000L, Set.of(), Map.of())));
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
    }

    private void snapshot(long startCap) {
        when(store.snapshot(TENANT, WALLET, LocalDate.of(2026, 11, 21)))
                .thenReturn(Optional.of(new DaySnapshot(
                        TENANT,
                        WALLET,
                        LocalDate.of(2026, 11, 21),
                        startCap,
                        startCap,
                        NOW.minusSeconds(3600),
                        DaySnapshot.TakenBy.DAY_JOB)));
    }
}
