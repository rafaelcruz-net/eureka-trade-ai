package com.memetrade.identity.api;

import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;

/** No authenticated session for the current request: {@code 401 UNAUTHENTICATED}. */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }
}
