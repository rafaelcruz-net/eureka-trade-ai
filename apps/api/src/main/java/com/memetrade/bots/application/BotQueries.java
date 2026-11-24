package com.memetrade.bots.application;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotLimits;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.identity.api.Principal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of C4 (BR6.1, BR6.2): the bots of the person, one bot with its draft and limits, and its decisions. Every
 * read is filtered by the tenant and the person of the token (NFR5.1); another person's bot is not found.
 */
@Service
public class BotQueries {

    private final JdbcBotRepository bots;
    private final JdbcDecisionRepository decisions;

    public BotQueries(JdbcBotRepository bots, JdbcDecisionRepository decisions) {
        this.bots = bots;
        this.decisions = decisions;
    }

    /** One bot of the person with the limits in force (or the draft limits before VALIDATED). */
    public record BotDetail(Bot bot, Optional<BotLimits> limits) {}

    /**
     * @param matchingCount 261121-paper-real-unico U3 (BR1.5): the bots of the person that pass the filters of the page
     * @param totalCount 261121-paper-real-unico U3 (BR1.3): every bot of the person, without state nor market
     */
    public record BotList(JdbcBotRepository.BotPage page, long activeCount, long matchingCount, long totalCount) {}

    public BotList list(Principal person, Optional<BotState> state, Optional<String> cursor, int limit) {
        return list(person, state, Optional.empty(), cursor, limit);
    }

    /**
     * 261121-paper-real-unico U3 (BR1.2, BR1.3, BR1.5, NFR1.2, NFR1.10): the page with the market as one more filter, and
     * the two counts, read in the same read-only transaction; no lock.
     *
     * @param market {@code PAPER} or {@code REAL}; empty for both
     */
    @Transactional(readOnly = true)
    public BotList list(
            Principal person, Optional<BotState> state, Optional<String> market, Optional<String> cursor, int limit) {
        TenantScope scope = TenantScope.of(person);
        return new BotList(
                bots.list(scope, state, market, cursor, limit),
                bots.countActive(scope),
                bots.count(scope, state, market),
                bots.count(scope, Optional.empty(), Optional.empty()));
    }

    public BotDetail get(Principal person, UUID botId) {
        Bot bot = bots.find(botId, TenantScope.of(person)).orElseThrow(BotNotFoundException::new);
        Optional<BotLimits> limits = bot.currentLimitsVersion()
                .flatMap(version -> bots.limits(botId, version))
                .or(bot::draftLimits);
        return new BotDetail(bot, limits);
    }

    public JdbcDecisionRepository.DecisionPage decisions(
            Principal person, UUID botId, Optional<String> cursor, int limit) {
        bots.find(botId, TenantScope.of(person)).orElseThrow(BotNotFoundException::new);
        return decisions.page(botId, person.tenantId(), person.userId(), cursor, limit);
    }
}
