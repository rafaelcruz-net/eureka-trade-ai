package com.memetrade.txsigner.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;

/**
 * RFC 7807 body of the TxSigner, same shape as the api's: stable {@code type} per code, machine-readable {@code code},
 * {@code correlationId} and, for a refused signature or consent, only the reason code. Never a transaction, a consent
 * text, a key reference nor a stack trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(URI type, String title, int status, String code, String correlationId, String reason) {

    private static final String TYPE_BASE = "https://memetrade.app/problems/";

    public static Problem of(int status, String code, String correlationId, String reason) {
        return new Problem(
                URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')),
                code,
                status,
                code,
                correlationId,
                reason);
    }
}
