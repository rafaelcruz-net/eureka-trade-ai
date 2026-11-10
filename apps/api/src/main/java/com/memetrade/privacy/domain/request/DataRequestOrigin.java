package com.memetrade.privacy.domain.request;

/**
 * Where a request came from (entities.md DataSubjectRequest.origin): the Account of the person, authenticated, or the
 * e-mail of the DPO, registered by the administrator with the contact.
 */
public enum DataRequestOrigin {
    ACCOUNT,
    EMAIL
}
