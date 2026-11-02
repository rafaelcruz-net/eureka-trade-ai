package com.memetrade.marketdata.api;

/**
 * Category of a source failure, stored with a FAILED or DEGRADED source and used in metrics and logs; never the error
 * text or the response body (entities.md, NFR9.6).
 */
public enum FailureCategory {
    /** The call did not answer within the time limit. */
    TIMEOUT,
    /** Network error or an error answer (5xx, 429, or any other failure of the call). */
    ERROR_RESPONSE,
    /** The answer could not be used (invalid payload or a field refused by the validation at the port). */
    INVALID_PAYLOAD
}
