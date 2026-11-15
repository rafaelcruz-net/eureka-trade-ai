package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.botwallet.api.SwapSigningRequest;
import com.memetrade.identity.api.Principal;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.domain.solana.Ed25519Check;
import com.memetrade.realexecution.domain.solana.WireTransaction;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.solana.Base58Codec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F4, BR4.2, U4-NFR6.2 (Q4 of the NFR Design): the signing session of the SIGNED mode, in the api. Tenant and person
 * from the token; an order of another person is not found. The browser only reads the message the server built and
 * sends it back signed: the server checks the deadline, then the message BYTE BY BYTE and the Ed25519 of the main wallet
 * over it, before anything else; then it claims the order with a compare-and-set on QUOTED, unclaimed and within the
 * deadline (against the deadline job, S25), rechecks the wallet and asks the TxSigner for the second signature.
 */
@Service
public class SigningSessionService {

    private final JdbcRealExecutionStore store;
    private final OrderTransitions transitions;
    private final OrderDriver driver;
    private final RealExecutionWallet wallets;
    private final CrashPoints crashes;
    private final RealExecutionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SigningSessionService(
            JdbcRealExecutionStore store,
            OrderTransitions transitions,
            OrderDriver driver,
            RealExecutionWallet wallets,
            CrashPoints crashes,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.transitions = transitions;
        this.driver = driver;
        this.wallets = wallets;
        this.crashes = crashes;
        this.properties = settings.properties();
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** AC3.2.1: what the signing screen shows. Amounts in base units (lamports for SOL). */
    public record Display(
            String side,
            String inputMint,
            long inputBaseUnits,
            String outputMint,
            long expectedOutBaseUnits,
            long minOutBaseUnits,
            long feeLamports,
            String reason) {}

    /** Contract R3 {@code SigningSession}: the message built by the server, its digest and the deadline. */
    public record Session(String transactionBase64, String messageDigest, Instant deadline, Display display) {}

    public Session session(Principal person, UUID realOrderId) {
        OrderRow order = signedModeOrder(person, realOrderId);
        if (order.state() == RealOrderState.CANCELLED
                && RealFailureCode.SIGNING_DEADLINE_EXPIRED.name().equals(order.failureCode())) {
            throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
        }
        if (order.state() != RealOrderState.QUOTED) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No open signing session.");
        }
        if (!clock.instant().isBefore(order.signingDeadline())) {
            throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
        }
        boolean buy = order.side() == com.memetrade.realexecution.api.RealSide.BUY;
        return new Session(
                order.unsignedMessage(),
                order.messageDigest(),
                order.signingDeadline(),
                new Display(
                        order.side().name(),
                        buy ? OrderDriver.WRAPPED_SOL : order.mint(),
                        order.input(),
                        buy ? order.mint() : OrderDriver.WRAPPED_SOL,
                        order.expectedOut(),
                        order.minOut(),
                        order.feeLamports(),
                        order.reason()));
    }

