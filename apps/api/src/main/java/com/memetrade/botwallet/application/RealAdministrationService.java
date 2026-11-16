package com.memetrade.botwallet.application;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealAdministration;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.domain.AdminViewStore;
import com.memetrade.botwallet.domain.AdminViewStore.WalletRow;
import com.memetrade.botwallet.domain.BotWalletRepository;
import com.memetrade.botwallet.domain.Compensation;
import com.memetrade.botwallet.domain.Network;
import com.memetrade.botwallet.domain.Preferences;
import com.memetrade.botwallet.domain.RealRelease;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.RestoreStore;
import com.memetrade.botwallet.infra.RealProperties;
import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.shared.wallet.WalletDisplay;
import com.memetrade.shared.web.KeysetCursor;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * M1 for the administration of the REAL (261113-trading-real-bot U5, R4, BR1.2 to BR6.1): the one implementation of
 * {@link RealAdministration}, which the {@code RealAdminController} calls. The changes delegate to the services of each
 * rule; the view of a person (BR5.1) is built here from the bot wallet, the M2 (balance), the M6 (20 newest orders) and
 * the open divergence, with the main wallet abbreviated and never a key.
 */
@Service
public class RealAdministrationService implements RealAdministration {

    private static final String USERS_CURSOR = "real-admin-users";
    private static final String DIVERGENCES_CURSOR = "real-admin-divergences";
    private static final int RECENT_ORDERS = 20;

    private final ReleaseService releases;
    private final CapService caps;
    private final DivergenceService divergences;
    private final RestoreService restores;
    private final AdminViewStore views;
    private final BotWalletRepository wallets;
    private final RealStateQuery states;
    private final RealLedger ledger;
    private final RealExecutionPort execution;
    private final ReconciliationStore reconciliations;
    private final RealProperties properties;

    public RealAdministrationService(
            ReleaseService releases,
            CapService caps,
            DivergenceService divergences,
            RestoreService restores,
            AdminViewStore views,
            BotWalletRepository wallets,
            RealStateQuery states,
            RealLedger ledger,
            RealExecutionPort execution,
            ReconciliationStore reconciliations,
            RealProperties properties) {
        this.releases = releases;
        this.caps = caps;
        this.divergences = divergences;
        this.restores = restores;
        this.views = views;
        this.wallets = wallets;
        this.states = states;
        this.ledger = ledger;
        this.execution = execution;
        this.reconciliations = reconciliations;
        this.properties = properties;
    }

    @Override
    public ReleaseView adminRelease(UUID adminId, UUID userId, String network) {
        Network requested;
        try {
            requested = Network.valueOf(network == null ? "" : network);
        } catch (IllegalArgumentException unknown) {
            throw RealAdminRefused.validation();
        }
        return releases.release(adminId, userId, requested);
    }

    @Override
    public RevokeView adminRevoke(UUID adminId, UUID userId, String reason) {
        return releases.revoke(adminId, userId, reason);
    }

    @Override
    public Optional<UUID> tenantOf(UUID userId) {
        return releases.tenantOf(userId);
    }

    @Override
    public CapView adminLowerCap(UUID adminId, UUID userId, long capLamports) {
        return caps.lower(adminId, userId, capLamports);
    }

    @Override
    public Optional<RealUserView> adminUserView(UUID userId) {
        return views.walletOf(userId).map(wallet -> view(wallet, true));
    }

    @Override
    public Page<RealUserView> adminUsers(Optional<String> cursor, int limit) {
        int bounded = Math.max(1, Math.min(50, limit));
        Optional<KeysetCursor> after = cursor.map(text -> KeysetCursor.decode(text, USERS_CURSOR));
        List<WalletRow> rows = views.wallets(after.map(KeysetCursor::at), after.map(KeysetCursor::id), bounded + 1);
        List<RealUserView> items =
                rows.stream().limit(bounded).map(row -> view(row, false)).toList();
        String next = rows.size() > bounded
                ? new KeysetCursor(
                                rows.get(bounded - 1).createdAt(),
                                rows.get(bounded - 1).botWalletId())
                        .encode(USERS_CURSOR)
                : null;
        return new Page<>(items, next);
    }

    @Override
    public Page<DivergenceView> listDivergences(Optional<String> cursor, int limit) {
        int bounded = Math.max(1, Math.min(50, limit));
        Optional<KeysetCursor> before = cursor.map(text -> KeysetCursor.decode(text, DIVERGENCES_CURSOR));
        List<DivergenceView> rows =
                divergences.open(before.map(KeysetCursor::at), before.map(KeysetCursor::id), bounded + 1);
        List<DivergenceView> items = rows.stream().limit(bounded).toList();
        String next = rows.size() > bounded
                ? new KeysetCursor(items.getLast().openedAt(), items.getLast().divergenceId())
                        .encode(DIVERGENCES_CURSOR)
                : null;
        return new Page<>(items, next);
    }

    @Override
    public DivergenceView compensate(UUID adminId, UUID divergenceId, List<CompensationItem> items, String reason) {
        return divergences.compensate(
                adminId,
                divergenceId,
                items.stream()
                        .map(item -> new Compensation(item.asset(), item.amount()))
                        .toList(),
                reason);
    }

    @Override
    public RestoreView confirmRestore(UUID adminId) {
        RestoreStore.Marker marker = restores.confirm(adminId);
        return new RestoreView(marker.markerId(), marker.detectedAt());
    }

    private RealUserView view(WalletRow wallet, boolean withOrders) {
        Preferences preferences = wallets.preferences(wallet.botWalletId());
        RealBalance balance = ledger.balance(wallet.tenantId(), wallet.botWalletId());
        Set<String> paused = states
                .readRealState(wallet.tenantId(), wallet.userId())
                .map(RealStateView::pausedReasons)
                .orElse(Set.of())
                .stream()
                .map(RealPauseReason::name)
                .collect(Collectors.toSet());
        List<OrderSummary> orders = withOrders
                ? execution.recentOrders(wallet.tenantId(), wallet.userId(), RECENT_ORDERS).stream()
                        .map(order -> new OrderSummary(
                                order.realOrderId(),
                                order.side().name(),
                                order.mint(),
                                order.state().name(),
                                order.createdAt()))
                        .toList()
                : List.of();
        List<DivergenceView> open =
                reconciliations.openDivergence(wallet.botWalletId()).map(DivergenceService::view).stream()
                        .toList();
        return new RealUserView(
                wallet.userId(),
                wallet.botWalletId(),
                wallet.status(),
                WalletDisplay.abbreviate(wallet.mainWalletAddress()),
                wallet.publicAddress(),
                preferences.operationMode().name(),
                preferences.capLamports(),
                new BalanceView(
                        balance.totalLamports(),
                        balance.freeLamports(),
                        balance.reservedLamports(),
                        balance.aboveCapLamports(preferences.capLamports(), preferences.feeReserveLamports())),
                release(wallet.userId()),
                paused,
                orders,
                open);
    }

    private ReleaseSummary release(UUID userId) {
        return wallets.releases(userId).stream()
                .filter(release -> release.network() == properties.network())
                .max(Comparator.comparing(RealRelease::requestedAt))
                .map(release -> new ReleaseSummary(
                        release.state().name(),
                        release.network().name(),
                        release.effectiveAt(),
                        release.blockedReason() == null
                                ? null
                                : release.blockedReason().name()))
                .orElse(new ReleaseSummary("NONE", properties.network().name(), (Instant) null, null));
    }
}
