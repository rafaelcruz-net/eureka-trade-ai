package com.memetrade.botwallet.application;

import com.memetrade.botwallet.domain.BotWalletAccount;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.PauseReason;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.Withdrawal;
import com.memetrade.ledger.api.RealBalance;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Views of the routes R2 (contract-summary.md): lamports as integer TEXT, never JSON numbers; the bot wallet address in
 * full only to its owner; never the key reference nor the main wallet of anyone else.
 */
public final class BotWalletViews {

    private BotWalletViews() {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealBalanceView")
    public record Balance(
            String totalLamports,
            String freeLamports,
            String reservedLamports,
            String feeReserveLamports,
            String aboveCapLamports,
            String operableLamports,
            Instant asOf) {}

    public record BotWalletView(
            String address,
            String mainWalletAddress,
            String status,
            String mode,
            String capLamports,
            String maxCapLamports,
            int maxSlippageBps,
            Balance balance,
            String pausedReason,
            String network,
            boolean policyResetRequired) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealChallengeView")
    public record ChallengeView(UUID challengeId, String message, String nonce, Instant expiresAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealDepositView")
    public record DepositView(String txSignature, String lamports, Instant finalizedAt, boolean knownSource) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealWithdrawalView")
    public record WithdrawalView(
            UUID withdrawalId,
            String kind,
            String lamports,
            String feeLamports,
            String state,
            String txSignature,
            Instant requestedAt) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "RealPage")
    public record Page<T>(List<T> items, String nextCursor, int total) {}

    static BotWalletView of(BotWalletAccount wallet, Preferences preferences, RealBalance balance, Instant now) {
        long cap = preferences.capLamports();
        long reserve = preferences.feeReserveLamports();
        return new BotWalletView(
                wallet.publicAddress(),
                wallet.mainWalletAddress(),
                wallet.status(),
                preferences.operationMode().name(),
                Long.toString(cap),
                Long.toString(preferences.maxCapLamports()),
                preferences.maxSlippageBps(),
                new Balance(
                        Long.toString(balance.totalLamports()),
                        Long.toString(balance.freeLamports()),
                        Long.toString(balance.reservedLamports()),
                        Long.toString(reserve),
                        Long.toString(balance.aboveCapLamports(cap, reserve)),
                        Long.toString(balance.operableLamports(cap, reserve)),
                        now),
                pausedReason(preferences),
                wallet.network().name(),
                wallet.policyResetRequired());
    }

    /** The most severe reason first; NONE when not paused (contract PausedReason). */
    static String pausedReason(Preferences preferences) {
        return preferences.pausedReasons().stream()
                .sorted(Comparator.comparingInt(PauseReason::ordinal).reversed())
                .map(Enum::name)
                .findFirst()
                .orElse("NONE");
    }

    static DepositView of(BotWalletRepository.DepositView deposit) {
        return new DepositView(
                deposit.txSignature(), Long.toString(deposit.lamports()), deposit.finalizedAt(), deposit.knownSource());
    }

    static WithdrawalView of(Withdrawal withdrawal) {
        return new WithdrawalView(
                withdrawal.withdrawalId(),
                withdrawal.kind().name(),
                Long.toString(withdrawal.lamports()),
                Long.toString(withdrawal.feeLamports()),
                withdrawal.state().name(),
                withdrawal.txSignature(),
                withdrawal.requestedAt());
    }
}
