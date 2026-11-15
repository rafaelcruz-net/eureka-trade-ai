package com.memetrade.botwallet.web;

import com.memetrade.botwallet.domain.BotWalletException;
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
 * Maps the domain refusals of the bot wallet to the common RFC 7807 answer (code, correlationId), through the central
 * handler so every 403 is audited like the others. INSUFFICIENT_FREE_BALANCE names the maximum withdrawable (BR4.5).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class BotWalletErrors {

    private final ProblemDetailsHandler problems;

    public BotWalletErrors(ProblemDetailsHandler problems) {
        this.problems = problems;
    }

    @ExceptionHandler(BotWalletException.class)
    public ResponseEntity<ProblemResponse> refused(BotWalletException refused, HttpServletRequest request) {
        String detail = refused.maxWithdrawableLamports() == null
                ? refused.error().name()
                : "Maximum withdrawable: " + refused.maxWithdrawableLamports() + " lamports.";
        return problems.handleApi(
                new ApiException(ErrorCode.valueOf(refused.error().name()), detail), request);
    }
}
