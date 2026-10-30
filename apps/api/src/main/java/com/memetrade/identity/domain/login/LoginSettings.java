package com.memetrade.identity.domain.login;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Login settings read from configuration by the infra layer and handed to the domain. */
public record LoginSettings(URI guestAppOrigin, String chain, Duration challengeTtl, Set<String> adminWalletAddresses) {

    /** Domain shown in the message: host and port of the guest app origin. */
    public String domain() {
        return guestAppOrigin.getPort() < 0
                ? guestAppOrigin.getHost()
                : guestAppOrigin.getHost() + ":" + guestAppOrigin.getPort();
    }

    public String uri() {
        return guestAppOrigin.toString();
    }
}
