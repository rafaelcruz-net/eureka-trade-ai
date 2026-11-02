package com.memetrade.scanner.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.RiskAssessmentDetails;
import com.memetrade.scanner.web.AssessmentDtos.AssessmentDto;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import com.memetrade.shared.error.ProblemResponse;
import com.memetrade.shared.web.ProblemWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * F4, {@code GET /api/v1/tokens/{chain}/{mint}/assessment} (C2): the assessment of one token with every factor, its
 * weight, input, source, observation time, stale flag (computed now) and contribution (BR3.9). A token without an
 * observation in the last 24 hours is {@code 404 NOT_FOUND}; the first detail of a new token computes it at once.
 */
@RestController
public class AssessmentController {

    private static final Pattern CHAIN = Pattern.compile("[a-z0-9]{1,64}");
    private static final Pattern MINT = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");

    private final RiskAssessmentDetails details;
    private final AuthContext auth;

    public AssessmentController(RiskAssessmentDetails details, AuthContext auth) {
        this.details = details;
        this.auth = auth;
    }

    @Operation(
            operationId = "getTokenAssessment",
            summary = "Avaliação de risco com fatores, pesos e versão (FR6.1 a FR6.3)",
            security = @SecurityRequirement(name = "accessToken"),
            responses = {
                @ApiResponse(responseCode = "200", description = "Avaliação guardada, em validação"),
                @ApiResponse(
                        responseCode = "404",
                        description = "NOT_FOUND para token sem observação nas últimas 24 horas",
                        content = @Content(
                                mediaType = ProblemWriter.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemResponse.class))),
                @ApiResponse(
                        responseCode = "429",
                        description = "RATE_LIMITED, com Retry-After",
                        content = @Content(
                                mediaType = ProblemWriter.PROBLEM_JSON,
                                schema = @Schema(implementation = ProblemResponse.class)))
            })
    @GetMapping("/api/v1/tokens/{chain}/{mint}/assessment")
    public AssessmentDto detail(@PathVariable String chain, @PathVariable String mint) {
        auth.current();
        List<FieldViolation> violations = new ArrayList<>();
        if (!CHAIN.matcher(chain).matches()) {
            violations.add(new FieldViolation("chain", "Must be a chain identifier."));
        }
        if (!MINT.matcher(mint).matches()) {
            violations.add(new FieldViolation("mint", "Must be a Base58 mint of 32 to 44 characters."));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", violations, null, Map.of());
        }
        return details.detail(new TokenId(chain, mint))
                .map(AssessmentDto::of)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Token not found."));
    }
}
