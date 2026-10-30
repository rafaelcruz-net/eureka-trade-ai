package com.memetrade.shared.web;

import com.memetrade.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Port that audits every 403 response as {@code REQUEST_FORBIDDEN} with route, role and reason (BR1.7). Defined in
 * {@code shared} and implemented by the identity module, which knows the principal, to avoid a module cycle.
 */
public interface ForbiddenRequestRecorder {

    void record(HttpServletRequest request, ErrorCode code, String reason);
}
