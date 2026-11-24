package com.memetrade.architecture.othertenant;

import com.memetrade.bots.application.BotDirectoryImpl;
import java.util.UUID;

/**
 * 261121-paper-real-unico U3 (test sources only; review finding 1): a class outside the path of the 404 that asks
 * whether a bot belongs to another tenant through the CONCRETE adapter, not the port. {@code PnlViewsArchitectureTest}
 * must fail on it too.
 */
public final class AskingThroughTheImplementationSample {

    private final BotDirectoryImpl directory;

    public AskingThroughTheImplementationSample(BotDirectoryImpl directory) {
        this.directory = directory;
    }

    public boolean peek(UUID tenantId, UUID botId) {
        return directory.ownedByAnotherTenant(tenantId, botId);
    }
}
