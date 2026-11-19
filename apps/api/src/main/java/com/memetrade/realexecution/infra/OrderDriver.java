package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.botwallet.api.SwapSigningRequest;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.realexecution.domain.QuoteMath;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.domain.RouterQuote;
import com.memetrade.realexecution.domain.RouterUnavailableException;
import com.memetrade.realexecution.domain.SwapRouter;
import com.memetrade.realexecution.domain.solana.BuiltMessage;
import com.memetrade.realexecution.domain.solana.ComputeBudget;
import com.memetrade.realexecution.domain.solana.MessageBuilder;
import com.memetrade.realexecution.domain.solana.RouteTooLargeException;
import com.memetrade.realexecution.domain.solana.SwapInstruction;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import com.memetrade.shared.solana.Base58Codec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F3, F4 (quote of the SIGNED mode) and the send of F5 (BR3.1 to BR3.3, BR4.1, BR5.1; reliability-design.md): takes the
 * lease of the orders PROPOSED, QUOTED in AUTO_FULL and SIGNED ({@code SKIP LOCKED}) and drives each one as far as it
 * goes, renewing the lease at every recorded step and never calling the network inside a database transaction.
 *
 * <ol>
 *   <li>PROPOSED: quote, swap instructions and blockhash; the server builds the v0 message with static keys and records
 *       it with its digest, the quote, the minimum and the fee BEFORE any signature is asked (QUOTED). In SIGNED the
 *       signing session opens here with its deadline of 8 s;
 *   <li>QUOTED in AUTO_FULL: a quote older than 10 s is taken again once, only while the TxSigner was never asked;
 *       then the recheck of the wallet, the moment of the first ask is recorded, and the TxSigner signs with
 *       signingRequestId = realOrderId (the same answer on every repetition); the signed transaction and its
 *       signature are recorded (SIGNED);
 *   <li>SIGNED: the recheck again; SENT and the attempt are recorded together BEFORE the call to the network, then the
 *       recorded transaction is sent and the outcome of the attempt written.
 * </ol>
 *
 * Every write is a compare-and-set with the lease: a cancel from outside wins and the driver stops (a signature it
 * already has is discarded unsent). A crash at any point resumes from the database alone once the lease expires.
 */
@Component
public class OrderDriver {

    private static final Logger log = LoggerFactory.getLogger(OrderDriver.class);
    static final String WRAPPED_SOL = "So11111111111111111111111111111111111111112";
    private static final int MAX_STEPS = 6;

    private final JdbcRealExecutionStore store;
    private final OrderTransitions transitions;
    private final RealExecutionWallet wallets;
    private final RealChain chain;
    private final SwapRouter router;
    private final UpstreamTracker upstream;
    private final RealExecutionMetrics metrics;
    private final CrashPoints crashes;
    private final RealExecutionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String owner = "driver-" + UUID.randomUUID();

    public OrderDriver(
            JdbcRealExecutionStore store,
            OrderTransitions transitions,
            RealExecutionWallet wallets,
            RealChain chain,
            SwapRouter router,
            UpstreamTracker upstream,
            RealExecutionMetrics metrics,
            CrashPoints crashes,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.transitions = transitions;
        this.wallets = wallets;
        this.chain = chain;
        this.router = router;
        this.upstream = upstream;
        this.metrics = metrics;
        this.crashes = crashes;
        this.properties = settings.properties();
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** One round: returns how many orders were driven. */
    public int runOnce() {
        List<UUID> claimed = store.claim(
                EnumSet.of(RealOrderState.PROPOSED, RealOrderState.QUOTED, RealOrderState.SIGNED),
                "(state <> 'QUOTED' or signing_mode = 'AUTO_FULL')",
                owner,
                properties.lease(),
                properties.orderBatchSize());
        boolean failed = false;
        for (UUID orderId : claimed) {
            try {
                drive(orderId);
                // The order waits for another job (confirmation, signing session) or ended: the lease goes back.
                store.releaseLease(orderId, owner);
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL order step stopped: order={}, error={}",
                        orderId,
                        failure.getClass().getSimpleName());
            }
        }
        if (!failed) {
            metrics.jobRun(RealExecutionMetrics.Job.DRIVER);
        }
        return claimed.size();
    }

    private void drive(UUID orderId) {
        for (int step = 0; step < MAX_STEPS; step++) {
            Optional<OrderRow> found = store.find(orderId);
            if (found.isEmpty()) {
                return;
            }
            OrderRow order = found.get();
            boolean progressed =
                    switch (order.state()) {
                        case PROPOSED -> quote(order, false);
                        case QUOTED -> !order.signedMode() && sign(order);
                        case SIGNED -> send(order);
                        default -> false;
                    };
            if (!progressed) {
                return;
            }
        }
    }

