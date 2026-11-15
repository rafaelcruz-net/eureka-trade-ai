package com.memetrade.bots.application;

import com.memetrade.bots.api.RealBotOwnership;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** {@link RealBotOwnership} over the bot table of this module, filtered by tenant and owner (BR2.10). */
@Service
public class RealBotOwnershipImpl implements RealBotOwnership {

    private final JdbcClient jdbc;

    public RealBotOwnershipImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean activeRealBotOf(UUID tenantId, UUID userId, UUID botId) {
        return jdbc.sql("""
                        select exists (select 1 from bot where bot_id = :bot and tenant_id = :tenant
                          and owner_user_id = :owner and market = 'REAL' and state = 'ACTIVE' and pause_reasons = '')""")
                .param("bot", botId)
                .param("tenant", tenantId)
                .param("owner", userId)
                .query(Boolean.class)
                .single();
    }
}
