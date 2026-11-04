package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.api.PaperExecution.FundResult;
import com.memetrade.ledger.api.PaperExecution.PaperOrderRequest;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives {@link PaperExecution} the way the bot cycle of U5 will: one cycle transaction per call, with the person of
 * the bot bound in the cycle authentication context (C10). U5 does not exist yet (code-generation-plan.md).
 */
public final class PaperDriver {

    private final PaperExecution execution;
    private final TransactionTemplate transactions;

    public PaperDriver(PaperExecution execution, TransactionTemplate transactions) {
        this.execution = execution;
        this.transactions = transactions;
    }

    /** A new bot of {@code person}, funded with {@code amount}. */
    public UUID fundedBot(Principal person, String amount) {
        UUID botId = UUID.randomUUID();
        FundResult result = fund(person, botId, amount, UUID.randomUUID().toString());
        if (!(result instanceof FundResult.Funded)) {
            throw new IllegalStateException("fund failed: " + result);
        }
        return botId;
    }

    public FundResult fund(Principal person, UUID botId, String amount, String key) {
        return CycleAuthContext.as(
                person, () -> transactions.execute(status -> execution.fund(botId, new BigDecimal(amount), key)));
    }

    public ExecutionResult order(
            Principal person, UUID botId, OrderSide side, TokenId token, String quantity, String key) {
        return order(person, request(botId, side, token, quantity, UUID.randomUUID(), 1), key);
    }

    public ExecutionResult order(Principal person, PaperOrderRequest request, String key) {
        return CycleAuthContext.as(person, () -> transactions.execute(status -> execution.execute(request, key)));
    }

    public ExecutionResult buy(Principal person, UUID botId, TokenId token, String quantity) {
        return order(
                person, botId, OrderSide.BUY, token, quantity, UUID.randomUUID().toString());
    }

    public ExecutionResult sell(Principal person, UUID botId, TokenId token, String quantity) {
        return order(
                person,
                botId,
                OrderSide.SELL,
                token,
                quantity,
                UUID.randomUUID().toString());
    }

    public CloseResult close(Principal person, UUID botId, String key) {
        return CycleAuthContext.as(
                person, () -> transactions.execute(status -> execution.closePositions(botId, 3, key)));
    }

    public static PaperOrderRequest request(
            UUID botId, OrderSide side, TokenId token, String quantity, UUID decisionId, long limitsVersion) {
        return new PaperOrderRequest(
                botId, decisionId, side, token.chain(), token.mint(), new BigDecimal(quantity), limitsVersion);
    }
}
