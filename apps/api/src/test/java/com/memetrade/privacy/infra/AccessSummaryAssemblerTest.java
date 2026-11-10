package com.memetrade.privacy.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import com.memetrade.privacy.domain.request.AccessSummary;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestStore;
import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.privacy.domain.request.DataSubjectRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Passo 6 of u4-data-requests (after the implementation; BR4.8, NFR1.2): the Access summary is assembled from J1 to J4
 * and the requests of the SAME person only, the wallet already masked by Identity, the text of the requests never in
 * it; and the snapshot survives the round trip of its JSON column.
 */
@Tag("u4-data-requests")
class AccessSummaryAssemblerTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-00000000f002");
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private final PersonDataPort people = Mockito.mock(PersonDataPort.class);
    private final DataRequestStore requests = Mockito.mock(DataRequestStore.class);

    private AccessSummaryAssembler assembler() {
        return new AccessSummaryAssembler(
                people,
                bots(),
                (tenant, owner) -> tenant.equals(TENANT) && owner.equals(USER) ? 7 : 99,
                feedback(),
                requests);
    }

    /** u5-anonymization: J2 and J3 gained the anonymization, so they are no longer functional interfaces. */
    private static com.memetrade.bots.api.BotOwnerDataPort bots() {
        com.memetrade.bots.api.BotOwnerDataPort bots = Mockito.mock(com.memetrade.bots.api.BotOwnerDataPort.class);
        Mockito.when(bots.countOwnerBots(Mockito.any(), Mockito.any())).thenReturn(99L);
        Mockito.when(bots.countOwnerBots(TENANT, USER)).thenReturn(2L);
        return bots;
    }

    private static com.memetrade.feedback.api.FeedbackOwnerDataPort feedback() {
        com.memetrade.feedback.api.FeedbackOwnerDataPort feedback =
                Mockito.mock(com.memetrade.feedback.api.FeedbackOwnerDataPort.class);
        Mockito.when(feedback.countByUser(Mockito.any(), Mockito.any())).thenReturn(99L);
        Mockito.when(feedback.countByUser(TENANT, USER)).thenReturn(1L);
        return feedback;
    }

    @Test
    void theSummaryCarriesTheCountsOfThePersonAndHerRequestsWithoutTheirText() {
        Mockito.when(people.personSummary(TENANT, USER))
                .thenReturn(Optional.of(new PersonDataPort.PersonSummary(
                        "Abcd…wxyz",
                        "PHANTOM",
                        CREATED,
                        Optional.of(CREATED.plusSeconds(60)),
                        List.of(new AcceptedVersion("v1", CREATED)))));
        DataSubjectRequest own = DataSubjectRequest.fromAccount(
                UUID.randomUUID(), TENANT, USER, DataRequestType.ACCESS, "texto privado", CREATED, Duration.ofDays(15));
        Mockito.when(requests.listOwned(TENANT, USER)).thenReturn(List.of(own));

        AccessSummary summary = assembler().assemble(TENANT, USER).orElseThrow();

        assertThat(summary.walletMasked()).isEqualTo("Abcd…wxyz");
        assertThat(summary.botCount()).isEqualTo(2);
        assertThat(summary.paperOrderCount()).isEqualTo(7);
        assertThat(summary.feedbackCount()).isEqualTo(1);
        assertThat(summary.requests())
                .containsExactly(
                        new AccessSummary.RequestLine(DataRequestType.ACCESS, DataRequestStatus.RECEIVED, CREATED));
        assertThat(summary.toString()).doesNotContain("texto privado");
        Mockito.verify(requests).listOwned(TENANT, USER);
    }

    @Test
    void aPersonWithoutASummaryHasNone() {
        Mockito.when(people.personSummary(TENANT, USER)).thenReturn(Optional.empty());

        assertThat(assembler().assemble(TENANT, USER)).isEmpty();
        Mockito.verifyNoInteractions(requests);
    }

    @Test
    void theSnapshotSurvivesTheRoundTripOfItsJsonColumn() {
        AccessSummary summary = new AccessSummary(
                "Abcd…wxyz",
                "SOLFLARE",
                CREATED,
                Optional.empty(),
                List.of(new AcceptedVersion("v2", CREATED.plusSeconds(5))),
                3,
                4,
                5,
                List.of(new AccessSummary.RequestLine(DataRequestType.OTHER, DataRequestStatus.DONE, CREATED)));

        String json = AccessSummaryJson.write(summary);

        assertThat(AccessSummaryJson.read(json)).isEqualTo(summary);
        assertThat(json).contains("\"lastLoginAt\":null");
    }

    @Test
    void anUnreadableSnapshotFailsLoudly() {
        assertThatThrownBy(() -> AccessSummaryJson.read("{\"walletMasked\":"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Access summary");
    }

    @Test
    void negativeCountsAreRefused() {
        assertThatThrownBy(() -> new AccessSummary("x", "y", CREATED, Optional.empty(), List.of(), -1, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
