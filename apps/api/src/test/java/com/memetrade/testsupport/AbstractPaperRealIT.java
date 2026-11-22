package com.memetrade.testsupport;

import com.memetrade.bots.infra.RealCreationRefusalAudit;
import com.memetrade.botwallet.api.RealReleaseInfo;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletRef;
import com.memetrade.botwallet.application.RealStateService;
import com.memetrade.identity.api.Principal;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.api.RealBotPnlQuery;
import com.memetrade.pnl.api.RealModePnl;
import com.memetrade.pnl.application.PaperModeBlockReader;
import com.memetrade.pnl.application.RealBotPnlQueryV1;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 261121-paper-real-unico U1: the ONE base of the integration tests of the unit (a single cached Spring context), on top
 * of the REAL execution base (REAL switched on, profile {@code local}, real PostgreSQL with the migrations of the
 * application, fake network, Jupiter and TxSigner). It adds controllable doubles at the edges the scenarios break:
 *
 * <ul>
 *   <li>the M1 reads of the REAL eligibility ({@link FaultyRealStateQuery}): normal, failing, or sleeping 1 s in the
 *       database ({@code pg_sleep}) so the {@code statement_timeout} of the deadline fires;
 *   <li>the audit of a REAL refusal ({@link FaultyRefusalAudit}), which can fail its write;
 *   <li>the two blocks of the P&L by mode ({@link FaultyPaperBlock}, {@link FaultyRealBotPnl}): normal, failing or
 *       sleeping past the deadline.
 * </ul>
 *
 * Every double is reset before each test, the kill switch REAL is switched off after each test, and every test creates
 * its own people, wallets and bots (never shared state).
 */
@Import(AbstractPaperRealIT.PaperRealTestConfig.class)
public abstract class AbstractPaperRealIT extends AbstractRealExecutionIT {

    /** What a controllable double does on its next calls. */
    public enum Fault {
        NONE,
        FAIL,
        SLEEP
    }

    @Autowired
    protected FaultyRealStateQuery faultyState;

    @Autowired
    protected FaultyRefusalAudit faultyAudit;

    @Autowired
    protected FaultyPaperBlock faultyPaper;

    @Autowired
    protected FaultyRealBotPnl faultyReal;

    @Autowired
    protected MeterRegistry meters;

    @BeforeEach
    void resetPaperRealDoubles() {
        faultyState.fault.set(Fault.NONE);
        faultyAudit.failNext.set(false);
        faultyPaper.fault.set(Fault.NONE);
        faultyReal.fault.set(Fault.NONE);
        killSwitch(false);
    }

    @AfterEach
    void switchTheKillSwitchOff() {
        killSwitch(false);
    }

    /** The kill switch REAL of the environment (one row for everybody). */
    protected void killSwitch(boolean active) {
        jdbc.sql(
                        active
                                ? "update real_kill_switch set active = true, activated_at = now(), activated_by = :admin,"
                                        + " reason = 'teste', updated_at = now() where switch_id = 1"
                                : "update real_kill_switch set active = false, activated_at = null, activated_by = null,"
                                        + " reason = null, updated_at = now() where switch_id = 1")
                .param("admin", UUID.randomUUID())
                .update();
    }

