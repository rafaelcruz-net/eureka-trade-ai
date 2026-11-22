package com.memetrade.bots.application;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcBotRepository.DirectoryRow;
import com.memetrade.pnl.api.BotDirectory;
import com.memetrade.pnl.api.BotEntry;
import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.api.PnlMarket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1, C8 (BR4.1, BR8.1, BR8.2; ADR-007): the adapter of the {@code bots} module behind the port
 * {@link BotDirectory} that the P&L declares. ACTIVE and CLOSING_PENDING become ACTIVE, PAUSED stays PAUSED, STOPPED
 * becomes REMOVED (a removed bot keeps its P&L); DRAFT and VALIDATED never appear. Filtered by tenant and person; a bot
 * of somebody else is simply absent. Read only, in the transaction of the caller.
 */
@Component
public class BotDirectoryImpl implements BotDirectory {

    private final JdbcBotRepository bots;

    public BotDirectoryImpl(JdbcBotRepository bots) {
        this.bots = bots;
    }

    @Override
    public List<BotEntry> botsOf(UUID tenantId, UUID userId, PnlMarket market) {
        return bots.directory(scope(tenantId, userId), Optional.of(market.name()), Optional.empty()).stream()
                .map(BotDirectoryImpl::entry)
                .toList();
    }

    @Override
    public Optional<BotEntry> botOf(UUID tenantId, UUID userId, UUID botId) {
        return bots.directory(scope(tenantId, userId), Optional.empty(), Optional.of(botId)).stream()
                .findFirst()
                .map(BotDirectoryImpl::entry);
    }

    /** BR4.1: the state of a bot reduced for the P&L. */
    static BotPnlStatus status(BotState state) {
        return switch (state) {
            case ACTIVE, CLOSING_PENDING -> BotPnlStatus.ACTIVE;
            case PAUSED -> BotPnlStatus.PAUSED;
            case STOPPED -> BotPnlStatus.REMOVED;
            case DRAFT, VALIDATED -> throw new IllegalArgumentException("A draft never enters the P&L");
        };
    }

    private static BotEntry entry(DirectoryRow row) {
        return new BotEntry(
                row.botId(),
                row.name(),
                PnlMarket.valueOf(row.market()),
                row.templateId(),
                status(row.state()),
                row.createdAt());
    }

    private static TenantScope scope(UUID tenantId, UUID userId) {
        return new TenantScope(tenantId, userId);
    }
}
