package com.memetrade.feedback.domain;

/** What the submission reports to the metrics (observability-design.md, NFR9.2); closed values only. */
public interface FeedbackEvents {

    /** Why a submission was refused: the {@code reason} label of {@code feedback.refused_total}. */
    enum Refusal {
        VALIDATION,
        RATE_LIMITED,
        IDEMPOTENCY_CONFLICT
    }

    void received(FeedbackScreen screen);

    void refused(Refusal reason);

    /** No-op, for wiring without metrics. */
    FeedbackEvents NONE = new FeedbackEvents() {
        @Override
        public void received(FeedbackScreen screen) {}

        @Override
        public void refused(Refusal reason) {}
    };
}