    /** An ACTIVE bot wallet of the person with the cap in lamports (preferences AUTO_FULL). */
    protected UUID activeWallet(Person person, long capLamports) {
        UUID botWalletId = UUID.randomUUID();
        Instant created = clock.instant().minus(Duration.ofDays(2));
        jdbc.sql("""
                        insert into bot_wallet_account (bot_wallet_id, tenant_id, user_id, public_address,
                          main_wallet_address, network, created_at)
                        values (:id, :tenant, :user, :address, :main, 'LOCALNET', :at)""")
                .param("id", botWalletId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("address", TestWallet.generate().address())
                .param("main", person.wallet().address())
                .param("at", Timestamp.from(created))
                .update();
        jdbc.sql("""
                        insert into real_preferences (bot_wallet_id, operation_mode, cap_lamports, fee_reserve_lamports,
                          max_withdrawal_fee_lamports, updated_at)
                        values (:id, 'AUTO_FULL', :cap, 30000000, 100000, :at)""")
                .param("id", botWalletId)
                .param("cap", capLamports)
                .param("at", Timestamp.from(created))
                .update();
        return botWalletId;
    }

    /** A release of the person in QUARANTINE, requested now: it counts 24 hours later. */
    protected Instant quarantinedRelease(UUID userId) {
        Instant requested = clock.instant();
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state)
                        values (:id, :user, 'LOCALNET', :user, :requested, :effective, 'QUARANTINE')""")
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("requested", Timestamp.from(requested))
                .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                .update();
        return requested.plus(Duration.ofHours(24));
    }

    /** A person ready to create a REAL bot: REAL terms, released 25 hours ago, an ACTIVE bot wallet of 5 SOL. */
    protected Person eligiblePerson() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person, 5 * SOL);
        return person;
    }

    /** POST /api/v1/bots with the market (null leaves it out) and the given key. */
    protected MockHttpServletResponse create(Person person, String market, UUID key) throws Exception {
        String body = market == null
                ? "{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"Meu bot\"}"
                : "{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"Meu bot\",\"market\":\"" + market + "\"}";
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/bots")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    /** A request with the session of the person and without an Idempotency-Key (the reads). */
    protected MockHttpServletResponse read(Person person, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", person.bearer()))
                .andReturn()
                .getResponse();
    }

    protected long bots(UUID userId) {
        return jdbc.sql("select count(*) from bot where owner_user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }

    protected long idempotencyRecords(UUID key) {
        return jdbc.sql("select count(*) from idempotency_record where idempotency_key = :k")
                .param("k", key)
                .query(Long.class)
                .single();
    }

    protected double refusals(String reason) {
        return meters.counter("bots.real.create.refusals", "reason", reason).count();
    }

    protected double unavailableBlocks(String market) {
        return meters.counter("pnl.mode_block.unavailable", "market", market).count();
    }

    protected static Principal principal(Person person) {
        return new Principal(person.tenantId(), person.userId(), com.memetrade.identity.api.Role.BETA_USER);
    }

    // ----- the doubles -----

    /** M1 with a switch: delegates to the real reads unless the test asks it to fail or to sleep in the database. */
    public static class FaultyRealStateQuery implements RealStateQuery {
        public final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
        private final RealStateService real;
        private final JdbcClient jdbc;

        FaultyRealStateQuery(RealStateService real, JdbcClient jdbc) {
            this.real = real;
            this.jdbc = jdbc;
        }

        private void maybeFail() {
            switch (fault.get()) {
                case FAIL -> throw new DataAccessResourceFailureException("test: the source is down");
                case SLEEP -> jdbc.sql("select pg_sleep(1)").query().singleValue();
                case NONE -> {
                    // the real read follows
                }
            }
        }

        @Override
        public Optional<RealStateView> readRealState(UUID tenantId, UUID userId) {
            maybeFail();
            return real.readRealState(tenantId, userId);
        }

        @Override
        public List<RealWalletRef> walletsInForce() {
            return real.walletsInForce();
        }

        @Override
        public RealReleaseInfo readRelease(UUID tenantId, UUID userId) {
            maybeFail();
            return real.readRelease(tenantId, userId);
        }

        @Override
        public List<UUID> botWalletIds(UUID tenantId, UUID userId) {
            return real.botWalletIds(tenantId, userId);
        }

        @Override
        public List<PausedWallet> pausedWallets(com.memetrade.botwallet.api.RealPauseReason reason) {
            return real.pausedWallets(reason);
        }
    }

    /** The audit of a REAL refusal whose next write can be made to fail. */
    public static class FaultyRefusalAudit extends RealCreationRefusalAudit {
        public final java.util.concurrent.atomic.AtomicBoolean failNext =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        FaultyRefusalAudit(
                com.memetrade.audit.api.AuditEventPublisher publisher,
                Clock clock,
                org.springframework.transaction.PlatformTransactionManager transactions) {
            super(publisher, clock, transactions);
        }

        @Override
        public void refused(Principal person, com.memetrade.bots.domain.RealIneligibilityReason reason) {
            if (failNext.getAndSet(false)) {
                throw new DataAccessResourceFailureException("test: the audit write failed");
            }
            super.refused(person, reason);
        }
    }

    /** The PAPER block of the P&L by mode, which can fail or sleep past the deadline. */
    public static class FaultyPaperBlock extends PaperModeBlockReader {
        public final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);

        FaultyPaperBlock(
                com.memetrade.pnl.application.PnlQueryService pnl,
                com.memetrade.pnl.api.BotDirectory directory,
                Clock clock) {
            super(pnl, directory, clock);
        }

        @Override
        public com.memetrade.pnl.application.PaperModeBlock block(Principal person, PnlWindow window) {
            Faults.apply(fault.get(), "PAPER");
            return super.block(person, window);
        }
    }

    /** The REAL block (C10), which can fail or sleep past the deadline. */
    public static class FaultyRealBotPnl implements RealBotPnlQuery {
        public final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
        private final RealBotPnlQueryV1 real;

        FaultyRealBotPnl(RealBotPnlQueryV1 real) {
            this.real = real;
        }

        @Override
        public RealModePnl modePnl(UUID tenantId, UUID userId, PnlWindow window) {
            Faults.apply(fault.get(), "REAL");
            return real.modePnl(tenantId, userId, window);
        }

        @Override
        public Optional<com.memetrade.pnl.api.RealBotPnlLine> botPnl(
                UUID tenantId, UUID userId, UUID botId, PnlWindow window) {
            return real.botPnl(tenantId, userId, botId, window);
        }
    }

    static final class Faults {
        private Faults() {}

        static void apply(Fault fault, String market) {
            switch (fault) {
                case FAIL -> throw new IllegalStateException("test: the " + market + " block failed");
                case SLEEP -> {
                    try {
                        Thread.sleep(1_500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test: the " + market + " block was interrupted", interrupted);
                    }
                }
                case NONE -> {
                    // the real block follows
                }
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class PaperRealTestConfig {

        @Bean
        @Primary
        FaultyRealStateQuery faultyRealStateQuery(RealStateService real, JdbcClient jdbc) {
            return new FaultyRealStateQuery(real, jdbc);
        }

        @Bean
        @Primary
        FaultyRefusalAudit faultyRefusalAudit(
                com.memetrade.audit.api.AuditEventPublisher publisher,
                Clock clock,
                org.springframework.transaction.PlatformTransactionManager transactions) {
            return new FaultyRefusalAudit(publisher, clock, transactions);
        }

        @Bean
        @Primary
        FaultyPaperBlock faultyPaperBlock(
                com.memetrade.pnl.application.PnlQueryService pnl,
                com.memetrade.pnl.api.BotDirectory directory,
                Clock clock) {
            return new FaultyPaperBlock(pnl, directory, clock);
        }

        @Bean
        @Primary
        FaultyRealBotPnl faultyRealBotPnl(RealBotPnlQueryV1 real) {
            return new FaultyRealBotPnl(real);
        }
    }
}
