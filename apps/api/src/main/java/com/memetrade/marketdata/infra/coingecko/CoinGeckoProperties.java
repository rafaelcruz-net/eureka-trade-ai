package com.memetrade.marketdata.infra.coingecko;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the real source {@code coingecko} (261113-trading-real-bot U2, entities.md CoinGeckoSourceConfig,
 * reliability-design.md and the corrections of infrastructure-specification.md). Bound in the api and the worker: the
 * api reads only the attribution; the worker builds the adapter. The API key is NOT a property: only the worker reads
 * {@code COINGECKO_API_KEY} from its environment ({@link CoinGeckoConfig}). A value out of range stops the process
 * with a message that names the setting, never a value.
 *
 * @param baseUrl HTTPS base of the CoinGecko API ({@code COINGECKO_BASE_URL}); outside the test profile only the hosts
 *     of {@link #ALLOWED_HOSTS} are accepted (security-design.md)
 * @param network GeckoTerminal network id, {@code solana}
 * @param discoveryLimit tokens kept by the discovery, 1 to 200 (BR1.1)
 * @param batchSize mints per price call, 1 to 30 (BR1.3)
 * @param discoveryInterval time between two discoveries (BR1.1, 5 min)
 * @param detailCallsPerMinute detail calls per sliding minute (BR1.3, 2)
 * @param detailRefresh age after which the details of a token are fetched again (BR1.3, 60 min)
 * @param callsPerMinute total calls per sliding minute (BR1.3, 250)
 * @param monthlyCallBudget calls per calendar month in UTC (BR1.3, 450 000)
 * @param callTimeout response time limit of one call (reliability-design.md, 4 s)
 * @param connectTimeout connection time limit (security-design.md, 2 s)
 * @param roundDeadline internal deadline of one round, measured with the injectable clock (18 s)
 * @param lastCallStart no new call is opened after this time from the start of the round (12 s)
 * @param attributionText attribution the provider requires on screen (BR1.8)
 * @param attributionUrl HTTPS link of the attribution (BR1.8)
 * @param apiKeyHeader authentication header of the plan ({@code x-cg-pro-api-key})
 */
@ConfigurationProperties(prefix = "memetrade.market.coingecko")
public record CoinGeckoProperties(
        String baseUrl,
        String network,
        Integer discoveryLimit,
        Integer batchSize,
        Duration discoveryInterval,
        Integer detailCallsPerMinute,
        Duration detailRefresh,
        Integer callsPerMinute,
        Long monthlyCallBudget,
        Duration callTimeout,
        Duration connectTimeout,
        Duration roundDeadline,
        Duration lastCallStart,
        String attributionText,
        String attributionUrl,
        String apiKeyHeader) {

    /** Hosts of the CoinGecko API accepted outside the test profile (security-design.md). */
    public static final Set<String> ALLOWED_HOSTS = Set.of("pro-api.coingecko.com", "api.coingecko.com");

    public static final String DEFAULT_BASE_URL = "https://pro-api.coingecko.com/api/v3";
    public static final String DEFAULT_ATTRIBUTION_TEXT = "Dados: CoinGecko";
    public static final String DEFAULT_ATTRIBUTION_URL = "https://www.coingecko.com/";

    public CoinGeckoProperties {
        baseUrl = text(baseUrl, DEFAULT_BASE_URL);
        network = text(network, "solana");
        discoveryLimit = range(discoveryLimit, 50, 1, 200, "COINGECKO_DISCOVERY_LIMIT");
        batchSize = range(batchSize, 30, 1, 30, "COINGECKO_BATCH_SIZE");
        discoveryInterval = positive(discoveryInterval, Duration.ofMinutes(5), "COINGECKO_DISCOVERY_INTERVAL");
        detailCallsPerMinute = range(detailCallsPerMinute, 2, 0, 250, "COINGECKO_DETAIL_CALLS_PER_MINUTE");
        detailRefresh = positive(detailRefresh, Duration.ofMinutes(60), "COINGECKO_DETAIL_REFRESH");
        callsPerMinute = range(callsPerMinute, 250, 1, 10_000, "COINGECKO_CALLS_PER_MINUTE");
        if (monthlyCallBudget == null) {
            monthlyCallBudget = 450_000L;
        }
        if (monthlyCallBudget < 1) {
            throw invalid("COINGECKO_MONTHLY_CALL_BUDGET", "a positive number of calls");
        }
        callTimeout = positive(callTimeout, Duration.ofSeconds(4), "COINGECKO_CALL_TIMEOUT");
        connectTimeout = positive(connectTimeout, Duration.ofSeconds(2), "COINGECKO_CONNECT_TIMEOUT");
        roundDeadline = positive(roundDeadline, Duration.ofSeconds(18), "COINGECKO_ROUND_DEADLINE");
        lastCallStart = positive(lastCallStart, Duration.ofSeconds(12), "COINGECKO_LAST_CALL_START");
        if (lastCallStart.compareTo(roundDeadline) > 0) {
            throw invalid("COINGECKO_LAST_CALL_START", "at most the round deadline");
        }
        attributionText = text(attributionText, DEFAULT_ATTRIBUTION_TEXT);
        attributionUrl = text(attributionUrl, DEFAULT_ATTRIBUTION_URL);
        if (!attributionUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw invalid("COINGECKO_ATTRIBUTION_URL", "an https link");
        }
        apiKeyHeader = text(apiKeyHeader, "x-cg-pro-api-key");
        if (!network.matches("[a-z0-9-]{1,32}")) {
            throw invalid("COINGECKO_NETWORK", "a network id of lowercase letters, digits and dashes");
        }
    }

    /** Every default (the values of the design). */
    public static CoinGeckoProperties defaults() {
        return new CoinGeckoProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The same settings with another base URL (tests point it to the WireMock double). */
    public CoinGeckoProperties withBaseUrl(String url) {
        return new CoinGeckoProperties(
                url,
                network,
                discoveryLimit,
                batchSize,
                discoveryInterval,
                detailCallsPerMinute,
                detailRefresh,
                callsPerMinute,
                monthlyCallBudget,
                callTimeout,
                connectTimeout,
                roundDeadline,
                lastCallStart,
                attributionText,
                attributionUrl,
                apiKeyHeader);
    }

    /**
     * Outside the test profile the base URL must be HTTPS on a CoinGecko host (NFR1.2): a typo or a hostile value never
     * sends the API key elsewhere.
     */
    public void checkProductionBaseUrl() {
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            throw invalid("COINGECKO_BASE_URL", "an https URL of a CoinGecko host");
        }
        String rest = lower.substring("https://".length());
        int end = rest.indexOf('/');
        String host = end < 0 ? rest : rest.substring(0, end);
        if (!ALLOWED_HOSTS.contains(host)) {
            throw invalid("COINGECKO_BASE_URL", "an https URL of a CoinGecko host");
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Integer range(Integer value, int fallback, int min, int max, String variable) {
        int actual = value == null ? fallback : value;
        if (actual < min || actual > max) {
            throw invalid(variable, "between " + min + " and " + max);
        }
        return actual;
    }

    private static Duration positive(Duration value, Duration fallback, String variable) {
        Duration actual = value == null ? fallback : value;
        if (actual.isNegative() || actual.isZero()) {
            throw invalid(variable, "a positive duration");
        }
        return actual;
    }

    static IllegalArgumentException invalid(String variable, String expected) {
        return new IllegalArgumentException(variable + " is out of range: expected " + expected);
    }
}
