package com.memetrade.privacy.domain.request;

/**
 * The audit events and counters of the requests (BR4.6, NFR1.1, NFR8.1, NFR8.4): each carries only the identifier,
 * the type, the origin and the states, never the text, the contact, the response, the reason, the message or the
 * signature. Published in the transaction of the change.
 */
public interface DataRequestEvents {

    /** DATA_REQUEST_CREATED. */
    void created(DataSubjectRequest request, Actor actor);

    /** DATA_REQUEST_TRANSITIONED from {@code from} to the state of {@code request}. */
    void transitioned(DataSubjectRequest request, DataRequestStatus from, Actor actor);

    /** DATA_REQUEST_VERIFICATION_STARTED (RECEIVED to AWAITING_VERIFICATION). */
    void verificationStarted(DataSubjectRequest request, Actor actor);

    /** DATA_REQUEST_VERIFIED (AWAITING_VERIFICATION to IN_PROGRESS). */
    void verified(DataSubjectRequest request, Actor actor);

    /** DATA_REQUEST_OVERDUE, by the system, once per request. */
    void overdue(DataSubjectRequest request);
}
