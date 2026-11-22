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
            com.memetrade.riskcontrol.web.RejectionController.class,
            // U5 (C4 and C7): the bots, the wizard, the commands, the decisions and the live channel.
            com.memetrade.bots.web.BotController.class,
            com.memetrade.bots.web.DashboardStreamController.class,
            // 261121-paper-real-unico U1 (C3): the REAL limits of the account.
            com.memetrade.riskcontrol.web.RealAccountLimitsController.class,
            // U8 (C6): the comment of a guest.
            com.memetrade.feedback.web.FeedbackController.class,
            // u1-legal-documents (L1): the public legal documents.
            com.memetrade.identity.web.LegalDocumentsController.class,
            // u3-reacceptance (L2): the acceptance of the current terms.
            com.memetrade.identity.web.TermsAcceptanceController.class,
            // u4-data-requests (P1): the data subject requests of the guest.
            com.memetrade.privacy.web.DataRequestController.class,
            // 261113-trading-real-bot U1 (R2): the bot wallet of the guest.
            com.memetrade.botwallet.web.BotWalletController.class,
            // 261113-trading-real-bot U4 (R3): the REAL orders, mode, liquidation and P&L of the guest.
            com.memetrade.realexecution.web.RealOrderController.class,
            com.memetrade.pnl.web.RealPnlController.class,
            // 261113-trading-real-bot U7 (R5 of the addendum): the conditional exits and the fiscal export.
            com.memetrade.realexits.web.ConditionalController.class,
            com.memetrade.realexits.fiscal.FiscalExportController.class
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
    com.memetrade.botwallet.application.BotWalletService botWalletService;

    /** 261113-trading-real-bot U6: the read of the release goes through the M1. */
    @MockitoBean
    com.memetrade.botwallet.api.RealStateQuery realStateQuery;

    /** 261113-trading-real-bot U7: the exits flag of the release, the conditional exits and the fiscal export. */
    @MockitoBean
    com.memetrade.botwallet.api.ExitsAvailability exitsAvailability;

    @MockitoBean
    com.memetrade.realexits.application.ConditionalService conditionalService;

    @MockitoBean
    com.memetrade.realexits.fiscal.FiscalExportService fiscalExportService;

    @MockitoBean
    com.memetrade.realexits.fiscal.FiscalExportLimiter fiscalExportLimiter;

    @MockitoBean
    com.memetrade.realexits.infra.RealExitsMetrics realExitsMetrics;

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

    /** U2: the scanner and the assessment detail read the kind of the active source and its attribution (M4). */
    @MockitoBean
    com.memetrade.marketdata.api.MarketDataQuery marketDataQuery;

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

    /** U5 (C4, C7): the commands, the reads and the live channel of the bots. */
    @MockitoBean
    com.memetrade.bots.application.BotCommandService botCommandService;

    @MockitoBean
    com.memetrade.bots.application.BotQueries botQueries;

    /** 261121-paper-real-unico U1 (C2, C3, C4): the eligibility, the limits of the account and the P&L by mode. */
    @MockitoBean
    com.memetrade.bots.application.RealEligibilityReader realEligibilityReader;

    @MockitoBean
    com.memetrade.riskcontrol.api.RealAccountLimits realAccountLimits;

    @MockitoBean
    org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

    @MockitoBean
    com.memetrade.pnl.modeview.ModePnlView modePnlView;

    @MockitoBean
    com.memetrade.pnl.web.PaperBlockSource paperBlockSource;

    @MockitoBean
    com.memetrade.pnl.web.RealBlockSource realBlockSource;

    @MockitoBean
    com.memetrade.bots.web.DashboardMarkPoller dashboardMarkPoller;

    /** U8 (C6): the submission of feedback. */
    @MockitoBean
    com.memetrade.feedback.domain.FeedbackService feedbackService;

    /** u1-legal-documents (L1): the ready answers and the limiter of the public legal documents. */
    @MockitoBean
    com.memetrade.identity.web.LegalDocumentsView legalDocumentsView;

    @MockitoBean
    com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter legalDocumentsRateLimiter;

    /** u3-reacceptance (L2, L3): the acceptance and the collaborators of the reacceptance lock. */
    @MockitoBean
    com.memetrade.identity.domain.terms.TermsAcceptanceService termsAcceptanceService;

    @MockitoBean
    com.memetrade.identity.domain.terms.CurrentTerms currentTerms;

    @MockitoBean
    com.memetrade.identity.domain.terms.TermsAcceptanceLookup termsAcceptanceLookup;

    @MockitoBean
    com.memetrade.identity.domain.terms.TermsEvents termsEvents;

    @MockitoBean
    com.memetrade.identity.infra.ReacceptanceProperties reacceptanceProperties;

    /** u4-data-requests (P1): the requests of the guest. */
    @MockitoBean
    com.memetrade.privacy.domain.request.DataRequestService dataRequestService;

    /** 261113-trading-real-bot U4 (R3): the collaborators of the REAL orders and P&L. */
    @MockitoBean
    com.memetrade.realexecution.infra.JdbcRealExecutionStore realExecutionStore;

    @MockitoBean
    com.memetrade.realexecution.infra.SigningSessionService signingSessionService;

    @MockitoBean
    com.memetrade.realexecution.infra.RealModeService realModeService;

    @MockitoBean
    com.memetrade.realexecution.infra.LiquidationService liquidationService;

    @MockitoBean
    com.memetrade.pnl.application.RealPnlService realPnlService;

    @MockitoBean
    com.memetrade.botwallet.web.BotWalletRateLimits botWalletRateLimits;

    protected String generatedYaml() throws Exception {
        return mvc.perform(get("/v3/api-docs.yaml/guest"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
