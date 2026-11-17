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
            boolean policyResetRequired,

            @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"USER", "ADMIN"},
                    description = "Quem definiu o teto atual: a pessoa ou o administrador (só leitura)")
            String capSetBy,

            @io.swagger.v3.oas.annotations.media.Schema(description = "A liberação do REAL na rede do ambiente")
            ReleaseStatus release) {}

    /**
     * 261113-trading-real-bot U6 (rules.md "Mudança de contrato" item 1): the release on the network of the environment;
     * the same object in the bot wallet view and in {@code GET /api/v1/real/release}.
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RealReleaseStatus")
    public record ReleaseStatus(
            @io.swagger.v3.oas.annotations.media.Schema(
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NONE", "QUARANTINE", "ACTIVE", "BLOCKED", "REVOKED"})
            String state,

            @io.swagger.v3.oas.annotations.media.Schema(description = "Fim da quarentena; ausente sem liberação")
            Instant effectiveAt,

            @io.swagger.v3.oas.annotations.media.Schema(
                    description = "Primeiro portão vermelho de uma liberação BLOCKED")
            String blockedReason) {}

    /** 261113-trading-real-bot U6: the answer of {@code GET /api/v1/real/release}. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RealReleaseView")
    public record ReleaseView(
            @io.swagger.v3.oas.annotations.media.Schema(
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            String network,

            @io.swagger.v3.oas.annotations.media.Schema(
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            ReleaseStatus release) {}

    /** Who set the cap (U6, BR5.4). */
    public static final String SET_BY_USER = "USER";

    public static final String SET_BY_ADMIN = "ADMIN";

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

    /**
     * 261113-trading-real-bot U6 (BR5.4): the administrator only lowers a cap, and each lowering leaves a LOWER_CAP
     * command with the new value; the person's own changes leave none. So the cap is "ADMIN" while it still equals the
     * most recent lowering by the administrator, and "USER" otherwise.
     */
    static String capSetBy(long capLamports, java.util.Optional<Long> lastLoweredByAdmin) {
        return lastLoweredByAdmin.filter(cap -> cap == capLamports).isPresent() ? SET_BY_ADMIN : SET_BY_USER;
    }

    static ReleaseStatus releaseOf(com.memetrade.botwallet.domain.RealReleases.Current current) {
        return new ReleaseStatus(current.state(), current.effectiveAt(), current.blockedReason());
    }

    /** What the view carries since the U6: who set the cap and the release. */
    record Extras(String capSetBy, ReleaseStatus release) {}

    /** 261113-trading-real-bot U5: the view with the global pauses of the REAL merged into its paused reason. */
    static BotWalletView of(
            BotWalletAccount wallet,
            Preferences preferences,
            RealBalance balance,
            Instant now,
            java.util.Set<PauseReason> globalPauses,
            Extras extras) {
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
                pausedReason(preferences, globalPauses),
                wallet.network().name(),
                wallet.policyResetRequired(),
                extras.capSetBy(),
                extras.release());
    }

    /** The most severe reason first; NONE when not paused (contract PausedReason). */
    static String pausedReason(Preferences preferences) {
        return pausedReason(preferences, java.util.Set.of());
    }

    static String pausedReason(Preferences preferences, java.util.Set<PauseReason> globalPauses) {
        return java.util.stream.Stream.concat(preferences.pausedReasons().stream(), globalPauses.stream())
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
