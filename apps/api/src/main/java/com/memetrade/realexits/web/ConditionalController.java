package com.memetrade.realexits.web;

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
import com.memetrade.realexits.application.ConditionalService;
import com.memetrade.realexits.domain.ConditionalKind;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.web.KeysetCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes R5 of the addendum of the U7: the conditional exits of the person. Tenant and person only from the access
 * token; a conditional of another person is 404 (U7-NFR1.9). The creation, the signature and the cancellation run
 * through the common idempotency mechanism of the identity (project.md): the same key and content return the stored
 * answer, the same key with another content is {@code 409 IDEMPOTENCY_CONFLICT}, an error is never stored. None runs
 * inside one transaction with the claim of the key, because the creation and the signature call the provider.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
@RequestMapping("/api/v1/real/conditional-orders")
@Tag(name = "REAL — saídas automáticas")
public class ConditionalController {

    private static final int MAX_PAGE = 50;
    private static final String CURSOR_SCOPE = "real-conditionals";
    private static final Pattern MINT = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private final ConditionalService conditionals;
    private final AuthContext auth;
    private final IdempotencyGuard idempotency;
    private final ObjectMapper json;
    private final Clock clock;

    public ConditionalController(
            ConditionalService conditionals,
            AuthContext auth,
            IdempotencyGuard idempotency,
            ObjectMapper json,
            Clock clock) {
        this.conditionals = conditionals;
        this.auth = auth;
        this.idempotency = idempotency;
        this.json = json;
        this.clock = clock;
    }

    @Operation(
            operationId = "createRealConditionalOrder",
            summary = "Cria stop, alvo ou OCO de uma posição REAL e abre a sessão de assinatura",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping
    public ResponseEntity<ConditionalDtos.Created> create(
            @RequestBody ConditionalDtos.CreateRequest body, HttpServletRequest request) {
        ConditionalService.CreateCommand command = command(body);
        Principal person = auth.current();
        String key = request.getHeader(IdempotencyKey.HEADER);
        return idempotent(
                IdempotentOperation.REAL_CONDITIONAL_CREATE,
                person,
                request,
                command.positionMint() + ":" + command.kind() + ":"
                        + command.stopPriceUsd().map(BigDecimal::toPlainString) + ":"
                        + command.takeProfitPriceUsd().map(BigDecimal::toPlainString),
                ConditionalDtos.Created.class,
                () -> {
                    ConditionalService.Created created = conditionals.create(person, command, key);
                    ConditionalDtos.ConditionalOrderView view =
                            ConditionalDtos.ConditionalOrderView.of(created.conditional(), clock.instant());
                    return new Answer(
                            HttpStatus.CREATED,
                            new ConditionalDtos.Created(
                                    created.conditional().conditionalOrderId(),
                                    view,
                                    ConditionalDtos.SigningSession.of(created.session()),
                                    true));
                });
    }

    @Operation(
            operationId = "listRealConditionalOrders",
            summary = "Saídas automáticas da pessoa, mais recentes primeiro",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping
    public ResponseEntity<ConditionalDtos.Page> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        Principal person = auth.current();
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        Optional<JdbcConditionalStore.Cursor> after = Optional.ofNullable(cursor)
                .map(text -> KeysetCursor.decode(text, CURSOR_SCOPE))
                .map(position -> new JdbcConditionalStore.Cursor(position.at(), position.id()));
        List<ConditionalOrder> rows = conditionals.page(person, after, size + 1);
        List<ConditionalOrder> page = rows.subList(0, Math.min(size, rows.size()));
        String next = rows.size() > size
                ? new KeysetCursor(page.getLast().createdAt(), page.getLast().conditionalOrderId()).encode(CURSOR_SCOPE)
                : null;
        java.time.Instant now = clock.instant();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ConditionalDtos.Page(
                        page.stream()
                                .map(row -> ConditionalDtos.ConditionalOrderView.of(row, now))
                                .toList(),
                        next,
                        conditionals.count(person)));
    }

