package com.memetrade.ledger.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.EntryPage;
import com.memetrade.ledger.api.LedgerReadModel.FillView;
import com.memetrade.ledger.api.LedgerReadModel.MarkingSnapshot;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.infra.JdbcLedgerRepository.NewOrder;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.LedgerRows;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Passo 9 (written before the code): the ledger repository and the public read model (BR3.1, BR9.1, BR9.2, NFR2.2,
 * NFR2.4) over PostgreSQL with the application login.
 */
class LedgerReadModelIT extends AbstractU7IT {

    private static final Instant FROM = Instant.parse("2026-10-28T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-29T00:00:00Z");

    @Autowired
    private LedgerReadModel readModel;

    @Autowired
    private JdbcLedgerRepository repository;

    private LedgerRows rows;
    private Principal person;
    private UUID bot;

    @BeforeEach
    void seed() {
        rows = new LedgerRows(appRole());
        person = newPerson();
        bot = rows.account(person, "OPEN");
    }

    private NewOrder order(String key) {
        return new NewOrder(
                UUID.randomUUID(),
                person.tenantId(),
                person.userId(),
                bot,
                UUID.randomUUID(),
                key,
                "a".repeat(64),
                "TRADE",
                "BUY",
                "solana",
                "MintReadModel",
                new BigDecimal("100"),
                1,
                "FILLED",
                null,
                clock.instant());
    }

    @Test
    void theIdempotentInsertDoesNotAbortTheTransactionOnACollision() {
        String key = "key-" + UUID.randomUUID();
        boolean[] inserted = new boolean[2];

        transactions.executeWithoutResult(status -> {
            inserted[0] = repository.insertOrderIfAbsent(order(key));
            inserted[1] = repository.insertOrderIfAbsent(order(key));
            repository.insertEntry(
                    person, bot, EntryType.FUNDING, new BigDecimal("10.000000"), "fund-" + key, null, clock.instant());
        });

        assertThat(inserted).containsExactly(true, false);
        assertThat(repository.findOrder(person.tenantId(), key)).isPresent();
        assertThat(readModel.balance(bot)).isEqualByComparingTo("10");
    }

    @Test
    void theBalanceIsTheSumOfTheEntriesOfTheBotOnly() {
        rows.entry(person, bot, "FUNDING", "1000.000000", "fund-" + UUID.randomUUID(), null);
        rows.entry(person, bot, "TRADE_COST", "-100.000000", UUID.randomUUID().toString(), null);
        rows.entry(person, bot, "FEE", "-0.301800", UUID.randomUUID().toString(), null);
        UUID other = rows.account(person, "OPEN");
        rows.entry(person, other, "FUNDING", "5.000000", "fund-" + UUID.randomUUID(), null);

        assertThat(readModel.balance(bot)).isEqualTo(new BigDecimal("899.698200"));
        assertThat(readModel.balance(UUID.randomUUID())).isEqualByComparingTo("0");
    }

