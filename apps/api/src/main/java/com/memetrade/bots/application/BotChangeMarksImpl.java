package com.memetrade.bots.application;

import com.memetrade.bots.api.BotChangeMarks;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.identity.api.Principal;
import org.springframework.stereotype.Service;

/** C18 for U5 (BR8.1): the highest change mark of the person's bots, read by index and without any lock. */
@Service
public class BotChangeMarksImpl implements BotChangeMarks {

    private final JdbcBotRepository bots;

    public BotChangeMarksImpl(JdbcBotRepository bots) {
        this.bots = bots;
    }

    @Override
    public long markFor(Principal principal) {
        return bots.markFor(TenantScope.of(principal));
    }
}
