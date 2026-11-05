package com.memetrade.bots.web;

import com.memetrade.bots.application.BotNotFoundException;
import com.memetrade.bots.application.BotTechnicalFailure;
import com.memetrade.bots.application.BotValidationException;
import com.memetrade.bots.domain.BotRuleViolation;
import com.memetrade.bots.domain.Violation;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import com.memetrade.shared.error.ProblemDetailsHandler;
import com.memetrade.shared.error.ProblemResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the refusals of U5 to the problems of C4 (contract-summary.md "Códigos de erro de domínio") through the common
 * handler, so every answer has the stable type, the code and the correlation id, and never SQL nor a stack trace:
 * not found 404, validation 422 with the fields (LIMIT_INCOHERENT, VIRTUAL_BALANCE_ABOVE_CAP or VALIDATION_FAILED), a
 * refused transition 409 with its code, a technical failure 503 "tentar de novo".
 */
@RestControllerAdvice(basePackageClasses = BotController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ProcessRole(ProcessRole.Kind.API)
public class BotWebErrors {

    private final ProblemDetailsHandler problems;

    public BotWebErrors(ProblemDetailsHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler(BotNotFoundException.class)
    public ResponseEntity<ProblemResponse> notFound(BotNotFoundException failure, HttpServletRequest request) {
        return problems.handleApi(new ApiException(ErrorCode.NOT_FOUND, "Resource not found."), request);
    }

    @ExceptionHandler(BotValidationException.class)
    public ResponseEntity<ProblemResponse> invalid(BotValidationException failure, HttpServletRequest request) {
        List<Violation> violations = failure.violations();
        ErrorCode code = violations.stream().anyMatch(v -> v.code() == Violation.Code.LIMIT_INCOHERENT)
                ? ErrorCode.LIMIT_INCOHERENT
                : violations.stream().anyMatch(v -> v.code() == Violation.Code.VIRTUAL_BALANCE_ABOVE_CAP)
                        ? ErrorCode.VIRTUAL_BALANCE_ABOVE_CAP
                        : ErrorCode.BOT_VALIDATION_FAILED;
        List<FieldViolation> fields = violations.stream()
                .map(v -> new FieldViolation(v.field(), v.message()))
                .toList();
        return problems.handleApi(
                new ApiException(code, "One or more fields are invalid.", fields, null, Map.of()), request);
    }

    @ExceptionHandler(BotRuleViolation.class)
    public ResponseEntity<ProblemResponse> refused(BotRuleViolation failure, HttpServletRequest request) {
        ErrorCode code =
                switch (failure.code()) {
                    case INVALID_TRANSITION -> ErrorCode.INVALID_TRANSITION;
                    case ACTIVE_BOT_LIMIT -> ErrorCode.ACTIVE_BOT_LIMIT;
                    case PAUSED_BY_SYSTEM -> ErrorCode.PAUSED_BY_SYSTEM;
                    case DAILY_LOSS_LIMIT_REACHED -> ErrorCode.DAILY_LOSS_LIMIT_REACHED;
                };
        return problems.handleApi(
                new ApiException(
                        code, code.title() + ".", List.of(), failure.value().orElse(null), Map.of()),
                request);
    }

    @ExceptionHandler(BotTechnicalFailure.class)
    public ResponseEntity<ProblemResponse> technical(BotTechnicalFailure failure, HttpServletRequest request) {
        return problems.handleApi(
                new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable."), request);
    }

    @ExceptionHandler({
        JdbcBotRepository.InvalidCursorException.class,
        JdbcDecisionRepository.InvalidDecisionCursorException.class
    })
    public ResponseEntity<ProblemResponse> cursor(RuntimeException failure, HttpServletRequest request) {
        return problems.handleApi(ApiException.validation("cursor", "Cursor inválido."), request);
    }
}
