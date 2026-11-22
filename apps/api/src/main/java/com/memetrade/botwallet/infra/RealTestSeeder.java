package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.domain.Network;
import com.memetrade.shared.config.ProcessRole;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * BR7.1, functional-spec.md F6: ONLY in the test profile and only with {@code memetrade.real.seed-test-release=true}
 * (the REAL overlay of the e2e run), the test founder (the smoke account) gets a release on the network of the
 * environment requested 25 hours ago, already past its quarantine, and the acceptance of the REAL terms (v3). No
 * production profile ever writes a release this way. Since the U5 (logical-components.md, iteration 2) the seed writes
 * the state ACTIVE directly; everywhere else only the gates job turns a release ACTIVE. Waits up to 30 s for the smoke
 * account seeded by the identity.
 */
@Component
@Profile("test")
@ProcessRole(ProcessRole.Kind.API)
public class RealTestSeeder {

    private static final Logger log = LoggerFactory.getLogger(RealTestSeeder.class);
    private static final Duration QUARANTINE_PASSED = Duration.ofHours(25);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RealProperties properties;
    private final String walletAddress;

    public RealTestSeeder(
            JdbcClient jdbc,
            Clock clock,
            RealProperties properties,
            @Value("${memetrade.smoke.beta-wallet-address:}") String walletAddress) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.walletAddress = walletAddress;
    }

    // Listeners of one event run in turn on one thread: this one must come after the smoke account seed, or its wait
    // would block the very listener that creates the account.
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void seed() throws InterruptedException {
        if (!properties.seedTestRelease() || walletAddress.isBlank()) {
            return;
        }
        requireLocalnet(properties.network());
        Optional<Person> person = Optional.empty();
        for (int attempt = 0; attempt < 30 && person.isEmpty(); attempt++) {
            person = smokePerson();
            if (person.isEmpty()) {
                Thread.sleep(1_000);
            }
        }
        if (person.isEmpty()) {
            throw new IllegalStateException("The REAL test seed found no smoke account");
        }
        seedFor(person.get());
    }

    /** The release and the REAL terms of the smoke account, on the LOCALNET only. */
    void seedFor(Person person) {
        requireLocalnet(properties.network());
        release(person);
        acceptRealTerms(person);
        log.warn(
                "TEST PROFILE ACTIVE: REAL release and terms {} seeded for the smoke account",
                properties.requiredTermsVersion());
    }

    /**
     * 261121-paper-real-unico U1 (NFR3.8, security-design.md "Checagem de rede", review R-04): the only runtime barrier
     * when an environment variable turns the seed on outside the files the guard test reads: with the seed on, any
     * network other than the LOCALNET logs ERROR and stops the start of the process; nothing is written.
     */
    static void requireLocalnet(Network network) {
        if (network != Network.LOCALNET) {
            log.error("The REAL test seed is on outside the LOCALNET: network={}", network);
            throw new IllegalStateException("The REAL test seed runs only on the LOCALNET network");
        }
    }

    record Person(UUID tenantId, UUID userId) {}

    private Optional<Person> smokePerson() {
        return jdbc.sql("select u.tenant_id, u.user_id from wallet w join app_user u on u.user_id = w.user_id "
                        + "where w.address = :address")
                .param("address", walletAddress)
                .query((rs, row) ->
                        new Person(rs.getObject("tenant_id", UUID.class), rs.getObject("user_id", UUID.class)))
                .optional();
    }

    private void release(Person person) {
        Instant requested = clock.instant().minus(QUARANTINE_PASSED);
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          state)
                        select :id, :user, :network, :user, :requested, :effective, 'ACTIVE'
                        where not exists (select 1 from real_release where user_id = :user and network = :network)""")
                .param("id", UUID.randomUUID())
                .param("user", person.userId())
                .param("network", properties.network().name())
                .param("requested", Timestamp.from(requested))
                .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                .update();
    }

    private void acceptRealTerms(Person person) {
        jdbc.sql("""
                        insert into terms_acceptance
                            (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
                        values (:id, :user, :tenant, :version, :at, null, 'REACCEPTANCE')
                        on conflict on constraint uq_terms_acceptance_user_version do nothing""")
                .param("id", UUID.randomUUID())
                .param("user", person.userId())
                .param("tenant", person.tenantId())
                .param("version", properties.requiredTermsVersion())
                .param("at", Timestamp.from(clock.instant()))
                .update();
    }
}
