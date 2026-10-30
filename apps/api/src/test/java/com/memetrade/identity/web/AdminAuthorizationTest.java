package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.ForbiddenRequestRecorder;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The single administrative authorisation component: admin passes, any other role is refused once and audited. */
@Tag("u2")
class AdminAuthorizationTest {

    private final AuthContext auth = mock(AuthContext.class);
    private final ForbiddenRequestRecorder recorder = mock(ForbiddenRequestRecorder.class);
    private final AdminAuthorization authorization = new AdminAuthorization(auth, recorder);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/audit-events");

    @Test
    void anAdministratorPassesWithoutAnyRecord() {
        Principal admin = new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
        when(auth.current()).thenReturn(admin);

        assertThat(authorization.requireAdministrator(request)).isSameAs(admin);
        assertThat(authorization.isAdministrator(Optional.of(admin))).isTrue();
        verifyNoInteractions(recorder);
    }

    @Test
    void aGuestIsRefusedWith403AndRecordedExactlyOnce() {
        when(auth.current()).thenReturn(new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER));

        assertThatThrownBy(() -> authorization.requireAdministrator(request))
                .isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.code())
                        .isEqualTo(ErrorCode.FORBIDDEN_ROLE));
        verify(recorder, times(1)).record(request, ErrorCode.FORBIDDEN_ROLE, AdminAuthorization.REASON);
    }

    @Test
    void withoutASessionTheAnswerIs401AndNothingIsRecorded() {
        when(auth.current()).thenThrow(new UnauthenticatedException());

        assertThatThrownBy(() -> authorization.requireAdministrator(request))
                .isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(recorder);
    }

    @Test
    void noPrincipalIsNotAnAdministrator() {
        assertThat(authorization.isAdministrator(Optional.empty())).isFalse();
        assertThat(authorization.isAdministrator(
                        Optional.of(new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER))))
                .isFalse();
    }
}
