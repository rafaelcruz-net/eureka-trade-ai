package com.memetrade.privacy.domain.anonymization;

import com.memetrade.identity.api.Principal;
import com.memetrade.privacy.domain.request.DataSubjectRequest;

/**
 * J6 of u5-anonymization (NFR8.2): {@code ACCOUNT_ANONYMIZED} by the administrator, in the transaction of the
 * anonymization, with the internal identifier of the account as target and only the three counts as details. Never for
 * a repetition (BR5.5).
 */
public interface AnonymizationEvents {

    void accountAnonymized(DataSubjectRequest request, AnonymizationResult result, Principal admin);
}
