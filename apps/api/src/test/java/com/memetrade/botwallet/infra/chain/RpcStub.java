package com.memetrade.botwallet.infra.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback JSON-RPC stub of a Solana provider for the tests (never the real network): answers each method with a
 * recorded {@code result} JSON, or with HTTP 503 while it is down. Counts the calls per method.
 */
final class RpcStub implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Map<String, String> results = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile boolean down;

    RpcStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            calls.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
            byte[] body;
            int status;
            if (down) {
                status = 503;
                body = "{}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                String result = results.getOrDefault(method, "null");
                body = ("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id") + ",\"result\":" + result + "}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    RpcStub answer(String method, String resultJson) {
        results.put(method, resultJson);
        return this;
    }

    void goDown() {
        down = true;
    }

    int calls(String method) {
        AtomicInteger count = calls.get(method);
        return count == null ? 0 : count.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
