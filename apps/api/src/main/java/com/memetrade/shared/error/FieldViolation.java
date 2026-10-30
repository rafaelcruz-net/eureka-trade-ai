package com.memetrade.shared.error;

/** One invalid input field reported in {@code ProblemResponse.fields}. */
public record FieldViolation(String field, String message) {}
