package com.memetrade.riskassessment.api;

/** Life cycle of a weight version (functional-spec.md): DRAFT, then ACTIVE, then RETIRED; never back. */
public enum WeightSetStatus {
    DRAFT,
    ACTIVE,
    RETIRED
}
