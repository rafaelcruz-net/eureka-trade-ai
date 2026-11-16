package com.memetrade.botwallet.infra.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.memetrade.botwallet.domain.CiResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

/**
 * Step 4 of the U5, written BEFORE the code (BR1.4, S4, S29, U5-NFR1.1, U5-NFR4.3, security-design.md "CI"): the read of
 * the workflow {@code ci} of the exact commit against recorded answers of the GitHub REST API served by WireMock (no
 * network, no real token). One run concluded {@code success} is green; cancelled, in progress, failed or absent is red;
 * a 5xx, a 4xx or a timeout is unreachable. The token goes only in the {@code Authorization} header and never into a
 * log or a result.
 */
@Tag("u5-real-operations")
class GitHubCiStatusTest {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PATH = "/repos/example/repo/actions/workflows/ci.yml/runs";
    private static final String TOKEN = "test-only-actions-token-0000";

    private GitHubCiStatus status(Duration timeout) {
        return GitHubCiStatus.create(wire.baseUrl(), "example/repo", "ci.yml", TOKEN, timeout);
    }

    private static String recorded(String name) throws IOException {
        try (var in = GitHubCiStatusTest.class.getResourceAsStream("/github-ci/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void answer(String file) throws IOException {
        wire.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(recorded(file))));
    }

    @Test
    void oneSuccessfulRunOfTheCommitIsGreenWithItsRunAndCancelledRunsAreIgnored() throws IOException {
        answer("runs-success.json");

        CiResult result = status(Duration.ofSeconds(5)).check(SHA);

        assertThat(result.status()).isEqualTo(CiResult.Status.GREEN);
        assertThat(result.runRef()).contains("9100000001");
        wire.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("head_sha", equalTo(SHA))
                .withQueryParam("branch", equalTo("main"))
                .withQueryParam("event", equalTo("push"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json")));
    }

    @Test
    void onlyCancelledRunsAreRed() throws IOException {
        answer("runs-cancelled.json");

        assertThat(status(Duration.ofSeconds(5)).check(SHA).status()).isEqualTo(CiResult.Status.RED);
    }

    @Test
    void aRunStillInProgressIsRedAndCheckedAgainLater() throws IOException {
        answer("runs-in-progress.json");

        assertThat(status(Duration.ofSeconds(5)).check(SHA).status()).isEqualTo(CiResult.Status.RED);
    }

    @Test
    void aFailedRunIsRed() throws IOException {
        answer("runs-failure.json");

        assertThat(status(Duration.ofSeconds(5)).check(SHA).status()).isEqualTo(CiResult.Status.RED);
    }

    /** S29: the CI of main was green on an older commit; the commit of the image has no run: red. */
    @Test
    void noRunOfTheCommitOfTheImageIsRedEvenWhenAnOlderCommitWasGreen() throws IOException {
        answer("runs-absent.json");

        CiResult result = status(Duration.ofSeconds(5)).check(SHA);

        assertThat(result.status()).isEqualTo(CiResult.Status.RED);
        assertThat(result.runRef()).isEmpty();
    }

    @Test
    void aServerErrorIsUnreachable() {
        wire.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(502)));

        assertThat(status(Duration.ofSeconds(5)).check(SHA).status()).isEqualTo(CiResult.Status.UNREACHABLE);
    }

    @Test
    void aRefusedTokenIsUnreachableNeverRed() {
        wire.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(status(Duration.ofSeconds(5)).check(SHA).status()).isEqualTo(CiResult.Status.UNREACHABLE);
    }

    @Test
    void anAnswerAfterTheTimeoutIsUnreachable() throws IOException {
        wire.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(recorded("runs-success.json"))
                        .withFixedDelay(1_500)));

        assertThat(status(Duration.ofMillis(300)).check(SHA).status()).isEqualTo(CiResult.Status.UNREACHABLE);
    }

    @Test
    void aBodyThatIsNotTheExpectedJsonIsUnreachable() {
        wire.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not json")));

        assertThat(status(Duration.ofSeconds(5)).check(SHA).status()).isEqualTo(CiResult.Status.UNREACHABLE);
    }

    @Test
    void anImageWithoutItsShaIsUnreachableWithoutAnyCall() {
        assertThat(status(Duration.ofSeconds(5)).check("").status()).isEqualTo(CiResult.Status.UNREACHABLE);
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void theTokenNeverReachesALogEvenAtDebugNorTheDescriptionOfTheAdapter() throws IOException {
        answer("runs-success.json");
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        root.setLevel(Level.TRACE);
        try {
            GitHubCiStatus adapter = status(Duration.ofSeconds(5));
            adapter.check(SHA);
            wire.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
            adapter.check(SHA);

            assertThat(adapter.toString()).doesNotContain(TOKEN);
        } finally {
            root.setLevel(previous);
            root.detachAppender(captured);
        }

        assertThat(captured.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(TOKEN));
    }
}
