package com.memetrade.scanner.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.scanner.domain.ScannerService;
import com.memetrade.shared.error.ProblemDetailsHandler;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@Tag("u1")
class ScannerControllerTest {

    private static final SourceRef SOURCE = new SourceRef("simulated", "Fonte simulada", true);

    private final MutableClock clock = new MutableClock();
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
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ScannerController(
                        new ScannerService(new OneToken(), clock), auth, new SimpleMeterRegistry()))
                .setControllerAdvice(new ProblemDetailsHandler((request, code, reason) -> {}))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }

    @Test
    void decimalsTravelAsTextAndValuesCarrySourceAndTime() throws Exception {
        mvc.perform(get("/api/v1/scanner/tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].price.status").value("KNOWN"))
                .andExpect(jsonPath("$.items[0].price.value").value("0.000012345"))
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
                .andExpect(jsonPath("$.items[0].coverage.total").value(5))
                .andExpect(jsonPath("$.items[0].risk").doesNotExist());
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

    private static final class OneToken implements MarketListingQuery {
        @Override
        public ListingPage page(Optional<ListingPosition> after, Instant cutoff, int limit) {
            Map<MarketField, MarketValue> values = new EnumMap<>(MarketField.class);
            Instant at = Instant.parse("2026-10-28T12:00:00Z");
            values.put(MarketField.PRICE, new MarketValue.Known(new BigDecimal("0.000012345"), SOURCE, at, false));
            values.put(MarketField.LIQUIDITY, new MarketValue.Known(BigDecimal.ZERO, SOURCE, at, false));
            values.put(MarketField.VOLUME_24H, new MarketValue.Known(new BigDecimal("1500.25"), SOURCE, at, false));
            values.put(MarketField.HOLDERS, new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)));
            values.put(MarketField.CREATED_AT, new MarketValue.Known(new BigDecimal("1761650000"), SOURCE, at, false));
            return new ListingPage(
                    List.of(new ListedToken(
                            new TokenId("solana", "Mint111"),
                            "PEPE",
                            null,
                            new ListingPosition(0, 0, "Mint111", "solana"),
                            values)),
                    false);
        }

        @Override
        public long count(Instant cutoff) {
            return 1;
        }

        @Override
        public List<SourceStatus> sources() {
            return List.of(new SourceStatus(
                    SOURCE, SourceStatus.State.OK, Optional.of(Instant.parse("2026-10-28T12:00:00Z"))));
        }
    }
}
