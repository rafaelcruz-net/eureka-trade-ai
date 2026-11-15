package com.memetrade.realexecution.infra;

import java.net.http.HttpClient;

/** TEST ONLY: a REAL execution class outside {@code infra.jupiter} that reaches the network (must fail the rule). */
public final class NetworkLeakFixture {

    private NetworkLeakFixture() {}

    static HttpClient client() {
        return HttpClient.newHttpClient();
    }
}
