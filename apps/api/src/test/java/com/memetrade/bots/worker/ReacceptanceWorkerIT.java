package com.memetrade.bots.worker;

import static com.memetrade.testsupport.U5Cycles.buySignal;
import static com.memetrade.testsupport.U5Cycles.decisions;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.U5Bots;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Passo 4 of u3-reacceptance (BR3.3, FR4.4, NFR2.2, NFR2.6; AC4.1.4): the worker has no web layer, so the reacceptance
 * lock does not exist there and the ACTIVE bots of a person who has not accepted the current terms keep their cycle.
 * The lock of the bot routes (403 in the api) is proved by {@code TermsReacceptanceRoutesIT}.
 */
@Tag("u3-reacceptance")
class ReacceptanceWorkerIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-28T12:05:00Z");

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private ApplicationContext context;

    @Test
    void theWorkerHasNoReacceptanceLock() {
        assertThat(context.getBeanNamesForType(com.memetrade.identity.web.TermsReacceptanceFilter.class))
                .isEmpty();
        assertThat(context.getBeanNamesForType(jakarta.servlet.Filter.class))
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("reacceptance"));
    }

    @Test
    void theActiveBotOfAPersonWithoutTheCurrentAcceptanceKeepsItsCycle() {
        Principal person = personWithoutAnyAcceptance();
        TokenId token = new TokenId("solana", SyntheticIds.mint());
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        clock.set(SLOT.plusSeconds(60));
        buySignal(market, assessments, token, SLOT);

        CycleResult result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);

        assertThat(result.kind()).isEqualTo(CycleResult.Kind.COMPLETED);
        assertThat(decisions(jdbc, botId)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from terms_acceptance where user_id = :u")
                        .param("u", person.userId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private Principal personWithoutAnyAcceptance() {
        TestAccounts.Seeded seeded = TestAccounts.betaUserWithoutAcceptance(jdbc, SyntheticIds.mint());
        return new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER);
    }
}