    /**
     * Receives the transaction signed by the main wallet (contract R3 POST). Returns the order after the second signature
     * (SIGNED), or as it already is when the same signed transaction comes again.
     */
    public RealOrderView submit(Principal person, UUID realOrderId, String signedTransactionBase64) {
        OrderRow order = signedModeOrder(person, realOrderId);
        if (order.signingClaimedAt() != null || order.state() != RealOrderState.QUOTED) {
            return alreadyHandled(order, signedTransactionBase64);
        }
        Instant now = clock.instant();
        if (!now.isBefore(order.signingDeadline())) {
            throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
        }
        checkSignedByMainWallet(order, signedTransactionBase64);
        boolean claimed = Boolean.TRUE.equals(
                transactions.execute(status -> store.claimSession(realOrderId, now, signedTransactionBase64)));
        if (!claimed) {
            OrderRow current = store.find(realOrderId).orElseThrow();
            if (current.signingClaimedAt() != null && current.state() != RealOrderState.CANCELLED) {
                return RealExecutionService.view(current);
            }
            throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
        }
        crashes.reached("claimed");
        OrderRow claimedOrder = store.find(realOrderId).orElseThrow();
        Optional<RealFailureCode> recheck = driver.recheck(claimedOrder);
        if (recheck.isPresent()) {
            transactions.executeWithoutResult(status -> transitions.move(
                    claimedOrder,
                    RealOrderState.CANCELLED,
                    recheck.get().name(),
                    Optional.empty(),
                    Map.of(),
                    Optional.of(person.userId()),
                    properties.lease()));
            return view(realOrderId);
        }
        try {
            // The session of the person holds the claim of the session, not a lease (an external writer).
            driver.askSignatureOrThrow(
                    claimedOrder, signedTransactionBase64, SwapSigningRequest.Mode.SIGNED, java.util.Optional.empty());
        } catch (RealUpstreamUnavailable down) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The signing service did not answer.");
        }
        return view(realOrderId);
    }

    /** AC3.2.4: the person refused in the wallet; an unclaimed session is cancelled USER_REJECTED. */
    public RealOrderView reject(Principal person, UUID realOrderId) {
        OrderRow order = signedModeOrder(person, realOrderId);
        if (order.state() == RealOrderState.CANCELLED) {
            return RealExecutionService.view(order);
        }
        if (order.state() != RealOrderState.QUOTED || order.signingClaimedAt() != null) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "The order is no longer waiting for a signature.");
        }
        boolean moved = Boolean.TRUE.equals(transactions.execute(status -> transitions.move(
                order,
                RealOrderState.CANCELLED,
                RealFailureCode.USER_REJECTED.name(),
                Optional.empty(),
                Map.of(),
                Optional.of(person.userId()),
                properties.lease())));
        if (!moved) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "The order is no longer waiting for a signature.");
        }
        return view(realOrderId);
    }

    /**
     * U4-NFR6.2: the transaction must carry EXACTLY the recorded message, two signature slots and, in the first, a
     * valid Ed25519 signature of the registered main wallet over that message.
     */
    private void checkSignedByMainWallet(OrderRow order, String signedTransactionBase64) {
        WireTransaction recorded = WireTransaction.decodeBase64(order.unsignedMessage());
        WireTransaction received;
        try {
            received = WireTransaction.decodeBase64(signedTransactionBase64);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.SIGNED_MESSAGE_MISMATCH, "The signed transaction is not the one built.");
        }
        List<byte[]> signatures = received.signatures();
        if (!Arrays.equals(received.messageBytes(), recorded.messageBytes()) || signatures.size() != 2) {
            throw new ApiException(ErrorCode.SIGNED_MESSAGE_MISMATCH, "The signed transaction is not the one built.");
        }
        RealWalletInfo wallet = wallets.wallet(order.tenantId(), order.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.BOT_WALLET_NOT_ACTIVE, "No active bot wallet."));
        byte[] main = Base58Codec.decode(wallet.mainWalletAddress(), 32);
        if (!Ed25519Check.verify(main, received.messageBytes(), signatures.get(0))) {
            throw new ApiException(ErrorCode.SIGNED_MESSAGE_MISMATCH, "The signed transaction is not the one built.");
        }
    }

    /** A repeated POST of the same transaction answers the order; anything else is a closed session. */
    private RealOrderView alreadyHandled(OrderRow order, String signedTransactionBase64) {
        if (order.signingClaimedAt() != null && order.state() != RealOrderState.CANCELLED) {
            return RealExecutionService.view(order);
        }
        throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
    }

    private OrderRow signedModeOrder(Principal person, UUID realOrderId) {
        return store.findForOwner(person.tenantId(), person.userId(), realOrderId)
                .filter(OrderRow::signedMode)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
    }

    private RealOrderView view(UUID realOrderId) {
        return RealExecutionService.view(store.find(realOrderId).orElseThrow());
    }
}
