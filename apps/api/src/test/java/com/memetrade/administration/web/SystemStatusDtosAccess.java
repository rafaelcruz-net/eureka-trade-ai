package com.memetrade.administration.web;

import com.memetrade.administration.domain.SystemStatus;

/** Test access to the package-private mapping of the system status answer. */
public final class SystemStatusDtosAccess {

    private SystemStatusDtosAccess() {}

    public static SystemStatusDtos.SystemStatusView view(SystemStatus status) {
        return SystemStatusDtos.SystemStatusView.of(status);
    }
}
