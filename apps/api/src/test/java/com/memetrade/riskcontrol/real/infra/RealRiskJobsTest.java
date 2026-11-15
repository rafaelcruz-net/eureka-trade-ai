package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletRef;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.SteppingClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
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
 */
class RealRiskJobsTest {

    @Test
    void eachPhotoTakesTheInstantOfItsOwnWalletRead() {
        RealStateQuery states = mock(RealStateQuery.class);
        RealGateInputsReader reader = mock(RealGateInputsReader.class);
        JdbcRealRiskStore store = mock(JdbcRealRiskStore.class);
        UUID tenant = UUID.randomUUID();
        List<RealWalletRef> wallets = List.of(
                new RealWalletRef(tenant, UUID.randomUUID(), UUID.randomUUID()),
                new RealWalletRef(tenant, UUID.randomUUID(), UUID.randomUUID()));
        when(states.walletsInForce()).thenReturn(wallets);
        when(states.pausedWallets(any())).thenReturn(List.of());
        for (RealWalletRef wallet : wallets) {
            when(states.readRealState(wallet.tenantId(), wallet.userId()))
                    .thenReturn(Optional.of(new RealStateView(
                            wallet.botWalletId(), true, true, RealOperationMode.AUTO_FULL, 10, 0, Set.of(), Map.of())));
            when(reader.value(wallet.tenantId(), wallet.botWalletId(), Optional.empty()))
                    .thenReturn(new RealGateInputsReader.WalletValue(
                            new RealBalance(5, 0, 5, Set.of(), false),
                            List.of(),
                            0,
                            5,
                            com.memetrade.ledger.api.RealFlows.NONE));
        }
        when(store.snapshot(any(), any(), any())).thenReturn(Optional.empty());
        List<DaySnapshot> photos = new ArrayList<>();
        when(store.insertSnapshot(any())).thenAnswer(call -> photos.add(call.getArgument(0)));
        Instant start = Instant.parse("2026-11-14T00:05:00Z");
        RealRiskJobs jobs = new RealRiskJobs(
                states,
                mock(RealPauses.class),
                reader,
                store,
                new RealRiskPolicy(),
                RealRiskLimits.DEFAULTS,
                new RealRiskMetrics(new SimpleMeterRegistry()),
                new DefaultLockingTaskExecutor(new InMemoryLockProvider()),
                mock(PlatformTransactionManager.class),
                new SteppingClock(start, Duration.ofSeconds(1)));

        RealRiskJobs.DayRound round = jobs.runDay();

        assertThat(round.photos()).isEqualTo(2);
        assertThat(photos).hasSize(2);
        assertThat(photos.get(0).takenAt()).isAfter(start);
        assertThat(photos.get(1).takenAt()).isAfter(photos.get(0).takenAt());
        assertThat(photos).allSatisfy(photo -> assertThat(photo.day()).isEqualTo(java.time.LocalDate.of(2026, 11, 14)));
    }
}
