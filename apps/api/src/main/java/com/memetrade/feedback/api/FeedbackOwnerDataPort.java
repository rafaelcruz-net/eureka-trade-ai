package com.memetrade.feedback.api;

import java.util.UUID;

/**
 * J3 (contract-summary.md): what the Privacy module may know of, and do to, the comments of a person, without reading a
 * table of Feedback. u4-data-requests adds the count for the Access summary (BR4.8); u5-anonymization adds the erasure of
 * the comments of the anonymized person (BR5.2, BR5.8). Never the text of a comment.
 */
public interface FeedbackOwnerDataPort {

    /** The comments of the person in that tenant still kept (at most 180 days old); 0 when there is none. */
    long countByUser(UUID tenantId, UUID userId);

    /**
     * u5-anonymization (BR5.8, NFR2.3): runs only inside an existing transaction (Propagation.MANDATORY). Erases every
     * comment of the person in that tenant through the database function {@code erase_feedback_of_user} (SECURITY
     * DEFINER), the only way the application deletes a comment; a direct DELETE stays refused by the trigger.
     *
     * @return the number of comments erased
     */
    int deleteByUser(UUID tenantId, UUID userId);
}
