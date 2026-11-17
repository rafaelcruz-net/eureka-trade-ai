package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Step 5 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR8.1, BR9.3, U7-NFR4.10, S50): the exits flag
 * in the change of mode. Off, raising to AUTO_EXITS is refused with {@code EXITS_DISABLED} and nothing reaches the
 * TxSigner; whoever already is in AUTO_EXITS stays there, and leaving AUTO_EXITS is always allowed.
 */
@Tag("u7-exits-and-fiscal")
class RealModeExitsTest {

    private final RealExecutionWallet wallets = mock(RealExecutionWallet.class);
    private final RealExecutionPort port = mock(RealExecutionPort.class);
    private final JdbcRealExecutionStore store = mock(JdbcRealExecutionStore.class);
    private final AtomicBoolean exits = new AtomicBoolean();
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final Principal person = new Principal(tenant, user, Role.BETA_USER);

    private RealModeService service(RealOperationMode current) {
        when(wallets.wallet(tenant, user))
                .thenReturn(Optional.of(new RealWalletInfo(UUID.randomUUID(), "bot", "main", current, 200, 1_000L)));
        return new RealModeService(
                wallets,
                port,
                store,
                new MockEnvironment().withProperty("memetrade.real.enabled", "true"),
                mock(PlatformTransactionManager.class),
                exits::get);
    }

    @Test
    void raisingToAutoExitsWithTheFlagOffIsRefusedAndNothingReachesTheSigner() {
        RealModeService modes = service(RealOperationMode.SIGNED);

        assertThatThrownBy(() -> modes.change(person, "AUTO_EXITS", UUID.randomUUID(), "sig"))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                        .isEqualTo(ErrorCode.EXITS_DISABLED));
        verify(wallets, never()).applyModeConsent(any(), any(), any(), any());
    }

    @Test
    void raisingToAutoExitsWithTheFlagOnAppliesTheConsent() {
        exits.set(true);
        RealModeService modes = service(RealOperationMode.SIGNED);
        when(wallets.applyModeConsent(eq(person), eq(RealOperationMode.AUTO_EXITS), any(), any()))
                .thenReturn(RealOperationMode.AUTO_EXITS);

        assertThat(modes.change(person, "AUTO_EXITS", UUID.randomUUID(), "sig")).isEqualTo("AUTO_EXITS");
    }

    /** BR8.1: whoever is already in AUTO_EXITS stays there with the flag off. */
    @Test
    void stayingInAutoExitsWithTheFlagOffIsAccepted() {
        RealModeService modes = service(RealOperationMode.AUTO_EXITS);

        assertThat(modes.change(person, "AUTO_EXITS", null, null)).isEqualTo("AUTO_EXITS");
    }

    /** BR6.2: leaving AUTO_EXITS is immediate, with the flag off too (the conditionals are cancelled right after). */
    @Test
    void leavingAutoExitsDownToSignedIsImmediateWithTheFlagOff() {
        RealModeService modes = service(RealOperationMode.AUTO_EXITS);

        assertThat(modes.change(person, "SIGNED", null, null)).isEqualTo("SIGNED");
        verify(wallets).lowerMode(tenant, user, RealOperationMode.SIGNED);
    }

    /** Raising from AUTO_EXITS to AUTO_FULL does not enter AUTO_EXITS: the flag does not apply. */
    @Test
    void raisingFromAutoExitsToAutoFullIgnoresTheExitsFlag() {
        RealModeService modes = service(RealOperationMode.AUTO_EXITS);
        when(wallets.applyModeConsent(eq(person), eq(RealOperationMode.AUTO_FULL), any(), any()))
                .thenReturn(RealOperationMode.AUTO_FULL);

        assertThat(modes.change(person, "AUTO_FULL", UUID.randomUUID(), "sig")).isEqualTo("AUTO_FULL");
    }
}
