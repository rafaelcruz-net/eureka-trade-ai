package com.memetrade.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.SyntheticJwt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Regression of the review findings "admin route bypass" and "refusal turns into 500": path normalisation. */
@Tag("u2")
class RequestPathsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/admin/audit-events",
                "/api/v1/%61dmin/audit-events",
                "/api/v1/admin;x/audit-events",
                "/api/v1/admin;jsessionid=1/audit-events",
                "//api/v1/admin/audit-events",
                "/api//v1/admin/audit-events",
                "/api/v1/./admin/audit-events",
                "/api/v1/x/../admin/audit-events",
                "/api/v1/%2e/admin/audit-events",
                "/api/v1/admin%2Faudit-events",
                "/API/v1/admin/audit-events",
                "/api/v1/ADMIN/audit-events",
                "/api/v1/admin",
                "/api/v1/admin/"
            })
    void everySpellingThatNormalisesUnderTheAdminPrefixIsAdministrative(String uri) {
        assertThat(RequestPaths.isAdministrative(get(uri))).as(uri).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/scanner/tokens", "/api/v1/administrator", "/api/v1/auth/verify", "/"})
    void otherPathsAreNotAdministrative(String uri) {
        assertThat(RequestPaths.isAdministrative(get(uri))).as(uri).isFalse();
    }

    @Test
    void theNormalisedPathIsDecodedWithoutSemicolonContentAndWithoutDotSegments() {
        assertThat(RequestPaths.normalized(get("/api/v1/%61dmin;x/./audit-events")))
                .isEqualTo("/api/v1/admin/audit-events");
        assertThat(RequestPaths.normalized(get("//api//v1/scanner/../auth/verify")))
                .isEqualTo("/api/v1/auth/verify");
    }

    @Test
    void theAuditRouteKeepsPlainSegmentsAndUuidsAndDropsTheQuery() {
        MockHttpServletRequest request = get("/api/v1/bots/0b5c3a1e-1111-4222-8333-944445555666/orders");
        request.setQueryString("token=" + SyntheticJwt.withSubject("x"));

        assertThat(RequestPaths.auditRoute(request))
                .isEqualTo("GET /api/v1/bots/0b5c3a1e-1111-4222-8333-944445555666/orders");
    }

    @Test
    void unsafeSegmentsAreRedacted() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl";
        String signature = "5".repeat(87);
        String wallet = "7".repeat(44);

        String route =
                RequestPaths.auditRoute(get("/api/v1/admin/" + jwt + "/" + signature + "/" + wallet + "/Bearer%20abc"));

        assertThat(route)
                .isEqualTo("GET /api/v1/admin/{redacted}/{redacted}/{redacted}/{redacted}")
                .doesNotContain(jwt)
                .doesNotContain(signature)
                .doesNotContain(wallet)
                .doesNotContain("Bearer");
    }

    @Test
    void aLongPathIsTruncatedToTheColumnLimit() {
        String route = RequestPaths.auditRoute(get("/api/v1/admin" + "/abcdefghij".repeat(40)));

        assertThat(route).hasSize(RequestPaths.MAX_ROUTE_LENGTH).endsWith("...").startsWith("GET /api/v1/admin/");
    }

    @Test
    void anUnusualMethodIsRecordedAsOther() {
        MockHttpServletRequest request = new MockHttpServletRequest("get it", "/api/v1/admin/x");

        assertThat(RequestPaths.auditRoute(request)).isEqualTo("OTHER /api/v1/admin/x");
        assertThat(RequestPaths.auditRoute(new MockHttpServletRequest("GET", "/")))
                .isEqualTo("GET /");
    }

    private static MockHttpServletRequest get(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
