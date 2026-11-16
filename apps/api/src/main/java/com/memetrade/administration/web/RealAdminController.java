package com.memetrade.administration.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.administration.web.RealAdminDtos.Cap;
import com.memetrade.administration.web.RealAdminDtos.CapRequest;
import com.memetrade.administration.web.RealAdminDtos.CompensationRequest;
import com.memetrade.administration.web.RealAdminDtos.Divergence;
import com.memetrade.administration.web.RealAdminDtos.DivergencePage;
import com.memetrade.administration.web.RealAdminDtos.KillSwitch;
import com.memetrade.administration.web.RealAdminDtos.ReasonRequest;
import com.memetrade.administration.web.RealAdminDtos.Release;
import com.memetrade.administration.web.RealAdminDtos.ReleaseRequest;
import com.memetrade.administration.web.RealAdminDtos.Restore;
import com.memetrade.administration.web.RealAdminDtos.Revocation;
import com.memetrade.administration.web.RealAdminDtos.Token;
import com.memetrade.administration.web.RealAdminDtos.TokenBlockRequest;
import com.memetrade.administration.web.RealAdminDtos.TokenList;
import com.memetrade.administration.web.RealAdminDtos.Unblock;
import com.memetrade.administration.web.RealAdminDtos.User;
import com.memetrade.administration.web.RealAdminDtos.UserPage;
import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealAdministration;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.api.RealKillSwitchState.KillSwitchChange;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.riskcontrol.api.UnblockResult;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.wallet.WalletAddress;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes R4 of the administration of the REAL (261113-trading-real-bot U5; BR1.2 to BR7.2; U5-NFR6.1 to U5-NFR6.3). Only
 * the role ADMIN, through the administrative entry: the route rule refuses any other role and each operation checks it
 * again through {@link AdminAccess}. Every change takes an {@code Idempotency-Key} (the common mechanism: the same key
 * and body give the same answer, another body 409, a refusal is never stored) and a reason, runs in one transaction with
 * the idempotency record and is audited by the module that owns the data, with the administrator as the actor. The
 * controller calls only the M1 ({@link RealAdministration}), the M5 ({@code withPersonLock} around the revocation, and
 * the block list) and the M7 ({@link RealKillSwitchState}).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/real")
@ProcessRole(ProcessRole.Kind.API)
public class RealAdminController {

    /** The chain of the block list of the REAL (the tokens of the bot wallets are on Solana). */
    static final String CHAIN = "solana";

    private static final int MAX_REASON = 500;

    private final AdminAccess admins;
    private final RealAdministration real;
    private final RealKillSwitchState killSwitch;
    private final RealRiskGate gate;
    private final IdempotencyGuard idempotency;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public RealAdminController(
            AdminAccess admins,
            RealAdministration real,
            RealKillSwitchState killSwitch,
            RealRiskGate gate,
            IdempotencyGuard idempotency,
            TransactionTemplate transactions,
            ObjectMapper json) {
        this.admins = admins;
        this.real = real;
        this.killSwitch = killSwitch;
        this.gate = gate;
        this.idempotency = idempotency;
        this.transactions = transactions;
        this.json = json;
    }

    // ----- BR5.1: the view -----

    @Operation(
            operationId = "listRealUsers",
            summary = "Pessoas com carteira do bot: modo, teto, saldo, liberação e divergências (BR5.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/users")
    public ResponseEntity<UserPage> users(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        RealAdministration.Page<RealAdministration.RealUserView> page =
                real.adminUsers(Optional.ofNullable(cursor), limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new UserPage(page.items().stream().map(User::of).toList(), page.nextCursor()));
    }

