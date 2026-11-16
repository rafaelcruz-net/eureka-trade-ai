package com.memetrade.botwallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.infra.GlobalPauses;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.botwallet.infra.RealProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of the U5, written BEFORE the code (logical-components.md "Mudanças em outras Units": M1 {@code readRealState}
 * joins the global pauses; S16, S26, BR2.5, BR3.1): the kill switch, the restore and the pending restore check are part
 * of the state of EVERY wallet from the live read, with their start (the {@code activated_at} of the switch, the
 * {@code detected_at} or {@code opened_at} of the marker), next to the pauses of the person. No wallet, no state.
 */
@Tag("u5-real-operations")
class RealStateGlobalPausesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();

    private final BotWalletRepository repository = mock(BotWalletRepository.class);
    private final GlobalPauses global = mock(GlobalPauses.class);

    private RealStateService service() {
        RealProperties properties = mock(RealProperties.class);
        when(properties.enabled()).thenReturn(true);
        when(properties.network()).thenReturn(Network.LOCALNET);
        return new RealStateService(
                repository, mock(RealAudit.class), properties, global, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void wallet(EnumSet<PauseReason> reasons) {
        when(repository.activeWallet(TENANT, USER))
                .thenReturn(Optional.of(new BotWalletAccount(
                        WALLET, TENANT, USER, "addr", "main", Network.LOCALNET, "ACTIVE", null, false, NOW)));
        when(repository.preferences(WALLET))
                .thenReturn(new Preferences(
                        OperationMode.AUTO_FULL, 1_000L, 10_000L, 200, 30L, 10L, reasons, NOW.minusSeconds(60)));
        when(repository.pausedSince(WALLET)).thenReturn(Map.of());
        when(repository.releases(USER)).thenReturn(List.of());
    }

    @Test
    void theKillSwitchIsAPauseOfEveryWalletSinceItWasActivated() {
        wallet(EnumSet.noneOf(PauseReason.class));
        Instant activated = NOW.minus(Duration.ofMinutes(3));
        when(global.active()).thenReturn(Map.of(PauseReason.KILL_SWITCH, activated));

        RealStateView state = service().readRealState(TENANT, USER).orElseThrow();

        assertThat(state.pausedReasons()).containsExactly(RealPauseReason.KILL_SWITCH);
        assertThat(state.pausedSince()).containsEntry(RealPauseReason.KILL_SWITCH, activated);
    }

    @Test
    void theRestoreAndItsPendingCheckArePausesWithTheirStart() {
        wallet(EnumSet.noneOf(PauseReason.class));
        Instant opened = NOW.minus(Duration.ofMinutes(1));
        when(global.active()).thenReturn(Map.of(PauseReason.RESTORE_CHECK_PENDING, opened));

        assertThat(service().readRealState(TENANT, USER).orElseThrow().pausedSince())
                .containsExactly(Map.entry(RealPauseReason.RESTORE_CHECK_PENDING, opened));

        Instant detected = NOW.minus(Duration.ofSeconds(10));
        when(global.active()).thenReturn(Map.of(PauseReason.RESTORE, detected));

        assertThat(service().readRealState(TENANT, USER).orElseThrow().pausedReasons())
                .containsExactly(RealPauseReason.RESTORE);
    }

    @Test
    void theGlobalPausesJoinThePausesOfThePerson() {
        wallet(EnumSet.of(PauseReason.USER, PauseReason.DIVERGENCE));
        when(global.active()).thenReturn(Map.of(PauseReason.KILL_SWITCH, NOW));

        assertThat(service().readRealState(TENANT, USER).orElseThrow().pausedReasons())
                .containsExactlyInAnyOrder(
                        RealPauseReason.USER, RealPauseReason.DIVERGENCE, RealPauseReason.KILL_SWITCH);
    }

    @Test
    void withoutAGlobalPauseNothingChanges() {
        wallet(EnumSet.noneOf(PauseReason.class));
        when(global.active()).thenReturn(Map.of());

        assertThat(service().readRealState(TENANT, USER).orElseThrow().paused()).isFalse();
    }

    @Test
    void aGlobalPauseNeverCreatesTheStateOfAPersonWithoutWallet() {
        when(repository.activeWallet(TENANT, USER)).thenReturn(Optional.empty());
        when(global.active()).thenReturn(Map.of(PauseReason.KILL_SWITCH, NOW));

        assertThat(service().readRealState(TENANT, USER)).isEmpty();
    }
}
