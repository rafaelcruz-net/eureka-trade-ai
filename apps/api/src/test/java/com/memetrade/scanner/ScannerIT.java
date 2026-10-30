package com.memetrade.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The scanner list against PostgreSQL with the application migrations. The list is global, so assertions are
 * properties of the order (hold whatever other tests inserted) plus the relative order of this test's own tokens.
 */
class ScannerIT extends AbstractPostgresIT {

    private static final SourceRef SOURCE = new SourceRef("simulated", "Fonte simulada", true);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MarketStore store;

    @Autowired
    private MarketListingQuery listing;

    private String accessToken;

    @BeforeEach
    void signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        accessToken = auth.accessToken(auth.login(wallet));
        store.sourceCollected(SOURCE, clock.instant());
    }

    @Test
    void newestFirstTiesByMintAndUnknownAgeLast() throws Exception {
        Instant created = clock.instant().minus(Duration.ofMinutes(10));
        String older = insert(clock.instant().minus(Duration.ofMinutes(30)));
        String tieB = insert(created, "zz" + SyntheticIds.mint().substring(2));
        String tieA = insert(created, "AA" + SyntheticIds.mint().substring(2));
        String unknown = insert(null);

        List<JsonNode> rows = allRows();
        List<String> mints = rows.stream().map(r -> r.get("mint").asText()).toList();

        assertThat(mints.indexOf(tieA)).isLessThan(mints.indexOf(tieB));
        assertThat(mints.indexOf(tieB)).isLessThan(mints.indexOf(older));
        assertThat(mints.indexOf(older)).isLessThan(mints.indexOf(unknown));
        int firstUnknown = indexOfFirstUnknownAge(rows);
        assertThat(rows.subList(firstUnknown, rows.size()))
                .allSatisfy(r ->
                        assertThat(r.get("ageMinutes").get("status").asText()).isEqualTo("UNKNOWN"));
    }

    @Test
    void cursorPagesAreStableWithoutDuplicatesOrGaps() throws Exception {
        for (int i = 0; i < 7; i++) {
            insert(clock.instant().minus(Duration.ofMinutes(i + 1)));
        }
        List<JsonNode> small = allRows(3);
        List<JsonNode> big = allRows(100);

        assertThat(small.stream().map(r -> r.get("mint").asText()).toList())
                .containsExactlyElementsOf(
                        big.stream().map(r -> r.get("mint").asText()).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void aTokenCreatedAfterTheCutoffOnlyAppearsOnANewFirstPage() throws Exception {
        for (int i = 0; i < 3; i++) {
            insert(clock.instant().minus(Duration.ofMinutes(i + 1)));
        }
        JsonNode first = page(null, 2);
        clock.advance(Duration.ofSeconds(5));
        String late = insertSeenAt(clock.instant(), clock.instant());

        Set<String> following = new HashSet<>();
        String cursor = first.get("nextCursor").asText();
        while (cursor != null) {
            JsonNode next = page(cursor, 2);
            next.get("items").forEach(r -> following.add(r.get("mint").asText()));
            assertThat(next.get("total").asLong()).isEqualTo(first.get("total").asLong());
            cursor = next.get("nextCursor").isNull()
                    ? null
                    : next.get("nextCursor").asText();
        }

        assertThat(following).doesNotContain(late);
        assertThat(page(null, 100).get("items").findValuesAsText("mint")).contains(late);
    }

    @Test
    void anAgeTurningFromUnknownToKnownBetweenPagesIsAccepted() throws Exception {
        String changing = insert(null);
        JsonNode first = page(null, 1);
        store.updateCreationTime(
                new TokenId("solana", changing), Optional.of(clock.instant().minusSeconds(30)));

        List<String> rest = new ArrayList<>();
        String cursor = first.get("nextCursor").isNull()
                ? null
                : first.get("nextCursor").asText();
        while (cursor != null) {
            JsonNode next = page(cursor, 50);
            next.get("items").forEach(r -> rest.add(r.get("mint").asText()));
            cursor = next.get("nextCursor").isNull()
                    ? null
                    : next.get("nextCursor").asText();
        }
        // The token may appear twice or be missing until the next first page (BR3.6); it must never break paging.
        assertThat(rest.stream().filter(changing::equals).count()).isLessThanOrEqualTo(1);
    }

    @Test
    void staleFlipsStrictlyAfterFiveMinutesAndUnknownIsNeverZeroOrNull() throws Exception {
        String mint = insert(clock.instant().minus(Duration.ofMinutes(1)));
        clock.advance(Duration.ofMinutes(5));
        JsonNode atLimit = find(mint);
        clock.advance(Duration.ofSeconds(1));
        JsonNode after = find(mint);

        assertThat(atLimit.get("price").get("stale").asBoolean()).isFalse();
        assertThat(after.get("price").get("stale").asBoolean()).isTrue();
        assertThat(after.get("holders").get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(after.get("holders").has("value")).isFalse();
        assertThat(after.get("liquidity").get("value").asText()).isEqualTo("0");
        assertThat(after.get("coverage").get("informed").asInt()).isEqualTo(4);
        assertThat(after.get("price").get("value").isTextual()).isTrue();
    }

    @Test
    void withoutATokenTheListIs401AndATenantIdIsIgnored() throws Exception {
        assertThat(mvc.perform(get("/api/v1/scanner/tokens"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        MockHttpServletResponse withTenant = mvc.perform(get("/api/v1/scanner/tokens")
                        .header("Authorization", "Bearer " + accessToken)
                        .param("tenantId", UUID.randomUUID().toString())
                        .param("limit", "100"))
                .andReturn()
                .getResponse();
        assertThat(withTenant.getStatus()).isEqualTo(200);
        assertThat(json.readTree(withTenant.getContentAsString()).get("total").asLong())
                .isEqualTo(page(null, 100).get("total").asLong());
    }

    @Test
    void theSimulatedSourceIsListedWithItsStatus() throws Exception {
        JsonNode sources = page(null, 1).get("sources");

        assertThat(sources.findValuesAsText("sourceId")).contains("simulated");
        assertThat(sources.get(0).get("source").get("simulated").asBoolean()).isTrue();
    }

    @Test
    void theP95OfThePageQueryWith500TokensIsBelow150Milliseconds() {
        for (int i = 0; i < 500; i++) {
            insert(i % 25 == 0 ? null : clock.instant().minusSeconds(i * 13L));
        }
        Instant cutoff = clock.instant();
        listing.page(Optional.empty(), cutoff, 50);
        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long start = System.nanoTime();
            listing.page(Optional.empty(), cutoff, 50);
            listing.count(cutoff);
            nanos.add(System.nanoTime() - start);
        }
        nanos.sort(Comparator.naturalOrder());
        long p95Millis = Duration.ofNanos(nanos.get((int) Math.ceil(nanos.size() * 0.95) - 1))
                .toMillis();

        assertThat(p95Millis).as("p95 of page + count (ms)").isLessThan(150);
    }

    private String insert(Instant created) {
        return insert(created, SyntheticIds.mint());
    }

    private String insert(Instant created, String mint) {
        return insertWith(clock.instant().minusSeconds(1), created, mint);
    }

    private String insertSeenAt(Instant seenAt, Instant created) {
        return insertWith(seenAt, created, SyntheticIds.mint());
    }

    private String insertWith(Instant seenAt, Instant created, String mint) {
        TokenId token = new TokenId("solana", mint);
        store.upsertToken(new TokenInfo(token, "TST", null, 6), seenAt);
        Instant observed = clock.instant();
        store.upsertObservation(token, "simulated", MarketField.PRICE, known("0.00001234"), observed);
        store.upsertObservation(token, "simulated", MarketField.LIQUIDITY, known("0"), observed);
        store.upsertObservation(token, "simulated", MarketField.VOLUME_24H, known("100.5"), observed);
        store.upsertObservation(
                token, "simulated", MarketField.HOLDERS, MarketValue.Unknown.notProvided(SOURCE), observed);
        if (created != null) {
            store.upsertObservation(
                    token,
                    "simulated",
                    MarketField.CREATED_AT,
                    known(Long.toString(created.getEpochSecond())),
                    observed);
        }
        store.updateCreationTime(token, Optional.ofNullable(created));
        return mint;
    }

    private MarketValue known(String value) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, clock.instant(), false);
    }

    private JsonNode find(String mint) throws Exception {
        for (JsonNode row : allRows()) {
            if (row.get("mint").asText().equals(mint)) {
                return row;
            }
        }
        throw new AssertionError("token not listed");
    }

    private List<JsonNode> allRows() throws Exception {
        return allRows(100);
    }

    private List<JsonNode> allRows(int limit) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = page(cursor, limit);
            page.get("items").forEach(rows::add);
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asText();
        } while (cursor != null);
        return rows;
    }

    private JsonNode page(String cursor, int limit) throws Exception {
        var request = get("/api/v1/scanner/tokens")
                .header("Authorization", "Bearer " + accessToken)
                .param("limit", Integer.toString(limit));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    private static int indexOfFirstUnknownAge(List<JsonNode> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if ("UNKNOWN".equals(rows.get(i).get("ageMinutes").get("status").asText())) {
                return i;
            }
        }
        return rows.size();
    }
}
