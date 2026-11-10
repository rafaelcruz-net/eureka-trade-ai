package com.memetrade.privacy.infra;

import com.memetrade.bots.api.BotOwnerDataPort;
import com.memetrade.feedback.api.FeedbackOwnerDataPort;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.ledger.api.PaperOrderCountPort;
import com.memetrade.privacy.domain.request.AccessSummary;
import com.memetrade.privacy.domain.request.AccessSummarySource;
import com.memetrade.privacy.domain.request.DataRequestStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * BR4.8, NFR1.2, NFR3.3: the Access summary of ONE person, from J1 ({@code personSummary}, the wallet already masked
 * by Identity), J2 (bots), J4 (PAPER orders), J3 (comments) and her own requests (type, state and reception only). Each
 * call is an indexed read by person in its own module; no table of another module is read here.
 */
@Component
public class AccessSummaryAssembler implements AccessSummarySource {

    private final PersonDataPort people;
    private final BotOwnerDataPort bots;
    private final PaperOrderCountPort orders;
    private final FeedbackOwnerDataPort feedback;
    private final DataRequestStore requests;

    public AccessSummaryAssembler(
            PersonDataPort people,
            BotOwnerDataPort bots,
            PaperOrderCountPort orders,
            FeedbackOwnerDataPort feedback,
            DataRequestStore requests) {
        this.people = people;
        this.bots = bots;
        this.orders = orders;
        this.feedback = feedback;
        this.requests = requests;
    }

    @Override
    public Optional<AccessSummary> assemble(UUID tenantId, UUID userId) {
        return people.personSummary(tenantId, userId)
                .map(person -> new AccessSummary(
                        person.walletMasked(),
                        person.provider(),
                        person.accountCreatedAt(),
                        person.lastLoginAt(),
                        person.acceptedVersions(),
                        bots.countOwnerBots(tenantId, userId),
                        orders.countPaperOrders(tenantId, userId),
                        feedback.countByUser(tenantId, userId),
                        requests.listOwned(tenantId, userId).stream()
                                .map(request -> new AccessSummary.RequestLine(
                                        request.type(), request.status(), request.receivedAt()))
                                .toList()));
    }
}
