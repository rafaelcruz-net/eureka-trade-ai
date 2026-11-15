package com.memetrade.marketdata.infra;

import java.net.URI;

/**
 * Test-only sample for {@code ArchitectureTest}: a market class OUTSIDE {@code marketdata.infra.coingecko} that uses
 * {@code java.net}. The network rule must refuse it (NFR1.4). Never part of the production classes.
 */
public final class NetworkUsingFixture {

    private NetworkUsingFixture() {}

    public static URI provider() {
        return URI.create("https://example.invalid/");
    }
}
