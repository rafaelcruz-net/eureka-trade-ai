package com.memetrade.realexecution.infra.jupiter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.memetrade.realexecution.domain.NoRouteException;
import com.memetrade.realexecution.domain.RouterQuote;
import com.memetrade.realexecution.domain.RouterUnavailableException;
import com.memetrade.realexecution.domain.solana.SwapInstruction;
import com.memetrade.shared.solana.Base58Codec;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Step 18 of the U4, written after the code (contract X1, U4-NFR8.7, U4-NFR1.5): the Jupiter adapter against recorded,
 * synthetic answers served by WireMock (no network). The quote keeps the raw answer and refuses an answer for another
 * amount; the instructions come in the order of the message (compute budget, other, setup, swap, cleanup) and the ask
 * caps the priority fee; a technical failure, a refusal, a body that is not JSON or a slow answer is "unavailable".
 */
@Tag("u4-real-execution")
class JupiterClientTest {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String WSOL = "So11111111111111111111111111111111111111112";
    private static final String MINT = Base58Codec.encode(filled(7));
    private static final String USER = Base58Codec.encode(filled(8));
    private static final String PROGRAM = Base58Codec.encode(filled(3));
    private static final String QUOTE = "{\"inputMint\":\"" + WSOL + "\",\"outputMint\":\"" + MINT
            + "\",\"inAmount\":\"50000000\",\"outAmount\":\"1000000\",\"otherAmountThreshold\":\"990000\","
            + "\"slippageBps\":100,\"routePlan\":[{}]}";

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private JupiterClient client(Duration timeout) {
        return new JupiterClient(HttpClient.newHttpClient(), wire.baseUrl() + "/", timeout, 1_000_000L);
    }

    private static String instruction(int tag) {
        return "{\"programId\":\"" + PROGRAM + "\",\"accounts\":[{\"pubkey\":\"" + USER
                + "\",\"isSigner\":true,\"isWritable\":true}],\"data\":\""
                + java.util.Base64.getEncoder().encodeToString(new byte[] {(byte) tag}) + "\"}";
    }

    @Test
    void aQuoteKeepsTheRawAnswerAndAsksTheBoundsOfTheMessage() {
        wire.stubFor(get(urlPathEqualTo("/quote"))
                .withQueryParam("inputMint", equalTo(WSOL))
                .withQueryParam("outputMint", equalTo(MINT))
                .withQueryParam("amount", equalTo("50000000"))
                .withQueryParam("maxAccounts", equalTo("40"))
                .withQueryParam("swapMode", equalTo("ExactIn"))
                .willReturn(okJson(QUOTE)));

        RouterQuote quote = client(Duration.ofSeconds(2)).quote(WSOL, MINT, 50_000_000L, 100, 40);

        assertThat(quote.inAmount()).isEqualTo(50_000_000L);
        assertThat(quote.outAmount()).isEqualTo(1_000_000L);
        assertThat(quote.maxAccounts()).isEqualTo(40);
        assertThat(quote.rawQuote()).contains("otherAmountThreshold");
    }

