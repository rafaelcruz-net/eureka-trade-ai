package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletRef;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealCost;
import com.memetrade.ledger.api.RealCostUnknownReason;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.LamportPrice;
import com.memetrade.riskcontrol.real.domain.PositionsValue;
import com.memetrade.riskcontrol.real.domain.PricedPosition;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.SteppingClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Review R-01, written before the change: the photo of each wallet carries the instant read just before THAT wallet's
 * own reads (never the start of the round), so a flow recorded after the photo of one wallet is never inside both its
 * start value and its flows. The clock moves one second on every read.
 *
 * <p>261121-paper-real-unico U2, Etapa 12 (written before the code; F-3, F-4, reliability-design.md "Fotografia do
 * dia adiada"): a value {@code Unknown} in the photo writes the mark of the deferred photo, no photo, counts a failure
 * and a REAL risk error of the attribution; with the mark, the photo is taken only with every cost known, each position
 * at the larger of its quote and its cost; a value {@code Unknown} in the judge neither pauses nor resumes.
 */
class RealRiskJobsTest {

    private static final Instant START = Instant.parse("2026-11-14T00:05:00Z");

    private final RealStateQuery states = mock(RealStateQuery.class);
    private final RealGateInputsReader reader = mock(RealGateInputsReader.class);
    private final JdbcRealRiskStore store = mock(JdbcRealRiskStore.class);
    private final RealPauses pauses = mock(RealPauses.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final UUID tenant = UUID.randomUUID();
    private final RealWalletRef wallet = new RealWalletRef(tenant, UUID.randomUUID(), UUID.randomUUID());

    private RealRiskJobs jobs() {
        return new RealRiskJobs(
                states,
                pauses,
                reader,
                store,
                new RealRiskPolicy(),
                RealRiskLimits.DEFAULTS,
                new RealRiskMetrics(registry),
                new DefaultLockingTaskExecutor(new InMemoryLockProvider()),
                mock(PlatformTransactionManager.class),
                new SteppingClock(START, Duration.ofSeconds(1)));
    }

    private void inForce(RealWalletRef... wallets) {
        when(states.walletsInForce()).thenReturn(List.of(wallets));
        when(states.pausedWallets(any())).thenReturn(List.of());
        for (RealWalletRef ref : wallets) {
            when(states.readRealState(ref.tenantId(), ref.userId()))
                    .thenReturn(Optional.of(new RealStateView(
                            ref.botWalletId(), true, true, RealOperationMode.AUTO_FULL, 10, 0, Set.of(), Map.of())));
        }
    }

    private static RealGateInputsReader.WalletValue worth(PositionsValue positions, List<PricedPosition> open) {
        PositionsValue value = positions instanceof PositionsValue.Known known
                ? new PositionsValue.Known(5 + known.lamports())
                : positions;
        return new RealGateInputsReader.WalletValue(
                new RealBalance(5, 0, 5, Set.of(), !open.isEmpty()), open, positions, value, RealFlows.NONE);
    }

    private double attributionErrors() {
        return registry.get("real_risk_errors")
                .tag("dependency", "attribution")
                .counter()
                .count();
    }

    @Test
    void eachPhotoTakesTheInstantOfItsOwnWalletRead() {
        RealWalletRef other = new RealWalletRef(tenant, UUID.randomUUID(), UUID.randomUUID());
        inForce(wallet, other);
        for (RealWalletRef ref : List.of(wallet, other)) {
            when(reader.value(ref.tenantId(), ref.userId(), ref.botWalletId(), Optional.empty(), false))
                    .thenReturn(worth(new PositionsValue.Known(0), List.of()));
        }
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
        List<DaySnapshot> photos = new ArrayList<>();
        when(store.insertSnapshot(any())).thenAnswer(call -> photos.add(call.getArgument(0)));

        RealRiskJobs.DayRound round = jobs().runDay();

        assertThat(round.photos()).isEqualTo(2);
        assertThat(photos).hasSize(2);
        assertThat(photos.get(0).takenAt()).isAfter(START);
        assertThat(photos.get(1).takenAt()).isAfter(photos.get(0).takenAt());
        assertThat(photos).allSatisfy(photo -> assertThat(photo.day()).isEqualTo(LocalDate.of(2026, 11, 14)));
    }

    @Test
    void anUnknownValueDefersThePhotoOfTheDayAndCountsAFailureOfTheAttribution() {
        inForce(wallet);
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
        when(reader.value(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(worth(new PositionsValue.Unknown(RealCostUnknownReason.TIMEOUT), List.of()));

        RealRiskJobs.DayRound round = jobs().runDay();

        assertThat(round.photos()).isZero();
        assertThat(round.failures()).isEqualTo(1);
        verify(store)
                .deferDay(
                        org.mockito.ArgumentMatchers.eq(tenant),
                        org.mockito.ArgumentMatchers.eq(wallet.botWalletId()),
                        org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 11, 14)),
                        any(Instant.class));
        verify(store, never()).insertSnapshot(any());
        assertThat(attributionErrors()).isEqualTo(1.0);
    }

    @Test
    void withTheMarkThePhotoTakesEachPositionAtTheLargerOfItsQuoteAndItsCost() {
        inForce(wallet);
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
        when(store.isDayDeferred(any(), any(), any())).thenReturn(true);
        List<PricedPosition> open = List.of(
                new PricedPosition("Fell", 100, new RealCost.Known(500), price("1")),
                new PricedPosition("Rose", 100, new RealCost.Known(50), price("2")),
                new PricedPosition("Unpriced", 10, new RealCost.Known(70), new LamportPrice.Unknown()));
        when(reader.value(tenant, wallet.userId(), wallet.botWalletId(), Optional.empty(), true))
                .thenReturn(worth(new PositionsValue.Known(100 + 200 + 70), open));
        List<DaySnapshot> photos = new ArrayList<>();
        when(store.insertSnapshot(any())).thenAnswer(call -> photos.add(call.getArgument(0)));

        RealRiskJobs.DayRound round = jobs().runDay();

        assertThat(round.photos()).isEqualTo(1);
        assertThat(photos).singleElement().satisfies(photo -> {
            // balance 5 + max(100, 500) + max(200, 50) + 70
            assertThat(photo.startValueLamports()).isEqualTo(5 + 500 + 200 + 70);
            assertThat(photo.takenBy()).isEqualTo(DaySnapshot.TakenBy.DAY_JOB);
        });
    }

    @Test
    void withTheMarkAndACostStillUnknownThePhotoStaysDeferred() {
        inForce(wallet);
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
        when(store.isDayDeferred(any(), any(), any())).thenReturn(true);
        List<PricedPosition> open = List.of(new PricedPosition(
                "Fell", 100, new RealCost.Unknown(RealCostUnknownReason.INCONSISTENT_READ), price("1")));
        when(reader.value(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(worth(new PositionsValue.Known(100), open));

        RealRiskJobs.DayRound round = jobs().runDay();

        assertThat(round.photos()).isZero();
        assertThat(round.failures()).isEqualTo(1);
        verify(store, never()).insertSnapshot(any());
    }

    @Test
    void anUnknownValueInTheJudgeNeitherPausesNorResumesAndCountsAFailure() {
        inForce(wallet);
        when(store.snapshot(any(), any(), any()))
                .thenReturn(Optional.of(new DaySnapshot(
                        tenant,
                        wallet.botWalletId(),
                        LocalDate.of(2026, 11, 14),
                        1_000_000,
                        10,
                        START.minusSeconds(60),
                        DaySnapshot.TakenBy.DAY_JOB)));
        when(reader.value(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(worth(new PositionsValue.Unknown(RealCostUnknownReason.ERROR), List.of()));

        RealRiskJobs.LossRound round = jobs().runDailyLoss();

        assertThat(round.paused()).isZero();
        assertThat(round.failures()).isEqualTo(1);
        verify(pauses, never()).pauseUser(any(), any(), any(RealPauseReason.class));
        assertThat(attributionErrors()).isEqualTo(1.0);
    }

    private static LamportPrice price(String lamportsPerBaseUnit) {
        return new LamportPrice.Known(new BigDecimal(lamportsPerBaseUnit), START);
    }
}
