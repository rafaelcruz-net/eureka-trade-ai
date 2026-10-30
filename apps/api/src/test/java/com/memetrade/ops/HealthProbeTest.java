package com.memetrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Uses a throw-away HTTP server on the loopback interface only (no external network). */
@Tag("u1")
class HealthProbeTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exitsZeroOnlyWhenReadinessAnswers200() throws Exception {
        URI url = serve(200);

        assertThat(HealthProbe.probe(url, Duration.ofSeconds(2))).isZero();
    }

    @Test
    void exitsOneWhenReadinessIsDown() throws Exception {
        URI url = serve(503);

        assertThat(HealthProbe.probe(url, Duration.ofSeconds(2))).isEqualTo(1);
    }

    @Test
    void exitsOneWhenNothingListens() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        assertThat(HealthProbe.probe(URI.create("http://127.0.0.1:" + freePort + "/x"), Duration.ofSeconds(1)))
                .isEqualTo(1);
    }

    @Test
    void theDefaultTargetIsTheReadinessEndpointOnTheManagementPort() {
        assertThat(HealthProbe.DEFAULT_URL.toString()).isEqualTo("http://127.0.0.1:8081/actuator/health/readiness");
    }

    private URI serve(int status) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ready");
    }
}
