package com.memetrade.txsigner.web;

import com.memetrade.txsigner.domain.ChangePolicyCommand;
import com.memetrade.txsigner.domain.CreateWalletCommand;
import com.memetrade.txsigner.domain.KeyService;
import com.memetrade.txsigner.domain.PolicyView;
import com.memetrade.txsigner.domain.SignCommand;
import com.memetrade.txsigner.domain.SignerStore;
import com.memetrade.txsigner.domain.SigningOutcome;
import com.memetrade.txsigner.domain.SigningService;
import com.memetrade.txsigner.domain.WalletPolicy;
import com.memetrade.txsigner.policy.PolicyRefusal;
import com.memetrade.txsigner.policy.SwapContext;
import com.memetrade.txsigner.policy.WithdrawalContext;
import com.memetrade.txsigner.web.InternalDtos.ConsentRequest;
import com.memetrade.txsigner.web.InternalDtos.CreateWalletRequest;
import com.memetrade.txsigner.web.InternalDtos.CreateWalletResponse;
import com.memetrade.txsigner.web.InternalDtos.PolicyResponse;
import com.memetrade.txsigner.web.InternalDtos.SignatureContext;
import com.memetrade.txsigner.web.InternalDtos.SignatureRequest;
import com.memetrade.txsigner.web.InternalDtos.SignatureResponse;
import com.memetrade.txsigner.web.InternalDtos.SignedItem;
import com.memetrade.txsigner.web.InternalDtos.SignedList;
import com.memetrade.txsigner.web.InternalDtos.WalletItem;
import com.memetrade.txsigner.web.InternalDtos.WalletList;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal routes R1 of the TxSigner (contract-summary.md, addendum of infrastructure-specification.md). Private network
 * only, behind {@link ServiceAuthFilter}. No answer ever carries a key reference, a consent text or, on a refusal, the
 * transaction (NFR1.7).
 */
@RestController
@Validated
@RequestMapping(path = "/internal/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalController {

    private static final int LIST_LIMIT = 500;

    private final KeyService keys;
    private final SigningService signing;
    private final SignerStore store;
    private final MeterRegistry meters;

    public InternalController(KeyService keys, SigningService signing, SignerStore store, MeterRegistry meters) {
        this.keys = keys;
        this.signing = signing;
        this.store = store;
        this.meters = meters;
    }

    @PostMapping("/wallets")
    public ResponseEntity<CreateWalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest body) {
        String address = keys.createWallet(new CreateWalletCommand(
                        body.requestId(), body.mainWalletAddress(), body.consentMessage(), body.consentSignature()))
                .botWalletAddress();
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateWalletResponse(address));
    }

    @PostMapping("/wallets/{botWalletAddress}/consents")
    public PolicyResponse changePolicy(
            @PathVariable @Pattern(regexp = InternalDtos.ADDRESS) String botWalletAddress,
            @Valid @RequestBody ConsentRequest body) {
        PolicyView view = keys.changePolicy(new ChangePolicyCommand(
                body.requestId(),
                botWalletAddress,
                body.kind(),
                body.consentMessage(),
                body.consentSignature(),
                body.lowerTo() == null ? null : body.lowerTo().mode(),
                body.lowerTo() == null || body.lowerTo().capLamports() == null
                        ? null
                        : Long.parseLong(body.lowerTo().capLamports())));
        return new PolicyResponse(
                view.botWalletAddress(), Long.toString(view.capLamports()), view.mode(), view.closed());
    }

    @GetMapping("/wallets")
    public WalletList wallets(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore) {
        return new WalletList(store.listPoliciesCreatedBefore(createdBefore, LIST_LIMIT).stream()
                .map(policy -> new WalletItem(
                        policy.botWalletAddress(), policy.creationRequestId(), policy.createdAt(), status(policy)))
                .toList());
    }

    @PostMapping("/signatures")
    public ResponseEntity<?> sign(@Valid @RequestBody SignatureRequest body) {
        Timer.Sample sample = Timer.start(meters);
        try {
            SigningOutcome outcome = signing.sign(new SignCommand(
                    body.signingRequestId(),
                    body.botWalletAddress(),
                    body.operationKind(),
                    body.transactionBase64(),
                    context(body.context()),
                    swapContext(body.context())));
            if (outcome.signed()) {
                return ResponseEntity.ok(new SignatureResponse(outcome.signature(), outcome.signedTransactionBase64()));
            }
            if (body.operationKind().isTrigger()) {
                // 261113-trading-real-bot U7 (U7-NFR5.4, alert U7-NFR5.9): a server out of the trigger policy.
                meters.counter(
                                "txsigner_trigger_refusals",
                                "reason",
                                outcome.refusal().name())
                        .increment();
            }
            return refusal(outcome.refusal());
        } finally {
            sample.stop(Timer.builder("txsigner_latency")
                    .description("Latency of a signing request")
                    .tag("kind", body.operationKind().name())
                    .register(meters));
        }
    }

    @GetMapping("/signatures")
    public SignedList signatures(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return new SignedList(store.listSignedSince(since, LIST_LIMIT).stream()
                .map(record -> new SignedItem(
                        record.signingRequestId(),
                        record.botWalletAddress(),
                        record.signature(),
                        record.operationKind(),
                        record.decidedAt()))
                .toList());
    }

    private static WithdrawalContext context(SignatureContext context) {
        if (context == null) {
            return null;
        }
        return new WithdrawalContext(lamports(context.inputLamports()), lamports(context.feeLamports()));
    }

    /** U4: the context of a SWAP; a missing field stays null and the policy refuses with CONTEXT_MISSING. */
    private static SwapContext swapContext(SignatureContext context) {
        if (context == null) {
            return null;
        }
        return new SwapContext(
                context.mode(),
                context.side(),
                lamports(context.inputLamports()),
                lamports(context.inputBaseUnits()),
                lamports(context.quotedOut()),
                lamports(context.minOut()),
                context.maxSlippageBps(),
                lamports(context.feeLamports()),
                context.mint());
    }

    private static Long lamports(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    private static String status(WalletPolicy policy) {
        if (policy.blocked()) {
            return "BLOCKED";
        }
        return policy.closed() ? "CLOSED" : "ACTIVE";
    }

    private static ResponseEntity<Problem> refusal(PolicyRefusal refusal) {
        String correlationId = CorrelationIdFilter.current();
        return switch (refusal) {
            case REAL_DISABLED -> problem(403, "REAL_DISABLED", correlationId, null);
            case RECOVERY_IN_PROGRESS -> problem(503, "RECOVERY_IN_PROGRESS", correlationId, null);
            // NFR1.9 (Step 31 of the U1): the limit of ONE wallet; the api takes it as a refusal, never as a failure of
            // the TxSigner, so it neither repeats it nor counts it in the circuit shared by every wallet.
            case WALLET_RATE_LIMITED -> problem(429, "WALLET_RATE_LIMITED", correlationId, refusal.name());
            default -> problem(422, "SIGNING_POLICY_REFUSED", correlationId, refusal.name());
        };
    }

    static ResponseEntity<Problem> problem(int status, String code, String correlationId, String reason) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problem.of(status, code, correlationId, reason));
    }
}
