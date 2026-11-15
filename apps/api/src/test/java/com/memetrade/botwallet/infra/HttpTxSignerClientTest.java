package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.TxSignerPort;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 18, infrastructure tested after the code (contract R1, reliability-design.md): the client sends the service
 * token and the withdrawal context with the fee, reads a refusal reason, repeats a technical failure once with the same
 * request id, and opens its circuit only on technical failures. Loopback stub, never the real TxSigner.
 */
class HttpTxSignerClientTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> answer = new AtomicReference<>("{}");
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private HttpTxSignerClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new HttpTxSignerClient(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "service-token-of-the-test",
                Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aWalletCreationSendsTheTokenAndReadsOnlyTheAddress() {
        status.set(201);
        answer.set("{\"botWalletAddress\":\"BotAddr\"}");

        String address = client.createWallet(UUID.randomUUID(), "Main", "message", "signature");

        assertThat(address).isEqualTo("BotAddr");
        assertThat(lastAuthorization.get()).isEqualTo("Bearer service-token-of-the-test");
    }

    @Test
    void aWithdrawalCarriesItsAmountAndFeeAsTextAndASignatureIsRead() {
        answer.set("{\"signature\":\"Sig1\",\"signedTransactionBase64\":\"AQID\"}");

        TxSignerPort.SignOutcome outcome = client.signWithdrawal(UUID.randomUUID(), "Bot", "AAAA", 500L, 5_001L);

        assertThat(outcome.signed()).isTrue();
        assertThat(outcome.signedTransactionBase64()).isEqualTo("AQID");
        assertThat(lastBody.get())
                .contains("\"operationKind\":\"WITHDRAWAL\"")
                .contains("\"inputLamports\":\"500\"")
                .contains("\"feeLamports\":\"5001\"");
    }

    @Test
    void aRefusalGivesItsReasonAndIsNotRetried() {
        status.set(422);
        answer.set("{\"code\":\"SIGNING_POLICY_REFUSED\",\"reason\":\"DESTINATION_NOT_ALLOWED\"}");

        TxSignerPort.SignOutcome outcome = client.signWithdrawal(UUID.randomUUID(), "Bot", "AAAA", 1L, 1L);

        assertThat(outcome.refusal()).isEqualTo("DESTINATION_NOT_ALLOWED");
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(() -> client.raise(UUID.randomUUID(), "Bot", ConsentKind.RAISE_CAP, "m", "s"))
                .isInstanceOfSatisfying(TxSignerPort.SignerRefusedException.class, e -> assertThat(e.code())
                        .isEqualTo("DESTINATION_NOT_ALLOWED"));
    }

    @Test
    void aTechnicalFailureIsRepeatedOnceAndThenReportedAsUnavailable() {
        status.set(503);

        assertThatThrownBy(() -> client.lowerCap(UUID.randomUUID(), "Bot", 1L))
                .isInstanceOf(TxSignerPort.SignerUnavailableException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void onlyTechnicalFailuresOpenTheCircuit() {
        status.set(422);
        answer.set("{\"code\":\"CONSENT_INVALID\"}");
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.createWallet(UUID.randomUUID(), "M", "m", "s"))
                    .isInstanceOf(TxSignerPort.SignerRefusedException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);

        status.set(500);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.createWallet(UUID.randomUUID(), "M", "m", "s"))
                    .isInstanceOf(TxSignerPort.SignerUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
