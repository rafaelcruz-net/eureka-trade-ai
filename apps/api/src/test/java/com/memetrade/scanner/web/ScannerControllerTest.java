package com.memetrade.scanner.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery.ListedToken;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.scanner.FakeListing;
import com.memetrade.scanner.domain.ScannerService;
import com.memetrade.shared.error.ProblemDetailsHandler;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The JSON of C2 for the list: text decimals, explicit unknown, validation, 401, 503 with sources, risk. */
@Tag("u1")
class ScannerControllerTest {

    private final MutableClock clock = new MutableClock();
    private final FakeListing listing = new FakeListing();
    private Optional<Principal> principal =
            Optional.of(new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER));
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuthContext auth = new AuthContext() {
            @Override
            public Principal current() {
                return principal.orElseThrow(UnauthenticatedException::new);
            }

            @Override
            public Optional<Principal> optional() {
                return principal;
            }
        };
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        ScannerService service = new ScannerService(
                listing, token -> new AssessmentResult.Unavailable(UnavailableReason.INSUFFICIENT_COVERAGE), clock);
        mvc = MockMvcBuilders.standaloneSetup(new ScannerController(service, auth, new SimpleMeterRegistry()))
                .setControllerAdvice(new ProblemDetailsHandler((request, code, reason) -> {}))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        listing.add("MintOne", clock.instant(), Optional.of(clock.instant()), FakeListing.available(45, "MEDIUM"));
    }

    @Test
    void decimalsTravelAsTextAndValuesCarrySourceAndTime() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].price.status").value("KNOWN"))
                .andExpect(jsonPath("$.items[0].price.value").value("0.00001234"))
                .andExpect(jsonPath("$.items[0].price.source.simulated").value(true))
                .andExpect(jsonPath("$.items[0].price.observedAt").value("2026-10-28T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].liquidity.value").value("0"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    @Test
    void unknownIsAnExplicitStatusWithReasonAndNoValue() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(jsonPath("$.items[0].holders.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[0].holders.reason").value("NOT_PROVIDED"))
                .andExpect(jsonPath("$.items[0].holders.value").doesNotExist())
                .andExpect(jsonPath("$.items[0].coverage.informed").value(4))
                .andExpect(jsonPath("$.items[0].coverage.total").value(5));
    }

    @Test
    void aTenantIdInTheQueryIsIgnored() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens")
                        .param("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void theLimitMustBeBetween1And100() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/scanner/tokens").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/scanner/tokens").param("limit", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/scanner/tokens").param("limit", "100")).andExpect(status().isOk());
    }

    @Test
    void withoutAPrincipalTheListIsUnauthenticated() throws Exception {
        principal = Optional.empty();

        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anInvalidCursorIs400() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens").param("cursor", "%%%"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("cursor"));
    }

    @Test
    @Tag("u4")
    void theRiskSummaryIsDiscriminatedByStatus() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(jsonPath("$.items[0].risk.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].risk.score").value(45))
                .andExpect(jsonPath("$.items[0].risk.band").value("MEDIUM"));
    }

    @Test
    @Tag("u4")
    void everyInvalidParameterIsListedInOne400() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens")
                        .param("minLiquidity", "-1")
                        .param("maxRiskScore", "101")
                        .param("color", "red"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.length()").value(3));
    }

    @Test
    @Tag("u4")
    void everySourceFailedIs503WithTheStateOfEachSource() throws Exception {
        listing.sources(State.FAILED, State.FAILED);

        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    @Tag("u4")
    void aPendingSourceHasNoLastUpdate() throws Exception {
        listing.sources(State.PENDING);

        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.sources[0].status").value("PENDING"))
                .andExpect(jsonPath("$.sources[0].lastUpdatedAt").doesNotExist());
    }

    @Test
    @Tag("u4")
    void aDivergentFieldCarriesTheOtherOrigins() throws Exception {
        listing.tokens.clear();
        ListedToken base = listing.add("MintTwo", clock.instant(), Optional.empty(), FakeListing.none());
        SourceRef other = new SourceRef("sim-b", "Fonte simulada B", true);
        MarketValue.Known shown = FakeListing.known("1.00", clock.instant());
        MarketValue.Known divergent = new MarketValue.Known(new BigDecimal("1.20"), other, clock.instant(), false);
        Map<MarketField, ResolvedField> fields = new EnumMap<>(base.fields());
        fields.put(MarketField.PRICE, new ResolvedField(shown, true, List.of(shown, divergent)));
        listing.tokens.set(
                0, new ListedToken(base.token(), base.symbol(), base.name(), fields, base.risk(), base.position()));

        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(jsonPath("$.items[0].price.divergence.length()").value(1))
                .andExpect(jsonPath("$.items[0].price.divergence[0].source.sourceId")
                        .value("sim-b"))
                .andExpect(jsonPath("$.items[0].price.divergence[0].value").value("1.20"))
                .andExpect(jsonPath("$.items[0].liquidity.divergence").doesNotExist())
                .andExpect(jsonPath("$.items[0].risk.reason").value("INSUFFICIENT_COVERAGE"));
    }
}
