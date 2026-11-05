package com.memetrade.bots.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.domain.BotStatus;
import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.PositionStatus;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.application.ClosingService;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.U5Bots;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L13 and BR2.8, NFR10.8 (Passo 11, written BEFORE the closing check): a bot CLOSING_PENDING becomes STOPPED in the
 * slot after U7 closed the account, exactly once, with one event of actor {@code system}; while the account is still
 * CLOSING nothing changes. The check only READS the account of U7; it never calls closePositions again.
 */
class ClosingCheckIT extends AbstractU5WorkerIT {

    @Autowired
    private ClosingCheckJob closingCheck;

    @Autowired
    private LedgerReadModel ledger;

    @Autowired
    private PaperExecution paperExecution;

    @Autowired
    private ClosingService closing;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void l13ThePendingCloseCompletedByU7StopsTheBotOnce() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = pendingBot(person, token);

        closingCheck.runOnce();
        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.CLOSING_PENDING));

        market.values().set(token, "1.00", "100000000", 6);
        completeTheCloseInU7(botId);
        assertThat(ledger.account(botId).orElseThrow().status()).isEqualTo(AccountStatus.CLOSED);

        closingCheck.runOnce();
        closingCheck.runOnce();

        assertThat(fixtures.bot(person, botId).status()).isEqualTo(BotStatus.of(BotState.STOPPED));
        assertThat(OutboxEvents.count(jdbc, "BOT_STOPPED", "bot:" + botId)).isEqualTo(1);
        assertThat(OutboxEvents.payloads(jdbc, "BOT_STOPPED"))
                .anyMatch(payload -> payload.contains(botId.toString()) && payload.contains("SYSTEM"));
    }

    @Test
    void aBotWhoseAccountIsStillClosingStaysPending() {
        Principal person = newPerson();
        UUID botId = pendingBot(person, newToken());

        ClosingCheckJob.Round round = closingCheck.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(fixtures.bot(person, botId).status().state()).isEqualTo(BotState.CLOSING_PENDING);
        assertThat(ledger.account(botId).orElseThrow().status()).isEqualTo(AccountStatus.CLOSING);
    }

    /** A bot with a position and no reliable price, stopped by the owner: CLOSING_PENDING (BR2.7). */
    private UUID pendingBot(Principal person, TokenId token) {
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        market.values().set(token, "1.00", "100000000", 6);
        fixtures.as(person, () -> new TransactionTemplate(transactionManager)
                .execute(status -> paperExecution.execute(
                        new PaperExecution.PaperOrderRequest(
                                botId,
                                UUID.randomUUID(),
                                OrderSide.BUY,
                                token.chain(),
                                token.mint(),
                                new BigDecimal("50"),
                                1),
                        "test-buy:" + UUID.randomUUID())));
        market.values().price(token, ControllableMarketData.unknown());
        fixtures.as(person, () -> commands.stop(person, botId, U5Bots.key()));
        assertThat(fixtures.bot(person, botId).status().state()).isEqualTo(BotState.CLOSING_PENDING);
        return botId;
    }

    /** What the pending close job of U7 does in the api process once the price is reliable again (BR5.3 of U7). */
    private void completeTheCloseInU7(UUID botId) {
        for (var position : ledger.positions(botId)) {
            if (position.status() == PositionStatus.CLOSE_PENDING) {
                new TransactionTemplate(transactionManager)
                        .execute(status -> closing.completePending(botId, position.positionId()));
            }
        }
    }
}