    // ----- PROPOSED -> QUOTED (and the one re-quote of AUTO_FULL) -----

    /** @return whether the order moved (to QUOTED, or failed) and the driver may go on */
    boolean quote(OrderRow order, boolean requote) {
        Optional<RealWalletInfo> wallet = wallets.wallet(order.tenantId(), order.userId());
        if (wallet.isEmpty()) {
            return transitions.move(
                    order,
                    terminal(order, true),
                    RealFailureCode.REAL_DISABLED.name(),
                    lease(),
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        }
        Instant quoteAt = clock.instant();
        Quoted quoted;
        try {
            quoted = quoteAndBuild(order, wallet.get(), properties.jupiter().maxAccounts());
            if (quoted == null) {
                quoted = quoteAndBuild(order, wallet.get(), properties.jupiter().retryMaxAccounts());
            }
        } catch (RouterUnavailableException down) {
            return upstreamFailed(order, RealExecutionMetrics.Provider.JUPITER, lease());
        } catch (com.memetrade.realexecution.domain.NoRouteException refused) {
            // A refusal of this route, not an outage: neither the circuit nor the UpstreamTracker count it (Major 1).
            log.warn("REAL route refused by the Jupiter: order={}", order.realOrderId());
            return transitions.move(
                    order,
                    RealOrderState.FAILED,
                    RealFailureCode.NO_ROUTE.name(),
                    lease(),
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        } catch (RealUpstreamUnavailable down) {
            return upstreamFailed(order, RealExecutionMetrics.Provider.RPC, lease());
        }
        if (quoted == null) {
            return transitions.move(
                    order,
                    RealOrderState.FAILED,
                    RealFailureCode.ROUTE_TOO_LARGE.name(),
                    lease(),
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        }
        upstream.succeeded(order.tenantId(), order.userId(), RealExecutionMetrics.Provider.JUPITER);
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("quote_at", OrderTransitions.at(quoteAt));
        columns.put("expected_out", quoted.quote().outAmount());
        columns.put("min_out", QuoteMath.minOut(quoted.quote().outAmount(), order.maxSlippageBps()));
        columns.put("message_digest", quoted.message().digestHex());
        columns.put("unsigned_message", quoted.message().unsignedTransactionBase64());
        columns.put("last_valid_block_height", quoted.lastValidBlockHeight());
        columns.put("fee_lamports", quoted.feeLamports());
        columns.put("fee_payer", order.signedMode() ? "MAIN_WALLET" : "BOT_WALLET");
        if (order.signedMode()) {
            columns.put(
                    "signing_deadline",
                    OrderTransitions.at(QuoteMath.signingDeadline(quoteAt, properties.signingWindow())));
        }
        if (requote) {
            columns.put("requoted", true);
            metrics.requoted(order.side(), order.operationMode());
        }
        boolean moved = transactions.execute(status -> transitions.move(
                order, RealOrderState.QUOTED, null, lease(), columns, Optional.empty(), properties.lease()));
        crashes.reached("quoted");
        return moved;
    }

    private record Quoted(RouterQuote quote, BuiltMessage message, long lastValidBlockHeight, long feeLamports) {}

    /** The quote, the instructions, the blockhash and the message; null when the route does not fit one packet. */
    private Quoted quoteAndBuild(OrderRow order, RealWalletInfo wallet, int maxAccounts) {
        boolean buy = order.side() == RealSide.BUY;
        RouterQuote quote = router.quote(
                buy ? WRAPPED_SOL : order.mint(),
                buy ? order.mint() : WRAPPED_SOL,
                order.input(),
                order.maxSlippageBps(),
                maxAccounts);
        List<SwapInstruction> instructions = router.swapInstructions(quote, wallet.botWalletAddress());
        RealChain.Blockhash blockhash = chain.latestBlockhash();
        byte[] payer =
                Base58Codec.decode(order.signedMode() ? wallet.mainWalletAddress() : wallet.botWalletAddress(), 32);
        try {
            BuiltMessage message =
                    MessageBuilder.build(payer, instructions, Base58Codec.decode(blockhash.blockhash(), 32));
            long fee = ComputeBudget.feeLamports(instructions, message.signerCount());
            return new Quoted(quote, message, blockhash.lastValidBlockHeight(), fee);
        } catch (RouteTooLargeException tooLarge) {
            return null;
        }
    }

    // ----- QUOTED -> SIGNED (AUTO_FULL) -----

    private boolean sign(OrderRow order) {
        Instant now = clock.instant();
        RealOrderStateMachine.SigningStep step =
                RealOrderStateMachine.beforeSigning(order.quoteAt(), now, order.requoted(), properties.quoteMaxAge());
        if (step == RealOrderStateMachine.SigningStep.REQUOTE) {
            if (RealOrderStateMachine.mayRebuildMessage(
                    order.state(), Optional.ofNullable(order.signingRequestedAt()))) {
                return quote(order, true);
            }
            step = RealOrderStateMachine.SigningStep.FAIL_QUOTE_EXPIRED;
        }
        if (step == RealOrderStateMachine.SigningStep.FAIL_QUOTE_EXPIRED) {
            return transitions.move(
                    order,
                    RealOrderState.FAILED,
                    RealFailureCode.QUOTE_EXPIRED.name(),
                    lease(),
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        }
        Optional<RealFailureCode> recheck = recheck(order);
        if (recheck.isPresent()) {
            return transitions.move(
                    order,
                    RealOrderState.CANCELLED,
                    recheck.get().name(),
                    lease(),
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        }
        if (order.signingRequestedAt() == null
                && !transitions.record(
                        order, lease(), Map.of("signing_requested_at", OrderTransitions.at(now)), properties.lease())) {
            return false;
        }
        return askSignature(order, order.unsignedMessage(), SwapSigningRequest.Mode.AUTO_FULL, lease());
    }

    /**
     * Asks the TxSigner with signingRequestId = realOrderId and the RECORDED message, then records SIGNED. In AUTO_FULL
     * a TxSigner without answer fails the order (BR9.1).
     *
     * @param holder the lease owner of the caller; its writes apply only while it still holds the lease
     */
    boolean askSignature(
            OrderRow order, String transactionBase64, SwapSigningRequest.Mode mode, Optional<String> holder) {
        try {
            return askSignatureOrThrow(order, transactionBase64, mode, holder);
        } catch (RealUpstreamUnavailable down) {
            return upstreamFailed(order, RealExecutionMetrics.Provider.TXSIGNER, holder);
        }
    }

    /**
     * The same ask for a claimed signing session of the SIGNED mode (BR4.2): a TxSigner without answer leaves the order
     * QUOTED and claimed (the exception goes up), and the deadline job repeats the same request 15 s later.
     *
     * @param holder the lease owner of the caller (driver, deadline job): SIGNED and the failures are written only
     *     while it holds the lease (reliability-design.md, review Minor 3); empty for the session of the person, which
     *     holds the claim of the session instead of a lease
     * @throws RealUpstreamUnavailable the TxSigner did not answer
     */
    boolean askSignatureOrThrow(
            OrderRow order, String transactionBase64, SwapSigningRequest.Mode mode, Optional<String> holder) {
        RealExecutionWallet.SwapSigning answer;
        try {
            answer = wallets.signSwap(order.tenantId(), order.userId(), signingRequest(order, transactionBase64, mode));
        } catch (RealUpstreamUnavailable down) {
            metrics.upstreamFailure(RealExecutionMetrics.Provider.TXSIGNER);
            throw down;
        }
        crashes.reached("signature-received");
        if (answer instanceof RealExecutionWallet.SwapSigning.Refused refused) {
            // Loop-back 1, Step 20 (U1 review-03, R-16): every refusal, WALLET_RATE_LIMITED included, fails the order
            // once and for all (the TxSigner recorded and signed nothing, so there is never a second transaction); the
            // code of the TxSigner is recorded in the audit event of the failure. RealOrderRateIT shows that normal
            // operation never reaches the limit of 60 per minute per wallet, so the refusal is not retried.
            String reason = refusalCode(refused.code());
            log.warn("REAL swap refused by the TxSigner: order={}, reason={}", order.realOrderId(), reason);
            return transitions.move(
                    order,
                    RealOrderState.FAILED,
                    RealFailureCode.SIGNING_REFUSED.name(),
                    holder,
                    Map.of(),
                    Optional.empty(),
                    properties.lease(),
                    Map.of("signerRefusal", reason));
        }
        upstream.succeeded(order.tenantId(), order.userId(), RealExecutionMetrics.Provider.TXSIGNER);
        RealExecutionWallet.SwapSigning.Signed signed = (RealExecutionWallet.SwapSigning.Signed) answer;
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("tx_signature", signed.txSignature());
        columns.put("signed_transaction", signed.signedTransactionBase64());
        boolean moved;
        try {
            moved = Boolean.TRUE.equals(transactions.execute(status -> transitions.move(
                    order, RealOrderState.SIGNED, null, holder, columns, Optional.empty(), properties.lease())));
        } catch (org.springframework.dao.DuplicateKeyException sameTransaction) {
            // The same quote and the same blockhash as an order already signed give byte for byte the same transaction
            // (Ed25519 is deterministic): sending it would be taken for the other one. It is discarded, never sent.
            log.warn("REAL signed transaction equals another order's; discarded: order={}", order.realOrderId());
            return transitions.move(
                    order,
                    RealOrderState.FAILED,
                    RealFailureCode.BLOCKHASH_EXPIRED.name(),
                    holder,
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        }
        if (moved) {
            crashes.reached("signed");
        }
        return moved;
    }

    /** The refusal code of the TxSigner as recorded: an upper-case code of at most 64 characters, otherwise UNKNOWN. */
    static String refusalCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "UNKNOWN";
    }

    static SwapSigningRequest signingRequest(OrderRow order, String transactionBase64, SwapSigningRequest.Mode mode) {
        return new SwapSigningRequest(
                order.realOrderId(),
                transactionBase64,
                mode,
                order.side().name(),
                order.inputLamports(),
                order.inputBaseUnits(),
                order.expectedOut(),
                order.minOut(),
                order.maxSlippageBps(),
                order.feeLamports(),
                order.mint());
    }

    // ----- SIGNED -> SENT -----

    private boolean send(OrderRow order) {
        Optional<RealFailureCode> recheck = recheck(order);
        if (recheck.isPresent()) {
            return transitions.move(
                    order,
                    RealOrderState.CANCELLED,
                    recheck.get().name(),
                    lease(),
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
        }
        Instant now = clock.instant();
        UUID attempt = transactions.execute(status -> {
            if (!transitions.move(
                    order,
                    RealOrderState.SENT,
                    null,
                    lease(),
                    Map.of("sent_at", OrderTransitions.at(now)),
                    Optional.empty(),
                    properties.lease())) {
                return null;
            }
            return store.insertAttempt(order.realOrderId(), order.tenantId(), now);
        });
        if (attempt == null) {
            return false;
        }
        crashes.reached("sent-recorded");
        sendRecorded(order, attempt);
        if (order.decisionRequestId() != null) {
            store.decisionCreatedAt(order.decisionRequestId())
                    .ifPresent(
                            created -> metrics.decisionToSend(order.operationMode(), Duration.between(created, now)));
        }
        return false;
    }

    /** Sends the RECORDED transaction and writes the outcome of the attempt; never builds nor signs anything. */
    void sendRecorded(OrderRow order, UUID attempt) {
        try {
            chain.send(order.signedTransaction());
            crashes.reached("send-accepted");
            store.resolveAttempt(attempt, "ACCEPTED", clock.instant());
        } catch (RealUpstreamUnavailable down) {
            metrics.upstreamFailure(RealExecutionMetrics.Provider.RPC);
            store.resolveAttempt(attempt, "RPC_ERROR", clock.instant());
        }
    }

    // ----- helpers -----

    /** BR3.3: the code of the cancellation when the wallet no longer allows the order. */
    Optional<RealFailureCode> recheck(OrderRow order) {
        RealExecutionWallet.Recheck recheck = wallets.recheckBeforeSend(
                order.tenantId(),
                order.userId(),
                RealOperationMode.valueOf(order.operationMode()),
                order.liquidation());
        if (recheck instanceof RealExecutionWallet.Recheck.Cancelled cancelled) {
            return Optional.of(RealFailureCode.valueOf(cancelled.reason().name()));
        }
        return Optional.empty();
    }

    /** BR9.1: a technical failure before the signature fails the order (the reservation is released) and is counted. */
    private boolean upstreamFailed(OrderRow order, RealExecutionMetrics.Provider provider, Optional<String> holder) {
        if (provider != RealExecutionMetrics.Provider.TXSIGNER) {
            metrics.upstreamFailure(provider);
        }
        boolean moved = Boolean.TRUE.equals(transactions.execute(status -> {
            boolean won = transitions.move(
                    order,
                    RealOrderState.FAILED,
                    RealFailureCode.UPSTREAM_UNAVAILABLE.name(),
                    holder,
                    Map.of(),
                    Optional.empty(),
                    properties.lease());
            if (won) {
                upstream.failed(order.tenantId(), order.userId(), provider);
            }
            return won;
        }));
        return moved;
    }

    private static RealOrderState terminal(OrderRow order, boolean cancel) {
        return cancel ? RealOrderState.CANCELLED : RealOrderState.FAILED;
    }

    private Optional<String> lease() {
        return Optional.of(owner);
    }
}
