package com.memetrade.realexecution.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore;
import com.memetrade.realexecution.infra.LiquidationService;
import com.memetrade.realexecution.infra.RealModeService;
import com.memetrade.realexecution.infra.SigningSessionService;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.KeysetCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes R3 of the REAL orders, the mode and the liquidation (contract-summary.md, U4). Tenant and person only from the
 * access token; an order of another person is 404 (BR10.2). Every route needs the acceptance of the terms v3 (the
 * reacceptance filter, no exception). The orders are born from the decision of the bot (M6), never from a POST.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
@RequestMapping("/api/v1/real")
@Tag(name = "REAL — ordens e modo")
public class RealOrderController {

    private static final int MAX_PAGE = 50;
    private static final String CURSOR_SCOPE = "real-orders";

    private final JdbcRealExecutionStore store;
    private final SigningSessionService sessions;
    private final RealModeService modes;
    private final LiquidationService liquidations;
    private final MarketDataQuery market;
    private final AuthContext auth;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public RealOrderController(
            JdbcRealExecutionStore store,
            SigningSessionService sessions,
            RealModeService modes,
            LiquidationService liquidations,
            MarketDataQuery market,
            AuthContext auth,
            IdempotencyGuard idempotency,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        this.store = store;
        this.sessions = sessions;
        this.modes = modes;
        this.liquidations = liquidations;
        this.market = market;
        this.auth = auth;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    @Operation(
            operationId = "listRealOrders",
            summary = "Ordens REAL da pessoa, mais recentes primeiro",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/orders")
    public ResponseEntity<RealOrderDtos.RealOrderPage> list(
            @RequestParam(required = false) UUID botId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        Principal person = auth.current();
        Optional<RealOrderState> filter = Optional.ofNullable(state).map(RealOrderController::state);
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        Optional<KeysetCursor> after = Optional.ofNullable(cursor).map(text -> KeysetCursor.decode(text, CURSOR_SCOPE));
        List<JdbcRealExecutionStore.OrderRow> rows = store.listForOwner(
                person.tenantId(),
                person.userId(),
                Optional.ofNullable(botId),
                filter,
                after.map(KeysetCursor::at),
                after.map(KeysetCursor::id),
                size + 1);
        List<JdbcRealExecutionStore.OrderRow> page = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size
                ? new KeysetCursor(page.getLast().createdAt(), page.getLast().realOrderId()).encode(CURSOR_SCOPE)
                : null;
        Map<String, Integer> decimals = decimals(
                page.stream().map(JdbcRealExecutionStore.OrderRow::mint).collect(Collectors.toSet()));
        List<RealOrderDtos.RealOrder> items = page.stream()
                .map(row -> RealOrderDtos.of(viewOf(row), decimals))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RealOrderDtos.RealOrderPage(
                        items, next, store.countForOwner(person.tenantId(), person.userId())));
    }

    @Operation(
            operationId = "getRealOrder",
            summary = "Uma ordem REAL da pessoa",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/orders/{realOrderId}")
    public ResponseEntity<RealOrderDtos.RealOrder> get(@PathVariable UUID realOrderId) {
        Principal person = auth.current();
        JdbcRealExecutionStore.OrderRow row = store.findForOwner(person.tenantId(), person.userId(), realOrderId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RealOrderDtos.of(viewOf(row), decimals(Set.of(row.mint()))));
    }

    @Operation(
            operationId = "getRealSigningSession",
            summary = "Modo Assinada: a transação montada pelo servidor para a carteira principal assinar",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/orders/{realOrderId}/signing-session")
    public ResponseEntity<RealOrderDtos.SigningSession> signingSession(@PathVariable UUID realOrderId) {
        SigningSessionService.Session session = sessions.session(auth.current(), realOrderId);
        SigningSessionService.Display display = session.display();
        Map<String, Integer> decimals = decimals(Set.of(display.inputMint(), display.outputMint()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RealOrderDtos.SigningSession(
                        session.transactionBase64(),
                        session.messageDigest(),
                        session.deadline(),
                        new RealOrderDtos.SigningDisplay(
                                display.side(),
                                RealOrderDtos.amount(display.inputMint(), display.inputBaseUnits(), decimals),
                                RealOrderDtos.amount(display.outputMint(), display.expectedOutBaseUnits(), decimals),
                                RealOrderDtos.amount(display.outputMint(), display.minOutBaseUnits(), decimals),
                                Long.toString(display.feeLamports()),
                                display.reason())));
    }

    @Operation(
            operationId = "submitRealSignedTransaction",
            summary = "Recebe a transação assinada pela carteira principal; o servidor confere, cossigna e envia",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/orders/{realOrderId}/signing-session")
    public ResponseEntity<RealOrderDtos.RealOrder> submit(
            @PathVariable UUID realOrderId,
            @RequestBody RealOrderDtos.SignedTransactionRequest body,
            HttpServletRequest request) {
        if (body == null
                || body.signedTransactionBase64() == null
                || body.signedTransactionBase64().length() > 1700) {
            throw ApiException.validation("signedTransactionBase64", "Envie a transação assinada.");
        }
        Principal person = auth.current();
        // Outside a transaction: the TxSigner is called over the network and the claim of the session must survive it.
        return idempotent(
                IdempotentOperation.REAL_SIGNING_SUBMIT,
                person,
                request,
                realOrderId + ":" + body.signedTransactionBase64(),
                false,
                RealOrderDtos.RealOrder.class,
                () -> {
                    RealOrderView order = sessions.submit(person, realOrderId, body.signedTransactionBase64());
                    return new Answer(HttpStatus.ACCEPTED, RealOrderDtos.of(order, decimals(Set.of(order.mint()))));
                });
    }

    @Operation(
            operationId = "rejectRealSigning",
            summary = "A pessoa recusa a assinatura; a ordem é cancelada",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/orders/{realOrderId}/reject")
    public ResponseEntity<RealOrderDtos.RealOrder> reject(@PathVariable UUID realOrderId, HttpServletRequest request) {
        Principal person = auth.current();
        return idempotent(
                IdempotentOperation.REAL_SIGNING_REJECT,
                person,
                request,
                realOrderId.toString(),
                true,
                RealOrderDtos.RealOrder.class,
                () -> {
                    RealOrderView order = sessions.reject(person, realOrderId);
                    return new Answer(HttpStatus.OK, RealOrderDtos.of(order, decimals(Set.of(order.mint()))));
                });
    }

    @Operation(
            operationId = "changeRealMode",
            summary = "Troca o modo: rebaixar é imediato; subir exige o consentimento da carteira principal",
            security = @SecurityRequirement(name = "accessToken"))
    @PutMapping("/mode")
    public ResponseEntity<RealOrderDtos.ModeView> mode(
            @RequestBody RealOrderDtos.ModeRequest body, HttpServletRequest request) {
        if (body == null) {
            throw ApiException.validation("mode", "Informe o modo.");
        }
        Principal person = auth.current();
        // Outside a transaction: raising the mode calls the TxSigner over the network.
        return idempotent(
                IdempotentOperation.REAL_MODE,
                person,
                request,
                body.mode() + ":" + body.challengeId() + ":" + body.signature(),
                false,
                RealOrderDtos.ModeView.class,
                () -> new Answer(
                        HttpStatus.OK,
                        new RealOrderDtos.ModeView(
                                modes.change(person, body.mode(), body.challengeId(), body.signature()))));
    }

    @Operation(
            operationId = "startRealLiquidation",
            summary = "Vender tudo e sacar",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/liquidations")
    public ResponseEntity<RealOrderDtos.LiquidationView> liquidate(HttpServletRequest request) {
        Principal person = auth.current();
        return idempotent(
                IdempotentOperation.REAL_LIQUIDATION,
                person,
                request,
                "liquidation",
                true,
                RealOrderDtos.LiquidationView.class,
                () -> new Answer(HttpStatus.ACCEPTED, new RealOrderDtos.LiquidationView(liquidations.start(person))));
    }

    /** The answer of one mutating route, before it is stored by the common idempotency mechanism. */
    private record Answer(HttpStatus status, Object body) {}

    /**
     * Review of the U4 (Major 2; contract line 49, project.md): the mutating R3 routes run through the common
     * idempotency mechanism of the identity module. The same key and content return the stored answer; the same key
     * with other content is 409 IDEMPOTENCY_CONFLICT; an error is not stored (the key may be repeated). The routes that
     * only write the database run in one transaction with the claim of the key; the ones that call the TxSigner do not
     * (the claim commits first and is released on an error).
     */
    private <T> ResponseEntity<T> idempotent(
            IdempotentOperation operation,
            Principal person,
            HttpServletRequest request,
            String content,
            boolean inTransaction,
            Class<T> type,
            Supplier<Answer> action) {
        IdempotencyRequest call = new IdempotencyRequest(
                operation,
                IdempotencyScope.authenticated(person, operation),
                IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER)),
                sha256(content));
        Supplier<IdempotentResponse> run = () -> idempotency.execute(call, () -> {
            Answer answer = action.get();
            return IdempotentOutcome.of(IdempotentResponse.fresh(answer.status().value(), write(answer.body())));
        });
        IdempotentResponse response = inTransaction ? transactions.execute(status -> run.get()) : run.get();
        try {
            return ResponseEntity.status(response.status())
                    .cacheControl(CacheControl.noStore())
                    .body(json.readValue(response.body(), type));
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A stored REAL answer could not be read", unreadable);
        }
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A REAL answer could not be stored", unwritable);
        }
    }

    private static String sha256(String content) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    private static RealOrderState state(String value) {
        try {
            return RealOrderState.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw ApiException.validation("state", "Estado de ordem desconhecido.");
        }
    }

    private RealOrderView viewOf(JdbcRealExecutionStore.OrderRow row) {
        return com.memetrade.realexecution.infra.RealExecutionService.viewOf(row);
    }

    /** The decimals of the mints (M4); a mint without them is shown without decimals, never with zero. */
    private Map<String, Integer> decimals(Set<String> mints) {
        Map<String, Integer> decimals = new HashMap<>();
        Set<TokenId> tokens =
                mints.stream().map(mint -> new TokenId("solana", mint)).collect(Collectors.toSet());
        market.decimalsOf(tokens).forEach((token, value) -> {
            if (value instanceof MarketValue.Known known) {
                decimals.put(token.mint(), known.value().intValueExact());
            }
        });
        return decimals;
    }
}
