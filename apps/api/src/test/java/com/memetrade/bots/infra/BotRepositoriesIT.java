package com.memetrade.bots.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 8 of U5, written BEFORE the repositories and LockOrder (US4.4; BR2.10, BR2.11; NFR5.1, NFR10.4): every read of
 * a bot carries the tenant and the person; the locks follow the single global order (system pause, owner, bot) and an
 * inverted or out-of-transaction lock is a programming error.
 */
class BotRepositoriesIT extends AbstractU5WorkerIT {

    @Autowired
    private LockOrder locks;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aBotIsFoundOnlyWithTheScopeOfItsOwner() {
        Principal owner = newPerson();
        Principal sameTenant = samePersonTenant(owner);
        Principal otherTenant = newPerson();
        UUID botId = fixtures.draft(owner);

        assertThat(bots.find(botId, TenantScope.of(owner))).isPresent();
        assertThat(bots.find(botId, TenantScope.of(sameTenant))).isEmpty();
        assertThat(bots.find(botId, TenantScope.of(otherTenant))).isEmpty();
        assertThat(bots.list(TenantScope.of(sameTenant), Optional.empty(), Optional.empty(), 50)
                        .items())
                .isEmpty();
    }

    @Test
    void theListFiltersByStateAndCountsOnlyTheActiveBotsOfThePerson() {
        Principal owner = newPerson();
        TokenId token = newToken();
        fixtures.draft(owner);
        fixtures.active(owner, token);
        fixtures.active(newPerson(), token);

        assertThat(bots.list(TenantScope.of(owner), Optional.empty(), Optional.empty(), 50)
                        .items())
                .hasSize(2);
        assertThat(bots.list(TenantScope.of(owner), Optional.of(BotState.ACTIVE), Optional.empty(), 50)
                        .items())
                .extracting(bot -> bot.status().state())
                .containsExactly(BotState.ACTIVE);
        assertThat(bots.countActive(TenantScope.of(owner))).isEqualTo(1);
    }

    @Test
    void theListIsPagedByCursorWithoutRepetition() {
        Principal owner = newPerson();
        for (int i = 0; i < 5; i++) {
            fixtures.draft(owner);
            clock.advance(java.time.Duration.ofSeconds(1));
        }
        var first = bots.list(TenantScope.of(owner), Optional.empty(), Optional.empty(), 2);
        var second = bots.list(TenantScope.of(owner), Optional.empty(), first.next(), 2);
        var third = bots.list(TenantScope.of(owner), Optional.empty(), second.next(), 2);

        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(2);
        assertThat(third.items()).hasSize(1);
        assertThat(third.next()).isEmpty();
        List<UUID> all = java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> page.items().stream())
                .map(Bot::botId)
                .distinct()
                .toList();
        assertThat(all).hasSize(5);
    }

    @Test
    void theLocksFollowTheGlobalOrderInsideOneTransaction() {
        Principal owner = newPerson();
        UUID botId = fixtures.draft(owner);
        TenantScope scope = TenantScope.of(owner);

        assertThatCode(() -> inTransaction(() -> {
                    locks.lockSystemPauseShared();
                    locks.lockOwner(scope);
                    return locks.lockBot(botId, scope).orElseThrow();
                }))
                .doesNotThrowAnyException();
        assertThat(inTransaction(() -> locks.lockBot(botId, TenantScope.of(newPerson()))))
                .isEmpty();
    }

    @Test
    void anInvertedLockIsAProgrammingError() {
        Principal owner = newPerson();
        UUID botId = fixtures.draft(owner);
        TenantScope scope = TenantScope.of(owner);

        assertThatThrownBy(() -> inTransaction(() -> {
                    locks.lockBot(botId, scope);
                    locks.lockOwner(scope);
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lock order");
        assertThatThrownBy(() -> inTransaction(() -> {
                    locks.lockOwner(scope);
                    locks.lockSystemPauseShared();
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lock order");
        assertThatThrownBy(() -> locks.lockBot(botId, scope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    @Test
    void everyChangeOfStatusRenewsTheMarkOfThePersonAndTheMarkOnlyGrows() {
        Principal owner = newPerson();
        TenantScope scope = TenantScope.of(owner);
        UUID botId = fixtures.active(owner, newToken());
        long before = bots.markFor(scope);

        inTransaction(() -> {
            Bot bot = locks.lockBot(botId, scope).orElseThrow();
            bots.updateStatus(bot.withStatus(BotStatus.of(BotState.PAUSED, PauseReason.PERSON), clock.instant()));
            return null;
        });

        assertThat(bots.markFor(scope)).isGreaterThan(before);
        assertThat(bots.markFor(TenantScope.of(newPerson()))).isZero();
        assertThat(bots.marksFor(List.of(scope))).containsEntry(scope, bots.markFor(scope));
    }

    @Test
    void theHoldingsAreKeptPerBotAndToken() {
        Principal owner = newPerson();
        UUID botId = fixtures.active(owner, newToken());
        TokenId token = newToken();
        Instant opened = clock.instant();

        inTransaction(() -> {
            bots.openHolding(botId, token, opened);
            bots.openHolding(botId, token, opened.plusSeconds(60));
            return null;
        });
        assertThat(bots.holdings(botId)).containsEntry(token, opened);

        inTransaction(() -> {
            bots.closeHolding(botId, token);
            return null;
        });
        assertThat(bots.holdings(botId)).isEmpty();
    }

    @Test
    void theWorkerListsReadTheScopeFromEachRow() {
        Principal owner = newPerson();
        UUID botId = fixtures.active(owner, newToken());

        assertThat(bots.activeBots()).contains(new BotRef(botId, TenantScope.of(owner)));
        assertThat(bots.botsWith(PauseReason.DAILY_LOSS)).doesNotContain(new BotRef(botId, TenantScope.of(owner)));
        assertThat(bots.countByState().get(BotState.ACTIVE)).isPositive();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
