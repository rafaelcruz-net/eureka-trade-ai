package com.memetrade.botwallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses.PauseOutcome;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.RealRelease;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 3 of the U3, written before the code (M1, BR7.3, BR4.5, Q11): {@code readRealState} with the set of pauses and
 * the start of each one, and the idempotent {@code pauseUser} / {@code resumeUser} of one reason.
 */
class RealStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();

    private final BotWalletRepository repository = mock(BotWalletRepository.class);
    private final RealAudit audit = mock(RealAudit.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private RealStateService service(boolean realEnabled) {
        RealProperties properties = mock(RealProperties.class);
        when(properties.enabled()).thenReturn(realEnabled);
        when(properties.network()).thenReturn(Network.LOCALNET);
        com.memetrade.botwallet.infra.GlobalPauses global =
                org.mockito.Mockito.mock(com.memetrade.botwallet.infra.GlobalPauses.class);
        when(global.active()).thenReturn(Map.of());
        return new RealStateService(repository, audit, properties, global, clock);
    }

    private void wallet(Set<PauseReason> reasons, Map<PauseReason, Instant> since) {
        BotWalletAccount account = new BotWalletAccount(
                WALLET, TENANT, USER, "addr", "main", Network.LOCALNET, "ACTIVE", null, false, NOW);
        when(repository.activeWallet(TENANT, USER)).thenReturn(Optional.of(account));
        when(repository.preferences(WALLET))
                .thenReturn(new Preferences(
                        OperationMode.AUTO_FULL,
                        10_000_000_000L,
                        10_000_000_000L,
                        200,
                        30_000_000L,
                        100_000L,
                        reasons,
                        NOW));
        when(repository.pausedSince(WALLET)).thenReturn(since);
    }

    private void releasedSince(Duration ago) {
        Instant requested = NOW.minus(ago);
        when(repository.releases(USER))
                .thenReturn(List.of(new RealRelease(
                        UUID.randomUUID(),
                        USER,
                        Network.LOCALNET,
                        USER,
                        requested,
                        requested.plus(RealRelease.QUARANTINE),
                        null,
                        // U5 (BR1.1): only ACTIVE is in force; the gates turn a release ACTIVE after its 24 hours.
                        ago.compareTo(RealRelease.QUARANTINE) >= 0
                                ? com.memetrade.botwallet.domain.ReleaseState.ACTIVE
                                : com.memetrade.botwallet.domain.ReleaseState.QUARANTINE,
                        null,
                        null,
                        null)));
    }

    @Test
    void withoutAnActiveWalletOfThatTenantTheStateIsEmpty() {
        when(repository.activeWallet(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(service(true).readRealState(TENANT, USER)).isEmpty();
    }

    @Test
    void theStateCarriesCapReserveModeEveryPauseAndItsStart() {
        Instant since = NOW.minus(Duration.ofHours(3));
        wallet(EnumSet.of(PauseReason.USER, PauseReason.DAILY_LOSS), Map.of(PauseReason.DAILY_LOSS, since));
        releasedSince(Duration.ofHours(25));

        RealStateView state = service(true).readRealState(TENANT, USER).orElseThrow();

        assertThat(state.botWalletId()).isEqualTo(WALLET);
        assertThat(state.realEnabled()).isTrue();
        assertThat(state.released()).isTrue();
        assertThat(state.mode()).isEqualTo(RealOperationMode.AUTO_FULL);
        assertThat(state.capLamports()).isEqualTo(10_000_000_000L);
        assertThat(state.feeReserveLamports()).isEqualTo(30_000_000L);
        assertThat(state.pausedReasons()).containsExactlyInAnyOrder(RealPauseReason.USER, RealPauseReason.DAILY_LOSS);
        assertThat(state.pausedSince()).containsEntry(RealPauseReason.DAILY_LOSS, since);
        // A reason without a recorded start (written before the U3) starts at the last change of the preferences.
        assertThat(state.pausedSince()).containsEntry(RealPauseReason.USER, NOW);
        assertThat(state.paused()).isTrue();
    }

    @Test
    void aReleaseStillInQuarantineAndTheRealSwitchedOffAreReported() {
        wallet(Set.of(), Map.of());
        releasedSince(Duration.ofHours(23));

        RealStateView state = service(false).readRealState(TENANT, USER).orElseThrow();

        assertThat(state.released()).isFalse();
        assertThat(state.realEnabled()).isFalse();
        assertThat(state.paused()).isFalse();
    }

    @Test
    void pausingAddsTheReasonOnceAndAuditsOnlyTheChange() {
        wallet(Set.of(), Map.of());
        when(repository.addPause(WALLET, PauseReason.DAILY_LOSS, NOW)).thenReturn(true, false);

        assertThat(service(true).pauseUser(TENANT, USER, RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.PAUSED);
        assertThat(service(true).pauseUser(TENANT, USER, RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.ALREADY_PAUSED);

        verify(audit)
                .system(
                        eq(TENANT),
                        eq("REAL_PAUSED"),
                        eq("bot-wallet:" + WALLET),
                        eq(Map.of("reason", "DAILY_LOSS")),
                        any());
    }

    @Test
    void resumingRemovesOnlyThatReasonAndIsIdempotent() {
        wallet(EnumSet.of(PauseReason.DAILY_LOSS, PauseReason.ADMIN), Map.of());
        when(repository.removePause(WALLET, PauseReason.DAILY_LOSS)).thenReturn(true, false);

        assertThat(service(true).resumeUser(TENANT, USER, RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.RESUMED);
        assertThat(service(true).resumeUser(TENANT, USER, RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.NOT_PAUSED);

        verify(repository, never()).removePause(WALLET, PauseReason.ADMIN);
    }

    @Test
    void pausingOrResumingWithoutAWalletChangesNothing() {
        when(repository.activeWallet(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(service(true).pauseUser(TENANT, USER, RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.NO_WALLET);
        assertThat(service(true).resumeUser(TENANT, USER, RealPauseReason.DAILY_LOSS))
                .isEqualTo(PauseOutcome.NO_WALLET);
        verify(audit, never()).system(any(), any(), any(), anyMap(), any());
    }
}