    @Operation(
            operationId = "getRealUser",
            summary = "A visão de uma pessoa, com as 20 ordens mais recentes; sem chave e com a carteira principal"
                    + " abreviada",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/users/{userId}")
    public ResponseEntity<User> user(@PathVariable UUID userId, @Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(User.of(real.adminUserView(userId).orElseThrow(RealAdminRefused::notFound)));
    }

    // ----- BR1.2, BR1.3, BR1.6: the release -----

    @Operation(
            operationId = "releaseRealUser",
            summary = "Registra a liberação; vale depois de 24 h com os portões verdes (BR1.2 a BR1.4)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PostMapping("/users/{userId}/release")
    public ResponseEntity<Release> release(
            @PathVariable UUID userId,
            @RequestBody ReleaseRequest body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        reason(body.reason());
        return idempotent(
                IdempotentOperation.REAL_ADMIN_RELEASE,
                admin,
                request,
                "release:" + userId + ":" + body.network(),
                Release.class,
                () -> new Answer(
                        HttpStatus.CREATED, Release.of(real.adminRelease(admin.userId(), userId, body.network()))));
    }

    @Operation(
            operationId = "revokeRealUser",
            summary = "Revoga na hora; as ordens não enviadas são canceladas e o saque continua (BR1.6)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @DeleteMapping("/users/{userId}/release")
    public ResponseEntity<Revocation> revoke(
            @PathVariable UUID userId,
            @RequestBody ReasonRequest body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        String reason = reason(body.reason());
        return idempotent(
                IdempotentOperation.REAL_ADMIN_REVOKE,
                admin,
                request,
                "revoke:" + userId + ":" + reason,
                Revocation.class,
                () -> new Answer(HttpStatus.OK, Revocation.of(revokeUnderTheLockOfThePerson(admin, userId, reason))));
    }

    /** logical-components.md iteration 2: the lock of the person (M5) around the M1, never inside botwallet. */
    private RealAdministration.RevokeView revokeUnderTheLockOfThePerson(Principal admin, UUID userId, String reason) {
        Optional<UUID> tenant = real.tenantOf(userId);
        if (tenant.isEmpty()) {
            return real.adminRevoke(admin.userId(), userId, reason);
        }
        return gate.withPersonLock(tenant.get(), userId, () -> real.adminRevoke(admin.userId(), userId, reason));
    }

    // ----- BR4.1, BR4.2: the cap and the block list -----

    @Operation(
            operationId = "lowerRealCap",
            summary = "Baixa o teto na hora (nunca sobe); o TxSigner recebe LOWER_CAP até confirmar (BR4.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PutMapping("/users/{userId}/cap")
    public ResponseEntity<Cap> cap(
            @PathVariable UUID userId,
            @RequestBody CapRequest body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        reason(body.reason());
        long cap = lamports(body.capLamports(), false);
        return idempotent(
                IdempotentOperation.REAL_ADMIN_CAP,
                admin,
                request,
                "cap:" + userId + ":" + cap,
                Cap.class,
                () -> new Answer(HttpStatus.OK, Cap.of(real.adminLowerCap(admin.userId(), userId, cap))));
    }

    @Operation(
            operationId = "listRealTokenBlocks",
            summary = "Lista de bloqueio de tokens das carteiras do bot",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/token-blocks")
    public ResponseEntity<TokenList> tokenBlocks(@Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TokenList(transactions.execute(status -> gate.blockedTokens()).stream()
                        .map(Token::of)
                        .toList()));
    }

    @Operation(
            operationId = "blockRealToken",
            summary = "Bloqueia um token para todas as carteiras do bot a partir do registro (BR4.2)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PostMapping("/token-blocks")
    public ResponseEntity<Token> block(
            @RequestBody TokenBlockRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        String reason = reason(body.reason());
        String mint = mint(body.mint());
        return idempotent(
                IdempotentOperation.REAL_ADMIN_TOKEN_BLOCK,
                admin,
                request,
                "block:" + mint + ":" + reason,
                Token.class,
                () -> new Answer(HttpStatus.CREATED, Token.of(gate.blockToken(CHAIN, mint, reason, admin.userId()))));
    }

    @Operation(
            operationId = "unblockRealToken",
            summary = "Desbloqueia um token (BR4.2)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @DeleteMapping("/token-blocks/{mint}")
    public ResponseEntity<Unblock> unblock(
            @PathVariable String mint, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        String checked = mint(mint);
        return idempotent(
                IdempotentOperation.REAL_ADMIN_TOKEN_BLOCK,
                admin,
                request,
                "unblock:" + checked,
                Unblock.class,
                () -> new Answer(
                        HttpStatus.OK,
                        new Unblock(
                                checked,
                                gate.unblockToken(CHAIN, checked, admin.userId()) == UnblockResult.UNBLOCKED)));
    }

    // ----- BR3.1, BR3.2: the kill switch REAL (M7) -----

    @Operation(
            operationId = "getRealKillSwitch",
            summary = "Estado do kill switch das carteiras do bot",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/kill-switch")
    public ResponseEntity<KillSwitch> killSwitch(@Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(KillSwitch.of(killSwitch.current(), false, 0));
    }

    @Operation(
            operationId = "activateRealKillSwitch",
            summary =
                    "Liga o kill switch das carteiras do bot: ordens novas de todos recusadas na hora; as não enviadas são canceladas;"
                            + " o saque continua (BR3.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PutMapping("/kill-switch")
    public ResponseEntity<KillSwitch> activate(
            @RequestBody ReasonRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        String reason = reason(body.reason());
        ResponseEntity<KillSwitch> answer = idempotent(
                IdempotentOperation.REAL_ADMIN_KILL_SWITCH, admin, request, "on:" + reason, KillSwitch.class, () -> {
                    KillSwitchChange change = killSwitch.activate(admin.userId(), reason);
                    return new Answer(HttpStatus.OK, KillSwitch.of(change.view(), change.changed(), 0));
                });
        // After the commit: the unsent orders of everybody are cancelled once now; the job repeats every minute.
        if (answer.getBody() != null && answer.getBody().changed()) {
            int cancelled = killSwitch.cancelUnsentWhileActive();
            return ResponseEntity.status(answer.getStatusCode())
                    .cacheControl(CacheControl.noStore())
                    .body(KillSwitch.of(killSwitch.current(), true, cancelled));
        }
        return answer;
    }

    @Operation(
            operationId = "deactivateRealKillSwitch",
            summary = "Desliga o kill switch das carteiras do bot; nenhum bot é religado (BR3.2)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @DeleteMapping("/kill-switch")
    public ResponseEntity<KillSwitch> deactivate(
            @RequestBody ReasonRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        String reason = reason(body.reason());
        return idempotent(
                IdempotentOperation.REAL_ADMIN_KILL_SWITCH, admin, request, "off:" + reason, KillSwitch.class, () -> {
                    KillSwitchChange change = killSwitch.deactivate(admin.userId(), reason);
                    return new Answer(HttpStatus.OK, KillSwitch.of(change.view(), change.changed(), 0));
                });
    }

    // ----- BR2.3, BR2.5: the divergences and the restore -----

    @Operation(
            operationId = "listRealDivergences",
            summary = "Divergências abertas ou compensadas, das mais novas para as mais antigas",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/divergences")
    public ResponseEntity<DivergencePage> divergences(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @Parameter(hidden = true) HttpServletRequest request) {
        admins.requireAdministrator(request);
        RealAdministration.Page<RealAdministration.DivergenceView> page =
                real.listDivergences(Optional.ofNullable(cursor), limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new DivergencePage(
                        page.items().stream().map(Divergence::of).toList(), page.nextCursor()));
    }

    @Operation(
            operationId = "compensateRealDivergence",
            summary = "Lançamento compensatório por ativo, igual à diferença medida; a nova reconciliação decide"
                    + " (BR2.3)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PostMapping("/divergences/{divergenceId}/compensation")
    public ResponseEntity<Divergence> compensate(
            @PathVariable UUID divergenceId,
            @RequestBody CompensationRequest body,
            @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        String reason = reason(body.reason());
        if (body.items() == null || body.items().isEmpty()) {
            throw RealAdminRefused.validation();
        }
        List<RealAdministration.CompensationItem> items = body.items().stream()
                .map(item -> new RealAdministration.CompensationItem(item.asset(), lamports(item.amount(), true)))
                .toList();
        StringBuilder content = new StringBuilder("compensate:" + divergenceId);
        items.forEach(
                item -> content.append(':').append(item.asset()).append('=').append(item.amount()));
        return idempotent(
                IdempotentOperation.REAL_ADMIN_COMPENSATE,
                admin,
                request,
                content.toString(),
                Divergence.class,
                () -> new Answer(
                        HttpStatus.CREATED,
                        Divergence.of(real.compensate(admin.userId(), divergenceId, items, reason))));
    }

    @Operation(
            operationId = "confirmRealRestore",
            summary = "Confirma a restauração do banco depois que todas as carteiras vieram limpas (BR2.5)",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @PostMapping("/restore/confirmation")
    public ResponseEntity<Restore> confirmRestore(
            @RequestBody ReasonRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        reason(body.reason());
        return idempotent(IdempotentOperation.REAL_ADMIN_RESTORE, admin, request, "restore", Restore.class, () -> {
            RealAdministration.RestoreView view = real.confirmRestore(admin.userId());
            return new Answer(HttpStatus.OK, new Restore(view.markerId(), view.detectedAt()));
        });
    }

    // ----- helpers -----

    /** The answer of one mutating route, before it is stored by the common idempotency mechanism. */
    private record Answer(HttpStatus status, Object body) {}

    /** U5-NFR6.2: one transaction with the claim of the key; a refusal rolls it back and is never stored. */
    private <T> ResponseEntity<T> idempotent(
            IdempotentOperation operation,
            Principal admin,
            HttpServletRequest request,
            String content,
            Class<T> type,
            Supplier<Answer> action) {
        IdempotencyRequest call = new IdempotencyRequest(
                operation,
                IdempotencyScope.authenticated(admin, operation),
                IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER)),
                sha256(content));
        IdempotentResponse response = transactions.execute(status -> idempotency.execute(call, () -> {
            Answer answer = action.get();
            return IdempotentOutcome.of(IdempotentResponse.fresh(answer.status().value(), write(answer.body())));
        }));
        try {
            return ResponseEntity.status(response.status())
                    .cacheControl(CacheControl.noStore())
                    .body(json.readValue(response.body(), type));
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("A stored REAL administration answer could not be read", unreadable);
        }
    }

    /** BR7.2: the reason, 1 to 500 characters once trimmed. */
    private static String reason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_REASON) {
            throw RealAdminRefused.validation();
        }
        return trimmed;
    }

    private static long lamports(String value, boolean signed) {
        if (value == null || !value.matches(signed ? "^-?[0-9]{1,20}$" : "^[0-9]{1,20}$")) {
            throw RealAdminRefused.validation();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooLarge) {
            throw RealAdminRefused.validation();
        }
    }

    private static String mint(String mint) {
        if (!WalletAddress.isValid(mint)) {
            throw RealAdminRefused.validation();
        }
        return mint;
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A REAL administration answer could not be stored", unwritable);
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }
}