    @Test
    void theLedgerPagesByCursorNewestFirstAndOnlyForThePersonAndBot() {
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(1));
            repository.insertEntry(
                    person, bot, EntryType.FEE, new BigDecimal("-0.10000" + i), "ref-" + i, null, clock.instant());
        }
        Principal stranger = newPerson();
        UUID strangerBot = rows.account(stranger, "OPEN");
        rows.entry(stranger, strangerBot, "FUNDING", "1.000000", "fund-" + UUID.randomUUID(), null);

        EntryPage first = readModel.entries(person.tenantId(), person.userId(), Optional.of(bot), Optional.empty(), 2);
        EntryPage second = readModel.entries(person.tenantId(), person.userId(), Optional.of(bot), first.next(), 2);
        EntryPage third = readModel.entries(person.tenantId(), person.userId(), Optional.of(bot), second.next(), 2);

        assertThat(first.total()).isEqualTo(5);
        assertThat(first.items())
                .extracting(LedgerReadModel.EntryView::referenceId)
                .containsExactly("ref-4", "ref-3");
        assertThat(second.items())
                .extracting(LedgerReadModel.EntryView::referenceId)
                .containsExactly("ref-2", "ref-1");
        assertThat(third.items())
                .extracting(LedgerReadModel.EntryView::referenceId)
                .containsExactly("ref-0");
        assertThat(third.next()).isEmpty();
        assertThat(readModel
                        .entries(stranger.tenantId(), person.userId(), Optional.empty(), Optional.empty(), 10)
                        .items())
                .as("another tenant with the same person id sees nothing")
                .isEmpty();
    }

    @Test
    void theFillsOfAnIntervalCarryTheFeesNetOfTheirCompensations() {
        UUID inside = rows.order(person, bot, "TRADE", "FILLED", null, "k-" + UUID.randomUUID());
        rows.fill(person, bot, inside);
        UUID fee = rows.entry(person, bot, "FEE", "-0.003018", inside.toString(), null);
        rows.entry(person, bot, "COMPENSATION", "0.003018", fee.toString(), fee);

        List<FillView> fills = readModel.fills(person.tenantId(), person.userId(), Optional.of(bot), FROM, TO);

        assertThat(fills).hasSize(1);
        assertThat(fills.getFirst().fees()).isEqualByComparingTo("0");
        assertThat(fills.getFirst().realized()).isEqualByComparingTo("0");
        assertThat(readModel.fills(person.tenantId(), person.userId(), Optional.of(bot), TO, TO.plusSeconds(1)))
                .as("[from, to) excludes the fill of the 28th")
                .isEmpty();
        assertThat(readModel.fills(person.tenantId(), UUID.randomUUID(), Optional.empty(), FROM, TO))
                .isEmpty();
    }

    @Test
    void theDailySummaryRowsOfWholeDaysAreRead() {
        rows.daily(person, bot, LocalDate.parse("2026-10-27"), "1.000000", "0.100000", 1);
        rows.daily(person, bot, LocalDate.parse("2026-10-28"), "2.000000", "0.200000", 2);

        assertThat(readModel.dailySummary(
                        person.tenantId(),
                        person.userId(),
                        Optional.empty(),
                        LocalDate.parse("2026-10-28"),
                        LocalDate.parse("2026-10-29")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.realized()).isEqualByComparingTo("2");
                    assertThat(row.fillCount()).isEqualTo(2);
                });
    }

    @Test
    void theMarkingLockReturnsBalanceAndPositionsOfTheSameInstantAndNeedsATransaction() {
        rows.entry(person, bot, "FUNDING", "1000.000000", "fund-" + UUID.randomUUID(), null);
        rows.position(person, bot, "100", "100.600000", "OPEN");

        MarkingSnapshot snapshot =
                transactions.execute(status -> readModel.lockAccountForMarking(bot, Duration.ofSeconds(1)));

        assertThat(snapshot.balance()).isEqualByComparingTo("1000");
        assertThat(snapshot.positions()).singleElement().satisfies(p -> assertThat(p.costBasis())
                .isEqualByComparingTo("100.6"));
        assertThat(snapshot.account().status()).isEqualTo(AccountStatus.OPEN);
        assertThatThrownBy(() -> readModel.lockAccountForMarking(bot, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theAccountsToMarkAreOpenOrClosingAndThePeopleAreListedOnce() {
        UUID closed = rows.account(person, "OPEN");
        appRole()
                .sql("update paper_account set status = 'CLOSING' where bot_id = :b")
                .param("b", closed)
                .update();
        appRole()
                .sql("update paper_account set status = 'CLOSED' where bot_id = :b")
                .param("b", closed)
                .update();

        assertThat(readModel.accountsToMark())
                .extracting(LedgerReadModel.AccountView::botId)
                .contains(bot)
                .doesNotContain(closed);
        assertThat(readModel.peopleWithAccounts())
                .filteredOn(p -> p.ownerUserId().equals(person.userId()))
                .hasSize(1);
        assertThat(readModel.account(bot))
                .get()
                .extracting(LedgerReadModel.AccountView::ownerUserId)
                .isEqualTo(person.userId());
    }

    @Test
    void theLastFilledInstantAndThePolicyInForceAreRead() {
        assertThat(readModel.lastFilledAt(bot)).isEmpty();
        UUID order = rows.order(person, bot, "TRADE", "FILLED", null, "k-" + UUID.randomUUID());
        rows.fill(person, bot, order);

        assertThat(readModel.lastFilledAt(bot)).contains(LedgerRows.AT.toInstant());
        assertThat(readModel.policy().version()).isEqualTo("v1");
        assertThat(readModel.policy().feePct()).isEqualByComparingTo("0.3000");
    }

    @Test
    void theTradesOfABotListEveryOrderOnceWithItsFill() {
        UUID filled = rows.order(person, bot, "TRADE", "FILLED", null, "k-" + UUID.randomUUID());
        rows.fill(person, bot, filled);
        rows.order(person, bot, "TRADE", "NOT_FILLED", "NO_LIQUIDITY", "k-" + UUID.randomUUID());

        LedgerReadModel.TradePage page =
                readModel.trades(person.tenantId(), person.userId(), bot, Optional.empty(), 10);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items())
                .filteredOn(t -> t.orderId().equals(filled))
                .singleElement()
                .satisfies(t -> assertThat(t.fill()).isPresent());
        assertThat(page.items())
                .filteredOn(t -> t.fill().isEmpty())
                .singleElement()
                .satisfies(t -> assertThat(t.notFilledReason()).isPresent());
        assertThat(readModel
                        .trades(person.tenantId(), UUID.randomUUID(), bot, Optional.empty(), 10)
                        .items())
                .isEmpty();
    }
}
