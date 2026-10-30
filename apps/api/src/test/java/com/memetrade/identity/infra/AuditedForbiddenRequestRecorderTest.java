package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** Regression of the review finding "refusal turns into 500 without an event": the route is sanitised. */
@Tag("u2")
class AuditedForbiddenRequestRecorderTest {

    private final AuthContext auth = mock(AuthContext.class);
    // A real template over a no-op transaction manager: the callback runs, nothing else happens.
    private final TransactionTemplate transactions =
            new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class));
    private final List<AuditEventCommand> published = new ArrayList<>();

    AuditedForbiddenRequestRecorderTest() {
        when(auth.optional())
                .thenReturn(Optional.of(new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER)));
    }

    @Test
    void theRouteIsTheSanitisedPathWithoutTheQuery() {
        AuditedForbiddenRequestRecorder recorder =
                new AuditedForbiddenRequestRecorder(published::add, auth, transactions, new MutableClock());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/admin/eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln/audit-events");
        request.setQueryString("token=Bearer%20abc");

        recorder.record(request, ErrorCode.FORBIDDEN_ROLE, "ADMIN_ROUTE");

        assertThat(published).hasSize(1);
        assertThat(published.getFirst().targetRef()).isEqualTo("GET /api/v1/admin/{redacted}/audit-events");
        assertThat(published.getFirst().details()).containsEntry("route", "GET /api/v1/admin/{redacted}/audit-events");
    }

    @Test
    void aRefusedEventIsRecordedOnceMoreWithAFixedRoute() {
        AuditEventPublisher refusesFirst = event -> {
            if (published.isEmpty() && !event.targetRef().startsWith("UNRECORDABLE")) {
                throw new AuditPublishRejectedException(
                        "targetRef", AuditPublishRejectedException.Reason.FORBIDDEN_VALUE);
            }
            published.add(event);
        };
        AuditedForbiddenRequestRecorder recorder =
                new AuditedForbiddenRequestRecorder(refusesFirst, auth, transactions, new MutableClock());

        recorder.record(new MockHttpServletRequest("GET", "/api/v1/admin/x"), ErrorCode.FORBIDDEN_ROLE, null);

        assertThat(published).hasSize(1);
        assertThat(published.getFirst().targetRef()).isEqualTo(AuditedForbiddenRequestRecorder.FALLBACK_ROUTE);
        assertThat(published.getFirst().details()).containsEntry("reason", "FORBIDDEN_ROLE");
    }
}