    @Operation(
            operationId = "quoteRealConditionalOrder",
            summary = "Posição e preço atual em USD (com fonte e horário) para definir stop e alvo",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/quote")
    public ResponseEntity<ConditionalDtos.QuoteView> quote(@RequestParam String positionMint) {
        if (positionMint == null || !MINT.matcher(positionMint).matches()) {
            throw ApiException.validation("positionMint", "Informe o mint da posição.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ConditionalDtos.QuoteView.of(conditionals.quote(auth.current(), positionMint)));
    }

    @Operation(
            operationId = "signRealConditionalOrder",
            summary = "Recebe a assinatura da carteira principal; o servidor confere e abre a condicional",
            security = @SecurityRequirement(name = "accessToken"))
    @PostMapping("/{conditionalOrderId}/signature")
    public ResponseEntity<ConditionalDtos.ConditionalOrderView> sign(
            @PathVariable UUID conditionalOrderId,
            @RequestBody ConditionalDtos.SignatureRequest body,
            HttpServletRequest request) {
        if (body == null
                || (blank(body.signature()) && blank(body.signedTransactionBase64()))
                || length(body.signature()) > 120
                || length(body.signedTransactionBase64()) > 1700) {
            throw ApiException.validation("signature", "Envie a assinatura da carteira principal.");
        }
        Principal person = auth.current();
        ConditionalService.SignatureCommand command = new ConditionalService.SignatureCommand(
                Optional.ofNullable(body.signature()).filter(text -> !text.isBlank()),
                Optional.ofNullable(body.signedTransactionBase64()).filter(text -> !text.isBlank()));
        return idempotent(
                IdempotentOperation.REAL_CONDITIONAL_SIGN,
                person,
                request,
                conditionalOrderId + ":" + body.signature() + ":" + body.signedTransactionBase64(),
                ConditionalDtos.ConditionalOrderView.class,
                () -> new Answer(
                        HttpStatus.ACCEPTED,
                        ConditionalDtos.ConditionalOrderView.of(
                                conditionals.submitSignature(person, conditionalOrderId, command), clock.instant())));
    }

    @Operation(
            operationId = "cancelRealConditionalOrder",
            summary = "Cancela a saída automática: CANCELLED sem assinatura pendente, CANCELLING se já aberta",
            security = @SecurityRequirement(name = "accessToken"))
    @DeleteMapping("/{conditionalOrderId}")
    public ResponseEntity<ConditionalDtos.ConditionalOrderView> cancel(
            @PathVariable UUID conditionalOrderId, HttpServletRequest request) {
        Principal person = auth.current();
        return idempotent(
                IdempotentOperation.REAL_CONDITIONAL_CANCEL,
                person,
                request,
                conditionalOrderId.toString(),
                ConditionalDtos.ConditionalOrderView.class,
                () -> new Answer(
                        HttpStatus.ACCEPTED,
                        ConditionalDtos.ConditionalOrderView.of(
                                conditionals.cancelByUser(person, conditionalOrderId), clock.instant())));
    }

    /** The validation of the body (400 VALIDATION_FAILED); the rules of BR3 come after, in the service (422). */
    private static ConditionalService.CreateCommand command(ConditionalDtos.CreateRequest body) {
        if (body == null
                || body.positionMint() == null
                || !MINT.matcher(body.positionMint()).matches()) {
            throw ApiException.validation("positionMint", "Informe o mint da posição.");
        }
        ConditionalKind kind;
        try {
            kind = ConditionalKind.valueOf(body.kind() == null ? "" : body.kind());
        } catch (IllegalArgumentException unknown) {
            throw ApiException.validation("kind", "Informe STOP, TAKE_PROFIT ou OCO.");
        }
        return new ConditionalService.CreateCommand(
                body.positionMint(),
                kind,
                price("stopPriceUsd", body.stopPriceUsd()),
                price("takeProfitPriceUsd", body.takeProfitPriceUsd()));
    }

    /** A DecimalString: plain digits with an optional point, never a sign, an exponent nor floating point. */
    private static Optional<BigDecimal> price(String field, String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        if (!ConditionalDtos.DECIMAL_STRING.matcher(text).matches()) {
            throw ApiException.validation(field, "Use um preço decimal com ponto, por exemplo 0.00008.");
        }
        return Optional.of(new BigDecimal(text));
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    /** The answer of one mutating route, before it is stored by the common idempotency mechanism. */
    private record Answer(HttpStatus status, Object body) {}

    private <T> ResponseEntity<T> idempotent(
            IdempotentOperation operation,
            Principal person,
            HttpServletRequest request,
            String content,
            Class<T> type,
            Supplier<Answer> action) {
        IdempotencyRequest call = new IdempotencyRequest(
                operation,
                IdempotencyScope.authenticated(person, operation),
                IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER)),
                sha256(content));
        IdempotentResponse response = idempotency.execute(call, () -> {
            Answer answer = action.get();
            return IdempotentOutcome.of(IdempotentResponse.fresh(answer.status().value(), write(answer.body())));
        });
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
}
