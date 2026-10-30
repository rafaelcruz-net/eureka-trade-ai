package com.memetrade.scanner.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.scanner.domain.ScannerService;
import com.memetrade.scanner.web.ScannerDtos.ScannerPageDto;
import com.memetrade.shared.error.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scanner list of C2 in U1, authenticated, without filters and without risk. Market data is global: the principal
 * is required for access but no tenant data is read, and any {@code tenantId} sent by the client is ignored (BR2.1).
 */
@RestController
public class ScannerController {

    private final ScannerService scanner;
    private final AuthContext auth;
    private final MeterRegistry registry;

    public ScannerController(ScannerService scanner, AuthContext auth, MeterRegistry registry) {
        this.scanner = scanner;
        this.auth = auth;
        this.registry = registry;
    }

    @Operation(
            operationId = "listScannerTokens",
            summary = "Lista paginada com ordem determinística e desempate estável (FR5.1)",
            security = @SecurityRequirement(name = "accessToken"))
    @GetMapping("/api/v1/scanner/tokens")
    public ScannerPageDto list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        auth.current();
        if (limit < 1 || limit > 100) {
            throw ApiException.validation("limit", "Must be between 1 and 100.");
        }
        try {
            return ScannerPageDto.of(scanner.list(Optional.ofNullable(cursor), limit));
        } catch (DataAccessException failure) {
            Counter.builder("scanner_list_errors_total")
                    .tag("type", "database")
                    .register(registry)
                    .increment();
            throw failure;
        }
    }
}
