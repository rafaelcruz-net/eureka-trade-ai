package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.domain.account.GuestAccountRepository;
import com.memetrade.identity.domain.invite.IdentityDirectory;
import com.memetrade.identity.domain.invite.Invite;
import com.memetrade.identity.domain.invite.InviteRepository;
import com.memetrade.identity.domain.legal.LegalSettings;
import com.memetrade.identity.domain.login.InviteAccountCreation;
import com.memetrade.identity.domain.login.LoginSettings;
import com.memetrade.identity.domain.login.RejectionReason;
import com.memetrade.identity.domain.session.AccountRepository;
import com.memetrade.identity.domain.session.SessionService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Passo 4 (BR1.16, NFR6.6): the existing acceptance (invitation) and the version lookup follow the activation. An
 * ignored version is never accepted nor found, and a future one is never found.
 */
@Tag("u1-legal-documents")
class CurrentTermsActivationTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final String CODE = "A".repeat(43);

    @TempDir
    Path dir;

    private TermsCatalog catalog;

    @BeforeEach
    void catalogWithAnIgnoredAndAFutureVersion() throws IOException {
        legacy("v1", "2026-01-01T00:00:00Z", false);
        nonLegacy("v2", "2026-06-01T00:00:00Z", true, false);
        nonLegacy("v3", "2026-12-01T00:00:00Z", false, true);
        catalog = new TermsCatalog(
                new PathMatchingResourcePatternResolver(),
                new TermsProperties(dir.toUri().toString()),
                LegalSettings.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theCurrentTermsAreTheEffectiveVersionNotTheMarkedOne() {
        assertThat(catalog.current().version()).isEqualTo("v1");
        assertThat(catalog.current().termsText()).isEqualTo("Termos antigos.");
    }

    @Test
    void findNeverReturnsAnIgnoredOrAFutureVersion() {
        assertThat(catalog.find("v1")).isPresent();
        assertThat(catalog.find("v2")).as("ignored: not legally reviewed").isEmpty();
        assertThat(catalog.find("v3")).as("future").isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }

    @Test
    void theInvitationRefusesTheIgnoredVersionAsOutdatedTerms() {
        Fixture fixture = new Fixture();

        InviteAccountCreation.Result result = fixture.creation.create(request("v2"), NOW);

        assertThat(result).isEqualTo(new InviteAccountCreation.Result.Refused(RejectionReason.TERMS_NOT_ACCEPTED));
        verify(fixture.guests, never()).createGuest(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void theInvitationRefusesAFutureVersionToo() {
        Fixture fixture = new Fixture();

        assertThat(fixture.creation.create(request("v3"), NOW))
                .isEqualTo(new InviteAccountCreation.Result.Refused(RejectionReason.TERMS_NOT_ACCEPTED));
    }

    @Test
    void theInvitationGoesPastTheTermsCheckWithTheEffectiveVersion() {
        Fixture fixture = new Fixture();

        // Passing the terms check reaches the account creation; the mocked session service opens nothing here.
        fixture.creation.create(request("v1"), NOW);

        verify(fixture.guests).createGuest(any(), any(), anyString(), anyString(), any());
    }

    private static InviteAccountCreation.Request request(String version) {
        return new InviteAccountCreation.Request("TestWallet1111", "PHANTOM", CODE, version, "corr-1");
    }

    /** InviteAccountCreation over this catalog, with a PENDING invitation of an ACTIVE tenant. */
    private final class Fixture {

        private final GuestAccountRepository guests = mock(GuestAccountRepository.class);
        private final InviteAccountCreation creation;

        private Fixture() {
            InviteRepository invites = mock(InviteRepository.class);
            IdentityDirectory directory = mock(IdentityDirectory.class);
            AccountRepository accounts = mock(AccountRepository.class);
            UUID tenant = UUID.randomUUID();
            Invite invite = new Invite(
                    UUID.randomUUID(),
                    tenant,
                    "digest",
                    "AAAA",
                    InviteStatus.PENDING,
                    "adm",
                    NOW.minusSeconds(60),
                    NOW.plus(Duration.ofDays(1)),
                    null,
                    null,
                    null);
            when(invites.findByDigestForUpdate(anyString())).thenReturn(Optional.of(invite));
            when(invites.markUsed(any(), any(), any())).thenReturn(true);
            when(directory.tenant(tenant))
                    .thenReturn(Optional.of(new IdentityDirectory.TenantState(tenant, "t", true)));
            when(accounts.findByWallet(anyString())).thenReturn(Optional.empty());
            LoginSettings settings = new LoginSettings(
                    URI.create("http://localhost:3000"), "solana:mainnet", Duration.ofMinutes(5), Set.of());
            creation = new InviteAccountCreation(
                    invites,
                    directory,
                    accounts,
                    guests,
                    catalog,
                    mock(SessionService.class),
                    mock(AuditEventPublisher.class),
                    settings);
        }
    }

    private void legacy(String name, String effectiveAt, boolean marked) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(name));
        Files.writeString(
                folder.resolve("version.properties"),
                "version=" + name + "\ncurrent=" + marked + "\nlegacy=true\neffectiveAt=" + effectiveAt + "\n");
        Files.writeString(folder.resolve("terms.txt"), "Termos antigos.", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("risk-notice.txt"), "Aviso antigo.", StandardCharsets.UTF_8);
    }

    private void nonLegacy(String name, String effectiveAt, boolean marked, boolean reviewed) throws IOException {
        Path folder = Files.createDirectories(dir.resolve(name));
        Files.writeString(
                folder.resolve("version.properties"),
                "version=" + name + "\ncurrent=" + marked
                        + "\neffectiveAt=" + effectiveAt + "\n"
                        + (reviewed ? "legalReviewedAt=2026-10-20\nlegalReviewedBy=Assessoria de teste\n" : ""));
        Files.writeString(folder.resolve("terms.txt"), "## Uso\n\nTermos novos.", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("privacy.txt"), "## Dados\n\nPolítica.", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("risk-notice.txt"), "## Riscos\n\nAviso.", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("terms-summary.txt"), "a\nb\nc\nd\ne\n", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("privacy-summary.txt"), "a\nb\nc\nd\ne\n", StandardCharsets.UTF_8);
        Files.writeString(
                folder.resolve("retention.tsv"), "Carteira\tEntrar\t12 meses\tAnonimizar\n", StandardCharsets.UTF_8);
    }
}
