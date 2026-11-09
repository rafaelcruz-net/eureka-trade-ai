package com.memetrade.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.domain.AuditQueryService;
import com.memetrade.audit.web.AdminAuditController;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.web.LoginGate;
import com.memetrade.identity.web.LoginMetrics;
import com.memetrade.identity.web.RequestAuthContext;
import com.memetrade.shared.config.OpenApiConfig;
import com.memetrade.shared.web.ForbiddenRequestRecorder;
import com.memetrade.shared.web.ProblemWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Web slice that generates the administrative OpenAPI group (C8 operations provided by the modules) from the real
 * administrative controllers and DTOs without a database, so the contract check runs in the unit phase. Services are
 * mocks: only the shapes matter here.
 */
@Tag("u2")
@WebMvcTest(
        controllers = {
            AdminAuditController.class,
            com.memetrade.identity.web.AdminInviteController.class,
            // U8 (C8): the administrator entry, the kill switch, the system status, the tenants and the feedback.
            com.memetrade.identity.web.AdminAuthController.class,
            com.memetrade.administration.web.AdminKillSwitchController.class,
            com.memetrade.administration.web.AdminSystemController.class,
            com.memetrade.feedback.web.AdminFeedbackController.class,
            // u1-legal-documents (L1): the public legal documents, also in the administrative group.
            com.memetrade.identity.web.LegalDocumentsController.class
        })
@Import({OpenApiConfig.class, ProblemWriter.class})
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class,
    MultipleOpenApiSupportConfiguration.class
})
public abstract class AdminOpenApiSlice {

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    AuditQueryService auditQueryService;

    /** U4 filter of the scanner routes, present in every web slice. */
    @MockitoBean
    com.memetrade.shared.web.PersonRouteRateLimiter personRouteRateLimiter;

    @MockitoBean
    com.memetrade.identity.web.AdminAuthorization adminAuthorization;

    @MockitoBean
    SessionService sessionService;

    @MockitoBean
    RequestAuthContext requestAuthContext;

    @MockitoBean
    IdempotencyGuard idempotencyGuard;

    @MockitoBean
    TransactionTemplate transactionTemplate;

    @MockitoBean
    LoginMetrics loginMetrics;

    @MockitoBean
    LoginGate loginGate;

    @MockitoBean
    Clock clock;

    @MockitoBean
    ForbiddenRequestRecorder forbiddenRequestRecorder;

    @MockitoBean
    AuditEventPublisher auditEventPublisher;

    @MockitoBean
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** U3 (C8): invitation routes of the administrator. */
    @MockitoBean
    com.memetrade.identity.api.IdentityAdministration identityAdministration;

    /** U8 (C8): the collaborators of the administrator entry, the kill switch, the status and the feedback list. */
    @MockitoBean
    com.memetrade.identity.domain.login.ChallengeService challengeService;

    @MockitoBean
    com.memetrade.identity.domain.login.AdminLoginService adminLoginService;

    @MockitoBean
    com.memetrade.identity.domain.login.AdminLoginSettings adminLoginSettings;

    @MockitoBean
    com.memetrade.identity.domain.ratelimit.AdminLoginLimiter adminLoginLimiter;

    @MockitoBean
    com.memetrade.identity.web.ClientIpResolver clientIpResolver;

    @MockitoBean
    com.memetrade.administration.domain.KillSwitchService killSwitchService;

    @MockitoBean
    com.memetrade.administration.infra.KillSwitchApplier killSwitchApplier;

    @MockitoBean
    com.memetrade.administration.domain.KillSwitchStore killSwitchStore;

    @MockitoBean
    com.memetrade.administration.domain.ActiveBots activeBots;

    @MockitoBean
    com.memetrade.administration.domain.SystemStatusAssembler systemStatusAssembler;

    @MockitoBean
    com.memetrade.feedback.domain.FeedbackStore feedbackStore;

    @MockitoBean
    com.memetrade.feedback.infra.FeedbackProperties feedbackProperties;

    /** u1-legal-documents (L1): the public legal documents, read by the administrator app too. */
    @MockitoBean
    com.memetrade.identity.web.LegalDocumentsView legalDocumentsView;

    @MockitoBean
    com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter legalDocumentsRateLimiter;

    protected String generatedYaml() throws Exception {
        return mvc.perform(get("/v3/api-docs.yaml/admin"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
