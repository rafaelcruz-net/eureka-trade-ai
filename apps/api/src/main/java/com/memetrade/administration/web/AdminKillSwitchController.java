package com.memetrade.administration.web;

import com.memetrade.administration.domain.ActiveBots;
import com.memetrade.administration.domain.KillSwitchService;
import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.administration.domain.SwitchState;
import com.memetrade.administration.infra.KillSwitchApplier;
import com.memetrade.administration.web.KillSwitchDtos.KillSwitchRequest;
import com.memetrade.administration.web.KillSwitchDtos.KillSwitchView;
import com.memetrade.identity.api.AdminAccess;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdentityAdministration;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kill switch routes of C8 (F2 to F4; BR2.1 to BR2.7; AC7.2.1, AC7.2.2, AC7.2.4, AC7.2.5). The administrative route
 * rule already refused any other role; each operation checks it again through {@link AdminAccess}. The {@code PUT}
 * commits the requested state FIRST (one transaction with the history, the event and the idempotency record) and only
 * then calls the applier, which applies the CURRENT state under the apply lock and answers within 5 s normally or
 * within 11 s when the lock is held elsewhere ({@code reconcileNeeded = true}).
 */
@RestController
@ProcessRole(ProcessRole.Kind.API)
public class AdminKillSwitchController {

    private static final Logger log = LoggerFactory.getLogger(AdminKillSwitchController.class);

    private final AdminAccess admins;
    private final KillSwitchService service;
    private final KillSwitchApplier applier;
    private final KillSwitchStore store;
    private final IdentityAdministration identity;
    private final ActiveBots activeBots;

    public AdminKillSwitchController(
            AdminAccess admins,
            KillSwitchService service,
            KillSwitchApplier applier,
            KillSwitchStore store,
            IdentityAdministration identity,
            ActiveBots activeBots) {
        this.admins = admins;
        this.service = service;
        this.applier = applier;
        this.store = store;
        this.identity = identity;
        this.activeBots = activeBots;
    }

    @Operation(
            operationId = "getKillSwitch",
            summary = "Estado do kill switch, quem, quando, motivo, pendências e bots ativos a pausar (AC7.2.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @ApiResponse(responseCode = "200", description = "Estado atual")
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE para token de convidado, auditado como REQUEST_FORBIDDEN",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/api/v1/admin/kill-switch")
    public ResponseEntity<KillSwitchView> current(@Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        KillSwitchSnapshot snapshot = store.read();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(KillSwitchView.of(snapshot, maskedBy(snapshot, admin), activeBots.count()));
    }

    @Operation(
            operationId = "changeKillSwitch",
            summary = "Aciona ou desliga o kill switch (FR3.2); a mesma Idempotency-Key devolve a mesma resposta",
            security = @SecurityRequirement(name = "accessToken"))
    @Parameter(
            name = IdempotencyKey.HEADER,
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(
            responseCode = "200",
            description = "Estado confirmado, com pendingBots e reconcileNeeded depois da aplicação")
    @ApiResponse(
            responseCode = "400",
            description =
                    "VALIDATION_FAILED: Idempotency-Key ausente, state fora de ON/OFF ou reason vazio ou com mais de"
                            + " 500 caracteres",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "UNAUTHENTICATED",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN_ROLE para token de convidado, auditado como REQUEST_FORBIDDEN",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT: mesma chave com outro corpo",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemResponse.class)))
    @PutMapping("/api/v1/admin/kill-switch")
    public ResponseEntity<KillSwitchView> change(
            @RequestBody KillSwitchRequest body, @Parameter(hidden = true) HttpServletRequest request) {
        Principal admin = admins.requireAdministrator(request);
        IdempotencyKey key = IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER));
        KillSwitchService.Outcome outcome = service.change(
                new KillSwitchService.Command(state(body.state()), body.reason(), admin, key, CorrelationId.current()));
        // The state is committed here. ON (new or repeated, BR2.4) and a new OFF reach U5 through the applier.
        SwitchState written = outcome.snapshot().state();
        if (!outcome.replayed() && (written == SwitchState.ON || outcome.changed())) {
            applier.applyCurrent(CorrelationId.current());
        }
        KillSwitchSnapshot after = store.read();
        log.info(
                "Kill switch request: state={}, version={}, changed={}, replayed={}, pendingBots={}",
                after.state(),
                after.version(),
                outcome.changed(),
                outcome.replayed(),
                after.pendingBots());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(KillSwitchView.of(after, maskedBy(after, admin), null));
    }

    private Optional<String> maskedBy(KillSwitchSnapshot snapshot, Principal admin) {
        return snapshot.changedBy().flatMap(adminId -> identity.maskedAdministratorWallet(adminId, admin));
    }

    /** An unknown value becomes the missing state, refused by the service with the field {@code state}. */
    private static SwitchState state(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            // Exactly ON or OFF (C8 enum); "on" or " ON" is refused like any other value.
            return raw.equals(raw.toUpperCase(Locale.ROOT)) ? SwitchState.valueOf(raw) : null;
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
