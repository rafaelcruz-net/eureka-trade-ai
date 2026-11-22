package com.memetrade.bots.application;

import com.memetrade.bots.domain.RealCreationEligibility;
import com.memetrade.bots.domain.RealCreationEligibility.Release;
import com.memetrade.bots.domain.RealEligibility;
import com.memetrade.bots.infra.BotProperties;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.api.RealReleaseInfo;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.identity.api.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261121-paper-real-unico U1 (BR2.1, BR2.3, BR2.5, NFR1.2, NFR1.8, NFR4.5; reliability-design.md "Elegibilidade na
 * criação REAL"): reads the sources of {@link RealCreationEligibility} in order and only up to the first reason, with
 * one deadline ({@code memetrade.bots.real-eligibility-timeout}) for the whole set: before each read the time left
 * becomes {@code set local statement_timeout}, and without time left the eligibility is unavailable. Only the database
 * and the configuration are read; no network call.
 *
 * <ul>
 *   <li>{@link #forCreation}: inside the idempotent creation command, in a SAVEPOINT ({@code PROPAGATION_NESTED}), so a
 *       failed read (a {@code statement_timeout} 57014 included) rolls back only the savepoint and the command
 *       transaction stays usable for the release of the idempotency claim (never a 25P02 that would become 500);
 *   <li>{@link #forRead}: the route C2, in its own read-only transaction.
 * </ul>
 *
 * A failure or a deadline that runs out before a reason is decided logs {@code WARN} and throws
 * {@link EligibilitySourceUnavailable} (503). Not bound to a process role: the {@link BotCommandService} that calls it
 * exists in the api and in the worker, and only the api ever creates a bot.
 */
@Component
public class RealEligibilityReader {

    private static final Logger log = LoggerFactory.getLogger(RealEligibilityReader.class);

    /** Where the eligibility is read, for the log. */
    enum Operation {
        CREATE,
        READ
    }

    private final RealKillSwitchState killSwitch;
    private final RealStateQuery state;
    private final JdbcClient jdbc;
    private final Duration timeout;
    private final boolean realEnabled;
    private final TransactionTemplate nested;
    private final TransactionTemplate readOnly;

    public RealEligibilityReader(
            RealKillSwitchState killSwitch,
            RealStateQuery state,
            JdbcClient jdbc,
            BotProperties properties,
            PlatformTransactionManager transactions,
            @Value("${memetrade.real.enabled:false}") boolean realEnabled) {
        this.killSwitch = killSwitch;
        this.state = state;
        this.jdbc = jdbc;
        this.timeout = properties.realEligibilityTimeout();
        this.realEnabled = realEnabled;
        this.nested = new TransactionTemplate(transactions);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** C1: the eligibility read again in the creation command, inside a savepoint of the command transaction. */
    public RealEligibility forCreation(Principal person) {
        RealEligibility eligibility = guarded(Operation.CREATE, () -> nested.execute(status -> evaluate(person)));
        // Back to the default for the rest of the command (the insert of the bot and its audit event).
        jdbc.sql("set local statement_timeout to default").update();
        return eligibility;
    }

    /** C2: the eligibility of the screen, in its own read-only transaction. */
    public RealEligibility forRead(Principal person) {
        return guarded(Operation.READ, () -> readOnly.execute(status -> evaluate(person)));
    }

    private RealEligibility guarded(Operation operation, Supplier<RealEligibility> read) {
        try {
            return read.get();
        } catch (DeadlineExceeded deadline) {
            log.warn("REAL eligibility unavailable: operation={}, cause={}", operation, "TIMEOUT");
            throw new EligibilitySourceUnavailable("TIMEOUT", deadline);
        } catch (DataAccessException | TransactionException failure) {
            String cause =
                    isStatementTimeout(failure) ? "TIMEOUT" : failure.getClass().getSimpleName();
            log.warn("REAL eligibility unavailable: operation={}, cause={}", operation, cause);
            throw new EligibilitySourceUnavailable(cause, failure);
        }
    }

    private RealEligibility evaluate(Principal person) {
        long deadline = System.nanoTime() + timeout.toNanos();
        return RealCreationEligibility.evaluate(new RealCreationEligibility.Sources() {
            @Override
            public boolean killSwitchActive() {
                within(deadline);
                return killSwitch.current().active();
            }

            @Override
            public boolean realEnabled() {
                return realEnabled;
            }

            @Override
            public boolean walletActive() {
                within(deadline);
                return state.readRealState(person.tenantId(), person.userId()).isPresent();
            }

            @Override
            public Release release() {
                within(deadline);
                RealReleaseInfo release = state.readRelease(person.tenantId(), person.userId());
                return new Release(release.state(), Optional.ofNullable(release.effectiveAt()));
            }
        });
    }

    /** The time left of the deadline becomes the statement timeout of the next read; none left fails. */
    private void within(long deadline) {
        long left = Duration.ofNanos(deadline - System.nanoTime()).toMillis();
        if (left <= 0) {
            throw new DeadlineExceeded();
        }
        jdbc.sql("set local statement_timeout = '" + left + "ms'").update();
    }

    private static boolean isStatementTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** The deadline of the eligibility ran out before the next read. */
    static final class DeadlineExceeded extends RuntimeException {
        DeadlineExceeded() {
            super("The deadline of the REAL eligibility ran out");
        }
    }
}
