package com.memetrade.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.domain.login.ChallengeService;
import com.memetrade.identity.domain.login.LoginVerificationService;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.web.AuthController;
import com.memetrade.identity.web.LoginGate;
import com.memetrade.identity.web.LoginMetrics;
import com.memetrade.identity.web.RequestAuthContext;
import com.memetrade.scanner.domain.ScannerService;
import com.memetrade.scanner.web.ScannerController;
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
 * Web slice that generates the guest OpenAPI from the real controllers and DTOs without a database, so the contract
 * check runs in the unit phase. Services are mocks: only the shapes matter here.
 */
@Tag("u1")
@WebMvcTest(
        controllers = {
            AuthController.class,
            ScannerController.class,
            com.memetrade.scanner.web.AssessmentController.class,
            com.memetrade.identity.web.InviteLookupController.class,
            com.memetrade.identity.web.AccountController.class,
            // U7 (C5): PAPER ledger and P&L of the caller.
            com.memetrade.ledger.web.LedgerController.class,
            com.memetrade.pnl.web.PnlController.class,
            // U6 (C21): refused orders of the caller's bot.
            com.memetrade.riskcontrol.web.RejectionController.class
        })
@Import({OpenApiConfig.class, ProblemWriter.class})
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class,
    MultipleOpenApiSupportConfiguration.class
})
public abstract class GuestOpenApiSlice {

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    ChallengeService challengeService;

    @MockitoBean
    LoginVerificationService loginVerificationService;

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
    ScannerService scannerService;

    @MockitoBean
    com.memetrade.riskassessment.api.RiskAssessmentDetails riskAssessmentDetails;

    @MockitoBean
    com.memetrade.shared.web.PersonRouteRateLimiter personRouteRateLimiter;

    @MockitoBean
    ForbiddenRequestRecorder forbiddenRequestRecorder;

    @MockitoBean
    com.memetrade.identity.web.AdminAuthorization adminAuthorization;

    @MockitoBean
    AuditEventPublisher auditEventPublisher;

    @MockitoBean
    io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** U3 (C3): invitation lookup and Account screen. */
    @MockitoBean
    com.memetrade.identity.domain.invite.InviteLookupService inviteLookupService;

    @MockitoBean
    com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter inviteLookupRateLimiter;

    @MockitoBean
    com.memetrade.identity.web.ClientIpResolver clientIpResolver;

    @MockitoBean
    com.memetrade.identity.domain.account.AccountQueryService accountQueryService;

    /** U7 (C5): ledger, P&L and reconciliation. */
    @MockitoBean
    com.memetrade.ledger.application.LedgerQueries ledgerQueries;

    @MockitoBean
    com.memetrade.pnl.application.PnlQueryService pnlQueryService;

    @MockitoBean
    com.memetrade.pnl.application.ReconciliationService reconciliationService;

    @MockitoBean
    com.memetrade.pnl.web.ReconciliationRunLimiter reconciliationRunLimiter;

    @MockitoBean
    org.springframework.transaction.PlatformTransactionManager platformTransactionManager;

    /** U6 (C21): the reading of the refused orders. */
    @MockitoBean
    com.memetrade.riskcontrol.domain.RejectionQueries rejectionQueries;

    protected String generatedYaml() throws Exception {
        return mvc.perform(get("/v3/api-docs.yaml/guest"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
