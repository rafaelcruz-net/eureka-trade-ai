package com.memetrade.realexecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealExecutionWallet.Recheck;
import com.memetrade.botwallet.api.RealExecutionWallet.RecheckReason;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.application.BotWalletService;
import com.memetrade.botwallet.application.RealExecutionWalletService;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.botwallet.infra.RealProperties;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Step 5 of the U5 of 261113-trading-real-bot, written BEFORE the code (logical-components.md "Mudanças em outras
 * Units": the U4 {@code recheckBeforeSend} reads the state through the M1 {@code readRealState}, with the global
 * pauses, instead of the preferences directly; U5-NFR3.5): before signing and before sending, the kill switch cancels
 * KILL_SWITCH, the restore and its pending check cancel PAUSED (the liquidation included), and the other pauses keep the
 * rule of the U4.
 */
@Tag("u5-real-operations")
class RealExecutionRecheckTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final RealStateQuery state = mock(RealStateQuery.class);

    private RealExecutionWalletService wallets(boolean realEnabled) {
        RealProperties properties = mock(RealProperties.class);
        when(properties.enabled()).thenReturn(realEnabled);
        return new RealExecutionWalletService(
                mock(BotWalletRepository.class),
                mock(TxSignerPort.class),
                mock(BotWalletService.class),
                properties,
                state);
    }

    private void paused(Set<RealPauseReason> reasons, RealOperationMode mode) {
        when(state.readRealState(TENANT, USER))
                .thenReturn(Optional.of(
                        new RealStateView(UUID.randomUUID(), true, true, mode, 1_000L, 10L, reasons, Map.of())));
    }

    private Recheck recheck(RealOperationMode orderMode, boolean liquidation) {
        return wallets(true).recheckBeforeSend(TENANT, USER, orderMode, liquidation);
    }

    @Test
    void theKillSwitchReadLiveCancelsEvenTheLiquidation() {
        paused(EnumSet.of(RealPauseReason.KILL_SWITCH), RealOperationMode.AUTO_FULL);

        assertThat(recheck(RealOperationMode.AUTO_FULL, false))
                .isEqualTo(new Recheck.Cancelled(RecheckReason.KILL_SWITCH));
        assertThat(recheck(RealOperationMode.AUTO_FULL, true))
                .isEqualTo(new Recheck.Cancelled(RecheckReason.KILL_SWITCH));
    }

    @ParameterizedTest
    @EnumSource(
            value = RealPauseReason.class,
            names = {"RESTORE", "RESTORE_CHECK_PENDING", "DIVERGENCE"})
    void theRestorePausesAndADivergenceCancelTheOrderAndTheLiquidation(RealPauseReason reason) {
        paused(EnumSet.of(reason), RealOperationMode.AUTO_FULL);

        assertThat(recheck(RealOperationMode.AUTO_FULL, false)).isEqualTo(new Recheck.Cancelled(RecheckReason.PAUSED));
        assertThat(recheck(RealOperationMode.AUTO_FULL, true)).isEqualTo(new Recheck.Cancelled(RecheckReason.PAUSED));
    }

    @Test
    void thePauseOfThePersonStopsTheBotButNotTheLiquidation() {
        paused(EnumSet.of(RealPauseReason.USER), RealOperationMode.AUTO_FULL);

        assertThat(recheck(RealOperationMode.AUTO_FULL, false)).isEqualTo(new Recheck.Cancelled(RecheckReason.PAUSED));
        assertThat(recheck(RealOperationMode.AUTO_FULL, true)).isEqualTo(Recheck.allowed());
    }

    @Test
    void aLoweredModeCancelsModeLowered() {
        paused(EnumSet.noneOf(RealPauseReason.class), RealOperationMode.SIGNED);

        assertThat(recheck(RealOperationMode.AUTO_FULL, false))
                .isEqualTo(new Recheck.Cancelled(RecheckReason.MODE_LOWERED));
        assertThat(recheck(RealOperationMode.SIGNED, false)).isEqualTo(Recheck.allowed());
    }

    @Test
    void theRealOffOrNoWalletCancelsRealDisabled() {
        when(state.readRealState(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(recheck(RealOperationMode.AUTO_FULL, false))
                .isEqualTo(new Recheck.Cancelled(RecheckReason.REAL_DISABLED));
        assertThat(wallets(false).recheckBeforeSend(TENANT, USER, RealOperationMode.AUTO_FULL, false))
                .isEqualTo(new Recheck.Cancelled(RecheckReason.REAL_DISABLED));
    }

    @Test
    void thePausedSinceOfTheStateIsNotNeededToDecide() {
        when(state.readRealState(TENANT, USER))
                .thenReturn(Optional.of(new RealStateView(
                        UUID.randomUUID(),
                        true,
                        true,
                        RealOperationMode.AUTO_FULL,
                        1L,
                        1L,
                        EnumSet.of(RealPauseReason.KILL_SWITCH),
                        Map.of(RealPauseReason.KILL_SWITCH, Instant.EPOCH))));

        assertThat(recheck(RealOperationMode.SIGNED, false))
                .isEqualTo(new Recheck.Cancelled(RecheckReason.KILL_SWITCH));
    }
}
