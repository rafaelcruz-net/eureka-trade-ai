package com.memetrade.shared.error;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.shared.web.ForbiddenRequestRecorder;
import com.memetrade.shared.web.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every error to RFC 7807 with a stable {@code type}, {@code code} and {@code correlationId}. Responses never
 * carry a stack trace, SQL or secret; unexpected failures are logged by exception type and correlation id only.
 */
@RestControllerAdvice
public class ProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);
    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType(ProblemWriter.PROBLEM_JSON);

    private final ForbiddenRequestRecorder forbiddenRecorder;

    public ProblemDetailsHandler(ForbiddenRequestRecorder forbiddenRecorder) {
        this.forbiddenRecorder = forbiddenRecorder;
    }

    /** Every 403 is audited as REQUEST_FORBIDDEN unless the domain already wrote its single event (BR1.7). */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemResponse> handleApi(ApiException exception, HttpServletRequest request) {
        if (exception.code().status() == 403 && !exception.isAudited()) {
            forbiddenRecorder.record(request, exception.code(), exception.reason());
        }
        return handleApi(exception);
    }

    ResponseEntity<ProblemResponse> handleApi(ApiException exception) {
        HttpHeaders headers = new HttpHeaders();
        exception.headers().forEach(headers::set);
        return ResponseEntity.status(exception.code().status())
                .headers(headers)
                .contentType(PROBLEM_JSON)
                .body(ProblemResponse.of(exception, CorrelationId.current()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        if (exception.getCause() instanceof UnrecognizedPropertyException unknown) {
            return handleApi(ApiException.validation(unknown.getPropertyName(), "Field is not accepted."));
        }
        if (exception.getCause() instanceof JsonMappingException mapping
                && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().stream()
                    .map(ref -> ref.getFieldName() == null ? "[" + ref.getIndex() + "]" : ref.getFieldName())
                    .collect(Collectors.joining("."));
            return handleApi(ApiException.validation(field, "Field has an invalid value."));
        }
        return handleApi(ApiException.validation("body", "Request body is missing or is not valid JSON."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemResponse> handleInvalidBody(MethodArgumentNotValidException exception) {
        List<FieldViolation> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), messageOf(error.getDefaultMessage())))
                .toList();
        return handleApi(new ApiException(
                ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", fields, null, java.util.Map.of()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemResponse> handleInvalidParameters(HandlerMethodValidationException exception) {
        List<FieldViolation> fields = exception.getParameterValidationResults().stream()
                .map(result -> new FieldViolation(
                        result.getMethodParameter().getParameterName(),
                        messageOf(result.getResolvableErrors().getFirst().getDefaultMessage())))
                .toList();
        return handleApi(new ApiException(
                ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid.", fields, null, java.util.Map.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return handleApi(ApiException.validation(exception.getName(), "Parameter has an invalid value."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
        return handleApi(ApiException.validation(exception.getParameterName(), "Parameter is required."));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        return handleApi(ApiException.validation(exception.getHeaderName(), "Header is required."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemResponse> handleNoResource(NoResourceFoundException exception) {
        return handleApi(new ApiException(ErrorCode.NOT_FOUND, "Resource not found."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemResponse> handleMethod(HttpRequestMethodNotSupportedException exception) {
        return handleApi(new ApiException(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed."));
    }

    @ExceptionHandler({
        CannotGetJdbcConnectionException.class,
        DataAccessResourceFailureException.class,
        QueryTimeoutException.class,
        TransientDataAccessException.class
    })
    public ResponseEntity<ProblemResponse> handleDatabaseUnavailable(Exception exception) {
        log.error("Database unavailable: {}", exception.getClass().getSimpleName());
        return handleApi(new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemResponse> handleUnexpected(Exception exception) {
        // Type only: messages and stack traces may contain SQL or data and never leave the process.
        log.error("Unexpected error: {}", exception.getClass().getName());
        return handleApi(new ApiException(ErrorCode.INTERNAL_ERROR, "Unexpected error."));
    }

    private static String messageOf(String message) {
        return message == null ? "Invalid value." : message;
    }
}
