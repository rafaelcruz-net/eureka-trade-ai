package com.memetrade.ledger.loadtest;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.api.PaperExecution.FundResult;
import com.memetrade.ledger.api.PaperExecution.PaperOrderRequest;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: prepares the ledger of the load scenarios {@code u7} and {@code u7-jobs}
 * (cicd-pipeline.md) with the real {@link PaperExecution}, one cycle transaction at a time, as U5 will call it:
 * funds every bot once ({@code FUNDING}), opens one base position per token of the bot and then records buy and sell
 * pairs until each person has the requested number of fills. Only synthetic PAPER values; nothing is signed or sent.
 *
 * <p>Not named "Seeder": that suffix is reserved by the U3 rule to the seeders of the {@code test} profile.
 */
@Component
@Profile("loadtest")
public class LoadtestLedgerSeed {

    /** Initial virtual balance per bot, USDC scale 6: enough for every fee of the seed and of the cycles. */
    static final BigDecimal FUNDING = new BigDecimal("100000.000000");

    private static final int PAIRS_PER_TRANSACTION = 25;
    private static final int MAX_EMPTY_BATCHES = 30;
    private static final Duration EMPTY_BATCH_PAUSE = Duration.ofSeconds(5);

    private final PaperExecution execution;
    private final TransactionTemplate transactions;
    private final LoadtestCycleAuthContext auth;
    private final LoadtestTokens tokens;
    private final AtomicLong fillsRecorded = new AtomicLong();

    public LoadtestLedgerSeed(
            PaperExecution execution,
            TransactionTemplate transactions,
            LoadtestCycleAuthContext auth,
            LoadtestTokens tokens) {
        this.execution = execution;
        this.transactions = transactions;
        this.auth = auth;
        this.tokens = tokens;
    }

    /** A bot of the load: its owner (a session principal), its tokens and the quantity its next cycle sells. */
    public static final class Bot {
        private final Principal owner;
        private final UUID botId;
        private final List<TokenId> tokens;
        private volatile BigDecimal toSell;
        private volatile TokenId toSellToken;
        private final AtomicLong cycles = new AtomicLong();

        Bot(Principal owner, UUID botId, List<TokenId> tokens) {
            this.owner = owner;
            this.botId = botId;
            this.tokens = List.copyOf(tokens);
        }

        public Principal owner() {
            return owner;
        }

        public UUID botId() {
            return botId;
        }

        public List<TokenId> tokens() {
            return tokens;
        }
    }

    /** Fills recorded by the seed and the cycles so far. */
    public long fillsRecorded() {
        return fillsRecorded.get();
    }

    /** The bots of the load, assigned round-robin to the people: bot {@code i} belongs to person {@code i % n}. */
    public List<Bot> bots(List<Principal> people, int botCount, List<TokenId> botTokens) {
        List<Bot> bots = new ArrayList<>();
        for (int i = 0; i < botCount; i++) {
            bots.add(new Bot(people.get(i % people.size()), UUID.randomUUID(), botTokens));
        }
        return bots;
    }

    /** F1 for every bot, then one base buy per token, so every bot holds as many open positions as tokens. */
    public void fundAndOpen(Bot bot) {
        FundResult funded = inCycle(bot.owner, () -> execution.fund(bot.botId, FUNDING, key()));
        if (!(funded instanceof FundResult.Funded)) {
            throw new IllegalStateException("The load bot could not be funded: " + funded);
        }
        for (TokenId token : bot.tokens) {
            ExecutionResult opened = inCycle(bot.owner, () -> order(bot, OrderSide.BUY, token));
            if (!(opened instanceof ExecutionResult.Filled)) {
                throw new IllegalStateException("The base position of a load bot was not filled: " + opened);
            }
            fillsRecorded.incrementAndGet();
        }
    }

