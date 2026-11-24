package com.memetrade.architecture.othertenant;

import com.memetrade.pnl.api.BotDirectory;
import java.util.UUID;

/**
 * 261121-paper-real-unico U3 (test sources only): a class outside the path of the 404 that asks whether a bot belongs to
 * another tenant. {@code PnlViewsArchitectureTest} must fail on it.
 */
public final class AskingOtherTenantsSample {

    private final BotDirectory directory;

    public AskingOtherTenantsSample(BotDirectory directory) {
        this.directory = directory;
    }

    public boolean peek(UUID tenantId, UUID botId) {
        return directory.ownedByAnotherTenant(tenantId, botId);
    }
}
