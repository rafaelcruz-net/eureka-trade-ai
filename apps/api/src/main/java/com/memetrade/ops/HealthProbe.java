package com.memetrade.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Container health check of the API image (infrastructure-specification.md): the slim JRE image has no curl or
 * wget, so this tiny JDK-only program calls the readiness endpoint on the management port and exits 0 only on
 * HTTP 200. Packaged in the application JAR.
 */
public final class HealthProbe {

    static final URI DEFAULT_URL = URI.create("http://127.0.0.1:8081/actuator/health/readiness");

    private HealthProbe() {}

    public static void main(String[] args) {
        URI url = args.length > 0 ? URI.create(args[0]) : DEFAULT_URL;
        System.exit(probe(url, Duration.ofSeconds(3)));
    }

    /** 0 when the endpoint answers HTTP 200, 1 for any other status, timeout or connection error. */
    static int probe(URI url, Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest request = HttpRequest.newBuilder(url).timeout(timeout).GET().build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? 0 : 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (java.io.IOException unreachable) {
            return 1;
        }
    }
}
