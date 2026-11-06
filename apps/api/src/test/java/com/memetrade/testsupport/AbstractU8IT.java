package com.memetrade.testsupport;

import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Base of the U8 integration tests (unit-test-instructions.md): the U5 base of the api process (real PostgreSQL with
 * every migration, the application login, fixed clock, no scheduled job, U6 and U7 real) plus
 *
 * <ul>
 *   <li>the controllable double of {@link BotAdministration} ({@link ControllableBotAdministration}): FAKE by default,
 *       REAL in the race and performance tests;
 *   <li>the real U5 commands bound to the test principal ({@link #fixtures}), to build real ACTIVE bots;
 *   <li>a clean kill switch before and after every test: the row goes back to OFF without pending bots nor
 *       reconciliation (its {@code version} keeps growing, the history is append-only) and, when a test used the real
 *       U5, its system pause is lifted so the other suites of the JVM never meet a paused system.
 * </ul>
 */
@Tag("u8")
@Import({
    AbstractPostgresIT.FixedClockConfig.class,
    U5TestBeans.class,
    AbstractU5IT.ApiContextBeans.class,
    AbstractU8IT.U8Beans.class
})
public abstract class AbstractU8IT extends AbstractU5IT {

    @Autowired
    protected ControllableBotAdministration u5;

    @Autowired
    protected BotCommandService commands;

    @Autowired
    protected JdbcBotRepository botRepository;

    /** The real U5 commands, each run as the owner bound in the test authentication context. */
    protected U5Bots fixtures;

    @Autowired
    protected MutableAdminWallets adminWallets;

    @Autowired
    protected com.memetrade.identity.domain.ratelimit.AdminLoginLimiter adminLoginLimiter;

    @BeforeEach
    void resetU8State() {
        adminWallets.clear();
        adminLoginLimiter.clear();
        u5.reset();
        restoreTheKillSwitch();
        fixtures = new U5Bots(commands, botRepository, CycleAuthContext::as);
    }

    @AfterEach
    void leaveTheSystemUnpaused() {
        if (u5.takeRealUsed()) {
            u5.real().liftSystemPause("u8-after-each");
        }
        u5.reset();
        restoreTheKillSwitch();
    }

    @Autowired
    protected AdminSessions adminSessions;

    /** The tenant "Beta" seeded by U3, where the administrator accounts live (entities.md AdminAccount). */
    public static final UUID SYSTEM_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000b3");

    /** A real ADMIN account (app_user and a synthetic wallet), so the history and the state can reference it. */
    protected Principal newAdministrator() {
        UUID adminId = UUID.randomUUID();
        jdbc.sql("""
                        insert into app_user (user_id, tenant_id, role, status, created_at)
                        values (:id, :tenant, 'ADMIN', 'ACTIVE', now())
                        """).param("id", adminId).param("tenant", SYSTEM_TENANT).update();
        jdbc.sql("""
                        insert into wallet (wallet_id, user_id, address, provider, verified_at)
                        values (:id, :user, :address, 'PHANTOM', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("user", adminId)
                .param("address", SyntheticIds.mint())
                .update();
        return new Principal(SYSTEM_TENANT, adminId, Role.ADMIN);
    }

    /** A bearer token for a real ADMIN account (the token itself is a test-only one of {@link AdminSessions}). */
    protected AdminSessions.AdminSession sessionOf(Principal administrator) {
        return adminSessions.openAdminSessionFor(administrator.tenantId(), administrator.userId());
    }

    @Autowired
    protected org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    protected com.fasterxml.jackson.databind.ObjectMapper json;

    /** {@code PUT /api/v1/admin/kill-switch} as the administrator app sends it, with a fresh Idempotency-Key. */
    protected org.springframework.mock.web.MockHttpServletResponse putKillSwitch(
            AdminSessions.AdminSession session, String state, String reason) throws Exception {
        return putKillSwitch(session, state, reason, UUID.randomUUID());
    }

    protected org.springframework.mock.web.MockHttpServletResponse putKillSwitch(
            AdminSessions.AdminSession session, String state, String reason, UUID key) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = json.createObjectNode();
        body.put("state", state);
        body.put("reason", reason);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/admin/kill-switch")
                        .header("Authorization", session.bearer())
                        .header("Idempotency-Key", key.toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    /** {@code GET /api/v1/admin/kill-switch}. */
    protected org.springframework.mock.web.MockHttpServletResponse getKillSwitch(AdminSessions.AdminSession session)
            throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/admin/kill-switch")
                        .header("Authorization", session.bearer()))
                .andReturn()
                .getResponse();
    }

    protected com.fasterxml.jackson.databind.JsonNode body(
            org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Autowired
    private com.memetrade.identity.domain.ratelimit.LoginRateLimiter loginLimiter;

    /** A guest signed in through the real entry, with its bearer token and principal. */
    public record Guest(String bearer, Principal principal) {}

    /**
     * A fresh guest (tenant, BETA_USER, synthetic wallet) signed in through challenge and verify. The login counters are
     * cleared after each sign-in: a test that needs many guests never meets the login limit, which other suites test.
     */
    protected Guest newGuest() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));
        loginLimiter.clear();
        return new Guest("Bearer " + token, new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER));
    }

    /** {@code POST /api/v1/feedback} as the guest app sends it. */
    protected org.springframework.mock.web.MockHttpServletResponse postFeedback(
            String bearer, String screen, String text, UUID key) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = json.createObjectNode();
        body.put("screen", screen);
        body.put("text", text);
        return postFeedbackBody(bearer, json.writeValueAsString(body), key);
    }

    protected org.springframework.mock.web.MockHttpServletResponse postFeedbackBody(
            String bearer, String body, UUID key) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/feedback")
                        .header("Authorization", bearer)
                        .header("Idempotency-Key", key.toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    /** OFF, no pending bot, no reconciliation; the version and the history stay (append-only). */
    protected void restoreTheKillSwitch() {
        jdbc.sql("""
                        update kill_switch_state
                        set state = 'OFF', pending_bots = 0, reconcile_needed = false, reconcile_marked_at = null
                        where id = 1
                        """).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class U8Beans {

        /** BR1.4: a list of administrator wallets the tests change while the context runs. */
        @Bean
        @Primary
        public MutableAdminWallets mutableAdminWallets() {
            return new MutableAdminWallets();
        }

        /** C16 behind the controllable double; the real implementation stays reachable through it. */
        @Bean
        @Primary
        public ControllableBotAdministration controllableBotAdministration(
                @Qualifier("botAdministrationImpl") BotAdministration real) {
            return new ControllableBotAdministration(real);
        }
    }
}