    /**
     * Buy and sell pairs on the bots of {@code person} until the person has {@code target} fills, counting the base
     * buys already made; {@link #PAIRS_PER_TRANSACTION} pairs per cycle transaction.
     */
    public void seedFills(List<Bot> personBots, long target) throws InterruptedException {
        long fills = (long) personBots.size() * personBots.get(0).tokens.size();
        int empty = 0;
        int step = 0;
        while (fills < target) {
            int turn = step++;
            long batch = inCycle(personBots.get(0).owner, () -> pairs(personBots, turn));
            fills += batch;
            fillsRecorded.addAndGet(batch);
            empty = batch == 0 ? empty + 1 : 0;
            if (empty >= MAX_EMPTY_BATCHES) {
                throw new IllegalStateException("The ledger seed made no progress: every order was NOT_FILLED");
            }
            if (batch == 0) {
                Thread.sleep(EMPTY_BATCH_PAUSE.toMillis());
            }
        }
    }

    /** One cycle of the driver: buys when the bot holds no extra quantity, otherwise sells what the last cycle bought. */
    public ExecutionResult cycle(Bot bot) {
        long n = bot.cycles.getAndIncrement();
        ExecutionResult result;
        if (bot.toSell == null) {
            TokenId token = bot.tokens.get((int) (n % bot.tokens.size()));
            result = inCycle(bot.owner, () -> order(bot, OrderSide.BUY, token));
            if (result instanceof ExecutionResult.Filled filled) {
                bot.toSellToken = token;
                bot.toSell = filled.filledQuantity();
            }
        } else {
            BigDecimal quantity = bot.toSell;
            TokenId token = bot.toSellToken;
            result = inCycle(bot.owner, () -> execution.execute(request(bot, OrderSide.SELL, token, quantity), key()));
            if (result instanceof ExecutionResult.Filled) {
                bot.toSell = null;
            }
        }
        if (result instanceof ExecutionResult.Filled) {
            fillsRecorded.incrementAndGet();
        }
        return result;
    }

    /**
     * F3 for {@code bot} in one cycle transaction of its owner, as U5 will close a deactivated bot: every open position
     * at the reference price, or pending without a reliable price (review repair R-04, NFR3.1).
     */
    public CloseResult close(Bot bot) {
        return inCycle(bot.owner, () -> execution.closePositions(bot.botId, 1, key()));
    }

    private long pairs(List<Bot> personBots, int turn) {
        long fills = 0;
        for (int i = 0; i < PAIRS_PER_TRANSACTION; i++) {
            Bot bot = personBots.get((turn + i) % personBots.size());
            TokenId token = bot.tokens.get((turn * PAIRS_PER_TRANSACTION + i) % bot.tokens.size());
            if (order(bot, OrderSide.BUY, token) instanceof ExecutionResult.Filled bought) {
                fills++;
                ExecutionResult sold =
                        execution.execute(request(bot, OrderSide.SELL, token, bought.filledQuantity()), key());
                fills += sold instanceof ExecutionResult.Filled ? 1 : 0;
            }
        }
        return fills;
    }

    /** An order worth {@link LoadtestTokens#NOTIONAL} now; a token without a fresh price is reported as not filled. */
    private ExecutionResult order(Bot bot, OrderSide side, TokenId token) {
        Optional<BigDecimal> quantity = tokens.quantity(token);
        if (quantity.isEmpty()) {
            return new ExecutionResult.NotFilled(Optional.empty(), NotFilledReason.PRICE_UNAVAILABLE);
        }
        return execution.execute(request(bot, side, token, quantity.get()), key());
    }

    private static PaperOrderRequest request(Bot bot, OrderSide side, TokenId token, BigDecimal quantity) {
        return new PaperOrderRequest(bot.botId, UUID.randomUUID(), side, token.chain(), token.mint(), quantity, 1);
    }

    /** One cycle transaction of {@code owner}, opened as U5 will open it. */
    private <T> T inCycle(Principal owner, Supplier<T> work) {
        return auth.as(owner, () -> transactions.execute(status -> work.get()));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
