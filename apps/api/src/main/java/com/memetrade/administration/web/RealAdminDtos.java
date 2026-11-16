package com.memetrade.administration.web;

import com.memetrade.botwallet.api.RealAdministration;
import com.memetrade.botwallet.api.RealKillSwitchState.KillSwitchView;
import com.memetrade.riskcontrol.api.BlockedToken;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bodies of the routes R4 of the administration of the REAL (261113-trading-real-bot U5, contract-summary.md R4 and the
 * addendum of the U5). Lamports and base units travel as integer text ({@code LamportsString}, signed where they may be
 * negative), never a JSON number. No body carries a key nor the full main wallet; the bot wallet is public.
 */
public final class RealAdminDtos {

    private RealAdminDtos() {}

    @Schema(name = "RealAdminReleaseRequest")
    public record ReleaseRequest(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"LOCALNET", "DEVNET", "MAINNET"})
            String network,

            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
            String reason) {}

    @Schema(name = "RealAdminReasonRequest")
    public record ReasonRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
            String reason) {}

    @Schema(name = "RealAdminCapRequest")
    public record CapRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,20}$")
            String capLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
            String reason) {}

    @Schema(name = "RealAdminTokenBlockRequest")
    public record TokenBlockRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[1-9A-HJ-NP-Za-km-z]{32,44}$")
            String mint,

            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
            String reason) {}

    @Schema(name = "RealAdminCompensationItem")
    public record CompensationItem(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "SOL ou o mint")
            String asset,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^-?[0-9]{1,20}$")
            String amount) {}

    @Schema(name = "RealAdminCompensationRequest")
    public record CompensationRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<CompensationItem> items,

            @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
            String reason) {}

    @Schema(name = "RealAdminRelease")
    public record Release(UUID releaseId, UUID userId, String network, String state, Instant effectiveAt) {
        static Release of(RealAdministration.ReleaseView view) {
            return new Release(view.releaseId(), view.userId(), view.network(), view.state(), view.effectiveAt());
        }
    }

    @Schema(name = "RealAdminRevocation")
    public record Revocation(UUID userId, boolean revoked, int cancelledOrders) {
        static Revocation of(RealAdministration.RevokeView view) {
            return new Revocation(view.userId(), view.revoked(), view.cancelledOrders());
        }
    }

    @Schema(name = "RealAdminCap")
    public record Cap(UUID userId, String capLamports, UUID commandId) {
        static Cap of(RealAdministration.CapView view) {
            return new Cap(view.userId(), Long.toString(view.capLamports()), view.commandId());
        }
    }

    @Schema(name = "RealAdminKillSwitch")
    public record KillSwitch(
            boolean active, Instant activatedAt, String reason, long version, boolean changed, int cancelledOrders) {
        static KillSwitch of(KillSwitchView view, boolean changed, int cancelledOrders) {
            return new KillSwitch(
                    view.active(), view.activatedAt(), view.reason(), view.version(), changed, cancelledOrders);
        }
    }

    @Schema(name = "RealAdminTokenDiff")
    public record TokenDiff(String mint, String chainBaseUnits, String ledgerBaseUnits, String diffBaseUnits) {}

    @Schema(name = "RealAdminDivergence")
    public record Divergence(
            UUID divergenceId,
            UUID botWalletId,
            String state,
            String lamportsDiff,
            List<TokenDiff> tokenDiffs,
            UUID lastRunId,
            Instant openedAt) {
        static Divergence of(RealAdministration.DivergenceView view) {
            return new Divergence(
                    view.divergenceId(),
                    view.botWalletId(),
                    view.state(),
                    Long.toString(view.lamportsDiff()),
                    view.tokenDiffs().stream()
                            .map(diff -> new TokenDiff(
                                    diff.mint(),
                                    Long.toString(diff.chain()),
                                    Long.toString(diff.ledger()),
                                    Long.toString(diff.diff())))
                            .toList(),
                    view.lastRunId(),
                    view.openedAt());
        }
    }

    @Schema(name = "RealAdminDivergencePage")
    public record DivergencePage(List<Divergence> items, String nextCursor) {}

    @Schema(name = "RealAdminBalance")
    public record Balance(
            String totalLamports, String freeLamports, String reservedLamports, String aboveCapLamports) {}

    @Schema(name = "RealAdminReleaseSummary")
    public record ReleaseSummary(String state, String network, Instant effectiveAt, String blockedReason) {}

    @Schema(name = "RealAdminOrder")
    public record Order(UUID realOrderId, String side, String mint, String state, Instant createdAt) {}

    @Schema(name = "RealAdminUser")
    public record User(
            UUID userId,
            UUID botWalletId,
            String walletStatus,

            @Schema(description = "Carteira principal abreviada: 4 primeiros … 4 últimos")
            String mainWalletShort,

            String botWalletAddress,
            String mode,
            String capLamports,
            Balance balance,
            ReleaseSummary release,
            Set<String> pausedReasons,
            List<Order> recentOrders,
            List<Divergence> openDivergences) {
        static User of(RealAdministration.RealUserView view) {
            return new User(
                    view.userId(),
                    view.botWalletId(),
                    view.walletStatus(),
                    view.mainWalletShort(),
                    view.botWalletAddress(),
                    view.mode(),
                    Long.toString(view.capLamports()),
                    new Balance(
                            Long.toString(view.balance().totalLamports()),
                            Long.toString(view.balance().freeLamports()),
                            Long.toString(view.balance().reservedLamports()),
                            Long.toString(view.balance().aboveCapLamports())),
                    new ReleaseSummary(
                            view.release().state(),
                            view.release().network(),
                            view.release().effectiveAt(),
                            view.release().blockedReason()),
                    view.pausedReasons(),
                    view.recentOrders().stream()
                            .map(order -> new Order(
                                    order.realOrderId(), order.side(), order.mint(), order.state(), order.createdAt()))
                            .toList(),
                    view.openDivergences().stream().map(Divergence::of).toList());
        }
    }

    @Schema(name = "RealAdminUserPage")
    public record UserPage(List<User> items, String nextCursor) {}

    @Schema(name = "RealAdminBlockedToken")
    public record Token(String chain, String mint, String reason, Instant blockedAt) {
        static Token of(BlockedToken token) {
            return new Token(token.chain(), token.mint(), token.reason(), token.blockedAt());
        }
    }

    @Schema(name = "RealAdminTokenList")
    public record TokenList(List<Token> items) {}

    @Schema(name = "RealAdminUnblock")
    public record Unblock(String mint, boolean unblocked) {}

    @Schema(name = "RealAdminRestore")
    public record Restore(UUID markerId, Instant detectedAt) {}
}