    @Test
    void aQuoteForAnotherAmountIsRefusedAsUnavailable() {
        wire.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson(QUOTE.replace("50000000", "49000000"))));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).quote(WSOL, MINT, 50_000_000L, 100, 40))
                .isInstanceOf(RouterUnavailableException.class);
    }

    @Test
    void theInstructionsComeInTheOrderOfTheMessageAndTheAskCapsThePriorityFee() {
        wire.stubFor(post(urlPathEqualTo("/swap-instructions"))
                .willReturn(okJson("{\"computeBudgetInstructions\":[" + instruction(1) + "],\"otherInstructions\":["
                        + instruction(2) + "],\"setupInstructions\":[" + instruction(3) + "],\"swapInstruction\":"
                        + instruction(4) + ",\"cleanupInstruction\":" + instruction(5)
                        + ",\"addressLookupTableAddresses\":[]}")));
        RouterQuote quote = new RouterQuote(WSOL, MINT, 50_000_000L, 1_000_000L, 100, 40, QUOTE);

        List<SwapInstruction> instructions = client(Duration.ofSeconds(2)).swapInstructions(quote, USER);

        assertThat(instructions).extracting(i -> (int) i.data()[0]).containsExactly(1, 2, 3, 4, 5);
        assertThat(instructions.get(3).accounts().get(0).signer()).isTrue();
        wire.verify(postRequestedFor(urlPathEqualTo("/swap-instructions"))
                .withRequestBody(matchingJsonPath("$.userPublicKey", equalTo(USER)))
                .withRequestBody(matchingJsonPath(
                        "$.prioritizationFeeLamports.priorityLevelWithMaxLamports.maxLamports", equalTo("1000000")))
                .withRequestBody(matchingJsonPath("$.quoteResponse.outAmount", equalTo("1000000"))));
    }

    @Test
    void anAnswerWithoutTheSwapInstructionIsUnavailable() {
        wire.stubFor(post(urlPathEqualTo("/swap-instructions")).willReturn(okJson("{\"setupInstructions\":[]}")));
        RouterQuote quote = new RouterQuote(WSOL, MINT, 50_000_000L, 1_000_000L, 100, 40, QUOTE);

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).swapInstructions(quote, USER))
                .isInstanceOf(RouterUnavailableException.class);
    }

    @Test
    void aServerFailureATooManyRequestsOrABodyThatIsNotJsonIsUnavailable() {
        JupiterClient client = client(Duration.ofSeconds(2));
        for (int status : new int[] {500, 503, 429}) {
            wire.stubFor(get(urlPathEqualTo("/quote")).willReturn(aResponse().withStatus(status)));
            assertThatThrownBy(() -> client.quote(WSOL, MINT, 50_000_000L, 100, 40))
                    .as("status %d", status)
                    .isInstanceOf(RouterUnavailableException.class);
        }
        wire.stubFor(get(urlPathEqualTo("/quote"))
                .willReturn(aResponse().withStatus(200).withBody("<html>")));
        assertThatThrownBy(() -> client.quote(WSOL, MINT, 50_000_000L, 100, 40))
                .isInstanceOf(RouterUnavailableException.class);
    }

    /**
     * Review (Major 1): any other 4xx (no route, a mint the Jupiter refuses) is a refusal of THIS order, not an outage:
     * it never counts in the circuit, so ten of them in a row leave the circuit closed and the next quote goes through.
     */
    @Test
    void anyOther4xxIsARefusalOfTheRouteAndNeverOpensTheCircuit() {
        JupiterClient client = client(Duration.ofSeconds(2));
        for (int status : new int[] {400, 404, 422}) {
            wire.stubFor(get(urlPathEqualTo("/quote")).willReturn(aResponse().withStatus(status)));
            assertThatThrownBy(() -> client.quote(WSOL, MINT, 50_000_000L, 100, 40))
                    .as("status %d", status)
                    .isInstanceOf(NoRouteException.class);
        }
        wire.stubFor(get(urlPathEqualTo("/quote")).willReturn(aResponse().withStatus(400)));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> client.quote(WSOL, MINT, 50_000_000L, 100, 40))
                    .isInstanceOf(NoRouteException.class);
        }
        wire.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson(QUOTE)));

        assertThat(client.quote(WSOL, MINT, 50_000_000L, 100, 40).outAmount()).isEqualTo(1_000_000L);
        assertThat(client.circuitState()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
    }

    @Test
    void aSlowAnswerIsUnavailableAfterTheTimeout() {
        wire.stubFor(get(urlPathEqualTo("/quote")).willReturn(okJson(QUOTE).withFixedDelay(1_500)));

        assertThatThrownBy(() -> client(Duration.ofMillis(300)).quote(WSOL, MINT, 50_000_000L, 100, 40))
                .isInstanceOf(RouterUnavailableException.class);
    }

    @Test
    void aMintThatIsNotAnAddressIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).quote("not-a-mint", MINT, 1L, 100, 40))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wire.getAllServeEvents()).isEmpty();
    }
}
