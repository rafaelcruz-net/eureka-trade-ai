package com.memetrade.txsigner.web;

import com.memetrade.txsigner.domain.SignerException;
import com.memetrade.txsigner.domain.kms.KmsRefusedException;
import com.memetrade.txsigner.domain.kms.KmsUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every error to the RFC 7807 body: domain refusals keep their code (4xx), KMS outages are 503
 * UPSTREAM_UNAVAILABLE and unexpected failures are 500 logged by exception type only. Never a stack trace, a message
 * of the KMS, a consent nor a transaction in the answer.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(SignerException.class)
    ResponseEntity<Problem> signer(SignerException refused) {
        return InternalController.problem(
                refused.error().status(), refused.error().name(), CorrelationIdFilter.current(), null);
    }

    @ExceptionHandler(KmsUnavailableException.class)
    ResponseEntity<Problem> kmsDown(KmsUnavailableException down) {
        LOG.warn("KMS unavailable: {}", down.getMessage());
        return InternalController.problem(503, "UPSTREAM_UNAVAILABLE", CorrelationIdFilter.current(), null);
    }

    @ExceptionHandler(KmsRefusedException.class)
    ResponseEntity<Problem> kmsRefused(KmsRefusedException refused) {
        LOG.error("KMS refused a call: {}", refused.getMessage());
        return InternalController.problem(503, "UPSTREAM_UNAVAILABLE", CorrelationIdFilter.current(), null);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        NumberFormatException.class,
        HttpMediaTypeNotSupportedException.class
    })
    ResponseEntity<Problem> invalid(Exception invalid) {
        return InternalController.problem(400, "VALIDATION_FAILED", CorrelationIdFilter.current(), null);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Problem> notFound(Exception missing) {
        return InternalController.problem(404, "NOT_FOUND", CorrelationIdFilter.current(), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpected(Exception failure) {
        LOG.error("Unexpected failure: {}", failure.getClass().getName());
        return InternalController.problem(500, "INTERNAL_ERROR", CorrelationIdFilter.current(), null);
    }
}
