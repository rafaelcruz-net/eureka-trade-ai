package com.memetrade.riskcontrol.domain;

import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.domain.RejectionRepository.PageKey;
import com.memetrade.riskcontrol.domain.RejectionRepository.RejectionPage;
import java.util.Optional;
import java.util.UUID;

/**
 * F3, the reading of C21 (BR4.1, BR4.2): only the person of the token, in their tenant, in PAPER. Without any
 * evaluation of the bot for that person the answer is empty (the route answers 404): another person's bot, evaluated
 * or not, another tenant's bot, an unknown bot, the person's bot never evaluated and a bot whose evaluations were all
 * purged look the same, and nothing reveals that a bot of someone else exists. U5 is never consulted.
 */
public final class RejectionQueries {

    private final EvaluationRepository evaluations;
    private final RejectionRepository rejections;

    public RejectionQueries(EvaluationRepository evaluations, RejectionRepository rejections) {
        this.evaluations = evaluations;
        this.rejections = rejections;
    }

    public Optional<RejectionPage> page(Principal person, UUID botId, Optional<PageKey> after, int limit) {
        if (!evaluations.existsForPerson(person.tenantId(), person.userId(), botId)) {
            return Optional.empty();
        }
        return Optional.of(rejections.page(person.tenantId(), person.userId(), botId, after, limit));
    }
}
