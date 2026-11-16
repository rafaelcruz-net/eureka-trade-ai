package com.memetrade.botwallet.infra.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.domain.CiResult;
import com.memetrade.botwallet.domain.CiStatus;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 261113-trading-real-bot U5 (BR1.4, U5-NFR1.1, security-design.md "CI"): the CI of the exact commit of the image, read
 * from the REST API of the GitHub: {@code GET /repos/{owner}/{repo}/actions/workflows/ci.yml/runs?head_sha=<sha>
 * &branch=main&event=push}, of which only {@code id}, {@code status} and {@code conclusion} are read. One run concluded
 * {@code success} is green (a {@code cancelled} one, left by {@code cancel-in-progress}, is ignored); no success is red;
 * a network or HTTP error, a body that is not the expected JSON or 5 s without an answer is unreachable.
 *
 * <p>The token is a fine-grained, read-only "Actions: read" token of this repository, only in the worker. It goes only
 * in the {@code Authorization} header of each request; it is never logged, never in an exception message, never in
 * {@link #toString()}.
 */
public final class GitHubCiStatus implements CiStatus {

    private static final Logger log = LoggerFactory.getLogger(GitHubCiStatus.class);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{7,64}$");

    private final RestClient client;
    private final String path;
    private final String token;

    private GitHubCiStatus(RestClient client, String path, String token) {
        this.client = client;
        this.path = path;
        this.token = token;
    }

    /**
     * @param apiUrl the base URL of the API ({@code https://api.github.com}; a stub in the tests)
     * @param repository {@code owner/repo}
     * @param workflow the file of the workflow ({@code ci.yml})
     * @param timeout the time limit of a call (5 s)
     */
    public static GitHubCiStatus create(
            String apiUrl, String repository, String workflow, String token, Duration timeout) {
        Objects.requireNonNull(apiUrl, "apiUrl");
        String[] parts = repository == null ? new String[0] : repository.trim().split("/", -1);
        boolean valid = parts.length == 2
                && NAME.matcher(parts[0]).matches()
                && NAME.matcher(parts[1]).matches()
                && workflow != null
                && NAME.matcher(workflow).matches();
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient client = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        String path = valid ? "/repos/" + parts[0] + "/" + parts[1] + "/actions/workflows/" + workflow + "/runs" : null;
        return new GitHubCiStatus(client, path, token == null ? "" : token.trim());
    }

    @Override
    public CiResult check(String commitSha) {
        if (commitSha == null || !SHA.matcher(commitSha.trim()).matches() || path == null || token.isEmpty()) {
            return CiResult.unreachable();
        }
        JsonNode runs;
        try {
            runs = client.get()
                    .uri(builder -> builder.path(path)
                            .queryParam("head_sha", commitSha.trim())
                            .queryParam("branch", "main")
                            .queryParam("event", "push")
                            .queryParam("per_page", 30)
                            .build())
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException failure) {
            log.warn("The CI of the commit was not read: {}", failure.getClass().getSimpleName());
            return CiResult.unreachable();
        }
        if (runs == null || !runs.path("workflow_runs").isArray()) {
            log.warn("The CI answered without the list of runs");
            return CiResult.unreachable();
        }
        for (JsonNode run : runs.path("workflow_runs")) {
            if ("completed".equals(run.path("status").asText())
                    && "success".equals(run.path("conclusion").asText())) {
                return CiResult.green(run.path("id").asText());
            }
        }
        return CiResult.red();
    }

    @Override
    public String toString() {
        return "GitHubCiStatus[path=" + path + "]";
    }
}
