package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.SystemStatus;
import com.memetrade.administration.domain.SystemStatusAssembler;
import com.memetrade.bots.api.BotState;
import com.memetrade.identity.api.IdentityCounts;
import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.api.RejectionCounts;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 12 of U8 (F5; BR3.1 to BR3.3; AC7.4.1; NFR3.2, NFR10.4): the system status has counts and the state of the
 * sources only; a part that is slow (2 s) or down becomes {@code UNKNOWN} with its reason, NEVER zero, while the others
 * are shown; the route answers in at most 1 s. The tenants route lists the ACTIVE tenants.
 */
class SystemStatusIT extends AbstractU8IT {

    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    @Autowired
    private RejectionCounts rejections;

    @AfterEach
    void stopThePool() {
        pool.shutdownNow();
    }

    @Test
    void everyPartIsKnownWithCountsOnly() throws Exception {
        Principal owner = newPerson();
        UUID bot = fixtures.active(owner, newToken());
        Principal admin = newAdministrator();

        long started = System.nanoTime();
        MockHttpServletResponse response = systemStatus(sessionOf(admin));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(1));
        JsonNode status = body(response);
        for (String part : List.of(
                "guestCount",
                "inviteCounts",
                "botCountsByState",
                "killSwitch",
                "sources",
                "auditOutboxPending",
                "rejectedOrdersToday")) {
            assertThat(status.get(part).get("status").asText()).as(part).isEqualTo("OK");
        }
        assertThat(status.at("/inviteCounts/value").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("PENDING", "USED", "EXPIRED", "REVOKED");
        assertThat(status.at("/botCountsByState/value").fieldNames())
                .toIterable()
                .contains("CLOSING_PENDING");
        assertThat(status.at("/botCountsByState/value/ACTIVE").asLong()).isPositive();
        assertThat(status.at("/killSwitch/value/state").asText()).isEqualTo("OFF");
        assertThat(status.at("/rejectedOrdersToday/value").asLong())
                .isEqualTo(rejections.countRejectedSince(clock.instant().truncatedTo(ChronoUnit.DAYS)));
        assertThat(status.toString())
                .doesNotContain(owner.tenantId().toString())
                .doesNotContain(owner.userId().toString())
                .doesNotContain(bot.toString())
                .doesNotContain(admin.userId().toString());
    }

    /** BR3.3, NFR10.4: each of the six reads, slow by 2 s, is UNKNOWN/TIMEOUT; the whole answer stays under 1 s. */
    @Test
    void aSlowPartIsUnknownByTimeoutAndTheOthersAreShown() {
        for (String slow : List.of("identity", "bots", "killSwitch", "sources", "backlog", "rejections")) {
            SystemStatusAssembler assembler = assembler(slow, "");

            long started = System.nanoTime();
            SystemStatus status = assembler.assemble(newAdministrator());
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).as(slow).isLessThanOrEqualTo(Duration.ofSeconds(1));
            assertThat(unknownParts(status)).as(slow).containsExactlyInAnyOrderElementsOf(partsOf(slow));
            assertThat(reasons(status)).as(slow).containsOnly(SystemStatus.UnknownReason.TIMEOUT);
        }
    }

    /** BR3.3: a part that fails is UNKNOWN/UNAVAILABLE, never a zero. */
    @Test
    void aFailingPartIsUnknownAndNeverZero() {
        for (String down : List.of("identity", "bots", "killSwitch", "sources", "backlog", "rejections")) {
            SystemStatus status = assembler("", down).assemble(newAdministrator());

            assertThat(unknownParts(status)).as(down).containsExactlyInAnyOrderElementsOf(partsOf(down));
            assertThat(reasons(status)).as(down).containsOnly(SystemStatus.UnknownReason.UNAVAILABLE);
        }
    }

    /** The JSON of an unknown part has a status and a reason, and no value at all. */
    @Test
    void anUnknownPartHasNoValueInTheAnswer() throws Exception {
        SystemStatus status = assembler("", "rejections").assemble(newAdministrator());
        com.fasterxml.jackson.databind.JsonNode part =
                json.valueToTree(com.memetrade.administration.web.SystemStatusDtosAccess.view(status)
                        .rejectedOrdersToday());

        assertThat(part.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(part.get("reason").asText()).isEqualTo("UNAVAILABLE");
        assertThat(part.has("value")).isFalse();
    }

    /** BR5.1: the active tenants for the invitation form, never a suspended one. */
    @Test
    void theTenantsRouteListsOnlyActiveTenants() throws Exception {
        TestAccounts.Seeded active = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        TestAccounts.Seeded suspended = TestAccounts.seed(jdbc, SyntheticIds.mint(), "SUSPENDED", "ACTIVE");

        MockHttpServletResponse response = mvc.perform(get("/api/v1/admin/tenants")
                        .header("Authorization", sessionOf(newAdministrator()).bearer()))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String text = response.getContentAsString();
        assertThat(text)
                .contains(active.tenantId().toString())
                .contains(SYSTEM_TENANT.toString())
                .doesNotContain(suspended.tenantId().toString());
    }

    private MockHttpServletResponse systemStatus(AdminSessions.AdminSession admin) throws Exception {
        return mvc.perform(get("/api/v1/admin/system-status").header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse();
    }

    /** The real reads, except one slow (2 s) and one failing, by name. */
    private SystemStatusAssembler assembler(String slow, String down) {
        return new SystemStatusAssembler(
                new SystemStatusAssembler.Reads(
                        principal -> controlled("identity", slow, down, () -> new IdentityCounts(3, Map.of())),
                        () -> controlled("bots", slow, down, () -> Map.of(BotState.ACTIVE, 2L)),
                        () -> controlled("killSwitch", slow, down, KillSwitchSnapshot::initial),
                        () -> controlled("sources", slow, down, List::of),
                        () -> controlled("backlog", slow, down, () -> 4L),
                        from -> controlled("rejections", slow, down, () -> 5L)),
                pool,
                Duration.ofMillis(500),
                clock);
    }

    private static <T> T controlled(String name, String slow, String down, Supplier<T> value) {
        if (name.equals(down)) {
            throw new IllegalStateException("part down");
        }
        if (name.equals(slow)) {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return value.get();
    }

    private static List<String> partsOf(String read) {
        return switch (read) {
            case "identity" -> List.of("guestCount", "inviteCounts");
            case "bots" -> List.of("botCountsByState");
            case "killSwitch" -> List.of("killSwitch");
            case "sources" -> List.of("sources");
            case "backlog" -> List.of("auditOutboxPending");
            default -> List.of("rejectedOrdersToday");
        };
    }

    private static List<String> unknownParts(SystemStatus status) {
        Map<String, SystemStatus.Part<?>> parts = parts(status);
        return parts.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof SystemStatus.Part.Unknown<?>)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static List<SystemStatus.UnknownReason> reasons(SystemStatus status) {
        Function<SystemStatus.Part<?>, SystemStatus.UnknownReason> reason =
                part -> ((SystemStatus.Part.Unknown<?>) part).reason();
        return parts(status).values().stream()
                .filter(part -> part instanceof SystemStatus.Part.Unknown<?>)
                .map(reason)
                .toList();
    }

    private static Map<String, SystemStatus.Part<?>> parts(SystemStatus status) {
        return Map.of(
                "guestCount", status.guestCount(),
                "inviteCounts", status.inviteCounts(),
                "botCountsByState", status.botCountsByState(),
                "killSwitch", status.killSwitch(),
                "sources", status.sources(),
                "auditOutboxPending", status.auditOutboxPending(),
                "rejectedOrdersToday", status.rejectedOrdersToday());
    }
}
