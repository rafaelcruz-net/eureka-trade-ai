package com.memetrade.pnl.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.application.RealPnlService;
import com.memetrade.pnl.real.RealPnlCalculator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/real/pnl} (contract R3, BR6.2, BR10.2): the P&L REAL of the person of the token, by average cost
 * with the fees, in signed lamports as integer text; an unknown price is shown as {@code UNKNOWN}, never as zero.
 * Nothing PAPER is read.
 */
@RestController
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
@Tag(name = "REAL — ordens e modo")
public class RealPnlController {

    private final RealPnlService service;
    private final AuthContext auth;

    public RealPnlController(RealPnlService service, AuthContext auth) {
        this.service = service;
        this.auth = auth;
    }

    @Schema(name = "RealPnlPosition")
    public record Position(
            @Schema(requiredMode = RequiredMode.REQUIRED) String mint,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,20}$")
            String baseUnits,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^-?[0-9]{1,20}$")
            String costLamports,

            @Schema(description = "ausente quando o preço é desconhecido")
            String valueLamports,

            @Schema(description = "ausente quando o preço é desconhecido")
            String unrealizedLamports,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"KNOWN", "UNKNOWN"})
            String priceStatus) {}

    @Schema(name = "RealPnl", description = "P&L REAL por custo médio, com taxas; nunca misturado com PAPER")
    public record RealPnl(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^-?[0-9]{1,20}$")
            String realizedLamports,

            @Schema(description = "ausente quando algum preço é desconhecido")
            String unrealizedLamports,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"KNOWN", "UNKNOWN"})
            String unrealizedStatus,

            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9]{1,20}$")
            String feesLamports,

            @Schema(requiredMode = RequiredMode.REQUIRED) List<Position> positions) {}

    @Operation(
            operationId = "getRealPnl",
            summary = "P&L REAL com taxas, nunca misturado com PAPER",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/api/v1/real/pnl")
    public ResponseEntity<RealPnl> pnl() {
        Principal person = auth.current();
        RealPnlCalculator.Result result = service.pnl(person.tenantId(), person.userId());
        List<Position> positions = result.positions().stream()
                .map(p -> new Position(
                        p.mint(),
                        Long.toString(p.baseUnits()),
                        Long.toString(p.costLamports()),
                        p.valueLamports().map(String::valueOf).orElse(null),
                        p.unrealizedLamports().map(String::valueOf).orElse(null),
                        p.valueLamports().isPresent() ? "KNOWN" : "UNKNOWN"))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RealPnl(
                        Long.toString(result.realizedLamports()),
                        result.unrealizedLamports().map(String::valueOf).orElse(null),
                        result.unrealizedLamports().isPresent() ? "KNOWN" : "UNKNOWN",
                        Long.toString(result.feesLamports()),
                        positions));
    }
}
