package com.memetrade.realexits.application;

import com.memetrade.botwallet.api.ExitsAvailability;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.RealIdentity;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalKind;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.CurrentPrice;
import com.memetrade.realexits.domain.TriggerPort;
import com.memetrade.realexits.domain.TriggerRefusal;
import com.memetrade.realexits.domain.TriggerValidation;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.realexits.infra.JdbcConditionalStore.Changes;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F1 and F3 of the U7 (BR1, BR3, BR4.1, BR4.4, BR6, BR9.3; routes R5 of the addendum). Tenant and person only from the
 * token; a conditional of another person is not found. The checks of the creation run in the order of the rules: the
 * exits flag, the REAL switch, the barrier of the U3 read through the M1 and the M5 (release in force, pauses and kill
 * switch, mode AUTO_EXITS, blocked token), the position of the REAL ledger, the current price in USD and BR3 with the
 * bounds of BR2.6 - all in memory, before the Jupiter (U7-NFR1.4, U7-NFR3.4). The conditional does not consume the
 * buy limit nor the cap (BR4.2). The browser only signs: the consent message (simulated path) or the transaction
 * built by the server; it never builds nor sends anything.
 */
@Service
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class ConditionalService {

    /** The time the person has to sign the conditional in the wallet. */
    static final Duration SIGNING_WINDOW = Duration.ofSeconds(60);

    private static final String CHAIN = "solana";

    private final JdbcConditionalStore store;
    private final TriggerPort trigger;
    private final ExitsAvailability exits;
    private final RealExecutionWallet wallets;
    private final RealStateQuery states;
    private final RealRiskGate gate;
    private final RealLedger ledger;
    private final MarketDataQuery market;
    private final RealIdentity identity;
    private final ExitEvents events;
    private final Environment environment;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ConditionalService(
            JdbcConditionalStore store,
            TriggerPort trigger,
            ExitsAvailability exits,
            RealExecutionWallet wallets,
            RealStateQuery states,
            RealRiskGate gate,
            RealLedger ledger,
            MarketDataQuery market,
            RealIdentity identity,
            ExitEvents events,
            Environment environment,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.trigger = trigger;
        this.exits = exits;
        this.wallets = wallets;
        this.states = states;
        this.gate = gate;
        this.ledger = ledger;
        this.market = market;
        this.identity = identity;
        this.events = events;
        this.environment = environment;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** What the person asks: the position, the kind and its prices in exact decimals. */
    public record CreateCommand(
            String positionMint,
            ConditionalKind kind,
            Optional<BigDecimal> stopPriceUsd,
            Optional<BigDecimal> takeProfitPriceUsd) {}

    /** The signing session of the creation: the consent message or the transaction built by the server. */
    public record SigningSession(
            TriggerPort.Authorization kind,
            Optional<String> message,
            Optional<String> transactionBase64,
            Instant deadline,
            Instant serverTime) {}

    public record Created(ConditionalOrder conditional, SigningSession session) {}

    /** What the browser sends back: the signature of the consent, or the transaction signed by the main wallet. */
    public record SignatureCommand(Optional<String> signature, Optional<String> signedTransactionBase64) {}

    public Created create(Principal person, CreateCommand command, String idempotencyKey) {
        RealWalletInfo wallet = barrier(person, command.positionMint());
        long position = position(person, wallet, command.positionMint());
        CurrentPrice price = currentPrice(command.positionMint());
        TriggerValidation.check(command.kind(), command.stopPriceUsd(), command.takeProfitPriceUsd(), price)
                .ifPresent(ConditionalService::refuse);
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Instant deadline = now.plus(SIGNING_WINDOW);
        TriggerPort.CreateRequest request = new TriggerPort.CreateRequest(
                id,
                wallet.botWalletAddress(),
                wallet.mainWalletAddress(),
                command.positionMint(),
                command.kind(),
                command.stopPriceUsd(),
                command.takeProfitPriceUsd(),
                position,
                wallet.maxSlippageBps());
        boolean byMessage = trigger.authorization() == TriggerPort.Authorization.MESSAGE;
        String material = byMessage ? ConsentMessage.of(network(), request, deadline) : prepare(request);
        try {
            transactions.executeWithoutResult(status -> {
                store.insert(new JdbcConditionalStore.NewConditional(
                        id,
                        person.tenantId(),
                        person.userId(),
                        wallet.botWalletId(),
                        command.positionMint(),
                        command.kind(),
                        command.stopPriceUsd(),
                        command.takeProfitPriceUsd(),
                        position,
                        wallet.maxSlippageBps(),
                        trigger.kind(),
                        idempotencyKey,
                        material,
                        deadline,
                        now));
                events.changed(
                        store.find(id).orElseThrow(),
                        ConditionalState.PENDING_SIGNATURE,
                        "CREATED",
                        Optional.of(person.userId()),
                        Optional.empty());
            });
        } catch (DuplicateKeyException taken) {
            Optional<ConditionalOrder> same = store.findByKey(person.tenantId(), person.userId(), idempotencyKey);
            if (same.isPresent()) {
                return new Created(same.get(), session(same.get()));
            }
            throw new ApiException(
                    ErrorCode.CONDITIONAL_ALREADY_OPEN, "The position already has a conditional that is not final.");
        }
        ConditionalOrder created = store.find(id).orElseThrow();
        return new Created(created, session(created));
    }

    /** The open signing session of a conditional of the person (the browser reads it again after a reload). */
    public SigningSession session(ConditionalOrder conditional) {
        boolean byMessage = trigger.authorization() == TriggerPort.Authorization.MESSAGE;
        return new SigningSession(
                trigger.authorization(),
                byMessage ? conditional.signingMessage() : Optional.empty(),
                byMessage ? Optional.empty() : conditional.signingMessage(),
                conditional.signingDeadline().orElse(conditional.createdAt()),
                clock.instant());
    }

    /** F1 step 5: the authorization of the main wallet; the conditional opens at the provider. */
    public ConditionalOrder submitSignature(Principal person, UUID conditionalOrderId, SignatureCommand command) {
        ConditionalOrder conditional = owned(person, conditionalOrderId);
        if (conditional.state() != ConditionalState.PENDING_SIGNATURE) {
            return alreadySigned(conditional);
        }
        Instant now = clock.instant();
        if (!now.isBefore(conditional.signingDeadline().orElse(now))) {
            transactions.executeWithoutResult(status -> {
                if (store.move(
                        conditionalOrderId,
                        ConditionalState.PENDING_SIGNATURE,
                        ConditionalState.EXPIRED,
                        Changes.none(),
                        now)) {
                    events.changed(
                            conditional,
                            ConditionalState.EXPIRED,
                            "SIGNING_DEADLINE_EXPIRED",
                            Optional.of(person.userId()),
                            Optional.empty());
                }
            });
            throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
        }
        RealWalletInfo wallet = wallets.wallet(person.tenantId(), person.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.BOT_WALLET_NOT_ACTIVE, "No active bot wallet."));
        String authorization = authorization(conditional, wallet, command);
        TriggerPort.CreateResult result;
        try {
            result = trigger.create(request(conditional, wallet), authorization);
        } catch (TriggerPort.TriggerUnavailable down) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The provider of the conditional did not answer.");
        }
        return switch (result) {
            case TriggerPort.CreateResult.Opened opened -> opened(person, conditional, opened);
            case TriggerPort.CreateResult.Rejected rejected -> rejected(person, conditional, rejected);
        };
    }

    /** F3, S48: the DELETE of the person. PENDING_SIGNATURE is CANCELLED at once; OPEN goes to CANCELLING. */
    public ConditionalOrder cancelByUser(Principal person, UUID conditionalOrderId) {
        ConditionalOrder conditional = owned(person, conditionalOrderId);
        if (conditional.state() == ConditionalState.CANCELLING) {
            return conditional;
        }
        Optional<ConditionalState> target =
                com.memetrade.realexits.domain.ConditionalStateMachine.cancellationTarget(conditional.state());
        if (target.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "The conditional is already final.");
        }
        Instant now = clock.instant();
        Changes changes = Changes.none().set("cancel_reason", CancelReason.USER);
        if (target.get() == ConditionalState.CANCELLING) {
            changes.set("cancelling_since", now);
        }
        boolean moved = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!store.move(conditionalOrderId, conditional.state(), target.get(), changes, now)) {
                return false;
            }
            events.cancelled(
                    conditional, target.get(), CancelReason.USER, Optional.of(person.userId()), Optional.empty());
            return true;
        }));
        ConditionalOrder current = store.find(conditionalOrderId).orElseThrow();
        if (!moved && current.state().isFinal()) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "The conditional is already final.");
        }
        return current;
    }

    /** The list of the person, newest first. */
    public List<ConditionalOrder> page(Principal person, Optional<JdbcConditionalStore.Cursor> after, int limit) {
        return store.page(person.tenantId(), person.userId(), after, limit);
    }

    public long count(Principal person) {
        return store.count(person.tenantId(), person.userId());
    }

    public ConditionalOrder get(Principal person, UUID conditionalOrderId) {
        return owned(person, conditionalOrderId);
    }

    /**
     * BR8.2: what the dialog "Definir stop e alvo" shows before the person types: the whole position of the person of
     * the token, the current price in USD with its source and instant (unknown, never zero) and the bounds of BR2.6.
     */
    public record Quote(
            String positionMint,
            long positionBaseUnits,
            Optional<BigDecimal> priceUsd,
            Optional<String> source,
            Optional<Instant> observedAt) {

        /** BR2.6: the lowest stop the API accepts. */
        public Optional<BigDecimal> minStopPriceUsd() {
            return priceUsd.map(price -> price.multiply(new BigDecimal("0.5")));
        }

        /** BR2.6: the highest take-profit the API accepts. */
        public Optional<BigDecimal> maxTakeProfitPriceUsd() {
            return priceUsd.map(price -> price.multiply(BigDecimal.TEN));
        }
    }

    public Quote quote(Principal person, String mint) {
        long position = wallets.wallet(person.tenantId(), person.userId())
                .map(wallet -> ledger.positions(person.tenantId(), wallet.botWalletId()).stream()
                        .filter(held -> held.mint().equals(mint))
                        .mapToLong(RealPosition::tokenBaseUnits)
                        .sum())
                .orElse(0L);
        MarketValue value = market.referencePrice(new TokenId(CHAIN, mint));
        if (value instanceof MarketValue.Known known
                && !known.stale()
                && known.value().signum() > 0) {
            return new Quote(
                    mint,
                    position,
                    Optional.of(known.value()),
                    Optional.of(known.source().name()),
                    Optional.of(known.observedAt()));
        }
        return new Quote(mint, position, Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ----- the checks of the creation -----

    private RealWalletInfo barrier(Principal person, String mint) {
        if (!exits.exitsEnabled()) {
            throw new ApiException(ErrorCode.EXITS_DISABLED, "The conditional exits are disabled in this environment.");
        }
        if (!environment.getProperty("memetrade.real.enabled", Boolean.class, false)) {
            throw new ApiException(ErrorCode.REAL_DISABLED, "REAL is disabled in this environment.");
        }
        RealWalletInfo wallet = wallets.wallet(person.tenantId(), person.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.BOT_WALLET_NOT_ACTIVE, "No active bot wallet."));
        RealStateView state = states.readRealState(person.tenantId(), person.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.BOT_WALLET_NOT_ACTIVE, "No active bot wallet."));
        if (!state.released()) {
            throw new ApiException(ErrorCode.REAL_NOT_RELEASED, "REAL is not released for this account.");
        }
        if (state.paused()) {
            throw new ApiException(ErrorCode.REAL_PAUSED, "REAL is paused: " + state.pausedReasons());
        }
        if (state.mode() != RealOperationMode.AUTO_EXITS) {
            throw new ApiException(
                    ErrorCode.EXITS_MODE_REQUIRED, "A conditional is created only in the mode AUTO_EXITS.");
        }
        // The block list of the M5 is read in a transaction of its own (the barrier runs in the one of its caller).
        boolean blocked = Boolean.TRUE.equals(transactions.execute(status -> gate.blockedTokens().stream()
                .anyMatch(token -> CHAIN.equals(token.chain()) && token.mint().equals(mint))));
        if (blocked) {
            throw new ApiException(ErrorCode.TOKEN_BLOCKED, "The token is blocked for the REAL.");
        }
        return wallet;
    }

    /** BR3.7, BR5.3b: the whole position of the REAL ledger (the source of truth), never the bot wallet balance. */
    private long position(Principal person, RealWalletInfo wallet, String mint) {
        long units = ledger.positions(person.tenantId(), wallet.botWalletId()).stream()
                .filter(held -> held.mint().equals(mint))
                .mapToLong(RealPosition::tokenBaseUnits)
                .sum();
        if (units <= 0) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_FIELDS,
                    "One or more fields are invalid.",
                    List.of(new FieldViolation("positionMint", "Sem posição deste token na carteira do bot.")),
                    null,
                    Map.of());
        }
        return units;
    }

    /** BR3.3: the current price in USD (M4); unknown or stale is unknown, never zero. */
    private CurrentPrice currentPrice(String mint) {
        MarketValue value = market.referencePrice(new TokenId(CHAIN, mint));
        if (value instanceof MarketValue.Known known && !known.stale()) {
            return CurrentPrice.known(known.value());
        }
        return CurrentPrice.unknown();
    }

    private static void refuse(TriggerRefusal refusal) {
        throw switch (refusal) {
            case VALIDATION_FAILED ->
                new ApiException(
                        ErrorCode.UNPROCESSABLE_FIELDS,
                        "One or more fields are invalid.",
                        List.of(new FieldViolation("kind", "Informe os preços do tipo escolhido, positivos.")),
                        null,
                        Map.of());
            case PRICE_UNKNOWN -> new ApiException(ErrorCode.PRICE_UNKNOWN, "The current price is unknown.");
            case TP_NOT_ABOVE_SL ->
                new ApiException(ErrorCode.TP_NOT_ABOVE_SL, "The take-profit must be above the stop.");
            case TRIGGER_ON_WRONG_SIDE ->
                new ApiException(
                        ErrorCode.TRIGGER_ON_WRONG_SIDE,
                        "The stop must be below the current price and the take-profit above it.");
            case TRIGGER_OUT_OF_BOUNDS ->
                new ApiException(
                        ErrorCode.TRIGGER_OUT_OF_BOUNDS,
                        "The stop is at least 50% of the current price and the take-profit at most 10 times it.");
        };
    }

    private String prepare(TriggerPort.CreateRequest request) {
        try {
            return trigger.prepareCreate(request);
        } catch (TriggerPort.TriggerUnavailable down) {
            throw new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "The provider of the conditional did not answer.");
        }
    }

    private String network() {
        return environment.getProperty("memetrade.real.network", "LOCALNET");
    }

    // ----- the signature -----

    /**
     * The simulated path: the main wallet signed exactly the consent message recorded with the conditional (native
     * Ed25519 of the identity, M9). The transaction path: the transaction signed by the main wallet goes to the adapter,
     * which compares it with the one it built (BR0: the real adapter is written after the recording).
     */
    private String authorization(ConditionalOrder conditional, RealWalletInfo wallet, SignatureCommand command) {
        if (trigger.authorization() == TriggerPort.Authorization.MESSAGE) {
            String signature = command.signature()
                    .orElseThrow(() -> ApiException.validation("signature", "Envie a assinatura do consentimento."));
            String message = conditional.signingMessage().orElse("");
            if (!identity.verifyConsentSignature(wallet.mainWalletAddress(), message, signature)) {
                throw new ApiException(ErrorCode.SIGNED_MESSAGE_MISMATCH, "The signed message is not the one built.");
            }
            return signature;
        }
        return command.signedTransactionBase64()
                .orElseThrow(() -> ApiException.validation("signedTransactionBase64", "Envie a transação assinada."));
    }

    private ConditionalOrder opened(
            Principal person, ConditionalOrder conditional, TriggerPort.CreateResult.Opened opened) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            Changes changes = Changes.none()
                    .set("jupiter_order_id", opened.providerOrderId())
                    .set("last_read_at", now);
            opened.createSignature().ifPresent(signature -> changes.set("create_signature", signature));
            opened.escrowAccount().ifPresent(account -> changes.set("escrow_account", account));
            if (store.move(
                    conditional.conditionalOrderId(),
                    ConditionalState.PENDING_SIGNATURE,
                    ConditionalState.OPEN,
                    changes,
                    now)) {
                events.changed(
                        conditional,
                        ConditionalState.OPEN,
                        "OPENED",
                        Optional.of(person.userId()),
                        opened.createSignature());
            }
        });
        return store.find(conditional.conditionalOrderId()).orElseThrow();
    }

    private ConditionalOrder rejected(
            Principal person, ConditionalOrder conditional, TriggerPort.CreateResult.Rejected rejected) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            if (store.move(
                    conditional.conditionalOrderId(),
                    ConditionalState.PENDING_SIGNATURE,
                    ConditionalState.FAILED,
                    Changes.none(),
                    now)) {
                events.changed(
                        conditional,
                        ConditionalState.FAILED,
                        rejected.signingPolicy() ? "SIGNING_POLICY_REFUSED" : "TRIGGER_REJECTED",
                        Optional.of(person.userId()),
                        Optional.empty());
            }
        });
        if (rejected.signingPolicy()) {
            throw new ApiException(ErrorCode.SIGNING_POLICY_REFUSED, "The signing policy refused the conditional.");
        }
        throw new ApiException(
                ErrorCode.TRIGGER_REJECTED, "The provider refused the conditional: " + rejected.reason());
    }

    /** A signature after the session: the conditional as it is, or the refusal of an expired session. */
    private static ConditionalOrder alreadySigned(ConditionalOrder conditional) {
        return switch (conditional.state()) {
            case OPEN, CANCELLING, FILLED -> conditional;
            case EXPIRED -> throw new ApiException(ErrorCode.SIGNING_DEADLINE_EXPIRED, "The signing window is over.");
            default ->
                throw new ApiException(
                        ErrorCode.INVALID_TRANSITION, "The conditional is no longer waiting for a signature.");
        };
    }

    private TriggerPort.CreateRequest request(ConditionalOrder conditional, RealWalletInfo wallet) {
        return new TriggerPort.CreateRequest(
                conditional.conditionalOrderId(),
                wallet.botWalletAddress(),
                wallet.mainWalletAddress(),
                conditional.positionMint(),
                conditional.kind(),
                conditional.stopPriceUsd(),
                conditional.takeProfitPriceUsd(),
                conditional.amountBaseUnits(),
                conditional.slippageBps());
    }

    private ConditionalOrder owned(Principal person, UUID conditionalOrderId) {
        return store.findForOwner(person.tenantId(), person.userId(), conditionalOrderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Conditional not found."));
    }
}
