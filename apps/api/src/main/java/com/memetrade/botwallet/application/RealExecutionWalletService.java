package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealConsentRefused;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.botwallet.api.SwapSigningRequest;
import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletException;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.memetrade.botwallet.infra.RealProperties;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * M1 for the REAL execution (261113-trading-real-bot U4): the wallet of a person as the execution needs it, the recheck
 * before signing and sending (BR3.3), the swap signature through the TxSigner (BR4.1, BR4.2), the change of the mode
 * (BR7.1, BR7.2) and the withdrawal of the liquidation (BR8.2). Tenant and person always filter the wallet. The TxSigner
 * is never called inside a database transaction here.
 */
@Service
public class RealExecutionWalletService implements RealExecutionWallet {

    /** BR1.1 of the U3: the liquidation of the person passes every pause except these. */
    private static final Set<PauseReason> STOP_LIQUIDATION =
            EnumSet.of(PauseReason.KILL_SWITCH, PauseReason.DIVERGENCE);

    private final BotWalletRepository repository;
    private final TxSignerPort signer;
    private final BotWalletService wallets;
    private final RealProperties properties;

    public RealExecutionWalletService(
            BotWalletRepository repository, TxSignerPort signer, BotWalletService wallets, RealProperties properties) {
        this.repository = repository;
        this.signer = signer;
        this.wallets = wallets;
        this.properties = properties;
    }

    @Override
    public Optional<RealWalletInfo> wallet(UUID tenantId, UUID userId) {
        return repository.activeWallet(tenantId, userId).map(wallet -> {
            Preferences preferences = repository.preferences(wallet.botWalletId());
            return new RealWalletInfo(
                    wallet.botWalletId(),
                    wallet.publicAddress(),
                    wallet.mainWalletAddress(),
                    RealOperationMode.valueOf(preferences.operationMode().name()),
                    preferences.maxSlippageBps(),
                    preferences.capLamports());
        });
    }

    @Override
    public Recheck recheckBeforeSend(UUID tenantId, UUID userId, RealOperationMode orderMode, boolean liquidation) {
        if (!properties.enabled()) {
            return new Recheck.Cancelled(RecheckReason.REAL_DISABLED);
        }
        Optional<BotWalletAccount> wallet = repository.activeWallet(tenantId, userId);
        if (wallet.isEmpty()) {
            return new Recheck.Cancelled(RecheckReason.REAL_DISABLED);
        }
        Preferences preferences = repository.preferences(wallet.get().botWalletId());
        Set<PauseReason> paused = preferences.pausedReasons();
        if (paused.contains(PauseReason.KILL_SWITCH)) {
            return new Recheck.Cancelled(RecheckReason.KILL_SWITCH);
        }
        boolean stops = liquidation ? paused.stream().anyMatch(STOP_LIQUIDATION::contains) : !paused.isEmpty();
        if (stops) {
            return new Recheck.Cancelled(RecheckReason.PAUSED);
        }
        OperationMode current = preferences.operationMode();
        if (OperationMode.valueOf(orderMode.name()).isMorePermissiveThan(current)) {
            return new Recheck.Cancelled(RecheckReason.MODE_LOWERED);
        }
        return Recheck.allowed();
    }

    @Override
    public SwapSigning signSwap(UUID tenantId, UUID userId, SwapSigningRequest request) {
        BotWalletAccount wallet = repository
                .activeWallet(tenantId, userId)
                .orElseThrow(() -> new IllegalStateException("A swap of a person without an ACTIVE bot wallet"));
        TxSignerPort.SignOutcome outcome;
        try {
            outcome = signer.signSwap(wallet.publicAddress(), request);
        } catch (TxSignerPort.SignerUnavailableException down) {
            throw new RealUpstreamUnavailable(RealUpstreamUnavailable.Provider.TXSIGNER);
        }
        return outcome.signed()
                ? new SwapSigning.Signed(outcome.signature(), outcome.signedTransactionBase64())
                : new SwapSigning.Refused(outcome.refusal());
    }

    @Override
    public RealOperationMode lowerMode(UUID tenantId, UUID userId, RealOperationMode mode) {
        try {
            return RealOperationMode.valueOf(wallets.lowerMode(tenantId, userId, OperationMode.valueOf(mode.name()))
                    .name());
        } catch (BotWalletException refused) {
            throw new RealConsentRefused(refused.error().name());
        }
    }

    @Override
    public RealOperationMode applyModeConsent(
            com.memetrade.identity.api.Principal person, RealOperationMode mode, UUID challengeId, String signature) {
        try {
            return RealOperationMode.valueOf(
                    wallets.raiseMode(person, OperationMode.valueOf(mode.name()), challengeId, signature)
                            .name());
        } catch (BotWalletException refused) {
            if (refused.error() == com.memetrade.botwallet.domain.BotWalletError.UPSTREAM_UNAVAILABLE) {
                throw new RealUpstreamUnavailable(RealUpstreamUnavailable.Provider.TXSIGNER);
            }
            throw new RealConsentRefused(refused.error().name());
        }
    }

    @Override
    public boolean signerReachable() {
        return signer.reachable();
    }

    @Override
    public Optional<UUID> requestWithdrawalAll(UUID tenantId, UUID userId, String idempotencyKey) {
        return wallets.requestWithdrawalForLiquidation(tenantId, userId, UUID.fromString(idempotencyKey));
    }
}
