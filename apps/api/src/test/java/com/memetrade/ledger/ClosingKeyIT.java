package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.infra.PendingCloseJob;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review repair R-05 of the code generation (written before the code): the key of {@code closePositions} (C15) has at
 * most 163 characters, so the derived key {@code <key>:<positionId>} of every CLOSE order (one colon and a 36-character
 * UUID) always fits {@code paper_order.idempotency_key varchar(200)}; a longer key is a validation error raised
 * before anything is written. The CLOSE order of a pending position is found by equality, never by a {@code LIKE} over
 * text supplied by the caller (BR3.1, BR5.2, BR5.3).
 */
class ClosingKeyIT extends AbstractU7IT {

    private static final int MAX_CLOSE_KEY = 200 - 1 - 36;

    @Autowired
    private PaperExecution execution;

    @Autowired
    private PendingCloseJob job;

    private PaperDriver driver;
    private LedgerFacts facts;
    private Principal person;
    private TokenId first;
    private TokenId second;

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        facts = new LedgerFacts(jdbc);
        person = newPerson();
        first = newToken();
        second = newToken();
        market.set(first, "1.000000", "100000.000000", 6);
        market.set(second, "1.000000", "100000.000000", 6);
    }

    private UUID botWithTwoPositions() {
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, first, "100");
        driver.buy(person, bot, second, "50");
        return bot;
    }

    private static String keyOf(int length) {
        return ("close-" + UUID.randomUUID() + "-").repeat(10).substring(0, length);
    }

    private long closeOrders(UUID bot) {
        return jdbc.sql("select count(*) from paper_order where bot_id = :b and kind = 'CLOSE'")
                .param("b", bot)
                .query(Long.class)
                .single();
    }

    @Test
    void aKeyOf163CharactersClosesAndEveryDerivedKeyFitsTheColumn() {
        UUID bot = botWithTwoPositions();
        market.set(first, "1.100000", "100000.000000", 6);
        market.set(second, "1.100000", "100000.000000", 6);
        String key = keyOf(MAX_CLOSE_KEY);

        CloseResult.Closed closed = (CloseResult.Closed) driver.close(person, bot, key);

        assertThat(closed.accountStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.closed()).hasSize(2);
        List<String> derived = jdbc.sql("select idempotency_key from paper_order where bot_id = :b and kind = 'CLOSE'")
                .param("b", bot)
                .query(String.class)
                .list();
        assertThat(derived)
                .hasSize(2)
                .allSatisfy(k -> assertThat(k).hasSize(200).startsWith(key + ":"));
    }

    @Test
    void aKeyOf164CharactersIsAValidationErrorAndNothingIsWritten() {
        UUID bot = botWithTwoPositions();
        long orders = facts.orders(bot);
        long entries = facts.entries(bot);

        assertThatThrownBy(() -> driver.close(person, bot, keyOf(MAX_CLOSE_KEY + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("163");

        assertThat(facts.accountStatus(bot)).isEqualTo("OPEN");
        assertThat(facts.orders(bot)).isEqualTo(orders);
        assertThat(facts.entries(bot)).isEqualTo(entries);
        assertThat(closeOrders(bot)).isZero();
        assertThat(facts.position(bot, first.mint()).get("status")).isEqualTo("OPEN");
    }

    @Test
    void aBlankKeyIsAValidationErrorAndNothingIsWritten() {
        UUID bot = botWithTwoPositions();

        assertThatThrownBy(() -> driver.close(person, bot, "  ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(facts.accountStatus(bot)).isEqualTo("OPEN");
        assertThat(closeOrders(bot)).isZero();
    }

    /**
     * Regression of R-05: two positions of the same bot left pending by a closing whose key carries {@code LIKE}
     * wildcards and the id of the OTHER position; a repeated closing and the pending close job each resolve the
     * CLOSE order of their own position.
     */
    @Test
    void eachPendingPositionResolvesItsOwnCloseOrderWhateverTheClosingKeyContains() {
        UUID bot = botWithTwoPositions();
        market.price(first, ControllableMarketData.unknown());
        market.price(second, ControllableMarketData.unknown());
        UUID secondPosition = (UUID) facts.position(bot, second.mint()).get("position_id");
        String key = "%_:" + secondPosition;

        CloseResult.Closed closing = (CloseResult.Closed) driver.close(person, bot, key);
        CloseResult.Closed again =
                (CloseResult.Closed) driver.close(person, bot, UUID.randomUUID().toString());

        assertThat(closing.pending()).hasSize(2);
        assertThat(again.pending())
                .extracting(CloseResult.PendingPosition::positionId, CloseResult.PendingPosition::orderId)
                .containsExactlyInAnyOrderElementsOf(closing.pending().stream()
                        .map(p -> org.assertj.core.groups.Tuple.tuple(p.positionId(), p.orderId()))
                        .toList());
        assertThat(closeOrders(bot)).isEqualTo(2);

        market.set(second, "1.200000", "100000.000000", 6);
        job.runOnce();

        CloseResult.PendingPosition secondPending = closing.pending().stream()
                .filter(p -> p.positionId().equals(secondPosition))
                .findFirst()
                .orElseThrow();
        CloseResult.PendingPosition firstPending = closing.pending().stream()
                .filter(p -> !p.positionId().equals(secondPosition))
                .findFirst()
                .orElseThrow();
        assertThat(facts.order(secondPending.orderId()).get("status")).isEqualTo("FILLED");
        assertThat(facts.order(secondPending.orderId()).get("token_mint")).isEqualTo(second.mint());
        assertThat(facts.order(firstPending.orderId()).get("status")).isEqualTo("CLOSE_PENDING");
        assertThat(facts.position(bot, second.mint()).get("status")).isEqualTo("CLOSED");
        assertThat(facts.position(bot, first.mint()).get("status")).isEqualTo("CLOSE_PENDING");
        assertThat(facts.accountStatus(bot)).isEqualTo("CLOSING");
    }
}
