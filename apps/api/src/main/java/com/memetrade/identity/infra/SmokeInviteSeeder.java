package com.memetrade.identity.infra;

import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.domain.invite.Invite;
import com.memetrade.identity.domain.invite.InviteCodes;
import com.memetrade.identity.domain.invite.InviteRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXISTS ONLY WITH THE "test" PROFILE (Q1 of the U3 Infrastructure Design): on start-up it creates, in the tenant
 * "Beta", a PENDING invitation of 1 hour for the code of {@code memetrade.smoke.invite-code} (the U3 smoke, generated
 * per run by scripts/verify.sh) and for each code of {@code memetrade.smoke.invite-codes} (the U3 load scenario).
 * Only the digest and the last 4 characters are stored and logged; a code already seeded is left as it is. A
 * malformed code stops the start with a message that never echoes it. Empty settings create nothing.
 */
@Component
@Profile("test")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class SmokeInviteSeeder {

    /** Fixed identifier of the tenant "Beta" seeded by the migration 0007 (BR1.7). */
    public static final UUID BETA_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000b3");

    static final Duration VALIDITY = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(SmokeInviteSeeder.class);

    private final InviteRepository invites;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final List<String> codes;

    public SmokeInviteSeeder(
            InviteRepository invites, TransactionTemplate transactions, Clock clock, String code, String codeList) {
        this(invites, transactions, clock, code, codeList, "");
    }

    /**
     * u5-anonymization (infrastructure-specification.md "Dados de teste"): {@code anonymizationCode}
     * ({@code memetrade.smoke.anonymization-invite-code}, from SMOKE_ANONYMIZATION_INVITE_CODE) is a second
     * single-use invitation for the throw-away account of the e2e/anonymization spec, so that spec never touches the
     * account of the smoke. Absent or empty, nothing more is seeded and the current scripts/verify.sh keeps working.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SmokeInviteSeeder(
            InviteRepository invites,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${memetrade.smoke.invite-code:}") String code,
            @Value("${memetrade.smoke.invite-codes:}") String codeList,
            @Value("${memetrade.smoke.anonymization-invite-code:}") String anonymizationCode) {
        this.invites = invites;
        this.transactions = transactions;
        this.clock = clock;
        this.codes = parse(code, codeList, anonymizationCode);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        for (String code : codes) {
            Boolean created = transactions.execute(status -> insertIfAbsent(code));
            log.warn(
                    "TEST PROFILE ACTIVE: smoke invitation ending {} {}",
                    InviteCodes.suffix(code),
                    Boolean.TRUE.equals(created) ? "created" : "already present");
        }
    }

    /** The well-formed codes to seed, without repetitions (test profile only, never logged). */
    public List<String> codes() {
        return codes;
    }

    private boolean insertIfAbsent(String code) {
        String digest = InviteCodes.digest(code);
        if (invites.findByDigest(digest).isPresent()) {
            return false;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        invites.insert(new Invite(
                UUID.randomUUID(),
                BETA_TENANT,
                digest,
                InviteCodes.suffix(code),
                InviteStatus.PENDING,
                "smoke-seeder",
                now,
                now.plus(VALIDITY),
                null,
                null,
                null));
        return true;
    }

    private static List<String> parse(String code, String codeList, String anonymizationCode) {
        Set<String> all = new LinkedHashSet<>();
        List<String> candidates = new ArrayList<>();
        candidates.add(code);
        candidates.add(anonymizationCode);
        if (codeList != null) {
            candidates.addAll(Arrays.asList(codeList.split(",")));
        }
        for (String candidate : candidates) {
            String trimmed = candidate == null ? "" : candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!InviteCodes.isWellFormed(trimmed)) {
                throw new IllegalStateException("The test profile got a malformed smoke invitation code in"
                        + " SMOKE_INVITE_CODE, SMOKE_INVITE_CODES or SMOKE_ANONYMIZATION_INVITE_CODE (43 characters of"
                        + " base64url expected)");
            }
            all.add(trimmed);
        }
        return List.copyOf(all);
    }
}
