package com.memetrade.botwallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.memetrade.botwallet.api.RealAnonymization.CloseOutcome;
import com.memetrade.botwallet.domain.AnonymizationStore;
import com.memetrade.botwallet.domain.ReleaseStore;
import com.memetrade.botwallet.infra.RealAudit;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealExecutionPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of the U7 of 261113-trading-real-bot, written BEFORE the code (BR6.9, S47, U7-NFR4.9; personal data logic,
 * team.md): the {@code closeForAnonymization} of the U5 refuses with {@code OPEN_CONDITIONALS} while the person has a
 * conditional that is not final, before any write, and never cancels nor deletes anything by itself.
 */
@Tag("u7-exits-and-fiscal")
class AnonymizationOpenExitsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID WALLET = UUID.randomUUID();

    private final AnonymizationStore store = mock(AnonymizationStore.class);
    private final ReleaseStore releases = mock(ReleaseStore.class);
    private final RealLedger ledger = mock(RealLedger.class);
    private final RealExecutionPort execution = mock(RealExecutionPort.class);
    private final TxSignerCommandService commands = mock(TxSignerCommandService.class);
    private final RealAudit audit = mock(RealAudit.class);
    private final AtomicBoolean openExits = new AtomicBoolean();
    private BotWalletAnonymizationService service;

    @BeforeEach
    void wire() {
        service = new BotWalletAnonymizationService(
                store,
                releases,
                ledger,
                execution,
                commands,
                audit,
                Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC),
                (tenantId, userId) -> openExits.get());
        when(store.lockActiveWallet(TENANT, USER))
                .thenReturn(Optional.of(new AnonymizationStore.WalletToClose(WALLET, "address-of-the-test")));
        when(ledger.balance(TENANT, WALLET)).thenReturn(new RealBalance(0, 0, 0, Set.of(), false, 0, 0));
        when(ledger.tokenBalances(TENANT, WALLET)).thenReturn(Map.of());
        when(commands.deactivate(WALLET)).thenReturn(UUID.randomUUID());
    }

    /** S47: an OPEN conditional refuses the anonymization and nothing is written. */
    @Test
    void anOpenConditionalRefusesWithOpenConditionalsAndWritesNothing() {
        openExits.set(true);

        CloseOutcome outcome = service.closeForAnonymization(TENANT, USER);

        assertThat(outcome).isEqualTo(new CloseOutcome.Refused("OPEN_CONDITIONALS"));
        verify(store, never()).closeWallet(any(), any());
        verify(store, never()).clearPersonalData(any(), any(), any());
        verify(store, never()).redactAudit(anyString());
        verifyNoInteractions(releases, commands, audit);
    }

    /** The conditional holds a position too: the refusal names the conditional, which the person must cancel first. */
    @Test
    void theConditionalIsNamedBeforeThePositionItHolds() {
        openExits.set(true);
        when(ledger.tokenBalances(TENANT, WALLET))
                .thenReturn(Map.of("Mint1111111111111111111111111111111111111111", 5L));

        assertThat(service.closeForAnonymization(TENANT, USER))
                .isEqualTo(new CloseOutcome.Refused("OPEN_CONDITIONALS"));
    }

    /** Checked before any write, even for a person whose bot wallet is already closed. */
    @Test
    void theCheckComesBeforeAnyWriteEvenWithoutAnActiveWallet() {
        openExits.set(true);
        when(store.lockActiveWallet(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(service.closeForAnonymization(TENANT, USER))
                .isEqualTo(new CloseOutcome.Refused("OPEN_CONDITIONALS"));
        verify(store, never()).clearPersonalData(any(), any(), any());
        verifyNoInteractions(releases);
    }

    /** Without a conditional the closing of the U5 goes on as before. */
    @Test
    void withoutAConditionalTheWalletIsClosedAsBefore() {
        CloseOutcome outcome = service.closeForAnonymization(TENANT, USER);

        assertThat(outcome).isEqualTo(new CloseOutcome.Closed(true));
        verify(store).closeWallet(any(), any());
        verify(commands).deactivate(WALLET);
    }
}
