package com.memetrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.infra.JdbcLedgerRepository;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Passo 11 (AC4.11.2, NFR4.3): a technical failure injected in EACH write of a fill rolls the whole cycle back - no
 * order, no fill, no entry, no position change, no summary, no event - and the retry with the same key is then the
 * first execution and records everything once (BR2.1, BR3.2).
 */
class PaperExecutionAtomicityIT extends AbstractU7IT {

    @MockitoSpyBean
    private JdbcLedgerRepository repository;

    @Autowired
    private PaperExecution execution;

    @AfterEach
    void stopInjecting() {
        reset(repository);
    }

    private static final DataAccessResourceFailureException INJECTED =
            new DataAccessResourceFailureException("injected failure");

    private final List<Consumer<JdbcLedgerRepository>> injections = List.of(
            spy -> doThrow(INJECTED).when(spy).insertOrderIfAbsent(any()),
            spy -> doThrow(INJECTED).when(spy).insertFill(any(), any(), any(), any(), any()),
            spy -> doThrow(INJECTED)
                    .when(spy)
                    .insertEntry(any(UUID.class), any(UUID.class), any(), any(), any(), any(), any(), any()),
            spy -> doThrow(INJECTED).when(spy).updatePosition(any(), any(), any(), any(), any()),
            spy -> doThrow(INJECTED).when(spy).addDaily(any(), any(), any(), any(), any(), any(), any()),
            spy -> doThrow(INJECTED)
                    .when(spy)
                    .lockOrCreatePosition(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()));

    @Test
    void aFailureInEachWriteLeavesNothingAndTheRetryWritesEverythingOnce() {
        PaperDriver driver = new PaperDriver(execution, transactions);
        LedgerFacts facts = new LedgerFacts(jdbc);
        Principal person = newPerson();
        TokenId token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);

        for (int point = 0; point < injections.size(); point++) {
            UUID bot = driver.fundedBot(person, "1000.000000");
            String key = UUID.randomUUID().toString();
            var request = PaperDriver.request(bot, OrderSide.BUY, token, "100", UUID.randomUUID(), 1);
            injections.get(point).accept(repository);

            assertThatThrownBy(() -> driver.order(person, request, key))
                    .as("write point " + point)
                    .isInstanceOf(DataAccessResourceFailureException.class);
            reset(repository);

            assertThat(facts.orders(bot)).as("write point " + point).isZero();
            assertThat(facts.fills(bot)).isZero();
            assertThat(facts.entries(bot)).as("only the FUNDING").isEqualTo(1);
            assertThat(facts.daily(bot)).isEmpty();
            assertThat(jdbc.sql("select count(*) from position where bot_id = :b")
                            .param("b", bot)
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(facts.balance(bot)).isEqualByComparingTo("1000");

            ExecutionResult retry = driver.order(person, request, key);
            assertThat(retry).as("the retry is the first execution").isInstanceOf(ExecutionResult.Filled.class);
            UUID orderId = ((ExecutionResult.Filled) retry).orderId();
            assertThat(facts.orders(bot)).isEqualTo(1);
            assertThat(facts.entries(bot)).isEqualTo(4);
            assertThat(facts.events(AuditActions.PAPER_ORDER_FILLED, "order:" + orderId))
                    .isEqualTo(1);
        }
    }

    @Test
    void aFailureWhileClosingLeavesTheAccountOpen() {
        PaperDriver driver = new PaperDriver(execution, transactions);
        LedgerFacts facts = new LedgerFacts(jdbc);
        Principal person = newPerson();
        TokenId token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
        UUID bot = driver.fundedBot(person, "1000.000000");
        driver.buy(person, bot, token, "100");
        doThrow(INJECTED).when(repository).insertFill(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> driver.close(person, bot, UUID.randomUUID().toString()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(facts.accountStatus(bot)).isEqualTo("OPEN");
        assertThat(facts.position(bot, token.mint()).get("status")).isEqualTo("OPEN");
        assertThat(facts.orders(bot)).isEqualTo(1);
    }
}
