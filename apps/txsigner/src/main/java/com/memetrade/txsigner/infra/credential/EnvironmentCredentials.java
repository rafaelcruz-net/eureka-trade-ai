package com.memetrade.txsigner.infra.credential;

import java.util.List;

/** Local credential (Compose): {@code TXSIGNER_SERVICE_TOKEN} and, during a rotation, {@code _PREVIOUS}. */
public final class EnvironmentCredentials implements ServiceCredentials {

    private final List<String> tokens;

    public EnvironmentCredentials(String current, String previous) {
        this.tokens = java.util.stream.Stream.of(current, previous)
                .filter(token -> token != null && !token.isBlank())
                .toList();
    }

    @Override
    public List<String> acceptedTokens() {
        return tokens;
    }
}
