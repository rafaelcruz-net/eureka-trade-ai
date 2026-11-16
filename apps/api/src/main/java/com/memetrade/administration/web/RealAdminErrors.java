package com.memetrade.administration.web;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.ProblemDetailsHandler;
import com.memetrade.shared.error.ProblemResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 261113-trading-real-bot U5 (R4, team.md "Erros da API"): the refusals of the administration of the REAL become the
 * common RFC 7807 answer with their stable code ({@code FOUNDER_FIRST}, {@code PILOT_FULL}, {@code CAP_ONLY_LOWERED},
 * {@code DIVERGENCE_NOT_OPEN}, {@code COMPENSATION_MISMATCH}, {@code RESTORE_NOT_CLEAN}...), through the central handler
 * so every refusal is audited like the others; the lock of the person not obtained in time is 503 and may be repeated
 * with the same Idempotency-Key.
 */
@RestControllerAdvice(assignableTypes = RealAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ProcessRole(ProcessRole.Kind.API)
public class RealAdminErrors {

    private final ProblemDetailsHandler problems;

    public RealAdminErrors(ProblemDetailsHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler(RealAdminRefused.class)
    public ResponseEntity<ProblemResponse> refused(RealAdminRefused refused, HttpServletRequest request) {
        return problems.handleApi(new ApiException(code(refused), refused.code()), request);
    }

    @ExceptionHandler(RealGateUnavailable.class)
    public ResponseEntity<ProblemResponse> lockNotObtained(
            RealGateUnavailable unavailable, HttpServletRequest request) {
        return problems.handleApi(
                new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The lock of the person was not obtained; repeat."),
                request);
    }

    static ErrorCode code(RealAdminRefused refused) {
        return switch (refused.code()) {
            case "VALIDATION_FAILED" -> ErrorCode.UNPROCESSABLE_FIELDS;
            case "NOT_FOUND" -> ErrorCode.NOT_FOUND;
            default -> ErrorCode.valueOf(refused.code());
        };
    }
}
