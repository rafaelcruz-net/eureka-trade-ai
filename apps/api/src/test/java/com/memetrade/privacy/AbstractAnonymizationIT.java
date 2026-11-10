package com.memetrade.privacy;

import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.anonymization.AnonymizationResult;
import com.memetrade.privacy.domain.anonymization.AnonymizationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the integration tests of u5-anonymization (unit-test-instructions.md): the context of the U4 and U8 tests
 * (real PostgreSQL with every migration and the role bootstrap, the application login, fixed clock, no scheduled job),
 * so no new Spring context is created. Each test creates its own people, bots, comments, sessions and requests with
 * test wallets generated in test code and synthetic texts, and reads only its own rows.
 */
@Tag("u5-anonymization")
public abstract class AbstractAnonymizationIT extends AbstractDataRequestIT {

    @Autowired
    protected AnonymizationService anonymization;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    /** A person signed in through the real entry, with an ACCOUNT DELETION request taken to IN_PROGRESS. */
    protected record Deletion(WalletGuest guest, String requestId, Principal admin) {
        public UUID userId() {
            return guest.principal().userId();
        }

        public UUID tenantId() {
            return guest.principal().tenantId();
        }

        public String wallet() {
            return guest.wallet().address();
        }
    }

    protected Deletion deletionInProgress() throws Exception {
        WalletGuest guest = newWalletGuest();
        Principal admin = newAdministrator();
        String id = created(guest.bearer(), "DELETION");
        body(calls.transition(sessionOf(admin).bearer(), id, "IN_PROGRESS", null, null, false), 200);
        return new Deletion(guest, id, admin);
    }

    protected AnonymizationResult anonymize(Deletion deletion) {
        return anonymize(deletion.admin(), deletion.requestId(), UUID.randomUUID());
    }

    protected AnonymizationResult anonymize(Principal admin, String requestId, UUID key) {
        return anonymization.anonymize(new AnonymizationService.Anonymize(
                admin,
                UUID.fromString(requestId),
                AnonymizationService.CONFIRMATION,
                IdempotencyKey.parse(key.toString())));
    }

    protected TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ seeds (synthetic, never personal data)

    /** A bot of the person in {@code state}; a PAUSED bot gets the reason PERSON. */
    protected UUID seedBot(Principal person, String state) {
        jdbc.sql("""
                        insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())
                        on conflict do nothing
                        """).param("t", person.tenantId()).param("u", person.userId()).update();
        UUID botId = UUID.randomUUID();
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, mode, name, template_id, template_version,
                                         state, pause_reasons, draft_step, created_at, updated_at)
                        values (:bot, :t, :u, 'PAPER', :name, 'MOMENTUM_VOLUME', 'v1', :state, :reasons, 1, now(), now())
                        """)
                .param("bot", botId)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .param("name", "Bot sintético " + botId.toString().substring(0, 8))
                .param("state", state)
                .param("reasons", "PAUSED".equals(state) ? "PERSON" : "")
                .update();
        return botId;
    }

    protected void seedFeedback(Principal person, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("""
                            insert into feedback_entry (feedback_id, tenant_id, user_id, screen, text, created_at)
                            values (:id, :t, :u, 'S1', 'Comentário sintético de teste', :at)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("t", person.tenantId())
                    .param("u", person.userId())
                    .param("at", Timestamp.from(clock.instant()))
                    .update();
        }
    }

    /** {@code count} ACTIVE sessions of the person, besides the one of its login. */
    protected void seedSessions(Principal person, int count) {
        for (int i = 0; i < count; i++) {
            Instant now = clock.instant();
            jdbc.sql("""
                            insert into user_session (session_id, user_id, tenant_id, role, refresh_token_digest,
                                                      created_at, refresh_expires_at, absolute_expires_at, state)
                            values (:id, :u, :t, 'BETA_USER', :digest, :now, :refresh, :absolute, 'ACTIVE')
                            """)
                    .param("id", UUID.randomUUID())
                    .param("u", person.userId())
                    .param("t", person.tenantId())
                    .param("digest", UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000")
                    .param("now", Timestamp.from(now))
                    .param("refresh", Timestamp.from(now.plusSeconds(8 * 3600)))
                    .param("absolute", Timestamp.from(now.plusSeconds(12 * 3600)))
                    .update();
        }
    }

    /**
     * Every log event of the application while open, read BEFORE any masking of the encoder (stricter than the console):
     * the formatted message, the arguments and the message of a throwable.
     */
    protected static final class CapturedLogs implements AutoCloseable {
        private final ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();

        public CapturedLogs() {
            appender.start();
            root.addAppender(appender);
        }

        public String text() {
            StringBuilder all = new StringBuilder();
            for (ch.qos.logback.classic.spi.ILoggingEvent event : List.copyOf(appender.list)) {
                all.append(event.getFormattedMessage()).append('\n');
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) {
                        all.append(argument).append('\n');
                    }
                }
                if (event.getThrowableProxy() != null) {
                    all.append(event.getThrowableProxy().getMessage()).append('\n');
                }
            }
            return all.toString();
        }

        public int size() {
            return appender.list.size();
        }

        @Override
        public void close() {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    // ------------------------------------------------------------------ reads

    protected String accountStatus(UUID userId) {
        return jdbc.sql("select status from app_user where user_id = :u")
                .param("u", userId)
                .query(String.class)
                .single();
    }

    protected Map<String, Object> walletRow(UUID userId) {
        return jdbc.sql("select address, provider, anonymized_at from wallet where user_id = :u")
                .param("u", userId)
                .query()
                .singleRow();
    }

    protected long activeSessions(UUID userId) {
        return count("select count(*) from user_session where user_id = :u and state = 'ACTIVE'", userId);
    }

    protected long sessions(UUID userId) {
        return count("select count(*) from user_session where user_id = :u", userId);
    }

    protected long feedbackOf(UUID userId) {
        return count("select count(*) from feedback_entry where user_id = :u", userId);
    }

    protected long challengesOf(String address) {
        return jdbc.sql("select count(*) from login_challenge where wallet_address = :a")
                .param("a", address)
                .query(Long.class)
                .single();
    }

    protected List<Map<String, Object>> bots(UUID userId) {
        return jdbc.sql("select bot_id, state, pause_reasons, name from bot where owner_user_id = :u order by bot_id")
                .param("u", userId)
                .query()
                .listOfRows();
    }

    protected String botState(UUID botId) {
        return jdbc.sql("select state from bot where bot_id = :b")
                .param("b", botId)
                .query(String.class)
                .single();
    }

    protected long anonymizedEvents(UUID userId) {
        return jdbc.sql("""
                        select count(*) from audit_outbox
                        where payload->>'action' = 'ACCOUNT_ANONYMIZED' and payload->>'targetRef' = :target
                        """).param("target", "user:" + userId).query(Long.class).single();
    }

    private long count(String sql, UUID userId) {
        return jdbc.sql(sql).param("u", userId).query(Long.class).single();
    }
}
