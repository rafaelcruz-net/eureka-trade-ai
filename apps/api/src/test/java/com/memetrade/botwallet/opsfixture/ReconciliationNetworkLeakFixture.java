package com.memetrade.botwallet.opsfixture;

import java.net.http.HttpClient;

/** TEST-ONLY: a U5 operation class that opens its own HTTP client, which RealOpsArchitectureTest must refuse. */
public final class ReconciliationNetworkLeakFixture {

    public HttpClient client() {
        return HttpClient.newHttpClient();
    }
}
