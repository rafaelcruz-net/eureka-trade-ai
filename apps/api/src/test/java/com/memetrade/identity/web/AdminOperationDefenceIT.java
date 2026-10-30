package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.web.AdminAuditController;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Defence in depth of the review finding "admin route bypass": even when a request reaches the administrative
 * operation WITHOUT passing the route rule (simulated here by calling the controller directly with a guest session
 * bound), the operation itself refuses with 403 FORBIDDEN_ROLE, records exactly one REQUEST_FORBIDDEN, reads nothing
 * and records no AUDIT_QUERIED.
 */
@Tag("u2")
class AdminOperationDefenceIT extends AbstractPostgresIT {

    @Autowired
    private AdminAuditController controller;

    @Test
    void theOperationRefusesAGuestEvenWithoutTheRouteRule() {
        Principal guest = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);
        String correlation = CorrelationId.withNew(() -> {
            RequestAuthContext.bind(new AuthenticatedSession(guest, UUID.randomUUID()));
            try {
                assertThatThrownBy(() -> controller.list(period(), request()))
                        .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                                .isEqualTo(ErrorCode.FORBIDDEN_ROLE));
                return CorrelationId.current();
            } finally {
                RequestAuthContext.clear();
            }
        });

        assertThat(actions(correlation)).containsExactly("REQUEST_FORBIDDEN");
    }

    @Test
    void withoutASessionTheOperationAnswers401AndRecordsNothing() {
        String correlation = CorrelationId.withNew(() -> {
            assertThatThrownBy(() -> controller.list(period(), request())).isInstanceOf(UnauthenticatedException.class);
            return CorrelationId.current();
        });

        assertThat(actions(correlation)).isEmpty();
    }

    private List<String> actions(String correlation) {
        return jdbc.sql("select payload->>'action' from audit_outbox where payload->>'correlationId' = :c")
                .param("c", correlation)
                .query(String.class)
                .list();
    }

    private static MultiValueMap<String, String> period() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("from", "2026-01-01T00:00:00Z");
        parameters.add("to", "2026-01-02T00:00:00Z");
        return parameters;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/audit-events");
        request.setQueryString("from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z");
        return request;
    }
}
